from pathlib import Path
import json

def repl(path, old, new):
    p=Path(path); s=p.read_text()
    if old not in s:
        raise SystemExit(f'pattern not found in {path}: {old[:80]!r}')
    p.write_text(s.replace(old,new,1))

repl('app/build.gradle', "versionCode 2\n        versionName '1.1.0'", "versionCode 3\n        versionName '1.2.0'")
repl('app/src/main/AndroidManifest.xml', '        android:allowBackup="true"\n', '        android:allowBackup="true"\n        android:icon="@drawable/app_logo"\n        android:roundIcon="@drawable/app_logo"\n')
repl('app/src/main/res/values/strings.xml','岁康商贸销售管理系统管理系统','岁康商贸销售管理系统')

p=Path('app/src/main/java/com/suikang/sales/XlsxProductImporter.java'); s=p.read_text()
s=s.replace('public boolean hasCostColumn;','public boolean hasCostColumn, hasSaleColumn, genericPriceAsCost;')
s=s.replace('result.hasCostColumn=h.cost>=0;\n            result.summaryColumns=h.describe();','result.hasCostColumn=h.cost>=0;\n            result.hasSaleColumn=h.sale>=0;\n            result.genericPriceAsCost=h.genericPriceAsCost;\n            result.summaryColumns=h.describe();')
s=s.replace('if(result.hasCostColumn && !blank(r.costRaw))p.costPrice=r.cost;\n                if(!blank(r.remark))p.remark=r.remark;', 'if(result.hasCostColumn && !blank(r.costRaw))p.costPrice=r.cost;\n                // 原产品表“价格”字段是成本价，不是销售价。\n                if(result.genericPriceAsCost && !result.hasSaleColumn){p.salePrice=0;p.salePriceRaw="";}\n                if(!blank(r.remark))p.remark=r.remark;')
s=s.replace('int row, name=-1,code=-1,image=-1,moq=-1,sale=-1,cost=-1,remark=-1;\n        String describe(){return "产品名称/编号/图片/起拿数量/"+(cost>=0?"成本价/":"")+"销售价/备注";}', 'int row, name=-1,code=-1,image=-1,moq=-1,sale=-1,cost=-1,remark=-1;\n        boolean genericPriceAsCost=false;\n        String describe(){StringBuilder b=new StringBuilder("产品名称/编号/图片/起拿数量/");if(cost>=0)b.append(genericPriceAsCost?"价格→成本价/":"成本价/");if(sale>=0)b.append("销售价/");b.append("备注");return b.toString();}')
s=s.replace('else if(s.equals("成本价")||s.equals("成本")||s.equals("采购价"))h.cost=col;\n                else if(s.equals("价格")||s.equals("销售价")||s.equals("单价")||s.equals("报价"))h.sale=col;', 'else if(s.equals("成本价")||s.equals("成本")||s.equals("采购价")||s.equals("进货价"))h.cost=col;\n                else if(s.equals("价格")||s.equals("单价")){h.cost=col;h.genericPriceAsCost=true;}\n                else if(s.equals("销售价")||s.equals("售价")||s.equals("报价")||s.equals("客户价"))h.sale=col;')
s=s.replace('if(h.name>=0&&h.code>=0&&(h.sale>=0||h.moq>=0)) return h;', 'if(h.name>=0&&h.code>=0&&(h.sale>=0||h.cost>=0||h.moq>=0)) return h;')
p.write_text(s)

p=Path('app/src/main/java/com/suikang/sales/MainActivity.java'); s=p.read_text()
s=s.replace('正在识别产品、价格和图片…','正在识别产品、成本价和图片…')
s=s.replace('产品编号作为唯一识别码；相同编号更新原产品，新编号自动新增。','产品编号作为唯一识别码；原表“价格”按成本价导入；相同编号更新原产品，新编号自动新增。')
p.write_text(s)

p=Path('app/src/main/java/com/suikang/sales/DataStore.java'); s=p.read_text()
s=s.replace('        ensureSeed();\n    }', '        ensureSeed();\n        migrateLegacyPriceToCost();\n    }',1)
needle='''    private String read(File f) {'''
migration='''    /** V1.2: migrate V1.1 data where Excel “价格” was incorrectly stored as sale price. */\n    private void migrateLegacyPriceToCost() {\n        File marker = new File(context.getFilesDir(), "migration_price_is_cost_v12.done");\n        if (marker.exists() || !productsFile.exists()) return;\n        try {\n            JSONArray a = new JSONArray(read(productsFile));\n            boolean changed = false;\n            for (int i=0;i<a.length();i++) {\n                JSONObject o=a.optJSONObject(i); if(o==null) continue;\n                String id=o.optString("id","");\n                double sale=o.optDouble("salePrice",0), cost=o.optDouble("costPrice",0);\n                if ((id.startsWith("P") || id.startsWith("X")) && cost==0 && sale!=0) {\n                    o.put("costPrice",sale); o.put("salePrice",0); o.put("salePriceRaw",""); changed=true;\n                }\n            }\n            if(changed) write(productsFile,a.toString());\n            try(OutputStream out=new FileOutputStream(marker)){out.write("done".getBytes(StandardCharsets.UTF_8));}\n        } catch(Exception ignored) {}\n    }\n\n'''
if needle not in s: raise SystemExit('DataStore insertion point missing')
s=s.replace(needle,migration+needle,1)
p.write_text(s)

p=Path('app/src/main/assets/products.json')
a=json.loads(p.read_text())
for o in a:
    if str(o.get('id','')).startswith('P') and not o.get('costPrice',0) and o.get('salePrice',0):
        o['costPrice']=o.get('salePrice',0); o['salePrice']=0.0; o['salePriceRaw']=''
p.write_text(json.dumps(a,ensure_ascii=False,separators=(',',':')))
