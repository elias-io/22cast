#!/usr/bin/env python3
"""Build the pinned OpenSSL dependency for each Android ABI, without system writes."""
import concurrent.futures
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
WORK = ROOT / '.native-deps'
VERSION = '3.5.4'
URL = f'https://github.com/openssl/openssl/releases/download/openssl-{VERSION}/openssl-{VERSION}.tar.gz'

def build(abi):
    prefix = ROOT / 'native' / 'prebuilt' / abi
    if (prefix / 'lib' / 'libcrypto.a').exists():
        print(f'{abi}: already built', flush=True)
        return
    sdk = Path(os.environ.get('ANDROID_HOME', Path.home() / 'Library/Android/sdk'))
    ndk = sdk / 'ndk/28.2.13676358'
    host = 'darwin-x86_64' if sys.platform == 'darwin' else 'linux-x86_64'
    toolchain = ndk / 'toolchains/llvm/prebuilt' / host / 'bin'
    if not toolchain.is_dir():
        raise RuntimeError(f'Install Android NDK 28.2.13676358: {toolchain}')
    source = WORK / ('openssl-' + abi)
    source.mkdir(parents=True, exist_ok=True)
    archive = WORK / 'openssl.tar.gz'
    with tarfile.open(archive) as tar:
        for entry in tar.getmembers():
            parts = entry.name.split('/')[1:]
            if not parts or '..' in parts:
                continue
            target = source.joinpath(*parts)
            if entry.isdir():
                target.mkdir(parents=True, exist_ok=True)
            elif entry.isfile():
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(tar.extractfile(entry).read())
                target.chmod(entry.mode)
    env = os.environ.copy()
    env.update(ANDROID_NDK_ROOT=str(ndk), PATH=str(toolchain) + os.pathsep + env['PATH'])
    platform = 'android-arm64' if abi == 'arm64-v8a' else 'android-arm'
    log = WORK / (abi + '.log')
    with log.open('w') as stream:
        subprocess.run(['perl', 'Configure', platform, '-D__ANDROID_API__=24', 'no-shared',
                        'no-tests', 'no-apps', 'no-docs', 'no-module', 'no-engine',
                        '--libdir=lib', '--prefix=' + str(prefix)], cwd=source, env=env,
                       stdout=stream, stderr=subprocess.STDOUT, check=True)
        subprocess.run(['make', '-j6', 'build_libs'], cwd=source, env=env,
                       stdout=stream, stderr=subprocess.STDOUT, check=True)
    (prefix / 'lib').mkdir(parents=True, exist_ok=True)
    shutil.copy2(source / 'libcrypto.a', prefix / 'lib')
    shutil.copytree(source / 'include', prefix / 'include', dirs_exist_ok=True)
    print(f'{abi}: built', flush=True)

if __name__ == '__main__':
    WORK.mkdir(exist_ok=True)
    archive = WORK / 'openssl.tar.gz'
    supplied = os.environ.get('OPENSSL_ARCHIVE')
    if supplied:
        shutil.copy2(supplied, archive)
    if not archive.exists():
        urllib.request.urlretrieve(URL, archive)
    expected = (ROOT / 'native/vendor/openssl.sha256').read_text().split()[0]
    if hashlib.sha256(archive.read_bytes()).hexdigest() != expected:
        raise RuntimeError('OpenSSL archive checksum mismatch')
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        list(pool.map(build, sys.argv[1:] or ['arm64-v8a', 'armeabi-v7a']))
