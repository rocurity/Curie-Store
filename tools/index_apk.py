#!/usr/bin/env python3
"""Add or update an app in site/store.json from a signed release APK.

Usage:
  python3 tools/index_apk.py app-release.apk --repo owner/name --tag v1.0.0 \
      --author owner --category tools --description "What it does"

Needs aapt2 and apksigner (Android SDK build-tools) on PATH.
"""
import argparse, hashlib, json, re, subprocess, sys, datetime, pathlib, zipfile

def run(cmd):
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode: sys.exit(f"{cmd[0]} failed:\n{r.stderr or r.stdout}")
    return r.stdout

ap = argparse.ArgumentParser()
ap.add_argument("apk"); ap.add_argument("--repo", required=True); ap.add_argument("--tag", required=True)
ap.add_argument("--author"); ap.add_argument("--category", default="tools")
ap.add_argument("--description", default=""); ap.add_argument("--store", default="site/store.json")
ap.add_argument("--allow-key-change", action="store_true")
a = ap.parse_args()

apk = pathlib.Path(a.apk)
badging = run(["aapt2", "dump", "badging", str(apk)])
pkg = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'", badging)
if not pkg: sys.exit("Could not read package info")
app_id, vcode, vname = pkg.group(1), int(pkg.group(2)), pkg.group(3)
label = (re.search(r"application-label:'([^']*)'", badging) or [None, app_id])[1]
perms = sorted(set(re.findall(r"uses-permission: name='([^']+)'", badging)))

certs = run(["apksigner", "verify", "--print-certs", str(apk)])
cert = re.search(r"certificate SHA-256 digest: ([0-9a-fA-F]+)", certs)
if not cert: sys.exit("Could not read signing certificate")
cert = cert.group(1).lower()
if "CN=Android Debug" in certs: sys.exit("REJECTED: APK is signed with a debug key")

sha = hashlib.sha256(apk.read_bytes()).hexdigest()
store_path = pathlib.Path(a.store)
store = json.loads(store_path.read_text())
old = next((x for x in store["apps"] if x["id"] == app_id), None)

if old and old["latest"]["certSha256"] != cert and not a.allow_key_change:
    sys.exit(f"REJECTED: signing key changed for {app_id}\n  known: {old['latest']['certSha256']}\n  new:   {cert}")
if old and vcode <= old["latest"]["versionCode"]:
    sys.exit("REJECTED: versionCode must increase")

# Extract the launcher icon (PNG/WebP only; adaptive XML icons are skipped)
icon = None
cands = re.findall(r"application-icon-(\d+):'([^']+\.(?:png|webp))'", badging)
if cands:
    path = max(cands, key=lambda c: int(c[0]))[1]
    out = pathlib.Path(a.store).parent / "icons"; out.mkdir(exist_ok=True)
    with zipfile.ZipFile(apk) as z: (out / f"{app_id}.png").write_bytes(z.read(path))
    icon = f"icons/{app_id}.png"

entry = {
    "id": app_id, "name": label, "author": a.author or a.repo.split("/")[0], "repo": a.repo,
    "description": a.description or (old or {}).get("description", ""),
    "category": a.category if not old else old["category"],
    "latest": {
        "version": vname, "versionCode": vcode,
        "apkUrl": f"https://github.com/{a.repo}/releases/download/{a.tag}/{apk.name}",
        "sha256": sha, "certSha256": cert, "sizeBytes": apk.stat().st_size, "permissions": perms,
    },
}
entry["icon"] = icon or (old or {}).get("icon", "")
store["apps"] = [x for x in store["apps"] if x["id"] != app_id] + [entry]
store["updated"] = datetime.date.today().isoformat()
store_path.write_text(json.dumps(store, indent=2) + "\n")
print(f"Indexed {label} {vname} ({app_id})\n  cert {cert}\n  sha256 {sha}")
