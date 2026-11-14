#!/usr/bin/env python3
"""Add or update a Curie .rcp app in store/store.json.

  python3 tools/index_rcp.py Hello-Curie.rcp --category tools --description "..."
  git add store tools && git commit -m "Add app" && git push

Copies the package to store/apps/<id>-<version>.rcp and records its SHA-256 and size.
Rejects unsafe paths, a missing manifest, a bad id, and a version that does not increase.
"""
import argparse, datetime, hashlib, json, os, re, shutil, sys, zipfile
import xml.etree.ElementTree as ET

def ver(v):
    return tuple(int(x) if x.isdigit() else 0 for x in re.split(r"[.\-]", v))

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("rcp")
    ap.add_argument("--store", default="store/store.json")
    ap.add_argument("--category", default="tools")
    ap.add_argument("--description", default="")
    a = ap.parse_args()

    with zipfile.ZipFile(a.rcp) as z:
        names = z.namelist()
        for n in names:
            if n.startswith("/") or "\\" in n or ".." in n.split("/"):
                sys.exit(f"unsafe path in package: {n}")
        if "manifest.xml" not in names:
            sys.exit("manifest.xml missing")
        m = ET.fromstring(z.read("manifest.xml"))
    g = lambda t, d="": (m.findtext(t) or d).strip()
    app_id, name, version = g("id"), g("name"), g("version", "0")
    if not re.fullmatch(r"[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+", app_id):
        sys.exit(f"bad id '{app_id}'")
    if not name:
        sys.exit("manifest has no <name>")
    for t in ("entry-interface", "entry-script"):
        p = g(t, "interface/main.xml" if t == "entry-interface" else "scripts/main.lua")
        if p not in names:
            sys.exit(f"missing {p}")
    perms = [p.text.strip() for p in m.findall("permissions/permission") if p.text and p.text.strip()]

    raw = open(a.rcp, "rb").read()
    sha = hashlib.sha256(raw).hexdigest()
    base = os.path.dirname(os.path.abspath(a.store))
    os.makedirs(os.path.join(base, "apps"), exist_ok=True)
    rel = f"apps/{app_id}-{version}.rcp"
    shutil.copy(a.rcp, os.path.join(base, rel))

    store = json.load(open(a.store))
    apps = store.setdefault("apps", [])
    latest = {
        "version": version, "rcpUrl": rel, "sha256": sha, "sizeBytes": len(raw),
        "permissions": perms, "certSha256": "unsigned", "notes": "",
    }
    cur = next((x for x in apps if x["id"] == app_id), None)
    if cur:
        if ver(version) <= ver(cur["latest"].get("version", "0")):
            sys.exit(f"version {version} does not increase (store has {cur['latest'].get('version')})")
        cur["latest"] = latest
        cur["name"] = name
        if a.description:
            cur["description"] = a.description
    else:
        apps.append({
            "id": app_id, "name": name, "author": g("developer", "unknown"),
            "category": a.category, "type": "rcp", "description": a.description, "latest": latest,
        })
    store["updated"] = datetime.date.today().isoformat()
    json.dump(store, open(a.store, "w"), indent=2)
    print(f"indexed {name} {version} ({len(raw)} bytes) sha256={sha}")

main()
