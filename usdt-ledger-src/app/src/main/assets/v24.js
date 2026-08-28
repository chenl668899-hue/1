(() => {
  const style=document.createElement('style');
  style.textContent=`
.lanbox{background:#f3f8f6;border-radius:14px;padding:13px;line-height:1.65}.lanstatus{display:flex;align-items:center;justify-content:space-between;gap:10px}.lanstatus b{font-size:15px}.lan-dot{display:inline-block;width:8px;height:8px;border-radius:50%;background:#a9b3c1;margin-right:6px}.lan-dot.on{background:var(--brand)}.lanvalue{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:14px;font-weight:800;word-break:break-all}.lanhelp{font-size:10px;color:var(--muted);line-height:1.65}.lan-actions{display:grid;grid-template-columns:1fr 1fr;gap:9px;margin-top:12px}`;
  document.head.appendChild(style);

  /* V2.5: realtime-rate robustness. Native + browser sources race; first valid result wins. */
  const oldRateSuccess=window.onRealtimeRate;
  const oldRateError=window.onRealtimeRateError;
  let rateActive=false,rateTimer=null,rateSeq=0,nativeRateError='';

  function setRateButtonLoading(on){
    const b=document.getElementById('fetchRateBtn');
    if(b){b.disabled=!!on;b.textContent=on?'获取中…':'自动获取'}
  }
  function fetchJsonTimed(url,ms=8000){
    const ctrl=new AbortController();
    const t=setTimeout(()=>ctrl.abort(),ms);
    return fetch(url,{cache:'no-store',signal:ctrl.signal,headers:{'Accept':'application/json'}})
      .then(r=>{if(!r.ok)throw new Error('HTTP '+r.status);return r.json()})
      .finally(()=>clearTimeout(t));
  }
  function validRate(v){const x=n(v);return Number.isFinite(x)&&x>4&&x<10?x:0}
  function firstSuccess(promises){
    return new Promise((resolve,reject)=>{
      let left=promises.length,lastErr=null;
      promises.forEach(p=>Promise.resolve(p).then(resolve).catch(e=>{lastErr=e;if(--left===0)reject(lastErr||new Error('all failed'))}))
    });
  }
  function browserRateRace(seq){
    const providers=[
      fetchJsonTimed('https://api.coingecko.com/api/v3/simple/price?ids=tether&vs_currencies=cny').then(j=>{const r=validRate(j?.tether?.cny);if(!r)throw new Error('CoinGecko无有效价格');return{rate:r,source:'CoinGecko'}}),
      fetchJsonTimed('https://api.coinbase.com/v2/exchange-rates?currency=USDT').then(j=>{const r=validRate(j?.data?.rates?.CNY);if(!r)throw new Error('Coinbase无有效价格');return{rate:r,source:'Coinbase'}}),
      fetchJsonTimed('https://open.er-api.com/v6/latest/USD').then(j=>{const r=validRate(j?.rates?.CNY);if(!r)throw new Error('ER-API无有效价格');return{rate:r,source:'ER-API USD/CNY参考（USDT≈USD）'}}),
      fetchJsonTimed('https://api.frankfurter.app/latest?from=USD&to=CNY').then(j=>{const r=validRate(j?.rates?.CNY);if(!r)throw new Error('Frankfurter无有效价格');return{rate:r,source:'Frankfurter USD/CNY参考（USDT≈USD）'}})
    ];
    firstSuccess(providers).then(res=>{
      if(!rateActive||seq!==rateSeq)return;
      rateActive=false;clearTimeout(rateTimer);rateTimer=null;
      oldRateSuccess(res.rate,res.source,Date.now());
    }).catch(()=>{
      if(!rateActive||seq!==rateSeq)return;
      /* Give the native bridge a little longer before declaring failure. */
      clearTimeout(rateTimer);
      rateTimer=setTimeout(()=>{
        if(!rateActive||seq!==rateSeq)return;
        rateActive=false;rateTimer=null;setRateButtonLoading(false);
        oldRateError(nativeRateError||'多个行情源均获取失败，请检查网络或手工输入');
      },2500);
    });
  }
  window.requestRealtimeRate=function(){
    const seq=++rateSeq;rateActive=true;nativeRateError='';setRateButtonLoading(true);
    clearTimeout(rateTimer);
    /* Browser fallbacks start immediately instead of waiting for a blocked native endpoint. */
    browserRateRace(seq);
    try{
      if(window.AndroidBridge&&AndroidBridge.fetchUsdtCnyRate)AndroidBridge.fetchUsdtCnyRate();
    }catch(e){nativeRateError=e?.message||'安卓行情请求失败'}
    rateTimer=setTimeout(()=>{
      if(!rateActive||seq!==rateSeq)return;
      rateActive=false;rateTimer=null;setRateButtonLoading(false);
      oldRateError(nativeRateError||'获取超时，请检查网络或手工输入');
    },12000);
  };
  window.onRealtimeRate=function(rate,source,time){
    if(!rateActive)return;
    const r=validRate(rate);if(!r){window.onRealtimeRateError('行情源返回无效汇率');return}
    rateActive=false;clearTimeout(rateTimer);rateTimer=null;
    oldRateSuccess(r,source,time);
  };
  window.onRealtimeRateError=function(msg){
    nativeRateError=msg||'安卓行情请求失败';
    /* Do not end the request here; browser fallback may still succeed. */
  };

  const oldSaveV24=save;
  save=function(){
    oldSaveV24();
    try{if(window.AndroidBridge&&AndroidBridge.syncState)AndroidBridge.syncState(JSON.stringify(state));}catch(e){}
  };

  const oldRenderHomeV23=renderHome;
  renderHome=function(){
    oldRenderHomeV23();
    const sub=document.querySelector('.subtitle');
    if(sub)sub.textContent='按项目独立记账 · V2.5 自动汇率增强/局域网互传';
    const menu=document.getElementById('menu');
    if(menu&&!document.getElementById('lanMenuBtn')){
      const b=document.createElement('button');b.id='lanMenuBtn';b.textContent='局域网互传';b.onclick=()=>{hideMenu();openLanTransferPanel()};menu.appendChild(b);
    }
  };

  window.readLanInfo=function(){
    try{
      if(!(window.AndroidBridge&&AndroidBridge.getLanInfo))return{running:false,error:'当前不是安卓安装版'};
      return JSON.parse(AndroidBridge.getLanInfo());
    }catch(e){return{running:false,error:'无法读取局域网状态'}}
  };

  window.openLanTransferPanel=function(){
    showModal(`<div class="sheet"><div class="sheet-title">Mac ↔ 手机 数据互传</div><div class="form"><div class="lanbox"><div class="lanstatus"><b><span id="lanDot" class="lan-dot"></span><span id="lanStateText">读取中…</span></b><span id="lanProjects" class="small"></span></div></div><div class="field"><label>Mac 连接地址</label><div id="lanAddress" class="lanbox lanvalue">-</div></div><div class="field"><label>6 位配对码</label><div id="lanCode" class="lanbox lanvalue">-</div></div><div class="lanhelp">使用方法：手机和 Mac 连接同一个 Wi-Fi → 在手机开启传输 → Mac 网页版点“数据 → 手机互传” → 输入这里显示的地址和配对码。只有传输开启期间 Mac 才能连接。</div><div class="lan-actions"><button id="lanStartBtn" class="btn primary" onclick="startLanTransfer()">开启传输</button><button id="lanStopBtn" class="btn secondary" onclick="stopLanTransfer()">关闭传输</button></div></div><div class="modal-actions" style="grid-template-columns:1fr"><button class="btn secondary" onclick="closeModal()">完成</button></div></div>`);
    refreshLanPanel();
  };

  window.refreshLanPanel=function(){
    const info=readLanInfo(),dot=document.getElementById('lanDot'),txt=document.getElementById('lanStateText'),addr=document.getElementById('lanAddress'),code=document.getElementById('lanCode'),projects=document.getElementById('lanProjects');
    if(!txt)return;
    const on=!!info.running;
    dot?.classList.toggle('on',on);
    txt.textContent=on?'传输已开启':'传输已关闭';
    if(addr)addr.textContent=on?`http://${info.ip}:${info.port}`:'开启后显示';
    if(code)code.textContent=on?(info.code||'-'):'开启后显示';
    if(projects)projects.textContent=`当前 ${state.projects.length} 个项目`;
    const sb=document.getElementById('lanStartBtn'),tb=document.getElementById('lanStopBtn');if(sb)sb.disabled=on;if(tb)tb.disabled=!on;
  };

  window.startLanTransfer=function(){
    try{
      if(!(window.AndroidBridge&&AndroidBridge.startLanServer))return toast('此功能仅支持安卓安装版');
      AndroidBridge.syncState(JSON.stringify(state));
      AndroidBridge.startLanServer();
      setTimeout(()=>{refreshLanPanel();const i=readLanInfo();toast(i.running?'局域网传输已开启':'开启失败，请检查网络')},350);
    }catch(e){toast('开启失败')}
  };

  window.stopLanTransfer=function(){
    try{if(window.AndroidBridge&&AndroidBridge.stopLanServer)AndroidBridge.stopLanServer();}catch(e){}
    setTimeout(()=>{refreshLanPanel();toast('局域网传输已关闭')},120);
  };

  window.onLanDataReceived=function(jsonText,mode){
    try{
      const incoming=JSON.parse(jsonText);if(!incoming||!Array.isArray(incoming.projects))throw new Error('格式错误');
      incoming.projects.forEach(p=>(p.entries||[]).forEach(migrateEntry));
      if(mode==='replace'){
        state=incoming;
        if(currentProjectId&&!state.projects.some(p=>p.id===currentProjectId))currentProjectId=null;
      }else{
        const projectIds=new Set(state.projects.map(p=>p.id));
        incoming.projects.forEach(src=>{
          const p=JSON.parse(JSON.stringify(src));
          if(!p.id||projectIds.has(p.id))p.id=uid();
          projectIds.add(p.id);
          p.entries=(p.entries||[]).map(e=>({...e,id:e.id||uid()}));
          state.projects.push(p);
        });
      }
      save();render();
      toast(mode==='replace'?'已接收 Mac 数据并覆盖':'已接收 Mac 数据并合并');
    }catch(e){toast('接收数据失败：格式不正确')}
  };

  state.projects.forEach(p=>(p.entries||[]).forEach(migrateEntry));
  save();
  render();
})();
