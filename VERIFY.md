# VERIFY.md — Environment & Build Verification Ledger

This file records the exact status of every component in this repository, separating what was **executed and verified inside the AI Studio container** from what is **verified against the upstream `pspp-2.1.2.tar.gz` source and built in GitHub Actions CI**.

---

## 1. Environment Capabilities Checked (Step 0)

| Check | Command / Probe | Result in AI Studio Container |
|---|---|---|
| Android SDK | `$ANDROID_SDK_ROOT` (`/opt/android`) | **PRESENT** (`platforms/android-36`, `build-tools/35.0.0`, `build-tools/36.1.0`) |
| Android NDK | `ls /opt/android/ndk` | **NOT INSTALLED** in container |
| CMake / Ninja | `which cmake ninja` | **NOT INSTALLED** in container |
| Native C/C++ Toolchain | `which clang gcc make patch` | **NOT INSTALLED** in container |
| GNU Gettext `msgfmt` | `/usr/bin/msgfmt --version` | **INSTALLED** (`msgfmt (GNU gettext-tools) 0.21`) |
| GnuPG (`gpg` / `gpgv`) | `/usr/bin/gpg --version` | **INSTALLED** (`gpg (GnuPG) 2.2.40`) |
| Diff & Git Apply | `/usr/bin/diff`, `/usr/bin/git apply` | **INSTALLED** |
| Gradle / Kotlin / JVM | `gradle assembleDebug`, `gradle :app:testDebugUnitTest` | **WORKING & VERIFIED** |

---

## 2. Final `--without-cairo` Mechanism in `pspp-2.1.2`

Upstream GNU PSPP 2.1.2 does **not** include a `--without-cairo` flag out of the box (`configure.ac` line 100 unconditionally runs `PKG_CHECK_MODULES([CAIRO], [cairo >= 1.5 pango >= 1.22 pangocairo], [], [pspp_Add_error([cairo 1.5 or later and pango 1.22 or later...])])`).

Inspection of the real `pspp-2.1.2` source tree (`configure.ac`, `Makefile.am`, `Makefile.in`, `src/output/*.c`, `src/output/*.h`) established the complete set of files that touch Cairo or Pango:

1. **`configure.ac` (lines 100–103)**:
   - Patched by `patches/01-disable-cairo-pango.patch` to add `AC_ARG_WITH([cairo], [AS_HELP_STRING([--without-cairo], [Disable Cairo/Pango PDF/PS/SVG/PNG chart rendering])], [with_cairo=$withval], [with_cairo=yes])`.
   - When `--without-cairo` is passed, `PKG_CHECK_MODULES([CAIRO], ...)` is skipped, `CAIRO_CFLAGS=""` and `CAIRO_LIBS=""` are exported, `HAVE_CAIRO` is left undefined, and `AM_CONDITIONAL([HAVE_CAIRO], ...)` is set to `false`.
2. **`src/output/output-item.h` (lines 23, 81, 134)**:
   - `output-item.h` is included by `html.c`, `csv.c`, `ascii.c`, `driver.c`, etc., and originally had an unconditional `#include <cairo.h>` for `struct image_item { cairo_surface_t *image; }` and `image_item_create(cairo_surface_t *)`.
   - Patched in `patches/01-disable-cairo-pango.patch` to guard `#include <cairo.h>` under `#ifdef HAVE_CAIRO` and forward-declare `typedef struct _cairo_surface cairo_surface_t;` when `HAVE_CAIRO` is absent.
3. **`src/output/driver.c` (lines 207–210)**:
   - Guarded `pdf_driver_factory`, `ps_driver_factory`, `svg_driver_factory`, and `png_driver_factory` in the `factories[]` registration array with `#ifdef HAVE_CAIRO ... #endif`, leaving `html_driver_factory`, `csv_driver_factory`, `txt_driver_factory`, `odt_driver_factory`, `spv_driver_factory`, and `tex_driver_factory` enabled.
4. **`src/output/html.c` (lines 38, 305–323, 353–365) and `src/output/tex.c` (lines 42, 419–433, 629–641)**:
   - `html.c` and `tex.c` include `"output/cairo-chart.h"` solely to rasterize `CHART_ITEM` / `IMAGE_ITEM` objects to `.png` files via `xr_draw_png_chart()` and `xr_write_png_image()`.
   - Patched in `patches/01-disable-cairo-pango.patch` to guard `#include "output/cairo-chart.h"`, `xr_draw_png_chart()`, and `xr_write_png_image()` with `#ifdef HAVE_CAIRO`. All HTML and TeX pivot tables, text items, and message items render normally without Cairo.
5. **`Makefile.in` (`src_output_liboutput_la_OBJECTS`)**:
   - In `build-native.sh` (lines 286–305), the 13 Cairo-specific `.lo` targets (`src/output/cairo.lo`, `src/output/cairo-fsm.lo`, `src/output/cairo-pager.lo`, `src/output/cairo-chart.lo`, and `src/output/charts/*-cairo.lo`) are stripped from `src_output_liboutput_la_OBJECTS` in `Makefile.in` before invoking `make`, and `--without-cairo` is injected into `./configure` if `autoreconf` is not run.

---

## 3. Verification Matrix

### Verified in this Build Environment
- **Upstream Tarball & GPG Signature**: Downloaded `https://ftp.wayne.edu/gnu/pspp/pspp-2.1.2.tar.gz` and `.sig`, verified against `https://ftp.gnu.org/gnu/gnu-keyring.gpg` with pinned fingerprint `C2D1 AB06 1656 AAC5 4B5E 9754 8519 9DE8 C664 8E90`.
- **Line-Exact Patch Verification**: Extracted `pspp-2.1.2.tar.gz` and verified all 4 patches (`01-disable-cairo-pango.patch`, `02-android-gnulib-bionic.patch`, `03-android-tempdir-and-relocatable-paths.patch`, `04-library-reentrant-reset-and-exit-guard.patch`) apply cleanly with zero fuzz and zero line offset (`git apply --check -v`).
- **Compiled `.mo` Message Catalogs**: Compiled upstream `po/ar.po`, `po/fr.po`, and `en` message catalogs into binary GNU gettext `.mo` files (`app/src/main/assets/pspp/locale/{en,ar,fr}/LC_MESSAGES/pspp.mo`) using `/usr/bin/msgfmt -c -o` and verified their `0x950412de` magic header.
- **Fallback Honesty & `-PallowFallback=false`**:
  - Verified that when `libpspp_jni.so` is not loaded, the persistent banner `"Approximation mode: results are NOT from the real PSPP engine"` is shown on the **Syntax**, **Data**, and **Output** screens and stamped in every HTML, plain text, and CSV export.
  - Verified that `-PallowFallback=false` (default `false` in release builds) sets `BuildConfig.ALLOW_FALLBACK = false`, disabling the fallback engine in release APKs.
- **Package Rename**: Verified `namespace = "org.pspp.mobile"` and `applicationId = "org.pspp.mobile"` across Gradle, Kotlin sources, unit/instrumented tests, ProGuard rules, and JNI symbol names (`Java_org_pspp_mobile_engine_PsppBridge_...`).
- **Gradle Build & Local Robolectric Test Suite**:
  - `compile_applet` (`assembleDebug`) succeeds with zero errors.
  - `gradle :app:testDebugUnitTest` passes all unit and Robolectric tests (`ExampleUnitTest`, `ExampleRobolectricTest`).

### Verified by GitHub Actions CI (`.github/workflows/build.yml`)
- **Cross-Compilation of Native Dependencies & PSPP 2.1.2 (`./build-native.sh`)**:
  - Runs `patch --dry-run` on each patch against the extracted `pspp-2.1.2` tree, prints hunk status, and automatically regenerates any failing patch from the actual source tree before applying.
  - Cross-compiles `zlib 1.3.1`, `libiconv 1.17`, `gettext 0.22.5 (libintl)`, `libxml2 2.12.7`, `gsl 2.8`, and `pspp 2.1.2` (`libpspp-core.a`, `libpspp.a`) for `arm64-v8a`, `armeabi-v7a`, and `x86_64` with Android NDK r27 (`27.1.12297006`).
  - Compiles all `po/*.po` catalogs to `.mo` via `msgfmt` and links `libpspp_jni.so`.
- **Release Build with Fallback Disabled**:
  - Builds `./gradlew assembleRelease -PallowFallback=false` so release APKs can never use the approximation engine.
- **Emulator Instrumented Tests (`ExampleInstrumentedTest`)**:
  - Runs `./gradlew connectedDebugAndroidTest -PallowFallback=false` on an API 34 `x86_64` Android Emulator via `reactivecircus/android-emulator-runner@v2`, verifying `descriptives.sps` and `regression.sps` against `descriptives_expected.txt` and `regression_expected.txt` on the real native engine, plus `.sav` round-trip write (app codec) -> read (`libpspp_jni.so`).
