#!/usr/bin/env python3
"""Fetch the pinned Google Identity dependency graph for the SDK-only build.

This small resolver supports the ordinary compile/runtime POMs in this exact
graph. It fails on unresolved properties instead of silently omitting a library.
Artifacts and original POMs are retained with SHA-256s in dependencies.json.
"""
import concurrent.futures as futures
import hashlib
import json
from pathlib import Path
import re
import shutil
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

HERE = Path(__file__).resolve().parent
CACHE = HERE / 'maven'
UNPACKED = HERE / 'unpacked'
NS = {'m': 'http://maven.apache.org/POM/4.0.0'}
ROOT = ('com.google.android.gms', 'play-services-auth', '22.0.0')

def version_key(v):
    return tuple((0, int(x)) if x.isdigit() else (1, x) for x in re.split(r'[.\-]', v))

def fetch(g, a, v, extension):
    name = f'{a}-{v}.{extension}'
    dest = CACHE / g / a / v / name
    if dest.exists() and dest.stat().st_size:
        return dest
    rel = f'{g.replace(".", "/")}/{a}/{v}/{name}'
    bases = (['https://dl.google.com/dl/android/maven2/'] if g.startswith(('androidx.', 'com.google.android.gms'))
             else ['https://repo.maven.apache.org/maven2/', 'https://dl.google.com/dl/android/maven2/'])
    errors=[]
    for base in bases:
        try:
            with urllib.request.urlopen(base + rel, timeout=45) as r:
                data = r.read()
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_bytes(data)
            return dest
        except Exception as e:
            errors.append(str(e))
    raise RuntimeError(f'Cannot download {rel}: {errors}')

def txt(el, key, default=''):
    e=el.find('m:' + key, NS)
    return (e.text or '').strip() if e is not None else default

def pom(coord):
    g,a,v=coord
    path=fetch(g,a,v,'pom')
    root=ET.parse(path).getroot()
    props={'project.version':v,'pom.version':v,'project.groupId':g}
    pe=root.find('m:properties',NS)
    if pe is not None:
        for p in pe: props[p.tag.split('}')[-1]]=p.text or ''
    def expand(s):
        for _ in range(8):
            n=re.sub(r'\$\{([^}]+)\}',lambda m:props.get(m.group(1),m.group(0)),s)
            if n==s: break
            s=n
        if '${' in s: raise RuntimeError(f'Unresolved POM property {coord}: {s}')
        if s.startswith('[') and s.endswith(']') and ',' not in s: s=s[1:-1]
        return s
    management={}
    for dep in root.findall('m:dependencyManagement/m:dependencies/m:dependency',NS):
        dg,da,dv=expand(txt(dep,'groupId')),expand(txt(dep,'artifactId')),expand(txt(dep,'version'))
        if txt(dep,'scope')=='import' and txt(dep,'type')=='pom':
            bp=ET.parse(fetch(dg,da,dv,'pom')).getroot()
            for bd in bp.findall('m:dependencyManagement/m:dependencies/m:dependency',NS):
                bversion=txt(bd,'version').replace('${project.version}',dv)
                management[(txt(bd,'groupId'),txt(bd,'artifactId'))]=bversion
        else: management[(dg,da)]=dv
    deps=[]
    for dep in root.findall('m:dependencies/m:dependency',NS):
        if txt(dep,'scope','compile') not in ('compile','runtime') or txt(dep,'optional')=='true': continue
        dg,da=expand(txt(dep,'groupId')),expand(txt(dep,'artifactId'))
        dv=expand(txt(dep,'version',management.get((dg,da),'')))
        if not dv: raise RuntimeError(f'Missing dependency version: {coord}: {dg}:{da}')
        if any(c in dv for c in '[,()]'): raise RuntimeError(f'Unsupported version range: {dv}')
        deps.append((dg,da,dv))
    return {'coordinate':coord,'packaging':txt(root,'packaging','jar'),'deps':deps,'pom':str(path.relative_to(HERE))}

def main():
    selected={(ROOT[0],ROOT[1]):ROOT[2]}
    known={}
    with futures.ThreadPoolExecutor(max_workers=10) as pool:
        while True:
            missing=[(g,a,v) for (g,a),v in selected.items() if (g,a,v) not in known]
            if not missing: break
            for info in pool.map(pom,missing):
                c=tuple(info['coordinate']);known[c]=info
                print('POM',':'.join(c),flush=True)
                for dg,da,dv in info['deps']:
                    old=selected.get((dg,da))
                    if old is None or version_key(dv)>version_key(old): selected[(dg,da)]=dv
        resolved=[known[(g,a,v)] for (g,a),v in sorted(selected.items())]
        def binary(info):
            g,a,v=info['coordinate'];kind=info['packaging']
            if kind=='pom':return info
            path=fetch(g,a,v,kind)
            info['artifact']=str(path.relative_to(HERE));info['sha256']=hashlib.sha256(path.read_bytes()).hexdigest()
            if kind=='aar':
                target=UNPACKED/(g+'.'+a+'-'+v)
                if target.exists():shutil.rmtree(target)
                target.mkdir(parents=True)
                with zipfile.ZipFile(path) as z:z.extractall(target)
                info['unpacked']=str(target.relative_to(HERE))
                runtime_jars = ([target/'classes.jar'] if (target/'classes.jar').exists() else [])
                runtime_jars += sorted((target/'libs').glob('*.jar'))
                info['jars']=[str(p.relative_to(HERE)) for p in runtime_jars]
                info['resources']=str((target/'res').relative_to(HERE)) if (target/'res').exists() else None
                info['manifest']=str((target/'AndroidManifest.xml').relative_to(HERE))
            elif kind=='jar':info['jars']=[info['artifact']]
            else:raise RuntimeError(f'Unsupported packaging {kind}')
            print('BIN',':'.join(info['coordinate']),flush=True)
            return info
        artifacts=list(pool.map(binary,resolved))
    (HERE/'dependencies.json').write_text(json.dumps(artifacts,indent=2)+'\n')
    jars=[str(HERE/p) for a in artifacts for p in a.get('jars',[])]
    (HERE/'classpath.txt').write_text(':'.join(jars)+'\n')
    print(f'Done: {len(artifacts)} modules / {len(jars)} jars',flush=True)

if __name__=='__main__':main()
