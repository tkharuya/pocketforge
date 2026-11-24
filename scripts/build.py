#!/usr/bin/env python3
"""Build/sign the personal APK with JDK 17+, Android SDK 36 and Python 3.

Usage: scripts/build.sh --sdk /path/to/sdk --jdk /path/to/jdk
Environment fallbacks: ANDROID_SDK_ROOT / ANDROID_HOME and JAVA_HOME.
First fetch the pinned dependencies with third_party/fetch_google_auth.py.
The generated signing/ directory is private and must be excluded from source ZIPs.
"""
from __future__ import annotations
import argparse
import copy
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
ANDROID = '{http://schemas.android.com/apk/res/android}'
TOOLS = '{http://schemas.android.com/tools}'
PACKAGE = 'jp.haruyalab.pocketforge'
ET.register_namespace('android', ANDROID[1:-1])

def run(args: list[str | Path], **kwargs):
    subprocess.run([str(a) for a in args], check=True, **kwargs)

def qualified(name: str, package: str) -> str:
    return package + name if name.startswith('.') else package + '.' + name if '.' not in name else name

def clean(element: ET.Element, package: str):
    for node in element.iter():
        for attr, value in list(node.attrib.items()):
            if attr.startswith(TOOLS):
                del node.attrib[attr]
                continue
            value = value.replace('${applicationId}', PACKAGE)
            if '${' in value:
                raise RuntimeError('Unresolved manifest placeholder: ' + value)
            if attr == ANDROID + 'name' and node.tag in ('application', 'activity', 'activity-alias', 'service', 'receiver', 'provider'):
                value = qualified(value, package)
            node.set(attr, value)

def merge_manifest(source: Path, library_manifests: list[Path], target: Path) -> list[str]:
    root = ET.parse(source).getroot()
    root.set('package', PACKAGE)
    clean(root, PACKAGE)
    application = root.find('application')
    if application is None:
        raise RuntimeError('App manifest has no application')
    packages: list[str] = []
    for path in library_manifests:
        lib = ET.parse(path).getroot()
        package = lib.get('package', '')
        if package and package not in packages:
            packages.append(package)
        clean(lib, package)
        for node in lib:
            if node.tag == 'uses-sdk':
                continue
            if node.tag == 'application':
                for key, value in node.attrib.items():
                    if key not in application.attrib:
                        application.set(key, value)
                for child in node:
                    name = child.get(ANDROID + 'name')
                    existing = [c for c in application if c.tag == child.tag and c.get(ANDROID + 'name') == name]
                    if not existing:
                        application.append(copy.deepcopy(child))
            elif node.tag == 'queries':
                queries = root.find('queries')
                if queries is None:
                    root.append(copy.deepcopy(node))
                else:
                    for child in node:
                        if not any(ET.tostring(child) == ET.tostring(old) for old in queries):
                            queries.append(copy.deepcopy(child))
            else:
                name = node.get(ANDROID + 'name')
                if not any(old.tag == node.tag and old.get(ANDROID + 'name') == name for old in root):
                    root.append(copy.deepcopy(node))
    root.set(ANDROID + 'versionCode', '1')
    root.set(ANDROID + 'versionName', '1.0.0')
    ET.ElementTree(root).write(target, encoding='utf-8', xml_declaration=True)
    return packages

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk', default=os.environ.get('ANDROID_SDK_ROOT', os.environ.get('ANDROID_HOME', '')))
    parser.add_argument('--jdk', default=os.environ.get('JAVA_HOME', ''))
    parser.add_argument('--build-tools', help='Absolute build-tools directory, or a version under SDK/build-tools')
    args = parser.parse_args()
    sdk = Path(args.sdk) if args.sdk else None
    if not sdk or not (sdk / 'platforms/android-36/android.jar').is_file():
        parser.error('Set --sdk or ANDROID_SDK_ROOT to an SDK containing platforms/android-36/android.jar')
    if args.jdk:
        jdk = Path(args.jdk)
    else:
        javac = shutil.which('javac')
        if not javac:
            parser.error('Set --jdk or JAVA_HOME to JDK 17 or newer')
        jdk = Path(javac).resolve().parents[1]
    if args.build_tools:
        bt = Path(args.build_tools)
        if not bt.is_absolute():
            bt = sdk / 'build-tools' / bt
    else:
        choices = [p for p in (sdk / 'build-tools').iterdir() if (p / 'aapt2').is_file()]
        if not choices:
            parser.error('Install Android SDK build-tools')
        bt = sorted(choices, key=lambda p: tuple(int(x) for x in re.findall(r'\d+', p.name)))[-1]
    for tool in ('aapt2', 'zipalign', 'lib/d8.jar', 'lib/apksigner.jar'):
        if not (bt / tool).is_file():
            parser.error('Missing build tool: ' + str(bt / tool))
    deps_file = ROOT / 'third_party/dependencies.json'
    if not deps_file.is_file():
        parser.error('First run: python3 third_party/fetch_google_auth.py')
    dependencies = json.loads(deps_file.read_text())
    third = ROOT / 'third_party'
    run([sys.executable, third / 'collect_notices.py'])
    jars: list[Path] = []
    resources: list[Path] = []
    manifests: list[Path] = []
    for dependency in dependencies:
        artifact = dependency.get('artifact')
        if artifact:
            file = third / artifact
            if hashlib.sha256(file.read_bytes()).hexdigest() != dependency['sha256']:
                raise RuntimeError('Dependency hash mismatch: ' + artifact)
        jars.extend(third / p for p in dependency.get('jars', []))
        if dependency.get('resources'):
            resources.append(third / dependency['resources'])
        if dependency.get('manifest'):
            manifests.append(third / dependency['manifest'])
    work = ROOT / 'build/manual'
    if work.exists():
        shutil.rmtree(work)
    for name in ('resources', 'generated', 'classes', 'dex'):
        (work / name).mkdir(parents=True)
    release = ROOT / 'build/release'
    release.mkdir(parents=True, exist_ok=True)
    manifest = work / 'AndroidManifest.xml'
    packages = merge_manifest(ROOT / 'app/src/main/AndroidManifest.xml', manifests, manifest)
    print('Compiling resources…', flush=True)
    resources.append(ROOT / 'app/src/main/res')
    compiled: list[Path] = []
    for i, resource in enumerate(resources):
        if resource.is_dir() and any(resource.rglob('*.*')):
            target = work / 'resources' / f'{i:03d}.zip'
            run([bt / 'aapt2', 'compile', '--dir', resource, '-o', target])
            compiled.append(target)
    apk_resources = work / 'resources.apk'
    link: list[str | Path] = [bt / 'aapt2', 'link', '-o', apk_resources, '-I', sdk / 'platforms/android-36/android.jar',
                           '--manifest', manifest, '--java', work / 'generated', '--min-sdk-version', '26',
                           '--target-sdk-version', '36', '--auto-add-overlay']
    if packages:
        link += ['--extra-packages', ':'.join(packages)]
    for resource in compiled:
        link += ['-R', resource]
    assets = ROOT / 'app/src/main/assets'
    if assets.is_dir():
        link += ['-A', assets]
    run(link)
    print('Compiling Java…', flush=True)
    sources = sorted((ROOT / 'app/src/main/java').rglob('*.java')) + sorted((work / 'generated').rglob('*.java'))
    argfile = work / 'sources.txt'
    argfile.write_text('\n'.join('"' + str(p) + '"' for p in sources) + '\n')
    classpath = os.pathsep.join(str(p) for p in [sdk / 'platforms/android-36/android.jar', *jars])
    run([jdk / 'bin/javac', '-encoding', 'UTF-8', '-source', '17', '-target', '17',
         '-classpath', classpath, '-d', work / 'classes', '@' + str(argfile)])
    appjar = work / 'app.jar'
    run([jdk / 'bin/jar', '--create', '--file', appjar, '-C', work / 'classes', '.'])
    print('Compiling DEX…', flush=True)
    run([jdk / 'bin/java', '-Xmx3g', '-cp', bt / 'lib/d8.jar', 'com.android.tools.r8.D8', '--release',
         '--min-api', '26', '--lib', sdk / 'platforms/android-36/android.jar', '--output', work / 'dex', appjar, *jars])
    unsigned = work / 'unsigned.apk'
    shutil.copyfile(apk_resources, unsigned)
    with zipfile.ZipFile(unsigned, 'a', compression=zipfile.ZIP_DEFLATED) as archive:
        for dex in sorted((work / 'dex').glob('*.dex')):
            archive.write(dex, dex.name)
        # Native and asset payloads from AARs, if the pinned graph gains any.
        known = set(archive.namelist())
        for dependency in dependencies:
            unpacked = dependency.get('unpacked')
            if not unpacked:
                continue
            aar = third / unpacked
            for dirname, output in (('jni', 'lib'), ('assets', 'assets')):
                folder = aar / dirname
                if folder.is_dir():
                    for file in folder.rglob('*'):
                        if not file.is_file():
                            continue
                        name = output + '/' + file.relative_to(folder).as_posix()
                        if name in known:
                            raise RuntimeError('Duplicate AAR payload: ' + name)
                        archive.write(file, name)
                        known.add(name)
    aligned = work / 'aligned.apk'
    run([bt / 'zipalign', '-f', '-p', '4', unsigned, aligned])
    signing = ROOT / 'signing'
    signing.mkdir(mode=0o700, exist_ok=True)
    os.chmod(signing, 0o700)
    key = signing / 'pocketforge-personal.p12'
    password = signing / 'password.txt'
    if key.exists() != password.exists():
        raise RuntimeError('Signing key/password pair is incomplete; restore both to retain update compatibility')
    if not key.exists():
        fd = os.open(password, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, 'w') as file:
            file.write(secrets.token_urlsafe(32) + '\n')
        try:
            run([jdk / 'bin/keytool', '-genkeypair', '-keystore', key, '-storetype', 'PKCS12',
                 '-storepass:file', password, '-keypass:file', password, '-alias', 'pocketforge-personal',
                 '-keyalg', 'RSA', '-keysize', '3072', '-validity', '10000',
                 '-dname', 'CN=PocketForge Personal, OU=Personal Development, O=PocketForge, C=JP'])
        except Exception:
            if not key.exists():
                password.unlink()
            raise
        os.chmod(key, 0o600)
    apk = release / 'pocketforge-1.0.0-personal.apk'
    run([jdk / 'bin/java', '-jar', bt / 'lib/apksigner.jar', 'sign', '--ks', key,
         '--ks-key-alias', 'pocketforge-personal', '--ks-pass', 'file:' + str(password),
         '--out', apk, aligned])
    verified = subprocess.check_output([str(jdk / 'bin/java'), '-jar', str(bt / 'lib/apksigner.jar'),
                                        'verify', '--verbose', '--print-certs', str(apk)], text=True)
    (release / 'signature-verification.txt').write_text(verified)
    match = re.search(r'Signer #1 certificate SHA-1 digest: ([0-9a-fA-F]+)', verified)
    if not match:
        raise RuntimeError('Cannot extract signing certificate SHA-1')
    fingerprint = ':'.join(match.group(1)[i:i+2].upper() for i in range(0, 40, 2))
    (release / 'google-oauth-android-sha1.txt').write_text('Android package: ' + PACKAGE + '\nSigning certificate SHA-1: ' + fingerprint + '\n')
    (release / 'SHA256SUMS.txt').write_text(hashlib.sha256(apk.read_bytes()).hexdigest() + '  ' + apk.name + '\n')
    run([bt / 'zipalign', '-c', '-p', '4', apk])
    print('Built and signature-verified: ' + str(apk), flush=True)
    print('Google OAuth Android certificate SHA-1: ' + fingerprint, flush=True)

if __name__ == '__main__':
    try:
        main()
    except subprocess.CalledProcessError as error:
        print('Build step failed with exit code ' + str(error.returncode), file=sys.stderr)
        sys.exit(error.returncode)
    except (OSError, RuntimeError, ValueError) as error:
        print('Build failed: ' + str(error), file=sys.stderr)
        sys.exit(1)
