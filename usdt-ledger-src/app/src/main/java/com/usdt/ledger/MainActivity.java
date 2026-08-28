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
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

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
        web.setWebViewClient(new WebViewClient(){
            @Override public void onPageFinished(WebView view,String url){
                super.onPageFinished(view,url);
                try{
                    view.evaluateJavascript(readAssetText("v21.js"), value21 -> {
                        try{
                            view.evaluateJavascript(readAssetText("v22.js"), value22 -> {
                                try{ view.evaluateJavascript(readAssetText("v23.js"),null); }catch(Exception ignored){}
                            });
                        }catch(Exception ignored){}
                    });
                }catch(Exception ignored){}
            }
        });
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

    private String readAssetText(String name) throws Exception{
        StringBuilder sb=new StringBuilder();
        try(BufferedReader br=new BufferedReader(new InputStreamReader(getAssets().open(name),StandardCharsets.UTF_8))){
            String line; while((line=br.readLine())!=null) sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private String getJson(String urlText) throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(urlText).openConnection();
        c.setConnectTimeout(7000); c.setReadTimeout(7000); c.setRequestMethod("GET");
        c.setRequestProperty("Accept","application/json"); c.setRequestProperty("User-Agent","USDT-Ledger/2.3");
        int code=c.getResponseCode(); if(code<200||code>=300) throw new Exception("行情接口返回 "+code);
        StringBuilder sb=new StringBuilder();
        try(BufferedReader br=new BufferedReader(new InputStreamReader(c.getInputStream(),StandardCharsets.UTF_8))){
            String line; while((line=br.readLine())!=null) sb.append(line);
        }finally{ c.disconnect(); }
        return sb.toString();
    }

    private double fetchCoinGecko() throws Exception{
        JSONObject j=new JSONObject(getJson("https://api.coingecko.com/api/v3/simple/price?ids=tether&vs_currencies=cny"));
        double r=j.getJSONObject("tether").getDouble("cny");
        if(r<=0)throw new Exception("CoinGecko 汇率无效");
        return r;
    }

    private double fetchCoinbase() throws Exception{
        JSONObject j=new JSONObject(getJson("https://api.coinbase.com/v2/exchange-rates?currency=USDT"));
        double r=j.getJSONObject("data").getJSONObject("rates").getDouble("CNY");
        if(r<=0)throw new Exception("Coinbase 汇率无效");
        return r;
    }

    public class Bridge {
        @JavascriptInterface public void fetchUsdtCnyRate(){
            new Thread(()->{
                try{
                    double rate; String source;
                    try{ rate=fetchCoinGecko(); source="CoinGecko"; }
                    catch(Exception first){ rate=fetchCoinbase(); source="Coinbase"; }
                    final double out=rate; final String src=source; final long ts=System.currentTimeMillis();
                    runOnUiThread(()->web.evaluateJavascript("onRealtimeRate("+out+","+JSONObject.quote(src)+","+ts+")",null));
                }catch(Exception ex){
                    final String msg=ex.getMessage()==null?"网络请求失败":ex.getMessage();
                    runOnUiThread(()->web.evaluateJavascript("onRealtimeRateError("+JSONObject.quote(msg)+")",null));
                }
            }).start();
        }

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
