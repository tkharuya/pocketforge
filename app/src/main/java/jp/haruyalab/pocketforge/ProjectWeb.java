package jp.haruyalab.pocketforge;

import android.webkit.*;
import android.net.Uri;
import java.io.*;
import java.util.*;

/** Only project source files are exposed, never app storage, credentials or Native bridge. */
final class ProjectWeb extends WebViewClient {
    private final ProjectStore store; private final String id; final String origin;
    ProjectWeb(ProjectStore store,String id){this.store=store;this.id=id;origin="https://p-"+id+".pocketforge.invalid";}
    static void lockDown(WebView view){
        WebSettings s=view.getSettings();s.setJavaScriptEnabled(true);s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);s.setAllowContentAccess(false);s.setBlockNetworkLoads(true);
        s.setAllowFileAccessFromFileURLs(false);s.setAllowUniversalAccessFromFileURLs(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setSupportMultipleWindows(false);s.setMediaPlaybackRequiresUserGesture(true);
        view.setWebChromeClient(new WebChromeClient(){@Override public void onPermissionRequest(PermissionRequest r){r.deny();}});
    }
    @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest req){return !req.getUrl().toString().startsWith(origin+"/");}
    @Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest req){
        Uri u=req.getUrl(); if(!"GET".equals(req.getMethod()) || !u.toString().startsWith(origin+"/")) return denied("外部ネットワークは無効です");
        try {
            String path=u.getPath();if(path==null||path.equals("/"))path="/index.html";
            byte[] data=store.readBytes(id,path.substring(1));
            Map<String,String> headers=new HashMap<>();headers.put("Cache-Control","no-store");headers.put("X-Content-Type-Options","nosniff");
            headers.put("Content-Security-Policy","default-src 'self' data: blob:; script-src 'self' 'unsafe-inline' 'unsafe-eval' blob:; style-src 'self' 'unsafe-inline'; connect-src 'self'; object-src 'none'; frame-src 'none'; base-uri 'self'; form-action 'none'");
            return new WebResourceResponse(mime(path),"UTF-8",200,"OK",headers,new ByteArrayInputStream(data));
        }catch(Exception e){return denied("ファイルが見つかりません");}
    }
    static WebResourceResponse denied(String message){return new WebResourceResponse("text/plain","UTF-8",403,"Blocked",Collections.emptyMap(),new ByteArrayInputStream(message.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
    static String mime(String p){
        String x=p.toLowerCase(Locale.ROOT);if(x.endsWith(".html")||x.endsWith(".htm"))return "text/html";
        if(x.endsWith(".js")||x.endsWith(".mjs"))return "text/javascript";if(x.endsWith(".css"))return "text/css";
        if(x.endsWith(".json"))return "application/json";if(x.endsWith(".svg"))return "image/svg+xml";
        int dot=x.lastIndexOf('.');String ext=dot>x.lastIndexOf('/')?x.substring(dot+1):"";String m=MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);return m==null?"text/plain":m;
    }
}
