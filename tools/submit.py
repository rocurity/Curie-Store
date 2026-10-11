#!/usr/bin/env python3
"""Check, publish or refresh apps in store/store.json using GitHub releases (needs the gh CLI).

  --mode check    download the repo's latest release APK and run every check, without changing the store
  --mode publish  same, then add the app to the store
  --mode update   look at every listed app and publish newer releases (same signing key only)

For check/publish the request comes from a store issue: --from-issue reads ISSUE_BODY and ISSUE_AUTHOR.
Writes a JSON result (--result) and a Markdown comment for the issue (--comment).
"""
import argparse, json, os, pathlib, re, subprocess, sys, tempfile

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import index_apk as ix

REPO_RE = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")


def gh(*args):
    r = subprocess.run(["gh", *args], capture_output=True, text=True)
    if r.returncode:
        lines = (r.stderr or r.stdout).strip().splitlines()
        raise ix.Rejected((lines[-1] if lines else "GitHub request failed")[:200])
    return r.stdout


def latest_tag(repo):
    try:
        tag = gh("release", "view", "-R", repo, "--json", "tagName", "-q", ".tagName").strip()
    except ix.Rejected:
        raise ix.Rejected(f"No published release found on {repo}. Publish a release with your signed APK first")
    if not tag:
        raise ix.Rejected(f"No published release found on {repo}")
    return tag


def fetch_release(repo, workdir):
    tag = latest_tag(repo)
    d = pathlib.Path(workdir) / re.sub(r"[^\w.-]", "_", repo)
    d.mkdir(parents=True, exist_ok=True)
    gh("release", "download", tag, "-R", repo, "--pattern", "*.apk", "--dir", str(d))
    apks = sorted(d.glob("*.apk"))
    if not apks:
        raise ix.Rejected("The latest release has no APK attached")
    if len(apks) > 1:
        raise ix.Rejected("The release must contain exactly one APK")
    return tag, apks[0]


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
    """A developer who already has an app in the store skips the manual review."""
    owner = repo.split("/")[0].lower()
    return any(a["repo"].split("/")[0].lower() == owner for a in store["apps"])


def mb(n):
    return f"{n / 1048576:.1f} MB"


def review_comment(s, trusted):
    perms = ", ".join(p.rsplit(".", 1)[-1] for p in s["permissions"]) or "none"
    lines = ["**Checks passed**", "",
             "| | |", "|---|---|",
             f"| App | {s['name']} (`{s['id']}`) |",
             f"| Version | {s['version']} (code {s['versionCode']})" + (f", update from {s['previousVersion']}" if s["isUpdate"] else ", new app") + " |",
             f"| Size | {mb(s['sizeBytes'])} |",
             f"| Category | {s['category']} |",
             f"| Signing key | `{s['certSha256']}` |",
             f"| Permissions | {perms} |",
             f"| Source | https://github.com/{s['repo']}/releases/tag/{s['tag']} |", ""]
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
        tag, apk = fetch_release(repo, tmp)
        s = ix.index_apk(apk, repo, tag, store_path, author=repo.split("/")[0], category=cat,
                         description=desc, dry_run=(a.mode == "check"))
    published = a.mode == "publish"
    comment = f"Added: {s['name']} {s['version']}" if published else review_comment(s, trusted)
    if not published and trusted:
        comment = comment.replace("**Checks passed**", "**Checks passed.** This developer already has an app in the store, so it is published automatically.")
    return {"ok": True, "mode": a.mode, "trusted": trusted, "published": published, "summary": s}, comment


def do_update(a):
    store_path = pathlib.Path(a.store)
    store = json.loads(store_path.read_text())
    res = {"ok": True, "mode": "update", "updated": [], "failed": [], "skipped": 0}
    with tempfile.TemporaryDirectory() as tmp:
        for app in list(store["apps"]):
            repo = app["repo"]
            try:
                tag = latest_tag(repo)
                cur = re.search(r"/download/([^/]+)/", app["latest"]["apkUrl"])
                if cur and cur.group(1) == tag:
                    res["skipped"] += 1
                    continue
                tag, apk = fetch_release(repo, tmp)
                s = ix.index_apk(apk, repo, tag, store_path, author=app.get("author"), category=app["category"])
                res["updated"].append({"repo": repo, "tag": tag, "version": s["version"]})
            except ix.Rejected as e:
                res["failed"].append({"repo": repo, "tag": locals().get("tag", "?"), "error": str(e)})
            except Exception as e:  # network trouble must not stop the other apps
                res["failed"].append({"repo": repo, "tag": "?", "error": f"Unexpected error: {e}"[:300]})
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
        result, comment = {"ok": False, "mode": a.mode, "error": f"Unexpected error: {e}"}, fail_comment("Unexpected error. The store owner has been notified in the workflow log.")
    write(a.result, json.dumps(result, indent=2))
    write(a.comment, comment)
    print(json.dumps(result, indent=2))
    sys.exit(0 if result["ok"] else 1)


if __name__ == "__main__":
    main()
