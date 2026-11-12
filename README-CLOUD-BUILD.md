# Build the Android preview APK from GitHub (no computer required after upload)

This repository includes a GitHub Actions workflow at `.github/workflows/android-apk.yml`.
It builds a **debug APK** automatically when code is pushed to `main`/`master`, or when
manually started from the Actions tab.

## One-time setup

1. Create a new empty repository on GitHub. Do not add a README or license during creation.
2. On a computer, extract this ZIP and upload the *contents* of the `HOK-BetaStudio-Android-port` folder to that repository. (GitHub's web uploader can upload files, but large folders are easier with Git.)
3. Open the repository's **Actions** tab and enable workflows if GitHub asks.
4. Select **Build Android APK** and tap **Run workflow**.
5. Wait for the run to finish successfully. Open the completed run and download the artifact named `HOK-BetaStudio-Android-debug-apk`.
6. Extract the downloaded artifact; it contains `app-debug.apk`. Transfer it to the Android tablet and install it.

## Important limitations

This APK is only the first-stage Android interface/catalog preview. The original Windows-only C# database engine and its import, rebuild, conversion, and export operations are not ported yet. A successful APK build does not mean full desktop feature parity.
