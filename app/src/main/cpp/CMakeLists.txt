cmake_minimum_required(VERSION 3.22.1)
project("pspp_jni" C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)

# Locate prebuilt cross-compiled archives under native-build/sysroot/${ANDROID_ABI}
set(SYSROOT_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../native-build/sysroot/${ANDROID_ABI}")
set(PSPP_BUILD_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../native-build/work/${ANDROID_ABI}/pspp-build")

include_directories(
    ${CMAKE_CURRENT_SOURCE_DIR}
    "${SYSROOT_DIR}/include"
    "${SYSROOT_DIR}/include/libxml2"
    "${PSPP_BUILD_DIR}"
    "${PSPP_BUILD_DIR}/src"
    "${PSPP_BUILD_DIR}/gl"
)

add_library(pspp_jni SHARED pspp_jni.c)

find_library(log-lib log)
find_library(m-lib m)

if(EXISTS "${PSPP_BUILD_DIR}/src/.libs/libpspp.a")
    target_compile_definitions(pspp_jni PRIVATE HAVE_PSPP_CORE=1 PSPP_ANDROID_LIBRARY=1)
    target_link_libraries(
        pspp_jni
        -Wl,--whole-archive
        "${PSPP_BUILD_DIR}/src/.libs/libpspp.a"
        "${PSPP_BUILD_DIR}/src/.libs/libpspp-core.a"
        "${PSPP_BUILD_DIR}/gl/.libs/libgl.a"
        -Wl,--no-whole-archive
        "${SYSROOT_DIR}/lib/libgsl.a"
        "${SYSROOT_DIR}/lib/libgslcblas.a"
        "${SYSROOT_DIR}/lib/libxml2.a"
        "${SYSROOT_DIR}/lib/libintl.a"
        "${SYSROOT_DIR}/lib/libiconv.a"
        "${SYSROOT_DIR}/lib/libz.a"
        ${m-lib}
        ${log-lib}
    )
else()
    # Allows standalone CMake syntax/ABI verification if build-native.sh has not yet populated native-build/
    target_link_libraries(pspp_jni ${m-lib} ${log-lib})
endif()
