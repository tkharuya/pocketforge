package jp.haruyalab.pocketforge;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.*;

/** Manual, conservative two-way sync of ordinary files in a visible My Drive folder.
 * Never syncs credentials or private project metadata. Call from a worker thread.
 * Deletions are deliberately not propagated: the surviving copy is restored.
 */
public final class DriveSync {
    public static final String SCOPE = "https://www.googleapis.com/auth/drive";
    private static final String API = "https://www.googleapis.com/drive/v3/";
    private static final String UPLOAD = "https://www.googleapis.com/upload/drive/v3/";
    private static final String FOLDER = "application/vnd.google-apps.folder";
    private static final String FIELDS = "id,name,mimeType,md5Checksum,version,size,trashed,headRevisionId";
    public interface TokenProvider {
        String getAccessToken() throws Exception;
        default void invalidateToken(String invalidToken) throws Exception { }
    }
    /** Test seam. Production uses only HTTPS googleapis.com and rejects redirects. */
    public interface Transport {
        Response request(String method, String url, String type, byte[] body,
                         Map<String,String> headers) throws Exception;
    }
    public static final class Response {
        public final int status; public final byte[] body;
        public Response(int status, byte[] body) { this.status=status; this.body=body; }
    }
    private final ProjectStore projects;
    private final File stateDir;
    private final TokenProvider tokens;
    private final Transport transport;
    private boolean verified;
    private String token;

    public DriveSync(Context context, ProjectStore projects, TokenProvider tokens) {
        this(new File(context.getFilesDir(), "drive-sync"), projects, tokens, new NetworkTransport());
    }
    public DriveSync(File stateDir, ProjectStore projects, TokenProvider tokens, Transport transport) {
        this.stateDir=stateDir; this.projects=projects; this.tokens=tokens; this.transport=transport;
    }

    public synchronized JSONObject status(String projectId) throws Exception {
        JSONObject s=readState(projectId);
        return new JSONObject().put("connected",verified).put("authorizationAvailable",tokens!=null)
            .put("lastSync",s.optLong("lastSync",0)).put("folderId",s.optString("folderId"))
            .put("folderUrl",folderUrl(s.optString("folderId")))
            .put("account",s.optString("email")).put("scope",SCOPE)
            .put("deletionsPropagated",false);
    }

    /** Forget local association only. Does not delete source files or anything in Drive. */
    public synchronized void forgetBinding(String projectId) throws Exception {
        File file=stateFile(projectId);
        if(file.exists() && !file.delete()) throw new IOException("Drive の関連付けを削除できませんでした");
        verified=false;
    }

    public synchronized JSONObject sync(String projectId) throws Exception {
        // ProjectStore methods use this same monitor, preventing concurrent local agent edits.
        synchronized(projects) {
            verified=false;
            if(tokens==null) throw new IOException("Google Drive の認可設定が必要です");
            token=tokens.getAccessToken();
            if(token==null || token.trim().isEmpty()) throw new IOException("Google Drive に接続してください");
            try { return performSync(projectId); }
            finally { token=null; }
        }
    }

    private JSONObject performSync(String projectId) throws Exception {
        JSONObject project=projects.getProject(projectId);
        JSONObject state=readState(projectId);
        JSONObject user=json("GET",API+"about?fields=user(permissionId,emailAddress)",null,null).getJSONObject("user");
        String account=user.getString("permissionId");
        if(!state.optString("accountId").isEmpty() && !account.equals(state.optString("accountId")))
            throw new IOException("前回と異なる Google アカウントです。元のアカウントで接続してください");
        verified=true;
        state.put("accountId",account).put("email",user.optString("emailAddress"));
        String folderId=state.optString("folderId");
        if(folderId.isEmpty()) {
            String root=appRoot();
            String name=project.optString("name","Project")+" ["+projectId.substring(0,Math.min(8,projectId.length()))+"]";
            JSONObject props=new JSONObject().put("pocketforgeProject",projectId);
            folderId=findOrCreateFolder(root,name,props);
            state.put("folderId",folderId);
            writeState(projectId,state);
        } else {
            JSONObject folder=metadata(folderId);
            if(folder.optBoolean("trashed") || !FOLDER.equals(folder.optString("mimeType")))
                throw new IOException("同期先フォルダーが移動・削除されました。Drive で復元してください");
        }
        SortedMap<String,JSONObject> remote=new TreeMap<>();
        Map<String,String> folders=new HashMap<>(); folders.put("",folderId);
        Set<String> unsupportedPaths=new HashSet<>();
        JSONArray warnings=new JSONArray();
        scanRemote(folderId,"",remote,folders,unsupportedPaths,warnings,0,new int[]{0});
        SortedMap<String,String> local=new TreeMap<>();
        JSONArray listed=projects.listFiles(projectId);
        // Reject cross-device file/folder collisions before modifying either tree.
        for(int i=0;i<listed.length();i++) {
            JSONObject file=listed.getJSONObject(i); String path=file.getString("path");
            if(file.optBoolean("directory") ? remote.containsKey(path) : folders.containsKey(path))
                throw new IOException("ファイルとフォルダーの名前が衝突しています: "+path);
            if(unsupportedPaths.contains(path)) throw new IOException("Drive の対象外形式と名前が衝突しています: "+path);
        }
        projects.snapshot(projectId,"Drive 同期前");
        for(String path:new TreeSet<>(folders.keySet())) if(!path.isEmpty()) projects.mkdir(projectId,path);
        for(int i=0;i<listed.length();i++) {
            JSONObject file=listed.getJSONObject(i);
            String path=file.getString("path");
            if(file.optBoolean("directory")) { ensureFolder(path,folders); continue; }
            local.put(path,digest(projects.readBytes(projectId,path)));
        }
        JSONObject bases=state.optJSONObject("files");
        if(bases==null) { bases=new JSONObject(); state.put("files",bases); }
        // The snapshot above protects every pre-sync local version.
        SortedSet<String> paths=new TreeSet<>(); paths.addAll(local.keySet()); paths.addAll(remote.keySet());
        int uploaded=0,downloaded=0,restored=0,unchanged=0;
        JSONArray conflicts=new JSONArray();
        for(String path:paths) {
            if(Thread.currentThread().isInterrupted()) throw new IOException("同期を中断しました");
            String localMd5=local.get(path);
            JSONObject r=remote.get(path),base=bases.optJSONObject(path);
            if(r==null) {
                // A remote delete/rename never destroys the only local copy.
                byte[] bytes=projects.readBytes(projectId,path);
                JSONObject created=createFile(path,bytes,ensureParent(path,folders));
                bases.put(path,binding(created,digest(bytes))); uploaded++;
                if(base!=null) restored++;
            } else if(localMd5==null) {
                Download fetched=downloadStable(r);
                writeDownloaded(projectId,path,null,fetched.bytes);
                bases.put(path,binding(fetched.metadata,digest(fetched.bytes))); downloaded++;
                if(base!=null) restored++;
            } else {
                String remoteMd5=r.optString("md5Checksum");
                if(localMd5.equals(remoteMd5)) {
                    bases.put(path,binding(r,localMd5)); unchanged++;
                } else {
                    String old=base==null?"":base.optString("md5");
                    boolean localChanged=base==null || !localMd5.equals(old);
                    boolean remoteChanged=base==null || !remoteMd5.equals(old) || !r.getString("id").equals(base.optString("id"));
                    if(!localChanged) {
                        Download fetched=downloadStable(r);
                        writeDownloaded(projectId,path,localMd5,fetched.bytes);
                        bases.put(path,binding(fetched.metadata,digest(fetched.bytes))); downloaded++;
                    } else if(!remoteChanged) {
                        byte[] bytes=projects.readBytes(projectId,path);
                        JSONObject current=metadata(r.getString("id"));
                        if(!sameRevision(r,current)) throw new IOException("同期中に Drive の内容が変わりました。もう一度同期してください: "+path);
                        // Pin the previous ordinary-file revision before replacement. If Drive
                        // refuses (e.g. its 200 pinned-revision cap), stop without overwriting.
                        String revision=current.optString("headRevisionId");
                        if(revision.isEmpty()) throw new IOException("以前の版を保護できないため更新を停止しました: "+path);
                        try {
                            json("PATCH",API+"files/"+enc(r.getString("id"))+"/revisions/"+enc(revision),
                                "application/json",new JSONObject().put("keepForever",true).toString().getBytes(StandardCharsets.UTF_8));
                        } catch(Exception preserveError) {
                            throw new IOException("Drive の旧版を保護できないため、上書きせず停止しました。固定版の上限は1ファイル200版です。ネット接続・権限・固定版数を確認してください: "+path,preserveError);
                        }
                        JSONObject checked=metadata(r.getString("id"));
                        if(!sameContent(current,checked)) throw new IOException("同期中に Drive の内容が変わりました。もう一度同期してください: "+path);
                        JSONObject updated=json("PATCH",UPLOAD+"files/"+enc(r.getString("id"))+"?uploadType=media&fields="+enc(FIELDS),
                            "application/octet-stream",bytes);
                        if(!digest(bytes).equals(updated.optString("md5Checksum")))
                            throw new IOException("アップロードの検証に失敗しました: "+path);
                        bases.put(path,binding(updated,digest(bytes))); uploaded++;
                    } else {
                        // Keep local as a separate visible file, upload it, then accept remote.
                        Download fetched=downloadStable(r);
                        byte[] bytes=projects.readBytes(projectId,path);
                        String conflict=conflictName(path,local,remote);
                        writeDownloaded(projectId,conflict,null,bytes);
                        JSONObject saved=createFile(conflict,bytes,ensureParent(conflict,folders));
                        bases.put(conflict,binding(saved,digest(bytes))); uploaded++;
                        writeDownloaded(projectId,path,localMd5,fetched.bytes);
                        bases.put(path,binding(fetched.metadata,digest(fetched.bytes))); downloaded++;
                        conflicts.put(new JSONObject().put("path",path).put("localCopy",conflict));
                    }
                }
            }
            // Persist each completed item so a network interruption does not forget progress.
            writeState(projectId,state);
        }
        state.put("lastSync",System.currentTimeMillis()); writeState(projectId,state); verified=true;
        return new JSONObject().put("uploaded",uploaded).put("downloaded",downloaded)
            .put("restored",restored).put("unchanged",unchanged).put("skipped",warnings.length())
            .put("conflicts",conflicts).put("warnings",warnings).put("lastSync",state.getLong("lastSync"))
            .put("folderId",folderId).put("folderUrl",folderUrl(folderId)).put("account",user.optString("emailAddress"))
            .put("deletionsPropagated",false);
    }

    private void writeDownloaded(String projectId, String path, String expectedLocalMd5, byte[] bytes) throws Exception {
        synchronized (projects) {
            String currentMd5 = projects.pathExists(projectId, path) ? digest(projects.readBytes(projectId, path)) : null;
            if (!Objects.equals(expectedLocalMd5, currentMd5)) {
                throw new IOException("同期中に端末のファイルが変更されました。もう一度同期してください: " + path);
            }
            projects.writeBytes(projectId, path, bytes);
        }
    }

    private String appRoot() throws Exception {
        String query="trashed = false and mimeType = '"+FOLDER+"' and 'root' in parents and appProperties has { key='pocketforgeRoot' and value='1' }";
        JSONArray files=list(query);
        if(files.length()>0) return files.getJSONObject(0).getString("id");
        return createFolder("root","PocketForge",new JSONObject().put("pocketforgeRoot","1"));
    }
    private String findOrCreateFolder(String parent,String name,JSONObject props) throws Exception {
        String query="trashed = false and '"+q(parent)+"' in parents and mimeType = '"+FOLDER+"' and appProperties has { key='pocketforgeProject' and value='"+q(props.getString("pocketforgeProject"))+"' }";
        JSONArray matches=list(query);
        if(matches.length()>1) throw new IOException("同期先プロジェクトフォルダーが重複しています");
        return matches.length()==1?matches.getJSONObject(0).getString("id"):createFolder(parent,name,props);
    }
    private String createFolder(String parent,String name,JSONObject props) throws Exception {
        JSONObject body=new JSONObject().put("name",name).put("mimeType",FOLDER).put("parents",new JSONArray().put(parent));
        if(props!=null) body.put("appProperties",props);
        return json("POST",API+"files?fields=id","application/json",body.toString().getBytes(StandardCharsets.UTF_8)).getString("id");
    }
    private void scanRemote(String parent,String prefix,Map<String,JSONObject> files,Map<String,String> folders,
                            Set<String> unsupportedPaths,JSONArray warnings,int depth,int[] count) throws Exception {
        if(depth>32) throw new IOException("Drive のフォルダー階層が深すぎます");
        JSONArray children=list("trashed = false and '"+q(parent)+"' in parents");
        Set<String> names=new HashSet<>();
        for(int i=0;i<children.length();i++) {
            if(++count[0]>ProjectStore.MAX_ENTRIES) throw new IOException("Drive のファイルが 2,000 件を超えています");
            JSONObject child=children.getJSONObject(i);
            String name=child.getString("name");
            if(!validName(name)) { warnings.put("利用できないファイル名: "+name); continue; }
            if(!names.add(name)) throw new IOException("Drive に同名ファイルが複数あります。名前を変更してください: "+prefix+name);
            String path=prefix+name,mime=child.optString("mimeType");
            if(FOLDER.equals(mime)) {
                folders.put(path,child.getString("id"));
                scanRemote(child.getString("id"),path+"/",files,folders,unsupportedPaths,warnings,depth+1,count);
            } else if(mime.startsWith("application/vnd.google-apps.")) {
                unsupportedPaths.add(path);
                warnings.put("Google ドキュメント・ショートカットは対象外: "+path);
            } else {
                if(child.optLong("size",0)>ProjectStore.MAX_FILE_BYTES) throw new IOException("Drive のファイルが 8 MB を超えています: "+path);
                if(child.optString("md5Checksum").isEmpty()) throw new IOException("Drive のファイル内容を検証できません: "+path);
                files.put(path,child);
            }
        }
    }
    private JSONArray list(String query) throws Exception {
        JSONArray all=new JSONArray(); String page="";
        do {
            JSONObject reply=json("GET",API+"files?spaces=drive&pageSize=1000&q="+enc(query)+"&fields="+enc("nextPageToken,incompleteSearch,files("+FIELDS+")")+(page.isEmpty()?"":"&pageToken="+enc(page)),null,null);
            if(reply.optBoolean("incompleteSearch")) throw new IOException("Drive の一覧を完全に取得できませんでした");
            JSONArray batch=reply.optJSONArray("files");
            if(batch!=null) for(int i=0;i<batch.length();i++) all.put(batch.getJSONObject(i));
            if(all.length()>ProjectStore.MAX_ENTRIES) throw new IOException("Drive の項目数が上限を超えています");
            page=reply.optString("nextPageToken");
        } while(!page.isEmpty());
        return all;
    }
    private String ensureParent(String path,Map<String,String> folders) throws Exception {
        int slash=path.lastIndexOf('/'); return ensureFolder(slash<0?"":path.substring(0,slash),folders);
    }
    private String ensureFolder(String path,Map<String,String> folders) throws Exception {
        if(folders.containsKey(path)) return folders.get(path);
        int slash=path.lastIndexOf('/'); String name=slash<0?path:path.substring(slash+1);
        String parent=ensureFolder(slash<0?"":path.substring(0,slash),folders);
        String id=createFolder(parent,name,null); folders.put(path,id); return id;
    }
    private JSONObject createFile(String path,byte[] bytes,String parent) throws Exception {
        String name=path.substring(path.lastIndexOf('/')+1),boundary="pocketforge_"+UUID.randomUUID().toString().replace("-","");
        JSONObject meta=new JSONObject().put("name",name).put("parents",new JSONArray().put(parent));
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        out.write(("--"+boundary+"\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n"+meta+"\r\n--"+boundary+"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(bytes); out.write(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));
        JSONObject created=json("POST",UPLOAD+"files?uploadType=multipart&fields="+enc(FIELDS),"multipart/related; boundary="+boundary,out.toByteArray());
        if(!digest(bytes).equals(created.optString("md5Checksum"))) throw new IOException("アップロードしたファイルを検証できません: "+path);
        return created;
    }
    private JSONObject metadata(String id) throws Exception { return json("GET",API+"files/"+enc(id)+"?fields="+enc(FIELDS),null,null); }
    private static boolean sameRevision(JSONObject a,JSONObject b) {
        return !b.optBoolean("trashed") && a.optString("version").equals(b.optString("version")) && sameContent(a,b);
    }
    private static boolean sameContent(JSONObject a,JSONObject b) {
        return !b.optBoolean("trashed") && a.optString("md5Checksum").equals(b.optString("md5Checksum")) && a.optString("headRevisionId").equals(b.optString("headRevisionId"));
    }
    private Download downloadStable(JSONObject original) throws Exception {
        JSONObject before=metadata(original.getString("id"));
        byte[] bytes=request("GET",API+"files/"+enc(original.getString("id"))+"?alt=media",null,null).body;
        JSONObject after=metadata(original.getString("id"));
        if(!sameRevision(before,after) || !digest(bytes).equals(after.optString("md5Checksum")))
            throw new IOException("ダウンロード中に Drive の内容が変わりました。もう一度同期してください");
        return new Download(after,bytes);
    }
    private static final class Download {
        final JSONObject metadata; final byte[] bytes;
        Download(JSONObject m,byte[] b) { metadata=m; bytes=b; }
    }
    private static JSONObject binding(JSONObject remote,String md5) throws Exception {
        return new JSONObject().put("id",remote.getString("id")).put("md5",md5).put("version",remote.optString("version"));
    }
    private static String conflictName(String path,Map<String,String> local,Map<String,JSONObject> remote) {
        SimpleDateFormat fmt=new SimpleDateFormat("yyyyMMdd-HHmmss",Locale.US); fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
        int slash=path.lastIndexOf('/'),dot=path.lastIndexOf('.'); if(dot<=slash+1) dot=path.length();
        String stem=path.substring(0,dot)+".local-conflict-"+fmt.format(new Date()),ext=path.substring(dot),result=stem+ext;
        int i=2; while(local.containsKey(result)||remote.containsKey(result)) result=stem+"-"+(i++)+ext;
        return result;
    }
    private static boolean validName(String name) { return !name.isEmpty() && !name.equals(".") && !name.equals("..") && name.indexOf('/')<0 && name.indexOf('\\')<0 && name.indexOf('\0')<0; }
    private static String folderUrl(String id) { return id.isEmpty()?"":"https://drive.google.com/drive/folders/"+id; }
    private File stateFile(String id) throws IOException {
        if(id==null || !id.matches("[A-Za-z0-9_-]{1,100}")) throw new IOException("プロジェクト ID が不正です");
        if(!stateDir.isDirectory() && !stateDir.mkdirs()) throw new IOException("Drive 同期情報を保存できません");
        return new File(stateDir,id+".json");
    }
    private JSONObject readState(String id) throws Exception {
        File file=stateFile(id); if(!file.exists()) return new JSONObject();
        try(InputStream in=new FileInputStream(file)) { return new JSONObject(new String(readLimited(in,4*1024*1024),StandardCharsets.UTF_8)); }
    }
    private void writeState(String id,JSONObject state) throws Exception {
        File file=stateFile(id),temp=new File(file.getPath()+".tmp");
        try(FileOutputStream out=new FileOutputStream(temp)) { out.write(state.toString().getBytes(StandardCharsets.UTF_8)); out.getFD().sync(); }
        if(!temp.renameTo(file)) throw new IOException("Drive 同期情報の保存に失敗しました");
    }
    private JSONObject json(String method,String url,String type,byte[] body) throws Exception {
        byte[] bytes=request(method,url,type,body).body;
        return bytes.length==0?new JSONObject():new JSONObject(new String(bytes,StandardCharsets.UTF_8));
    }
    private Response request(String method,String url,String type,byte[] body) throws Exception {
        Map<String,String> headers=new HashMap<>(); headers.put("Authorization","Bearer "+token);
        Response response=transport.request(method,url,type,body,headers);
        if(response.status==401) {
            verified=false;
            tokens.invalidateToken(token);
            token=tokens.getAccessToken();
            if(token==null || token.trim().isEmpty()) throw new IOException("Google Drive に再接続してください");
            headers.put("Authorization","Bearer "+token);
            response=transport.request(method,url,type,body,headers);
        }
        if(response.status<200 || response.status>=300) {
            if(response.status==401) { verified=false; throw new IOException("Google Drive の認可が期限切れです。再接続してください"); }
            if(response.status==403) throw new IOException("Google Drive へのアクセスが拒否されました。Drive API、有効な OAuth クライアント、権限、容量を確認してください");
            if(response.status==404) throw new IOException("Drive の同期先が見つかりません。アカウントとゴミ箱を確認してください");
            throw new IOException("Drive 通信エラー (HTTP "+response.status+")。再試行してください");
        }
        return response;
    }
    private static String q(String text) { return text.replace("\\","\\\\").replace("'","\\'"); }
    private static String enc(String text) throws Exception { return URLEncoder.encode(text,"UTF-8"); }
    private static String digest(byte[] data) throws Exception {
        byte[] hash=MessageDigest.getInstance("MD5").digest(data); StringBuilder out=new StringBuilder();
        for(byte b:hash) out.append(String.format(Locale.US,"%02x",b&255)); return out.toString();
    }
    private static byte[] readLimited(InputStream in,long max) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] buf=new byte[16384]; int n; long total=0;
        while((n=in.read(buf))!=-1) { total+=n; if(total>max) throw new IOException("Drive の応答がサイズ上限を超えました"); out.write(buf,0,n); }
        return out.toByteArray();
    }
    public static final class NetworkTransport implements Transport {
        public Response request(String method,String url,String type,byte[] body,Map<String,String> headers) throws Exception {
            URL endpoint=new URL(url);
            if(!"https".equals(endpoint.getProtocol()) || !"www.googleapis.com".equals(endpoint.getHost())) throw new IOException("許可されていない Drive 通信先です");
            HttpURLConnection connection=(HttpURLConnection)endpoint.openConnection();
            connection.setConnectTimeout(15000); connection.setReadTimeout(45000); connection.setInstanceFollowRedirects(false);
            try { connection.setRequestMethod(method); }
            catch(java.net.ProtocolException unsupported) {
                if(!"PATCH".equals(method)) throw unsupported;
                connection.setRequestMethod("POST"); connection.setRequestProperty("X-HTTP-Method-Override","PATCH");
            }
            for(Map.Entry<String,String> header:headers.entrySet()) connection.setRequestProperty(header.getKey(),header.getValue());
            if(type!=null) connection.setRequestProperty("Content-Type",type);
            try {
                if(body!=null) { connection.setDoOutput(true); connection.setFixedLengthStreamingMode(body.length); try(OutputStream out=connection.getOutputStream()) { out.write(body); } }
                int status=connection.getResponseCode(); InputStream stream=status<400?connection.getInputStream():connection.getErrorStream();
                byte[] reply; if(stream==null) reply=new byte[0]; else try(InputStream in=stream) { reply=readLimited(in,ProjectStore.MAX_FILE_BYTES+1024*1024); }
                return new Response(status,reply);
            } finally { connection.disconnect(); }
        }
    }
}
