/* ================= Экзамен в шторке + «Поделиться» → Словопад ================= */

/* ---------- экзамен в шторке ---------- */
const QUIZ_EVERY = () => (S.quizEvery == null ? 120 : S.quizEvery);
function quizSync() {
  try {
    if (!(window.Android && Android.quizSetup)) return;
    const seen = new Set(), pool = [];
    [...ringList(), ...dueList(), ...learnedList().slice(-60)].forEach(w => { if (!w.ph && !seen.has(w.en) && pool.length < 120) { seen.add(w.en); pool.push([w.en, w.ru]); } });
    Android.quizSetup(JSON.stringify({ every: QUIZ_EVERY(), from: 9, to: 22, pool }));
  } catch (e) {}
}
function quizApply() {
  try {
    if (!(window.Android && Android.quizTakeLog)) return;
    const log = JSON.parse(Android.quizTakeLog() || '[]'); if (!log.length) return;
    let ok = 0, bad = 0;
    log.forEach(x => {
      const w = BY[x.en]; if (!w) return;
      const r = rec(w.en), M = mx(w);
      S.answered++; today().a++;
      if (x.ok) {
        ok++; S.correct++; today().c++;
        if (r.s === 1) { r.m = Math.min(M - 1, r.m + 80); }          // прогресс, но выучить — только в игре
        else if (r.s === 2 && r.n && r.n <= S.day) { const k = INTERVALS.indexOf(r.iv); r.iv = INTERVALS[Math.min(INTERVALS.length - 1, k + 1)]; r.n = dAdd(r.iv); }
      } else {
        bad++; r.e = (r.e || 0) + 1;
        if (r.s === 2) { r.s = 1; r.m = Math.round(M / 2); r.iv = 0; r.n = ''; }
        else if (r.s === 1) r.m = Math.max(0, r.m - 75);
      }
    });
    if (ok) { addCoins(ok); foxFedNative(); }
    save(); renderHud();
    toast(`Из шторки: ${ok} верно${bad ? `, ${bad} мимо` : ''}${ok ? ` · +${ok} 🪙` : ''}`, ok ? 'ok' : '');
  } catch (e) {}
}

/* ---------- «Поделиться» → Словопад ---------- */
const IRR2 = { is: 'be', am: 'be', are: 'be', was: 'be', were: 'be', been: 'be', has: 'have', had: 'have', did: 'do', does: 'do', done: 'do', went: 'go', gone: 'go', got: 'get', made: 'make', knew: 'know', known: 'know', thought: 'think', took: 'take', taken: 'take', saw: 'see', seen: 'see', came: 'come', found: 'find', gave: 'give', given: 'give', told: 'tell', felt: 'feel', left: 'leave', paid: 'pay', met: 'meet', sold: 'sell', bought: 'buy', built: 'build', brought: 'bring', kept: 'keep', held: 'hold', wrote: 'write', written: 'write', sent: 'send', spent: 'spend', said: 'say', ran: 'run', lost: 'lose', won: 'win', understood: 'understand', began: 'begin', stood: 'stand', sat: 'sit', led: 'lead', meant: 'mean', broke: 'break', broken: 'break', chose: 'choose', drove: 'drive', ate: 'eat', fell: 'fall', forgot: 'forget', grew: 'grow', heard: 'hear', slept: 'sleep', spoke: 'speak', taught: 'teach', threw: 'throw', woke: 'wake', wore: 'wear', children: 'child', men: 'man', women: 'woman', feet: 'foot', teeth: 'tooth', better: 'good', best: 'good', worse: 'bad', worst: 'bad', became: 'become', flew: 'fly', drank: 'drink', sang: 'sing', swam: 'swim', rode: 'ride', rose: 'rise', caught: 'catch', fought: 'fight', sought: 'seek', laid: 'lay', lay: 'lie', hid: 'hide', shook: 'shake', stole: 'steal', bit: 'bite', froze: 'freeze', hung: 'hang', lent: 'lend', dug: 'dig', fed: 'feed', fled: 'flee', struck: 'strike', swore: 'swear', tore: 'tear', wept: 'weep', wound: 'wind' };
const STOP = new Set('a an the and or but if of in on at to for from by with as is it its this that these those i you he she we they me him her us them my your his our their be not no yes so do'.split(' '));
function lemma(t) {
  if (IRR2[t]) return IRR2[t];
  if (BY[t]) return t;
  const rules = [['ies', 'y'], ['ves', 'f'], ['es', ''], ['s', ''], ['ied', 'y'], ['ed', ''], ['ed', 'e'], ['d', ''], ['ing', ''], ['ing', 'e'], ['er', ''], ['est', ''], ['ly', ''], ["'s", ''], ["s'", '']];
  for (const [suf, rep] of rules) if (t.endsWith(suf) && t.length - suf.length >= 2) {
    const b = t.slice(0, -suf.length) + rep;
    if (BY[b]) return b;
    if (b.length > 2 && b.at(-1) === b.at(-2) && BY[b.slice(0, -1)]) return b.slice(0, -1);
  }
  return null;
}
let shareSel = new Set(), shareData = null;
window.onShared = text => {
  text = String(text || '').trim();
  if (/^\S+:\/\/\S+$/.test(text) || (text.length < 400 && /https?:\/\//.test(text) && !/[a-z]{3,}\s+[a-z]{3,}\s+[a-z]{3,}/i.test(text.replace(/https?:\/\/\S+/g, '')))) {
    if ($('hub').hidden) openHub();
    const el = $('sheet');
    el.innerHTML = `<div class="sh-top"><button class="back" id="shBack">← Назад</button></div><div class="sh-body"><section class="block"><h3>Пришла ссылка, а не текст</h3><p>Выдели сам текст на странице (долгое нажатие → «Выделить» → «Поделиться») и отправь его в Словопад — лис разберёт слова и скажет, сколько ты уже понимаешь.</p></section></div>`;
    el.hidden = false; $('shBack').onclick = () => { el.hidden = true; el.innerHTML = ''; };
    return;
  }
  const low = ' ' + text.toLowerCase().replace(/[’`]/g, "'").replace(/[^a-z' -]+/g, ' ').replace(/\s+/g, ' ') + ' ';
  const cnt = {}, unk = {};
  // сначала выражения из нескольких слов (phrasal verbs и т.п.)
  let rest = low;
  W.filter(w => w.en.includes(' ')).forEach(w => { const k = ' ' + w.en.toLowerCase() + ' '; let i = rest.indexOf(k); while (i >= 0) { cnt[w.en] = (cnt[w.en] || 0) + 1; rest = rest.slice(0, i) + ' ' + rest.slice(i + k.length - 1); i = rest.indexOf(k); } });
  let total = 0;
  rest.split(' ').forEach(t => {
    t = t.replace(/^'+|'+$/g, ''); if (t.length < 2) return;
    total++;
    if (STOP.has(t)) return;
    const l = lemma(t);
    if (l) cnt[l] = (cnt[l] || 0) + 1; else if (t.length > 2 && !/^(ve|ll|re|nt)$/.test(t)) unk[t] = (unk[t] || 0) + 1;
  });
  const found = Object.keys(cnt).map(en => ({ w: BY[en], n: cnt[en] })).filter(x => x.w);
  const newW = found.filter(x => st(x.w.en) === 0).sort((a, b) => b.n - a.n || a.w.i - b.w.i);
  const learning = found.filter(x => st(x.w.en) === 1), knownF = found.filter(x => st(x.w.en) >= 2);
  const knownTok = knownF.reduce((a, x) => a + x.n, 0) + [...rest.split(' ')].filter(t => STOP.has(t)).length;
  const cover = total ? Math.round(knownTok / total * 100) : 0;
  shareData = { newW, unk: Object.keys(unk).sort((a, b) => unk[b] - unk[a]) };
  shareSel = new Set(newW.slice(0, 30).map(x => x.w.en));
  if (!$('hub').hidden) {} else openHub();
  renderShare(total, cover, learning.length, knownF.length);
};
function renderShare(total, cover, nLearning, nKnown) {
  const { newW, unk } = shareData, el = $('sheet');
  el.innerHTML = `<div class="sh-top"><button class="back" id="shBack">← Назад</button></div>
  <div class="sh-body">
    <section class="block"><h3>Лис разобрал текст <span>${total} ${pl(total, 'слово', 'слова', 'слов')}</span></h3>
      <div class="cover"><div class="bar"><i style="width:${cover}%"></i></div><b class="num">${cover}%</b></div>
      <p>Столько слов текста ты уже знаешь. ${cover >= 95 ? 'Можно читать почти без словаря 🔥' : cover >= 80 ? 'Читать уже можно, но со словарём.' : 'Пока тяжеловато — добей новые слова, и текст откроется.'}</p>
      <p class="muted">Знакомых слов: ${nKnown} · учишь: ${nLearning} · новых: ${newW.length}${unk.length ? ` · нет в словаре Словопада: ${unk.length}` : ''}</p>
    </section>
    ${newW.length ? `<section class="block"><h3>Новые слова <span>${newW.length}</span></h3>
      <div class="btnrow"><button class="btn" id="shLearn">Учить выбранные</button><button class="btn ghost" id="shKnow">Я их знаю</button></div>
      <div id="shList">${newW.map(x => `<label class="srow"><input type="checkbox" data-en="${esc(x.w.en)}" ${shareSel.has(x.w.en) ? 'checked' : ''}><span><b>${esc(x.w.en)}</b> — ${esc(x.w.ru)}</span><small class="num">×${x.n}</small></label>`).join('')}</div>
    </section>` : '<section class="block"><p>Новых слов из словаря Словопада тут нет — ты всё это уже знаешь или учишь. Красава!</p></section>'}
    ${unk.length ? `<section class="block"><h3>Нет в словаре <span>${unk.length}</span></h3><p class="muted">${unk.slice(0, 40).map(esc).join(', ')}${unk.length > 40 ? '…' : ''}</p><p class="muted">Это редкие слова, имена или формы, которых нет среди 5000 частых. Их можно спросить у лиса: зажми его на экране.</p></section>` : ''}
  </div>`;
  el.hidden = false;
  $('shBack').onclick = () => { el.hidden = true; el.innerHTML = ''; };
  el.onchange = e => { const c = e.target.closest('input[data-en]'); if (!c) return; c.checked ? shareSel.add(c.dataset.en) : shareSel.delete(c.dataset.en); };
  if ($('shLearn')) $('shLearn').onclick = () => {
    const pick = [...shareSel]; if (!pick.length) return toast('Ничего не выбрано');
    S.queue = [...new Set([...pick, ...(S.queue || [])])].filter(en => st(en) === 0);
    save(); el.hidden = true; el.innerHTML = '';
    toast(`${pick.length} ${pl(pick.length, 'слово', 'слова', 'слов')} в очереди — пойдут первыми в игре`, 'ach');
  };
  if ($('shKnow')) $('shKnow').onclick = () => {
    const pick = [...shareSel]; if (!pick.length) return toast('Ничего не выбрано');
    pick.forEach(en => { const r = rec(en); r.s = 3; r.t = Date.now(); });
    S.queue = (S.queue || []).filter(en => st(en) === 0);
    checkBuild(); checkAch(); foxEv(); save(); el.hidden = true; el.innerHTML = '';
    toast(`+${pick.length} ${pl(pick.length, 'кирпич', 'кирпича', 'кирпичей')} в стройку и лису`, 'ok');
    if (MQ.length) showModal(() => {});
  };
}

/* ---------- свой мозг: скачивание и проверка ---------- */
let brainIdx = null, brainPoll = 0;
const GB = b => (b / 1e9).toFixed(b > 1e9 ? 1 : 2).replace('.', ',') + ' ГБ';
window.onBrain = (kind, val) => {
  if (kind === 'index') { try { brainIdx = JSON.parse(val); } catch (e) { brainIdx = { err: true }; } brainRender(); }
  else if (kind === 'start') { if (val) toast(val); brainRender(); }
  else if (kind === 'test') { const t = $('bTestOut'); if (t) t.textContent = val || 'Мозг не ответил — попробуй удалить и скачать заново.'; }
};
function brainRender() {
  const box = $('brainBox'); if (!box) return;
  if (!(window.Android && Android.brainStatus)) { box.innerHTML = '<p class="muted">Свой мозг работает только в приложении на телефоне.</p>'; return; }
  let st = {}; try { st = JSON.parse(Android.brainStatus()); } catch (e) {}
  clearTimeout(brainPoll);
  if (st.lib === false) { box.innerHTML = '<p class="muted">В этой сборке нет движка нейросети — обнови приложение.</p>'; return; }
  if (st.state === 'downloading' || st.state === 'paused') {
    const pct = st.total > 0 ? Math.floor(st.done / st.total * 100) : 0;
    box.innerHTML = `<p>${st.state === 'paused' ? '⏸ Пауза — ждём сеть (Wi-Fi?)' : '⬇️ Качаю мозг' + (st.mirror ? ` (зеркало ${st.mirror + 1})` : '') + '…'} <b class="num">${pct}%</b> <span class="muted">${st.total > 0 ? GB(st.done) + ' из ' + GB(st.total) : ''}</span></p>
      <div class="bar"><i style="width:${pct}%"></i></div><div class="btnrow"><button class="btn ghost" id="bCancel">Отменить</button></div>`;
    $('bCancel').onclick = () => { Android.brainCancel(); brainRender(); };
    brainPoll = setTimeout(brainRender, 1000); return;
  }
  if (st.state === 'verify') { box.innerHTML = '<p>🔍 Проверяю файл, это пара секунд…</p>'; brainPoll = setTimeout(brainRender, 800); return; }
  const old = st.ready && !/-v3\./.test(st.file || '');
  if (old && !brainIdx) { box.innerHTML = '<p class="muted">Смотрю, есть ли мозг поновее…</p>'; Android.brainIndex(); return; }
  if (st.ready) {
    box.innerHTML = `<p>🧠 Установлен: <b>${esc(st.title || 'мозг')}</b> <span class="muted">${GB(st.size)}</span></p>
      ${old && brainIdx && brainIdx.models && brainIdx.models.length ? `<p>🆕 <b>Вышел новый мозг — Qwen3.5.</b> Заметно умнее и отвечает быстрее. Старый удалится сам после загрузки.</p>${brainOpts()}` : ''}
      <p class="muted">Лис думает сам, без интернета и VPN. Первый ответ после паузы — пара секунд на «проснуться».</p>
      <div class="btnrow"><button class="btn" id="bTest">Проверить</button><button class="btn ghost" id="bDel">Удалить</button></div>
      <div class="btest" id="bTestOut" hidden></div>`;
    $('bTest').onclick = () => { const o = $('bTestOut'); o.hidden = false; o.textContent = '🦊 думаю…'; Android.brainTest('Привет! Как дела?'); };
    $('bDel').onclick = () => { if (!confirmDel()) return; Android.brainDelete(); toast('Мозг удалён, место свободно'); brainRender(); };
    if (old) brainOptsBind(box);
    return;
  }
  if (!brainIdx) { box.innerHTML = '<p class="muted">Смотрю, какие мозги есть…</p>'; Android.brainIndex(); return; }
  if (brainIdx.err || !brainIdx.models || !brainIdx.models.length) { box.innerHTML = '<p class="muted">Не смог получить список мозгов с GitHub. Проверь интернет.</p><div class="btnrow"><button class="btn ghost" id="bRetry">Ещё раз</button></div>'; $('bRetry').onclick = () => { brainIdx = null; brainRender(); }; return; }
  box.innerHTML = `${st.error ? `<p class="muted">⚠️ ${esc(st.error)}</p>` : ''}
    ${brainOpts()}
    <p class="muted">Качается один раз, потом лис думает без интернета. Нужно свободное место с запасом.</p>`;
  brainOptsBind(box);
}
function brainOpts() {
  return `${brainIdx.models.map(m => `<div class="bopt"><div><b>${esc(m.title)} · ${GB(m.size)}</b><small>${esc(m.desc)}</small></div><button class="btn" data-brain="${esc(m.id)}">Скачать</button></div>`).join('')}
    <label class="srow" style="border:0;padding:4px 0"><input type="checkbox" id="bWifi" ${S.brainWifi === false ? '' : 'checked'}><span>Только по Wi-Fi</span></label>`;
}
function brainOptsBind(box) {
  box.onclick = ev => { const b = ev.target.closest('[data-brain]'); if (!b) return; S.brainWifi = $('bWifi').checked; save(); b.disabled = true; b.textContent = '…'; Android.brainStart(b.dataset.brain, S.brainWifi); setTimeout(brainRender, 1500); };
}
let delArm = 0;
function confirmDel() { if (Date.now() - delArm < 4000) return true; delArm = Date.now(); toast('Нажми «Удалить» ещё раз, чтобы подтвердить'); return false; }
