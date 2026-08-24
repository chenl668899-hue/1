package com.suikang.sales;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

public final class Models {
    private Models() {}

    public static class Product {
        public String id="", name="", code="", salePriceRaw="", remark="", imageAsset="", imageUri="";
        public double moq=0, salePrice=0, costPrice=0;
        public final List<String> aliases = new ArrayList<>();

        public String displayName() {
            if (name != null && !name.trim().isEmpty()) return name.trim();
            if (code != null && !code.trim().isEmpty()) return code.trim();
            return "未命名产品";
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id); o.put("name", name); o.put("code", code); o.put("moq", moq);
                o.put("salePrice", salePrice); o.put("salePriceRaw", salePriceRaw); o.put("costPrice", costPrice);
                o.put("remark", remark); o.put("imageAsset", imageAsset); o.put("imageUri", imageUri);
                JSONArray a = new JSONArray(); for (String s: aliases) a.put(s); o.put("aliases", a);
            } catch (Exception ignored) {}
            return o;
        }

        public static Product fromJson(JSONObject o) {
            Product p = new Product();
            p.id=o.optString("id",""); p.name=o.optString("name",""); p.code=o.optString("code","");
            p.moq=o.optDouble("moq",0); p.salePrice=o.optDouble("salePrice",0); p.salePriceRaw=o.optString("salePriceRaw","");
            p.costPrice=o.optDouble("costPrice",0); p.remark=o.optString("remark","");
            p.imageAsset=o.optString("imageAsset",""); p.imageUri=o.optString("imageUri","");
            JSONArray a=o.optJSONArray("aliases"); if(a!=null) for(int i=0;i<a.length();i++) p.aliases.add(a.optString(i,""));
            return p;
        }
    }

    public static class FeeItem {
        public String name="其他费用"; public double amount=0;
        public JSONObject toJson(){ JSONObject o=new JSONObject(); try{o.put("name",name);o.put("amount",amount);}catch(Exception ignored){} return o; }
        public static FeeItem fromJson(JSONObject o){ FeeItem f=new FeeItem(); f.name=o.optString("name","其他费用");f.amount=o.optDouble("amount",0);return f; }
    }

    public static class OrderItem {
        public String productId="", name="", code="", imageAsset="", imageUri="", remark="";
        public double quantity=1, costPrice=0, salePrice=0;
        public double revenue(){ return quantity*salePrice; }
        public double cost(){ return quantity*costPrice; }
        public double grossProfit(){ return revenue()-cost(); }
        public JSONObject toJson(){
            JSONObject o=new JSONObject(); try{
                o.put("productId",productId);o.put("name",name);o.put("code",code);o.put("imageAsset",imageAsset);o.put("imageUri",imageUri);o.put("remark",remark);
                o.put("quantity",quantity);o.put("costPrice",costPrice);o.put("salePrice",salePrice);
            }catch(Exception ignored){} return o;
        }
        public static OrderItem fromJson(JSONObject o){
            OrderItem i=new OrderItem(); i.productId=o.optString("productId","");i.name=o.optString("name","");i.code=o.optString("code","");i.imageAsset=o.optString("imageAsset","");i.imageUri=o.optString("imageUri","");i.remark=o.optString("remark","");
            i.quantity=o.optDouble("quantity",1);i.costPrice=o.optDouble("costPrice",0);i.salePrice=o.optDouble("salePrice",0); return i;
        }
    }

    public static class SalesOrder {
        public String id="", date="", customerName="", contact="", phone="", note="", salesChannel="";
        public double freight=0;
        public final List<OrderItem> items=new ArrayList<>();
        public final List<FeeItem> fees=new ArrayList<>();
        public double sales(){ double v=0; for(OrderItem i:items)v+=i.revenue(); return v; }
        public double goodsCost(){ double v=0; for(OrderItem i:items)v+=i.cost(); return v; }
        public double grossProfit(){ return sales()-goodsCost(); }
        public double extraFees(){ double v=0; for(FeeItem f:fees)v+=f.amount; return v; }
        public double netProfit(){ return grossProfit()-freight-extraFees(); }
        public double margin(){ double s=sales(); return s==0?0:netProfit()/s*100.0; }
        public JSONObject toJson(){
            JSONObject o=new JSONObject(); try{
                o.put("id",id);o.put("date",date);o.put("customerName",customerName);o.put("contact",contact);o.put("phone",phone);o.put("note",note);o.put("salesChannel",salesChannel);o.put("freight",freight);
                JSONArray ia=new JSONArray();for(OrderItem i:items)ia.put(i.toJson());o.put("items",ia);
                JSONArray fa=new JSONArray();for(FeeItem f:fees)fa.put(f.toJson());o.put("fees",fa);
            }catch(Exception ignored){} return o;
        }
        public static SalesOrder fromJson(JSONObject o){
            SalesOrder s=new SalesOrder();s.id=o.optString("id","");s.date=o.optString("date","");s.customerName=o.optString("customerName","");s.contact=o.optString("contact","");s.phone=o.optString("phone","");s.note=o.optString("note","");s.salesChannel=o.optString("salesChannel","");s.freight=o.optDouble("freight",0);
            JSONArray ia=o.optJSONArray("items");if(ia!=null)for(int i=0;i<ia.length();i++){JSONObject x=ia.optJSONObject(i);if(x!=null)s.items.add(OrderItem.fromJson(x));}
            JSONArray fa=o.optJSONArray("fees");if(fa!=null)for(int i=0;i<fa.length();i++){JSONObject x=fa.optJSONObject(i);if(x!=null)s.fees.add(FeeItem.fromJson(x));}
            return s;
        }
    }
}
