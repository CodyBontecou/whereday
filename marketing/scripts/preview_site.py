#!/usr/bin/env python3
"""Preview static Pages content with clean legal URLs and HTTP compression."""
import argparse
import gzip
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from io import BytesIO
from pathlib import Path
from urllib.parse import urlsplit

PUBLIC = Path(__file__).resolve().parent.parent / 'public'


class Handler(SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(PUBLIC), **kwargs)

    def do_GET(self):
        url = urlsplit(self.path)
        if url.path in ('/privacy', '/terms'):
            self.path = url.path + '.html' + ('?' + url.query if url.query else '')
        super().do_GET()

    def send_head(self):
        path = Path(self.translate_path(self.path))
        if path.is_dir():
            path = path / 'index.html'
        if (path.is_file() and path.suffix in ('.html', '.css', '.xml', '.txt')
                and 'gzip' in self.headers.get('Accept-Encoding', '')):
            data = gzip.compress(path.read_bytes())
            self.send_response(200)
            self.send_header('Content-Type', self.guess_type(str(path)))
            self.send_header('Content-Encoding', 'gzip')
            self.send_header('Content-Length', str(len(data)))
            self.send_header('Vary', 'Accept-Encoding')
            self.end_headers()
            return BytesIO(data)
        return super().send_head()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port', type=int, default=8767)
    args = parser.parse_args()
    print(f'Preview: http://127.0.0.1:{args.port}', flush=True)
    ThreadingHTTPServer(('127.0.0.1', args.port), Handler).serve_forever()
