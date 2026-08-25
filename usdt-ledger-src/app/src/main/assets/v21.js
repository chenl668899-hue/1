(() => {
  const style = document.createElement('style');
  style.textContent = `
.settle-choice{display:grid;grid-template-columns:1fr 1fr;gap:12px}.settle-card{border:1px solid #dce4ee;background:#f8fafc;border-radius:18px;padding:18px 14px;text-align:left}.settle-card b{display:block;font-size:17px;color:var(--ink);margin-bottom:7px}.settle-card span{display:block;font-size:11px;color:var(--muted);line-height:1.55}.settle-card.rate{border-color:#9cc8ff;background:#f2f7ff}.settle-card.points{border-color:#8ad6bc;background:#eef9f5}.rate-row{display:grid;grid-template-columns:1fr auto;gap:8px;align-items:end}.fetchbtn{border:0;border-radius:12px;padding:12px 14px;background:#eaf8f3;color:#087752;font-weight:850;white-space:nowrap}.fetchbtn:disabled{opacity:.55}.rate-meta{font-size:10px;color:var(--muted);margin-top:5px;min-height:15px}.calcbox{display:grid;gap:7px}.calcrow{display:flex;justify-content:space-between;gap:12px}.calcrow strong{font-weight:850}.modebadge{font-size:10px;border-radius:999px;padding:4px 7px;background:#f3efff;color:#6550a8;white-space:nowrap}.readonly{background:#f3f6f9!important;color:#596579}.switchlink{border:0;background:transparent;color:var(--blue);font-size:11px;padding:0}.menu-backdrop{position:fixed;inset:0;z-index:29;display:none}.menu-backdrop.show{display:block}`;
  document.head.appendChild(style);

  let formSettlement = 'RATE';
  let liveRateSource = '';
  let liveRateTime = '';

  const oldRenderHome = renderHome;
  renderHome = function(){
    oldRenderHome();
    const sub=document.querySelector('.subtitle');
    if(sub)sub.textContent='按项目独立记账 · V2.1 汇率/费率双模式';
    ensureMenuBackdrop();
  };

  migrateEntry = function(e){
    if(!e.direction)e.direction='CNY_TO_USDT';
    if(!e.settlementMode)e.settlementMode='RATE';
    if(e.baseRate==null)e.baseRate=e.costRate||'';
    if(e.adjustType==null)e.adjustType='UP';
    if(e.adjustValue==null){const b=n(e.baseRate),sr=n(e.settleRate);e.adjustValue=b&&sr?Math.abs(sr-b):''}
    if(e.actualCostRate==null)e.actualCostRate=e.costRate||'';
    if(e.usdtAmount==null)e.usdtAmount=e.usdtManual||'';
    if(e.pointsRate==null)e.pointsRate='';
    if(e.rateSource==null)e.rateSource='';
    if(e.rateFetchedAt==null)e.rateFetchedAt='';
    return e;
  };

  calcEntry = function(e){
    migrateEntry(e);
    const mode=e.settlementMode||'RATE',base=n(e.baseRate),cost=n(e.actualCostRate)||base,expenseRate=n(e.feeRate),points=Math.max(0,n(e.pointsRate));
    let cny=n(e.cnyAmount),usdt=n(e.usdtAmount),rate=base,grossCny=cny,netCny=cny,serviceFee=0;
    if(mode==='POINTS'){
      if(e.direction==='CNY_TO_USDT'){
        grossCny=cny;
        netCny=grossCny*Math.max(0,1-points/100);
        if(!usdt&&base)usdt=netCny/base;
        serviceFee=grossCny-netCny;
      }else{
        if(usdt&&base)grossCny=usdt*base;
        netCny=grossCny*Math.max(0,1-points/100);
        if(!cny)cny=netCny; else netCny=cny;
        serviceFee=Math.max(0,grossCny-netCny);
      }
      rate=base;
    }else{
      rate=dealRate(e);
      if(e.direction==='CNY_TO_USDT'){if(!usdt&&rate)usdt=cny/rate}
      else if(!cny&&rate)cny=usdt*rate;
      grossCny=cny; netCny=cny;
    }
    const expenseBase=mode==='POINTS'?(e.direction==='CNY_TO_USDT'?grossCny:netCny):cny;
    const fee=e.feeManual!==''&&e.feeManual!=null?n(e.feeManual):expenseBase*expenseRate/100;
    let profit;
    if(e.profitManual!==''&&e.profitManual!=null)profit=n(e.profitManual);
    else if(e.direction==='CNY_TO_USDT')profit=cny-usdt*cost-fee;
    else profit=usdt*cost-cny-fee;
    return{cny,usdt,base,rate,cost,fee,profit,feeRate:expenseRate,settlementMode:mode,pointsRate:points,grossCny,netCny,serviceFee};
  };

  settlementText = e => (e.settlementMode||'RATE')==='POINTS'?'费率结算':'汇率结算';
  window.formatRateTime = function(v){if(!v)return'';const d=new Date(Number(v)||v);return Number.isNaN(d.getTime())?String(v):d.toLocaleString('zh-CN',{hour12:false})};

  entryCard = function(e){
    const c=calcEntry(e),points=c.settlementMode==='POINTS';
    return `<div class="entry"><div class="entry-top"><div class="entry-date">${esc(e.date||'未填日期')}</div><div class="righttags"><span class="modebadge">${settlementText(e)}</span><span class="dirbadge">${dirText(e.direction)}</span><span class="status ${e.status==='已结清'?'done':'open'}">${esc(e.status||'未结清')}</span></div></div><div class="entry-main"><div><div class="entry-cny">¥ ${money(c.cny)}</div><div class="entry-usdt">₮ ${money(c.usdt,4)} USDT</div></div><div class="entry-profit"><div class="p profit ${c.profit>=0?'pos':'neg'}">${c.profit>=0?'+':''}¥ ${money(c.profit)}</div><div class="r">净利润</div></div></div><div class="entry-info">${points?`<div class="mini"><div class="k">实时汇率</div><div class="v">${c.base?c.base.toFixed(4):'-'}</div></div><div class="mini"><div class="k">收费点数</div><div class="v">${money(c.pointsRate,2)} 点</div></div><div class="mini"><div class="k">费率收入</div><div class="v">¥${money(c.serviceFee)}</div></div>`:`<div class="mini"><div class="k">实时基准汇率</div><div class="v">${c.base?c.base.toFixed(4):'-'}</div></div><div class="mini"><div class="k">成交汇率</div><div class="v">${c.rate?c.rate.toFixed(4):'-'}</div></div><div class="mini"><div class="k">实际成本汇率</div><div class="v">${c.cost?c.cost.toFixed(4):'-'}</div></div>`}</div>${e.rateSource?`<div class="note">汇率来源：${esc(e.rateSource)}${e.rateFetchedAt?' · '+esc(formatRateTime(e.rateFetchedAt)):''}</div>`:''}${e.note?`<div class="note">备注：${esc(e.note)}</div>`:''}<div class="entry-actions"><button class="textbtn" onclick="openEntryModal('${e.id}')">编辑</button><button class="textbtn danger" onclick="deleteEntry('${e.id}')">删除</button></div></div>`;
  };

  window.openEntryModal = function(id){
    const p=getProject();if(!p)return;editingEntryId=id||null;
    if(id){const e=migrateEntry(p.entries.find(x=>x.id===id));return(e.settlementMode||'RATE')==='POINTS'?openPointsEntryForm(id):openRateEntryForm(id)}
    openSettlementChoice();
  };

  window.openSettlementChoice = function(){
    editingEntryId=null;
    showModal(`<div class="sheet"><div class="sheet-title">选择结算方式</div><div class="settle-choice"><button class="settle-card rate" onclick="openRateEntryForm()"><b>按汇率结算</b><span>实时基准汇率 ＋/－ 浮动值，自动得到成交汇率</span></button><button class="settle-card points" onclick="openPointsEntryForm()"><b>按费率结算</b><span>按收费点数扣除，例如 100 元收 3 点，97 元参与兑换</span></button></div><div class="modal-actions" style="grid-template-columns:1fr"><button class="btn secondary" onclick="closeModal()">取消</button></div></div>`);
  };

  function commonTop(e){
    const today=new Date().toISOString().slice(0,10);
    return `<div class="field"><label>兑换方向 *</label><div class="direction"><button id="d_c2u" class="dirbtn" onclick="setDirection('CNY_TO_USDT')">人民币 → USDT</button><button id="d_u2c" class="dirbtn" onclick="setDirection('USDT_TO_CNY')">USDT → 人民币</button></div><div id="dirhint" class="dirhint"></div></div><div class="two"><div class="field"><label>日期 *</label><input type="date" id="e_date" value="${e?esc(e.date):today}"></div><div class="field"><label>结算状态</label><select id="e_status"><option ${e?.status!=='已结清'?'selected':''}>未结清</option><option ${e?.status==='已结清'?'selected':''}>已结清</option></select></div></div><div class="field"><label>实时汇率 *</label><div class="rate-row"><input type="number" step="0.0001" id="e_base" value="${e?.baseRate??''}" placeholder="可手工输入" oninput="markRateManual();syncAmounts()"><button type="button" id="fetchRateBtn" class="fetchbtn" onclick="requestRealtimeRate()">自动获取</button></div><div id="e_rate_meta" class="rate-meta"></div></div>`;
  }

  function initOrderForm(e,mode){
    formSettlement=mode;formDirection=e?.direction||'CNY_TO_USDT';formAdjust=e?.adjustType||'UP';liveRateSource=e?.rateSource||'';liveRateTime=e?.rateFetchedAt||'';lastInput=formDirection==='CNY_TO_USDT'?'cny':'usdt';
    refreshDirUI();refreshAdjustUI();renderRateMeta();syncAmounts();
  }

  window.openRateEntryForm = function(id){
    const p=getProject();if(!p)return;editingEntryId=id||null;const e=id?migrateEntry(p.entries.find(x=>x.id===id)):null;
    showModal(`<div class="sheet"><div class="sheet-title">${e?'编辑':'新增'}订单 · 按汇率结算</div><button class="switchlink" onclick="openSettlementChoice()">‹ 重新选择结算方式</button><div class="form" style="margin-top:12px">${commonTop(e)}<div class="two"><div class="field"><label>调整方式</label><div class="seg"><button id="a_up" type="button" onclick="setAdjust('UP')">上浮 ＋</button><button id="a_down" type="button" onclick="setAdjust('DOWN')">下浮 －</button></div></div><div class="field"><label>浮动值</label><input type="number" step="0.0001" id="e_adjust" value="${e?.adjustValue??''}" placeholder="例如 0.0300" oninput="syncAmounts()"></div></div><div class="field"><label>成交汇率（自动）</label><input id="e_deal" class="readonly" disabled></div><div class="two"><div class="field"><label>人民币金额 CNY</label><input type="number" step="0.01" id="e_cny" value="${e?.cnyAmount??''}" placeholder="输入人民币金额" oninput="lastInput='cny';syncAmounts()"></div><div class="field"><label>USDT数量</label><input type="number" step="0.0001" id="e_usdt" value="${e?.usdtAmount??''}" placeholder="输入USDT数量" oninput="lastInput='usdt';syncAmounts()"></div></div><div class="two"><div class="field"><label>实际成本汇率</label><input type="number" step="0.0001" id="e_cost" value="${e?.actualCostRate??''}" placeholder="留空=实时汇率" oninput="updateFormula()"></div><div class="field"><label>其他手续费率 %</label><input type="number" step="0.0001" id="e_feeRate" value="${e?.feeRate??0}" oninput="updateFormula()"></div></div><div class="two"><div class="field"><label>其他手续费/成本 CNY（可覆盖）</label><input type="number" step="0.01" id="e_fee" value="${e?.feeManual??''}" placeholder="留空自动算" oninput="updateFormula()"></div><div class="field"><label>利润 CNY（可覆盖）</label><input type="number" step="0.01" id="e_profit" value="${e?.profitManual??''}" placeholder="留空自动算" oninput="updateFormula()"></div></div><div id="formula" class="formula"></div><div class="field"><label>备注</label><textarea id="e_note" rows="3">${e?esc(e.note||''):''}</textarea></div></div><div class="modal-actions"><button class="btn secondary" onclick="closeModal()">取消</button><button class="btn primary" onclick="saveEntryForm()">保存订单</button></div></div>`);
    initOrderForm(e,'RATE');
  };

  window.openPointsEntryForm = function(id){
    const p=getProject();if(!p)return;editingEntryId=id||null;const e=id?migrateEntry(p.entries.find(x=>x.id===id)):null;
    showModal(`<div class="sheet"><div class="sheet-title">${e?'编辑':'新增'}订单 · 按费率结算</div><button class="switchlink" onclick="openSettlementChoice()">‹ 重新选择结算方式</button><div class="form" style="margin-top:12px">${commonTop(e)}<div class="field"><label>收费点数 %</label><input type="number" step="0.01" min="0" max="100" id="e_points" value="${e?.pointsRate??''}" placeholder="例如 3 = 收 3 个点" oninput="syncAmounts()"></div><div class="two"><div class="field"><label id="points_cny_label">客户支付人民币 CNY</label><input type="number" step="0.01" id="e_cny" value="${e?.cnyAmount??''}" oninput="syncAmounts()"></div><div class="field"><label id="points_usdt_label">应付客户 USDT</label><input type="number" step="0.0001" id="e_usdt" class="readonly" value="${e?.usdtAmount??''}" disabled oninput="syncAmounts()"></div></div><div id="points_breakdown" class="formula calcbox"></div><div class="two"><div class="field"><label>实际成本汇率</label><input type="number" step="0.0001" id="e_cost" value="${e?.actualCostRate??''}" placeholder="留空=实时汇率" oninput="updateFormula()"></div><div class="field"><label>其他手续费/成本 CNY</label><input type="number" step="0.01" id="e_fee" value="${e?.feeManual??''}" placeholder="例如链上手续费" oninput="updateFormula()"></div></div><div class="field"><label>利润 CNY（可覆盖）</label><input type="number" step="0.01" id="e_profit" value="${e?.profitManual??''}" placeholder="留空自动算" oninput="updateFormula()"></div><div id="formula" class="formula"></div><div class="field"><label>备注</label><textarea id="e_note" rows="3">${e?esc(e.note||''):''}</textarea></div></div><div class="modal-actions"><button class="btn secondary" onclick="closeModal()">取消</button><button class="btn primary" onclick="saveEntryForm()">保存订单</button></div></div>`);
    initOrderForm(e,'POINTS');refreshPointsDirectionUI();
  };

  window.setDirection = function(d){formDirection=d;lastInput=d==='CNY_TO_USDT'?'cny':'usdt';refreshDirUI();if(formSettlement==='POINTS')refreshPointsDirectionUI();syncAmounts()};
  window.refreshPointsDirectionUI = function(){
    const cny=document.getElementById('e_cny'),usdt=document.getElementById('e_usdt'),cl=document.getElementById('points_cny_label'),ul=document.getElementById('points_usdt_label');if(!cny||!usdt)return;
    if(formDirection==='CNY_TO_USDT'){
      cl.textContent='客户支付人民币 CNY';ul.textContent='应付客户 USDT';cny.disabled=false;cny.classList.remove('readonly');usdt.disabled=true;usdt.classList.add('readonly');
    }else{
      cl.textContent='实付客户人民币 CNY';ul.textContent='客户支付 USDT';cny.disabled=true;cny.classList.add('readonly');usdt.disabled=false;usdt.classList.remove('readonly');
    }
  };

  formEntry = function(){return{settlementMode:formSettlement,direction:formDirection,baseRate:document.getElementById('e_base')?.value||'',adjustType:formAdjust,adjustValue:formSettlement==='RATE'?(document.getElementById('e_adjust')?.value||''):'',pointsRate:formSettlement==='POINTS'?(document.getElementById('e_points')?.value||''):'',cnyAmount:document.getElementById('e_cny')?.value||'',usdtAmount:document.getElementById('e_usdt')?.value||'',actualCostRate:document.getElementById('e_cost')?.value||'',feeRate:formSettlement==='RATE'?(document.getElementById('e_feeRate')?.value||''):'',feeManual:document.getElementById('e_fee')?.value||'',profitManual:document.getElementById('e_profit')?.value||'',rateSource:liveRateSource,rateFetchedAt:liveRateTime}};

  syncAmounts = function(){formSettlement==='POINTS'?syncPointsAmounts():syncRateAmounts()};
  window.syncRateAmounts = function(){
    const base=n(document.getElementById('e_base')?.value),adj=n(document.getElementById('e_adjust')?.value),rate=Math.max(0,formAdjust==='DOWN'?base-adj:base+adj);const deal=document.getElementById('e_deal');if(deal)deal.value=rate?rate.toFixed(4):'';const cny=document.getElementById('e_cny'),usdt=document.getElementById('e_usdt');if(rate&&cny&&usdt){if(lastInput==='cny'&&cny.value!=='')usdt.value=(n(cny.value)/rate).toFixed(4);else if(lastInput==='usdt'&&usdt.value!=='')cny.value=(n(usdt.value)*rate).toFixed(2)}updateFormula();
  };
  window.syncPointsAmounts = function(){
    const base=n(document.getElementById('e_base')?.value),points=Math.max(0,n(document.getElementById('e_points')?.value)),cny=document.getElementById('e_cny'),usdt=document.getElementById('e_usdt');if(base&&cny&&usdt){if(formDirection==='CNY_TO_USDT'){const gross=n(cny.value),net=gross*Math.max(0,1-points/100);usdt.value=gross?(net/base).toFixed(4):''}else{const u=n(usdt.value),gross=u*base,net=gross*Math.max(0,1-points/100);cny.value=u?net.toFixed(2):''}}updateFormula();
  };

  updateFormula = function(){
    const box=document.getElementById('formula');if(!box)return;const e=formEntry(),c=calcEntry(e);
    if(formSettlement==='POINTS'){
      const bd=document.getElementById('points_breakdown');
      if(bd){
        bd.innerHTML=formDirection==='CNY_TO_USDT'
          ? `<div class="calcrow"><span>客户支付人民币</span><strong>¥${money(c.grossCny)}</strong></div><div class="calcrow"><span>收费 ${money(c.pointsRate,2)} 点</span><strong>−¥${money(c.serviceFee)}</strong></div><div class="calcrow"><span>实际参与兑换</span><strong>¥${money(c.netCny)}</strong></div><div class="calcrow"><span>按实时汇率 ${c.base?c.base.toFixed(4):'-'} 兑换</span><strong>₮${money(c.usdt,4)}</strong></div>`
          : `<div class="calcrow"><span>客户支付 USDT</span><strong>₮${money(c.usdt,4)}</strong></div><div class="calcrow"><span>按实时汇率折算</span><strong>¥${money(c.grossCny)}</strong></div><div class="calcrow"><span>收费 ${money(c.pointsRate,2)} 点</span><strong>−¥${money(c.serviceFee)}</strong></div><div class="calcrow"><span>实付客户人民币</span><strong>¥${money(c.cny)}</strong></div>`;
      }
      box.innerHTML=`费率收入：<b>¥${money(c.serviceFee)}</b><br>预计净利润：<b class="profit ${c.profit>=0?'pos':'neg'}">${c.profit>=0?'+':''}¥${money(c.profit)}</b><br><span style="font-size:10px">费率收入按点数自动计算；净利润还会结合实际成本汇率和其他手续费/成本</span>`;
      return;
    }
    const sign=formAdjust==='DOWN'?'−':'＋',rule=formDirection==='CNY_TO_USDT'?'人民币 ÷ 成交汇率 = USDT；利润 = 收到人民币 − USDT×实际成本 − 其他成本':'USDT × 成交汇率 = 人民币；利润 = USDT×实际成本 − 付出人民币 − 其他成本';
    box.innerHTML=`成交汇率：<b>${c.rate?c.rate.toFixed(4):'-'}</b>（${c.base?c.base.toFixed(4):'-'} ${sign} ${n(e.adjustValue).toFixed(4)}）<br>折算结果：<b>¥${money(c.cny)}</b> ⇄ <b>₮${money(c.usdt,4)}</b><br>预计净利润：<b class="profit ${c.profit>=0?'pos':'neg'}">${c.profit>=0?'+':''}¥${money(c.profit)}</b><br><span style="font-size:10px">${rule}</span>`;
  };

  window.markRateManual = function(){liveRateSource='手工输入';liveRateTime='';renderRateMeta()};
  window.renderRateMeta = function(){const m=document.getElementById('e_rate_meta');if(!m)return;m.textContent=liveRateSource?`来源：${liveRateSource}${liveRateTime?' · '+formatRateTime(liveRateTime):''} · 可手工修改`:'可手工输入，或点击“自动获取”获取 USDT/CNY 市场参考价'};
  window.requestRealtimeRate = function(){const b=document.getElementById('fetchRateBtn');if(b){b.disabled=true;b.textContent='获取中…'}if(window.AndroidBridge&&AndroidBridge.fetchUsdtCnyRate){AndroidBridge.fetchUsdtCnyRate();return}fetch('https://api.coingecko.com/api/v3/simple/price?ids=tether&vs_currencies=cny').then(r=>r.json()).then(j=>onRealtimeRate(j.tether.cny,'CoinGecko',Date.now())).catch(()=>onRealtimeRateError('网络请求失败'))};
  window.onRealtimeRate = function(rate,source,time){const v=n(rate),inp=document.getElementById('e_base'),b=document.getElementById('fetchRateBtn');if(b){b.disabled=false;b.textContent='自动获取'}if(!v||!inp)return onRealtimeRateError('未获取到有效汇率');inp.value=v.toFixed(4);liveRateSource=source||'市场参考价';liveRateTime=String(time||Date.now());renderRateMeta();syncAmounts();toast('实时汇率已更新')};
  window.onRealtimeRateError = function(msg){const b=document.getElementById('fetchRateBtn');if(b){b.disabled=false;b.textContent='自动获取'}toast('获取失败：'+(msg||'请检查网络，可手工输入'))};

  saveEntryForm = function(){
    const p=getProject();if(!p)return;const date=document.getElementById('e_date').value,base=document.getElementById('e_base').value,cny=document.getElementById('e_cny').value,usdt=document.getElementById('e_usdt').value;
    if(!date||!base||!cny||!usdt)return toast('请填写日期、实时汇率和兑换金额');
    if(formSettlement==='POINTS'&&document.getElementById('e_points').value==='')return toast('请填写收费点数');
    const data={date,status:document.getElementById('e_status').value,...formEntry(),note:document.getElementById('e_note').value.trim()};
    if(editingEntryId)Object.assign(p.entries.find(x=>x.id===editingEntryId),data);else p.entries.push({id:uid(),...data,createdAt:new Date().toISOString()});
    save();closeModal();render();toast('订单已保存');
  };

  csvForProjects = function(projects){
    const rows=[['项目','日期','结算方式','兑换方向','客户','负责人','实时汇率','汇率来源','调整方式/费率','浮动值/点数','成交汇率','人民币金额','USDT数量','费率收入CNY','实际成本汇率','其他手续费CNY','净利润CNY','状态','备注']];
    projects.forEach(p=>(p.entries||[]).forEach(e=>{const c=calcEntry(e),pm=c.settlementMode==='POINTS';rows.push([p.name,e.date,pm?'按费率':'按汇率',dirText(e.direction),p.client||'',p.manager||'',c.base,e.rateSource||'',pm?'收费点数':(e.adjustType==='DOWN'?'下浮':'上浮'),pm?c.pointsRate:n(e.adjustValue),c.rate,c.cny,c.usdt,c.serviceFee,c.cost,c.fee,c.profit,e.status||'',e.note||''])}));
    return '\ufeff'+rows.map(r=>r.map(v=>'"'+String(v??'').replace(/"/g,'""')+'"').join(',')).join('\n');
  };

  function ensureMenuBackdrop(){let b=document.getElementById('menuBackdrop');if(!b){b=document.createElement('div');b.id='menuBackdrop';b.className='menu-backdrop';b.onclick=hideMenu;document.body.appendChild(b)}return b}
  toggleMenu = function(){const m=document.getElementById('menu'),b=ensureMenuBackdrop();if(!m)return;const show=!m.classList.contains('show');m.classList.toggle('show',show);b.classList.toggle('show',show)};
  hideMenu = function(){document.getElementById('menu')?.classList.remove('show');document.getElementById('menuBackdrop')?.classList.remove('show')};

  state.projects.forEach(p=>(p.entries||[]).forEach(migrateEntry));
  save();
  render();
})();
