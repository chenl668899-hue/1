package com.suikang.sales;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

public final class PdfQuotationExporter {
    private static final int W=595,H=842,L=42,R=553,TOP=46,BOTTOM=795;
    private static final int NAVY=Color.rgb(13,39,65), BLUE=Color.rgb(39,109,216), MUTED=Color.rgb(103,116,134), LINE=Color.rgb(222,228,236);
    private PdfQuotationExporter(){}

    private static class S { PdfDocument doc; PdfDocument.Page page; Canvas c; int y=TOP, no=0; Paint p=new Paint(Paint.ANTI_ALIAS_FLAG); Context ctx; Models.SalesOrder o; }

    public static void export(Context ctx, Models.SalesOrder o, OutputStream out) throws Exception {
        S s=new S();s.ctx=ctx;s.o=o;s.doc=new PdfDocument();newPage(s,true);
        info(s);
        section(s,"产品明细");
        for(int i=0;i<o.items.size();i++) item(s,o.items.get(i),i+1);
        ensure(s,170);
        line(s);
        s.y+=16;
        labelValue(s,"报价总金额",money(o.sales()),18,true);
        if(o.depositRate>0||o.depositAmount>0){
            double amount=o.depositAmount>0?o.depositAmount:o.sales()*o.depositRate/100.0;
            double rate=o.depositRate>0?o.depositRate:(o.sales()>0?amount/o.sales()*100.0:0);
            labelValue(s,"定金比例",String.format(Locale.CHINA,"%.2f%%",rate),13,true);
            labelValue(s,"定金金额",money(amount),13,true);
            labelValue(s,"剩余尾款",money(Math.max(0,o.sales()-amount)),13,true);
        }
        s.y+=8;
        txt(s,"备注：本报价仅显示客户报价及付款信息，不包含成本、利润及内部费用数据",MUTED,10,false,L,s.y);s.y+=18;
        txt(s,"惠州市岁康商贸",NAVY,11,true,L,s.y);
        finishPage(s);s.doc.writeTo(out);s.doc.close();
    }

    private static void newPage(S s, boolean first){ if(s.page!=null)finishPage(s);s.no++;PdfDocument.PageInfo pi=new PdfDocument.PageInfo.Builder(W,H,s.no).create();s.page=s.doc.startPage(pi);s.c=s.page.getCanvas();s.c.drawColor(Color.WHITE);s.y=TOP;
        s.p.setColor(NAVY);s.c.drawRect(0,0,W,78,s.p);txt(s,"岁康商贸",Color.WHITE,20,true,L,34);txt(s,"客户报价单",Color.WHITE,14,false,L,58);
        txt(s,"单号  "+safe(s.o.id)+"    日期  "+safe(s.o.date),Color.rgb(210,224,240),9,false,330,57);
        s.y=100;
    }
    private static void finishPage(S s){ if(s.page==null)return;txt(s,"第 "+s.no+" 页",MUTED,8,false,500,820);s.doc.finishPage(s.page);s.page=null; }
    private static void ensure(S s,int need){ if(s.y+need>BOTTOM)newPage(s,false); }
    private static void info(S s){
        txt(s,"客户："+(safe(s.o.customerName).isEmpty()?"未填写":safe(s.o.customerName)),NAVY,13,true,L,s.y);s.y+=22;
        txt(s,"联系人："+safe(s.o.contact)+"    联系电话："+safe(s.o.phone),MUTED,10,false,L,s.y);s.y+=18;
        txt(s,"销售渠道："+(safe(s.o.salesChannel).isEmpty()?"未设置":safe(s.o.salesChannel)),MUTED,10,false,L,s.y);s.y+=24;
        line(s);s.y+=16;
    }
    private static void section(S s,String t){ensure(s,40);txt(s,t,NAVY,15,true,L,s.y);s.y+=18;s.p.setColor(BLUE);s.c.drawRect(L,s.y,R,s.y+2,s.p);s.y+=15;}
    private static void item(S s,Models.OrderItem it,int idx){ensure(s,92);int top=s.y;Bitmap bm=load(s.ctx,it);if(bm!=null){RectF dst=new RectF(L,top,L+58,top+58);s.c.drawBitmap(bm,null,dst,s.p);}else{ s.p.setStyle(Paint.Style.STROKE);s.p.setColor(LINE);s.c.drawRect(L,top,L+58,top+58,s.p);s.p.setStyle(Paint.Style.FILL);txt(s,"图片",MUTED,9,false,L+17,top+33);}
        int x=L+72;txt(s,idx+". "+safe(it.name),NAVY,13,true,x,top+14);txt(s,"编号："+safe(it.code),MUTED,9,false,x,top+31);
        txt(s,"数量："+num(it.quantity),Color.DKGRAY,10,false,x,top+49);txt(s,"单价："+money(it.salePrice),Color.DKGRAY,10,false,335,top+49);txt(s,"小计："+money(it.revenue()),NAVY,11,true,435,top+49);
        if(it.remark!=null&&!it.remark.trim().isEmpty())txt(s,"备注："+clip(it.remark,26),MUTED,8,false,x,top+65);
        s.y=top+78;line(s);s.y+=12;
    }
    private static void labelValue(S s,String l,String v,int size,boolean bold){txt(s,l,NAVY,size,bold,L,s.y);Paint p=paint(s,NAVY,size,bold);float w=p.measureText(v);s.c.drawText(v,R-w,s.y,p);s.y+=26;}
    private static void line(S s){s.p.setColor(LINE);s.p.setStrokeWidth(1);s.c.drawLine(L,s.y,R,s.y,s.p);}
    private static Paint paint(S s,int color,float size,boolean bold){s.p.setColor(color);s.p.setTextSize(size);s.p.setTypeface(Typeface.create("sans-serif",bold?Typeface.BOLD:Typeface.NORMAL));s.p.setStyle(Paint.Style.FILL);return s.p;}
    private static void txt(S s,String t,int color,float size,boolean bold,float x,float y){s.c.drawText(t==null?"":t,x,y,paint(s,color,size,bold));}
    private static Bitmap load(Context c,Models.OrderItem it){try{InputStream in=null;if(it.imageUri!=null&&!it.imageUri.isEmpty()){Uri u=Uri.parse(it.imageUri);if("file".equalsIgnoreCase(u.getScheme()))in=new FileInputStream(new File(u.getPath()));else in=c.getContentResolver().openInputStream(u);}if(in==null&&it.imageAsset!=null&&!it.imageAsset.isEmpty())in=c.getAssets().open(it.imageAsset);if(in==null)return null;Bitmap b=BitmapFactory.decodeStream(in);in.close();return b;}catch(Exception e){return null;}}
    private static String money(double v){return String.format(Locale.CHINA,"¥%,.2f",v);}
    private static String num(double v){if(Math.abs(v-Math.rint(v))<0.000001)return String.format(Locale.CHINA,"%.0f",v);return String.format(Locale.CHINA,"%.2f",v);}
    private static String safe(String s){return s==null?"":s.trim();}
    private static String clip(String s,int n){s=safe(s);return s.length()<=n?s:s.substring(0,n)+"…";}
}
