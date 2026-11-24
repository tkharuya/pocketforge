package jp.haruyalab.pocketforge;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.graphics.Color;
import android.view.*;
import android.webkit.*;
import android.widget.*;

public final class PreviewActivity extends Activity {
    private WebView web;private ValueCallback<Uri[]> fileChoice;
    @Override public void onCreate(Bundle state){super.onCreate(state);
        LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);layout.setBackgroundColor(Color.rgb(16,18,16));
        layout.setOnApplyWindowInsetsListener((v,i)->{v.setPadding(i.getSystemWindowInsetLeft(),i.getSystemWindowInsetTop(),i.getSystemWindowInsetRight(),i.getSystemWindowInsetBottom());return i;});
        LinearLayout bar=new LinearLayout(this);Button back=new Button(this);back.setText("戻る");back.setOnClickListener(v->finish());bar.addView(back);
        TextView label=new TextView(this);label.setText("  端末内プレビュー · 通信なし");label.setTextColor(Color.WHITE);bar.addView(label,new LinearLayout.LayoutParams(0,-1,1));label.setGravity(Gravity.CENTER_VERTICAL);
        Button reload=new Button(this);reload.setText("再読込");reload.setOnClickListener(v->web.reload());bar.addView(reload);layout.addView(bar);
        web=new WebView(this);ProjectWeb.lockDown(web);web.getSettings().setAllowContentAccess(true);
        web.setWebChromeClient(new WebChromeClient(){
            @Override public void onPermissionRequest(PermissionRequest r){r.deny();}
            @Override public boolean onShowFileChooser(WebView view,ValueCallback<Uri[]> callback,FileChooserParams params){
                if(fileChoice!=null)fileChoice.onReceiveValue(null);fileChoice=callback;
                Intent choose=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
                String[] types=params.getAcceptTypes();if(types!=null&&types.length>0&&types[0]!=null&&!types[0].isEmpty()&&types[0].contains("/"))choose.putExtra(Intent.EXTRA_MIME_TYPES,types);
                choose.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,params.getMode()==FileChooserParams.MODE_OPEN_MULTIPLE);
                try{startActivityForResult(choose,411);}catch(Exception e){fileChoice.onReceiveValue(null);fileChoice=null;}return true;
            }
        });
        String id=getIntent().getStringExtra("project");ProjectWeb client=new ProjectWeb(new ProjectStore(this),id);web.setWebViewClient(client);layout.addView(web,new LinearLayout.LayoutParams(-1,0,1));setContentView(layout);
        String path=getIntent().getStringExtra("path");if(path==null||path.isEmpty())path="index.html";
        try{new ProjectStore(this).readBytes(id,path);web.loadUrl(client.origin+"/"+path.replace("%","%25").replace("#","%23").replace("?","%3F").replace(" ","%20"));}catch(Exception e){Toast.makeText(this,"実行するファイルがありません",Toast.LENGTH_LONG).show();finish();}
    }
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==411&&fileChoice!=null){ValueCallback<Uri[]> cb=fileChoice;fileChoice=null;if(result!=RESULT_OK||data==null){cb.onReceiveValue(null);return;}if(data.getClipData()!=null){ClipData clip=data.getClipData();Uri[] uris=new Uri[clip.getItemCount()];for(int i=0;i<uris.length;i++)uris[i]=clip.getItemAt(i).getUri();cb.onReceiveValue(uris);}else cb.onReceiveValue(data.getData()==null?null:new Uri[]{data.getData()});}}
    @Override public void onDestroy(){if(fileChoice!=null){fileChoice.onReceiveValue(null);fileChoice=null;}if(web!=null){web.stopLoading();web.destroy();}super.onDestroy();}
}
