"""Validate version tags and package a verified, fixed-signature field APK."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
VERSION = r'(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)(?:-[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?'


def version_for_tag(tag, gradle):
    if not re.fullmatch('v' + VERSION, tag):
        raise ValueError('Expected a version tag such as v0.1.5-field')
    version = tag[1:]
    if '-' in version:
        for part in version.split('-', 1)[1].split('.'):
            if part.isdigit() and len(part) > 1 and part.startswith('0'):
                raise ValueError('Numeric prerelease identifiers cannot have leading zeroes')
    names = re.findall(r"versionName\s+'([^']+)'", gradle)
    codes = re.findall(r'versionCode\s+(\d+)', gradle)
    if names != [version] or len(codes) != 1 or int(codes[0]) <= 0:
        raise ValueError('Tag must match app/build.gradle versionName and a positive versionCode')
    return version, int(codes[0])


def check_signature(output, expected):
    fingerprints = re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})$', output, re.M)
    if not re.fullmatch(r'[0-9a-fA-F]{64}', expected) or [v.lower() for v in fingerprints] != [expected.lower()]:
        raise ValueError('APK signing certificate does not match the pinned field certificate')


def release_notes(version, changelog):
    match = re.search(r'^## ' + re.escape(version) + r'\s*\n(.*?)(?=^## |\Z)', changelog, re.M | re.S)
    if not match or not match[1].strip():
        raise ValueError('Missing changelog for this version')
    return (match[1].strip() + '\n\n此 APK 为固定签名的实测 Debug 构建，支持 ADB 取数；尚不能控车。'
            '\n可覆盖使用相同签名的旧实测版，保留本机记录。\n')


def git(*args):
    return subprocess.check_output(['git', *args], cwd=ROOT, text=True).strip()


def main():
    if len(sys.argv) != 3 or sys.argv[1] not in ('validate', 'package'):
        raise ValueError('Usage: release.py validate|package vVERSION')
    command, tag = sys.argv[1:]
    version, code = version_for_tag(tag, (ROOT / 'app/build.gradle').read_text())
    notes = release_notes(version, (ROOT / 'CHANGELOG.md').read_text())
    if command == 'validate':
        if git('rev-parse', tag + '^{commit}') != git('rev-parse', 'HEAD'):
            raise ValueError('Tag must point to the checked-out commit')
        subprocess.run(['git', 'merge-base', '--is-ancestor', 'HEAD', 'origin/main'], cwd=ROOT, check=True)
        print(f'Validated {tag}, versionCode {code}, main ancestry')
        return
    output = ROOT / 'app/build/outputs/apk/debug'
    metadata = json.loads((output / 'output-metadata.json').read_text())
    elements = metadata['elements']
    if (metadata['applicationId'] != 'local.leo.qingqi' or len(elements) != 1
            or elements[0]['versionName'] != version or elements[0]['versionCode'] != code
            or elements[0]['outputFile'] != 'app-debug.apk'):
        raise ValueError('APK output metadata does not match the tag')
    apk = output / 'app-debug.apk'
    sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    if not sdk:
        raise ValueError('ANDROID_HOME must point to the Android SDK')
    result = subprocess.check_output([str(Path(sdk) / 'build-tools/36.0.0/apksigner'),
                                      'verify', '--verbose', '--print-certs', str(apk)], text=True)
    check_signature(result, (ROOT / 'app/signing-certificate.sha256').read_text().strip())
    dist = ROOT / 'dist'
    dist.mkdir(exist_ok=True)
    # Do not accidentally publish an older APK left in this output directory.
    if any(dist.iterdir()):
        raise ValueError('dist must be empty before packaging')
    name = f'qingqi-{version}.apk'
    shutil.copyfile(apk, dist / name)
    digest = hashlib.sha256((dist / name).read_bytes()).hexdigest()
    (dist / 'SHA256SUMS').write_text(f'{digest}  {name}\n')
    (dist / 'RELEASE_NOTES.md').write_text(notes)
    print(f'Packaged {name}; fixed signing certificate verified')


if __name__ == '__main__':
    main()
