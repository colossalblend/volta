// The disc on the home page, as it behaves in the app: a tap sends a spark round the
// rim while patina creeps out from where the finger touched; a second tap scrapes it
// back. It plays once by itself after the page loads, unless motion is turned down.
(function () {
  var root = document.querySelector('[data-demo]');
  if (!root) return;
  var disc = root.querySelector('.disc');
  var patina = root.querySelector('.patina');
  var title = root.querySelector('[data-title]');
  var caption = root.querySelector('[data-caption]');
  var ip = root.querySelector('[data-ip]');
  var timer = root.querySelector('[data-timer]');
  var still = window.matchMedia('(prefers-reduced-motion: reduce)');

  var IDLE = 'Нажмите на диск, чтобы включить VPN';
  var phase = 'off';
  var value = 0;
  var origin = { x: 0.5, y: 0.5 };
  var frame = 0, step = 0, clock = 0, since = 0, touched = false;

  function ease(t) { return t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2; }
  function easeOut(t) { return 1 - Math.pow(1 - t, 3); }

  // At 1 the solid part of the patina reaches the far edge, wherever it started.
  function paint(v) {
    value = v;
    var r = disc.clientWidth / 2;
    var ox = origin.x * r * 2, oy = origin.y * r * 2;
    var front = v * (Math.hypot(ox - r, oy - r) + r * 1.12);
    patina.style.setProperty('--ox', ox + 'px');
    patina.style.setProperty('--oy', oy + 'px');
    patina.style.setProperty('--edge', (r * 0.12) + 'px');
    patina.style.setProperty('--front', front + 'px');
  }

  function run(to, ms, curve, done) {
    cancelAnimationFrame(frame);
    if (still.matches || !ms) { paint(to); if (done) done(); return; }
    var from = value, start = performance.now();
    function tick(now) {
      var t = Math.min(1, (now - start) / ms);
      paint(from + (to - from) * curve(t));
      if (t < 1) frame = requestAnimationFrame(tick);
      else if (done) done();
    }
    frame = requestAnimationFrame(tick);
  }

  function swap(el, text) {
    if (el.textContent === text) return;
    el.textContent = text;
    el.classList.remove('ph-swap');
    void el.offsetWidth;
    el.classList.add('ph-swap');
  }

  function pad(n) { return (n < 10 ? '0' : '') + n; }
  function tickClock() {
    var s = Math.floor((Date.now() - since) / 1000);
    timer.textContent = pad(Math.floor(s / 60)) + ':' + pad(s % 60);
  }

  function show(next) {
    phase = next;
    root.setAttribute('data-phase', next);
    disc.setAttribute('aria-pressed', next === 'on' || next === 'connecting' ? 'true' : 'false');
    if (next === 'connecting') { swap(title, 'Подключаю'); swap(caption, 'Поднимаю туннель…'); }
    if (next === 'on') {
      swap(title, 'Защищено');
      swap(caption, 'Нидерланды');
      ip.textContent = 'Нидерланды, 203.0.113.24';
      since = Date.now();
      tickClock();
      clearInterval(clock);
      clock = setInterval(tickClock, 1000);
      root.classList.remove('burst');
      void root.offsetWidth;
      root.classList.add('burst');
    }
    if (next === 'stopping') { swap(title, 'Отключаю'); swap(caption, 'Закрываю туннель…'); }
    if (next === 'off') {
      swap(title, 'Выключено');
      swap(caption, IDLE);
      ip.textContent = 'Россия, 198.51.100.23';
      clearInterval(clock);
    }
  }

  function connect() {
    show('connecting');
    // Creeps and never quite arrives while the tunnel comes up.
    run(0.3, 1500, easeOut);
    step = setTimeout(function () {
      show('on');
      run(1, 900, ease);
    }, still.matches ? 0 : 1500);
  }

  function disconnect() {
    clearTimeout(step);
    show('stopping');
    run(0, 650, ease, function () { show('off'); });
  }

  disc.addEventListener('pointerdown', function (e) {
    var box = disc.getBoundingClientRect();
    origin = {
      x: Math.min(1, Math.max(0, (e.clientX - box.left) / box.width)),
      y: Math.min(1, Math.max(0, (e.clientY - box.top) / box.height))
    };
  });
  disc.addEventListener('click', function (e) {
    touched = true;
    if (e.detail === 0) origin = { x: 0.5, y: 0.5 };
    if (phase === 'stopping') return;
    if (phase === 'off') connect(); else disconnect();
  });

  window.addEventListener('resize', function () { paint(value); });

  if (!still.matches) {
    setTimeout(function () {
      if (touched || phase !== 'off') return;
      origin = { x: 0.64, y: 0.72 };
      connect();
    }, 900);
  }
})();
