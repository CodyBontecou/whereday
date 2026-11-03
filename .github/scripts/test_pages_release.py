import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import MagicMock, patch

spec = importlib.util.spec_from_file_location('release', Path(__file__).with_name('check-pages-release.py'))
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)

class ReleaseBoundaryTests(unittest.TestCase):
    def test_fetch_sends_release_user_agent_for_pages_and_assets(self):
        url = 'https://site.test/resource'
        for accept in (None, '*/*'):
            with self.subTest(accept=accept), patch.object(release, 'urlopen') as open_url:
                response = MagicMock()
                response.status = 200
                response.geturl.return_value = url
                response.headers = {'Content-Type': 'application/octet-stream'}
                response.read.return_value = b'verified resource'
                open_url.return_value.__enter__.return_value = response

                result = release.fetch(url) if accept is None else release.fetch(url, accept)

                self.assertEqual(result, (response.headers, b'verified resource'))
                open_url.assert_called_once()
                request = open_url.call_args.args[0]
                self.assertEqual(request.full_url, url)
                self.assertEqual(request.get_header('User-agent'), 'Pages-release-verification/1.0')
                self.assertEqual(request.get_header('Accept'), 'text/html' if accept is None else accept)
                self.assertEqual(open_url.call_args.kwargs, {'timeout': 30})

    def test_fetch_rejects_cross_origin_redirect_before_reading_body(self):
        url = 'https://site.test/resource'
        for redirected in ('https://other.test/resource', 'https://site.test:8443/resource'):
            with self.subTest(redirected=redirected), patch.object(release, 'urlopen') as open_url:
                response = MagicMock()
                response.status = 200
                response.geturl.return_value = redirected
                open_url.return_value.__enter__.return_value = response

                with self.assertRaisesRegex(ValueError, 'Unexpected cross-origin redirect'):
                    release.fetch(url)

                response.read.assert_not_called()

    def test_only_managed_beacon_is_ignored(self):
        beacon = '<script defer src="https://static.cloudflareinsights.com/beacon.min.js/test"></script>'
        self.assertEqual(release.normalize_html('<main>Body</main>' + beacon), '<main>Body</main>')
        other = '<script src="https://example.org/changed.js"></script>'
        self.assertIn(other, release.normalize_html(other))

    def test_sitemap_cannot_escape_origin_or_artifact(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'index.html').write_text('site')
            self.assertEqual(release.local_page(root, 'https://site.test/', 'https://site.test'), root / 'index.html')
            for url in ['https://other.test/', 'https://site.test/../outside', 'https://site.test/?q=secret']:
                with self.subTest(url=url), self.assertRaises(ValueError):
                    release.local_page(root, url, 'https://site.test')

if __name__ == '__main__':
    unittest.main()
