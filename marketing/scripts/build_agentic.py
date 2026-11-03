#!/usr/bin/env python3
"""Build factual trust pages and Markdown representations of public content."""
from html.parser import HTMLParser
import json
from pathlib import Path
import re
from urllib.parse import urljoin

from build_seo_pages import render
from check_site import BASE, PUBLIC, page_url
from product_contract import build_product_contract
from campaign_links import update_download_links
from build_navigation import enhance_navigation

TRUST = [
    {
        'path': '/about', 'section': 'About', 'section_path': '/about',
        'title': 'About Whereday — Private iPhone Location Journal',
        'headline': 'About Whereday',
        'description': 'Meet Whereday, the open source iPhone and Apple Watch location journal built by Cody Bontecou at isolated.tech.',
        'lede': 'A location journal you can keep on your own device, built by Cody Bontecou at isolated.tech.',
        'content': '''<section><h2>Why Whereday exists</h2><p>Whereday records the places you visit and the routes you choose to track, then lets you review that history on your iPhone. It is an independent, open source project built by <a href="https://isolated.tech">Cody Bontecou at isolated.tech</a>. The goal is a useful personal journal with portable files and understandable controls.</p><p>The app requires no account. Your journal stays on your device; Whereday does not operate a server archive of your location history. Apple geocoding and Maps services, optional webhook delivery, first-party onboarding analytics, and export scheduling have specific network behavior described in the <a href="/privacy">privacy policy</a>.</p></section><section><h2>What you can use</h2><p>From version 1.8, new users need an eligible trial, subscription, or Lifetime purchase to track. Existing users keep free tracking and prior purchase rights. Supported import and Export Preview are free. New users from 1.8 retain exports after tracking access expires; existing free users keep their previous export-unlock requirement. Check the <a href="/#pricing">current pricing details</a> and the App Store purchase sheet for your storefront.</p><p>Read the <a href="/docs/">product documentation</a>, inspect the <a href="https://github.com/CodyBontecou/whereday">source code under the AGPL v3 license</a>, or <a href="/contact">contact the developer</a> with a question. This website documents the native app; its <a href="/api/v1/product">public product metadata endpoint</a> describes capabilities and documentation links, with a <a href="/openapi.json">read-only OpenAPI contract</a>. It cannot access your private journal.</p></section>''',
    },
    {
        'path': '/contact', 'section': 'About', 'section_path': '/about', 'breadcrumb': 'Contact',
        'title': 'Contact Whereday — Support and Project Links',
        'headline': 'Contact Whereday',
        'description': 'Contact Whereday developer Cody Bontecou for support, bug reports, and questions about the private iPhone location journal.',
        'lede': 'Get help with Whereday or talk to the developer about the project.',
        'content': '''<section><h2>Support by email</h2><p>Email <a href="mailto:cody.bontecou@gmail.com">cody.bontecou@gmail.com</a> to reach Cody Bontecou, the developer of Whereday. Describe what you were trying to do, what happened, the app version, and your iOS or watchOS version. If a screenshot helps explain the issue, remove personal details before sharing it.</p><p>You can ask for help without sending your location records, precise coordinates, private export files, or a full personal journal. If a small example becomes useful during troubleshooting, use invented locations and share only the part needed to reproduce the behavior. This is a support channel, so a response time is not guaranteed.</p></section><section><h2>Public project conversations</h2><p>Report reproducible bugs in the <a href="https://github.com/CodyBontecou/whereday/issues">GitHub issue tracker</a>. For feature ideas and general project questions, use <a href="https://github.com/CodyBontecou/whereday/discussions">GitHub Discussions</a>. Posts there are public, so remove sensitive information before posting.</p><p>For setup and product questions, start with the <a href="/docs/">documentation</a>. The <a href="/privacy">privacy policy</a> explains data handling, and the <a href="/terms">terms</a> describe purchases and use of the app. App Store purchases and refunds are processed through Apple; do not send payment credentials by email.</p></section>''',
    },
    {
        'path': '/404', 'section': 'Help', 'section_path': '/docs/', 'breadcrumb': 'Page not found',
        'title': 'Page Not Found — Whereday', 'headline': 'This page could not be found.',
        'description': 'Find Whereday documentation, product information, and support when a page is missing.',
        'lede': 'The address may be incomplete, or the page may have moved.',
        'content': '<section><h2>Find your next step</h2><p>Visit the <a href="/">Whereday homepage</a>, browse the <a href="/docs/">documentation</a>, or check the <a href="/sitemap.xml">site map</a>. Agents can start with <a href="/llms.txt">the agent guide</a>. If a link from this website brought you here, <a href="/contact">let the developer know</a>.</p></section>',
    },
]


class Markdown(HTMLParser):
    def __init__(self, url):
        super().__init__(convert_charrefs=True)
        self.url, self.parts, self.links = url, [], []
        self.active = False
        self.skip = 0
        self.pre = False

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == 'main':
            self.active = True
        if not self.active:
            return
        if tag in ('script', 'style', 'svg'):
            self.skip += 1
        if self.skip:
            return
        if re.fullmatch('h[1-6]', tag):
            self.parts.append('\n\n' + '#' * int(tag[1]) + ' ')
        elif tag in ('p', 'div', 'section', 'article', 'figure', 'header', 'nav', 'table'):
            self.parts.append('\n\n')
        elif tag == 'li':
            self.parts.append('\n- ')
        elif tag == 'a':
            self.links.append(urljoin(self.url, attrs.get('href', '')))
            self.parts.append('[')
        elif tag == 'img' and attrs.get('alt'):
            self.parts.append(f"\n\n![{attrs['alt']}]({urljoin(self.url, attrs.get('src', ''))})\n\n")
        elif tag == 'pre':
            self.pre = True
            self.pre_fence_index = len(self.parts)
            self.parts.append('')
        elif tag == 'code' and not self.pre:
            self.parts.append('`')
        elif tag == 'br':
            self.parts.append('\n')
        elif tag == 'tr':
            self.parts.append('\n')
        elif tag in ('td', 'th'):
            self.parts.append(' | ')

    def handle_endtag(self, tag):
        if not self.active:
            return
        if self.skip:
            if tag in ('script', 'style', 'svg'):
                self.skip -= 1
            return
        if tag == 'main':
            self.active = False
        elif tag == 'a' and self.links:
            self.parts.append('](' + self.links.pop() + ')')
        elif tag == 'pre':
            content = ''.join(self.parts[self.pre_fence_index + 1:])
            # A displayed Markdown block can itself contain code fences.
            # Its outer fence must be longer so consumers retain the example.
            runs = [len(run) for run in re.findall(r'`+', content)]
            fence = '`' * max(3, max(runs, default=0) + 1)
            self.parts[self.pre_fence_index] = '\n\n' + fence + '\n'
            self.parts.append('\n' + fence + '\n\n')
            self.pre = False
        elif tag == 'code' and not self.pre:
            self.parts.append('`')
        elif tag in ('p', 'div', 'section', 'article', 'figure', 'header', 'nav', 'table') or re.fullmatch('h[1-6]', tag):
            self.parts.append('\n\n')

    def handle_data(self, text):
        if self.active and not self.skip:
            self.parts.append(text if self.pre else re.sub(r'\s+', ' ', text))

    def result(self):
        text = ''.join(self.parts)
        text = re.sub(r'\n[ \t]+', '\n', text)
        return '\n'.join(line.rstrip() for line in re.sub(r'\n{3,}', '\n\n', text).strip().splitlines()) + '\n\nSource: ' + self.url + '\n'


def build():
    build_product_contract()
    for page in TRUST:
        content = render(page)
        if page['path'] == '/404':
            content = content.replace('content="index,follow"', 'content="noindex,follow"')
        (PUBLIC / (page['path'].lstrip('/') + '.html')).write_text(content)
    update_download_links()
    for source in sorted(PUBLIC.rglob('*.html')):
        original = source.read_text()
        enhanced = enhance_navigation(original, page_url(source).removeprefix(BASE))
        if enhanced != original:
            source.write_text(enhanced)
    manifest = {}
    for source in sorted(PUBLIC.rglob('*.html')):
        if source.name == '404.html':
            continue
        url = page_url(source)
        parser = Markdown(url)
        parser.feed(source.read_text())
        markdown = parser.result()
        if len(markdown) < 100:
            raise ValueError(f'Missing public content in {source}')
        target = PUBLIC / '_markdown' / source.relative_to(PUBLIC).with_suffix('.md')
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(markdown)
        manifest[url.removeprefix(BASE)] = '/' + target.relative_to(PUBLIC).as_posix()
    worker = (PUBLIC.parent / 'worker.js').read_text().replace('__PAGE_MARKDOWN__', json.dumps(manifest, indent=2))
    (PUBLIC / '_worker.js').write_text(worker)
    print(f'Built {len(manifest)} Markdown pages, trust pages, and the Pages response handler.')


if __name__ == '__main__':
    build()
