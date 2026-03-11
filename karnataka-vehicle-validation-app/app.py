from __future__ import annotations

from dataclasses import dataclass
import sys
from html import escape
from html.parser import HTMLParser
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from typing import Optional
from urllib.parse import parse_qs, urljoin

import requests
import urllib3

urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)

BASE_DIR = Path(getattr(sys, '_MEIPASS', Path(__file__).resolve().parent))
STATIC_DIR = BASE_DIR / 'static'
SOURCE_URL = 'https://etc.karnataka.gov.in/ReportingUser/Scgr1.aspx'
HOST = '127.0.0.1'
PORT = 8000

FUEL_OPTIONS = {
    'P': {'label': 'Petrol', 'table_id': 'GridView1'},
    'D': {'label': 'Diesel', 'table_id': 'GridView2'},
}

MANIFEST_JSON = """{
  \"name\": \"KA-Vehicle-PUC-Check\",
  \"short_name\": \"KA-Vehicle-PUC-Check\",
  \"start_url\": \"/\",
  \"display\": \"standalone\",
  \"background_color\": \"#eef4ef\",
  \"theme_color\": \"#155e4b\",
  \"description\": \"Check Karnataka vehicle validation records with automatic petrol and diesel fallback.\",
  \"icons\": [
    {
      \"src\": \"/static/icon-192.png\",
      \"sizes\": \"192x192\",
      \"type\": \"image/png\"
    },
    {
      \"src\": \"/static/icon-512.png\",
      \"sizes\": \"512x512\",
      \"type\": \"image/png\"
    }
  ]
}"""

SERVICE_WORKER_JS = """
const CACHE_NAME = 'karnataka-vehicle-checker-v1';
const APP_SHELL = [
  '/',
  '/manifest.webmanifest',
  '/static/icon-192.png',
  '/static/icon-512.png'
];

self.addEventListener('install', (event) => {
  event.waitUntil(caches.open(CACHE_NAME).then((cache) => cache.addAll(APP_SHELL)));
  self.skipWaiting();
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((keys) => Promise.all(keys.filter((key) => key !== CACHE_NAME).map((key) => caches.delete(key))))
  );
  self.clients.claim();
});

self.addEventListener('fetch', (event) => {
  if (event.request.method !== 'GET') {
    return;
  }

  event.respondWith(
    caches.match(event.request).then((cached) => {
      if (cached) {
        return cached;
      }
      return fetch(event.request)
        .then((response) => {
          if (!response || response.status !== 200 || response.type !== 'basic') {
            return response;
          }
          const copy = response.clone();
          caches.open(CACHE_NAME).then((cache) => cache.put(event.request, copy));
          return response;
        })
        .catch(() => caches.match('/'));
    })
  );
});
""".strip()


@dataclass
class LookupResult:
    fuel_code: str
    fuel_label: str
    headers: list[str]
    rows: list[list[dict[str, str]]]

    @property
    def found(self) -> bool:
        return bool(self.rows)

    @property
    def count(self) -> int:
        return len(self.rows)


class ResultTableParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__()
        self._active_table: Optional[str] = None
        self._headers: list[str] = []
        self._rows: list[list[dict[str, str]]] = []
        self._current_row: list[dict[str, str]] = []
        self._cell_text: list[str] = []
        self._cell_href = ''
        self._cell_tag: Optional[str] = None

    @property
    def result(self) -> tuple[list[str], list[list[dict[str, str]]]]:
        return self._headers, self._rows

    def handle_starttag(self, tag: str, attrs: list[tuple[str, Optional[str]]]) -> None:
        attr_map = {key: value or '' for key, value in attrs}
        if tag == 'table' and attr_map.get('id') in {item['table_id'] for item in FUEL_OPTIONS.values()}:
            self._active_table = attr_map['id']
            return
        if not self._active_table:
            return
        if tag == 'tr':
            self._current_row = []
            return
        if tag in {'th', 'td'}:
            self._cell_tag = tag
            self._cell_text = []
            self._cell_href = ''
            return
        if tag == 'a' and self._cell_tag == 'td':
            self._cell_href = attr_map.get('href', '')

    def handle_data(self, data: str) -> None:
        if self._active_table and self._cell_tag:
            self._cell_text.append(data)

    def handle_endtag(self, tag: str) -> None:
        if not self._active_table:
            return
        if tag in {'th', 'td'} and self._cell_tag == tag:
            text = ' '.join(part.strip() for part in self._cell_text if part.strip())
            cell = {'text': text, 'href': self._cell_href}
            if tag == 'th':
                self._headers.append(text)
            else:
                self._current_row.append(cell)
            self._cell_tag = None
            self._cell_text = []
            self._cell_href = ''
            return
        if tag == 'tr' and self._current_row:
            self._rows.append(self._current_row)
            self._current_row = []
            return
        if tag == 'table':
            self._active_table = None


def normalize_registration(value: str) -> str:
    return ''.join(character for character in value.upper() if character.isalnum())


def pick_hidden_value(html: str, name: str) -> str:
    marker = f'name="{name}" id="{name}" value="'
    start = html.index(marker) + len(marker)
    end = html.index('"', start)
    return html[start:end]


def fetch_table(registration: str, fuel_code: str) -> LookupResult:
    last_error: Optional[requests.RequestException] = None

    for _ in range(3):
        session = requests.Session()
        initial = session.get(SOURCE_URL, verify=False, timeout=30)
        initial.raise_for_status()
        html = initial.text

        payload = {
            '__VIEWSTATE': pick_hidden_value(html, '__VIEWSTATE'),
            '__VIEWSTATEGENERATOR': pick_hidden_value(html, '__VIEWSTATEGENERATOR'),
            '__EVENTVALIDATION': pick_hidden_value(html, '__EVENTVALIDATION'),
            'Sreg': registration,
            'Veh_Type': fuel_code,
            'Button1': 'Search',
        }

        try:
            response = session.post(SOURCE_URL, data=payload, verify=False, timeout=30)
            response.raise_for_status()
        except requests.RequestException as exc:
            last_error = exc
            continue

        parser = ResultTableParser()
        parser.feed(response.text)
        headers, rows = parser.result

        target_table = FUEL_OPTIONS[fuel_code]['table_id']
        if target_table not in response.text:
            headers = []
            rows = []

        return LookupResult(
            fuel_code=fuel_code,
            fuel_label=FUEL_OPTIONS[fuel_code]['label'],
            headers=headers,
            rows=rows,
        )

    if last_error is not None:
        raise last_error

    return LookupResult(
        fuel_code=fuel_code,
        fuel_label=FUEL_OPTIONS[fuel_code]['label'],
        headers=[],
        rows=[],
    )


def run_lookup(registration: str) -> dict[str, object]:
    normalized = normalize_registration(registration)
    attempts: list[LookupResult] = []

    for fuel_code in ('P', 'D'):
        result = fetch_table(normalized, fuel_code)
        attempts.append(result)
        if result.found:
            return {
                'registration': normalized,
                'matched': result,
                'attempts': attempts,
            }

    return {
        'registration': normalized,
        'matched': None,
        'attempts': attempts,
    }


def render_rows(headers: list[str], rows: list[list[dict[str, str]]]) -> str:
    header_html = ''.join(f'<th>{escape(header)}</th>' for header in headers)
    body_parts: list[str] = []
    for row in rows:
        cells: list[str] = []
        for index, cell in enumerate(row):
            text = escape(cell['text'])
            href = cell['href']
            if index == 0 and href:
                absolute = escape(urljoin(SOURCE_URL, href), quote=True)
                text = f'<a href="{absolute}" target="_blank" rel="noreferrer">{text}</a>'
            cells.append(f'<td>{text}</td>')
        body_parts.append(f"<tr>{''.join(cells)}</tr>")
    return (
        '<div class="table-wrap">'
        '<table class="results-table">'
        f'<thead><tr>{header_html}</tr></thead>'
        f'<tbody>{''.join(body_parts)}</tbody>'
        '</table>'
        '</div>'
    )


def render_page(registration: str = '', error: str = '', lookup: Optional[dict[str, object]] = None) -> bytes:
    safe_registration = escape(registration)
    error_html = f'<p class="error">{escape(error)}</p>' if error else ''
    result_html = ''

    if lookup is not None:
        matched = lookup['matched']
        attempts = lookup['attempts']
        checked = ' -> '.join(result.fuel_label for result in attempts)
        if matched:
            matched_result = matched
            first_row = matched_result.rows[0]
            summary_map = {
                matched_result.headers[index]: first_row[index]['text']
                for index in range(min(len(matched_result.headers), len(first_row)))
            }
            top_result = escape(summary_map.get('Result', 'Available'))
            valid_date = escape(summary_map.get('ValidDate', 'Not listed'))
            result_html = f"""
            <section class="result-card success">
              <div class="result-head">
                <div>
                  <p class="eyebrow">Lookup Complete</p>
                  <h2>{escape(lookup['registration'])}</h2>
                </div>
                <div class="pill">Matched as {escape(matched_result.fuel_label)}</div>
              </div>
              <div class="summary-grid">
                <article>
                  <span>Records Found</span>
                  <strong>{matched_result.count}</strong>
                </article>
                <article>
                  <span>Top Result</span>
                  <strong>{top_result}</strong>
                </article>
                <article>
                  <span>Valid Date</span>
                  <strong>{valid_date}</strong>
                </article>
              </div>
              <p class="trace">Checked in order: {escape(checked)}</p>
              <p class="trace">Source: <a href="{escape(SOURCE_URL, quote=True)}" target="_blank" rel="noreferrer">Transport Department, Karnataka</a></p>
              {render_rows(matched_result.headers, matched_result.rows)}
            </section>
            """
        else:
            result_html = f"""
            <section class="result-card empty">
              <p class="eyebrow">No Match Found</p>
              <h2>{escape(lookup['registration'])}</h2>
              <p>No records were returned by the Karnataka Transport page for either Petrol or Diesel.</p>
              <p class="trace">Checked in order: {escape(checked)}</p>
              <p class="trace">Source: <a href="{escape(SOURCE_URL, quote=True)}" target="_blank" rel="noreferrer">Transport Department, Karnataka</a></p>
            </section>
            """

    html = f"""
    <!doctype html>
    <html lang="en">
    <head>
      <meta charset="utf-8">
      <meta name="viewport" content="width=device-width, initial-scale=1">
      <meta name="theme-color" content="#155e4b">
      <meta name="apple-mobile-web-app-capable" content="yes">
      <meta name="apple-mobile-web-app-status-bar-style" content="default">
      <meta name="apple-mobile-web-app-title" content="KA-Vehicle-PUC-Check">
      <link rel="manifest" href="/manifest.webmanifest">
      <link rel="icon" href="/static/icon-192.png">
      <title>KA-Vehicle-PUC-Check</title>
      <style>
        :root {{
          --bg: #eef4ef;
          --panel: rgba(255,255,255,0.88);
          --panel-strong: #ffffff;
          --text: #173126;
          --muted: #557267;
          --accent: #155e4b;
          --accent-soft: #dff3e8;
          --error: #b42318;
          --line: rgba(21, 94, 75, 0.14);
          --shadow: 0 24px 60px rgba(24, 65, 51, 0.16);
        }}
        * {{ box-sizing: border-box; }}
        body {{
          margin: 0;
          font-family: Georgia, 'Times New Roman', serif;
          color: var(--text);
          background:
            radial-gradient(circle at top left, rgba(110, 180, 140, 0.26), transparent 32%),
            radial-gradient(circle at top right, rgba(21, 94, 75, 0.18), transparent 24%),
            linear-gradient(180deg, #f6fbf7 0%, var(--bg) 100%);
          min-height: 100vh;
        }}
        .shell {{ width: min(1120px, calc(100% - 32px)); margin: 0 auto; padding: 40px 0 56px; }}
        .hero {{
          background: linear-gradient(135deg, rgba(255,255,255,0.92), rgba(236, 247, 241, 0.84));
          border: 1px solid rgba(255,255,255,0.8);
          border-radius: 32px;
          padding: 32px;
          box-shadow: var(--shadow);
          backdrop-filter: blur(12px);
        }}
        .eyebrow {{ margin: 0 0 10px; letter-spacing: 0.18em; text-transform: uppercase; font-size: 0.74rem; color: var(--muted); }}
        h1, h2 {{ margin: 0; font-weight: 600; }}
        h1 {{ font-size: clamp(2.4rem, 5vw, 4.6rem); line-height: 0.94; max-width: 10ch; }}
        .hero p {{ max-width: 60ch; color: var(--muted); font-size: 1.05rem; }}
        form {{ margin-top: 28px; }}
        .form-panel {{ display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 14px; align-items: end; }}
        label {{ display: block; font-size: 0.95rem; margin-bottom: 10px; color: var(--muted); }}
        input {{ width: 100%; border: 1px solid var(--line); border-radius: 16px; padding: 18px 20px; font-size: 1rem; color: var(--text); background: var(--panel-strong); }}
        input:focus {{ outline: 2px solid rgba(21, 94, 75, 0.24); border-color: var(--accent); }}
        button {{ border: 0; border-radius: 16px; padding: 18px 26px; font-size: 1rem; font-weight: 600; cursor: pointer; color: #fff; background: linear-gradient(135deg, #155e4b, #0f766e); box-shadow: 0 14px 30px rgba(21, 94, 75, 0.24); }}
        button:hover {{ transform: translateY(-1px); }}
        .secondary-button {{ background: rgba(21, 94, 75, 0.08); color: var(--accent); box-shadow: none; display:none; }}
        .hint {{ margin-top: 12px; color: var(--muted); font-size: 0.95rem; }}
        .error {{ background: rgba(180, 35, 24, 0.08); color: var(--error); border: 1px solid rgba(180, 35, 24, 0.18); padding: 14px 16px; border-radius: 14px; }}
        .cta-row {{ display:flex; gap:12px; flex-wrap:wrap; margin-top: 16px; }}
        .result-card {{ margin-top: 24px; border-radius: 28px; padding: 28px; background: var(--panel); box-shadow: var(--shadow); border: 1px solid rgba(255,255,255,0.7); }}
        .result-card.success {{ background: linear-gradient(180deg, rgba(255,255,255,0.95), rgba(235, 249, 241, 0.92)); }}
        .result-card.empty {{ background: linear-gradient(180deg, rgba(255,255,255,0.95), rgba(255, 244, 236, 0.92)); }}
        .result-head {{ display: flex; justify-content: space-between; gap: 16px; align-items: start; }}
        .pill {{ background: var(--accent-soft); color: var(--accent); border-radius: 999px; padding: 10px 14px; font-size: 0.94rem; white-space: nowrap; }}
        .summary-grid {{ display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 14px; margin: 22px 0 16px; }}
        .summary-grid article {{ background: rgba(255,255,255,0.82); border: 1px solid var(--line); border-radius: 18px; padding: 16px; }}
        .summary-grid span {{ display: block; color: var(--muted); margin-bottom: 8px; font-size: 0.9rem; }}
        .summary-grid strong {{ font-size: 1.45rem; }}
        .trace {{ color: var(--muted); margin: 8px 0; }}
        .table-wrap {{ overflow-x: auto; margin-top: 18px; border-radius: 18px; border: 1px solid var(--line); }}
        .results-table {{ width: 100%; border-collapse: collapse; background: rgba(255,255,255,0.92); min-width: 860px; }}
        .results-table th, .results-table td {{ padding: 14px 16px; text-align: left; border-bottom: 1px solid rgba(21, 94, 75, 0.08); font-size: 0.95rem; }}
        .results-table thead {{ background: rgba(21, 94, 75, 0.08); }}
        .results-table a {{ color: var(--accent); text-decoration: none; font-weight: 600; }}
        @media (max-width: 780px) {{
          .shell {{ width: min(100% - 18px, 100%); padding: 18px 0 30px; }}
          .hero, .result-card {{ padding: 20px; border-radius: 24px; }}
          .form-panel, .summary-grid {{ grid-template-columns: 1fr; }}
          .result-head {{ flex-direction: column; }}
          button {{ width: 100%; }}
          .cta-row {{ flex-direction: column; }}
        }}
      </style>
    </head>
    <body>
      <main class="shell">
        <section class="hero">
          <p class="eyebrow">Welcome</p>
          <h1>KA-Vehicle-PUC-Check</h1>
          <p>Enter a Karnataka vehicle registration number and the app will search the Transport Department page automatically. It checks Petrol first, falls back to Diesel if needed, and shows the result directly here.</p>
          {error_html}
          <form method="post" action="/search">
            <div class="form-panel">
              <div>
                <label for="registration">Vehicle registration number</label>
                <input id="registration" name="registration" value="{safe_registration}" placeholder="Example: KA01AB1234" autocomplete="off" maxlength="15" required>
              </div>
              <button type="submit">Check Vehicle</button>
            </div>
            <p class="hint">Spaces and dashes are fine. The search will normalize the registration number automatically.</p>
          </form>
          <div class="cta-row">
            <button id="install-app" class="secondary-button" type="button">Install on Android</button>
          </div>
        </section>
        {result_html}
      </main>
      <script>
        if ('serviceWorker' in navigator) {{
          navigator.serviceWorker.register('/service-worker.js').catch(() => {{}});
        }}

        let deferredPrompt = null;
        const installButton = document.getElementById('install-app');
        window.addEventListener('beforeinstallprompt', (event) => {{
          event.preventDefault();
          deferredPrompt = event;
          installButton.style.display = 'inline-flex';
        }});
        installButton?.addEventListener('click', async () => {{
          if (!deferredPrompt) return;
          deferredPrompt.prompt();
          await deferredPrompt.userChoice;
          deferredPrompt = null;
          installButton.style.display = 'none';
        }});
      </script>
    </body>
    </html>
    """
    return html.encode('utf-8')


class VehicleLookupHandler(BaseHTTPRequestHandler):
    def do_GET(self) -> None:
        if self.path == '/':
            self._send_bytes(render_page(), 'text/html; charset=utf-8')
            return
        if self.path == '/manifest.webmanifest':
            self._send_bytes(MANIFEST_JSON.encode('utf-8'), 'application/manifest+json; charset=utf-8')
            return
        if self.path == '/service-worker.js':
            self._send_bytes(SERVICE_WORKER_JS.encode('utf-8'), 'application/javascript; charset=utf-8')
            return
        if self.path.startswith('/static/'):
            self._serve_static(self.path.removeprefix('/static/'))
            return
        self.send_error(404)

    def do_POST(self) -> None:
        if self.path != '/search':
            self.send_error(404)
            return

        content_length = int(self.headers.get('Content-Length', '0'))
        body = self.rfile.read(content_length).decode('utf-8')
        form = parse_qs(body)
        registration = form.get('registration', [''])[0]
        normalized = normalize_registration(registration)

        if not normalized:
            self._send_bytes(render_page(registration=registration, error='Please enter a vehicle registration number.'), 'text/html; charset=utf-8')
            return

        try:
            lookup = run_lookup(normalized)
            self._send_bytes(render_page(registration=normalized, lookup=lookup), 'text/html; charset=utf-8')
        except requests.RequestException:
            self._send_bytes(
                render_page(
                    registration=normalized,
                    error='The Karnataka source could not be reached right now. Please try again in a moment.',
                ),
                'text/html; charset=utf-8',
            )

    def _serve_static(self, relative_path: str) -> None:
        file_path = (STATIC_DIR / relative_path).resolve()
        if not str(file_path).startswith(str(STATIC_DIR.resolve())) or not file_path.exists():
            self.send_error(404)
            return

        content_type = 'application/octet-stream'
        if file_path.suffix == '.png':
            content_type = 'image/png'
        elif file_path.suffix == '.svg':
            content_type = 'image/svg+xml; charset=utf-8'

        self._send_bytes(file_path.read_bytes(), content_type)

    def _send_bytes(self, content: bytes, content_type: str) -> None:
        self.send_response(200)
        self.send_header('Content-Type', content_type)
        self.send_header('Content-Length', str(len(content)))
        self.end_headers()
        self.wfile.write(content)

    def log_message(self, format: str, *args: object) -> None:
        return


def create_server(host: str = HOST, port: int = PORT) -> HTTPServer:
    return HTTPServer((host, port), VehicleLookupHandler)


def main() -> None:
    server = create_server()
    print(f'Server running at http://{HOST}:{PORT}')
    server.serve_forever()


if __name__ == '__main__':
    main()


