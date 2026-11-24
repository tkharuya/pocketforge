#!/usr/bin/env python3
"""Reproducibly preserve dependency notices in the source tree and APK assets.

Consumes the pinned dependencies.json manifest. Copies original POMs and license
entries present in downloaded AARs/runtime JARs without rewriting their text.
The output contains an offline HTML index, machine-readable catalog and source
SHA-256s. It deliberately does not download licenses during an app build.
"""
from __future__ import annotations

import hashlib
import html
import json
from pathlib import Path, PurePosixPath
import re
import shutil
import tempfile
import xml.etree.ElementTree as ET
import zipfile

HERE = Path(__file__).resolve().parent
OUTPUT = HERE.parent / "app" / "src" / "main" / "assets" / "third_party_notices"
NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def slug(value: str) -> str:
    result = re.sub(r"[^a-zA-Z0-9_.-]+", "_", value).strip("._")
    return result or "notice"


def notice_entry(name: str, is_aar: bool) -> bool:
    parts = PurePosixPath(name).parts
    if not parts or name.endswith("/"):
        return False
    base = parts[-1].upper()
    is_notice = (base.startswith("LICENSE") or base.startswith("LICENCE")
                 or base.startswith("NOTICE") or base.startswith("COPYING")
                 or base.startswith("COPYRIGHT") or base.startswith("THIRD_PARTY_LICENSE"))
    if not is_notice:
        return False
    return len(parts) == 1 or (not is_aar and parts[0].upper() == "META-INF")


def main() -> None:
    modules = json.loads((HERE / "dependencies.json").read_text(encoding="utf-8"))
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    staging = Path(tempfile.mkdtemp(prefix=".third-party-notices-", dir=OUTPUT.parent))
    catalog = []
    try:
        for module in sorted(modules, key=lambda x: tuple(x["coordinate"])):
            coordinate = ":".join(module["coordinate"])
            folder = slug(coordinate)
            dest = staging / folder
            dest.mkdir()
            item = {"coordinate": coordinate, "licensesDeclaredInPom": [], "files": []}

            def preserve(source: str, original: str, data: bytes) -> None:
                digest = hashlib.sha256(data).hexdigest()
                # Include source and the original entry path to avoid collisions.
                name = slug(Path(source).name + "__" + original) + ".txt"
                rel = folder + "/" + name
                target = staging / rel
                if target.exists() and target.read_bytes() != data:
                    name = name[:-4] + "_" + digest[:12] + ".txt"
                    rel = folder + "/" + name
                    target = staging / rel
                target.write_bytes(data)
                item["files"].append({"path": rel, "source": source,
                                      "entry": original, "sha256": digest})

            pom_path = HERE / module["pom"]
            pom_bytes = pom_path.read_bytes()
            preserve(module["pom"], "original-pom.xml", pom_bytes)
            pom = ET.fromstring(pom_bytes)
            for license_node in pom.findall("m:licenses/m:license", NS):
                item["licensesDeclaredInPom"].append({
                    "name": license_node.findtext("m:name", "", NS),
                    "url": license_node.findtext("m:url", "", NS),
                    "distribution": license_node.findtext("m:distribution", "", NS),
                })

            sources = []
            if module.get("packaging") == "aar" and module.get("artifact"):
                sources.append((module["artifact"], True))
            sources.extend((jar, False) for jar in module.get("jars", []))
            seen = set()
            for source, is_aar in sources:
                if source in seen:
                    continue
                seen.add(source)
                with zipfile.ZipFile(HERE / source) as archive:
                    for name in sorted(archive.namelist()):
                        if notice_entry(name, is_aar):
                            preserve(source, name, archive.read(name))
            catalog.append(item)

        (staging / "index.json").write_text(
            json.dumps({"description": "Original dependency notices and POM license declarations",
                        "modules": catalog}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        page = ["<!doctype html><html lang=\"ja\"><meta charset=\"utf-8\">",
                "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">",
                "<title>PocketForge — Third-party notices</title>",
                "<style>body{font:16px/1.6 sans-serif;max-width:900px;margin:24px auto;padding:0 16px;background:#f6f7fa;color:#16202a}h1{font-size:24px}h2{font-size:17px;overflow-wrap:anywhere}section{padding:16px;margin:16px 0;background:white;border:1px solid #d4dbe3;border-radius:12px}a{color:#145ab3;overflow-wrap:anywhere}li{margin:6px 0}small{color:#536273}</style>",
                "<h1>Third-party notices</h1>",
                "<p>PocketForge に同梱した依存ライブラリのライセンス表示です。原本の通知と、各配布元 POM のライセンス宣言を保持しています。</p>",
                "<p>These files preserve notices supplied with the dependencies. The original POM records license declarations and source URLs; it is not a replacement for those license terms.</p>"]
        for item in catalog:
            page.append("<section><h2>" + html.escape(item["coordinate"]) + "</h2><ul>")
            for lic in item["licensesDeclaredInPom"]:
                label = html.escape(lic["name"] or "Declared license")
                url = lic["url"]
                if url.startswith(("https://", "http://")):
                    label = '<a href="' + html.escape(url, quote=True) + '">' + label + "</a>"
                page.append("<li>" + label + "</li>")
            for notice in item["files"]:
                page.append('<li><a href="' + html.escape(notice["path"], quote=True) + '">'
                            + html.escape(notice["entry"]) + "</a></li>")
            page.append("</ul></section>")
        page.append("<p><small>Generated from the pinned dependency catalog. No credentials or project content are included.</small></p></html>\n")
        (staging / "index.html").write_text("\n".join(page), encoding="utf-8")

        if OUTPUT.exists():
            shutil.rmtree(OUTPUT)
        staging.replace(OUTPUT)
        count = sum(len(item["files"]) for item in catalog)
        print(f"Preserved {count} notice/POM files from {len(catalog)} modules in {OUTPUT}")
    except BaseException:
        shutil.rmtree(staging, ignore_errors=True)
        raise


if __name__ == "__main__":
    main()
