"""Share resource navigation across English templates, with native mobile disclosure."""
from html import escape, unescape
from html.parser import HTMLParser
from pathlib import Path
import re

PUBLIC = Path(__file__).resolve().parent.parent / 'public'
LINKS = [('/blog/', 'Guides'), ('/tools/', 'Tools'), ('/compare/', 'Compare'), ('/docs/', 'Docs'), ('/#pricing', 'Pricing')]

class Anchor(HTMLParser):
    def __init__(self, html):
        super().__init__(convert_charrefs=True)
        self.attrs = {}
        self.feed(html)
    def handle_starttag(self, tag, attrs):
        if tag == 'a': self.attrs = dict(attrs)


def enhance_navigation(content, path):
    if not re.search(r'<html\b[^>]*\blang=["\']en["\']', content):
        return content
    pattern = re.compile(r'<nav\b(?=[^>]*\bclass=["\'][^"\']*\b(?:top|site-nav)\b)[^>]*>(.*?)</nav>', re.S)
    matches = list(pattern.finditer(content))
    if len(matches) != 1:
        raise ValueError(f'Expected one English primary navigation: {path}')
    old = matches[0].group(0)
    anchors = [(html, Anchor(html).attrs) for html in re.findall(r'<a\b[^>]*>.*?</a>', old, re.S)]
    brand = [html for html, attrs in anchors if 'brand' in attrs.get('class', '').split()]
    download = [html for html, attrs in anchors if 'https://apps.apple.com/' in attrs.get('href', '') and set(attrs.get('class', '').split()) & {'btn', 'button'}]
    if len(brand) != 1 or len(download) != 1:
        raise ValueError(f'Expected one brand and download link: {path}')
    def link(href, label):
        current = ''
        if href.endswith('/') and path.startswith(href):
            current = ' aria-current="page"' if path == href else ' aria-current="true"'
        return f'<a href="{escape(href, quote=True)}"{current}>{escape(label)}</a>'
    primary = ''.join(link(href, label) for href, label in LINKS)
    # Retain existing page shortcuts in the mobile disclosure.
    # Rebuilding a previously enhanced page must not duplicate them.
    extras, seen = [], {href for href, _ in LINKS}
    for html, attrs in anchors:
        if html in brand or html in download: continue
        href = attrs.get('href', '')
        normalized = '/'+href if href.startswith('#') else href
        if not href or normalized in seen: continue
        seen.add(normalized)
        label = unescape(re.sub(r'<[^>]+>', '', html)).strip()
        extras.append(link(normalized, label))
    links = '<div class="nav-links"><div class="desktop-navigation">'+primary+'</div><details class="mobile-navigation"><summary>Explore</summary><div class="mobile-navigation-links">'+primary+''.join(extras)+'</div></details>'+download[0]+'</div>'
    top = bool(re.search(r'class=["\']top(?:\s|["\'])', old))
    inner = brand[0]+links
    if top: inner = '<div class="nav-inner">'+inner+'</div>'
    nav = '<nav class="'+('top' if top else 'site-nav')+' shared-navigation" aria-label="Primary navigation">'+inner+'</nav>'
    content = content[:matches[0].start()]+nav+content[matches[0].end():]
    css = '<style id="shared-navigation-styles">'+(PUBLIC/'assets/navigation.css').read_text()+'</style>'
    if 'id="shared-navigation-styles"' in content:
        content = re.sub(r'<style id="shared-navigation-styles">.*?</style>', lambda _: css, content, flags=re.S)
    else: content = content.replace('</head>', css+'\n</head>')
    script = '<script id="shared-navigation-script" type="module" src="/assets/navigation.mjs?v=20261030"></script>'
    if 'id="shared-navigation-script"' not in content: content = content.replace('</body>', script+'\n</body>')
    return content
