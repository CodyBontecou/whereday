#!/usr/bin/env python3
"""Check the built site's crawlability, metadata, and local link targets."""
from collections import Counter
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
        self.canonicals = []
        self.descriptions = []
        self.h1s = 0
        self.title = ""
        self.in_title = False
        self.in_schema = False
        self.schema = ""
        self.errors = []

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
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

    checked_links = 0
    for path, page in pages.items():
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
    for error in errors:
        print(f"ERROR {error}")
    if errors:
        raise SystemExit(1)
    print(f"Passed: {len(pages)} pages, {checked_links} local targets, metadata, JSON-LD, IDs, and sitemap coverage.")


if __name__ == "__main__":
    check()
