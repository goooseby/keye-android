#!/usr/bin/env python3
"""Run on a computer with GitHub access: verify releases, upload, publish metadata last."""
import argparse
import json
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--host', default='root@8.138.204.47')
    args = parser.parse_args()
    website = Path(__file__).resolve().parent
    checkout = website.parent
    mirror = checkout / 'app/build/diagnostics/website/download-mirror'
    subprocess.run([sys.executable, str(website / 'server/sync-downloads.py'), '--root', str(mirror)], check=True)
    manifest = json.loads((mirror / 'releases.json').read_text(encoding='utf-8'))
    identifier = uuid.uuid4().hex
    remote_archive = f'/root/keye-download-sync-{identifier}.tar.gz'
    with tempfile.TemporaryDirectory(prefix='keye-download-upload-') as temporary:
        archive = Path(temporary) / 'downloads.tar.gz'
        with tarfile.open(archive, 'w:gz') as output:
            output.add(mirror / 'releases.json', arcname='releases.json')
            for product, entry in manifest['products'].items():
                for name in [entry['filename'], 'SHA256SUMS.txt']:
                    relative = Path(product) / entry['version'] / name
                    output.add(mirror / relative, arcname=relative.as_posix())
        subprocess.run(['scp', '-o', 'BatchMode=yes', '-o', 'StrictHostKeyChecking=yes', str(archive), args.host + ':' + remote_archive], check=True)
    worker = r'''
import hashlib,json,os,pathlib,shutil,tarfile,tempfile
root=pathlib.Path('/var/www/keye/downloads')
stage=pathlib.Path(tempfile.mkdtemp(prefix='.incoming-',dir=root))
try:
    with tarfile.open(ARCHIVE) as archive:
        for item in archive.getmembers():
            target=(stage/item.name).resolve()
            if not target.is_relative_to(stage) or not item.isfile():
                raise ValueError('Unexpected archive entry')
        archive.extractall(stage)
    manifest=json.loads((stage/'releases.json').read_text())
    if manifest.get('schemaVersion')!=1 or set(manifest['products'])!={'windows','android'}:
        raise ValueError('Invalid product manifest')
    for product,entry in manifest['products'].items():
        relative=pathlib.Path(product)/entry['version']/entry['filename']
        source=(stage/relative).resolve()
        target=(root/relative).resolve()
        if not source.is_relative_to(stage) or not target.is_relative_to(root):
            raise ValueError('Invalid installer path')
        if source.stat().st_size!=entry['size'] or hashlib.sha256(source.read_bytes()).hexdigest()!=entry['sha256']:
            raise ValueError('Checksum mismatch')
        if target.exists() and hashlib.sha256(target.read_bytes()).hexdigest()!=entry['sha256']:
            raise ValueError('Published version differs; refusing overwrite')
    for product,entry in manifest['products'].items():
        relative=pathlib.Path(product)/entry['version']
        (root/relative).mkdir(parents=True,exist_ok=True)
        for name in [entry['filename'],'SHA256SUMS.txt']:
            source=stage/relative/name
            source.chmod(0o644)
            os.replace(source,root/relative/name)
    (stage/'releases.json').chmod(0o644)
    os.replace(stage/'releases.json',root/'releases.json')
    print('Published verified mirror:',', '.join(k+' '+v['version'] for k,v in manifest['products'].items()))
finally:
    shutil.rmtree(stage)
    pathlib.Path(ARCHIVE).unlink(missing_ok=True)
'''
    worker = 'ARCHIVE = ' + repr(remote_archive) + '\n' + worker
    subprocess.run(['ssh', '-o', 'BatchMode=yes', '-o', 'StrictHostKeyChecking=yes', args.host, 'python3', '-'], input=worker.encode(), check=True)
    # Keep the development snapshot in step with the successfully published mirror.
    (website / 'public/downloads/releases.json').write_bytes((mirror / 'releases.json').read_bytes())


if __name__ == '__main__':
    main()
