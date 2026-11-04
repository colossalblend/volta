// The header takes a surface once the page scrolls under it, like the app's top bars.
// The contents panel is folded on narrow screens and marks the section being read.
(function () {
  var top = document.querySelector('.top');
  var toc = document.querySelector('.toc');
  var details = toc && toc.querySelector('details');
  var wide = window.matchMedia('(min-width: 1060px)');
  var links = {};
  var parts = [];

  if (toc) {
    toc.querySelectorAll('a[href^="#"]').forEach(function (a) { links[a.getAttribute('href').slice(1)] = a; });
    document.querySelectorAll('.doc section[aria-labelledby]').forEach(function (s) {
      var id = s.getAttribute('aria-labelledby');
      if (links[id]) parts.push({ id: id, el: s });
    });
    // On wide screens the panel always stays open, so its title is not a control there.
    var fold = function () {
      if (!details) return;
      details.open = wide.matches;
      var summary = details.querySelector('summary');
      if (summary) summary.tabIndex = wide.matches ? -1 : 0;
    };
    fold();
    if (wide.addEventListener) wide.addEventListener('change', fold);
  }

  function mark() {
    if (!parts.length) return;
    var line = window.innerHeight * 0.3;
    var current = parts[0].id;
    parts.forEach(function (p) { if (p.el.getBoundingClientRect().top <= line) current = p.id; });
    if (window.innerHeight + window.scrollY >= document.documentElement.scrollHeight - 2) current = parts[parts.length - 1].id;
    Object.keys(links).forEach(function (id) {
      if (id === current) links[id].setAttribute('aria-current', 'true');
      else links[id].removeAttribute('aria-current');
    });
  }

  var queued = false;
  function update() {
    queued = false;
    if (top) top.classList.toggle('scrolled', window.scrollY > 4);
    mark();
  }
  window.addEventListener('scroll', function () {
    if (!queued) { queued = true; requestAnimationFrame(update); }
  }, { passive: true });
  window.addEventListener('resize', update);
  update();
})();
