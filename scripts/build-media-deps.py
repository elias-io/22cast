#!/usr/bin/env python3
"""Build the bundled, LGPL FFmpeg AAC/ALAC decoders for Android."""
import concurrent.futures
import hashlib
import os
from pathlib import Path
import subprocess
import sys
import tarfile

ROOT=Path(__file__).resolve().parents[1]
WORK=ROOT/'.native-deps'
ARCHIVE=ROOT/'native/vendor/ffmpeg-source.tar.xz'

def build(abi):
    prefix=ROOT/'native/prebuilt'/abi/'ffmpeg'
    if (prefix/'lib/libavcodec.a').exists():
        print(f'{abi}: media decoder already built',flush=True)
        return
    source=WORK/('ffmpeg-'+abi)
    source.mkdir(parents=True,exist_ok=True)
    with tarfile.open(ARCHIVE) as tar:
        for entry in tar.getmembers():
            parts=entry.name.split('/')[1:]
            if not parts or '..' in parts:continue
            target=source.joinpath(*parts)
            if entry.isdir():target.mkdir(parents=True,exist_ok=True)
            elif entry.isfile():
                target.parent.mkdir(parents=True,exist_ok=True)
                target.write_bytes(tar.extractfile(entry).read())
                target.chmod(entry.mode)
    sdk=Path(os.environ.get('ANDROID_HOME',Path.home()/'Library/Android/sdk'))
    host='darwin-x86_64' if sys.platform=='darwin' else 'linux-x86_64'
    tools=sdk/'ndk/28.2.13676358/toolchains/llvm/prebuilt'/host/'bin'
    triple='aarch64-linux-android24' if abi=='arm64-v8a' else 'armv7a-linux-androideabi24'
    args=['./configure','--prefix='+str(prefix),'--enable-cross-compile','--target-os=android',
          '--arch='+('aarch64' if abi=='arm64-v8a' else 'arm'),
          '--cc='+str(tools/(triple+'-clang')),'--cxx='+str(tools/(triple+'-clang++')),
          '--ar='+str(tools/'llvm-ar'),'--ranlib='+str(tools/'llvm-ranlib'),
          '--strip='+str(tools/'llvm-strip'),'--disable-everything','--disable-autodetect',
          '--disable-programs','--disable-doc','--disable-network','--disable-avformat',
          '--disable-avdevice','--disable-avfilter','--disable-swscale','--disable-swresample',
          '--disable-shared','--enable-static','--enable-pic','--enable-small','--disable-debug',
          '--disable-asm','--enable-decoder=aac,alac']
    if abi=='armeabi-v7a':args+=['--cpu=armv7-a']
    with (WORK/('media-'+abi+'.log')).open('w') as log:
        for command in [args,['make','-j6'],['make','install']]:
            subprocess.run(command,cwd=source,stdout=log,stderr=subprocess.STDOUT,check=True)
    print(f'{abi}: media decoder built',flush=True)

if __name__=='__main__':
    WORK.mkdir(exist_ok=True)
    expected=(ROOT/'native/vendor/ffmpeg.sha256').read_text().split()[0]
    if hashlib.sha256(ARCHIVE.read_bytes()).hexdigest()!=expected:raise RuntimeError('FFmpeg checksum mismatch')
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        list(pool.map(build,sys.argv[1:] or ['arm64-v8a','armeabi-v7a']))
