#!/usr/bin/env python3
"""Run offline JVM regression tests. No account, OAuth request, or real Drive write.

python3 scripts/test.py --sdk /path/to/android-sdk --jdk /path/to/jdk
Pass --json /path/to/json.jar to reuse org.json 20250517; otherwise fetch and
SHA-256-verify it from Maven Central into the ignored build/test-deps directory.
Android Keystore, WebView/native UI, and actual OAuth require device testing.
"""
import argparse
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import sys
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
JSON_SHA256 = '3ea61b2a06e31edf1c91134fe9106b0ebb16628be169f3db75bc7a2b06b45796'
SOURCE_NAMES = ['ProjectStore', 'Http', 'SecureStore', 'ChatGptAuth', 'ResponseStream', 'AgentEngine', 'DriveSync']
TEST_CLASSES = ['ProjectStoreTest', 'jp.haruyalab.pocketforge.PocketAgentTest',
                'jp.haruyalab.pocketforge.AuthSecurityTest', 'jp.haruyalab.pocketforge.DriveSyncTest']

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk', default=os.environ.get('ANDROID_SDK_ROOT', os.environ.get('ANDROID_HOME', '')))
    parser.add_argument('--jdk', default=os.environ.get('JAVA_HOME', ''))
    parser.add_argument('--json', help='Existing org.json 20250517 JVM JAR')
    args = parser.parse_args()
    android = Path(args.sdk) / 'platforms/android-36/android.jar'
    if not android.is_file():
        parser.error('Set --sdk or ANDROID_SDK_ROOT to an SDK with Android API 36')
    jdk = Path(args.jdk) if args.jdk else Path(shutil.which('javac') or '/missing/javac').resolve().parents[1]
    if not (jdk / 'bin/javac').is_file():
        parser.error('Set --jdk or JAVA_HOME to JDK 17 or newer')
    json_jar = Path(args.json) if args.json else ROOT / 'build/test-deps/json-20250517.jar'
    if not json_jar.exists() and not args.json:
        json_jar.parent.mkdir(parents=True, exist_ok=True)
        with urllib.request.urlopen('https://repo.maven.apache.org/maven2/org/json/json/20250517/json-20250517.jar', timeout=45) as response:
            data = response.read()
        if hashlib.sha256(data).hexdigest() != JSON_SHA256:
            raise RuntimeError('Downloaded org.json hash mismatch')
        json_jar.write_bytes(data)
    if hashlib.sha256(json_jar.read_bytes()).hexdigest() != JSON_SHA256:
        raise RuntimeError('Expected the pinned org.json 20250517 JAR; hash mismatch')
    out = ROOT / 'build/jvm-tests'
    if out.exists():
        shutil.rmtree(out)
    out.mkdir(parents=True)
    main_sources = ROOT / 'app/src/main/java/jp/haruyalab/pocketforge'
    sources = [main_sources / (name + '.java') for name in SOURCE_NAMES]
    test_files = {file.stem: file for file in (ROOT / 'tests').rglob('*.java')}
    sources += [test_files[name.split('.')[-1]] for name in TEST_CLASSES]
    cp = os.pathsep.join(str(file) for file in (json_jar, android))
    subprocess.run([str(jdk / 'bin/javac'), '-encoding', 'UTF-8', '-source', '17', '-target', '17',
                    '-cp', cp, '-d', str(out), *map(str, sources)], check=True)
    report = []
    failures = []
    for name in TEST_CLASSES:
        print('Running ' + name, flush=True)
        result = subprocess.run([str(jdk / 'bin/java'), '-cp', str(out) + os.pathsep + cp, name],
                                text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        print(result.stdout, end='', flush=True)
        report.append(name + '\n' + result.stdout)
        if result.returncode:
            failures.append(name)
    (ROOT / 'build/test-report.txt').write_text('\n'.join(report), encoding='utf-8')
    if failures:
        raise RuntimeError('Failed tests: ' + ', '.join(failures))
    print('PASS: all four offline test suites. Report: build/test-report.txt')

if __name__ == '__main__':
    try:
        main()
    except (OSError, RuntimeError, subprocess.CalledProcessError) as error:
        print('Tests failed: ' + str(error), file=sys.stderr)
        sys.exit(1)
