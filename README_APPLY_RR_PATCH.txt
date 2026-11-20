ANIYOMI RR PATCH — XHAMSTER ARABIC + VIDEO DURATION BUILD FIX

Contents
- Adds the XHamster Arabic Aniyomi source module with the six supplied routes:
  /newest, /categories, /channels, /pornstars,
  /creators/videos/naoc, /creators/contest/naoc.
- Adds the XHamster public MP4/HLS metadata extractor, including nested HLS codec entries and the site's hex-format URL decoder, plus a static verifier.
- Updates the Arabic extension workflow and index generator from seven to eight APKs.
- Wires the Compose getValue import repair into the Aniyomi app build workflow after the video-duration badge is generated.
- Keeps the existing main Aniyomi app build pinned to the repository's current upstream commit.

Apply from the root of your cloned rr repository in Termux:
  unzip -o /storage/emulated/0/Download/rr_aniyomi_xhamster_patch_v2.zip -d .
  python3 extension-pack/tools/xhamster/verify_xhamster.py .
  git diff --check
  git status --short
  git add README_APPLY_RR_PATCH.txt .github/workflows/aniyomi-quality-build.yml .github/workflows/build-arabic-extensions.yml aniyomi-custom/fix_video_duration_badge.py extension-pack/src/ar/xhamster extension-pack/tools/xhamster/verify_xhamster.py extension-pack/scripts/publish_index.py
  git commit -m "Add XHamster Arabic source and fix duration badge build"
  git push origin main

After push, GitHub Actions should start two workflows:
1) Build Aniyomi Quality APK for rr
2) Build Arabic Aniyomi Extensions (experimental)

The main-app workflow applies customizations in this order:
  1. Generate the video-duration resolver and badge.
  2. Run fix_video_duration_badge.py to add the required Compose getValue import.
  3. Run formatting/checks and build the ARM64 APK.

Important limits
- The verifier is a static audit and Kotlin syntax was checked with local API stubs; neither proves a full Gradle build, live site access, or playback.
- CAPTCHA, login, age checks, DRM, and private-content restrictions are not bypassed.
- The video-duration badge obtains metadata from public page HTML; sites that hide duration or block requests may show no badge.
- Do not install the APK until the relevant GitHub Actions build succeeds. The extension APKs are experimental debug builds.
