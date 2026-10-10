# Curie Store

- `store/` the store website (index.html) and its app list (store.json, icons/)
- `dev/` the developer portal
- `android/` the Android app
- `tools/index_apk.py` adds an app to store/store.json from a signed release APK

Add an app by hand:

    python3 tools/index_apk.py app-release.apk --repo owner/name --tag v1.0.0 --category tools --description "..."
    git add store && git commit -m "Add app" && git push

The script rejects debug-signed APKs, a changed signing key, and a version that does not increase.
