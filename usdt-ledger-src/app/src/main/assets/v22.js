(() => {
  const oldMigrateEntry = migrateEntry;
  const oldCalcEntry = calcEntry;
  const oldEntryCard = entryCard;
  const oldOpenPointsEntryForm = window.openPointsEntryForm;
  const oldFormEntry = formEntry;
  const oldUpdateFormula = updateFormula;
  const oldRenderHomeV21 = renderHome;

  migrateEntry = function(e){
    oldMigrateEntry(e);
    if(e.costPointsRate==null)e.costPointsRate='';
    return e;
  };

  calcEntry = function(e){
    migrateEntry(e);
    const c = oldCalcEntry(e);
    const costPointsRate = Math.max(0,n(e.costPointsRate));
    const pointCost = c.settlementMode==='POINTS' ? Math.max(0,c.grossCny)*costPointsRate/100 : 0;
    const pointSpread = c.settlementMode==='POINTS' ? c.serviceFee-pointCost : 0;
    let profit = c.profit;
    if(c.settlementMode==='POINTS' && !(e.profitManual!=='' && e.profitManual!=null)) profit = c.profit-pointCost;
    return {...c,costPointsRate,pointCost,pointSpread,profit};
  };

  renderHome = function(){
    oldRenderHomeV21();
    const sub=document.querySelector('.subtitle');
    if(sub)sub.textContent='按项目独立记账 · V2.2 汇率/费率双模式';
  };

  entryCard = function(e){
    const c=calcEntry(e),points=c.settlementMode==='POINTS';
    return `<div class="entry"><div class="entry-top"><div class="entry-date">${esc(e.date||'未填日期')}</div><div class="righttags"><span class="modebadge">${settlementText(e)}</span><span class="dirbadge">${dirText(e.direction)}</span><span class="status ${e.status==='已结清'?'done':'open'}">${esc(e.status||'未结清')}</span></div></div><div class="entry-main"><div><div class="entry-cny">¥ ${money(c.cny)}</div><div class="entry-usdt">₮ ${money(c.usdt,4)} USDT</div></div><div class="entry-profit"><div class="p profit ${c.profit>=0?'pos':'neg'}">${c.profit>=0?'+':''}¥ ${money(c.profit)}</div><div class="r">净利润</div></div></div><div class="entry-info">${points?`<div class="mini"><div class="k">实时汇率</div><div class="v">${c.base?c.base.toFixed(4):'-'}</div></div><div class="mini"><div class="k">收费点数</div><div class="v">${money(c.pointsRate,2)} 点</div></div><div class="mini"><div class="k">成本点数</div><div class="v">${money(c.costPointsRate,2)} 点</div></div><div class="mini"><div class="k">费率收入</div><div class="v">¥${money(c.serviceFee)}</div></div><div class="mini"><div class="k">点数成本</div><div class="v">¥${money(c.pointCost)}</div></div><div class="mini"><div class="k">点差利润</div><div class="v profit ${c.pointSpread>=0?'pos':'neg'}">${c.pointSpread>=0?'+':''}¥${money(c.pointSpread)}</div></div>`:`<div class="mini"><div class="k">实时基准汇率</div><div class="v">${c.base?c.base.toFixed(4):'-'}</div></div><div class="mini"><div class="k">成交汇率</div><div class="v">${c.rate?c.rate.toFixed(4):'-'}</div></div><div class="mini"><div class="k">实际成本汇率</div><div class="v">${c.cost?c.cost.toFixed(4):'-'}</div></div>`}</div>${e.rateSource?`<div class="note">汇率来源：${esc(e.rateSource)}${e.rateFetchedAt?' · '+esc(formatRateTime(e.rateFetchedAt)):''}</div>`:''}${e.note?`<div class="note">备注：${esc(e.note)}</div>`:''}<div class="entry-actions"><button class="textbtn" onclick="openEntryModal('${e.id}')">编辑</button><button class="textbtn danger" onclick="deleteEntry('${e.id}')">删除</button></div></div>`;
  };

  window.openPointsEntryForm = function(id){
    oldOpenPointsEntryForm(id);
    const p=getProject();
    const e=id&&p?migrateEntry(p.entries.find(x=>x.id===id)):null;
    const points=document.getElementById('e_points');
    if(!points)return;
    const chargeField=points.closest('.field');
    if(chargeField){
      const label=chargeField.querySelector('label');
      if(label)label.textContent='收费点数';
      const row=document.createElement('div');
      row.className='two';
      chargeField.parentNode.insertBefore(row,chargeField);
      row.appendChild(chargeField);
      const costField=document.createElement('div');
      costField.className='field';
      costField.innerHTML=`<label>成本点数</label><input type="number" step="0.01" min="0" max="100" id="e_costPoints" value="${e?.costPointsRate??0}" placeholder="例如 2 = 成本 2 个点" oninput="updateFormula()">`;
      row.appendChild(costField);
      const help=document.createElement('div');
      help.className='rate-meta';
      help.style.gridColumn='1 / -1';
      help.textContent='1 点 = 1%｜点差利润 = 收费点数 − 成本点数';
      row.appendChild(help);
    }
    updateFormula();
  };

  formEntry = function(){
    const e=oldFormEntry();
    if(document.getElementById('e_points'))e.costPointsRate=document.getElementById('e_costPoints')?.value||'0';
    else e.costPointsRate='';
    return e;
  };

  updateFormula = function(){
    oldUpdateFormula();
    const points=document.getElementById('e_points');
    if(!points)return;
    const e=formEntry(),c=calcEntry(e),bd=document.getElementById('points_breakdown'),box=document.getElementById('formula');
    if(bd){
      if(formDirection==='CNY_TO_USDT'){
        bd.innerHTML=`<div class="calcrow"><span>客户支付人民币</span><strong>¥${money(c.grossCny)}</strong></div><div class="calcrow"><span>收费 ${money(c.pointsRate,2)} 点</span><strong>−¥${money(c.serviceFee)}</strong></div><div class="calcrow"><span>实际参与兑换</span><strong>¥${money(c.netCny)}</strong></div><div class="calcrow"><span>按实时汇率 ${c.base?c.base.toFixed(4):'-'} 兑换</span><strong>₮${money(c.usdt,4)}</strong></div>`;
      }else{
        bd.innerHTML=`<div class="calcrow"><span>客户支付 USDT</span><strong>₮${money(c.usdt,4)}</strong></div><div class="calcrow"><span>按实时汇率折算</span><strong>¥${money(c.grossCny)}</strong></div><div class="calcrow"><span>收费 ${money(c.pointsRate,2)} 点</span><strong>−¥${money(c.serviceFee)}</strong></div><div class="calcrow"><span>实付客户人民币</span><strong>¥${money(c.cny)}</strong></div>`;
      }
    }
    if(box)box.innerHTML=`费率收入：<b>¥${money(c.serviceFee)}</b><br>成本 ${money(c.costPointsRate,2)} 点：<b>−¥${money(c.pointCost)}</b><br>点差利润：<b class="profit ${c.pointSpread>=0?'pos':'neg'}">${c.pointSpread>=0?'+':''}¥${money(c.pointSpread)}</b><br>预计净利润：<b class="profit ${c.profit>=0?'pos':'neg'}">${c.profit>=0?'+':''}¥${money(c.profit)}</b><br><span style="font-size:10px">净利润 = 费率收入 − 点数成本 ± 实际成本汇率差额 − 其他手续费/成本</span>`;
  };

  csvForProjects = function(projects){
    const rows=[['项目','日期','结算方式','兑换方向','客户','负责人','实时汇率','汇率来源','调整方式/费率','浮动值/收费点数','成本点数','成交汇率','人民币金额','USDT数量','费率收入CNY','点数成本CNY','点差利润CNY','实际成本汇率','其他手续费CNY','净利润CNY','状态','备注']];
    projects.forEach(p=>(p.entries||[]).forEach(e=>{const c=calcEntry(e),pm=c.settlementMode==='POINTS';rows.push([p.name,e.date,pm?'按费率':'按汇率',dirText(e.direction),p.client||'',p.manager||'',c.base,e.rateSource||'',pm?'收费点数':(e.adjustType==='DOWN'?'下浮':'上浮'),pm?c.pointsRate:n(e.adjustValue),pm?c.costPointsRate:'',c.rate,c.cny,c.usdt,c.serviceFee,c.pointCost,c.pointSpread,c.cost,c.fee,c.profit,e.status||'',e.note||''])}));
    return '\ufeff'+rows.map(r=>r.map(v=>'"'+String(v??'').replace(/"/g,'""')+'"').join(',')).join('\n');
  };

  state.projects.forEach(p=>(p.entries||[]).forEach(migrateEntry));
  save();
  render();
})();
