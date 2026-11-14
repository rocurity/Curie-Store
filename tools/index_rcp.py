#!/usr/bin/env python3
"""Add or update a compiled Curie .rcp in store/store.json.

  python3 tools/index_rcp.py dist/Curie-Notes.rcp --category tools --description "..."
  git add store tools && git commit -m "Add app" && git push

Only compiled packages (built with tools/rcpc) are accepted: source zips and packages with
plain Lua / XML inside are refused. The package is copied to store/apps/<id>-<version>.rcp and
its SHA-256 and size are recorded.  A version that does not increase is refused.
"""
import argparse, datetime, hashlib, json, os, re, shutil, struct, sys
import xml.etree.ElementTree as ET

def ver(v):
    return tuple(int(x) if x.isdigit() else 0 for x in re.split(r"[.\-]", v))

def read_rcp(path):
    raw = open(path, "rb").read()
    if raw[:2] == b"PK":
        sys.exit("this is a source zip. Compile it first:  tools/rcpc <app-folder> <out.rcp>")
    if raw[:4] != b"RCP1" or len(raw) < 40:
        sys.exit("not a compiled Curie package (missing RCP1 header)")
    body, tail = raw[:-32], raw[-32:]
    if hashlib.sha256(body).digest() != tail:
        sys.exit("package is damaged (checksum mismatch)")
    pos = 4
    (n,) = struct.unpack(">H", body[pos:pos + 2]); pos += 2
    entries = {}
    for _ in range(n):
        (l,) = struct.unpack(">H", body[pos:pos + 2]); pos += 2
        name = body[pos:pos + l].decode("utf-8"); pos += l
        kind = body[pos]; pos += 1
        (ln,) = struct.unpack(">I", body[pos:pos + 4]); pos += 4
        data = body[pos:pos + ln]; pos += ln
        if name.startswith("/") or "\\" in name or ".." in name.split("/"):
            sys.exit(f"unsafe path in package: {name}")
        if kind == 0 and (name.endswith(".lua") or (name.endswith(".xml") and name != "manifest.xml")):
            sys.exit(f"{name}: source file inside a package")
        if kind == 2 and data[:1] != b"\x1b":
            sys.exit(f"{name}: not Lua bytecode")
        if kind == 1 and data[:4] != b"RUI1":
            sys.exit(f"{name}: bad compiled UI")
        entries[name] = (kind, data)
    return raw, entries

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("rcp")
    ap.add_argument("--store", default="store/store.json")
    ap.add_argument("--category", default="tools")
    ap.add_argument("--description", default="")
    a = ap.parse_args()

    raw, entries = read_rcp(a.rcp)
    if "manifest.xml" not in entries:
        sys.exit("manifest.xml missing")
    m = ET.fromstring(entries["manifest.xml"][1])
    g = lambda t, d="": (m.findtext(t) or d).strip()
    app_id, name, version = g("id"), g("name"), g("version", "0")
    if not re.fullmatch(r"[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+", app_id):
        sys.exit(f"bad id '{app_id}'")
    if not name:
        sys.exit("manifest has no <name>")
    for t, d in (("entry-interface", "interface/main.xml"), ("entry-script", "scripts/main.lua")):
        if g(t, d) not in entries:
            sys.exit(f"missing {g(t, d)}")
    perms = [p.text.strip() for p in m.findall("permissions/permission") if p.text and p.text.strip()]

    sha = hashlib.sha256(raw).hexdigest()
    base = os.path.dirname(os.path.abspath(a.store))
    os.makedirs(os.path.join(base, "apps"), exist_ok=True)
    rel = f"apps/{app_id}-{version}.rcp"

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
    shutil.copy(a.rcp, os.path.join(base, rel))
    store["updated"] = datetime.date.today().isoformat()
    json.dump(store, open(a.store, "w"), indent=2)
    print(f"indexed {name} {version} ({len(raw)} bytes) sha256={sha}")

main()
