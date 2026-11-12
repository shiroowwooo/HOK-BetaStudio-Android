# HOK BetaStudio — Android port starter

This is an Android Studio project scaffold based on the HOK-BetaStudio 1.4 source ZIP.
It embeds the existing React/Three.js interface in Android WebView and includes the
catalog seed data. It is a **first-stage Android shell**, not a complete port of the
Windows application.

## Current status and limitations

- Android launcher app + local WebView shell: scaffolded.
- Existing React interface: retained as source; build it with the script below.
- Hero/skin catalog: intended to work from the bundled catalog seed.
- DB parsing, folder/file import, asset listing, audio decoding, model conversion,
  export, replacement and DB rebuilding: **not ported yet**. These features call the
  Windows C#/.NET worker and WPF host. The desktop host targets `net10.0-windows`,
  uses WPF and Microsoft WebView2, and cannot be packaged as an Android app unchanged.
- This project has not been compiled into an APK in this environment. Do not treat
  this ZIP as an installable APK.

## Build prerequisites

- Android Studio with Android SDK Platform 35 and a compatible JDK (Android Studio's
  bundled JDK is recommended).
- Node.js 20+ and npm.
- Gradle 8.9+ (Android Studio can provision the matching Gradle distribution).

## Build steps

1. Open this folder in Android Studio.
2. Run `bash scripts/build-web.sh` (on Windows, run the equivalent commands manually:
   `cd frontend`, `npm ci`, `npm run build`, then copy all contents of `frontend/dist`
   into `app/src/main/assets/`).
3. Ensure `app/src/main/assets/index.html` exists after the web build.
4. Sync Gradle, then choose **Build > Build Bundle(s) / APK(s) > Build APK(s)**.

The first-stage app is a catalog/interface preview only. The UI's desktop operations
will report that the desktop host is required until the native Android data layer is
implemented.

## Next engineering phase for a full Android port

1. Replace the WPF file/folder dialogs with Android Storage Access Framework pickers.
2. Port the C# DB scanning, parsing, preview and export worker to a cross-platform
   .NET Android service or a native Kotlin/JNI library.
3. Replace WebView2 host messaging with a restricted Android JavaScript bridge.
4. Port native helper dependencies and validate formats independently on-device.
5. Implement Android SAF document export, background work, cancellation and large-file
   memory limits; then run real DB fixtures on the tablet before claiming parity.

The original MIT license is included. Game assets and third-party/native dependencies
may have separate rights and redistribution conditions; this scaffold does not bundle
private game DBs or proprietary runtime binaries.
