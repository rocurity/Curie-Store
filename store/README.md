# Curie Store

## Run the site locally
    cd site && python3 -m http.server 8000
    # open http://localhost:8000

## Publish on GitHub Pages
Repo Settings > Pages > Deploy from branch > main, folder `/site` (or move `site/` contents to `/docs`).

## Add an app
1. Developer signs a release APK with their permanent key and publishes it as a GitHub Release.
2. Run:
       python3 tools/index_apk.py app-release.apk --repo owner/name --tag v1.0.0 --category tools --description "..."
3. Commit `site/store.json` and push.

The script rejects debug-signed APKs, rejects a changed signing key for an existing app, and requires versionCode to increase.
