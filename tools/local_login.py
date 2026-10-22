"""Local-only login handoff. Passwords stay in memory; session is protected by Windows DPAPI."""
import ctypes
from ctypes import wintypes
import json
import secrets
from pathlib import Path
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import urllib.request
import urllib.parse
import urllib.error

ROOT = Path(__file__).resolve().parents[1]
SESSION = ROOT / '.private' / 'session.dpapi'
BASE = 'http://tpm.forbetter.cn:8855/'
PORT = 18765


class Blob(ctypes.Structure):
    _fields_ = [('size', wintypes.DWORD), ('data', ctypes.POINTER(ctypes.c_byte))]


def protect(data, decrypt=False):
    buffer = ctypes.create_string_buffer(data)
    source = Blob(len(data), ctypes.cast(buffer, ctypes.POINTER(ctypes.c_byte)))
    result = Blob()
    if decrypt:
        ok = ctypes.windll.crypt32.CryptUnprotectData(ctypes.byref(source), None, None, None, None, 1, ctypes.byref(result))
    else:
        ok = ctypes.windll.crypt32.CryptProtectData(ctypes.byref(source), None, None, None, None, 1, ctypes.byref(result))
    if not ok:
        raise ctypes.WinError()
    try:
        return ctypes.string_at(result.data, result.size)
    finally:
        ctypes.windll.kernel32.LocalFree(result.data)


def save_session(auth):
    SESSION.parent.mkdir(exist_ok=True)
    SESSION.write_bytes(protect(json.dumps(auth).encode()))


def load_session():
    return json.loads(protect(SESSION.read_bytes(), decrypt=True))


def api(path, query=None, body=None):
    """For controlled local read-only analysis, no token is printed or returned in metadata."""
    auth = load_session()
    url = BASE + 'rest/' + path
    if query:
        url += '?' + urllib.parse.urlencode(query)
    headers = {'Authorization': 'Bearer ' + auth['token'], 'Accept': 'application/json'}
    data = None
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode()
        headers['Content-Type'] = 'application/json'
    request = urllib.request.Request(url, data=data, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            token = response.headers.get('Authorization')
            if token:
                auth['token'] = token.removeprefix('Bearer ')
                save_session(auth)
            return json.loads(response.read())
    except urllib.error.HTTPError as exc:
        raise RuntimeError(f'API {path} returned HTTP {exc.code}') from None


if __name__ == '__main__':
    csrf = secrets.token_urlsafe(32)
    page = '''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width">
    <title>原应用账号 · 本地联调</title><style>body{font:17px system-ui;max-width:440px;margin:12vh auto;padding:24px;background:#f5f5f7;color:#161617}input,button{box-sizing:border-box;width:100%;font:inherit;padding:14px;margin:8px 0;border:1px solid #bbb;border-radius:12px}button{background:#007aff;color:white;border:0}p{line-height:1.6;color:#555}</style>
    <h1>登录原系统</h1><p>账号密码仅用于连接 APK 中的原服务器。密码不写入文件；登录后的会话使用 Windows 用户加密保存在本机，不上传到 GitHub。</p>
    <form id="form"><label>账号<input id="username" autocomplete="username" required></label><label>密码<input id="password" type="password" autocomplete="current-password" required></label><button id="submit">登录，开始只读核对</button></form><p id="status" role="status"></p>
    <script>const csrf=CSRF;document.getElementById('form').onsubmit=async(e)=>{e.preventDefault();let button=document.getElementById('submit');button.disabled=true;try{const r=await fetch('/login',{method:'POST',headers:{'Content-Type':'application/json','X-Local-CSRF':csrf},body:JSON.stringify({username:document.getElementById('username').value,password:document.getElementById('password').value})});const d=await r.json();document.getElementById('status').textContent=d.message;document.getElementById('password').value='';if(d.ok)document.getElementById('form').hidden=true;}catch(e){document.getElementById('status').textContent='连接失败，请重试。';}finally{button.disabled=false}};</script></html>'''.replace('CSRF', json.dumps(csrf), 1)

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def reply(self, status, body, kind='application/json'):
            self.send_response(status)
            self.send_header('Content-Type', kind + '; charset=utf-8')
            self.send_header('Cache-Control', 'no-store')
            self.send_header('X-Frame-Options', 'DENY')
            self.end_headers()
            self.wfile.write(body.encode('utf-8'))

        def do_GET(self):
            if self.path == '/':
                return self.reply(200, page, 'text/html')
            self.reply(404, '{}')

        def do_POST(self):
            if (self.path != '/login' or self.headers.get('X-Local-CSRF') != csrf
                    or self.headers.get('Origin') != f'http://127.0.0.1:{PORT}'
                    or int(self.headers.get('Content-Length', '0')) > 16384):
                return self.reply(403, '{}')
            try:
                values = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                data = urllib.parse.urlencode({'username': values['username'], 'password': values['password']}).encode()
                request = urllib.request.Request(BASE + 'userLogin', data=data, headers={
                    'Content-Type': 'application/x-www-form-urlencoded', 'x-requested-with': 'XMLHttpRequest'})
                with urllib.request.urlopen(request, timeout=25) as response:
                    auth = json.loads(response.read())
                if not isinstance(auth, dict) or not auth.get('token'):
                    raise ValueError('No session')
                save_session(auth)
                self.reply(200, json.dumps({'ok': True, 'message': '登录成功。会话已加密保存在本机，可以回到聊天；接下来只读核对接口与流程。'}, ensure_ascii=False))
                print('LOGIN_READY', flush=True)
            except Exception:
                self.reply(400, json.dumps({'ok': False, 'message': '登录未成功，请核对账号、密码或服务器访问条件。'}, ensure_ascii=False))

    print(f'LOCAL_LOGIN http://127.0.0.1:{PORT}/', flush=True)
    ThreadingHTTPServer(('127.0.0.1', PORT), Handler).serve_forever()
