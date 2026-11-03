// Native details remains usable without JavaScript. Enhance dismissal only; no network or storage.
const menu = document.querySelector('.mobile-navigation');
if (menu) {
  const summary = menu.querySelector('summary');
  menu.addEventListener('click', event => { if (event.target.closest('a')) menu.open = false; });
  document.addEventListener('click', event => { if (menu.open && !menu.contains(event.target)) menu.open = false; });
  document.addEventListener('keydown', event => {
    if (event.key === 'Escape' && menu.open) { menu.open = false; summary.focus(); }
  });
  matchMedia('(max-width: 960px)').addEventListener('change', event => { if (!event.matches) menu.open = false; });
}
