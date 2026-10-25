#!/usr/bin/env python3
"""22Cast's local launcher. Real AirPlay runs in the native receiver process."""
import argparse
import atexit
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

ROOT = Path(__file__).resolve().parent
DEFAULTS = dict(name='22Cast Living Room', quality='1080', fps=30,
                pinRequired=False, audio=True, keepAwake=True)

class Controller:
    def __init__(self, headless=False):
        self.lock = threading.RLock()
        self.settings = dict(DEFAULTS)
        self.file = ROOT / '.local-settings.json'
        try:
            saved = json.loads(self.file.read_text())
            self.settings.update({k: saved[k] for k in DEFAULTS if k in saved})
            self.identity = saved['id']
        except (OSError, ValueError, KeyError):
            self.identity = '02:' + ':'.join(secrets.token_hex(1).upper() for _ in range(5))
        self.headless = headless
        self.process = None
        self.caffeinate = None
        self.paused = False
        self.state = dict(status='starting', message='Starting receiver…', platform='local',
                          streaming=False, video=False, pin='', sender='', network='Local network')
        self.save()

    def save(self):
        temp = self.file.with_suffix('.tmp')
        temp.write_text(json.dumps(dict(self.settings, id=self.identity), indent=2) + '\n')
        temp.replace(self.file)

    def stop(self):
        if self.caffeinate:
            self.caffeinate.terminate()
            self.caffeinate = None
        process, self.process = self.process, None
        if process and process.poll() is None:
            try:
                process.stdin.write('quit\n')
                process.stdin.flush()
                process.wait(timeout=4)
            except (OSError, subprocess.TimeoutExpired):
                process.terminate()
                try:
                    process.wait(timeout=2)
                except subprocess.TimeoutExpired:
                    process.kill()

    def restart(self):
        with self.lock:
            self.stop()
            self.state.update(streaming=False, video=False, pin='', sender='', ip='')
            if self.paused:
                self.state.update(status='paused', message='Receiver paused')
                return
            binary = ROOT / 'native/build/desktop/cast22'
            if not binary.exists():
                self.state.update(status='error', message='Build the native receiver with ./run-local first')
                return
            self.state.update(status='starting', message='Starting receiver…')
            key = ROOT / '.native-deps/receiver.pem'
            key.parent.mkdir(exist_ok=True)
            args = [str(binary), '--name', self.settings['name'], '--height', self.settings['quality'],
                    '--fps', str(self.settings['fps']), '--id', self.identity, '--key', str(key)]
            if self.headless:
                args.append('--headless')
            if self.settings['pinRequired']:
                args.append('--pin')
            if not self.settings['audio']:
                args.append('--no-audio')
            process = subprocess.Popen(args, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                       text=True, bufsize=1, umask=0o077)
            self.process = process
            threading.Thread(target=self.read_events, args=(process,), daemon=True).start()
            if self.caffeinate:
                self.caffeinate.terminate()
                self.caffeinate = None
            if self.settings['keepAwake'] and sys.platform == 'darwin':
                self.caffeinate = subprocess.Popen(['caffeinate', '-d', '-i', '-w', str(os.getpid())])

    def read_events(self, process):
        for line in process.stdout:
            try:
                message = json.loads(line)
            except ValueError:
                continue
            with self.lock:
                if process is not self.process:
                    return
                event, value = message.get('event'), message.get('value', '')
                if event == 'address':
                    self.state['ip'] = value
                elif event == 'ready':
                    self.state.update(status='ready', message='Ready to connect', port=value)
                elif event == 'error':
                    self.state.update(status='error', message=value)
                elif event == 'client':
                    self.state.update(sender=value, pin='')
                elif event == 'pin':
                    self.state['pin'] = value
                elif event in ('mirroring', 'videoActive'):
                    self.state.update(streaming=True, video=True, pin='', message='Screen mirroring')
                elif event == 'audio':
                    self.state.update(streaming=True, pin='', message='Playing audio')
                elif event == 'disconnected':
                    self.state.update(streaming=False, video=False, pin='', message='Ready to connect')
        with self.lock:
            if process is self.process:
                self.state.update(status='error', message='Receiver stopped. Choose Network → Restart receiver.',
                                  streaming=False, video=False, pin='', sender='', port='')

    def snapshot(self):
        with self.lock:
            ip = self.state.get('ip') or '127.0.0.1'
            try:
                # UDP connect chooses the LAN interface without transmitting a packet.
                with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
                    if ip == '127.0.0.1':
                        sock.connect(('192.0.2.1', 9))
                        ip = sock.getsockname()[0]
            except OSError:
                pass
            return dict(self.state, **self.settings, ip=ip)

    def command(self, action, data):
        with self.lock:
            if action == 'settings':
                name = data.get('name', '').strip()
                if not name or len(name.encode()) > 50 or any(ord(c) < 32 for c in name):
                    raise ValueError('Choose a receiver name between 1 and 50 bytes')
                if data.get('quality') not in ('720', '1080') or data.get('fps') not in (30, 60):
                    raise ValueError('Invalid picture settings')
                self.settings.update({k: data[k] for k in DEFAULTS if k in data})
                self.save()
            elif action == 'pause':
                self.paused = True
            elif action == 'resume':
                self.paused = False
            elif action not in ('restart', 'disconnect'):
                raise ValueError('Unknown command')
        self.restart()
        return dict(ok=True)

    def close(self):
        with self.lock:
            self.stop()
            if self.caffeinate:
                self.caffeinate.terminate()

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--headless', action='store_true', help='Run the real receiver without desktop playback, for protocol tests')
    parser.add_argument('--port', type=int, default=2244)
    args = parser.parse_args()
    controller = Controller(args.headless)
    atexit.register(controller.close)

    class Handler(BaseHTTPRequestHandler):
        def respond(self, code, value, content_type='application/json'):
            body = value if isinstance(value, bytes) else json.dumps(value).encode()
            self.send_response(code)
            self.send_header('Content-Type', content_type)
            self.send_header('Content-Length', str(len(body)))
            self.send_header('Cache-Control', 'no-store')
            self.send_header('X-Content-Type-Options', 'nosniff')
            self.send_header('Content-Security-Policy', "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'")
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            path = urlparse(self.path).path
            if path == '/api/state':
                self.respond(200, controller.snapshot())
            elif path in ('/', '/index.html'):
                self.respond(200, (ROOT / 'desktop/index.html').read_bytes(), 'text/html; charset=utf-8')
            elif path == '/favicon.ico':
                self.respond(204, b'', 'image/x-icon')
            else:
                self.respond(404, dict(error='Not found'))

        def do_POST(self):
            host = self.headers.get('Host', '')
            origin = self.headers.get('Origin')
            if host not in (f'127.0.0.1:{args.port}', f'localhost:{args.port}') or (origin and origin not in (f'http://127.0.0.1:{args.port}', f'http://localhost:{args.port}')):
                self.respond(403, dict(error='Local requests only'))
                return
            if self.headers.get('Content-Type', '').split(';')[0] != 'application/json':
                self.respond(415, dict(error='JSON required'))
                return
            try:
                size = int(self.headers.get('Content-Length', '0'))
                if size < 0 or size > 8192:
                    raise ValueError('Request too large')
                data = json.loads(self.rfile.read(size) or b'{}')
                self.respond(200, controller.command(self.path.removeprefix('/api/'), data))
            except (ValueError, TypeError, OSError) as error:
                self.respond(400, dict(error=str(error)))

        def log_message(self, fmt, *values):
            if values and str(values[1]) != '200':
                super().log_message(fmt, *values)

    server = ThreadingHTTPServer(('127.0.0.1', args.port), Handler)
    controller.restart()
    print(f'22Cast: http://127.0.0.1:{args.port}', flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
        controller.close()

if __name__ == '__main__':
    main()
