(() => {
  const style=document.createElement('style');
  style.textContent=`
.project-date{font-size:11px;color:var(--muted);margin-top:6px;display:flex;align-items:center;gap:5px}.project-actions{display:flex;justify-content:flex-end;gap:4px;margin-top:10px;padding-top:8px;border-top:1px solid #eef2f6}.project-actions .textbtn{padding:6px 8px}.copyhint{background:#f6f8fb;border-radius:11px;padding:9px 11px;font-size:11px;color:#627086;line-height:1.55}`;
  document.head.appendChild(style);

  let copySourceId=null;
  const oldRenderHomeV22=renderHome;
  renderHome=function(){
    oldRenderHomeV22();
    const sub=document.querySelector('.subtitle');
    if(sub)sub.textContent='按项目独立记账 · V2.3 日期/复制账本';
  };

  window.projectDisplayDate=function(p){
    const entries=p.entries||[];
    const dates=entries.map(e=>e.date).filter(Boolean).sort();
    if(dates.length)return{label:'最近订单',value:dates[dates.length-1]};
    const created=p.createdAt?String(p.createdAt).slice(0,10):'';
    return{label:'创建日期',value:created||'-'};
  };

  projectCard=function(p){
    const s=projectStats(p),d=projectDisplayDate(p);
    return `<div class="project" onclick="openProject('${p.id}')"><div class="project-top"><div><div class="project-name">${esc(p.name)}</div><div class="project-meta">${esc(p.client||'未填写客户')}${p.manager?' · '+esc(p.manager):''}</div><div class="project-date">${d.label}：${esc(d.value)}</div></div><span class="pill">${(p.entries||[]).length} 笔</span></div><div class="project-grid"><div class="metric"><div class="k">人民币流水</div><div class="v">¥${money(s.cny)}</div></div><div class="metric"><div class="k">USDT流水</div><div class="v">₮${money(s.usdt,2)}</div></div><div class="metric"><div class="k">净利润</div><div class="v profit ${s.profit>=0?'pos':'neg'}">${s.profit>=0?'+':''}¥${money(s.profit)}</div></div></div><div class="project-actions" onclick="event.stopPropagation()"><button class="textbtn" onclick="openProject('${p.id}')">打开账本</button><button class="textbtn" onclick="openCopyProject('${p.id}')">复制账本</button><button class="textbtn" onclick="openProjectModal('${p.id}')">设置</button></div></div>`;
  };

  window.openCopyProject=function(id){
    const p=state.projects.find(x=>x.id===id);if(!p)return;
    copySourceId=id;
    showModal(`<div class="sheet"><div class="sheet-title">复制账本</div><div class="form"><div class="copyhint">将复制这个项目的全部订单、汇率、收费点数、成本点数和备注。保存后会生成一个完全独立的新项目，之后修改新项目不会影响原账本。</div><div class="field"><label>新项目名称 *</label><input id="cp_name" value="${esc((p.name||'项目')+'（副本）')}"></div><div class="two"><div class="field"><label>客户/对方</label><input id="cp_client" value="${esc(p.client||'')}"></div><div class="field"><label>负责人</label><input id="cp_manager" value="${esc(p.manager||'')}"></div></div><div class="field"><label>项目备注</label><textarea id="cp_note" rows="3">${esc(p.note||'')}</textarea></div></div><div class="modal-actions"><button class="btn secondary" onclick="closeModal()">取消</button><button class="btn primary" onclick="saveCopiedProject()">保存为新项目</button></div></div>`);
  };

  window.saveCopiedProject=function(){
    const src=state.projects.find(x=>x.id===copySourceId);if(!src)return toast('原项目不存在');
    const name=document.getElementById('cp_name')?.value.trim();if(!name)return toast('请填写新项目名称');
    const clone=JSON.parse(JSON.stringify(src));
    clone.id=uid();
    clone.name=name;
    clone.client=document.getElementById('cp_client')?.value.trim()||'';
    clone.manager=document.getElementById('cp_manager')?.value.trim()||'';
    clone.note=document.getElementById('cp_note')?.value.trim()||'';
    clone.createdAt=new Date().toISOString();
    clone.entries=(clone.entries||[]).map(e=>({...e,id:uid(),createdAt:e.createdAt||new Date().toISOString()}));
    state.projects.unshift(clone);
    save();
    copySourceId=null;
    closeModal();
    render();
    toast('账本已复制为独立新项目');
  };

  render();
})();
