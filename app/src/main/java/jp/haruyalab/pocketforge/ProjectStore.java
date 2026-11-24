package jp.haruyalab.pocketforge;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Local project storage. Only source/ is ever exposed to a preview or agent. */
public final class ProjectStore {
    public static final long MAX_FILE_BYTES = 8L * 1024 * 1024;
    public static final long MAX_PROJECT_BYTES = 32L * 1024 * 1024;
    public static final int MAX_ENTRIES = 2000;
    private static final int MAX_SNAPSHOTS = 12;
    private static final long MAX_CONVERSATION_BYTES = 4L * 1024 * 1024;
    private final File base;
    private final File projects;

    public ProjectStore(Context context) {
        this(new File(context.getApplicationContext().getFilesDir(), "pocketforge"));
    }

    /** Also useful for JVM storage tests; the directory is owned by this store. */
    public ProjectStore(File directory) {
        try {
            base = directory.getCanonicalFile();
            ensureDirectory(base);
            projects = new File(base, "projects");
            ensureDirectory(projects);
        } catch (IOException e) { throw new IllegalStateException("Cannot open project storage", e); }
    }

    public synchronized JSONArray listProjects() throws Exception {
        List<JSONObject> result = new ArrayList<>();
        File[] dirs = projects.listFiles();
        if (dirs == null) throw new IOException("Cannot list projects");
        for (File dir : dirs) {
            if (!validUuid(dir.getName()) || Files.isSymbolicLink(dir.toPath()) || !dir.isDirectory()) continue;
            try { result.add(getProject(dir.getName())); }
            catch (Exception ignored) {
                // Preserve a visible handle so a damaged project can still be located/deleted.
                result.add(new JSONObject().put("id", dir.getName()).put("name", "Damaged project")
                        .put("createdAt", 0).put("updatedAt", 0).put("entryPoint", "index.html").put("damaged", true));
            }
        }
        result.sort((a, b) -> Long.compare(b.optLong("updatedAt"), a.optLong("updatedAt")));
        return new JSONArray(result);
    }

    public synchronized JSONObject createProject(String name) throws Exception {
        String id = UUID.randomUUID().toString();
        File dir = new File(projects, id);
        ensureDirectory(dir);
        try {
            ensureDirectory(new File(dir, "source"));
            long now = System.currentTimeMillis();
            JSONObject meta = new JSONObject().put("id", id).put("name", cleanName(name))
                    .put("createdAt", now).put("updatedAt", now).put("entryPoint", "index.html");
            writeJson(new File(dir, "meta.json"), meta.toString());
            writeJson(new File(dir, "conversation.json"), "[]");
            return meta;
        } catch (Exception e) { removeTree(dir); throw e; }
    }

    public synchronized JSONObject getProject(String id) throws Exception {
        JSONObject meta = new JSONObject(readUtf8(new File(project(id), "meta.json"), MAX_CONVERSATION_BYTES));
        if (!id.equals(meta.optString("id"))) throw new IOException("Invalid project metadata");
        return meta;
    }

    public synchronized void renameProject(String id, String name) throws Exception {
        JSONObject meta = getProject(id).put("name", cleanName(name)).put("updatedAt", System.currentTimeMillis());
        writeJson(new File(project(id), "meta.json"), meta.toString());
    }

    public synchronized void deleteProject(String id) throws IOException { removeTree(project(id)); }

    public synchronized File projectDirectory(String id) throws IOException {
        File source = new File(project(id), "source");
        ensureDirectory(source);
        return source;
    }

    public synchronized JSONArray listFiles(String id) throws Exception {
        JSONArray out = new JSONArray();
        for (FileItem item : scan(projectDirectory(id))) {
            out.put(new JSONObject().put("path", item.path).put("size", item.directory ? 0 : item.file.length())
                    .put("directory", item.directory));
        }
        return out;
    }

    public synchronized String readText(String id, String path) throws IOException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(readBytes(id, path))).toString();
    }

    public synchronized byte[] readBytes(String id, String path) throws IOException {
        File target = resolve(projectDirectory(id), path);
        if (!target.isFile()) throw new IOException("File does not exist: " + path);
        try (InputStream in = new FileInputStream(target)) { return readLimited(in, MAX_FILE_BYTES); }
    }

    synchronized boolean pathExists(String id, String path) throws IOException {
        return resolve(projectDirectory(id), path).exists();
    }

    public synchronized void writeText(String id, String path, String content) throws Exception {
        if (content == null) throw new IOException("Content is required");
        writeBytes(id, path, content.getBytes(StandardCharsets.UTF_8));
    }

    public synchronized void writeBytes(String id, String path, byte[] content) throws Exception {
        if (content == null || content.length > MAX_FILE_BYTES) throw new IOException("File exceeds 8 MB limit");
        File source = projectDirectory(id);
        File target = resolve(source, path);
        if (target.isDirectory()) throw new IOException("Path is a directory");
        List<FileItem> items = scan(source);
        long total = totalBytes(items) - (target.exists() ? target.length() : 0) + content.length;
        if (total > MAX_PROJECT_BYTES) throw new IOException("Project exceeds 32 MB limit");
        int missing = missingComponents(source, target);
        if (items.size() + missing > MAX_ENTRIES) throw new IOException("Project exceeds 2000 entries");
        ensureDirectory(target.getParentFile());
        atomicWrite(target, content);
        touch(id);
    }

    public synchronized void mkdir(String id, String path) throws Exception {
        File source = projectDirectory(id);
        File dir = resolve(source, path);
        if (scan(source).size() + missingComponents(source, dir) > MAX_ENTRIES)
            throw new IOException("Project exceeds 2000 entries");
        ensureDirectory(dir);
        touch(id);
    }

    public synchronized void deleteFile(String id, String path) throws Exception {
        File target = resolve(projectDirectory(id), path);
        if (!target.exists()) throw new IOException("Path does not exist");
        removeTree(target);
        touch(id);
    }

    public synchronized JSONArray loadConversation(String id) throws Exception {
        File file = new File(project(id), "conversation.json");
        return file.exists() ? new JSONArray(readUtf8(file, MAX_CONVERSATION_BYTES)) : new JSONArray();
    }

    public synchronized void saveConversation(String id, JSONArray conversation) throws Exception {
        String json = conversation.toString();
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_CONVERSATION_BYTES)
            throw new IOException("Conversation exceeds 4 MB limit; start a new conversation");
        writeJson(new File(project(id), "conversation.json"), json);
        touch(id);
    }

    public synchronized JSONObject summary(String id) throws Exception {
        JSONObject meta = getProject(id);
        List<FileItem> items = scan(projectDirectory(id));
        int count = 0;
        for (FileItem item : items) if (!item.directory) count++;
        JSONArray conversation = loadConversation(id);
        String last = "";
        if (conversation.length() > 0) {
            JSONObject message = conversation.optJSONObject(conversation.length() - 1);
            if (message != null) last = message.optString("content", "");
        }
        return meta.put("fileCount", count).put("bytes", totalBytes(items)).put("lastMessage", last);
    }

    public synchronized JSONArray history(String id) throws Exception {
        File root = new File(project(id), "snapshots");
        if (!root.exists()) return new JSONArray();
        checkNotLink(root);
        List<JSONObject> result = new ArrayList<>();
        File[] children = root.listFiles();
        if (children == null) throw new IOException("Cannot list snapshots");
        for (File dir : children) {
            if (!validSnapshotId(dir.getName()) || !dir.isDirectory()) continue;
            checkNotLink(dir);
            JSONObject meta = new JSONObject(readUtf8(new File(dir, "meta.json"), MAX_CONVERSATION_BYTES));
            if (!dir.getName().equals(meta.optString("id"))) throw new IOException("Invalid snapshot metadata");
            result.add(meta);
        }
        result.sort((a, b) -> Long.compare(b.optLong("createdAt"), a.optLong("createdAt")));
        return new JSONArray(result);
    }

    public synchronized String snapshot(String id, String label) throws Exception {
        File project = project(id);
        File root = new File(project, "snapshots");
        ensureDirectory(root);
        JSONArray existing = history(id);
        long now = Math.max(System.currentTimeMillis(), existing.length() == 0 ? 0 : existing.getJSONObject(0).getLong("createdAt") + 1);
        String sid = now + "-" + UUID.randomUUID().toString();
        File stage = new File(root, ".pending-" + sid);
        File destination = new File(root, sid);
        ensureDirectory(stage);
        try {
            File source = projectDirectory(id);
            List<FileItem> items = scan(source);
            copySource(source, new File(stage, "files"));
            writeJson(new File(stage, "conversation.json"), loadConversation(id).toString());
            writeJson(new File(stage, "meta.json"), new JSONObject().put("id", sid)
                    .put("label", label == null ? "Checkpoint" : truncate(label, 120)).put("createdAt", now)
                    .put("fileCount", items.stream().filter(i -> !i.directory).count())
                    .put("bytes", totalBytes(items)).toString());
            move(stage, destination);
            JSONArray all = history(id);
            for (int i = MAX_SNAPSHOTS; i < all.length(); i++)
                removeTree(new File(root, all.getJSONObject(i).getString("id")));
            return sid;
        } finally { if (stage.exists()) removeTree(stage); }
    }

    public synchronized void restore(String id, String snapshotId) throws Exception {
        if (!validSnapshotId(snapshotId)) throw new IOException("Invalid snapshot ID");
        File project = project(id);
        File selected = new File(new File(project, "snapshots"), snapshotId);
        checkNotLink(selected.getParentFile());
        checkNotLink(selected);
        if (!selected.isDirectory()) throw new IOException("Snapshot does not exist");
        File staged = new File(project, ".restore-" + UUID.randomUUID());
        File previous = new File(project, ".previous-" + UUID.randomUUID());
        File source = projectDirectory(id);
        String conversation = readUtf8(new File(selected, "conversation.json"), MAX_CONVERSATION_BYTES);
        new JSONArray(conversation);
        String priorConversation = loadConversation(id).toString();
        try {
            // Stage first: creating the safety snapshot can prune the selected old snapshot.
            copySource(new File(selected, "files"), staged);
            snapshot(id, "Before restore");
            move(source, previous);
            try {
                move(staged, source);
                writeJson(new File(project, "conversation.json"), conversation);
                touch(id);
            } catch (Exception e) {
                if (source.exists()) removeTree(source);
                move(previous, source);
                writeJson(new File(project, "conversation.json"), priorConversation);
                throw e;
            }
            removeTree(previous);
        } finally { if (staged.exists()) removeTree(staged); }
    }

    /** Own export includes conversation; credentials and snapshot history are never exported. */
    public synchronized void exportZip(String id, OutputStream stream) throws Exception {
        File source = projectDirectory(id);
        List<FileItem> items = scan(source);
        JSONObject envelope = new JSONObject().put("app", "PocketForge").put("version", 1)
                .put("project", getProject(id)).put("conversation", loadConversation(id));
        // Closing ZIP frees its compressor; this wrapper keeps the caller's stream open.
        try (ZipOutputStream zip = new ZipOutputStream(new FilterOutputStream(stream) {
            @Override public void close() throws IOException { flush(); }
        })) {
        zip.putNextEntry(new ZipEntry("pocketforge.json"));
        zip.write(envelope.toString().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
        zip.putNextEntry(new ZipEntry("files/"));
        zip.closeEntry();
        for (FileItem item : items) {
            zip.putNextEntry(new ZipEntry("files/" + item.path + (item.directory ? "/" : "")));
            if (!item.directory) try (InputStream in = new FileInputStream(item.file)) { transfer(in, zip, MAX_FILE_BYTES); }
            zip.closeEntry();
        }
        zip.finish();
        zip.flush();
        }
    }

    /** Imports an exported workspace or a regular source ZIP, always under a new project ID. */
    public synchronized String importZip(InputStream stream, String suggestedName) throws Exception {
        File stage = new File(base, ".import-" + UUID.randomUUID());
        ensureDirectory(stage);
        String newId = null;
        try {
            Set<String> seen = new HashSet<>();
            long total = 0;
            int count = 0;
            // A ZIP is allowed one envelope + source-root entry beyond the project entry limit.
            try (ZipInputStream zip = new ZipInputStream(new FilterInputStream(stream) {
                @Override public void close() { }
            })) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++count > MAX_ENTRIES + 2) throw new IOException("Archive has too many entries");
                String name = entry.getName();
                if (entry.isDirectory() && name.endsWith("/")) name = name.substring(0, name.length() - 1);
                File target = resolve(stage, name);
                if (!seen.add(name)) throw new IOException("Duplicate archive path");
                if (entry.isDirectory()) ensureDirectory(target);
                else {
                    ensureDirectory(target.getParentFile());
                    try (OutputStream out = new FileOutputStream(target)) {
                        total += transfer(zip, out, MAX_FILE_BYTES);
                    }
                    if (total > MAX_PROJECT_BYTES + MAX_CONVERSATION_BYTES + 65536)
                        throw new IOException("Archive expands beyond size limit");
                }
                zip.closeEntry();
            }
            }
            if (count == 0) throw new IOException("Archive is empty or is not a ZIP");
            File source = stage;
            JSONArray conversation = new JSONArray();
            String name = suggestedName;
            String entryPoint = "index.html";
            File manifest = new File(stage, "pocketforge.json");
            if (manifest.isFile()) {
                JSONObject envelope = null;
                try { envelope = new JSONObject(readUtf8(manifest, MAX_FILE_BYTES)); }
                catch (org.json.JSONException ignored) { /* Ordinary source file with the same name. */ }
                if (envelope != null && "PocketForge".equals(envelope.optString("app"))) {
                    if (envelope.optInt("version") != 1) throw new IOException("Unsupported project archive version");
                    source = new File(stage, "files");
                    if (!source.isDirectory()) throw new IOException("Archive source directory is missing");
                    JSONObject meta = envelope.optJSONObject("project");
                    if (meta != null) {
                        if (name == null || name.trim().isEmpty()) name = meta.optString("name");
                        entryPoint = meta.optString("entryPoint", "index.html");
                        resolve(source, entryPoint);
                    }
                    JSONArray saved = envelope.optJSONArray("conversation");
                    if (saved != null) conversation = saved;
                }
            }
            scan(source); // Includes implicit parent directories in project size/count validation.
            JSONObject meta = createProject(name == null || name.trim().isEmpty() ? "Imported project" : name);
            newId = meta.getString("id");
            copySource(source, projectDirectory(newId));
            saveConversation(newId, conversation);
            meta.put("entryPoint", entryPoint).put("updatedAt", System.currentTimeMillis());
            writeJson(new File(project(newId), "meta.json"), meta.toString());
            return newId;
        } catch (Exception e) {
            if (newId != null) removeTree(new File(projects, newId));
            throw e;
        } finally { removeTree(stage); }
    }

    private File project(String id) throws IOException {
        if (!validUuid(id)) throw new IOException("Invalid project ID");
        checkNotLink(projects);
        File dir = new File(projects, id);
        checkNotLink(dir);
        if (!dir.isDirectory()) throw new IOException("Project does not exist");
        return dir;
    }

    private static boolean validUuid(String id) {
        return id != null && id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    private static boolean validSnapshotId(String id) {
        return id != null && id.matches("[0-9]{13,17}-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    private static String cleanName(String name) throws IOException {
        String value = name == null ? "Untitled project" : name.trim();
        if (value.isEmpty()) value = "Untitled project";
        if (value.length() > 100 || value.chars().anyMatch(c -> Character.isISOControl(c)))
            throw new IOException("Project name must be 1–100 printable characters");
        return value;
    }

    private static String truncate(String value, int max) { return value.length() <= max ? value : value.substring(0, max); }

    private void touch(String id) throws Exception {
        JSONObject meta = getProject(id).put("updatedAt", System.currentTimeMillis());
        writeJson(new File(project(id), "meta.json"), meta.toString());
    }

    private static File resolve(File root, String path) throws IOException {
        checkNotLink(root);
        if (path == null || path.isEmpty() || path.length() > 1024 || path.startsWith("/")
                || path.indexOf('\\') >= 0 || path.indexOf(':') >= 0)
            throw new IOException("Invalid relative file path");
        File target = root;
        for (String part : path.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.length() > 255
                    || part.chars().anyMatch(c -> Character.isISOControl(c)))
                throw new IOException("Unsafe file path");
            target = new File(target, part);
            checkNotLink(target);
        }
        String prefix = root.getCanonicalPath() + File.separator;
        if (!target.getCanonicalPath().startsWith(prefix)) throw new IOException("Path escapes project");
        return target;
    }

    private static void checkNotLink(File file) throws IOException {
        if (Files.isSymbolicLink(file.toPath())) throw new IOException("Symbolic links are not supported");
    }

    private static void ensureDirectory(File file) throws IOException {
        checkNotLink(file);
        if (!file.isDirectory() && !file.mkdirs() && !file.isDirectory()) throw new IOException("Cannot create directory");
    }

    private static int missingComponents(File root, File target) throws IOException {
        int n = 0;
        File file = target;
        while (!file.equals(root)) {
            if (file == null) throw new IOException("Path escapes project");
            if (!file.exists()) n++;
            file = file.getParentFile();
        }
        return n;
    }

    private static final class FileItem {
        final File file;
        final String path;
        final boolean directory;
        FileItem(File f, String p) { file = f; path = p; directory = f.isDirectory(); }
    }

    private static List<FileItem> scan(File root) throws IOException {
        checkNotLink(root);
        if (!root.isDirectory()) throw new IOException("Source directory is missing");
        List<FileItem> items = new ArrayList<>();
        scanChildren(root, "", items, new long[]{0});
        items.sort((a, b) -> a.directory != b.directory ? (a.directory ? -1 : 1) : a.path.compareTo(b.path));
        return items;
    }

    private static void scanChildren(File root, String prefix, List<FileItem> items, long[] bytes) throws IOException {
        File[] children = root.listFiles();
        if (children == null) throw new IOException("Cannot list project files");
        for (File child : children) {
            checkNotLink(child);
            String path = prefix + child.getName();
            if (path.length() > 1024) throw new IOException("Project path too long");
            if (!child.isFile() && !child.isDirectory()) throw new IOException("Special files are not supported");
            items.add(new FileItem(child, path));
            if (items.size() > MAX_ENTRIES) throw new IOException("Project exceeds 2000 entries");
            if (child.isDirectory()) scanChildren(child, path + "/", items, bytes);
            else {
                if (child.length() > MAX_FILE_BYTES) throw new IOException("File exceeds 8 MB limit");
                bytes[0] += child.length();
                if (bytes[0] > MAX_PROJECT_BYTES) throw new IOException("Project exceeds 32 MB limit");
            }
        }
    }

    private static long totalBytes(List<FileItem> items) {
        long n = 0;
        for (FileItem i : items) if (!i.directory) n += i.file.length();
        return n;
    }

    private static void copySource(File source, File destination) throws IOException {
        List<FileItem> items = scan(source);
        ensureDirectory(destination);
        for (FileItem item : items) {
            File target = resolve(destination, item.path);
            if (item.directory) ensureDirectory(target);
            else {
                ensureDirectory(target.getParentFile());
                try (InputStream in = new FileInputStream(item.file); OutputStream out = new FileOutputStream(target)) {
                    transfer(in, out, MAX_FILE_BYTES);
                }
            }
        }
    }

    private static long transfer(InputStream in, OutputStream out, long limit) throws IOException {
        byte[] buffer = new byte[16384];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > limit) throw new IOException("File exceeds size limit");
            out.write(buffer, 0, read);
        }
        return total;
    }

    private static byte[] readLimited(InputStream in, long limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        transfer(in, out, limit);
        return out.toByteArray();
    }

    private static String readUtf8(File file, long limit) throws IOException {
        checkNotLink(file);
        try (InputStream in = new FileInputStream(file)) { return new String(readLimited(in, limit), StandardCharsets.UTF_8); }
    }

    private static void writeJson(File file, String json) throws IOException {
        atomicWrite(file, json.getBytes(StandardCharsets.UTF_8));
    }

    private static void atomicWrite(File target, byte[] bytes) throws IOException {
        checkNotLink(target);
        File temp = File.createTempFile(".write-", ".tmp", target.getParentFile());
        try {
            try (FileOutputStream out = new FileOutputStream(temp)) { out.write(bytes); out.getFD().sync(); }
            move(temp, target);
        } finally { if (temp.exists() && !temp.delete()) temp.deleteOnExit(); }
    }

    private static void move(File source, File target) throws IOException {
        checkNotLink(source);
        checkNotLink(target);
        try { Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void removeTree(File file) throws IOException {
        if (!Files.isSymbolicLink(file.toPath()) && file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) throw new IOException("Cannot read directory for removal");
            for (File child : children) removeTree(child);
        }
        if (file.exists() || Files.isSymbolicLink(file.toPath())) {
            if (!file.delete()) throw new IOException("Cannot remove " + file.getName());
        }
    }
}
