# PSPP Studio — Native GNU PSPP 2.1.2 for Android

**PSPP Studio** is a native Android statistical analysis workbench (Kotlin + Jetpack Compose, Material 3, `minSdk 24`, `targetSdk 35`) that embeds **GNU PSPP 2.1.2** through JNI (`libpspp_jni.so`), providing a complete syntax editor, Data View & Variable View spreadsheet editors, an HTML/CSV/PDF output viewer, Storage Access Framework (SAF) file management, and 9 guided statistical analysis procedures.

---

## 1. Upstream Source & Cryptographic Verification

- **Upstream Release**: `https://ftp.wayne.edu/gnu/pspp/pspp-2.1.2.tar.gz`
- **Detached GPG Signature**: `https://ftp.wayne.edu/gnu/pspp/pspp-2.1.2.tar.gz.sig`
- **GNU Keyring**: `https://ftp.gnu.org/gnu/gnu-keyring.gpg`
- **Pinned Signing Key Fingerprint**: `C2D1 AB06 1656 AAC5 4B5E 9754 8519 9DE8 C664 8E90`

`build-native.sh` verifies `pspp-2.1.2.tar.gz.sig` against `gnu-keyring.gpg` using `gpg --status-fd 1 --verify` and aborts immediately if the signing fingerprint does not match `C2D1AB061656AAC54B5E975485199DE8C6648E90`.

---

## 2. Building Native Libraries (`build-native.sh`) and APKs

### Prerequisites (Linux or macOS Host)
- Android SDK (`platforms;android-35`, `build-tools;35.0.0`)
- Android NDK r27 (`27.0.12077973`), set via `ANDROID_NDK_HOME`
- Host tools: `autoconf`, `automake`, `libtool`, `pkg-config`, `gettext`, `texinfo`, `bison`, `flex`, `gperf`, `gpg`, `curl`, `xz-utils`
- JDK 17+

### Step 1: Cross-Build Native Dependencies & PSPP 2.1.2
```bash
export ANDROID_NDK_HOME="$ANDROID_SDK_ROOT/ndk/27.0.12077973"
./build-native.sh
```
This single command downloads, verifies, patches, and cross-compiles:
1. `zlib 1.3.1`
2. `GNU libiconv 1.17`
3. `GNU gettext 0.22.5` (`libintl` with `--enable-nls` so engine diagnostics and output strings stay localized)
4. `libxml2 2.12.7`
5. `GNU Scientific Library (GSL) 2.8` (`libgsl` + `libgslcblas`)
6. `GNU PSPP 2.1.2` (`libpspp`, `libpspp-core`, `libgl`, HTML/CSV/TXT output drivers)
7. `libpspp_jni.so` into `app/src/main/jniLibs/{arm64-v8a,armeabi-v7a,x86_64}/libpspp_jni.so`

### Step 2: Build Debug, Release, ABI Split & Universal APKs
```bash
gradle assembleDebug assembleRelease -PenableAbiSplits=true
```
To also invoke CMake (`app/src/main/cpp/CMakeLists.txt`) directly during the Gradle build after `build-native.sh` has populated `native-build/sysroot`:
```bash
gradle assembleDebug -PuseCmake=true
```

### Step 3: Run Unit & Robolectric Regression Tests
```bash
gradle :app:testDebugUnitTest
```

---

## 3. GitHub Actions CI (`.github/workflows/build.yml`)

Pushing this repository to GitHub automatically triggers `.github/workflows/build.yml`, which:
1. Installs host autotools/GPG packages and Android SDK + NDK r27 (`27.0.12077973`).
2. Runs `./build-native.sh` to build `app/src/main/jniLibs/<abi>/libpspp_jni.so`.
3. Builds Debug, Release, ABI-split, and Universal APKs (falling back to `debug.keystore` when release signing secrets are not set).
4. Runs `gradle :app:testDebugUnitTest`.
5. Boots an x86_64 Android emulator via `reactivecircus/android-emulator-runner@v2` and runs `gradle connectedDebugAndroidTest`.
6. Uploads all `.so` libraries, APKs, test reports, and logs as workflow artifacts.

---

## 4. Known Limitations & Architectural Decisions

1. **Cairo/Pango Headless Patch (`patches/01-disable-cairo-pango.patch`)**:
   - Upstream PSPP 2.1.2 has `--without-gui`, `--without-libpq`, and `--without-perl-module`, but `configure.ac` unconditionally checks for `cairo >= 1.5 pango >= 1.22 pangocairo >= 1.22`.
   - Patch 01 adds `--without-cairo` and guards `HAVE_CAIRO` in `src/output/driver.c` and `src/output/html.c` so the HTML, CSV, and ASCII/Text output drivers build without pulling in the X11/Freetype/Fontconfig/HarfBuzz/Pango/Cairo graphical stack.
   - PDF export in the Android UI is achieved natively via Android's `PrintManager` + `WebView.createPrintDocumentAdapter()` on the rendered PSPP HTML output.
2. **Process & Signal Safety (`pspp_jni.c` + `patches/04-library-reentrant-reset-and-exit-guard.patch`)**:
   - The JNI wrapper executes the CLI engine inside an isolated `fork()` child process (with `sigsetjmp`/`siglongjmp` fallback if `fork` is restricted) under a global `pthread_mutex_t` lock so `exit()` or fatal signals never terminate the hosting Android UI process.
3. **Pre-CI Fallback Evaluator (`PsppStatisticalEvaluator.kt`)**:
   - When running in an environment where `libpspp_jni.so` has not yet been compiled by NDK (such as the cloud preview container or local JVM Robolectric tests), `PsppBridge` transparently evaluates the PSPP syntax and binary `$FL2` `.sav` files in Kotlin while displaying the engine mode in the About screen.

---

## 5. How to Update to a Newer PSPP Release

1. Open `build-native.sh` and update `PSPP_VERSION="2.1.2"` to the new version string (e.g., `PSPP_VERSION="2.2.0"`).
2. Verify whether the release manager's signing key in `https://ftp.gnu.org/gnu/gnu-keyring.gpg` has changed; if still signed by Ben Pfaff, keep `PINNED_FPR="C2D1AB061656AAC54B5E975485199DE8C6648E90"`.
3. Run `./build-native.sh` and check if all 4 patch files in `/patches/*.patch` apply cleanly. If line offsets shifted in `configure.ac`, `src/output/driver.c`, or `src/ui/terminal/main.c`, refresh the patch hunks with `diff -u` and copy the updated `.patch` files to `app/src/main/assets/pspp/patches/`.
4. Re-run `gradle :app:testDebugUnitTest` to verify all sample `.sps` regression tests pass.
