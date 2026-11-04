/* Homepage Trace mockup: scrub the invented sample day. No storage or network. */
(() => {
  const root = document.querySelector('[data-trace-replay]');
  if (!root) return;
  const scrub = root.querySelector('.trace-scrub');
  const part = name => root.querySelector(`[data-trace="${name}"]`);
  const trail = part('trail'), halo = part('halo'), dot = part('dot');
  const kicker = part('kicker'), label = part('label'), clock = part('clock'), dist = part('dist');

  // [minute of day, x, y] keyframes of the sample route on the 390×560 map.
  const K = [[0,70,470],[492,70,470],[498,110,420],[505,180,380],[512,240,260],[517,290,170],[520,320,110],[565,320,110],[573,280,180],[581,230,250],[590,170,330],[750,170,330],[758,150,355],[765,130,380],[815,130,380],[830,170,330],[860,170,330]];
  const names = { '70,470': 'Home', '320,110': 'Ferry Building', '170,330': 'Mission Office', '130,380': 'Tartine Manufactory' };
  const NOW = 860;
  const pad = n => String(n).padStart(2, '0');

  function render(raw) {
    const t = Math.min(raw, NOW);
    let px = 70, py = 470, i = 0, moving = false, mode = 'Walking';
    const pts = [];
    for (i = 0; i < K.length - 1; i++) {
      const a = K[i], b = K[i + 1];
      pts.push([a[1], a[2]]);
      if (t <= b[0]) {
        const f = b[0] === a[0] ? 0 : (t - a[0]) / (b[0] - a[0]);
        px = a[1] + (b[1] - a[1]) * f;
        py = a[2] + (b[2] - a[2]) * f;
        moving = !(a[1] === b[1] && a[2] === b[2]);
        mode = a[0] >= 492 && b[0] <= 520 ? 'Biking' : 'Walking';
        break;
      }
    }
    if (i === K.length - 1) { px = 170; py = 330; }
    pts.push([px, py]);
    let d = 0;
    for (let k = 1; k < pts.length; k++) d += Math.hypot(pts[k][0] - pts[k - 1][0], pts[k][1] - pts[k - 1][1]);

    const here = names[Math.round(px) + ',' + Math.round(py)];
    let k, l;
    if (raw > NOW) { k = 'LATER TODAY'; l = 'Not lived yet'; }
    else if (moving || !here) {
      k = mode.toUpperCase() + ' · ' + (mode === 'Biking' ? '14 KM/H' : '4 KM/H');
      l = mode === 'Biking' ? 'Along the Embarcadero' : 'On Valencia Street';
    } else { k = 'STAYING AT'; l = here; }
    const time = pad(Math.floor(raw / 60) % 24) + ':' + pad(raw % 60);

    trail.setAttribute('points', pts.map(p => p[0].toFixed(1) + ',' + p[1].toFixed(1)).join(' '));
    for (const c of [halo, dot]) { c.setAttribute('cx', px.toFixed(1)); c.setAttribute('cy', py.toFixed(1)); }
    kicker.textContent = k;
    label.textContent = l;
    clock.textContent = time;
    dist.textContent = (d / 60).toFixed(1);
    const spoken = raw > NOW ? 'not lived yet' : k === 'STAYING AT' ? `staying at ${l}` : `${mode.toLowerCase()}, ${l}`;
    scrub.setAttribute('aria-valuetext', `${time}, ${spoken}`);
  }

  scrub.addEventListener('input', () => render(Number(scrub.value)));
  root.addEventListener('click', event => {
    const jump = event.target.closest('.trace-jump');
    if (!jump) return;
    scrub.value = jump.dataset.minute;
    render(Number(scrub.value));
  });
  render(Number(scrub.value));
})();
