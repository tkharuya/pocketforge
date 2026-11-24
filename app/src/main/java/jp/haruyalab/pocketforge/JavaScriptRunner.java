package jp.haruyalab.pocketforge;

import android.content.Context;
import android.os.*;
import android.webkit.*;
import org.json.JSONObject;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Runs generated JS in a disposable empty DOM with no network/files or privileged methods. */
final class JavaScriptRunner {
    private final Context context;private final Handler main=new Handler(Looper.getMainLooper());
    JavaScriptRunner(Context context){this.context=context;}
    String run(String code) throws Exception{
        if(Looper.myLooper()==Looper.getMainLooper())throw new IllegalStateException("Background thread required");
        if(code.length()>200000)throw new IllegalArgumentException("JavaScriptが大きすぎます");
        CountDownLatch done=new CountDownLatch(1);AtomicReference<String> result=new AtomicReference<>();AtomicReference<WebView> holder=new AtomicReference<>();AtomicBoolean closed=new AtomicBoolean();
        Runnable cleanup=()->{closed.set(true);WebView w=holder.getAndSet(null);if(w!=null){w.stopLoading();w.removeJavascriptInterface("Result");w.destroy();}};
        Runnable timeout=()->{result.compareAndSet(null,"{\"ok\":false,\"error\":\"実行時間が10秒を超えました\"}");done.countDown();cleanup.run();};
        main.post(()->{
            try{
                WebView w=new WebView(context);holder.set(w);ProjectWeb.lockDown(w);w.getSettings().setDomStorageEnabled(false);
                w.setWebViewClient(new WebViewClient(){
                    @Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest r){return ProjectWeb.denied("network disabled");}
                    @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){return true;}
                    @Override public void onPageFinished(WebView v,String url){if(closed.get())return;
                        String script="(async()=>{const logs=[]; const fmt=v=>{try{return typeof v==='string'?v:JSON.stringify(v)}catch(e){return String(v)}}; ['log','warn','error','info'].forEach(k=>console[k]=(...a)=>{if(logs.length<200)logs.push(k+': '+a.map(fmt).join(' ').slice(0,4000))}); try{const AsyncFunction=Object.getPrototypeOf(async function(){}).constructor; const value=await new AsyncFunction("+JSONObject.quote(code)+")(); Result.finish(JSON.stringify({ok:true,value:fmt(value),logs}));}catch(e){Result.finish(JSON.stringify({ok:false,error:String(e),stack:String(e.stack||'').slice(0,4000),logs}));}})();";
                        v.evaluateJavascript(script,null);
                    }
                    @Override public boolean onRenderProcessGone(WebView v,RenderProcessGoneDetail d){result.compareAndSet(null,"{\"ok\":false,\"error\":\"実行プロセスが終了しました\"}");done.countDown();main.post(cleanup);return true;}
                });
                w.addJavascriptInterface(new Object(){@JavascriptInterface public void finish(String value){if(value.length()>1024*1024)value="{\"ok\":false,\"error\":\"出力サイズ超過\"}";if(result.compareAndSet(null,value)){done.countDown();main.post(cleanup);}}},"Result");
                main.postDelayed(timeout,10000);w.loadDataWithBaseURL("https://runner.pocketforge.invalid/","<!doctype html><meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; script-src 'unsafe-inline' 'unsafe-eval'; connect-src 'none'\"><body></body>","text/html","UTF-8",null);
            }catch(Exception e){result.compareAndSet(null,"{\"ok\":false,\"error\":"+JSONObject.quote(e.toString())+"}");done.countDown();cleanup.run();}
        });
        try{if(!done.await(12,TimeUnit.SECONDS)){return "{\"ok\":false,\"error\":\"実行が応答しません\"}";}return result.get();}finally{main.removeCallbacks(timeout);main.post(cleanup);}
    }
}
