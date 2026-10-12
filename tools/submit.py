#!/usr/bin/env python3
"""Check, publish or refresh apps in store/store.json using GitHub (needs the gh CLI).

  --mode check    find the repo's APK and run every check, without changing the store
  --mode publish  same, then add the app to the store
  --mode update   look at every listed app and publish newer versions (same signing key only)

The APK is taken from the repo's latest release. If the repo has no release, an .apk file in the
repo's top folder is used instead. Easy mode is the default: any app can be published and debug
builds are allowed (they get a "Debug build" label). Set STRICT=true to require a review for new
developers and to refuse debug builds.

For check/publish the request comes from a store issue: --from-issue reads ISSUE_BODY and ISSUE_AUTHOR.
Writes a JSON result (--result) and a Markdown comment for the issue (--comment).
"""
import argparse, json, os, pathlib, re, subprocess, sys, tempfile

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import index_apk as ix

REPO_RE = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
STRICT = os.environ.get("STRICT", "false").strip().lower() == "true"


def gh(*args, binary=False):
    r = subprocess.run(["gh", *args], capture_output=True, text=not binary)
    if r.returncode:
        err = r.stderr if not binary else r.stderr.decode("utf-8", "replace")
        out = r.stdout if not binary else b""
        lines = (err or (out if not binary else "")).strip().splitlines()
        raise ix.Rejected((lines[-1] if lines else "GitHub request failed")[:200])
    return r.stdout


def pick_apk(names):
    """Several APKs? Prefer a universal one, then 64-bit ARM, then the first by name."""
    names = sorted(names)
    for key in ("universal", "arm64"):
        for n in names:
            if key in n.lower():
                return n
    return names[0]


def find_source(repo):
    """Where is the APK? ('release', tag) or ('file', path, blob_sha)."""
    try:
        tag = gh("release", "view", "-R", repo, "--json", "tagName", "-q", ".tagName").strip()
    except ix.Rejected:
        tag = ""
    if tag:
        return ("release", tag)
    try:
        files = json.loads(gh("api", f"repos/{repo}/contents", "--jq",
                              '[.[] | select(.type=="file" and (.name|endswith(".apk"))) | {name, sha}]') or "[]")
    except (ix.Rejected, ValueError):
        files = []
    if not files:
        raise ix.Rejected(f"Nothing to publish on {repo}. Put your APK in a GitHub release, or in the repo's top folder")
    name = pick_apk([f["name"] for f in files])
    return ("file", name, next(f["sha"] for f in files if f["name"] == name))


def source_id(src):
    return f"release:{src[1]}" if src[0] == "release" else f"file:{src[2]}"


def fetch_apk(repo, src, workdir):
    """Download the APK. Returns (tag, path, apk_url, source_id)."""
    d = pathlib.Path(workdir) / re.sub(r"[^\w.-]", "_", repo)
    d.mkdir(parents=True, exist_ok=True)
    if src[0] == "release":
        tag = src[1]
        gh("release", "download", tag, "-R", repo, "--pattern", "*.apk", "--dir", str(d))
        apks = sorted(d.glob("*.apk"))
        if not apks:
            raise ix.Rejected("The latest release has no APK attached")
        chosen = next(p for p in apks if p.name == pick_apk([p.name for p in apks]))
        return tag, chosen, None, source_id(src)
    _, name, blob = src
    commit = gh("api", f"repos/{repo}/commits/HEAD", "--jq", ".sha").strip()
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise ix.Rejected("Could not read the repo's latest commit")
    data = gh("api", f"repos/{repo}/contents/{name}", "-H", "Accept: application/vnd.github.raw", binary=True)
    path = d / name
    path.write_bytes(data)
    return f"file-{blob[:7]}", path, f"https://github.com/{repo}/raw/{commit}/{name}", source_id(src)


def parse_issue():
    body = os.environ.get("ISSUE_BODY", "")
    def field(k):
        m = re.search(rf"^{k}:[ \t]*(.*)$", body, re.M)
        return m.group(1).strip() if m else ""
    repo = field("repo")
    if not REPO_RE.match(repo):
        raise ix.Rejected("The request does not contain a valid repo name (owner/app)")
    author = os.environ.get("ISSUE_AUTHOR", "")
    if repo.split("/")[0].lower() != author.lower():
        raise ix.Rejected(f"The request must come from the owner of the repo ({repo.split('/')[0]}), not {author or 'an unknown user'}")
    cat = field("category").lower()
    return repo, (cat if cat in ix.CATEGORIES else "tools"), re.sub(r"\s+", " ", field("description"))[:160]


def is_trusted(store, repo):
    """Easy mode: everyone. Strict mode: only developers who already have an app in the store."""
    if not STRICT:
        return True
    owner = repo.split("/")[0].lower()
    return any(a["repo"].split("/")[0].lower() == owner for a in store["apps"])


def mb(n):
    return f"{n / 1048576:.1f} MB"


def review_comment(s, trusted):
    perms = ", ".join(p.rsplit(".", 1)[-1] for p in s["permissions"]) or "none"
    where = f"https://github.com/{s['repo']}/releases/tag/{s['tag']}" if s["source"].startswith("release:") else f"a file in https://github.com/{s['repo']}"
    lines = ["**Checks passed**", "",
             "| | |", "|---|---|",
             f"| App | {s['name']} (`{s['id']}`) |",
             f"| Version | {s['version']} (code {s['versionCode']})" + (f", update from {s['previousVersion']}" if s["isUpdate"] else ", new app") + " |",
             f"| Size | {mb(s['sizeBytes'])} |",
             f"| Category | {s['category']} |",
             f"| Signing key | `{s['certSha256']}` |",
             f"| Permissions | {perms} |",
             f"| Source | {where} |", ""]
    if s["debug"]:
        lines += ["This is a **debug build**. It is listed with a Debug label.", ""]
    if s["sensitive"]:
        lines += [f"Sensitive permissions to look at: **{', '.join(s['sensitive'])}**", ""]
    lines += ["This is a new developer. Add the `approved` label to publish it."] if not trusted else []
    return "\n".join(lines)


def fail_comment(msg):
    return "Not added.\n```\n" + msg.strip() + "\n```"


def write(path, text):
    if path:
        pathlib.Path(path).write_text(text)


def do_submission(a):
    store_path = pathlib.Path(a.store)
    repo, cat, desc = parse_issue() if a.from_issue else (a.repo, a.category, a.description)
    if not REPO_RE.match(repo):
        raise ix.Rejected("Invalid repo name")
    store = json.loads(store_path.read_text())
    trusted = is_trusted(store, repo)
    with tempfile.TemporaryDirectory() as tmp:
        tag, apk, url, sid = fetch_apk(repo, find_source(repo), tmp)
        s = ix.index_apk(apk, repo, tag, store_path, author=repo.split("/")[0], category=cat, description=desc,
                         dry_run=(a.mode == "check"), allow_debug=not STRICT, apk_url=url, source=sid)
    published = a.mode == "publish"
    comment = f"Added: {s['name']} {s['version']}" + (" (debug build)" if s["debug"] else "") if published else review_comment(s, trusted)
    if not published and trusted:
        comment = comment.replace("**Checks passed**", "**Checks passed.** It is published automatically.")
    return {"ok": True, "mode": a.mode, "trusted": trusted, "published": published, "summary": s}, comment


def current_source(app):
    if app.get("source"):
        return app["source"]
    m = re.search(r"/download/([^/]+)/", app["latest"]["apkUrl"])
    return f"release:{m.group(1)}" if m else ""


def do_update(a):
    store_path = pathlib.Path(a.store)
    store = json.loads(store_path.read_text())
    res = {"ok": True, "mode": "update", "updated": [], "failed": [], "skipped": 0}
    with tempfile.TemporaryDirectory() as tmp:
        for app in list(store["apps"]):
            repo, tag = app["repo"], "?"
            try:
                src = find_source(repo)
                if source_id(src) == current_source(app):
                    res["skipped"] += 1
                    continue
                tag, apk, url, sid = fetch_apk(repo, src, tmp)
                s = ix.index_apk(apk, repo, tag, store_path, author=app.get("author"), category=app["category"],
                                 allow_debug=not STRICT, apk_url=url, source=sid)
                res["updated"].append({"repo": repo, "tag": tag, "version": s["version"]})
            except ix.Rejected as e:
                res["failed"].append({"repo": repo, "tag": tag, "error": str(e)})
            except Exception as e:  # network trouble must not stop the other apps
                res["failed"].append({"repo": repo, "tag": tag, "error": f"Unexpected error: {e}"[:300]})
    return res, ""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--mode", required=True, choices=["check", "publish", "update"])
    ap.add_argument("--from-issue", action="store_true")
    ap.add_argument("--repo"); ap.add_argument("--category", default="tools"); ap.add_argument("--description", default="")
    ap.add_argument("--store", default="store/store.json")
    ap.add_argument("--result"); ap.add_argument("--comment")
    a = ap.parse_args()
    try:
        result, comment = do_update(a) if a.mode == "update" else do_submission(a)
    except ix.Rejected as e:
        result, comment = {"ok": False, "mode": a.mode, "error": str(e)}, fail_comment(str(e))
    except Exception as e:
        result, comment = {"ok": False, "mode": a.mode, "error": f"Unexpected error: {e}"}, fail_comment("Unexpected error. The store owner can see the details in the workflow log.")
    write(a.result, json.dumps(result, indent=2))
    write(a.comment, comment)
    print(json.dumps(result, indent=2))
    sys.exit(0 if result["ok"] else 1)


if __name__ == "__main__":
    main()
