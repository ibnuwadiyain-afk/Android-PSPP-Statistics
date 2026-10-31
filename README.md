# PSPP Studio for Android (`org.pspp.mobile` — GNU PSPP 2.1.2)

**PSPP Studio** (`org.pspp.mobile`) is a complete native Android statistical workbench (Kotlin + Jetpack Compose, Material 3, `minSdk = 24`, `targetSdk = 35`) designed around the **GNU PSPP 2.1.2** engine via JNI (`libpspp_jni.so`), with full **GPLv3** compliance, compiled `.mo` gettext catalogs, binary `.sav` & delimited `.csv` I/O, 9 interactive statistical analysis procedures, and localization in **English**, **Arabic (full RTL & Eastern Arabic numerals)**, and **French**.

---

## 1. Honest Build & Fallback Architecture

1. **Fallback Honesty Banner & Export Stamping**:
   - Whenever `libpspp_jni.so` is not loaded (`!PsppBridge.isNativeLoaded`), the app displays a persistent warning banner on the **Syntax**, **Data**, and **Output** screens:
     > `Approximation mode: results are NOT from the real PSPP engine`
   - Every HTML, Plain Text, and CSV output or file export generated while `libpspp_jni.so` is absent is stamped with that exact line.
2. **Release Safeguard (`-PallowFallback=false`)**:
   - `app/build.gradle.kts` defines the Gradle property `-PallowFallback` (`BuildConfig.ALLOW_FALLBACK`).
   - It defaults to **`false` for `release` builds** (and `true` for `debug` builds unless `-PallowFallback=false` is passed), guaranteeing that a release APK can **never** silently fall back to non-PSPP results.
3. **One-Command Reproducible NDK Build (`./build-native.sh`) & CI (`.github/workflows/build.yml`)**:
   - Downloads `https://ftp.wayne.edu/gnu/pspp/pspp-2.1.2.tar.gz` and `.tar.gz.sig`, verifies the GPG signature against `https://ftp.gnu.org/gnu/gnu-keyring.gpg` pinning fingerprint `C2D1 AB06 1656 AAC5 4B5E 9754 8519 9DE8 C664 8E90`, and fails immediately on mismatch.
   - Runs `patch --dry-run` on each patch in `/patches/` against the extracted `pspp-2.1.2` source first, prints which hunks succeed or fail, and automatically regenerates any failing patch from the actual source before applying.
   - Compiles upstream `.po` files into binary `.mo` catalogs (`app/src/main/assets/pspp/locale/<lang>/LC_MESSAGES/pspp.mo`) using `msgfmt`.
   - Cross-builds `zlib 1.3.1`, `libiconv 1.17`, `gettext 0.22.5 (libintl)`, `libxml2 2.12.7`, `gsl 2.8`, and `pspp 2.1.2` (`libpspp-core`, `libpspp`) for `arm64-v8a`, `armeabi-v7a`, and `x86_64` with **Android NDK r27 (`27.1.12297006`)**, outputting `app/src/main/jniLibs/<abi>/libpspp_jni.so`.

---

## 2. How to Build Locally or via GitHub Actions

### Option A: GitHub Actions (Zero Local Setup)
1. Download the project ZIP from AI Studio and push it to a GitHub repository.
2. GitHub Actions automatically runs `.github/workflows/build.yml`:
   - Installs Android SDK 35, NDK r27 (`27.1.12297006`), CMake 3.22.1, `gettext` (`msgfmt`), `gnupg`, `texinfo`, `perl`, and `python3`.
   - Executes `./build-native.sh` for `arm64-v8a`, `armeabi-v7a`, and `x86_64`.
   - Builds universal and per-ABI split Debug & Release APKs (`./gradlew assembleDebug assembleRelease -PallowFallback=false`).
   - Boots an Android API 34 `x86_64` emulator via `reactivecircus/android-emulator-runner@v2` and runs `./gradlew connectedDebugAndroidTest -PallowFallback=false` (`ExampleInstrumentedTest`).
   - Uploads `pspp-native-jniLibs`, `pspp-android-apks`, and `pspp-build-and-test-logs` as workflow artifacts.

### Option B: Local Linux / macOS Build
```bash
export ANDROID_NDK_HOME=/path/to/android-sdk/ndk/27.1.12297006
chmod +x build-native.sh
./build-native.sh
./gradlew assembleDebug assembleRelease -PallowFallback=false
```

---

## 3. Patches in `/patches/`

Each patch in `/patches/` (also mirrored in `app/src/main/assets/pspp/patches/` for in-app inspection on the About screen) begins with a one-line `Reason:` header and was generated directly against `pspp-2.1.2.tar.gz`:

1. `01-disable-cairo-pango.patch`: Add `--without-cairo` configure option and guard Cairo/Pango output drivers (`driver.c`, `output-item.h`, `html.c`, `tex.c`) while keeping HTML, CSV, and ASCII drivers enabled.
2. `02-android-gnulib-bionic.patch`: Fix gnulib `freadahead.c` and `fseeko.c` stdio internals for Android Bionic `FILE` structs on API 24+.
3. `03-android-tempdir-and-relocatable-paths.patch`: Respect Android `PSPP_TMPDIR`/`TMPDIR` in `temp-file.c` and `PSPP_LOCALEDIR` in `i18n.c` for extracted `.mo` catalogs.
4. `04-library-reentrant-reset-and-exit-guard.patch`: Prevent `exit()`/`abort()` from killing the Android JVM process (`longjmp` to `pspp_jni_jmp_env`) and reset static driver/lexer state between JNI runs.

---

## 4. License

GNU PSPP and this Android application are licensed under the **GNU General Public License v3.0 or later (GPLv3+)**. See [`LICENSE`](LICENSE) and `app/src/main/assets/pspp/COPYING`.
