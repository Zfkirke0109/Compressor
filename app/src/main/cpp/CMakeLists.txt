cmake_minimum_required(VERSION 3.22.1)
# CXX is required because libvmaf.a embeds C++ objects (svm.cpp); the final .so must
# link libc++ (ANDROID_STL=c++_static is passed from Gradle).
project(compressorvmaf C CXX)

# Prebuilt static libvmaf (arm64-v8a only; cross-compiled from third_party/vmaf v3.0.0
# with meson + NDK clang; see validation/vmaf_analysis/libvmaf_build.log for provenance).
set(VMAF_PREBUILT_DIR ${CMAKE_CURRENT_SOURCE_DIR}/prebuilt/${ANDROID_ABI})
set(VMAF_INCLUDE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/prebuilt/include)

if(NOT EXISTS ${VMAF_PREBUILT_DIR}/libvmaf.a)
    message(FATAL_ERROR "libvmaf.a missing for ${ANDROID_ABI}; build it first (see third_party/vmaf).")
endif()

add_library(vmaf STATIC IMPORTED)
set_target_properties(vmaf PROPERTIES IMPORTED_LOCATION ${VMAF_PREBUILT_DIR}/libvmaf.a)

add_library(compressorvmaf SHARED vmaf_jni.c)
set_target_properties(compressorvmaf PROPERTIES LINKER_LANGUAGE CXX)
target_include_directories(compressorvmaf PRIVATE ${VMAF_INCLUDE_DIR})
# Android 15+/16 devices with 16 KB memory pages require ELF segments aligned to 16384;
# NDK r27 does not do this by default (r28+ does).
target_link_options(compressorvmaf PRIVATE "-Wl,-z,max-page-size=16384")
target_link_libraries(compressorvmaf vmaf log m)
