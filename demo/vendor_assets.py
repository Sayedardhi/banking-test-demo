#!/usr/bin/env python3
"""Cache the upstream page's browser assets for reliable local rehearsals."""
from pathlib import Path
import re
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT / 'src/frontend/static/vendor'
ASSETS = {
    'bootstrap-material-design.min.css': 'https://unpkg.com/bootstrap-material-design@4.1.1/dist/css/bootstrap-material-design.min.css',
    'jquery.slim.min.js': 'https://code.jquery.com/jquery-3.2.1.slim.min.js',
    'popper.min.js': 'https://unpkg.com/popper.js@1.12.6/dist/umd/popper.min.js',
    'bootstrap-material-design.js': 'https://unpkg.com/bootstrap-material-design@4.1.1/dist/js/bootstrap-material-design.js',
    'roboto.css': 'https://fonts.googleapis.com/css2?family=Roboto:wght@400;500;700&display=swap',
    'material-icons.css': 'https://fonts.googleapis.com/icon?family=Material+Icons',
}

def download(url):
    with urlopen(Request(url, headers={'User-Agent':'Mozilla/5.0'}), timeout=30) as response:
        return response.read()

def main():
    DEST.mkdir(parents=True, exist_ok=True)
    for name, url in ASSETS.items():
        print('Caching ' + name, flush=True)
        body = download(url)
        if name in ('roboto.css', 'material-icons.css'):
            css = body.decode()
            for i, font_url in enumerate(dict.fromkeys(re.findall(r'url\((https://[^)]+)\)', css))):
                suffix = '.woff2' if '.woff2' in font_url else '.ttf'
                filename = name.replace('.css','') + '-' + str(i) + suffix
                (DEST / filename).write_bytes(download(font_url))
                css = css.replace(font_url, filename)
            body = css.encode()
        (DEST / name).write_bytes(body)
    head = ROOT / 'src/frontend/templates/shared/html_head.html'
    body = head.read_text()
    for name in ['bootstrap-material-design.min.css', 'roboto.css', 'material-icons.css']:
        body = body.replace(ASSETS[name], 'static/vendor/' + name)
    body = body.replace('    <link rel="preconnect" href="https://fonts.gstatic.com">\n', '')
    head.write_text(body)
    scripts = ROOT / 'src/frontend/templates/shared/scripts.html'
    body = scripts.read_text()
    for name in ['jquery.slim.min.js', 'bootstrap-material-design.js']:
        body = body.replace(ASSETS[name], 'static/vendor/' + name)
    body = body.replace('https://unpkg.com/popper.js@1.12.6/dist/umd/popper.js', 'static/vendor/popper.min.js')
    # The vendored minified Popper file differs from upstream's unminified SRI target.
    body = re.sub(r' integrity="sha384-fA23[^\"]+"', '', body)
    scripts.write_text(body)
    (DEST / 'SOURCES.txt').write_text('\n'.join(f'{name}: {url}' for name,url in ASSETS.items()) + '\n\nBrowser assets from the upstream Google sample. Original notices are retained in downloaded files.\n')

if __name__ == '__main__':
    main()
