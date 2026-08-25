package com.usdt.ledger;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private static final int PICK_FILE=2001, SAVE_FILE=2002;
    private String pendingText="", pendingMime="text/plain", pendingName="export.txt";

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        getWindow().setStatusBarColor(0xFFF4F7FB);
        getWindow().setNavigationBarColor(0xFFF4F7FB);
        if(android.os.Build.VERSION.SDK_INT>=23)getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        web=new WebView(this); setContentView(web);
        WebSettings s=web.getSettings();
        s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true); s.setAllowFileAccess(true); s.setAllowContentAccess(true);
        web.addJavascriptInterface(new Bridge(),"AndroidBridge");
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient(){
            @Override public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, FileChooserParams params){
                if(fileCallback!=null) fileCallback.onReceiveValue(null);
                fileCallback=cb;
                Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("application/json");
                try{ startActivityForResult(i,PICK_FILE); }catch(Exception ex){ fileCallback.onReceiveValue(null); fileCallback=null; }
                return true;
            }
        });
        web.loadUrl("file:///android_asset/index.html");
    }

    public class Bridge {
        @JavascriptInterface public void saveText(String name,String text,String mime){
            pendingName=name==null?"export.txt":name; pendingText=text==null?"":text; pendingMime=mime==null?"text/plain":mime;
            runOnUiThread(()->{
                Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType(pendingMime); i.putExtra(Intent.EXTRA_TITLE,pendingName);
                startActivityForResult(i,SAVE_FILE);
            });
        }
    }

    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request==PICK_FILE){
            if(fileCallback!=null){ Uri[] out=(result==RESULT_OK&&data!=null&&data.getData()!=null)?new Uri[]{data.getData()}:null; fileCallback.onReceiveValue(out); fileCallback=null; }
            return;
        }
        if(request==SAVE_FILE&&result==RESULT_OK&&data!=null&&data.getData()!=null){
            try(OutputStream os=getContentResolver().openOutputStream(data.getData())){ os.write(pendingText.getBytes(StandardCharsets.UTF_8)); os.flush(); }catch(Exception ignored){}
        }
    }

    @Override public void onBackPressed(){ if(web!=null&&web.canGoBack()) web.goBack(); else super.onBackPressed(); }
}
