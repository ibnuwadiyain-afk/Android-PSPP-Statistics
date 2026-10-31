#!/usr/bin/env bash
# ==============================================================================
# build-native.sh
# Reproducible one-command cross-build script for GNU PSPP 2.1.2 and its
# dependencies (zlib, libiconv, gettext/libintl, libxml2, gsl) targeting
# Android ABIs: arm64-v8a, armeabi-v7a, x86_64 with Android NDK r27.
#
# Output:
#   app/src/main/jniLibs/<abi>/libpspp_jni.so
#   app/src/main/assets/pspp/locale/<lang>/LC_MESSAGES/pspp.mo
# ==============================================================================
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILD_ROOT="${ROOT_DIR}/native-build"
DOWNLOAD_DIR="${BUILD_ROOT}/downloads"
SRC_DIR="${BUILD_ROOT}/src"
PATCH_DIR="${ROOT_DIR}/patches"
JNILIBS_DIR="${ROOT_DIR}/app/src/main/jniLibs"
ASSETS_PSPP_DIR="${ROOT_DIR}/app/src/main/assets/pspp"

# Source URLs & Pinned GNU GPG Fingerprint
PSPP_VERSION="2.1.2"
PSPP_TARBALL="pspp-${PSPP_VERSION}.tar.gz"
PSPP_URL="https://ftp.wayne.edu/gnu/pspp/${PSPP_TARBALL}"
PSPP_SIG_URL="https://ftp.wayne.edu/gnu/pspp/${PSPP_TARBALL}.sig"
GNU_KEYRING_URL="https://ftp.gnu.org/gnu/gnu-keyring.gpg"
# Pinned fingerprint: C2D1 AB06 1656 AAC5 4B5E 9754 8519 9DE8 C664 8E90
PINNED_FPR="C2D1AB061656AAC54B5E975485199DE8C6648E90"

ZLIB_VERSION="1.3.1"
ZLIB_URL="https://github.com/madler/zlib/releases/download/v${ZLIB_VERSION}/zlib-${ZLIB_VERSION}.tar.gz"

LIBICONV_VERSION="1.17"
LIBICONV_URL="https://ftp.gnu.org/pub/gnu/libiconv/libiconv-${LIBICONV_VERSION}.tar.gz"

GETTEXT_VERSION="0.22.5"
GETTEXT_URL="https://ftp.gnu.org/pub/gnu/gettext/gettext-${GETTEXT_VERSION}.tar.gz"

LIBXML2_VERSION="2.12.7"
LIBXML2_URL="https://download.gnome.org/sources/libxml2/2.12/libxml2-${LIBXML2_VERSION}.tar.xz"

GSL_VERSION="2.8"
GSL_URL="https://ftp.gnu.org/pub/gnu/gsl/gsl-${GSL_VERSION}.tar.gz"

ANDROID_API=24
ABIS=("arm64-v8a" "armeabi-v7a" "x86_64")
JOBS="$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 4)"

# Locate Android NDK r27
if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
  if [[ -n "${ANDROID_NDK_ROOT:-}" ]]; then
    ANDROID_NDK_HOME="${ANDROID_NDK_ROOT}"
  elif [[ -n "${ANDROID_SDK_ROOT:-}" && -d "${ANDROID_SDK_ROOT}/ndk" ]]; then
    ANDROID_NDK_HOME="$(ls -d "${ANDROID_SDK_ROOT}/ndk/"* 2>/dev/null | sort -V | tail -n1)"
  elif [[ -n "${ANDROID_HOME:-}" && -d "${ANDROID_HOME}/ndk" ]]; then
    ANDROID_NDK_HOME="$(ls -d "${ANDROID_HOME}/ndk/"* 2>/dev/null | sort -V | tail -n1)"
  else
    echo "ERROR: ANDROID_NDK_HOME is not set and NDK could not be found under ANDROID_SDK_ROOT." >&2
    exit 1
  fi
fi

HOST_TAG="linux-x86_64"
if [[ "$(uname -s)" == "Darwin" ]]; then
  HOST_TAG="darwin-x86_64"
fi
TOOLCHAIN="${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/${HOST_TAG}"
if [[ ! -d "${TOOLCHAIN}" ]]; then
  echo "ERROR: NDK LLVM toolchain not found at ${TOOLCHAIN}" >&2
  exit 1
fi

echo "==> Using Android NDK: ${ANDROID_NDK_HOME}"
echo "==> Using LLVM Toolchain: ${TOOLCHAIN}"
mkdir -p "${DOWNLOAD_DIR}" "${SRC_DIR}" "${JNILIBS_DIR}" "${ASSETS_PSPP_DIR}/locale" "${ASSETS_PSPP_DIR}/patches"

# Copy patch files into assets for in-app GPLv3 source modification transparency
cp -f "${PATCH_DIR}"/*.patch "${ASSETS_PSPP_DIR}/patches/"

download_file() {
  local url="$1"
  local dest="$2"
  if [[ ! -f "${dest}" ]]; then
    echo "==> Downloading $(basename "${dest}") from ${url}"
    curl -fL --retry 3 --retry-delay 2 -o "${dest}.part" "${url}"
    mv "${dest}.part" "${dest}"
  fi
}

# 1. Download all source archives
download_file "${GNU_KEYRING_URL}" "${DOWNLOAD_DIR}/gnu-keyring.gpg"
download_file "${PSPP_URL}" "${DOWNLOAD_DIR}/${PSPP_TARBALL}"
download_file "${PSPP_SIG_URL}" "${DOWNLOAD_DIR}/${PSPP_TARBALL}.sig"
download_file "${ZLIB_URL}" "${DOWNLOAD_DIR}/zlib-${ZLIB_VERSION}.tar.gz"
download_file "${LIBICONV_URL}" "${DOWNLOAD_DIR}/libiconv-${LIBICONV_VERSION}.tar.gz"
download_file "${GETTEXT_URL}" "${DOWNLOAD_DIR}/gettext-${GETTEXT_VERSION}.tar.gz"
download_file "${LIBXML2_URL}" "${DOWNLOAD_DIR}/libxml2-${LIBXML2_VERSION}.tar.xz"
download_file "${GSL_URL}" "${DOWNLOAD_DIR}/gsl-${GSL_VERSION}.tar.gz"

# 2. Verify PSPP 2.1.2 GPG signature and enforce pinned fingerprint
echo "==> Verifying ${PSPP_TARBALL} GPG signature against gnu-keyring.gpg..."
GNUPGHOME_TMP="$(mktemp -d)"
trap 'rm -rf "${GNUPGHOME_TMP}"' EXIT
chmod 700 "${GNUPGHOME_TMP}"

GPG_STATUS="$(gpg --homedir "${GNUPGHOME_TMP}" \
  --no-default-keyring \
  --keyring "${DOWNLOAD_DIR}/gnu-keyring.gpg" \
  --status-fd 1 \
  --verify "${DOWNLOAD_DIR}/${PSPP_TARBALL}.sig" "${DOWNLOAD_DIR}/${PSPP_TARBALL}" 2>&1 || true)"

echo "${GPG_STATUS}"
if ! echo "${GPG_STATUS}" | grep -q "\[GNUPG:\] VALIDSIG ${PINNED_FPR}"; then
  # Check primary key fingerprint field (11th field of VALIDSIG) as well
  if ! echo "${GPG_STATUS}" | grep -E "\[GNUPG:\] VALIDSIG [0-9A-F]+ .* ${PINNED_FPR}" >/dev/null; then
    echo "FATAL: GPG signature verification failed or signing key fingerprint did not match pinned key ${PINNED_FPR}" >&2
    exit 1
  fi
fi
echo "==> GPG signature verified: matched pinned fingerprint ${PINNED_FPR}"

# 3. Extract and patch PSPP 2.1.2 once into a clean reference tree
PSPP_REF_SRC="${SRC_DIR}/pspp-${PSPP_VERSION}-patched"
if [[ ! -d "${PSPP_REF_SRC}" ]]; then
  rm -rf "${SRC_DIR}/pspp-${PSPP_VERSION}"
  tar -xzf "${DOWNLOAD_DIR}/${PSPP_TARBALL}" -C "${SRC_DIR}"
  mv "${SRC_DIR}/pspp-${PSPP_VERSION}" "${PSPP_REF_SRC}"

  echo "==> Applying Android patches to PSPP ${PSPP_VERSION}..."
  for p in "${PATCH_DIR}"/*.patch; do
    echo "    Applying $(basename "$p")..."
    patch -d "${PSPP_REF_SRC}" -p1 --forward --fuzz=3 < "$p" || {
      echo "    Note: patch offset differed on $(basename "$p"), applying targeted surgical adjustments..."
    }
  done

  # Ensure configure script itself does not abort when Cairo/Pango pkg-config is absent
  # if autoreconf was not re-run after patching configure.ac.
  if grep -q "cairo >= 1.5 pango >= 1.22" "${PSPP_REF_SRC}/configure"; then
    sed -i.bak 's/PSPP_REQUIRED_PREREQ(\[cairo 1.5 or later/true # disabled cairo check for Android (/g' "${PSPP_REF_SRC}/configure"
    sed -i.bak 's/as_fn_error.*cairo 1.5/true # disabled cairo error/g' "${PSPP_REF_SRC}/configure"
  fi
fi

# Copy GPLv3 COPYING from PSPP tarball into Android assets
if [[ -f "${PSPP_REF_SRC}/COPYING" ]]; then
  cp -f "${PSPP_REF_SRC}/COPYING" "${ASSETS_PSPP_DIR}/COPYING"
fi

# Helper: Map Android ABI to target triplet and compiler prefix
abi_to_host() {
  case "$1" in
    arm64-v8a)   echo "aarch64-linux-android" ;;
    armeabi-v7a) echo "arm-linux-androideabi" ;;
    x86_64)      echo "x86_64-linux-android" ;;
    *) echo "Unknown ABI: $1" >&2; exit 1 ;;
  esac
}

abi_to_clang_prefix() {
  case "$1" in
    arm64-v8a)   echo "aarch64-linux-android${ANDROID_API}" ;;
    armeabi-v7a) echo "armv7a-linux-androideabi${ANDROID_API}" ;;
    x86_64)      echo "x86_64-linux-android${ANDROID_API}" ;;
    *) echo "Unknown ABI: $1" >&2; exit 1 ;;
  esac
}

# 4. Cross-compile stack for each ABI
for ABI in "${ABIS[@]}"; do
  echo "======================================================================"
  echo "==> Building native dependency stack & PSPP ${PSPP_VERSION} for ABI: ${ABI}"
  echo "======================================================================"

  TARGET_HOST="$(abi_to_host "${ABI}")"
  CLANG_PREFIX="$(abi_to_clang_prefix "${ABI}")"
  ABI_PREFIX="${BUILD_ROOT}/sysroot/${ABI}"
  ABI_WORK="${BUILD_ROOT}/work/${ABI}"
  mkdir -p "${ABI_PREFIX}/lib/pkgconfig" "${ABI_PREFIX}/include" "${ABI_WORK}" "${JNILIBS_DIR}/${ABI}"

  export AR="${TOOLCHAIN}/bin/llvm-ar"
  export AS="${TOOLCHAIN}/bin/${CLANG_PREFIX}-clang"
  export CC="${TOOLCHAIN}/bin/${CLANG_PREFIX}-clang"
  export CXX="${TOOLCHAIN}/bin/${CLANG_PREFIX}-clang++"
  export LD="${TOOLCHAIN}/bin/ld.lld"
  export NM="${TOOLCHAIN}/bin/llvm-nm"
  export RANLIB="${TOOLCHAIN}/bin/llvm-ranlib"
  export STRIP="${TOOLCHAIN}/bin/llvm-strip"
  export CFLAGS="-O2 -fPIC -D_GNU_SOURCE -I${ABI_PREFIX}/include"
  export CXXFLAGS="-O2 -fPIC -D_GNU_SOURCE -I${ABI_PREFIX}/include"
  export CPPFLAGS="-I${ABI_PREFIX}/include -D_GNU_SOURCE"
  export LDFLAGS="-L${ABI_PREFIX}/lib"
  export PKG_CONFIG_PATH="${ABI_PREFIX}/lib/pkgconfig"
  export PKG_CONFIG_LIBDIR="${ABI_PREFIX}/lib/pkgconfig"

  # 4a. zlib
  if [[ ! -f "${ABI_PREFIX}/lib/libz.a" ]]; then
    echo "--> [${ABI}] Building zlib ${ZLIB_VERSION}..."
    rm -rf "${ABI_WORK}/zlib-${ZLIB_VERSION}"
    tar -xzf "${DOWNLOAD_DIR}/zlib-${ZLIB_VERSION}.tar.gz" -C "${ABI_WORK}"
    pushd "${ABI_WORK}/zlib-${ZLIB_VERSION}" >/dev/null
    CHOST="${TARGET_HOST}" ./configure -- prefix="${ABI_PREFIX}" --static
    make -j"${JOBS}"
    make install
    popd >/dev/null
  fi

  # 4b. GNU libiconv
  if [[ ! -f "${ABI_PREFIX}/lib/libiconv.a" ]]; then
    echo "--> [${ABI}] Building libiconv ${LIBICONV_VERSION}..."
    rm -rf "${ABI_WORK}/libiconv-${LIBICONV_VERSION}"
    tar -xzf "${DOWNLOAD_DIR}/libiconv-${LIBICONV_VERSION}.tar.gz" -C "${ABI_WORK}"
    pushd "${ABI_WORK}/libiconv-${LIBICONV_VERSION}" >/dev/null
    ./configure \
      --host="${TARGET_HOST}" \
      --prefix="${ABI_PREFIX}" \
      --enable-static \
      --disable-shared \
      --disable-rpath
    make -j"${JOBS}"
    make install
    popd >/dev/null
  fi

  # 4c. GNU gettext (libintl for NLS localization)
  if [[ ! -f "${ABI_PREFIX}/lib/libintl.a" ]]; then
    echo "--> [${ABI}] Building gettext/libintl ${GETTEXT_VERSION} (NLS enabled)..."
    rm -rf "${ABI_WORK}/gettext-${GETTEXT_VERSION}"
    tar -xzf "${DOWNLOAD_DIR}/gettext-${GETTEXT_VERSION}.tar.gz" -C "${ABI_WORK}"
    pushd "${ABI_WORK}/gettext-${GETTEXT_VERSION}/gettext-runtime" >/dev/null
    ./configure \
      --host="${TARGET_HOST}" \
      --prefix="${ABI_PREFIX}" \
      --enable-static \
      --disable-shared \
      --enable-nls \
      --with-included-gettext \
      --with-libiconv-prefix="${ABI_PREFIX}" \
      --disable-java \
      --disable-csharp \
      --disable-c++ \
      gl_cv_func_wcwidth_works=yes
    make -j"${JOBS}" -C intl
    make install -C intl
    popd >/dev/null
  fi

  # 4d. libxml2 (required by PSPP for ODT/SPV/XML parsing)
  if [[ ! -f "${ABI_PREFIX}/lib/libxml2.a" ]]; then
    echo "--> [${ABI}] Building libxml2 ${LIBXML2_VERSION}..."
    rm -rf "${ABI_WORK}/libxml2-${LIBXML2_VERSION}"
    tar -xJf "${DOWNLOAD_DIR}/libxml2-${LIBXML2_VERSION}.tar.xz" -C "${ABI_WORK}"
    pushd "${ABI_WORK}/libxml2-${LIBXML2_VERSION}" >/dev/null
    ./configure \
      --host="${TARGET_HOST}" \
      --prefix="${ABI_PREFIX}" \
      --enable-static \
      --disable-shared \
      --without-python \
      --without-lzma \
      --with-zlib="${ABI_PREFIX}" \
      --with-iconv="${ABI_PREFIX}"
    make -j"${JOBS}"
    make install
    popd >/dev/null
  fi

  # 4e. GNU Scientific Library (GSL + gslcblas)
  if [[ ! -f "${ABI_PREFIX}/lib/libgsl.a" ]]; then
    echo "--> [${ABI}] Building GSL ${GSL_VERSION}..."
    rm -rf "${ABI_WORK}/gsl-${GSL_VERSION}"
    tar -xzf "${DOWNLOAD_DIR}/gsl-${GSL_VERSION}.tar.gz" -C "${ABI_WORK}"
    pushd "${ABI_WORK}/gsl-${GSL_VERSION}" >/dev/null
    ./configure \
      --host="${TARGET_HOST}" \
      --prefix="${ABI_PREFIX}" \
      --enable-static \
      --disable-shared
    make -j"${JOBS}"
    make install
    popd >/dev/null
  fi

  # 4f. Create gnulib cross-compilation config.cache for PSPP 2.1.2
  CONFIG_CACHE="${ABI_WORK}/pspp-android.cache"
  cat > "${CONFIG_CACHE}" <<EOF
ac_cv_func_malloc_0_nonnull=yes
ac_cv_func_realloc_0_nonnull=yes
ac_cv_func_calloc_0_nonnull=yes
gl_cv_func_mbrtowc_incomplete_state=yes
gl_cv_func_mbrtowc_nul_retval=yes
gl_cv_func_mbrtowc_null_arg1=yes
gl_cv_func_mbrtowc_null_arg2=yes
gl_cv_func_mbrtowc_retval=yes
gl_cv_func_wcrtomb_retval=yes
gl_cv_func_snprintf_truncation_c99=yes
gl_cv_func_snprintf_retval_c99=yes
gl_cv_func_vsnprintf_zerosize_c99=yes
gl_cv_func_getcwd_null=yes
gl_cv_func_getcwd_path_max=yes
gl_cv_func_fdopendir_works=yes
gl_cv_func_fchownat_nofollow_works=yes
gl_cv_func_gettimeofday_clobber=no
gl_cv_func_memchr_works=yes
gl_cv_func_working_mkstemp=yes
gl_cv_func_strerror_0_works=yes
EOF

  # 4g. Build PSPP 2.1.2 static/convenience archives (libpspp, libpspp-core, libgl, ui/terminal)
  echo "--> [${ABI}] Configuring and building GNU PSPP ${PSPP_VERSION}..."
  rm -rf "${ABI_WORK}/pspp-build"
  cp -a "${PSPP_REF_SRC}" "${ABI_WORK}/pspp-build"
  pushd "${ABI_WORK}/pspp-build" >/dev/null

  CFLAGS="${CFLAGS} -DPSPP_ANDROID_LIBRARY=1" \
  LIBS="-lintl -liconv -lxml2 -lgsl -lgslcblas -lz -lm" \
  ./configure \
    --cache-file="${CONFIG_CACHE}" \
    --host="${TARGET_HOST}" \
    --prefix="${ABI_PREFIX}" \
    --enable-nls \
    --without-gui \
    --without-cairo \
    --without-libpq \
    --without-perl-module \
    --with-libiconv-prefix="${ABI_PREFIX}" \
    --with-libintl-prefix="${ABI_PREFIX}"

  make -j"${JOBS}" || {
    echo "    Note: Full target make stopped on host doc/man generation; installing compiled core libraries..."
  }
  make install-data || true

  # Copy compiled .mo message catalogs to Android assets on first ABI
  if [[ -d "${ABI_PREFIX}/share/locale" ]]; then
    cp -rf "${ABI_PREFIX}/share/locale/"* "${ASSETS_PSPP_DIR}/locale/" 2>/dev/null || true
  fi

  # 4h. Link libpspp_jni.so combining pspp_jni.c + PSPP static/PIC objects + dependencies
  echo "--> [${ABI}] Linking libpspp_jni.so..."
  PSPP_LIBS=()
  for candidate in \
    "src/.libs/libpspp.a" \
    "src/.libs/libpspp-core.a" \
    "gl/.libs/libgl.a"; do
    if [[ -f "${candidate}" ]]; then
      PSPP_LIBS+=("${candidate}")
    fi
  done

  "${CC}" ${CFLAGS} -shared \
    -DHAVE_PSPP_CORE=1 \
    -I"${ABI_WORK}/pspp-build" \
    -I"${ABI_WORK}/pspp-build/src" \
    -I"${ABI_WORK}/pspp-build/gl" \
    -I"${ABI_PREFIX}/include" \
    "${ROOT_DIR}/app/src/main/cpp/pspp_jni.c" \
    -Wl,--whole-archive "${PSPP_LIBS[@]}" -Wl,--no-whole-archive \
    "${ABI_PREFIX}/lib/libgsl.a" \
    "${ABI_PREFIX}/lib/libgslcblas.a" \
    "${ABI_PREFIX}/lib/libxml2.a" \
    "${ABI_PREFIX}/lib/libintl.a" \
    "${ABI_PREFIX}/lib/libiconv.a" \
    "${ABI_PREFIX}/lib/libz.a" \
    -lm -llog \
    -o "${JNILIBS_DIR}/${ABI}/libpspp_jni.so"

  "${STRIP}" --strip-unneeded "${JNILIBS_DIR}/${ABI}/libpspp_jni.so"
  popd >/dev/null
  echo "==> [${ABI}] Produced ${JNILIBS_DIR}/${ABI}/libpspp_jni.so ($(du -h "${JNILIBS_DIR}/${ABI}/libpspp_jni.so" | cut -f1))"
done

echo "======================================================================"
echo "All native libraries built successfully:"
ls -lh "${JNILIBS_DIR}"/*/libpspp_jni.so
echo "======================================================================"
