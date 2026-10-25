#!/usr/bin/env python3
"""Build a separate test APK and validate an explicitly selected Android emulator.

Requires Android SDK/JDK, FFmpeg with libx264 and Python cryptography. Tests real
PIN protocol traffic, remote keys and injected H.264 playback; no physical sender.
"""
import argparse
import json
import os
from pathlib import Path
import queue
import shutil
import subprocess
import threading
import zipfile

from receiver_smoke import run_checks

ROOT = Path(__file__).resolve().parents[1]
SDK = Path(os.environ.get('ANDROID_HOME', Path.home() / 'Library/Android/sdk'))
TOOLS = SDK / 'build-tools/36.1.0'


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--serial', required=True, help='Explicit emulator serial; physical devices are refused')
    parser.add_argument('--adb-port', default='5037')
    parser.add_argument('--output', type=Path, default=ROOT / 'tests/output')
    parser.add_argument('--mode', choices=('all', 'pairing', 'playback', 'updates'), default='all')
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    adb = [str(SDK / 'platform-tools/adb'), '-P', args.adb_port, '-s', args.serial]

    def device(*command, timeout=20):
        return subprocess.check_output(adb + list(command), text=True, timeout=timeout).strip()

    assert device('shell', 'getprop', 'ro.kernel.qemu') == '1', 'This test only runs on an emulator'
    build = ROOT / '.native-deps/android-probe'
    assets, classes, dex = [build / name for name in ('assets', 'classes', 'dex')]
    for folder in (assets, classes, dex):
        if folder != assets and folder.exists():
            shutil.rmtree(folder)
        folder.mkdir(parents=True, exist_ok=True)
    with (output / 'build.log').open('w') as log:
        def run(*command):
            subprocess.run([str(part) for part in command], cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, check=True)

        for name, size in (('landscape', '1280x720'), ('portrait', '360x640')):
            path = assets / (name + '.h264')
            if not path.exists():
                run('ffmpeg', '-y', '-f', 'lavfi', '-i', f'testsrc2=size={size}:rate=30',
                    '-frames:v', '75', '-c:v', 'libx264', '-preset', 'ultrafast', '-tune', 'zerolatency',
                    '-g', '30', '-bf', '0', '-x264-params', 'aud=1:repeat-headers=1', '-f', 'h264', path)
        android = SDK / 'platforms/android-36/android.jar'
        run(TOOLS / 'aapt2', 'link', '-o', build / 'probe.apk', '--manifest', ROOT / 'tests/android/AndroidManifest.xml',
            '-I', android, '-A', assets)
        run('javac', '-source', '17', '-target', '17', '-Xlint:-options', '-classpath',
            str(android) + os.pathsep + str(ROOT / 'app/build/sdk/classes'),
            '-d', classes, ROOT / 'tests/android/ReceiverProbe.java')
        run(TOOLS / 'd8', '--lib', android, '--min-api', '24', '--output', dex, *classes.rglob('*.class'))
        with zipfile.ZipFile(build / 'probe.apk', 'a') as apk:
            apk.write(dex / 'classes.dex', 'classes.dex')
        run(TOOLS / 'zipalign', '-f', '4', build / 'probe.apk', build / 'aligned.apk')
        run(TOOLS / 'apksigner', 'sign', '--ks', ROOT / '.native-deps/debug.keystore',
            '--ks-pass', 'pass:android', '--key-pass', 'pass:android', '--out', build / 'signed.apk', build / 'aligned.apk')
        run(*adb, 'install', '-r', ROOT / 'dist/22Cast.apk')
        run(*adb, 'install', '-r', build / 'signed.apk')

    class AndroidReceiver:
        def __init__(self):
            self.events = queue.Queue()
            self.lines = []
            self.process = subprocess.Popen(adb + ['shell', 'am', 'instrument', '-w', '-r', '-e', 'mode', 'pairing',
                'dev.elias.cast22.probe/dev.elias.cast22.ReceiverProbe'], stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT, text=True)
            threading.Thread(target=self.read, daemon=True).start()
            port = self.event('ready')
            self.port = int(device('forward', 'tcp:0', 'tcp:' + str(port)))

        def read(self):
            previous_pin = ''
            ready = False
            for line in self.process.stdout:
                self.lines.append(line)
                if line.startswith('INSTRUMENTATION_STATUS: state='):
                    state = json.loads(line.split('state=', 1)[1])
                    if state.get('status') == 'ready' and not ready:
                        self.name = state['name']
                        self.events.put(dict(event='ready', value=state['port']))
                        ready = True
                    pin = state.get('pin', '')
                    if pin and pin != previous_pin:
                        self.events.put(dict(event='pin', value=pin))
                    previous_pin = pin
                elif line.startswith('INSTRUMENTATION_STATUS: recovery='):
                    self.events.put(dict(event='recovered', value=line.split('recovery=', 1)[1].strip()))
            self.events.put(dict(event='exited', value=''.join(self.lines[-8:])))

        def event(self, kind):
            while True:
                event = self.events.get(timeout=15)
                assert event['event'] != 'exited', event
                if event['event'] == kind:
                    return event['value']

        def close(self):
            device('shell', 'am', 'broadcast', '-a', 'dev.elias.cast22.probe.STOP')
            self.process.wait(timeout=15)
            device('forward', '--remove', 'tcp:' + str(self.port))
            # Pairing logs contain ephemeral PINs; keep only the test result.
            (output / 'pairing.txt').write_text(''.join(line for line in self.lines if 'state=' not in line))

        def reset_connections(self):
            device('shell', 'am', 'broadcast', '-a', 'dev.elias.cast22.probe.RECOVERY')
            assert self.event('recovered') == 'PASS', self.lines[-8:]

    if args.mode in ('all', 'pairing'):
        device('shell', 'am', 'force-stop', 'dev.elias.cast22')
        run_checks(AndroidReceiver())
        print('PASS: Android native receiver PIN and socket recovery checks')
    if args.mode == 'pairing':
        return
    if args.mode == 'updates':
        result = device('shell', 'am', 'instrument', '-w', '-r', '-e', 'mode', 'updates',
                        'dev.elias.cast22.probe/dev.elias.cast22.ReceiverProbe', timeout=60)
        (output / 'updates.txt').write_text(result + '\n')
        assert 'INSTRUMENTATION_RESULT: error=' not in result and 'PASS: updates' in result, result
        print('PASS: release parsing, bounded response and idle native update recommendation')
        return
    device('shell', 'am', 'force-stop', 'dev.elias.cast22')
    result = device('shell', 'am', 'instrument', '-w', '-r', '-e', 'mode', 'playback',
                    'dev.elias.cast22.probe/dev.elias.cast22.ReceiverProbe', timeout=150)
    (output / 'playback.txt').write_text(result + '\n')
    device('pull', '/sdcard/Android/data/dev.elias.cast22/files/validation', str(output))
    assert 'INSTRUMENTATION_RESULT: error=' not in result and 'PASS: playback' in result, result
    print('PASS: D-pad settings, five-second overlay hiding and key/touch wake-up, landscape/portrait playback, queue overflow and reset recovery')
    print('Screenshots:', output / 'validation')


if __name__ == '__main__':
    main()
