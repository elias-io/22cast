"""Pinned public Kotlin compiler/runtime inputs for the standalone SDK build."""
import hashlib
import json
from pathlib import Path
import urllib.request

ROOT = Path(__file__).resolve().parents[1]


def prepare():
    destination = ROOT / '.native-deps/kotlin'
    destination.mkdir(parents=True, exist_ok=True)
    compiler, runtime = [], []
    for dependency in json.loads((ROOT / 'scripts/kotlin-dependencies.json').read_text()):
        path = destination / dependency['file']
        if not path.exists():
            temporary = path.with_suffix('.download')
            urllib.request.urlretrieve(dependency['url'], temporary)
            temporary.replace(path)
        if hashlib.sha256(path.read_bytes()).hexdigest() != dependency['sha256']:
            raise RuntimeError('Kotlin dependency checksum mismatch: ' + path.name)
        compiler.append(path)
        if dependency['runtime']:
            runtime.append(path)
    return compiler, runtime
