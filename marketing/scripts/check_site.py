#!/usr/bin/env python3
"""Check the built site's crawlability, metadata, and local link targets."""
from collections import Counter, deque
from html.parser import HTMLParser
import json
from pathlib import Path
import re
from urllib.parse import unquote, urljoin, urlsplit
import xml.etree.ElementTree as ET

PUBLIC = Path(__file__).resolve().parent.parent / "public"
BASE = "https://whereday.com"


class Page(HTMLParser):
    def __init__(self, path):
        super().__init__(convert_charrefs=True)
        self.path = path
        self.ids = []
        self.targets = []
        self.reader_links = []
        self.canonicals = []
        self.languages = []
        self.language_alternates = []
        self.language_switches = []
        self.descriptions = []
        self.h1s = 0
        self.title = ""
        self.in_title = False
        self.in_schema = False
        self.schema = ""
        self.errors = []

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == "html":
            self.languages.append(attrs.get("lang", ""))
        if tag == "link" and "alternate" in attrs.get("rel", "").split() and attrs.get("hreflang"):
            self.language_alternates.append((attrs["hreflang"], attrs.get("href", "")))
        if tag == "a" and attrs.get("hreflang"):
            self.language_switches.append((attrs["hreflang"], attrs.get("href", "")))
        if tag == "a" and attrs.get("href") and "nofollow" not in attrs.get("rel", "").lower().split():
            self.reader_links.append(attrs["href"])
        if "id" in attrs:
            self.ids.append(attrs["id"])
        if tag == "h1":
            self.h1s += 1
        if tag == "title":
            self.in_title = True
        if tag == "script" and attrs.get("type") == "application/ld+json":
            self.in_schema = True
            self.schema = ""
        if tag == "link" and attrs.get("rel") == "canonical":
            self.canonicals.append(attrs.get("href"))
        if tag == "meta" and attrs.get("name") == "description":
            self.descriptions.append(attrs.get("content", ""))
        for key in ("href", "src", "aria-labelledby", "aria-describedby"):
            value = attrs.get(key)
            if not value:
                continue
            if key.startswith("aria-"):
                self.targets.extend(("#" + target, "aria") for target in value.split())
            else:
                self.targets.append((value, key))
        if tag == "source" and attrs.get("srcset"):
            self.targets.extend((source.strip().split()[0], "srcset") for source in attrs["srcset"].split(","))

    def handle_data(self, text):
        if self.in_title:
            self.title += text
        if self.in_schema:
            self.schema += text

    def handle_endtag(self, tag):
        if tag == "title":
            self.in_title = False
        if tag == "script" and self.in_schema:
            try:
                json.loads(self.schema)
            except ValueError as error:
                self.errors.append(f"invalid JSON-LD: {error}")
            self.in_schema = False


def page_url(path):
    relative = path.relative_to(PUBLIC).as_posix()
    if relative == "index.html":
        return BASE + "/"
    if relative.endswith("/index.html"):
        return BASE + "/" + relative.removesuffix("index.html")
    return BASE + "/" + relative.removesuffix(".html")


def resolve_local(path):
    if path == "/api/v1/product":
        return PUBLIC / "api/v1/product.json"
    candidate = PUBLIC / unquote(path).lstrip("/")
    if not candidate.resolve().is_relative_to(PUBLIC.resolve()):
        return None
    if candidate.is_dir():
        candidate = candidate / "index.html"
    if candidate.is_file():
        return candidate
    candidate = candidate.with_suffix(".html")
    return candidate if candidate.is_file() else None


def reader_depths(pages):
    """Require published pages to be reachable from home through reader links.

    Canonicals, schema, sitemap entries, and links on the 404 page do not make
    an orphan discoverable through site navigation.
    """
    published = {path: page for path, page in pages.items() if path.name != "404.html"}
    graph = {}
    for path, page in published.items():
        destinations = set()
        for href in page.reader_links:
            url = urlsplit(urljoin(page_url(path), href))
            if url.scheme not in ("https", "http") or url.netloc != urlsplit(BASE).netloc:
                continue
            destination = resolve_local(url.path)
            if destination in published:
                destinations.add(page_url(destination))
        graph[page_url(path)] = destinations

    home = BASE + "/"
    if home not in graph:
        raise ValueError("reader navigation: missing homepage")
    depths = {home: 0}
    pending = deque([home])
    while pending:
        source = pending.popleft()
        for destination in graph[source]:
            if destination not in depths:
                depths[destination] = depths[source] + 1
                pending.append(destination)
    orphans = sorted(set(graph) - set(depths))
    if orphans:
        raise ValueError("reader navigation: pages unreachable from homepage: " + ", ".join(orphans))
    return depths


def check():
    pages = {}
    errors = []
    for path in sorted(PUBLIC.rglob("*.html")):
        page = Page(path)
        source = path.read_text()
        page.feed(source)
        pages[path] = page
        if re.search(r"__[A-Z][A-Z_]+__", source):
            page.errors.append("unresolved template placeholder")
        if len(page.languages) != 1 or not re.fullmatch(r"[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*", page.languages[0]):
            page.errors.append(f"expected one valid HTML language, found {page.languages}")
        if page.h1s != 1:
            page.errors.append(f"expected one H1, found {page.h1s}")
        if not page.title.strip():
            page.errors.append("missing title")
        if len(page.descriptions) != 1 or not page.descriptions[0].strip():
            page.errors.append("expected one nonempty meta description")
        if page.canonicals != [page_url(path)]:
            page.errors.append(f"canonical mismatch: {page.canonicals}")
        duplicates = [id_ for id_, count in Counter(page.ids).items() if count > 1]
        if duplicates:
            page.errors.append(f"duplicate IDs: {duplicates}")

    # Keep the already-published home-language pair in every release. A missing
    # locale or one-way alternate must fail the same build that deploy.sh uses.
    home_languages = {"/": "en", "/zh-hans/": "zh-Hans"}
    expected_alternates = {language: BASE + path for path, language in home_languages.items()}
    expected_alternates["x-default"] = BASE + "/"
    for public_path, language in home_languages.items():
        local_path = resolve_local(public_path)
        page = pages.get(local_path)
        if page is None:
            errors.append(f"home languages: missing required page {public_path}")
            continue
        if page.languages != [language]:
            page.errors.append(f"expected home language {language}")
        if len(page.language_alternates) != len(expected_alternates) or dict(page.language_alternates) != expected_alternates:
            page.errors.append("home languages: incomplete or nonreciprocal hreflang cluster")
        for other_path, other_language in home_languages.items():
            if other_path != public_path and not any(
                tag == other_language and urljoin(page_url(local_path), href) == BASE + other_path
                for tag, href in page.language_switches
            ):
                page.errors.append(f"home languages: missing reader language link to {other_path}")

    from blog_inventory import check as check_blog_inventory
    try:
        check_blog_inventory(PUBLIC)
    except ValueError as error:
        errors.append(str(error))

    checked_links = 0
    from campaign_links import app_url, CONFIG
    campaign_config = json.loads(CONFIG.read_text())
    for path, page in pages.items():
        public_path = page_url(path).removeprefix(BASE)
        download_links = [target for target, kind in page.targets
                          if kind == 'href' and urlsplit(target).hostname == 'apps.apple.com'
                          and 'id6761960794' in urlsplit(target).path]
        if public_path in campaign_config['campaigns'] and not download_links:
            page.errors.append('missing campaign download anchor')
        if any(target != app_url(public_path) for target in download_links):
            page.errors.append('download campaign does not match this page')
        for target, kind in page.targets:
            url = urlsplit(urljoin(page_url(path), target))
            if url.scheme not in ("https", "http") or url.netloc != urlsplit(BASE).netloc:
                continue
            checked_links += 1
            destination = resolve_local(url.path)
            if not destination:
                page.errors.append(f"missing {kind} target: {target}")
            elif url.fragment and destination in pages and unquote(url.fragment) not in pages[destination].ids:
                page.errors.append(f"missing fragment or ARIA target: {target}")
        errors.extend(f"{path.relative_to(PUBLIC)}: {error}" for error in page.errors)

    sitemap = ET.parse(PUBLIC / "sitemap.xml")
    listed = [element.text for element in sitemap.findall(".//{*}loc")]
    expected = {page_url(path) for path in pages if path.name != "404.html"}
    if len(listed) != len(set(listed)):
        errors.append("sitemap: duplicate URLs")
    if set(listed) != expected:
        errors.append(f"sitemap mismatch; missing: {expected - set(listed)}, extra: {set(listed) - expected}")
    depths = {}
    try:
        depths = reader_depths(pages)
    except ValueError as error:
        errors.append(str(error))
    for error in errors:
        print(f"ERROR {error}")
    if errors:
        raise SystemExit(1)
    print(f"Passed: {len(pages)} pages, {checked_links} local targets, metadata, JSON-LD, IDs, and sitemap coverage.")
    print(f"Reader navigation: {len(depths)} published pages reachable; maximum depth {max(depths.values())}.")


if __name__ == "__main__":
    check()
