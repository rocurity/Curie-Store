#!/usr/bin/env python3
"""Checker for apps uploaded through the Curie Store developer dashboard.

Three separate steps, so the step that opens the untrusted APK never sees a secret:
  fetch    (has the secret)  get the submission from the store backend, mark it "checking"
  analyze  (NO secret)       download the APK from the public bucket, read it with aapt2 / apksigner
  report   (has the secret)  send the result back (or the reason it was refused)
  fail     (has the secret)  mark a submission failed when the job itself broke

Environment:  STORE_API_URL, INTERNAL_SECRET   (fetch, report, fail)
              B2_ENDPOINT, STRICT              (analyze)
"""
import argparse, base64, hashlib, json, os, pathlib, re, sys, tempfile, urllib.error, urllib.parse, urllib.request

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import index_apk as ix

MAX_APK = 150 * 1024 * 1024
MAX_ICON = 100 * 1024
ID_RE = re.compile(r"^[A-Za-z0-9-]{8,64}$")


def api(method, path, body=None):
    base = os.environ["STORE_API_URL"].rstrip("/")
    req = urllib.request.Request(base + path, method=method, data=json.dumps(body).encode() if body is not None else None,
                                 headers={"X-Internal-Secret": os.environ["INTERNAL_SECRET"], "Content-Type": "application/json", "User-Agent": "curie-store-checker"})
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


def check_storage_url(url):
    """Only ever download from the store's own Backblaze endpoint."""
    host = os.environ.get("B2_ENDPOINT", "").strip().lower()
    u = urllib.parse.urlparse(url)
    if not host or (u.scheme != "https" and os.environ.get("CURIE_TEST") != "1") or (u.hostname or "").lower() != host:
        raise ix.Rejected("The app file is not in the store's storage")


def cmd_fetch(a):
    sid = check_id(a.id)
    code, sub = api("GET", f"/internal/submissions/{sid}")
    if code != 200:
        sys.exit(f"Could not read the submission ({code})")
    if sub.get("status") not in ("queued", "checking"):
        print("Nothing to do: the submission is", sub.get("status"))
        pathlib.Path(a.out).write_text(json.dumps({"skip": True}))
        return
    api("POST", f"/internal/submissions/{sid}/status", {"status": "checking", "message": "Checking the app"})
    pathlib.Path(a.out).write_text(json.dumps({
        "id": sid, "kind": sub["kind"], "targetApp": sub.get("targetApp", ""), "apk": sub["apk"], "admin": bool(sub.get("admin")),
    }))


def download(url, dest, expected):
    """Download exactly `expected` bytes from the public bucket. Returns the sha256."""
    h = hashlib.sha256()
    req = urllib.request.Request(url, headers={"User-Agent": "curie-store-checker"})
    got = 0
    with urllib.request.urlopen(req, timeout=120) as r, open(dest, "wb") as f:
        while True:
            chunk = r.read(1 << 20)
            if not chunk:
                break
            got += len(chunk)
            if got > expected or got > MAX_APK:
                raise ix.Rejected("The uploaded file is larger than it should be")
            h.update(chunk)
            f.write(chunk)
    if got != expected:
        raise ix.Rejected("The uploaded file is not the size it should be")
    return h.hexdigest()


def cmd_analyze(a):
    sub = json.loads(pathlib.Path(a.submission).read_text())
    if sub.get("skip"):
        pathlib.Path(a.out).write_text(json.dumps({"skip": True}))
        return
    strict = os.environ.get("STRICT", "false").strip().lower() == "true"
    try:
        url, size = sub["apk"]["downloadUrl"], int(sub["apk"]["size"])
        check_storage_url(url)
        with tempfile.TemporaryDirectory() as tmp:
            apk = pathlib.Path(tmp) / "app.apk"
            try:
                sha = download(url, apk, size)
            except (urllib.error.URLError, OSError):
                raise ix.Rejected("The uploaded file could not be downloaded. Upload it again")
            info = ix.inspect_apk(apk, allow_debug=not strict)
            if sub["kind"] == "version" and info["app_id"] != sub["targetApp"]:
                raise ix.Rejected(f"This file is a different app ({info['app_id']}) than the one you are updating ({sub['targetApp']})")
            icon = ix.read_icon(apk, info["icon_path"])
            out = {
                "ok": True, "appId": info["app_id"], "label": info["label"],
                "version": {"version": info["vname"], "versionCode": info["vcode"], "sha256": sha, "certSha256": info["cert"],
                            "sizeBytes": size, "permissions": info["perms"], "debug": info["debug"]},
            }
            if icon and len(icon) <= MAX_ICON:
                out["iconBase64"] = base64.b64encode(icon).decode()
    except ix.Rejected as e:
        msg = str(e).replace("REJECTED: ", "")
        # never show a developer the raw output of our tools
        if msg.startswith("aapt2 failed"):
            msg = "This file could not be read as an Android app. Is it a valid APK?"
        elif msg.startswith("apksigner failed"):
            msg = "The APK is not signed correctly. Android can only install signed apps."
        out = {"ok": False, "error": msg[:400]}
    except Exception as e:
        out = {"ok": False, "error": "The checker could not read this file. Is it a valid APK?", "detail": str(e)[:200]}
    pathlib.Path(a.out).write_text(json.dumps(out))
    print("analysis:", "ok" if out["ok"] else out["error"])


def cmd_report(a):
    sub = json.loads(pathlib.Path(a.submission).read_text())
    if sub.get("skip"):
        return
    sid = check_id(sub["id"])
    res = json.loads(pathlib.Path(a.analysis).read_text())
    if not res.get("ok"):
        api("POST", f"/internal/submissions/{sid}/status", {"status": "failed", "message": res.get("error", "The check failed")})
        print("reported failure:", res.get("error"))
        return
    payload = {k: res[k] for k in ("appId", "label", "version", "iconBase64") if k in res}
    code, body = api("POST", f"/internal/submissions/{sid}/result", payload)
    print("result sent:", code, body)
    if code >= 500:
        sys.exit("The store backend failed to save the result")


def cmd_fail(a):
    sid = check_id(a.id)
    api("POST", f"/internal/submissions/{sid}/status", {"status": "failed", "message": a.message[:400]})


def main():
    ap = argparse.ArgumentParser()
    sp = ap.add_subparsers(dest="cmd", required=True)
    f = sp.add_parser("fetch"); f.add_argument("--id", required=True); f.add_argument("--out", required=True); f.set_defaults(fn=cmd_fetch)
    n = sp.add_parser("analyze"); n.add_argument("--submission", required=True); n.add_argument("--out", required=True); n.set_defaults(fn=cmd_analyze)
    r = sp.add_parser("report"); r.add_argument("--submission", required=True); r.add_argument("--analysis", required=True); r.set_defaults(fn=cmd_report)
    x = sp.add_parser("fail"); x.add_argument("--id", required=True); x.add_argument("--message", required=True); x.set_defaults(fn=cmd_fail)
    a = ap.parse_args()
    a.fn(a)


if __name__ == "__main__":
    main()
