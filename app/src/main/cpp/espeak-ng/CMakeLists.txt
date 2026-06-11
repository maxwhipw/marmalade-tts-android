# -----------------------------------------------------------------------------
# espeak-ng built from source — produces libespeak-ng.so inside the APK.
#
# Sources come unmodified from the pinned submodule at third_party/espeak-ng
# (GPL-3.0-or-later). We deliberately do NOT add_subdirectory the submodule's
# top-level CMakeLists: it drags in data compilation (needs a host espeak
# binary), tests, packaging, and a FetchContent network clone of libsonic —
# all of which would break Android cross-compilation and F-Droid's offline
# buildserver. Instead this file provides exactly the parent-scope pieces
# src/libespeak-ng/CMakeLists.txt expects (the espeak-include target, the ucd
# and speechPlayer libraries, and the config.h feature flags) and builds only
# the library.
#
# Shipping this .so makes the distributed APK a GPL-3.0-or-later combined
# work. The repo's own source files stay MIT. See NOTICE.md.
# -----------------------------------------------------------------------------

# VERSION feeds PACKAGE_VERSION in espeak's config.h (espeak_Info()'s version
# string) and the library SOVERSION. Keep in lockstep with the submodule tag.
project(espeak-ng-android VERSION 1.52.0 LANGUAGES C CXX)

get_filename_component(ESPEAK_ROOT
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../third_party/espeak-ng ABSOLUTE)

if (NOT EXISTS ${ESPEAK_ROOT}/src/libespeak-ng/CMakeLists.txt)
    message(FATAL_ERROR
        "espeak-ng submodule missing at ${ESPEAK_ROOT}. "
        "Run: git submodule update --init")
endif()

# Feature flags consumed by src/libespeak-ng/config.h.in and CMakeLists.txt.
# Matches the official espeak-ng Android build: klatt + speechPlayer voice
# variants on; no audio output (we only call espeak_TextToPhonemes), no
# mbrola, no sonic, no async fifo thread.
set(HAVE_MKSTEMP 1)            # bionic has mkstemp; skip check_symbol_exists
set(USE_ASYNC OFF)
set(USE_KLATT ON)
set(USE_SPEECHPLAYER ON)
set(USE_MBROLA OFF)
set(USE_LIBSONIC OFF)
set(USE_LIBPCAUDIO OFF)
set(HAVE_LIBSONIC OFF)
set(HAVE_LIBPCAUDIO OFF)

# Directory-scoped: makes add_library(espeak-ng) in the subdir a SHARED lib.
# ucd and speechPlayer declare STATIC explicitly, so they're unaffected.
set(BUILD_SHARED_LIBS ON)

# The submodule's src/CMakeLists.txt normally defines this interface target;
# we recreate it with absolute paths since we skip that file.
add_library(espeak-include INTERFACE)
target_include_directories(espeak-include INTERFACE
    ${ESPEAK_ROOT}/src/include
    ${ESPEAK_ROOT}/src/include/compat
)

add_subdirectory(${ESPEAK_ROOT}/src/ucd-tools     ${CMAKE_CURRENT_BINARY_DIR}/ucd-tools)
add_subdirectory(${ESPEAK_ROOT}/src/speechPlayer  ${CMAKE_CURRENT_BINARY_DIR}/speechPlayer)
add_subdirectory(${ESPEAK_ROOT}/src/libespeak-ng  ${CMAKE_CURRENT_BINARY_DIR}/libespeak-ng)
