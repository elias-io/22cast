#!/usr/bin/env python3
"""Compile the native Kotlin interface and package it with the Android SDK tools."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import zipfile
import xml.etree.ElementTree as ET
from kotlin_deps import prepare

ROOT = Path(__file__).resolve().parents[1]
SDK = Path(os.environ.get('ANDROID_HOME', Path.home() / 'Library/Android/sdk'))
TOOLS = SDK / 'build-tools/36.1.0'
ANDROID = SDK / 'platforms/android-36/android.jar'
BUILD = ROOT / 'app/build/sdk'

def run(*args, env=None):
    subprocess.run([str(a) for a in args], cwd=ROOT, env=env, check=True)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--release', action='store_true',
                        help='Sign for distribution using local .native-deps/release-signing.json')
    args = parser.parse_args()
    if not TOOLS.exists() or not ANDROID.exists():
        raise SystemExit('Install Android platform 36 and build-tools 36.1.0 first.')
    BUILD.mkdir(parents=True, exist_ok=True)
    generated, classes, dex = [BUILD / n for n in ('generated', 'classes', 'dex')]
    for p in (generated, classes, dex):
        if p.exists():
            shutil.rmtree(p)
        p.mkdir()
    compiler, runtime = prepare()
    version = dict(line.split('=', 1) for line in (ROOT / 'version.properties').read_text().splitlines() if '=' in line)
    run(TOOLS / 'aapt2', 'compile', '--dir', ROOT / 'app/src/main/res', '-o', BUILD / 'resources.zip')
    manifest = ET.parse(ROOT / 'app/src/main/AndroidManifest.xml')
    manifest.getroot().set('package', 'dev.elias.cast22')
    ET.register_namespace('android', 'http://schemas.android.com/apk/res/android')
    manifest.write(BUILD / 'AndroidManifest.xml', encoding='utf-8', xml_declaration=True)
    run(TOOLS / 'aapt2', 'link', '-o', BUILD / 'resources.apk', '--manifest', BUILD / 'AndroidManifest.xml',
        '-I', ANDROID, '--java', generated, '-A', ROOT / 'app/src/main/assets', '--min-sdk-version', '24',
        '--target-sdk-version', '36', '--version-code', version['code'], '--version-name', version['name'], BUILD / 'resources.zip')
    sources = list((ROOT / 'app/src/main/java').rglob('*.java')) + list(generated.rglob('*.java'))
    kotlin_sources = list((ROOT / 'app/src/main/java').rglob('*.kt'))
    java = os.environ.get('JAVA', 'java')
    run(java, '-cp', os.pathsep.join(map(str, compiler)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
        '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-classpath', os.pathsep.join(map(str, [ANDROID, *runtime])),
        '-d', classes, *kotlin_sources, *sources)
    run('javac', '-source', '17', '-target', '17', '-Xlint:-options', '-classpath',
        os.pathsep.join(map(str, [ANDROID, classes, *runtime])), '-d', classes, *sources)
    run(TOOLS / 'd8', '--lib', ANDROID, '--min-api', '24', '--output', dex, *classes.rglob('*.class'), *runtime)
    shutil.copy2(BUILD / 'resources.apk', BUILD / 'unsigned.apk')
    with zipfile.ZipFile(BUILD / 'unsigned.apk', 'a') as apk:
        for path in dex.glob('*.dex'):
            apk.write(path, path.name)
        for abi in ('armeabi-v7a', 'arm64-v8a'):
            path = ROOT / 'native/prebuilt-jni' / abi / 'libcast22.so'
            if not path.exists():
                raise SystemExit(f'Missing {path}; run ./build-android first')
            apk.write(path, f'lib/{abi}/libcast22.so', compress_type=zipfile.ZIP_STORED)
    run(TOOLS / 'zipalign', '-P', '16', '-f', '4', BUILD / 'unsigned.apk', BUILD / 'aligned.apk')
    signing_env = None
    alias_args = []
    if args.release:
        config_path = ROOT / '.native-deps/release-signing.json'
        try:
            if config_path.stat().st_mode & 0o077:
                raise ValueError('Release signing configuration must be owner-readable only (chmod 600).')
            config = json.loads(config_path.read_text())
            if not isinstance(config, dict) or not all(isinstance(config.get(field), str) and config[field]
                       for field in ('keystore', 'alias', 'storePassword', 'keyPassword')):
                raise ValueError('Release signing configuration needs keystore, alias, storePassword and keyPassword.')
            key = Path(config['keystore'])
            if not key.is_absolute():
                key = ROOT / key
            if not key.is_file():
                raise ValueError('Configured release keystore does not exist.')
            alias_args = ['--ks-key-alias', config['alias']]
            signing_env = os.environ.copy()
            signing_env['CAST22_STORE_PASSWORD'] = config['storePassword']
            signing_env['CAST22_KEY_PASSWORD'] = config['keyPassword']
        except (OSError, json.JSONDecodeError):
            raise SystemExit('Could not read local .native-deps/release-signing.json.') from None
        except ValueError as error:
            raise SystemExit(str(error)) from None
        password_args = ['--ks-pass', 'env:CAST22_STORE_PASSWORD', '--key-pass', 'env:CAST22_KEY_PASSWORD']
    else:
        key = ROOT / '.native-deps/debug.keystore'
        key.parent.mkdir(exist_ok=True)
        if not key.exists():
            run('keytool', '-genkeypair', '-keystore', key, '-storepass', 'android', '-keypass', 'android',
                '-alias', 'androiddebugkey', '-dname', 'CN=22Cast Development,O=22Cast,C=DE',
                '-keyalg', 'RSA', '-keysize', '2048', '-validity', '10000')
        password_args = ['--ks-pass', 'pass:android', '--key-pass', 'pass:android']
    output = ROOT / 'dist/22Cast.apk'
    output.parent.mkdir(exist_ok=True)
    run(TOOLS / 'apksigner', 'sign', '--ks', key, *alias_args, *password_args,
        '--out', output, BUILD / 'aligned.apk', env=signing_env)
    run(TOOLS / 'apksigner', 'verify', '--verbose', output)
    print(f'Built {output}')

if __name__ == '__main__':
    main()
