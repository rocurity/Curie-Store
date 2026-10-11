#!/usr/bin/env python3
"""Add or update an app in store/store.json from a signed release APK.

CLI:  python3 tools/index_apk.py app-release.apk --repo owner/name --tag v1.0.0 \
          --category tools --description "What it does"
Needs aapt2 and apksigner (Android SDK build-tools) on PATH.
Also imported by tools/submit.py.
"""
import argparse, datetime, hashlib, json, pathlib, re, subprocess, sys, zipfile

CATEGORIES = {"tools", "productivity", "media", "games", "social", "education", "security"}
# Permissions a reviewer should look at twice
SENSITIVE = {
    "CAMERA", "RECORD_AUDIO", "READ_SMS", "SEND_SMS", "RECEIVE_SMS", "READ_CONTACTS", "WRITE_CONTACTS",
    "READ_CALL_LOG", "WRITE_CALL_LOG", "CALL_PHONE", "READ_PHONE_STATE", "ACCESS_FINE_LOCATION",
    "ACCESS_BACKGROUND_LOCATION", "SYSTEM_ALERT_WINDOW", "REQUEST_INSTALL_PACKAGES", "BIND_ACCESSIBILITY_SERVICE",
    "MANAGE_EXTERNAL_STORAGE", "QUERY_ALL_PACKAGES", "BIND_DEVICE_ADMIN", "READ_EXTERNAL_STORAGE",
}
APP_ID_RE = re.compile(r"^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$")
APK_NAME_RE = re.compile(r"^[A-Za-z0-9_.\-]+\.apk$")


class Rejected(Exception):
    """The APK or request failed a check. The message is shown to the developer."""


def run(cmd):
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode:
        raise Rejected(f"{cmd[0]} failed: {(r.stderr or r.stdout).strip()[:300]}")
    return r.stdout


def index_apk(apk, repo, tag, store_path, author=None, category="tools", description="",
              allow_key_change=False, dry_run=False):
    """Check an APK and add it to the store index. Returns a summary dict. Raises Rejected."""
    apk = pathlib.Path(apk)
    store_path = pathlib.Path(store_path)
    if not APK_NAME_RE.match(apk.name):
        raise Rejected("The APK file name may only contain letters, numbers, dots, dashes and underscores")

    badging = run(["aapt2", "dump", "badging", str(apk)])
    pkg = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'", badging)
    if not pkg:
        raise Rejected("Could not read the app's package information")
    app_id, vcode, vname = pkg.group(1), int(pkg.group(2)), pkg.group(3)
    if not APP_ID_RE.match(app_id) or len(app_id) > 150:
        raise Rejected("The app's package name is not valid")
    m = re.search(r"application-label:'([^']*)'", badging)
    label = (m.group(1) if m else app_id).strip()[:60] or app_id
    perms = sorted(set(re.findall(r"uses-permission: name='([^']+)'", badging)))

    certs = run(["apksigner", "verify", "--print-certs", str(apk)])
    c = re.search(r"certificate SHA-256 digest: ([0-9a-fA-F]+)", certs)
    if not c:
        raise Rejected("Could not read the signing certificate")
    cert = c.group(1).lower()
    if "CN=Android Debug" in certs:
        raise Rejected("REJECTED: the APK is signed with a debug key. Sign it with your own release key")

    store = json.loads(store_path.read_text())
    old = next((x for x in store["apps"] if x["id"] == app_id), None)
    if old and old["repo"].lower() != repo.lower():
        raise Rejected(f"REJECTED: {app_id} already belongs to {old['repo']}")
    if old and old["latest"]["certSha256"] != cert and not allow_key_change:
        raise Rejected(f"REJECTED: the signing key changed for {app_id}. Updates must use the same key as the first release")
    if old and vcode <= old["latest"]["versionCode"]:
        raise Rejected(f"REJECTED: versionCode must increase (store has {old['latest']['versionCode']}, this release has {vcode})")

    sha = hashlib.sha256(apk.read_bytes()).hexdigest()
    category = category if category in CATEGORIES else "tools"

    icon = None
    cands = re.findall(r"application-icon-(\d+):'([^']+\.(?:png|webp))'", badging)
    if cands:
        path = max(cands, key=lambda x: int(x[0]))[1]
        icon = f"icons/{app_id}.png"
        if not dry_run:
            out = store_path.parent / "icons"
            out.mkdir(exist_ok=True)
            with zipfile.ZipFile(apk) as z:
                (out / f"{app_id}.png").write_bytes(z.read(path))

    entry = {
        "id": app_id, "name": label, "author": author or repo.split("/")[0], "repo": repo,
        "description": (description or (old or {}).get("description", ""))[:160],
        "category": category if not old else old["category"],
        "latest": {
            "version": vname, "versionCode": vcode,
            "apkUrl": f"https://github.com/{repo}/releases/download/{tag}/{apk.name}",
            "sha256": sha, "certSha256": cert, "sizeBytes": apk.stat().st_size, "permissions": perms,
        },
        "icon": icon or (old or {}).get("icon", ""),
    }
    if not dry_run:
        store["apps"] = [x for x in store["apps"] if x["id"] != app_id] + [entry]
        store["updated"] = datetime.date.today().isoformat()
        store_path.write_text(json.dumps(store, indent=2) + "\n")

    return {
        "id": app_id, "name": label, "version": vname, "versionCode": vcode, "permissions": perms,
        "sensitive": [p.rsplit(".", 1)[-1] for p in perms if p.rsplit(".", 1)[-1] in SENSITIVE],
        "certSha256": cert, "sha256": sha, "sizeBytes": apk.stat().st_size, "category": entry["category"],
        "isUpdate": bool(old), "previousVersion": old["latest"]["version"] if old else None,
        "tag": tag, "repo": repo,
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("apk"); ap.add_argument("--repo", required=True); ap.add_argument("--tag", required=True)
    ap.add_argument("--author"); ap.add_argument("--category", default="tools")
    ap.add_argument("--description", default=""); ap.add_argument("--store", default="store/store.json")
    ap.add_argument("--allow-key-change", action="store_true")
    a = ap.parse_args()
    try:
        s = index_apk(a.apk, a.repo, a.tag, a.store, a.author, a.category, a.description, a.allow_key_change)
    except Rejected as e:
        sys.exit(str(e))
    print(f"Indexed {s['name']} {s['version']} ({s['id']})\n  cert {s['certSha256']}\n  sha256 {s['sha256']}")


if __name__ == "__main__":
    main()
