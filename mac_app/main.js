const { app, BrowserWindow, ipcMain, dialog, shell } = require('electron');
const path = require('path');
const fs = require('fs');
const crypto = require('crypto');
const AdmZip = require('adm-zip');
const { XMLParser } = require('fast-xml-parser');
const XLSX = require('xlsx');

let mainWindow;
const parser = new XMLParser({ ignoreAttributes:false, attributeNamePrefix:'@_', removeNSPrefix:true });

function appDataDir(){ const p=app.getPath('userData'); fs.mkdirSync(p,{recursive:true}); fs.mkdirSync(path.join(p,'images'),{recursive:true}); return p; }
function statePath(){ return path.join(appDataDir(),'state.json'); }
function seedDir(){ return path.join(__dirname,'seed'); }
function normalizeProduct(p){
  const cp = Number(p.costPrice||0) || Number(p.salePrice||0) || 0;
  return {
    id:String(p.id||crypto.randomUUID()), name:String(p.name||''), code:String(p.code||''), moq:Number(p.moq||0),
    costPrice:cp, salePrice:Number(p.costPrice ? (p.salePrice||0) : 0), remark:String(p.remark||''), aliases:Array.isArray(p.aliases)?p.aliases:[],
    imagePath:p.imagePath||''
  };
}
function defaultState(){
  let products=[];
  try { products=JSON.parse(fs.readFileSync(path.join(seedDir(),'products.json'),'utf8')).map(normalizeProduct); } catch(e){}
  return { products, orders:[], channels:['未设置','微信','线下','平台','经销商'], company:{name:'惠州市岁康商贸'} };
}
function loadState(){
  try { const s=JSON.parse(fs.readFileSync(statePath(),'utf8')); s.products=(s.products||[]).map(normalizeProduct); s.orders=s.orders||[]; s.channels=s.channels&&s.channels.length?s.channels:['未设置']; return s; }
  catch(e){ const s=defaultState(); saveState(s); return s; }
}
function saveState(s){ fs.writeFileSync(statePath(),JSON.stringify(s,null,2),'utf8'); return {ok:true}; }
function createWindow(){
  mainWindow=new BrowserWindow({width:1440,height:900,minWidth:1100,minHeight:700,title:'岁康商贸销售管理系统',backgroundColor:'#f4f7fb',webPreferences:{preload:path.join(__dirname,'preload.js'),contextIsolation:true,nodeIntegration:false}});
  mainWindow.loadFile('index.html');
}
app.whenReady().then(()=>{createWindow();app.on('activate',()=>{if(BrowserWindow.getAllWindows().length===0)createWindow();});});
app.on('window-all-closed',()=>{if(process.platform!=='darwin')app.quit();});

ipcMain.handle('state:load',()=>loadState());
ipcMain.handle('state:save',(e,s)=>saveState(s));
ipcMain.handle('file:reveal',(e,p)=>{if(p)shell.showItemInFolder(p);return true;});
ipcMain.handle('image:choose',async()=>{
  const r=await dialog.showOpenDialog(mainWindow,{properties:['openFile'],filters:[{name:'图片',extensions:['png','jpg','jpeg','webp']}]});
  if(r.canceled||!r.filePaths[0])return null;
  const src=r.filePaths[0],ext=path.extname(src)||'.jpg',dst=path.join(appDataDir(),'images',crypto.randomUUID()+ext.toLowerCase());
  fs.copyFileSync(src,dst);return dst;
});

function normalizeHeader(s){return String(s||'').replace(/\s+/g,'').toLowerCase();}
function findHeader(rows){
  const aliases={name:['产品名称','名称','品名'],code:['编号','产品编号','货号','型号'],image:['图片','产品图片'],moq:['起拿数量','起订量','起拿','moq'],price:['价格','成本价','单价'],remark:['备注','说明']};
  for(let r=0;r<Math.min(rows.length,20);r++){
    const map={};(rows[r]||[]).forEach((v,c)=>{const h=normalizeHeader(v);for(const [k,arr] of Object.entries(aliases))if(arr.some(a=>h.includes(normalizeHeader(a))))map[k]=c;});
    if(map.name!==undefined||map.code!==undefined)return{row:r,map};
  }
  return{row:0,map:{name:0,code:1,image:2,moq:3,price:4,remark:5}};
}
function parseDrawingImages(filePath,sheetPath,imageColIndex){
  const zip=new AdmZip(filePath),entries={};zip.getEntries().forEach(e=>entries[e.entryName]=e);
  const relPath=path.posix.join(path.posix.dirname(sheetPath),'_rels',path.posix.basename(sheetPath)+'.rels');const relEntry=entries[relPath];if(!relEntry)return new Map();
  const rels=parser.parse(relEntry.getData().toString('utf8'));const relArr=[].concat((rels.Relationships&&rels.Relationships.Relationship)||[]);let drawTarget='';for(const r of relArr)if(String(r['@_Type']||'').includes('/drawing'))drawTarget=r['@_Target'];if(!drawTarget)return new Map();
  const drawingPath=path.posix.normalize(path.posix.join(path.posix.dirname(sheetPath),drawTarget));const dr=entries[drawingPath];if(!dr)return new Map();
  const drRelPath=path.posix.join(path.posix.dirname(drawingPath),'_rels',path.posix.basename(drawingPath)+'.rels');const drRel=entries[drRelPath],ridMap={};
  if(drRel){const rr=parser.parse(drRel.getData().toString('utf8'));for(const x of [].concat((rr.Relationships&&rr.Relationships.Relationship)||[]))ridMap[x['@_Id']]=path.posix.normalize(path.posix.join(path.posix.dirname(drawingPath),x['@_Target']));}
  const obj=parser.parse(dr.getData().toString('utf8')),root=obj.wsDr||obj.xdr_wsDr||obj;let anchors=[];for(const k of Object.keys(root||{}))if(/Anchor$/i.test(k))anchors=anchors.concat([].concat(root[k]||[]));
  const out=new Map();for(const a of anchors){const from=a.from||a.xdr_from||{},row=Number(from.row??-1),col=Number(from.col??-1);if(row<0)continue;if(imageColIndex>=0&&col>=0&&Math.abs(col-imageColIndex)>1)continue;const pic=a.pic||{},blipFill=pic.blipFill||{},blip=blipFill.blip||{},rid=blip['@_embed']||blip['@_r:embed'],media=ridMap[rid];if(!media||!entries[media])continue;out.set(row+1,{buf:entries[media].getData(),ext:path.extname(media)||'.png'});}return out;
}
async function importExcel(){
  const r=await dialog.showOpenDialog(mainWindow,{properties:['openFile'],filters:[{name:'Excel',extensions:['xlsx']}]});if(r.canceled||!r.filePaths[0])return null;
  const fp=r.filePaths[0],wb=XLSX.readFile(fp,{cellDates:false}),ws=wb.Sheets[wb.SheetNames[0]],rows=XLSX.utils.sheet_to_json(ws,{header:1,defval:''}),{row:hr,map}=findHeader(rows);
  const zip=new AdmZip(fp),wbXml=parser.parse(zip.readAsText('xl/workbook.xml')),relXml=parser.parse(zip.readAsText('xl/_rels/workbook.xml.rels')),sheet=(wbXml.workbook.sheets.sheet instanceof Array?wbXml.workbook.sheets.sheet[0]:wbXml.workbook.sheets.sheet),rid=sheet['@_id']||sheet['@_r:id'];
  const rels=[].concat(relXml.Relationships.Relationship||[]),rel=rels.find(x=>x['@_Id']===rid);let sheetPath='xl/worksheets/sheet1.xml';if(rel)sheetPath=path.posix.normalize(path.posix.join('xl',String(rel['@_Target']).replace(/^\//,'').replace(/^xl\//,'')));
  const imgMap=parseDrawingImages(fp,sheetPath,map.image===undefined?-1:map.image),state=loadState(),byCode=new Map(state.products.filter(p=>p.code).map(p=>[normalizeHeader(p.code),p])),byName=new Map(state.products.filter(p=>p.name).map(p=>[normalizeHeader(p.name),p]));
  let added=0,updated=0,images=0,skipped=0;
  for(let i=hr+1;i<rows.length;i++){
    const rr=rows[i]||[],name=String(rr[map.name]||'').trim(),code=String(rr[map.code]||'').trim();if(!name&&!code){skipped++;continue;}
    const moq=Number(rr[map.moq]||0)||0,cost=Number(rr[map.price]||0)||0,remark=String(rr[map.remark]||'').trim();let p=(code&&byCode.get(normalizeHeader(code)))||(name&&byName.get(normalizeHeader(name)));
    if(!p){p={id:crypto.randomUUID(),name,code,moq,costPrice:cost,salePrice:0,remark,aliases:[],imagePath:''};state.products.unshift(p);added++;}else{p.name=name||p.name;p.code=code||p.code;p.moq=moq;p.costPrice=cost;p.remark=remark;updated++;}
    const im=imgMap.get(i+1);if(im){const ext=['.jpg','.jpeg','.png','.webp'].includes(im.ext.toLowerCase())?im.ext.toLowerCase():'.png',dst=path.join(appDataDir(),'images',p.id+ext);fs.writeFileSync(dst,im.buf);p.imagePath=dst;images++;}
    if(p.code)byCode.set(normalizeHeader(p.code),p);if(p.name)byName.set(normalizeHeader(p.name),p);
  }
  saveState(state);return{added,updated,images,skipped,total:state.products.length};
}
ipcMain.handle('excel:import',()=>importExcel());

function esc(s){return String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));}
function imgData(p){try{if(!p||!fs.existsSync(p))return'';const ext=path.extname(p).toLowerCase(),mime=ext==='.png'?'image/png':ext==='.webp'?'image/webp':'image/jpeg';return`data:${mime};base64,${fs.readFileSync(p).toString('base64')}`;}catch(e){return'';}}
function quoteHtml(o){
  const total=(o.items||[]).reduce((a,i)=>a+Number(i.quantity||0)*Number(i.salePrice||0),0),dep=Number(o.depositAmount||0)||(total*Number(o.depositRate||0)/100),bal=Math.max(0,total-dep);
  const items=(o.items||[]).map((i,n)=>{const d=imgData(i.imagePath);return`<tr><td>${n+1}</td><td>${d?`<img src="${d}">`:''}</td><td><b>${esc(i.name)}</b><br><small>${esc(i.code)}</small></td><td>${Number(i.quantity||0)}</td><td>¥${Number(i.salePrice||0).toFixed(2)}</td><td>¥${(Number(i.quantity||0)*Number(i.salePrice||0)).toFixed(2)}</td></tr>`}).join('');
  const rate=Number(o.depositRate||0)||(total?dep/total*100:0);
  return`<!doctype html><html><head><meta charset="utf-8"><style>@page{size:A4;margin:18mm}body{font-family:-apple-system,BlinkMacSystemFont,'PingFang SC',sans-serif;color:#182b44}h1{margin:0 0 8px}.sub{color:#6e7e91;margin-bottom:20px}.info{background:#f4f7fb;padding:14px;border-radius:10px;margin-bottom:16px}table{border-collapse:collapse;width:100%;font-size:12px}th,td{border-bottom:1px solid #e1e7ef;padding:9px;text-align:left}th{background:#0d2741;color:white}img{width:56px;height:56px;object-fit:contain}.sum{margin-top:20px;margin-left:auto;width:300px}.sum div{display:flex;justify-content:space-between;padding:6px 0}.total{font-size:18px;font-weight:700}.brand{color:#0d2741;font-weight:700}.foot{margin-top:28px;color:#8190a3;font-size:11px}</style></head><body><h1>岁康商贸 · 客户报价单</h1><div class="sub">单号 ${esc(o.id)}　日期 ${esc(o.date)}</div><div class="info">客户：${esc(o.customerName||'未填写')}　 联系人：${esc(o.contact||'')}　 电话：${esc(o.phone||'')}<br>销售渠道：${esc(o.salesChannel||'未设置')}</div><table><thead><tr><th>#</th><th>图片</th><th>产品</th><th>数量</th><th>单价</th><th>小计</th></tr></thead><tbody>${items}</tbody></table><div class="sum"><div class="total"><span>报价总金额</span><span>¥${total.toFixed(2)}</span></div>${dep>0?`<div><span>定金比例</span><span>${rate.toFixed(2)}%</span></div><div><span>定金金额</span><span>¥${dep.toFixed(2)}</span></div><div><span>剩余尾款</span><span>¥${bal.toFixed(2)}</span></div>`:''}</div><div class="foot">本报价不包含成本、利润及内部费用信息<br><span class="brand">惠州市岁康商贸</span></div></body></html>`;
}
ipcMain.handle('export:quote-pdf',async(e,o)=>{const r=await dialog.showSaveDialog(mainWindow,{defaultPath:`岁康商贸_${o.customerName||'客户'}_报价单_${o.date||''}.pdf`,filters:[{name:'PDF',extensions:['pdf']}]});if(r.canceled)return null;const win=new BrowserWindow({show:false});await win.loadURL('data:text/html;charset=utf-8,'+encodeURIComponent(quoteHtml(o)));const buf=await win.webContents.printToPDF({printBackground:true,pageSize:'A4'});fs.writeFileSync(r.filePath,buf);win.destroy();return r.filePath;});
function exportInternal(order,fp){const rows=[['岁康商贸内部利润单'],['单号',order.id,'日期',order.date,'客户',order.customerName,'销售渠道',order.salesChannel],[],['产品名称','编号','数量','成本价','销售价','成本小计','销售小计','毛利润']];(order.items||[]).forEach(i=>rows.push([i.name,i.code,Number(i.quantity||0),Number(i.costPrice||0),Number(i.salePrice||0),Number(i.quantity||0)*Number(i.costPrice||0),Number(i.quantity||0)*Number(i.salePrice||0),Number(i.quantity||0)*(Number(i.salePrice||0)-Number(i.costPrice||0))]));const sales=(order.items||[]).reduce((a,i)=>a+Number(i.quantity||0)*Number(i.salePrice||0),0),cost=(order.items||[]).reduce((a,i)=>a+Number(i.quantity||0)*Number(i.costPrice||0),0),extra=(order.fees||[]).reduce((a,f)=>a+Number(f.amount||0),0),freight=Number(order.freight||0),net=sales-cost-freight-extra;rows.push([],['销售总额',sales],['货品总成本',cost],['商品毛利润',sales-cost],['货代费用',freight],['其他费用',extra],['最终净利润',net],['净利润率',sales?net/sales:0],['定金比例',Number(order.depositRate||0)/100],['定金金额',Number(order.depositAmount||0)]);const wb=XLSX.utils.book_new(),ws=XLSX.utils.aoa_to_sheet(rows);XLSX.utils.book_append_sheet(wb,ws,'内部利润');XLSX.writeFile(wb,fp);}
ipcMain.handle('export:internal-xlsx',async(e,o)=>{const r=await dialog.showSaveDialog(mainWindow,{defaultPath:`岁康商贸_${o.customerName||'客户'}_内部利润_${o.date||''}.xlsx`,filters:[{name:'Excel',extensions:['xlsx']}]});if(r.canceled)return null;exportInternal(o,r.filePath);return r.filePath;});
ipcMain.handle('export:orders-xlsx',async(e,{orders,label})=>{const r=await dialog.showSaveDialog(mainWindow,{defaultPath:`岁康商贸_${label||'订单汇总'}.xlsx`,filters:[{name:'Excel',extensions:['xlsx']}]});if(r.canceled)return null;const rows=[['单号','日期','客户','渠道','销售额','成本','净利润','定金','尾款']];for(const o of orders){const sales=(o.items||[]).reduce((a,i)=>a+Number(i.quantity||0)*Number(i.salePrice||0),0),cost=(o.items||[]).reduce((a,i)=>a+Number(i.quantity||0)*Number(i.costPrice||0),0),extra=(o.fees||[]).reduce((a,f)=>a+Number(f.amount||0),0),net=sales-cost-Number(o.freight||0)-extra,dep=Number(o.depositAmount||0);rows.push([o.id,o.date,o.customerName,o.salesChannel,sales,cost,net,dep,Math.max(0,sales-dep)]);}const wb=XLSX.utils.book_new();XLSX.utils.book_append_sheet(wb,XLSX.utils.aoa_to_sheet(rows),'订单汇总');XLSX.writeFile(wb,r.filePath);return r.filePath;});
