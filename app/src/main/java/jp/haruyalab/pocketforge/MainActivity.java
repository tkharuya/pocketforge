package jp.haruyalab.pocketforge;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.view.*;
import android.webkit.*;
import android.widget.FrameLayout;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class MainActivity extends Activity {
    private static final int EXPORT=101,IMPORT=102,ATTACH=103;
    private final ExecutorService tasks=Executors.newSingleThreadExecutor();
    private final ExecutorService inference=Executors.newSingleThreadExecutor();
    private final AtomicBoolean busy=new AtomicBoolean(false);
    private final ArrayDeque<String> logs=new ArrayDeque<>();
    private WebView web;private ProjectStore store;private ChatGptAuth auth;private AgentEngine engine;
    private GoogleDriveAuth google;private DriveSync drive;private JavaScriptRunner runner;
    private SharedPreferences prefs;private volatile String activeProject;private volatile JSONArray models=new JSONArray();
    private volatile boolean destroyed;private JSONObject pendingDocument;private String pendingProject;
    private int foregroundUsers;

    @Override public void onCreate(Bundle state){super.onCreate(state);
        prefs=getSharedPreferences("preferences",MODE_PRIVATE);store=new ProjectStore(this);auth=new ChatGptAuth(this);
        runner=new JavaScriptRunner(this);engine=new AgentEngine(auth,store,(id,code)->runner.run(code));
        google=new GoogleDriveAuth(this);drive=new DriveSync(this,store,google);
        try{JSONArray p=store.listProjects();activeProject=prefs.getString("activeProject",null);
            boolean exists=false;for(int i=0;i<p.length();i++)if(p.getJSONObject(i).optString("id").equals(activeProject))exists=true;
            if(!exists){if(p.length()>0)activeProject=p.getJSONObject(0).getString("id");else{JSONObject sample=store.createProject("はじめてのプロジェクト");activeProject=sample.getString("id");store.writeText(activeProject,"index.html",readAsset("sample.html"));}}
            prefs.edit().putString("activeProject",activeProject).apply();
        }catch(Exception e){record("起動: "+safe(e));}
        FrameLayout root=new FrameLayout(this);root.setBackgroundColor(Color.rgb(16,18,16));
        root.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets;});
        web=new WebView(this);root.addView(web,new FrameLayout.LayoutParams(-1,-1));setContentView(root);
        WebSettings settings=web.getSettings();settings.setJavaScriptEnabled(true);settings.setDomStorageEnabled(true);settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);settings.setAllowFileAccessFromFileURLs(false);settings.setAllowUniversalAccessFromFileURLs(false);settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        web.setBackgroundColor(Color.rgb(16,18,16));web.addJavascriptInterface(new Bridge(),"Native");
        web.setWebChromeClient(new WebChromeClient(){@Override public void onPermissionRequest(PermissionRequest r){r.deny();}});
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){return !r.getUrl().toString().equals("https://app.pocketforge.invalid/");}
            @Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest r){
                if(r.getUrl().toString().equals("https://app.pocketforge.invalid/")){try{Map<String,String> headers=new HashMap<>();headers.put("Content-Security-Policy","default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data:; connect-src 'none'; base-uri 'none'; form-action 'none'");return new WebResourceResponse("text/html","UTF-8",200,"OK",headers,getAssets().open("index.html"));}catch(Exception ignored){}}
                return ProjectWeb.denied("blocked");
            }
        });
        web.loadUrl("https://app.pocketforge.invalid/");
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},44);
    }

    public final class Bridge {
        @JavascriptInterface public void request(String raw){
            if(raw==null||raw.length()>10*1024*1024)return;
            try{JSONObject request=new JSONObject(raw);tasks.execute(()->dispatch(request));}catch(Exception e){event("error","message","要求を解析できませんでした");}
        }
    }
    private void dispatch(JSONObject r){
        String action=r.optString("action");
        try{
            switch(action){
                case "bootstrap":respond(r,bootstrap());if(auth.isSignedIn())tasks.execute(()->refreshModels(null));break;
                case "createProject":idle();JSONObject p=store.createProject(r.optString("name","新しいプロジェクト"));store.writeText(p.getString("id"),"index.html",readAsset("sample.html"));select(p.getString("id"));respond(r,p);changed();break;
                case "selectProject":idle();select(r.getString("projectId"));respond(r,bootstrap());changed();break;
                case "renameProject":idle();store.renameProject(current(),r.getString("name"));respond(r,store.getProject(current()));changed();break;
                case "deleteProject":idle();store.deleteProject(current());JSONArray all=store.listProjects();if(all.length()==0)select(store.createProject("新しいプロジェクト").getString("id"));else select(all.getJSONObject(0).getString("id"));respond(r,bootstrap());changed();break;
                case "listFiles":respond(r,store.listFiles(current()));break;
                case "readFile":respond(r,object("content",store.readText(current(),r.getString("path")),"path",r.getString("path")));break;
                case "writeFile":case "newFile":idle();String writePath=r.getString("path");if("newFile".equals(action)&&fileExists(current(),writePath))throw new IOException("同じ名前のファイルが既にあります。既存ファイルを開いて編集してください");store.snapshot(current(),"手動編集前");store.writeText(current(),writePath,r.optString("content"));respond(r,true);filesChanged();break;
                case "deleteFile":idle();store.snapshot(current(),"ファイル削除前");store.deleteFile(current(),r.getString("path"));respond(r,true);filesChanged();break;
                case "send":startGeneration(r);break;
                case "stop":engine.cancel();respond(r,true);break;
                case "login":idle();login(r);break;
                case "selectProfile":idle();auth.selectProfile(r.getString("clientId"));models=new JSONArray();respond(r,auth.getProfile());authEvent();if(auth.isSignedIn())refreshModels(null);break;
                case "logout":idle();String warning=auth.logout();models=new JSONArray();respond(r,object("warning",warning==null?"":warning));authEvent();break;
                case "models":refreshModels(r);break;
                case "preview":String previewId=current();String path=r.optString("path","index.html");store.readBytes(previewId,path);runOnUiThread(()->startActivity(new Intent(this,PreviewActivity.class).putExtra("project",previewId).putExtra("path",path)));respond(r,true);break;
                case "runJavaScript":respond(r,new JSONObject(runner.run(r.optString("code"))));break;
                case "history":respond(r,store.history(current()));break;
                case "restore":idle();store.restore(current(),r.getString("snapshotId"));respond(r,bootstrap());filesChanged();break;
                case "clearConversation":idle();store.saveConversation(current(),new JSONArray());respond(r,true);break;
                case "exportProject":document(r,EXPORT);break;
                case "importProject":idle();document(r,IMPORT);break;
                case "attachFile":idle();document(r,ATTACH);break;
                case "driveConnect":idle();connectDrive(r);break;
                case "driveDisconnect":idle();runOnUiThread(()->google.disconnect(this,new GoogleDriveAuth.Callback(){public void onSuccess(JSONObject profile){respond(r,profile);event("drive","status",driveStatus());}public void onError(String e){fail(r,e);}}));break;
                case "driveSync":idle();if(!busy.compareAndSet(false,true))throw new IOException("処理中です");workStart();try{event("status","text","Google Driveと同期中");JSONObject result=drive.sync(current());respond(r,result);filesChanged();event("drive","status",driveStatus());}finally{busy.set(false);workStop();}break;
                case "driveStatus":respond(r,driveStatus());break;
                case "openDriveFolder":JSONObject ds=drive.status(current());String folder=ds.optString("folderUrl");if(!folder.startsWith("https://drive.google.com/drive/folders/"))throw new IOException("先にプロジェクトをDrive同期してください");openExternal(folder);respond(r,true);break;
                case "settings":JSONObject value=r.optJSONObject("values");if(value!=null){SharedPreferences.Editor e=prefs.edit();if(value.has("model"))e.putString("model",value.optString("model"));if(value.has("reasoning"))e.putString("reasoning",value.optString("reasoning"));if(value.has("maxTurns"))e.putInt("maxTurns",Math.max(1,Math.min(50,value.optInt("maxTurns",20))));e.apply();}respond(r,settings());break;
                case "openUsage":openExternal("https://chatgpt.com/settings/usage");respond(r,true);break;
                case "logs":synchronized(logs){respond(r,new JSONArray(logs));}break;
                case "notices":runOnUiThread(()->startActivity(new Intent(this,NoticesActivity.class)));respond(r,true);break;
                default:throw new IOException("未対応の操作: "+action);
            }
        }catch(Exception e){fail(r,safe(e));}
    }

    private void startGeneration(JSONObject r)throws Exception{
        String id=current(),model=r.optString("model",prefs.getString("model","")),prompt=r.optString("message");
        if(!auth.isSignedIn())throw new IOException("設定からChatGPTにログインしてください");
        if(model.isEmpty())throw new IOException("モデル一覧を取得して選択してください");
        if(prompt.trim().isEmpty())throw new IOException("指示を入力してください");
        if(prompt.length()>100000)throw new IOException("指示が長すぎます");
        if(!busy.compareAndSet(false,true))throw new IOException("別の処理を実行中です");
        engine.setMaxTurns(prefs.getInt("maxTurns",20));String reasoning=r.optString("reasoning",prefs.getString("reasoning",""));
        prefs.edit().putString("model",model).putString("reasoning",reasoning).apply();workStart();respond(r,object("started",true));
        inference.execute(()->{try{engine.run(id,model,prompt,reasoning,new AgentEngine.Listener(){
            public void onDelta(String text){event("chatDelta","text",text);}
            public void onStatus(String text){event("status","text",text);record(text);}
            public void onTool(String name,String detail){emit(object("type","tool","name",name,"status","running","detail",detail));record("ツール: "+name);}
            public void onComplete(String finalText){busy.set(false);try{emit(object("type","done","text",finalText,"conversation",store.loadConversation(id)));filesChanged();}catch(Exception e){event("error","message",safe(e));}}
            public void onError(String message){busy.set(false);event("error","message",message);filesChanged();record(message);}
        });}finally{busy.set(false);workStop();}});
    }
    private JSONObject bootstrap()throws Exception{return object("projects",store.listProjects(),"activeProject",store.getProject(current()),"profile",auth.getProfile(),"profiles",auth.getProfiles(),"models",models,"settings",settings(),"conversation",store.loadConversation(current()),"files",store.listFiles(current()),"drive",driveStatus(),"busy",busy.get());}
    private JSONObject settings(){return object("model",prefs.getString("model",""),"reasoning",prefs.getString("reasoning",""),"maxTurns",prefs.getInt("maxTurns",20),"version","1.0.0","packageName",getPackageName());}
    private void select(String id)throws Exception{store.getProject(id);activeProject=id;prefs.edit().putString("activeProject",id).apply();}
    private String current()throws IOException{if(activeProject==null)throw new IOException("プロジェクトを作成してください");return activeProject;}
    private boolean fileExists(String project,String path)throws Exception{JSONArray files=store.listFiles(project);for(int i=0;i<files.length();i++)if(files.getJSONObject(i).optString("path").equals(path))return true;return false;}
    private void idle()throws IOException{if(busy.get())throw new IOException("処理が終わるか停止してから操作してください");}
    private void changed()throws Exception{emit(object("type","projects","projects",store.listProjects(),"activeProject",store.getProject(current())));filesChanged();}
    private void filesChanged(){try{event("files","files",store.listFiles(current()));}catch(Exception ignored){}}
    private void login(JSONObject r){workStart();runOnUiThread(()->{
        ChatGptAuth.Callback callback=new ChatGptAuth.Callback(){
            public void onSuccess(JSONObject profile){workStop();respond(r,profile);authEvent();tasks.execute(()->refreshModels(null));}
            public void onError(String message){workStop();fail(r,message);}
        };
        if(r.optBoolean("newRegistration"))auth.startLogin(this,null,r.optBoolean("requestPlanConsent"),callback);
        else if(r.optBoolean("requestPlanConsent")){JSONObject profile=auth.getProfile();auth.startLogin(this,profile==null?null:profile.optString("client_id"),true,callback);}
        else auth.startLogin(this,callback);
    });}
    private void authEvent(){emit(object("type","auth","profile",auth.getProfile(),"profiles",auth.getProfiles()));}
    private void refreshModels(JSONObject r){try{JSONArray loaded=engine.listModels();models=loaded;if(loaded.length()>0){String selected=prefs.getString("model","");boolean found=false;for(int i=0;i<loaded.length();i++)if(selected.equals(loaded.getJSONObject(i).optString("slug")))found=true;if(!found)prefs.edit().putString("model",loaded.getJSONObject(0).getString("slug")).apply();}if(r!=null)respond(r,loaded);emit(object("type","models","models",loaded,"settings",settings()));}catch(Exception e){if(r!=null)fail(r,safe(e));else event("status","text",safe(e));}}
    private void connectDrive(JSONObject r){runOnUiThread(()->google.connect(this,new GoogleDriveAuth.Callback(){
        public void onSuccess(JSONObject profile){respond(r,profile);event("drive","status",driveStatus());}
        public void onError(String message){fail(r,message);}
    }));}
    private JSONObject driveStatus(){JSONObject status=new JSONObject();try{if(activeProject!=null)status=drive.status(activeProject);JSONObject authState=google.status();status.put("auth",authState);status.put("connected",authState.optBoolean("connected"));status.put("scope","https://www.googleapis.com/auth/drive");status.put("setupRequired",!authState.optBoolean("connected"));}catch(Exception e){try{status.put("error",safe(e));}catch(Exception ignored){}}return status;}

    private void document(JSONObject r,int request)throws Exception{
        if(pendingDocument!=null)throw new IOException("ファイル選択中です");pendingDocument=r;pendingProject=current();
        Intent i;if(request==EXPORT){i=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/zip").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"PocketForge-"+store.getProject(current()).optString("name").replaceAll("[^\\p{L}\\p{N}._-]","_")+".zip");}
        else i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType(request==IMPORT?"application/zip":"*/*").addCategory(Intent.CATEGORY_OPENABLE);
        runOnUiThread(()->{try{startActivityForResult(i,request);}catch(Exception e){JSONObject pending=pendingDocument;pendingDocument=null;fail(pending,safe(e));}});
    }
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(google.onActivityResult(request,result,data))return;
        if(request!=EXPORT&&request!=IMPORT&&request!=ATTACH)return;JSONObject pending=pendingDocument;String project=pendingProject;pendingDocument=null;pendingProject=null;
        if(pending==null)return;if(result!=RESULT_OK||data==null||data.getData()==null){respond(pending,object("cancelled",true));return;}
        Uri uri=data.getData();tasks.execute(()->{try{
            if(request==EXPORT){try(OutputStream out=getContentResolver().openOutputStream(uri,"wt")){if(out==null)throw new IOException("保存先を開けません");store.exportZip(project,out);}respond(pending,true);}
            else if(request==IMPORT){idle();try(InputStream in=getContentResolver().openInputStream(uri)){if(in==null)throw new IOException("ファイルを開けません");String id=store.importZip(in,displayName(uri).replaceFirst("(?i)\\.zip$",""));select(id);respond(pending,bootstrap());changed();}}
            else {idle();String name=displayName(uri).replaceAll("[\\\\/\\p{Cntrl}]","_");String path="attachments/"+name;try(InputStream in=getContentResolver().openInputStream(uri)){if(in==null)throw new IOException("ファイルを開けません");ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1){if(bytes.size()+n>ProjectStore.MAX_FILE_BYTES)throw new IOException("添付は8MB以下にしてください");bytes.write(buffer,0,n);}store.snapshot(project,"ファイル追加前");store.writeBytes(project,path,bytes.toByteArray());respond(pending,object("path",path));filesChanged();}}
        }catch(Exception e){fail(pending,safe(e));}});
    }
    private String displayName(Uri uri){try(Cursor cursor=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(cursor!=null&&cursor.moveToFirst())return cursor.getString(0);}return "file-"+System.currentTimeMillis();}
    private String readAsset(String name)throws IOException{try(InputStream in=getAssets().open(name)){ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)bytes.write(b,0,n);return bytes.toString("UTF-8");}}
    private void openExternal(String url){runOnUiThread(()->{try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));}catch(Exception e){event("error","message","ブラウザを開けません");}});}
    private void workStart(){runOnUiThread(()->{if(++foregroundUsers==1)try{startForegroundService(new Intent(this,WorkService.class));}catch(Exception e){record("バックグラウンド継続不可: "+safe(e));}});}
    private void workStop(){runOnUiThread(()->{foregroundUsers=Math.max(0,foregroundUsers-1);if(foregroundUsers==0)stopService(new Intent(this,WorkService.class));});}
    private void respond(JSONObject r,Object data){if(r!=null)sendJs("receiveNative",object("id",r.opt("id"),"ok",true,"data",data));}
    private void fail(JSONObject r,String message){record(message);if(r!=null)sendJs("receiveNative",object("id",r.opt("id"),"ok",false,"error",message));}
    private void event(String type,String key,Object value){emit(object("type",type,key,value));}
    private void emit(JSONObject object){sendJs("nativeEvent",object);}
    private void sendJs(String fn,JSONObject value){String script="window."+fn+"&&window."+fn+"("+value.toString().replace("\u2028","\\u2028").replace("\u2029","\\u2029")+");";runOnUiThread(()->{if(!destroyed&&web!=null)web.evaluateJavascript(script,null);});}
    private void record(String text){synchronized(logs){logs.addLast(new java.text.SimpleDateFormat("HH:mm:ss",Locale.ROOT).format(new Date())+" "+text);while(logs.size()>100)logs.removeFirst();}}
    private static String safe(Throwable e){String m=e.getMessage();return m==null?e.getClass().getSimpleName():m;}
    private static JSONObject object(Object... pairs){JSONObject o=new JSONObject();try{for(int i=0;i+1<pairs.length;i+=2)o.put(String.valueOf(pairs[i]),pairs[i+1]==null?JSONObject.NULL:pairs[i+1]);}catch(JSONException e){throw new IllegalStateException(e);}return o;}
    @Override public void onBackPressed(){if(web!=null)web.evaluateJavascript("window.pocketBack?window.pocketBack():false",v->{if(!"true".equals(v))moveTaskToBack(true);});else super.onBackPressed();}
    @Override protected void onDestroy(){destroyed=true;engine.cancel();auth.cancelLogin();google.cancelConnect();tasks.shutdownNow();inference.shutdownNow();stopService(new Intent(this,WorkService.class));if(web!=null){web.removeJavascriptInterface("Native");web.destroy();}super.onDestroy();}
}
