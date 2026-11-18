# 口播提词 PromptCue

Android teleprompter for talking-head videos (YouTube / Shorts). Offline, no API key, data stays on the phone.

- 🎙 Voice notes (recorded audio) + free speech-to-text (phone's own recognizer)
- 📝 Note / script library: 口播稿 · 素材 · 灵感 · 工具, tags, search, favourites
- 🎬 In-app camera + scrolling prompt, record video → saved to `Movies/PromptCue`
- 🪟 Floating prompt window over any camera app (Android app only)

APK is built by GitHub Actions on every push to `main` → download from **Releases**.
Web version (GitHub Pages): `https://moonsiao.github.io/<repo>/`

## Layout
- `web/index.html` — single source of the UI. CI copies it into the app; run `python3 tools/sync_web.py` to refresh `docs/`.
- `app/` — Android shell: `MainActivity.java` (WebView + camera/mic/speech/video-save bridge), `FloatService.java` (floating prompter).
- `docs/` — GitHub Pages PWA.
- Release signing (optional): secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
