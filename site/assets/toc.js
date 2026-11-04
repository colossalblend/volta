// Side contents: folded on narrow screens, marks the section you are reading on wide ones.
(function () {
  var toc = document.querySelector('.toc');
  if (!toc) return;
  var details = toc.querySelector('details');
  var wide = window.matchMedia('(min-width: 1180px)');
  function fold() { if (details) details.open = wide.matches; }
  fold();
  if (wide.addEventListener) wide.addEventListener('change', fold);

  var links = {};
  toc.querySelectorAll('a[href^="#"]').forEach(function (a) { links[a.getAttribute('href').slice(1)] = a; });
  var parts = document.querySelectorAll('.part[aria-labelledby]');
  if (!('IntersectionObserver' in window) || !parts.length) return;
  var visible = {};
  function mark() {
    var current = null;
    parts.forEach(function (p) { if (!current && visible[p.getAttribute('aria-labelledby')]) current = p.getAttribute('aria-labelledby'); });
    if (!current) return;
    Object.keys(links).forEach(function (id) {
      if (id === current) links[id].setAttribute('aria-current', 'true');
      else links[id].removeAttribute('aria-current');
    });
  }
  var io = new IntersectionObserver(function (entries) {
    entries.forEach(function (e) { visible[e.target.getAttribute('aria-labelledby')] = e.isIntersecting; });
    mark();
  }, { rootMargin: '-15% 0px -55% 0px' });
  parts.forEach(function (p) { io.observe(p); });
})();
