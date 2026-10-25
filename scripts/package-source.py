#!/usr/bin/env python3
"""Package the corresponding source and production JNI libraries, without local data."""
import argparse
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]
ROOT_FILES = {'.gitignore', 'README.md', 'LICENSE', 'THIRD_PARTY_NOTICES.md',
              'build.gradle', 'settings.gradle', 'gradle.properties', 'version.properties', 'build-android', 'run-local', 'local.py'}


def source_files():
    for path in sorted(ROOT.rglob('*')):
        if not path.is_file():
            continue
        relative = path.relative_to(ROOT)
        if relative.parts[0] not in ('app', 'native', 'desktop', 'scripts', 'tests') and str(relative) not in ROOT_FILES:
            continue
        if any(part in ('build', 'prebuilt', '__pycache__', 'output', '.git', '.gradle', '.native-deps') for part in relative.parts):
            continue
        if path.suffix in ('.pem', '.keystore', '.log', '.pyc') or path.name == '.DS_Store':
            continue
        yield path, relative


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=Path, default=ROOT / 'dist/22Cast-source.zip')
    args = parser.parse_args()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(args.output, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for path, relative in source_files():
            archive.write(path, Path('22Cast') / relative)
    print(args.output.resolve())


if __name__ == '__main__':
    main()
