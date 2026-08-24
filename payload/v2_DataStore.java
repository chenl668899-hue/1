package com.suikang.sales;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class DataStore {
    private final Context context;
    private final File productsFile;
    private final File ordersFile;
    private final File channelsFile;

    public DataStore(Context context) {
        this.context = context.getApplicationContext();
        productsFile = new File(context.getFilesDir(), "products.json");
        ordersFile = new File(context.getFilesDir(), "orders.json");
        channelsFile = new File(context.getFilesDir(), "sales_channels.json");
        ensureSeed();
        migrateLegacyPriceToCost();
    }

    private void ensureSeed() {
        if (productsFile.exists()) return;
        try (InputStream in=context.getAssets().open("products.json"); OutputStream out=new FileOutputStream(productsFile)) {
            byte[] buf=new byte[8192]; int n; while((n=in.read(buf))>0) out.write(buf,0,n);
        } catch (Exception ignored) {}
    }

    private void migrateLegacyPriceToCost() {
        File marker = new File(context.getFilesDir(), "migration_price_is_cost_v12.done");
        if (marker.exists() || !productsFile.exists()) return;
        try {
            JSONArray a = new JSONArray(read(productsFile));
            boolean changed = false;
            for (int i=0;i<a.length();i++) {
                JSONObject o=a.optJSONObject(i); if(o==null) continue;
                String id=o.optString("id","");
                double sale=o.optDouble("salePrice",0);
                double cost=o.optDouble("costPrice",0);
                if ((id.startsWith("P") || id.startsWith("X")) && cost==0 && sale!=0) {
                    o.put("costPrice",sale); o.put("salePrice",0); o.put("salePriceRaw",""); changed=true;
                }
            }
            if(changed) write(productsFile,a.toString());
            try(OutputStream out=new FileOutputStream(marker)){out.write("done".getBytes(StandardCharsets.UTF_8));}
        } catch(Exception ignored) {}
    }

    private String read(File f) {
        if (!f.exists()) return "[]";
        try (InputStream in=new FileInputStream(f); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] b=new byte[8192];int n;while((n=in.read(b))>0)out.write(b,0,n);
            return out.toString(StandardCharsets.UTF_8.name());
        } catch(Exception e){ return "[]"; }
    }

    private void write(File f, String text) {
        try (OutputStream out=new FileOutputStream(f)) { out.write(text.getBytes(StandardCharsets.UTF_8)); }
        catch(Exception ignored){}
    }

    public List<Models.Product> loadProducts() {
        List<Models.Product> list=new ArrayList<>();
        try { JSONArray a=new JSONArray(read(productsFile)); for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i); if(o!=null)list.add(Models.Product.fromJson(o));} }
        catch(Exception ignored){}
        return list;
    }

    public void saveProducts(List<Models.Product> list) {
        JSONArray a=new JSONArray(); for(Models.Product p:list)a.put(p.toJson()); write(productsFile,a.toString());
    }

    public List<Models.SalesOrder> loadOrders() {
        List<Models.SalesOrder> list=new ArrayList<>();
        try { JSONArray a=new JSONArray(read(ordersFile)); for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o!=null)list.add(Models.SalesOrder.fromJson(o));} }
        catch(Exception ignored){}
        return list;
    }

    public void saveOrder(Models.SalesOrder order) {
        List<Models.SalesOrder> list=loadOrders(); boolean replaced=false;
        for(int i=0;i<list.size();i++){if(list.get(i).id.equals(order.id)){list.set(i,order);replaced=true;break;}}
        if(!replaced)list.add(0,order);
        JSONArray a=new JSONArray();for(Models.SalesOrder s:list)a.put(s.toJson());write(ordersFile,a.toString());
    }

    public List<String> loadSalesChannels() {
        List<String> out=new ArrayList<>();
        try { JSONArray a=new JSONArray(read(channelsFile)); for(int i=0;i<a.length();i++){String s=a.optString(i,"").trim();if(!s.isEmpty()&&!out.contains(s))out.add(s);} } catch(Exception ignored){}
        if(out.isEmpty()) out.add("未设置");
        else if(!out.contains("未设置")) out.add(0,"未设置");
        return out;
    }

    public void saveSalesChannels(List<String> channels) {
        JSONArray a=new JSONArray();
        for(String s:channels){String v=s==null?"":s.trim();if(!v.isEmpty()&&!"未设置".equals(v))a.put(v);}
        write(channelsFile,a.toString());
    }
}
