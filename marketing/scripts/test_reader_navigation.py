"""Guard against sitemap-only pages and disconnected navigation groups."""
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

import check_site


class ReaderNavigationTests(unittest.TestCase):
    def setUp(self):
        self.temp = TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.public = Path(self.temp.name)
        self.override = patch.object(check_site, "PUBLIC", self.public)
        self.override.start()
        self.addCleanup(self.override.stop)
        self.pages = {}

    def page(self, relative, source):
        path = self.public / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(source)
        page = check_site.Page(path)
        page.feed(source)
        self.pages[path] = page

    def test_relative_and_absolute_reader_links_reach_guide(self):
        self.page("index.html", '<a href="/docs/">Docs</a>')
        self.page("docs/index.html", '<a href="https://whereday.com/docs/export/#sample">Export</a>')
        self.page("docs/export/index.html", '<a href="../">Back</a>')
        depths = check_site.reader_depths(self.pages)
        self.assertEqual(depths[check_site.BASE + "/docs/export/"], 2)

    def test_canonical_alternate_and_schema_do_not_rescue_orphan(self):
        self.page("index.html", '<link rel="alternate" href="/orphan/">'
                  '<script type="application/ld+json">{"url":"/orphan/"}</script>')
        self.page("orphan/index.html", '<link rel="canonical" href="/orphan/">')
        with self.assertRaisesRegex(ValueError, "unreachable.*orphan"):
            check_site.reader_depths(self.pages)

    def test_disconnected_cycle_remains_unreachable(self):
        self.page("index.html", '<a href="/">Home</a>')
        self.page("first/index.html", '<a href="/second/">Second</a>')
        self.page("second/index.html", '<a href="/first/">First</a>')
        with self.assertRaisesRegex(ValueError, "unreachable.*first.*second"):
            check_site.reader_depths(self.pages)

    def test_404_and_nofollow_links_do_not_rescue_orphan(self):
        self.page("index.html", '<a rel="external NOFOLLOW" href="/orphan/">Orphan</a>')
        self.page("404.html", '<a href="/orphan/">Recover</a>')
        self.page("orphan/index.html", '<a href="/">Home</a>')
        with self.assertRaisesRegex(ValueError, "unreachable.*orphan"):
            check_site.reader_depths(self.pages)


if __name__ == "__main__":
    unittest.main()
