"""Copy web/index.html (the single source) to the Android app and GitHub Pages.
Run: python3 tools/sync_web.py   (CI also runs it before building the APK)
"""
import pathlib
root = pathlib.Path(__file__).resolve().parent.parent
src = (root / "web/index.html").read_text(encoding="utf-8")
(root / "app/src/main/assets/index.html").write_text(src, encoding="utf-8")
title = src[src.index("<title>"):src.index("</title>") + 8]
web = src.replace(title, title + '\n<link rel="manifest" href="manifest.json">\n<link rel="icon" href="icon-192.png">\n<link rel="apple-touch-icon" href="icon-192.png">')
web = web.replace("</script>\n</body>", "</script>\n<script>\nif('serviceWorker' in navigator){ navigator.serviceWorker.register('sw.js').catch(function(){}); }\n</script>\n</body>")
(root / "docs/index.html").write_text(web, encoding="utf-8")
print("synced")
