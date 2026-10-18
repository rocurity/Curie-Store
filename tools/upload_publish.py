#!/usr/bin/env python3
"""Publisher for approved uploads: moves the files from the private mailbox into the app's public GitHub release.

It never opens or runs any file. Steps (each in its own workflow step, so no step has more than it needs):
  fetch     (has the secret)       ask the store backend which files to move
  download  (NO secret, NO token)  download them from the private bucket, check they look like what they claim
  release   (GitHub token only)    create the app's release and attach the files
  report    (has the secret)       tell the store backend the files are public (with the APK's checksum)
  fail      (has the secret)       tell the store backend the job broke

Environment: STORE_API_URL, INTERNAL_SECRET (fetch, report, fail); B2_ENDPOINT (download); GH_TOKEN (release)
"""
import argparse, base64, hashlib, json, os, pathlib, re, subprocess, sys, urllib.error, urllib.parse, urllib.request

ID_RE = re.compile(r"^[A-Za-z0-9-]{8,64}$")
NAME_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,150}$")
TAG_RE = re.compile(r"^app-[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$")
MAX = {"apk": 150 * 1024 * 1024, "icon": 1024 * 1024, "shot": 3 * 1024 * 1024}


def api(method, path, body=None):
    base = os.environ["STORE_API_URL"].rstrip("/")
    req = urllib.request.Request(base + path, method=method, data=json.dumps(body).encode() if body is not None else None,
                                 headers={"X-Internal-Secret": os.environ["INTERNAL_SECRET"], "Content-Type": "application/json", "User-Agent": "curie-store-publisher"})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.status, json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read() or b"{}")
        except Exception:
            return e.code, {}


def check_id(i):
    if not ID_RE.match(i or ""):
        sys.exit("Invalid submission id")
    return i


def magic_ok(role, b):
    if role == "apk":
        return b[:4] == b"PK\x03\x04"
    return b[:4] == b"\x89PNG" or b[:3] == b"\xff\xd8\xff" or (b[:4] == b"RIFF" and b[8:12] == b"WEBP")


def cmd_fetch(a):
    sid = check_id(a.id)
    code, info = api("GET", f"/internal/submissions/{sid}/publish")
    if code != 200:
        sys.exit(f"Nothing to publish ({code}): {info.get('error', '')}")
    pathlib.Path(a.out).write_text(json.dumps(info))


def validate(info):
    if not TAG_RE.match(info.get("tag", "")):
        sys.exit("Invalid release tag")
    for f in info["files"]:
        if f["role"] not in MAX or not NAME_RE.match(f["name"]):
            sys.exit(f"Invalid file in the plan: {f.get('name')}")


def cmd_download(a):
    info = json.loads(pathlib.Path(a.info).read_text())
    validate(info)
    host = os.environ.get("B2_ENDPOINT", "").strip().lower()
    out = pathlib.Path(a.dir); out.mkdir(parents=True, exist_ok=True)
    names, apk_sha = [], None
    for f in info["files"]:
        dest = out / f["name"]
        if f.get("inlineBase64"):
            data = base64.b64decode(f["inlineBase64"])
            if len(data) > MAX[f["role"]] or not magic_ok(f["role"], data):
                sys.exit(f"{f['name']} is not a valid {f['role']}")
            dest.write_bytes(data)
        else:
            u = urllib.parse.urlparse(f["downloadUrl"])
            if not host or (u.scheme != "https" and os.environ.get("CURIE_TEST") != "1") or (u.hostname or "").lower() != host:
                sys.exit("A download link is not in the store's storage")
            h, got, first = hashlib.sha256(), 0, b""
            with urllib.request.urlopen(urllib.request.Request(f["downloadUrl"], headers={"User-Agent": "curie-store-publisher"}), timeout=120) as r, open(dest, "wb") as fh:
                while True:
                    chunk = r.read(1 << 20)
                    if not chunk:
                        break
                    first = first or chunk[:16]
                    got += len(chunk)
                    if got > MAX[f["role"]] or got > f.get("size", MAX[f["role"]]):
                        sys.exit(f"{f['name']} is larger than it should be")
                    h.update(chunk); fh.write(chunk)
            if got != f.get("size", got) or not magic_ok(f["role"], first):
                sys.exit(f"{f['name']} is not what it should be")
            if f["role"] == "apk":
                apk_sha = h.hexdigest()
        names.append(f["name"])
    if not apk_sha:
        sys.exit("The plan has no app file")
    pathlib.Path(a.files).write_text(json.dumps({"sha256": apk_sha, "names": names}))
    print("downloaded", names)


def gh(*args):
    r = subprocess.run(["gh", *args], capture_output=True, text=True)
    return r.returncode, (r.stdout + r.stderr).strip()


def cmd_release(a):
    info = json.loads(pathlib.Path(a.info).read_text())
    validate(info)
    files = json.loads(pathlib.Path(a.files).read_text())["names"]
    tag, d = info["tag"], pathlib.Path(a.dir)
    if gh("release", "view", tag)[0] != 0:
        code, out = gh("release", "create", tag, "--title", str(info.get("appName", tag))[:80], "--notes", "Files for " + str(info.get("appName", tag))[:80])
        if code != 0:
            sys.exit("Could not create the release: " + out[-200:])
    code, out = gh("release", "upload", tag, *[str(d / n) for n in files], "--clobber")
    if code != 0:
        sys.exit("Could not attach the files: " + out[-200:])
    print("published", tag, files)


def cmd_report(a):
    sid = check_id(a.id)
    sha = json.loads(pathlib.Path(a.files).read_text())["sha256"]
    code, body = api("POST", f"/internal/submissions/{sid}/published", {"sha256": sha})
    print("reported:", code, body)
    if code >= 400:
        sys.exit(f"The store backend refused the result: {body.get('error', code)}")


def cmd_fail(a):
    api("POST", f"/internal/submissions/{check_id(a.id)}/status", {"status": "failed", "message": a.message[:400]})


def main():
    ap = argparse.ArgumentParser()
    sp = ap.add_subparsers(dest="cmd", required=True)
    f = sp.add_parser("fetch"); f.add_argument("--id", required=True); f.add_argument("--out", required=True); f.set_defaults(fn=cmd_fetch)
    d = sp.add_parser("download"); d.add_argument("--info", required=True); d.add_argument("--dir", required=True); d.add_argument("--files", required=True); d.set_defaults(fn=cmd_download)
    r = sp.add_parser("release"); r.add_argument("--info", required=True); r.add_argument("--dir", required=True); r.add_argument("--files", required=True); r.set_defaults(fn=cmd_release)
    p = sp.add_parser("report"); p.add_argument("--id", required=True); p.add_argument("--files", required=True); p.set_defaults(fn=cmd_report)
    x = sp.add_parser("fail"); x.add_argument("--id", required=True); x.add_argument("--message", required=True); x.set_defaults(fn=cmd_fail)
    a = ap.parse_args()
    a.fn(a)


if __name__ == "__main__":
    main()
