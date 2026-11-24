package jp.haruyalab.pocketforge;
import org.json.*; import java.io.*; import java.nio.charset.StandardCharsets; import java.nio.file.*; import java.net.*; import java.security.*; import java.util.*;
public class DriveSyncTest {
 static void ok(boolean b,String m){ if(!b) throw new AssertionError(m); }
 static String text(byte[] b){return new String(b,StandardCharsets.UTF_8);}
 static byte[] bytes(String s){return s.getBytes(StandardCharsets.UTF_8);}
 static String md5(byte[] b)throws Exception{StringBuilder s=new StringBuilder();for(byte x:MessageDigest.getInstance("MD5").digest(b))s.append(String.format("%02x",x&255));return s.toString();}
 static class Fake implements DriveSync.Transport {
  Map<String,JSONObject> files=new LinkedHashMap<>(); Map<String,byte[]> content=new HashMap<>(); int seq,pinned,writes; String account="account-a"; int failCode; boolean flipBeforeDownload, failRevision; int unauthorizedOnce;
  java.util.concurrent.Callable<Void> duringDownload;
  JSONObject add(String name,String parent,String mime,byte[] b,JSONObject props)throws Exception{
   String id="f"+(++seq);JSONObject f=new JSONObject().put("id",id).put("name",name).put("mimeType",mime).put("parent",parent).put("version","1").put("headRevisionId","r1");
   if(props!=null)f.put("appProperties",props);files.put(id,f); if(b!=null)set(id,b);return f;
  }
  void set(String id,byte[] b)throws Exception {JSONObject f=files.get(id); int ver=Integer.parseInt(f.optString("version","0"))+1;f.put("md5Checksum",md5(b)).put("size",b.length).put("version",""+ver).put("headRevisionId","r"+ver);content.put(id,b);}
  JSONObject byName(String name){for(JSONObject f:files.values())if(name.equals(f.optString("name")))return f;throw new AssertionError("missing remote "+name);}
  DriveSync.Response response(JSONObject json){return new DriveSync.Response(200,bytes(json.toString()));}
  public DriveSync.Response request(String method,String url,String type,byte[] body,Map<String,String> headers)throws Exception{
   ok(headers.get("Authorization").equals("Bearer test-token"),"bearer token missing");
   if(unauthorizedOnce>0){unauthorizedOnce--;return new DriveSync.Response(401,bytes("expired"));}
   if(failCode>0)return new DriveSync.Response(failCode,bytes("denied"));
   URL u=new URL(url);Map<String,String> query=new HashMap<>();if(u.getQuery()!=null)for(String q:u.getQuery().split("&")){String[] a=q.split("=",2);query.put(a[0],URLDecoder.decode(a.length>1?a[1]:"","UTF-8"));}
   String path=u.getPath();
   if(path.endsWith("/about"))return response(new JSONObject().put("user",new JSONObject().put("permissionId",account).put("emailAddress",account+"@example.test")));
   if(path.contains("/revisions/")){if(failRevision)return new DriveSync.Response(403,bytes("revision cap"));pinned++;return response(new JSONObject().put("keepForever",true));}
   if(path.endsWith("/files") && method.equals("GET")){
    String q=query.get("q");JSONArray list=new JSONArray();
    for(JSONObject f:files.values()){
     if(f.optBoolean("trashed"))continue;String p=f.getString("parent");if(!q.contains("'"+p+"' in parents"))continue;
     if(q.contains("pocketforgeRoot")&&!f.optJSONObject("appProperties").optString("pocketforgeRoot").equals("1"))continue;
     if(q.contains("pocketforgeProject")){JSONObject props=f.optJSONObject("appProperties");if(props==null||!q.contains("value='"+props.optString("pocketforgeProject")+"'"))continue;}
     list.put(new JSONObject(f.toString()));
    }
    return response(new JSONObject().put("files",list));
   }
   if(path.endsWith("/files")&&method.equals("POST")){
    writes++;
    if(type.startsWith("application/json")){JSONObject j=new JSONObject(text(body));return response(add(j.getString("name"),j.getJSONArray("parents").getString(0),j.getString("mimeType"),null,j.optJSONObject("appProperties")));}
    String boundary=type.substring(type.indexOf("boundary=")+9);byte[] marker=bytes("\r\n--"+boundary+"\r\nContent-Type: application/octet-stream\r\n\r\n");String s=text(body);int js=s.indexOf("\r\n\r\n")+4;int split=s.indexOf("\r\n--"+boundary,js);JSONObject j=new JSONObject(s.substring(js,split));int start=index(body,marker)+marker.length;int end=body.length-bytes("\r\n--"+boundary+"--\r\n").length;byte[] b=Arrays.copyOfRange(body,start,end);
    return response(add(j.getString("name"),j.getJSONArray("parents").getString(0),"application/octet-stream",b,null));
   }
   String id=path.substring(path.lastIndexOf('/')+1);JSONObject f=files.get(id);if(f==null)return new DriveSync.Response(404,bytes("{}"));
   if(method.equals("GET")){
    if("media".equals(query.get("alt"))){
     byte[] downloaded=content.get(id);
     if(flipBeforeDownload){flipBeforeDownload=false;set(id,bytes("raced"));}
     if(duringDownload!=null){var edit=duringDownload;duringDownload=null;edit.call();}
     return new DriveSync.Response(200,downloaded);
    }
    return response(new JSONObject(f.toString()));
   }
   if(method.equals("PATCH")){writes++;set(id,body);return response(new JSONObject(f.toString()));}
   throw new AssertionError("unexpected request "+method+" "+url);
  }
  int index(byte[] data,byte[] pattern){outer:for(int i=0;i<=data.length-pattern.length;i++){for(int j=0;j<pattern.length;j++)if(data[i+j]!=pattern[j])continue outer;return i;}throw new AssertionError("multipart marker missing");}
 }
 public static void main(String[] args)throws Exception{
  File temp=Files.createTempDirectory("drive-test-").toFile();ProjectStore store=new ProjectStore(new File(temp,"store"));String id=store.createProject("Trial").getString("id");
  store.writeText(id,"index.html","base");store.writeBytes(id,"assets/icon.bin",new byte[]{0,1,2,-1});store.mkdir(id,"empty");
  Fake fake=new Fake();int[] tokenCalls={0},invalidations={0};DriveSync.TokenProvider tokenProvider=new DriveSync.TokenProvider(){public String getAccessToken(){tokenCalls[0]++;return "test-token";}public void invalidateToken(String token){invalidations[0]++;}};DriveSync drive=new DriveSync(new File(temp,"sync"),store,tokenProvider,fake);
  JSONObject first=drive.sync(id);ok(first.getInt("uploaded")==2,"first upload count");ok(first.getInt("downloaded")==0,"initial download");ok(fake.byName("empty").getString("mimeType").contains("folder"),"empty directory upload");ok(Arrays.equals(fake.content.get(fake.byName("icon.bin").getString("id")),new byte[]{0,1,2,-1}),"binary roundtrip");
  fake.unauthorizedOnce=1;int invalidBefore=invalidations[0];JSONObject unchanged=drive.sync(id);ok(invalidations[0]==invalidBefore+1,"401 token invalidated and retried");ok(unchanged.getInt("unchanged")==2,"no-op");
  String remoteId=fake.byName("index.html").getString("id");store.writeText(id,"index.html","android edit");JSONObject upload=drive.sync(id);ok(upload.getInt("uploaded")==1,"local edit upload");ok(text(fake.content.get(remoteId)).equals("android edit"),"local content sync");ok(fake.pinned==1,"previous revision pinned");
  store.writeText(id,"index.html","blocked update");fake.failRevision=true;try{drive.sync(id);throw new AssertionError("pin failure accepted");}catch(IOException expected){ok(expected.getMessage().contains("200"),"revision cap message");}ok(text(fake.content.get(remoteId)).equals("android edit"),"pin failure overwrote remote");fake.failRevision=false;store.writeText(id,"index.html","android edit");
  fake.set(remoteId,bytes("pc edit"));JSONObject download=drive.sync(id);ok(download.getInt("downloaded")==1,"remote download");ok(store.readText(id,"index.html").equals("pc edit"),"remote content sync");
  fake.set(remoteId,bytes("new pc edit"));
  fake.duringDownload=()->{store.writeText(id,"index.html","saved locally during download");return null;};
  try{drive.sync(id);throw new AssertionError("concurrent local edit lost");}
  catch(IOException expected){ok(expected.getMessage().contains("端末のファイルが変更"),"local race error");}
  ok(store.readText(id,"index.html").equals("saved locally during download"),"new local edit preserved");
  store.writeText(id,"index.html","pc edit");fake.set(remoteId,bytes("pc edit"));drive.sync(id);
  String folder=first.getString("folderId");fake.add("created-on-pc.txt",folder,"text/plain",bytes("PC new"),null);JSONObject added=drive.sync(id);ok(added.getInt("downloaded")==1,"new PC file");ok(store.readText(id,"created-on-pc.txt").equals("PC new"),"new PC contents");
  store.writeText(id,"index.html","local divergent");fake.set(remoteId,bytes("remote divergent"));JSONObject conflict=drive.sync(id);ok(conflict.getJSONArray("conflicts").length()==1,"conflict flagged");String cp=conflict.getJSONArray("conflicts").getJSONObject(0).getString("localCopy");ok(store.readText(id,"index.html").equals("remote divergent"),"canonical remote preserved");ok(store.readText(id,cp).equals("local divergent"),"local conflict retained");ok(text(fake.content.get(fake.byName(cp).getString("id"))).equals("local divergent"),"conflict uploaded");
  store.deleteFile(id,"created-on-pc.txt");JSONObject restored=drive.sync(id);ok(restored.getInt("restored")==1,"local deletion restored");ok(store.readText(id,"created-on-pc.txt").equals("PC new"),"restored contents");
  fake.files.get(remoteId).put("trashed",true);JSONObject restoredRemote=drive.sync(id);ok(restoredRemote.getInt("restored")==1,"remote deletion restored");
  int writes=fake.writes;fake.account="other-account";try{drive.sync(id);throw new AssertionError("account switch accepted");}catch(IOException expected){ok(expected.getMessage().contains("アカウント"),"account error");}ok(fake.writes==writes,"account switch mutated");fake.account="account-a";
  fake.failCode=401;try{drive.sync(id);throw new AssertionError("401 accepted");}catch(IOException expected){}ok(!drive.status(id).getBoolean("connected"),"unauthorized status");fake.failCode=0;
  JSONObject current=null;for(JSONObject f:fake.files.values())if(f.optString("name").equals("index.html")&&!f.optBoolean("trashed"))current=f;
  fake.set(current.getString("id"),bytes("before race"));fake.flipBeforeDownload=true;String before=store.readText(id,"index.html");try{drive.sync(id);throw new AssertionError("raced download accepted");}catch(IOException expected){}ok(store.readText(id,"index.html").equals(before),"raced download overwrote local");
  fake.add("duplicate.txt",folder,"text/plain",bytes("a"),null);fake.add("duplicate.txt",folder,"text/plain",bytes("b"),null);writes=fake.writes;try{drive.sync(id);throw new AssertionError("duplicates accepted");}catch(IOException expected){ok(expected.getMessage().contains("同名"),"duplicate error");}ok(fake.writes==writes,"duplicate failure mutated remote");
  System.out.println("PASS: upload, no-op, 401 invalidation/retry, pinned update, revision cap stops before overwrite, download, PC-created file, conflict preserve/upload, deletion restoration, account mismatch, auth failure, remote race, duplicate path rejection, binary, empty directory");
 }
}
