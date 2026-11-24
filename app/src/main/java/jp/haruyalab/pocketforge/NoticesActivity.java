package jp.haruyalab.pocketforge;
import android.app.Activity;
import android.os.Bundle;
import android.webkit.*;
import android.widget.*;
/** Read-only dependency notices. No JavaScript or privileged bridge. */
public final class NoticesActivity extends Activity{
    private WebView web;
    @Override public void onCreate(Bundle b){super.onCreate(b);LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);layout.setOnApplyWindowInsetsListener((v,i)->{v.setPadding(i.getSystemWindowInsetLeft(),i.getSystemWindowInsetTop(),i.getSystemWindowInsetRight(),i.getSystemWindowInsetBottom());return i;});Button back=new Button(this);back.setText("戻る");back.setOnClickListener(v->finish());layout.addView(back);web=new WebView(this);web.getSettings().setJavaScriptEnabled(false);web.getSettings().setAllowFileAccess(false);web.getSettings().setAllowContentAccess(false);web.getSettings().setBlockNetworkLoads(true);web.setWebViewClient(new WebViewClient(){@Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){return !r.getUrl().toString().startsWith("file:///android_asset/third_party_notices/");}});layout.addView(web,new LinearLayout.LayoutParams(-1,0,1));setContentView(layout);web.loadUrl("file:///android_asset/third_party_notices/index.html");}
    @Override public void onDestroy(){if(web!=null)web.destroy();super.onDestroy();}
}
