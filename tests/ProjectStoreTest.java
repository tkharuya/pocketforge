import jp.haruyalab.pocketforge.ProjectStore;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Run with a JVM org.json implementation; Android framework is only needed at compile time. */
public final class ProjectStoreTest {
    private static int checks;
    private interface Checked { void run() throws Exception; }
    private static void check(boolean value, String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }
    private static void rejects(Checked action, String label) throws Exception {
        try { action.run(); } catch (IOException e) { checks++; return; }
        throw new AssertionError("Expected rejection: " + label);
    }
    private static byte[] zip(String[] names, byte[][] contents) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (int i = 0; i < names.length; i++) {
                zip.putNextEntry(new ZipEntry(names[i]));
                zip.write(contents[i]);
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }
    private static void deleteTree(Path root) throws Exception {
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(path);
        }
    }
    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("pocketforge-store-test-");
        try {
            ProjectStore store = new ProjectStore(temp.resolve("store").toFile());
            String id = store.createProject("Test project").getString("id");
            check(store.listProjects().length() == 1, "created project listed");
            check(store.getProject(id).getString("entryPoint").equals("index.html"), "default entry point");
            store.writeText(id, "index.html", "<h1>こんにちは</h1>");
            store.writeText(id, "src/main.js", "console.log('first');");
            store.mkdir(id, "assets/empty");
            byte[] picture = new byte[]{0, 1, -1, 42};
            store.writeBytes(id, "assets/photo.bin", picture);
            check(store.readText(id, "index.html").contains("こんにちは"), "UTF-8 round trip");
            check(Arrays.equals(picture, store.readBytes(id, "assets/photo.bin")), "binary round trip");
            rejects(() -> store.readText(id, "assets/photo.bin"), "invalid UTF-8 is not silently changed into text");
            check(store.summary(id).getInt("fileCount") == 3, "summary counts only files");
            check(store.listFiles(id).length() == 6, "tree includes directories");
            for (String path : new String[]{"", "../outside", "/tmp/outside", "a/../../outside", "a//b", "a/./b", "a\\b", "C:/x", "a\u0000b"}) {
                rejects(() -> store.writeText(id, path, "bad"), "unsafe path " + path);
            }
            rejects(() -> store.projectDirectory("../outside"), "invalid project ID");
            rejects(() -> store.deleteFile(id, ".."), "source root delete");
            Path outside = temp.resolve("outside");
            Files.createDirectories(outside);
            Files.writeString(outside.resolve("secret"), "untouched");
            Path link = store.projectDirectory(id).toPath().resolve("linked");
            Files.createSymbolicLink(link, outside);
            rejects(() -> store.readText(id, "linked/secret"), "read through symlink");
            rejects(() -> store.writeText(id, "linked/new", "bad"), "write through symlink");
            rejects(() -> store.listFiles(id), "tree rejects symlink");
            Files.delete(link);
            check(Files.readString(outside.resolve("secret")).equals("untouched"), "external data untouched");

            JSONArray originalConversation = new JSONArray().put(new JSONObject().put("role", "user").put("content", "Build it"))
                    .put(new JSONObject().put("type", "function_call").put("call_id", "call_1").put("arguments", "{}"));
            store.saveConversation(id, originalConversation);
            String before = store.snapshot(id, "Before editing");
            store.writeText(id, "index.html", "changed");
            store.deleteFile(id, "src");
            store.saveConversation(id, new JSONArray().put(new JSONObject().put("type", "function_call_output").put("output", "done")));
            store.restore(id, before);
            check(store.readText(id, "index.html").contains("こんにちは"), "snapshot restores text");
            check(store.readText(id, "src/main.js").contains("first"), "snapshot restores removed tree");
            check(store.loadConversation(id).toString().equals(originalConversation.toString()), "snapshot restores full conversation items");
            check(store.history(id).length() == 2, "restore first creates safety snapshot");
            rejects(() -> store.restore(id, "../../outside"), "unsafe snapshot ID");

            ByteArrayOutputStream exported = new ByteArrayOutputStream();
            store.exportZip(id, exported);
            String imported = store.importZip(new ByteArrayInputStream(exported.toByteArray()), "Restored project");
            check(!id.equals(imported), "import has fresh ID");
            check(store.getProject(imported).getString("name").equals("Restored project"), "import suggested name");
            check(store.readText(imported, "src/main.js").contains("first"), "archive source round trip");
            check(Arrays.equals(picture, store.readBytes(imported, "assets/photo.bin")), "archive binary round trip");
            check(store.loadConversation(imported).toString().equals(originalConversation.toString()), "archive preserves full conversation");
            check(store.history(imported).length() == 0, "archives exclude snapshots");
            check(!new File(store.projectDirectory(imported), "conversation.json").exists(), "private data outside source");
            byte[] ordinary = zip(new String[]{"site/index.html", "site/app.js"}, new byte[][]{"<p>x</p>".getBytes(), "alert(1)".getBytes()});
            String regular = store.importZip(new ByteArrayInputStream(ordinary), "Regular ZIP");
            check(store.readText(regular, "site/index.html").equals("<p>x</p>"), "regular ZIP source import");
            int projectCount = store.listProjects().length();
            byte[] zipSlip = zip(new String[]{"../outside/secret"}, new byte[][]{"bad".getBytes()});
            rejects(() -> store.importZip(new ByteArrayInputStream(zipSlip), "bad"), "ZIP slip");
            byte[] zipAbsolute = zip(new String[]{"/absolute"}, new byte[][]{new byte[0]});
            rejects(() -> store.importZip(new ByteArrayInputStream(zipAbsolute), "bad"), "ZIP absolute path");
            byte[] collision = zip(new String[]{"folder/", "folder"}, new byte[][]{new byte[0], new byte[0]});
            rejects(() -> store.importZip(new ByteArrayInputStream(collision), "bad"), "ZIP file-directory collision");
            byte[] large = new byte[(int) ProjectStore.MAX_FILE_BYTES + 1];
            byte[] bomb = zip(new String[]{"big.txt"}, new byte[][]{large});
            rejects(() -> store.importZip(new ByteArrayInputStream(bomb), "bad"), "decompression size bound");
            rejects(() -> store.writeBytes(id, "big.bin", large), "write size bound");
            rejects(() -> store.importZip(new ByteArrayInputStream("not a zip".getBytes()), "bad"), "non ZIP input");
            check(store.listProjects().length() == projectCount, "failed imports leave no projects");
            check(Files.readString(outside.resolve("secret")).equals("untouched"), "zip slip never writes outside staging");
            try (java.util.stream.Stream<Path> files = Files.list(temp.resolve("store"))) {
                check(files.noneMatch(p -> p.getFileName().toString().startsWith(".import-")), "failed imports clean staging");
            }

            String[] tooManyNames = new String[ProjectStore.MAX_ENTRIES + 1];
            byte[][] emptyFiles = new byte[tooManyNames.length][];
            for (int i = 0; i < tooManyNames.length; i++) { tooManyNames[i] = "file" + i; emptyFiles[i] = new byte[0]; }
            byte[] many = zip(tooManyNames, emptyFiles);
            rejects(() -> store.importZip(new ByteArrayInputStream(many), "Too many files"), "project file-count bound");
            String bounded = store.createProject("Bounded").getString("id");
            byte[] chunk = new byte[(int) ProjectStore.MAX_FILE_BYTES];
            for (int i = 0; i < 4; i++) store.writeBytes(bounded, "part" + i, chunk);
            store.writeBytes(bounded, "part0", chunk);
            check(store.summary(bounded).getLong("bytes") == ProjectStore.MAX_PROJECT_BYTES, "replacing a file does not double-count size");
            rejects(() -> store.writeText(bounded, "overflow", "x"), "project total-byte bound");
            store.deleteProject(bounded);

            for (int i = 0; i < 13; i++) store.snapshot(id, "version " + i);
            check(store.history(id).length() == 12, "snapshot retention bounded");
            String oldest = store.history(id).getJSONObject(11).getString("id");
            store.writeText(id, "index.html", "after retained snapshot");
            store.restore(id, oldest);
            check(store.readText(id, "index.html").contains("こんにちは"), "oldest retained snapshot restores even when pruned during safety snapshot");

            store.renameProject(id, "Renamed");
            check(store.getProject(id).getString("name").equals("Renamed"), "rename metadata");
            store.deleteFile(id, "assets");
            check(store.summary(id).getInt("fileCount") == 2, "recursive directory delete");
            store.deleteProject(regular);
            check(store.listProjects().length() == projectCount - 1, "delete project");
            System.out.println("PASS ProjectStoreTest: " + checks + " checks");
        } finally { deleteTree(temp); }
    }
}
