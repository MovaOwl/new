/* ================= Лис-тамагочи =================
   Спрайты: assets/fox/<anim>.png — горизонтальная лента кадров (w×h каждый).
   Жизнь лиса зависит от того, сколько слов ты знаешь (выучено + «знал»). */
window.FOX_META = {"sleep":{"w":192,"h":160,"n":4},"wake":{"w":194,"h":160,"n":8},"idle":{"w":172,"h":160,"n":4},"sit":{"w":170,"h":160,"n":8},"wag":{"w":176,"h":160,"n":4},"wave":{"w":150,"h":160,"n":8},"talk":{"w":174,"h":160,"n":4},"surprise":{"w":161,"h":160,"n":3},"jump":{"w":118,"h":160,"n":6},"cheer":{"w":144,"h":160,"n":2},"laugh":{"w":148,"h":160,"n":8},"sad":{"w":207,"h":160,"n":8},"grumpy":{"w":123,"h":160,"n":4},"turn":{"w":186,"h":160,"n":8},"walk":{"w":209,"h":160,"n":8},"run":{"w":232,"h":160,"n":8},"eat":{"w":185,"h":160,"n":8},"read":{"w":144,"h":160,"n":8},"dig":{"w":243,"h":160,"n":4},"fish":{"w":193,"h":160,"n":4},"stove":{"w":244,"h":160,"n":8},"chop":{"w":223,"h":160,"n":8},"water":{"w":244,"h":160,"n":8},"icons":{"w":66,"h":64,"n":8}};
const FOX_FPS = { sleep: 2.5, wake: 5, idle: 4, sit: 4, wag: 7, wave: 7, talk: 6, surprise: 4, jump: 9, cheer: 3, laugh: 8, sad: 4, grumpy: 3, turn: 5, walk: 9, run: 13, eat: 6, read: 3, dig: 5, fish: 2.5, stove: 4, chop: 6, water: 5 };
const FOX_ICON = { heart: 0, zzz: 1, excl: 2, quest: 3, note: 4, puff: 5, drop: 6, star: 7 };

/* стадии жизни: at — сколько слов знаешь */
const FOX_ST = [
  { at: 0, n: 'Спит', d: 'Свернулся клубком и видит сны. Выучи 10 слов — проснётся.', idle: ['sleep'] },
  { at: 10, n: 'Проснулся', d: 'Зевает, моргает и здоровается: «Hi!»', idle: ['sit', 'sit', 'idle'] },
  { at: 25, n: 'Дружок', d: 'Машет лапой, виляет хвостом и говорит твои слова.', idle: ['sit', 'idle', 'wag', 'wave'] },
  { at: 50, n: 'Ест слова', d: 'Слова — его еда. Корми каждый день: 10 верных ответов — миска полная.', idle: ['sit', 'idle', 'wag', 'wave', 'eat'] },
  { at: 100, n: 'Болтун', d: 'Гуляет, читает и говорит целыми фразами из твоих слов.', idle: ['idle', 'wag', 'walk', 'read', 'read', 'run', 'talk'] },
  { at: 300, n: 'Работяга', d: 'Помогает со стройкой: копает, колет дрова, топит печь, поливает грядки.', idle: ['dig', 'chop', 'stove', 'water', 'read', 'walk', 'wag'] },
  { at: 600, n: 'Шутник', d: 'Рыбачит и травит американские шутки и идиомы — с объяснением.', idle: ['fish', 'laugh', 'chop', 'stove', 'read', 'walk', 'water'] },
  { at: 1000, n: 'Свой в доску', d: 'Говорит на сленге, как настоящий американец.', idle: ['fish', 'laugh', 'read', 'walk', 'run', 'dig', 'chop', 'stove', 'water', 'wag'] },
];

const FOX_HI = [['Hi!', 'Привет!'], ['Hello!', 'Здравствуй!'], ['Good morning!', 'Доброе утро!'], ['*yawn* Hi…', '*зевает* Привет…'], ['Hey there!', 'Эй, привет!'], ['I missed you!', 'Я скучал!']];

/* 600+: американские идиомы и шутки — то, что интересно потом загуглить */
const FOX_JOKES = [
  ['Break a leg!', 'Дословно «сломай ногу», а значит «ни пуха ни пера». Так желают удачи перед выступлением.'],
  ['It\'s raining cats and dogs.', '«Льёт кошками и собаками» — ливень стеной.'],
  ['Piece of cake.', '«Кусок торта» — проще простого.'],
  ['Hit the sack.', '«Ударить мешок» — завалиться спать.'],
  ['Spill the beans.', '«Рассыпать бобы» — проболтаться, выдать секрет.'],
  ['Cost an arm and a leg.', '«Стоит руку и ногу» — стоит бешеных денег. Про квартиры в Москве — точно.'],
  ['Under the weather.', '«Под погодой» — приболел, неважно себя чувствую.'],
  ['Once in a blue moon.', '«Раз в синюю луну» — раз в сто лет, очень редко.'],
  ['The ball is in your court.', '«Мяч на твоей стороне» — твой ход, решать тебе. Говорят на переговорах.'],
  ['Bite the bullet.', '«Закусить пулю» — стиснуть зубы и сделать неприятное.'],
  ['Let the cat out of the bag.', '«Выпустить кота из мешка» — раскрыть секрет.'],
  ['Hit the nail on the head.', '«Попасть по шляпке гвоздя» — попасть в точку.'],
  ['Kill two birds with one stone.', '«Убить двух птиц одним камнем» — убить двух зайцев.'],
  ['When pigs fly.', '«Когда свиньи полетят» — когда рак на горе свистнет.'],
  ['Don\'t count your chickens before they hatch.', '«Не считай цыплят, пока не вылупились» — цыплят по осени считают. Про задаток до сделки!'],
  ['Get cold feet.', '«Замёрзли ноги» — струсить в последний момент. Покупатель «got cold feet» перед подписанием.'],
  ['On the fence.', '«На заборе» — колеблется, не может решить.'],
  ['Cut corners.', '«Срезать углы» — халтурить, экономить на качестве.'],
  ['Go the extra mile.', '«Пройти лишнюю милю» — сделать больше, чем просили.'],
  ['A dime a dozen.', '«Дайм за дюжину» — пруд пруди, ничего особенного.'],
  ['Hang in there!', '«Повисни там» — держись, не сдавайся!'],
  ['You can say that again!', '«Можешь сказать это ещё раз» — вот уж точно, согласен на сто процентов.'],
  ['No pain, no gain.', 'Без боли нет результата — без труда не вытащишь и рыбку из пруда. Кстати, я рыбачу.'],
  ['Why did the scarecrow win an award? He was outstanding in his field.', 'Игра слов: outstanding — «выдающийся» и «стоящий в своём поле». Пугало стоит в поле.'],
  ['I told my wife she was drawing her eyebrows too high. She looked surprised.', 'Брови нарисованы высоко — лицо выглядит удивлённым. Классическая американская шутка «dad joke».'],
  ['I\'m reading a book about anti-gravity. It\'s impossible to put down.', '«Невозможно отложить» — и книга увлекательная, и антигравитация. Dad joke.'],
  ['What do you call a fake noodle? An impasta.', 'impasta = impostor (самозванец) + pasta. Каламбур.'],
  ['Why don\'t skeletons fight? They don\'t have the guts.', 'guts — «кишки» и «смелость». У скелетов нет ни того, ни другого.'],
  ['I would tell you a joke about real estate, but it\'s too high-priced.', 'Шутка про недвижимость, но она «слишком дорогая». Риелторский юмор.'],
  ['Location, location, location!', 'Главное правило недвижимости: три главных фактора — расположение, расположение и расположение.'],
  ['Home sweet home.', '«Дом, милый дом» — пишут на табличках у входа.'],
  ['My house is a fixer-upper — it fixes me up with debt.', 'fixer-upper — дом под ремонт. Шутка: «он чинит меня долгами».'],
  ['The early bird catches the worm.', 'Кто рано встаёт, тому бог подаёт. Ранняя птица ловит червяка.'],
  ['Every cloud has a silver lining.', 'У каждой тучи есть серебряная кайма — нет худа без добра.'],
  ['Actions speak louder than words.', 'Поступки громче слов. Не болтай — учи слова.'],
  ['Better late than never.', 'Лучше поздно, чем никогда. Это про тех, кто пропустил день.'],
  ['Practice makes perfect.', 'Практика делает идеальным — повторение мать учения.'],
  ['Rome wasn\'t built in a day.', 'Рим не за день строился. И твой объект тоже.'],
  ['Knock on wood!', '«Постучи по дереву» — тьфу-тьфу, чтоб не сглазить. Я как раз дрова колю.'],
  ['That\'s the way the cookie crumbles.', '«Так крошится печенька» — ну, такова жизнь.'],
  ['Sleep on it.', '«Поспи на этом» — утро вечера мудренее. Говорят клиенту перед решением.'],
  ['Back to square one.', '«Обратно на клетку один» — начать всё сначала.'],
];

/* 1000+: сленг */
const FOX_SLANG = [
  ['What\'s up?', 'Как дела? / Чё как? Отвечают «Not much».'],
  ['No cap.', 'Без шуток, отвечаю. cap — враньё.'],
  ['That\'s lit!', 'Это огонь! Круто.'],
  ['I\'m down.', 'Я в деле, я за. «Wanna grab a beer? — I\'m down.»'],
  ['My bad.', 'Мой косяк, виноват.'],
  ['Chill out.', 'Остынь, расслабься.'],
  ['It\'s a deal-breaker.', 'Это меня не устраивает — сделки не будет.'],
  ['Gonna / wanna / gotta.', 'going to / want to / got to — так говорят вживую: «I gotta go».'],
  ['Ain\'t nobody got time for that.', 'Ни у кого нет на это времени. Мем.'],
  ['Spill the tea.', 'Выкладывай сплетни. tea — сплетни, новости.'],
  ['Low-key.', 'Слегка, втихую. «I low-key love this app».'],
  ['GOAT.', 'Greatest Of All Time — величайший. Не козёл, а наоборот!'],
  ['Hit me up.', 'Напиши / набери мне.'],
  ['Bummer.', 'Облом, досада.'],
  ['Buck.', 'Доллар. «Five bucks» — пять баксов.'],
  ['Grand.', 'Тысяча долларов. «The flat is two hundred grand».'],
  ['Cheesy.', 'Пошлый, слащавый, банальный.'],
  ['Hangry.', 'hungry + angry — злой от голода. Это я, когда ты не учишь слова.'],
  ['Ghosting.', 'Пропасть без объяснений, перестать отвечать. Клиент ghosted me.'],
  ['FOMO.', 'Fear Of Missing Out — страх упустить. Двигатель продаж.'],
  ['Sus.', 'Подозрительно. От suspicious.'],
  ['Vibe.', 'Атмосфера, настроение. «This house has good vibes».'],
  ['Slay.', 'Порвать, сделать идеально. «You slayed that test!»'],
  ['It slaps.', 'Это качает, очень круто (про музыку, еду).'],
  ['I\'m beat.', 'Я вымотан.'],
  ['Get the ball rolling.', 'Сдвинуть дело с мёртвой точки.'],
  ['Touch base.', 'Связаться, свериться. Офисный сленг: «Let\'s touch base tomorrow».'],
  ['Ballpark figure.', 'Примерная цифра. «Give me a ballpark».'],
  ['Wing it.', 'Импровизировать, действовать без подготовки.'],
  ['Cool beans!', 'Отлично! Клёво! (шутливое)'],
];

const foxSt = n => { let i = 0; FOX_ST.forEach((s, k) => { if (n >= s.at) i = k; }); return i; };
const foxN = () => (typeof nBricks === 'function' ? nBricks() : 0);
const rnd = a => a[Math.floor(Math.random() * a.length)];

/* ---------- спрайт-проигрыватель ---------- */
function FoxView(el, H) {
  this.el = el; this.H = H; this.t = null; this.f = 0; this.q = null;
  el.classList.add('foxspr');
}
FoxView.prototype.play = function (name, o = {}) {
  const m = FOX_META[name]; if (!m) return;
  clearInterval(this.t);
  const sc = this.H / m.h, el = this.el;
  el.style.width = Math.round(m.w * sc) + 'px'; el.style.height = this.H + 'px';
  el.style.backgroundImage = `url(fox/${name}.png)`;
  el.style.backgroundSize = `${Math.round(m.w * sc * m.n)}px ${this.H}px`;
  this.cur = name; this.f = 0;
  const loops = o.loops || (o.once ? 1 : Infinity); let done = 0;
  const step = () => {
    el.style.backgroundPosition = `-${Math.round(this.f * m.w * sc)}px 0`;
    this.f++;
    if (this.f >= m.n) { this.f = 0; done++; if (done >= loops) { clearInterval(this.t); this.t = null; o.then && setTimeout(o.then, 1000 / (o.fps || FOX_FPS[name] || 6)); } }
  };
  step();
  this.t = setInterval(step, 1000 / (o.fps || FOX_FPS[name] || 6));
};
FoxView.prototype.stop = function () { clearInterval(this.t); this.t = null; };

/* ---------- мини-лис на игровом поле ---------- */
let foxMini = null, foxMiniBusy = false;
function foxMiniIdle() {
  if (!foxMini) return;
  const s = foxSt(foxN());
  if (S.foxMood === 'grumpy') return foxMini.play('grumpy');
  foxMini.play(s === 0 ? 'sleep' : s < 2 ? 'sit' : rnd(['sit', 'idle', 'wag']), { loops: s === 0 ? Infinity : 3, then: foxMiniIdle });
}
function foxMiniMount() {
  const f = document.getElementById('foxMini'); if (!f) return;
  if (!foxMini) foxMini = new FoxView(f, 64);
  foxMiniIdle();
}
/* события из игры */
function foxEv(ev) {
  const n = foxN(), s = foxSt(n);
  if (S.foxSt == null) S.foxSt = s;
  if (s > S.foxSt) {
    S.foxSt = s;
    setTimeout(() => toast(`Лис: «${FOX_ST[s].n}» — ${FOX_ST[s].d}`, 'ach'), 900);
  }
  if (ev === 'ok' && S.foxMood === 'grumpy') { S.foxMood = ''; }
  if (!foxMini || s === 0) return;
  const map = { ok: ['jump', 1], learn: [s >= 3 ? 'eat' : 'jump', 1], bad: ['sad', 1], lvl: ['cheer', 3], combo: ['laugh', 1] };
  const a = map[ev]; if (!a) return;
  if (foxMiniBusy && ev === 'ok') return;
  foxMiniBusy = true;
  foxMini.play(a[0], { loops: a[1], then: () => { foxMiniBusy = false; foxMiniIdle(); } });
}
function foxOnBoot() {
  if (S.lastActive && S.lastActive < dAdd(-2) && foxSt(foxN()) > 0) S.foxMood = 'grumpy';
  if (S.foxSt == null) S.foxSt = foxSt(foxN());
}

/* ---------- вкладка «Лис» ---------- */
let foxBig = null, foxIdleT = null, foxWalkX = 0;
function foxSay(en, ru, more) {
  const b = document.getElementById('foxBub'); if (!b) return;
  b.innerHTML = `<b>${esc(en)}</b>${ru ? `<span>${esc(ru)}</span>` : ''}${more ? `<i>${esc(more)}</i>` : ''}`;
  b.hidden = false; b.onclick = e => { e.stopPropagation(); say(en.replace(/\*[^*]*\*/g, ''), true); };
  clearTimeout(b._t); b._t = setTimeout(() => { b.hidden = true; }, more ? 12000 : 6000);
}
function foxIcon(k) {
  const i = document.getElementById('foxIcon'); if (!i) return;
  const m = FOX_META.icons; i.style.backgroundPosition = `-${FOX_ICON[k] * 40}px 0`; i.style.backgroundSize = `${40 * m.n}px 39px`;
  i.classList.remove('pop'); void i.offsetWidth; i.classList.add('pop');
}
function foxLine() {
  const n = foxN(), s = foxSt(n);
  const mine = W.filter(w => st(w.en) >= 2);
  if (s >= 7 && Math.random() < .5) { const j = rnd(FOX_SLANG); return [j[0], '', j[1]]; }
  if (s >= 6 && Math.random() < .6) { const j = rnd(FOX_JOKES); return [j[0], '', j[1]]; }
  if (s >= 4) { const U = pUnlocked(); if (U.length) { const p = rnd(U); return [p.en, p.ru]; } }
  if (s >= 2 && mine.length) { const w = rnd(mine); return [w.en + '!', w.ru]; }
  return rnd(FOX_HI);
}
function foxDoIdle() {
  clearTimeout(foxIdleT); if (!foxBig || !document.getElementById('foxBig')) return;
  const s = foxSt(foxN()), stage = document.getElementById('foxBig');
  if (S.foxMood === 'grumpy') { foxBig.play('grumpy'); return; }
  const a = s === 0 ? 'sleep' : rnd(FOX_ST[s].idle);
  if (a === 'walk' || a === 'run') {
    const box = document.getElementById('foxStage'), lim = Math.max(0, (box.clientWidth - 200) / 2);
    foxWalkX = foxWalkX > 0 ? -lim : lim; stage.style.transition = `transform ${a === 'run' ? 1.6 : 3.2}s linear`;
    stage.style.transform = `translateX(${foxWalkX}px) scaleX(${foxWalkX > 0 ? 1 : -1})`;
  } else { stage.style.transform = `translateX(${foxWalkX}px)`; }
  foxBig.play(a, { loops: s === 0 ? Infinity : a === 'walk' || a === 'run' ? (a === 'run' ? 2 : 3) : 3, then: foxDoIdle });
  if (s === 0) foxIcon('zzz');
  else if (Math.random() < .35 && a !== 'sleep') { const l = foxLine(); foxSay(...l); }
}
function foxPet() {
  if (!foxBig) return;
  const s = foxSt(foxN());
  if (s === 0) { foxIcon('zzz'); foxSay('Zzz…', `Спит. Выучи ещё ${10 - foxN()} ${pl(10 - foxN(), 'слово', 'слова', 'слов')} — проснётся`); return; }
  if (S.foxMood === 'grumpy') { foxIcon('puff'); foxSay('Hmph!', 'Обиделся: тебя не было пару дней. Дай 1 верный ответ в игре — оттает'); return; }
  foxIcon('heart'); clearTimeout(foxIdleT);
  foxBig.play(rnd(s >= 2 ? ['wave', 'jump', 'laugh', 'turn', 'wag'] : ['wake', 'sit']), { once: true, then: foxDoIdle });
  const l = foxLine(); foxSay(...l);
  buzz(15);
}
function foxFeed() {
  if (!foxBig) return;
  const fed = S.foxFed === S.day, c = today().c;
  if (fed) { foxIcon('heart'); foxSay('I\'m full, thanks!', 'Наелся, спасибо! Приходи завтра'); return; }
  if (c < 10) { foxIcon('quest'); foxSay('I\'m hungry…', `Я голодный… Еда — это твои верные ответы: сегодня ${c} из 10`); return; }
  S.foxFed = S.day; save(); foxIcon('heart'); clearTimeout(foxIdleT);
  foxBig.play('eat', { loops: 2, then: () => foxBig.play('cheer', { loops: 3, then: foxDoIdle }) });
  foxSay('Yummy! Words are tasty!', 'Ням! Слова вкусные!');
}
function tabFox() {
  const n = foxN(), s = foxSt(n), cs = FOX_ST[s], nx = FOX_ST[s + 1];
  const pct = nx ? Math.min(100, (n - cs.at) / (nx.at - cs.at) * 100) : 100;
  const fed = S.foxFed === S.day;
  $('hubBody').innerHTML = `
  <article class="foxcard">
    <div class="fox-stage" id="foxStage"><div class="fox-sky"></div><div class="fox-ground"></div>
      <div class="fox-wrap" id="foxBig"><div class="fox-icon" id="foxIcon"></div></div>
      <div class="fox-bub" id="foxBub" hidden></div></div>
    <div class="fox-info">
      <div class="fox-name"><b>${esc(S.foxName || 'Лис')}</b><span>${esc(cs.n)}${S.foxMood === 'grumpy' ? ' · обиделся' : ''}</span></div>
      <p>${esc(cs.d)}</p>
      <div class="fox-prog"><div class="bar"><i style="width:${pct}%"></i></div><small class="num">${nx ? `${n} из ${nx.at} слов до «${nx.n}»` : `${n} слов — максимум, лис прокачан`}</small></div>
      <div class="btnrow"><button class="btn" id="foxPetBtn">Погладить</button>${s >= 3 ? `<button class="btn ${fed ? 'ghost' : ''}" id="foxFeedBtn">${fed ? 'Сытый' : 'Покормить'}</button>` : ''}${s >= 1 ? '<button class="btn ghost" id="foxTalkBtn">Скажи что-нибудь</button>' : ''}</div>
    </div>
  </article>
  <section class="block"><h3>Жизнь лиса <span>от твоих слов</span></h3>
    ${FOX_ST.map((x, i) => `<div class="fox-row ${i <= s ? 'on' : ''}"><span class="num">${x.at}</span><div><b>${esc(x.n)}</b><small>${esc(x.d)}</small></div>${i <= s ? '✓' : '🔒'}</div>`).join('')}
    <p class="muted">Считаются выученные слова и те, что ты отметил «знал».</p>
  </section>`;
  foxBig = new FoxView(document.getElementById('foxBig'), 150);
  foxWalkX = 0; foxDoIdle();
  $('foxBig').onclick = foxPet; $('foxPetBtn').onclick = foxPet;
  if ($('foxFeedBtn')) $('foxFeedBtn').onclick = foxFeed;
  if ($('foxTalkBtn')) $('foxTalkBtn').onclick = () => { if (S.foxMood === 'grumpy') return foxPet(); clearTimeout(foxIdleT); foxBig.play(s >= 6 ? 'laugh' : 'talk', { loops: 2, then: foxDoIdle }); const l = foxLine(); foxSay(...l); foxIcon('note'); };
  if (s === 0) setTimeout(() => foxSay('Zzz…', `Лис спит. Ещё ${10 - n} ${pl(10 - n, 'слово', 'слова', 'слов')} — и проснётся`), 600);
}
function foxLeaveTab() { if (foxBig) foxBig.stop(); foxBig = null; clearTimeout(foxIdleT); }
