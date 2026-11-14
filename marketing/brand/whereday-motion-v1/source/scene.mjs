// Every moving value is a pure function of time. Capture never uses wall time.
const params = new URLSearchParams(location.search);
const format = params.get('format') || 'header';
const capture = params.has('capture');
const svg = document.querySelector('#scene');
const find = id => document.getElementById(id);
const ns = 'http://www.w3.org/2000/svg';
export const duration = 16;
const layouts = {
  header: { width: 1600, height: 1646 / 3840 * 1600, scale: .89, x: 116.48, y: -112 },
  search: { width: 1536, height: 1024, scale: 1, x: 0, y: 0 },
};
const layout = layouts[format] || layouts.header;
svg.setAttribute('viewBox', `0 0 ${layout.width} ${layout.height}`);
find('composition').setAttribute('transform', `translate(${layout.x} ${layout.y}) scale(${layout.scale})`);
if (format === 'header') find('art').setAttribute('mask', 'url(#soft-paper-edge)');

// Coordinates are in the original illustration space, following its streets.
const firstLeg = 'M319 566 C353 544 389 526 433 511 S512 470 537 444 C549 431 544 421 570 415 C603 407 631 432 680 454 S743 481 780 487';
const routeData = firstLeg + ' C826 496 866 471 906 451 C927 440 947 436 947 444 C946 458 900 468 891 492 C875 525 932 558 970 601 S1022 665 1053 718';
for (const id of ['route', 'route-bed']) find(id).setAttribute('d', routeData);
const path = find('route');
const length = path.getTotalLength();
const firstPath = document.createElementNS('http://www.w3.org/2000/svg', 'path');
firstPath.setAttribute('d', firstLeg);
const cafeFraction = firstPath.getTotalLength() / length;
const ease = x => { x = Math.max(0, Math.min(1, x)); return x * x * (3 - 2 * x); };
const out = x => 1 - Math.pow(1 - Math.max(0, Math.min(1, x)), 3);
const track = (keys, t) => {
  if (t <= keys[0][0]) return keys[0][1];
  for (let i = 1; i < keys.length; i++) {
    if (t <= keys[i][0]) {
      const [a, x] = keys[i - 1], [b, y] = keys[i];
      return x + (y - x) * ease((t - a) / (b - a));
    }
  }
  return keys.at(-1)[1];
};
const make = (tag, attrs, parent) => {
  const el = document.createElementNS(ns, tag);
  for (const [key, value] of Object.entries(attrs)) el.setAttribute(key, value);
  parent.append(el);
  return el;
};
const pins = [
  { fraction: 0, icon: 'home', time: .35 },
  { fraction: cafeFraction, icon: 'cafe', time: 5.9 },
  { fraction: 1, icon: 'lookout', time: 10.1 },
].map(spec => {
  const point = path.getPointAtLength(length * spec.fraction);
  const group = make('g', {}, find('visits'));
  const shadow = make('ellipse', { cx: point.x, cy: point.y + 3, rx: 18, ry: 5, fill: '#203557', opacity: '.09', stroke: 'none' }, group);
  const pin = make('g', {}, group);
  make('use', { href: '#marker' }, pin);
  make('use', { href: `#${spec.icon}`, y: -49, color: '#203557', stroke: 'none' }, pin);
  return { ...spec, point, group, pin, shadow };
});
const dots = Array.from({ length: 25 }, (_, i) => {
  const fraction = (i + 1) / 27;
  const point = path.getPointAtLength(length * fraction);
  const el = make('circle', { cx: point.x, cy: point.y, r: 3.3 }, find('breadcrumbs'));
  return { fraction, el };
});
path.style.strokeDasharray = `${length} ${length}`;
find('route-bed').style.strokeDasharray = `${length} ${length}`;

function draw(seconds, still = false) {
  const t = ((seconds % duration) + duration) % duration;
  const progress = still ? 1 : track([[0, 0], [1.6, 0], [5.7, cafeFraction], [6.7, cafeFraction], [10.2, 1], [16, 1]], t);
  const alpha = still ? 1 : 1 - ease((t - 13.8) / 1.7);
  find('journey').setAttribute('opacity', alpha);
  find('journey').style.display = alpha === 0 || (!still && t < .35) ? 'none' : '';
  for (const id of ['route', 'route-bed']) {
    find(id).style.strokeDashoffset = length * (1 - progress);
    find(id).setAttribute('opacity', progress > 0 ? '1' : '0');
  }
  for (const dot of dots) dot.el.setAttribute('opacity', progress >= dot.fraction ? '.82' : '0');
  for (const pin of pins) {
    const k = still ? 1 : out((t - pin.time) / .85);
    pin.group.setAttribute('opacity', k);
    pin.pin.setAttribute('transform', `translate(${pin.point.x} ${pin.point.y - 14 * (1 - k)}) scale(${.84 + .16 * k})`);
    pin.shadow.setAttribute('opacity', .09 * k);
  }
  const point = path.getPointAtLength(length * progress);
  find('traveler').setAttribute('transform', `translate(${point.x} ${point.y})`);
  find('traveler').setAttribute('opacity', still || t < 1.6 || t > 11.3 ? '0' : '1');
  find('traveler-halo').setAttribute('r', 16 + 3 * Math.sin(t * Math.PI));
  window.__lt = t;
}
window.__seek = (t, still = false) => draw(t, still);
window.__tick = dt => draw((window.__lt || 0) + dt);
window.__start = () => draw(0);
window.__duration = duration;
await Promise.all([...document.images].map(image => image.decode()));
// SVG images are not in document.images.
await new Promise((resolve, reject) => {
  const image = new Image();
  image.onload = resolve; image.onerror = reject; image.src = '../art/folded-city-map.png';
});
window.__ready = true;
const reduced = matchMedia('(prefers-reduced-motion: reduce)').matches;
if (params.has('still') || reduced) draw(12, true);
else if (capture) draw(0);
else {
  let start;
  let visible = true;
  document.addEventListener('visibilitychange', () => { visible = !document.hidden; start = undefined; });
  const animate = now => {
    start ??= now;
    if (visible) draw((now - start) / 1000);
    requestAnimationFrame(animate);
  };
  requestAnimationFrame(animate);
}
// Review controls can deterministically scrub the scene without reloading.
window.addEventListener('message', event => {
  if (event.origin !== location.origin) return;
  if (event.data?.type === 'seek') draw(event.data.time, event.data.still);
});
