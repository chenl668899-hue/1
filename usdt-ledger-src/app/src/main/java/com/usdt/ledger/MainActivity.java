package com.usdt.ledger;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.io.OutputStream;
import java.io.InputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URI;
import java.net.URLDecoder;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private static final int PICK_FILE=2001, SAVE_FILE=2002;
    private static final int LAN_PORT=8765;
    private String pendingText="", pendingMime="text/plain", pendingName="export.txt";
    private volatile String mirroredState="{\"projects\":[]}";
    private volatile boolean lanRunning=false;
    private volatile String pairingCode="";
    private ServerSocket lanServer;
    private Thread lanThread;
    private SharedPreferences prefs;

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        getWindow().setStatusBarColor(0xFFF4F7FB);
        getWindow().setNavigationBarColor(0xFFF4F7FB);
        if(android.os.Build.VERSION.SDK_INT>=23)getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        prefs=getSharedPreferences("usdt_ledger_native",MODE_PRIVATE);
        mirroredState=prefs.getString("mirror","{\"projects\":[]}");
        web=new WebView(this); setContentView(web);
        WebSettings s=web.getSettings();
        s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true); s.setAllowFileAccess(true); s.setAllowContentAccess(true);
        web.addJavascriptInterface(new Bridge(),"AndroidBridge");
        web.setWebViewClient(new WebViewClient(){
            @Override public void onPageFinished(WebView view,String url){
                super.onPageFinished(view,url);
                try{
                    view.evaluateJavascript(readAssetText("v21.js"), value -> {
                        try{
                            view.evaluateJavascript(readAssetText("v22.js"), value2 -> {
                                try{
                                    view.evaluateJavascript(readAssetText("v23.js"), value3 -> {
                                        try{ view.evaluateJavascript(readAssetText("v24.js"),null); }catch(Exception ignored){}
                                    });
                                }catch(Exception ignored){}
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
        c.setRequestProperty("Accept","application/json"); c.setRequestProperty("User-Agent","USDT-Ledger/2.4");
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

    private synchronized void startLanServerInternal(){
        if(lanRunning)return;
        pairingCode=String.format("%06d",new SecureRandom().nextInt(1000000));
        lanRunning=true;
        lanThread=new Thread(()->{
            try{
                ServerSocket ss=new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(LAN_PORT));
                lanServer=ss;
                while(lanRunning){
                    try{
                        Socket client=ss.accept();
                        new Thread(()->handleLanClient(client),"ledger-lan-client").start();
                    }catch(Exception ex){if(lanRunning){} }
                }
            }catch(Exception ex){
                lanRunning=false;
                pairingCode="";
            }finally{
                try{if(lanServer!=null)lanServer.close();}catch(Exception ignored){}
                lanServer=null;
            }
        },"ledger-lan-server");
        lanThread.start();
    }

    private synchronized void stopLanServerInternal(){
        lanRunning=false;
        pairingCode="";
        try{if(lanServer!=null)lanServer.close();}catch(Exception ignored){}
        lanServer=null;
    }

    private String getLocalIpv4(){
        String fallback=null;
        try{
            Enumeration<NetworkInterface> nis=NetworkInterface.getNetworkInterfaces();
            while(nis.hasMoreElements()){
                NetworkInterface ni=nis.nextElement();
                try{if(!ni.isUp()||ni.isLoopback())continue;}catch(Exception ignored){}
                String name=ni.getName()==null?"":ni.getName().toLowerCase();
                Enumeration<InetAddress> as=ni.getInetAddresses();
                while(as.hasMoreElements()){
                    InetAddress a=as.nextElement();
                    String host=a.getHostAddress();
                    if(host==null||host.contains(":")||a.isLoopbackAddress())continue;
                    if(a.isSiteLocalAddress()){
                        if(name.contains("wlan")||name.contains("wifi")||name.contains("ap"))return host;
                        if(fallback==null)fallback=host;
                    }
                }
            }
        }catch(Exception ignored){}
        return fallback==null?"127.0.0.1":fallback;
    }

    private Map<String,String> parseQuery(String raw){
        Map<String,String> out=new HashMap<>();
        if(raw==null||raw.isEmpty())return out;
        for(String part:raw.split("&")){
            int i=part.indexOf('=');
            try{
                String k=URLDecoder.decode(i>=0?part.substring(0,i):part,"UTF-8");
                String v=URLDecoder.decode(i>=0?part.substring(i+1):"","UTF-8");
                out.put(k,v);
            }catch(Exception ignored){}
        }
        return out;
    }

    private void handleLanClient(Socket socket){
        try(Socket s=socket){
            s.setSoTimeout(10000);
            InputStream in=s.getInputStream();
            ByteArrayOutputStream hb=new ByteArrayOutputStream();
            int matched=0,b;
            byte[] end=new byte[]{13,10,13,10};
            while((b=in.read())!=-1&&hb.size()<65536){
                hb.write(b);
                if(b==(end[matched]&0xff)){matched++;if(matched==4)break;}else matched=(b==(end[0]&0xff))?1:0;
            }
            String header=new String(hb.toByteArray(),StandardCharsets.ISO_8859_1);
            String[] lines=header.split("\\r\\n");
            if(lines.length==0){sendJson(s,400,new JSONObject().put("ok",false).put("error","bad request"));return;}
            String[] req=lines[0].split(" ");
            if(req.length<2){sendJson(s,400,new JSONObject().put("ok",false).put("error","bad request"));return;}
            String method=req[0].toUpperCase(),target=req[1];
            int contentLength=0;
            for(String line:lines){
                int idx=line.indexOf(':');if(idx<=0)continue;
                if(line.substring(0,idx).trim().equalsIgnoreCase("Content-Length")){
                    try{contentLength=Integer.parseInt(line.substring(idx+1).trim());}catch(Exception ignored){}
                }
            }
            if(contentLength>5_000_000){sendJson(s,413,new JSONObject().put("ok",false).put("error","data too large"));return;}
            byte[] bodyBytes=new byte[Math.max(0,contentLength)];
            int off=0;while(off<bodyBytes.length){int r=in.read(bodyBytes,off,bodyBytes.length-off);if(r<0)break;off+=r;}
            String body=new String(bodyBytes,0,off,StandardCharsets.UTF_8);
            URI uri=new URI("http://127.0.0.1"+target);
            Map<String,String> q=parseQuery(uri.getRawQuery());
            if("OPTIONS".equals(method)){sendEmpty(s,204);return;}
            if(!lanRunning||pairingCode.isEmpty()||!pairingCode.equals(q.get("code"))){sendJson(s,403,new JSONObject().put("ok",false).put("error","配对码错误或手机传输未开启"));return;}
            String path=uri.getPath();
            if("GET".equals(method)&&"/info".equals(path)){
                JSONObject stateObj;try{stateObj=new JSONObject(mirroredState);}catch(Exception e){stateObj=new JSONObject().put("projects",new org.json.JSONArray());}
                JSONObject out=new JSONObject().put("ok",true).put("app","USDT承兑台账").put("version","2.4").put("projects",stateObj.optJSONArray("projects")!=null?stateObj.optJSONArray("projects").length():0);
                sendJson(s,200,out);return;
            }
            if("GET".equals(method)&&"/data".equals(path)){
                JSONObject data;try{data=new JSONObject(mirroredState);}catch(Exception e){data=new JSONObject().put("projects",new org.json.JSONArray());}
                sendJson(s,200,new JSONObject().put("ok",true).put("data",data));return;
            }
            if("POST".equals(method)&&"/data".equals(path)){
                JSONObject incoming=new JSONObject(body);
                if(incoming.optJSONArray("projects")==null){sendJson(s,400,new JSONObject().put("ok",false).put("error","账本格式不正确"));return;}
                String mode="merge".equalsIgnoreCase(q.get("mode"))?"merge":"replace";
                final String payload=incoming.toString(),modeFinal=mode;
                runOnUiThread(()->web.evaluateJavascript("onLanDataReceived("+JSONObject.quote(payload)+","+JSONObject.quote(modeFinal)+")",null));
                sendJson(s,200,new JSONObject().put("ok",true).put("mode",mode));return;
            }
            sendJson(s,404,new JSONObject().put("ok",false).put("error","not found"));
        }catch(Exception ignored){}
    }

    private void sendEmpty(Socket s,int code) throws Exception{
        String h="HTTP/1.1 "+code+" No Content\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Methods: GET,POST,OPTIONS\r\nAccess-Control-Allow-Headers: Content-Type\r\nConnection: close\r\nContent-Length: 0\r\n\r\n";
        OutputStream os=s.getOutputStream();os.write(h.getBytes(StandardCharsets.UTF_8));os.flush();
    }

    private void sendJson(Socket s,int code,JSONObject obj) throws Exception{
        byte[] body=obj.toString().getBytes(StandardCharsets.UTF_8);
        String reason=code>=200&&code<300?"OK":"Error";
        String h="HTTP/1.1 "+code+" "+reason+"\r\nContent-Type: application/json; charset=utf-8\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Methods: GET,POST,OPTIONS\r\nAccess-Control-Allow-Headers: Content-Type\r\nCache-Control: no-store\r\nConnection: close\r\nContent-Length: "+body.length+"\r\n\r\n";
        OutputStream os=s.getOutputStream();os.write(h.getBytes(StandardCharsets.UTF_8));os.write(body);os.flush();
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

        @JavascriptInterface public void syncState(String json){
            try{
                JSONObject j=new JSONObject(json);
                if(j.optJSONArray("projects")==null)return;
                mirroredState=j.toString();
                prefs.edit().putString("mirror",mirroredState).apply();
            }catch(Exception ignored){}
        }

        @JavascriptInterface public String getLanInfo(){
            try{return new JSONObject().put("running",lanRunning).put("ip",getLocalIpv4()).put("port",LAN_PORT).put("code",pairingCode).toString();}
            catch(Exception e){return "{\"running\":false}";}
        }

        @JavascriptInterface public void startLanServer(){startLanServerInternal();}
        @JavascriptInterface public void stopLanServer(){stopLanServerInternal();}

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

    @Override protected void onDestroy(){stopLanServerInternal();super.onDestroy();}
    @Override public void onBackPressed(){ if(web!=null&&web.canGoBack()) web.goBack(); else super.onBackPressed(); }
}
