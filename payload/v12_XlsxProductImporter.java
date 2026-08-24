package com.suikang.sales;

import android.content.Context;
import android.net.Uri;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.parsers.DocumentBuilderFactory;

/** Lightweight XLSX importer tailored for the Suikang product template. */
public final class XlsxProductImporter {
    private XlsxProductImporter() {}

    public static final class ImportRow {
        int sourceRow;
        String name="", code="", moqRaw="", saleRaw="", costRaw="", remark="", imageEntry="";
        double moq=0, sale=0, cost=0;
    }

    public static final class ImportResult {
        public File tempXlsx;
        public final List<ImportRow> rows = new ArrayList<>();
        public int newCount, updateCount, duplicateCount, incompleteCount, imageCount;
        public int headerRow;
        public boolean hasCostColumn, hasSaleColumn, genericPriceAsCost;
        public String summaryColumns="";
        public void cleanup(){ if(tempXlsx!=null) { try{tempXlsx.delete();}catch(Exception ignored){} } }
    }

    public static final class ApplyResult {
        public int added, updated, imagesSaved, skipped;
    }

    public static ImportResult parse(Context ctx, Uri uri, List<Models.Product> existing) throws Exception {
        ImportResult result = new ImportResult();
        File tmp = File.createTempFile("suikang_import_", ".xlsx", ctx.getCacheDir());
        result.tempXlsx = tmp;
        try(InputStream in=ctx.getContentResolver().openInputStream(uri); OutputStream out=new FileOutputStream(tmp)){
            if(in==null) throw new IOException("无法读取选择的 Excel 文件");
            byte[] b=new byte[64*1024]; int n; while((n=in.read(b))>0) out.write(b,0,n);
        }

        try(ZipFile zip=new ZipFile(tmp)){
            List<String> shared = readSharedStrings(zip);
            String sheetPath = firstSheetPath(zip);
            if(sheetPath==null) throw new IOException("Excel 中没有找到工作表");
            Document sheet = xml(zip, sheetPath);
            if(sheet==null) throw new IOException("无法解析 Excel 工作表");

            Map<Integer,Map<Integer,String>> grid = readGrid(sheet, shared);
            Header h = detectHeader(grid);
            if(h==null) throw new IOException("没有识别到表头，请使用包含“产品名称、编号、图片、起拿数量、价格、备注”的岁康产品表格式");
            result.headerRow=h.row;
            result.hasCostColumn=h.cost>=0;
            result.hasSaleColumn=h.sale>=0;
            result.genericPriceAsCost=h.genericPriceAsCost;
            result.summaryColumns=h.describe();

            Map<Integer,String> imageByRow = readImagesByRow(zip, sheetPath, sheet, h.image);
            List<ImportRow> rawRows=new ArrayList<>();
            int maxRow=0; for(Integer r:grid.keySet()) maxRow=Math.max(maxRow,r);
            for(int row=h.row+1; row<=maxRow; row++){
                Map<Integer,String> cells=grid.get(row);
                String name=cell(cells,h.name), code=cell(cells,h.code);
                String moqRaw=cell(cells,h.moq), saleRaw=cell(cells,h.sale), costRaw=cell(cells,h.cost), remark=cell(cells,h.remark);
                String img=imageByRow.getOrDefault(row-1,"");
                if(blank(name)&&blank(code)&&blank(moqRaw)&&blank(saleRaw)&&blank(costRaw)&&blank(remark)&&blank(img)) continue;
                if(blank(code)&&blank(img)&&(blank(name)||(blank(moqRaw)&&blank(saleRaw)&&blank(costRaw)))) continue;
                ImportRow ir=new ImportRow(); ir.sourceRow=row; ir.name=name.trim(); ir.code=code.trim();
                ir.moqRaw=moqRaw.trim(); ir.saleRaw=saleRaw.trim(); ir.costRaw=costRaw.trim(); ir.remark=remark.trim(); ir.imageEntry=img;
                ir.moq=firstNumber(ir.moqRaw); ir.sale=firstNumber(ir.saleRaw); ir.cost=firstNumber(ir.costRaw);
                rawRows.add(ir);
            }
            Map<String,Integer> codeFreq=new HashMap<>();
            for(ImportRow r:rawRows){String c=normKey(r.code);if(!c.isEmpty())codeFreq.put(c,codeFreq.getOrDefault(c,0)+1);}
            for(Integer n:codeFreq.values())if(n>1)result.duplicateCount+=n-1;
            LinkedHashMap<String,ImportRow> merged=new LinkedHashMap<>();
            for(ImportRow r:rawRows){
                String c=normKey(r.code), n=normKey(r.name);String key;
                if(!c.isEmpty()&&codeFreq.getOrDefault(c,0)>1)key="C:"+c+"|N:"+n; else key=productKey(r.code,r.name);
                if(key.isEmpty())key="ROW:"+r.sourceRow;
                ImportRow prev=merged.get(key);
                if(prev==null)merged.put(key,r); else mergeNonBlank(prev,r);
            }
            result.rows.addAll(merged.values());
            for(ImportRow r: result.rows){
                if(blank(r.name)||blank(r.code)) result.incompleteCount++;
                if(!blank(r.imageEntry)) result.imageCount++;
                if(findExisting(r,existing)!=null)result.updateCount++;else result.newCount++;
            }
        } catch(Exception e){ result.cleanup(); throw e; }
        return result;
    }

    public static ApplyResult apply(Context ctx, ImportResult result, List<Models.Product> products) throws Exception {
        ApplyResult ar=new ApplyResult();
        File imageDir=new File(ctx.getFilesDir(),"product_images"); if(!imageDir.exists()&&!imageDir.mkdirs()) throw new IOException("无法创建产品图片目录");
        try(ZipFile zip=new ZipFile(result.tempXlsx)){
            for(ImportRow r:result.rows){
                Models.Product p=findExisting(r,products); boolean isNew=p==null;
                if(isNew){p=new Models.Product();p.id="X"+System.currentTimeMillis()+"_"+r.sourceRow;products.add(p);ar.added++;}
                else ar.updated++;
                if(!blank(r.name))p.name=r.name;
                if(!blank(r.code))p.code=r.code;
                if(!blank(r.moqRaw))p.moq=r.moq;
                if(!blank(r.saleRaw)){p.salePrice=r.sale;p.salePriceRaw=r.saleRaw;}
                if(result.hasCostColumn && !blank(r.costRaw))p.costPrice=r.cost;
                if(result.genericPriceAsCost && !result.hasSaleColumn){p.salePrice=0;p.salePriceRaw="";}
                if(!blank(r.remark))p.remark=r.remark;
                if(!blank(r.imageEntry)){
                    ZipEntry ze=zip.getEntry(r.imageEntry);
                    if(ze!=null){
                        String ext=extension(r.imageEntry); String base=safeName(!blank(p.code)?p.code:p.id);
                        File target=new File(imageDir,base+"_"+System.currentTimeMillis()+"_"+r.sourceRow+"."+ext);
                        try(InputStream in=zip.getInputStream(ze); OutputStream out=new FileOutputStream(target)){
                            byte[] b=new byte[32*1024];int n;while((n=in.read(b))>0)out.write(b,0,n);
                        }
                        p.imageAsset=""; p.imageUri=Uri.fromFile(target).toString(); ar.imagesSaved++;
                    }
                }
            }
        } finally { result.cleanup(); }
        return ar;
    }

    private static Models.Product findExisting(ImportRow r,List<Models.Product> products){
        String c=normKey(r.code), n=normKey(r.name);List<Models.Product> codeMatches=new ArrayList<>();
        if(!c.isEmpty())for(Models.Product p:products)if(c.equals(normKey(p.code)))codeMatches.add(p);
        if(codeMatches.size()==1)return codeMatches.get(0);
        if(codeMatches.size()>1&&!n.isEmpty())for(Models.Product p:codeMatches)if(n.equals(normKey(p.name)))return p;
        if(!n.isEmpty()){Models.Product only=null;for(Models.Product p:products)if(n.equals(normKey(p.name))){if(only!=null)return null;only=p;}return only;}
        return null;
    }
    private static void mergeNonBlank(ImportRow base,ImportRow next){
        if(blank(base.name)&&!blank(next.name))base.name=next.name;if(blank(base.code)&&!blank(next.code))base.code=next.code;
        if(blank(base.moqRaw)&&!blank(next.moqRaw)){base.moqRaw=next.moqRaw;base.moq=next.moq;}
        if(blank(base.saleRaw)&&!blank(next.saleRaw)){base.saleRaw=next.saleRaw;base.sale=next.sale;}
        if(blank(base.costRaw)&&!blank(next.costRaw)){base.costRaw=next.costRaw;base.cost=next.cost;}
        if(blank(base.remark)&&!blank(next.remark))base.remark=next.remark;if(blank(base.imageEntry)&&!blank(next.imageEntry))base.imageEntry=next.imageEntry;
    }

    private static final class Header {
        int row, name=-1,code=-1,image=-1,moq=-1,sale=-1,cost=-1,remark=-1;
        boolean genericPriceAsCost=false;
        String describe(){
            StringBuilder b=new StringBuilder("产品名称/编号/图片/起拿数量/");
            if(cost>=0)b.append(genericPriceAsCost?"价格→成本价/":"成本价/");
            if(sale>=0)b.append("销售价/");
            b.append("备注");
            return b.toString();
        }
    }

    private static Header detectHeader(Map<Integer,Map<Integer,String>> grid){
        for(Map.Entry<Integer,Map<Integer,String>> e:grid.entrySet()){
            Header h=new Header();h.row=e.getKey();
            for(Map.Entry<Integer,String> c:e.getValue().entrySet()){
                String s=normHeader(c.getValue());int col=c.getKey();
                if(s.equals("产品名称")||s.equals("名称")||s.equals("品名"))h.name=col;
                else if(s.equals("编号")||s.equals("产品编号")||s.equals("货号")||s.equals("sku"))h.code=col;
                else if(s.equals("图片")||s.equals("产品图片"))h.image=col;
                else if(s.contains("起拿")||s.contains("起订")||s.equals("moq")||s.equals("数量"))h.moq=col;
                else if(s.equals("成本价")||s.equals("成本")||s.equals("采购价")||s.equals("进货价"))h.cost=col;
                else if(s.equals("价格")||s.equals("单价")){h.cost=col;h.genericPriceAsCost=true;}
                else if(s.equals("销售价")||s.equals("售价")||s.equals("报价")||s.equals("客户价"))h.sale=col;
                else if(s.equals("备注")||s.equals("说明"))h.remark=col;
            }
            if(h.name>=0&&h.code>=0&&(h.sale>=0||h.cost>=0||h.moq>=0)) return h;
        }
        return null;
    }

    private static Map<Integer,Map<Integer,String>> readGrid(Document doc,List<String> shared){
        Map<Integer,Map<Integer,String>> grid=new TreeMap<>(); NodeList rows=doc.getElementsByTagNameNS("*","row");
        for(int i=0;i<rows.getLength();i++){
            Element row=(Element)rows.item(i);int r=intVal(row.getAttribute("r"),i+1);Map<Integer,String> cols=new HashMap<>();
            NodeList cells=row.getElementsByTagNameNS("*","c");
            for(int j=0;j<cells.getLength();j++){
                Element c=(Element)cells.item(j);String ref=c.getAttribute("r");int col=columnIndex(ref);String type=c.getAttribute("t");String val="";
                if("inlineStr".equals(type)){NodeList ts=c.getElementsByTagNameNS("*","t");StringBuilder sb=new StringBuilder();for(int k=0;k<ts.getLength();k++)sb.append(ts.item(k).getTextContent());val=sb.toString();}
                else {NodeList vs=c.getElementsByTagNameNS("*","v");if(vs.getLength()>0)val=vs.item(0).getTextContent();if("s".equals(type)){int idx=intVal(val,-1);val=idx>=0&&idx<shared.size()?shared.get(idx):"";}}
                cols.put(col,val==null?"":val);
            }
            grid.put(r,cols);
        }
        return grid;
    }

    private static List<String> readSharedStrings(ZipFile zip)throws Exception{
        List<String> out=new ArrayList<>();Document d=xml(zip,"xl/sharedStrings.xml");if(d==null)return out;NodeList sis=d.getElementsByTagNameNS("*","si");
        for(int i=0;i<sis.getLength();i++){Element si=(Element)sis.item(i);NodeList ts=si.getElementsByTagNameNS("*","t");StringBuilder s=new StringBuilder();for(int j=0;j<ts.getLength();j++)s.append(ts.item(j).getTextContent());out.add(s.toString());}
        return out;
    }

    private static String firstSheetPath(ZipFile zip)throws Exception{
        Document wb=xml(zip,"xl/workbook.xml"), rel=xml(zip,"xl/_rels/workbook.xml.rels"); if(wb==null||rel==null)return "xl/worksheets/sheet1.xml";
        NodeList sheets=wb.getElementsByTagNameNS("*","sheet");if(sheets.getLength()==0)return null;Element s=(Element)sheets.item(0);String rid=s.getAttributeNS("http://schemas.openxmlformats.org/officeDocument/2006/relationships","id");if(blank(rid))rid=s.getAttribute("r:id");
        NodeList rs=rel.getElementsByTagNameNS("*","Relationship");for(int i=0;i<rs.getLength();i++){Element r=(Element)rs.item(i);if(rid.equals(r.getAttribute("Id")))return resolve("xl/workbook.xml",r.getAttribute("Target"));}
        return "xl/worksheets/sheet1.xml";
    }

    private static Map<Integer,String> readImagesByRow(ZipFile zip,String sheetPath,Document sheet,int imageCol)throws Exception{
        Map<Integer,String> out=new HashMap<>();
        String relPath=relsPath(sheetPath);Document srel=xml(zip,relPath);if(srel==null)return out;
        NodeList drawingNodes=sheet.getElementsByTagNameNS("*","drawing");if(drawingNodes.getLength()==0)return out;Element de=(Element)drawingNodes.item(0);String rid=de.getAttributeNS("http://schemas.openxmlformats.org/officeDocument/2006/relationships","id");if(blank(rid))rid=de.getAttribute("r:id");
        String drawingPath="";NodeList rels=srel.getElementsByTagNameNS("*","Relationship");for(int i=0;i<rels.getLength();i++){Element r=(Element)rels.item(i);if(rid.equals(r.getAttribute("Id"))){drawingPath=resolve(sheetPath,r.getAttribute("Target"));break;}}
        if(blank(drawingPath))return out;Document drawing=xml(zip,drawingPath);Document drel=xml(zip,relsPath(drawingPath));if(drawing==null||drel==null)return out;
        Map<String,String> targets=new HashMap<>();NodeList drs=drel.getElementsByTagNameNS("*","Relationship");for(int i=0;i<drs.getLength();i++){Element r=(Element)drs.item(i);targets.put(r.getAttribute("Id"),resolve(drawingPath,r.getAttribute("Target")));}
        NodeList anchors=drawing.getElementsByTagNameNS("*","oneCellAnchor");List<Element> all=new ArrayList<>();for(int i=0;i<anchors.getLength();i++)all.add((Element)anchors.item(i));NodeList two=drawing.getElementsByTagNameNS("*","twoCellAnchor");for(int i=0;i<two.getLength();i++)all.add((Element)two.item(i));
        for(Element a:all){NodeList froms=a.getElementsByTagNameNS("*","from");if(froms.getLength()==0)continue;Element from=(Element)froms.item(0);int row=childInt(from,"row",-1),col=childInt(from,"col",-1);if(row<0)continue;if(imageCol>=0&&col>=0&&col!=imageCol)continue;NodeList blips=a.getElementsByTagNameNS("*","blip");if(blips.getLength()==0)continue;Element b=(Element)blips.item(0);String id=b.getAttributeNS("http://schemas.openxmlformats.org/officeDocument/2006/relationships","embed");if(blank(id))id=b.getAttribute("r:embed");String t=targets.get(id);if(!blank(t)&&zip.getEntry(t)!=null)out.put(row,t);}
        return out;
    }

    private static int childInt(Element e,String local,int def){NodeList n=e.getElementsByTagNameNS("*",local);return n.getLength()>0?intVal(n.item(0).getTextContent(),def):def;}
    private static Document xml(ZipFile zip,String path)throws Exception{ZipEntry e=zip.getEntry(path);if(e==null)return null;try(InputStream in=zip.getInputStream(e)){DocumentBuilderFactory f=DocumentBuilderFactory.newInstance();f.setNamespaceAware(true);try{f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);}catch(Exception ignored){}return f.newDocumentBuilder().parse(in);}}
    private static String relsPath(String p){int slash=p.lastIndexOf('/');String dir=slash>=0?p.substring(0,slash+1):"";String name=slash>=0?p.substring(slash+1):p;return dir+"_rels/"+name+".rels";}
    private static String resolve(String base,String target){
        if(target.startsWith("/"))return target.substring(1);int slash=base.lastIndexOf('/');String dir=slash>=0?base.substring(0,slash+1):"";String joined=dir+target;Deque<String> parts=new ArrayDeque<>();for(String s:joined.split("/")){if(s.equals("..")){if(!parts.isEmpty())parts.removeLast();}else if(!s.equals(".")&&!s.isEmpty())parts.addLast(s);}return String.join("/",parts);
    }
    private static int columnIndex(String ref){int n=0;for(int i=0;i<ref.length();i++){char c=ref.charAt(i);if(c>='A'&&c<='Z')n=n*26+(c-'A'+1);else if(c>='a'&&c<='z')n=n*26+(c-'a'+1);else break;}return n-1;}
    private static String cell(Map<Integer,String> m,int col){return m==null||col<0?"":m.getOrDefault(col,"");}
    private static String normHeader(String s){return (s==null?"":s).trim().toLowerCase(Locale.ROOT).replace(" ","").replace("：","").replace(":","");}
    private static String normKey(String s){return (s==null?"":s).trim().toLowerCase(Locale.ROOT).replaceAll("[\\s_\\-（）()]+","");}
    private static String productKey(String code,String name){String c=normKey(code);if(!c.isEmpty())return "C:"+c;String n=normKey(name);return n.isEmpty()?"":"N:"+n;}
    private static boolean blank(String s){return s==null||s.trim().isEmpty();}
    private static int intVal(String s,int d){try{return Integer.parseInt(s.trim());}catch(Exception e){return d;}}
    private static double firstNumber(String s){if(blank(s))return 0;String x=s.replace(",","");StringBuilder b=new StringBuilder();boolean dot=false,started=false;for(int i=0;i<x.length();i++){char c=x.charAt(i);if((c>='0'&&c<='9')||(!started&&(c=='-'||c=='+'))||(c=='.'&&!dot)){b.append(c);started=true;if(c=='.')dot=true;}else if(started)break;}try{return Double.parseDouble(b.toString());}catch(Exception e){return 0;}}
    private static String extension(String p){int dot=p.lastIndexOf('.');String e=dot>=0?p.substring(dot+1).toLowerCase(Locale.ROOT):"jpg";return e.matches("png|jpg|jpeg|webp")?e:"jpg";}
    private static String safeName(String s){String x=s==null?"product":s.replaceAll("[^A-Za-z0-9._-]","_");return x.isEmpty()?"product":x;}
}
