#!/usr/bin/env python3
"""Mirror published releases; publish the two-product manifest only after validation."""
import hashlib
import argparse
import json
import os
from pathlib import Path
import re
import tempfile
import urllib.request

ROOT = Path('/var/www/keye/downloads')
MAX_SIZE = 300 * 1024 * 1024
PRODUCTS = {
    'windows': ('keye-desktop', re.compile(r'Keye_[0-9.]+_x64-setup\.exe'), b'MZ'),
    'android': ('keye-android', re.compile(r'keye-android\.apk'), b'PK'),
}


def request(url):
    return urllib.request.urlopen(urllib.request.Request(url, headers={
        'User-Agent': 'KOVRA-release-mirror/1.0',
        'Accept': 'application/vnd.github+json' if 'api.github.com/' in url else '*/*',
    }), timeout=60)


def checksum(path):
    digest = hashlib.sha256()
    with path.open('rb') as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def atomic_write(path, data):
    descriptor, temporary = tempfile.mkstemp(prefix='.pending-', dir=path.parent)
    try:
        with os.fdopen(descriptor, 'wb') as output:
            output.write(data)
            output.flush()
            os.fsync(output.fileno())
        os.chmod(temporary, 0o644)
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def mirror(product, repo, pattern, magic):
    with request(f'https://api.github.com/repos/goooseby/{repo}/releases/latest') as response:
        release = json.load(response)
    if release.get('draft') or release.get('prerelease'):
        raise ValueError(f'{repo}: refusing a draft or prerelease')
    version = release['tag_name']
    if not re.fullmatch(r'v[0-9]+\.[0-9]+\.[0-9]+', version):
        raise ValueError(f'{repo}: unsupported version format')
    assets = [a for a in release['assets'] if pattern.fullmatch(a['name'])]
    if len(assets) != 1:
        raise ValueError(f'{repo}: expected one installer')
    asset = assets[0]
    size = asset['size']
    digest = asset.get('digest', '')
    if not 0 < size <= MAX_SIZE or not re.fullmatch(r'sha256:[0-9a-f]{64}', digest):
        raise ValueError(f'{repo}: missing checksum or invalid size')
    expected = digest.split(':')[1]
    source_url = asset['browser_download_url']
    expected_url = f'https://github.com/goooseby/{repo}/releases/download/{version}/{asset["name"]}'
    if source_url != expected_url:
        raise ValueError(f'{repo}: unexpected asset origin')
    directory = ROOT / product / version
    directory.mkdir(parents=True, exist_ok=True)
    target = directory / asset['name']
    if target.exists():
        if target.stat().st_size != size or checksum(target) != expected:
            raise ValueError(f'{repo}: existing version differs; refusing to overwrite')
    else:
        descriptor, temporary = tempfile.mkstemp(prefix='.pending-', dir=directory)
        try:
            sha = hashlib.sha256()
            count = 0
            first = b''
            with os.fdopen(descriptor, 'wb') as output, request(source_url) as response:
                while True:
                    chunk = response.read(1024 * 1024)
                    if not chunk:
                        break
                    if not first:
                        first = chunk[:2]
                    count += len(chunk)
                    if count > size:
                        raise ValueError(f'{repo}: oversized response')
                    sha.update(chunk)
                    output.write(chunk)
                output.flush()
                os.fsync(output.fileno())
            if count != size or sha.hexdigest() != expected or first != magic:
                raise ValueError(f'{repo}: installer integrity check failed')
            os.chmod(temporary, 0o644)
            os.replace(temporary, target)
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)
    atomic_write(directory / 'SHA256SUMS.txt', f'{expected}  {asset["name"]}\n'.encode())
    print(f'Verified {product} {version}: {size} bytes', flush=True)
    return {
        'version': version, 'size': size, 'sha256': expected,
        'filename': asset['name'], 'publishedAt': release['published_at'],
        'mirrorUrl': f'/downloads/{product}/{version}/{asset["name"]}',
        'githubUrl': source_url,
        'releaseUrl': f'https://github.com/goooseby/{repo}/releases/tag/{version}',
        **({'notes': (release.get('body') or '')[:12_000]} if product == 'android' else {}),
    }


def main():
    global ROOT
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=ROOT, help='Installer mirror output directory')
    args = parser.parse_args()
    ROOT = args.root.resolve()
    os.umask(0o022)
    ROOT.mkdir(parents=True, exist_ok=True)
    manifest = {'schemaVersion': 1, 'products': {
        key: mirror(key, *config) for key, config in PRODUCTS.items()
    }}
    atomic_write(ROOT / 'releases.json', (json.dumps(manifest, ensure_ascii=False, indent=2) + '\n').encode())
    print('Published verified download manifest.', flush=True)


if __name__ == '__main__':
    main()
