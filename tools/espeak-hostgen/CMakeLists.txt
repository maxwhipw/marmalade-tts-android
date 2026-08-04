# -----------------------------------------------------------------------------
# Host-side espeak-ng-data generator (BUILD TOOLING — never shipped, never
# committed output).
#
# Purpose: compile the espeak-ng dictionary data the baked default engine
# (Kitten Nano, English-only) needs, FROM the pinned third_party/espeak-ng
# submodule, AT BUILD TIME on the build host. The generated `espeak-ng-data/`
# is GPL-3.0-or-later and lands only in the Gradle build directory — it is
# assembled into the APK's assets alongside the repo-committed Apache-2.0
# model+voices, exactly the way libespeak-ng.so is compiled-in but never
# committed. The repo stays espeak-free and MIT.
#
# Why a bespoke host CMake instead of the submodule's own build:
#   * The submodule top-level CMake pulls in cmake/deps.cmake, which does a
#     FetchContent GIT clone of libsonic at configure time — that would break
#     F-Droid's OFFLINE reproducible buildserver. We include NEITHER deps.cmake
#     NOR config.cmake and set the feature flags by hand (same set the Android
#     lib build at app/src/main/cpp/espeak-ng/CMakeLists.txt already proves
#     compiles from the manual flags alone).
#   * We only need ENGLISH data, so we skip the ~110-language dict loop.
#
# This is a HOST build (native cmake/gcc), not the NDK cross-compile. It builds
# a host `espeak-ng-bin` and runs it to compile the dictionaries — the same
# mechanism as the submodule's cmake/data.cmake, trimmed to English.
# -----------------------------------------------------------------------------
cmake_minimum_required(VERSION 3.18)
project(espeak-hostgen VERSION 1.52.0 LANGUAGES C CXX)

get_filename_component(ESPEAK_ROOT
    ${CMAKE_CURRENT_SOURCE_DIR}/../../third_party/espeak-ng ABSOLUTE)

if (NOT EXISTS ${ESPEAK_ROOT}/src/libespeak-ng/CMakeLists.txt)
    message(FATAL_ERROR
        "espeak-ng submodule missing at ${ESPEAK_ROOT}. "
        "Run: git submodule update --init")
endif()

# Feature flags consumed by src/libespeak-ng/config.h.in + CMakeLists.txt.
# Same set as the Android build (klatt + speechPlayer on; no audio, no mbrola,
# no sonic, no async). HAVE_MKSTEMP: glibc has it — skip the check.
set(HAVE_MKSTEMP 1)
set(USE_ASYNC OFF)
set(USE_KLATT ON)
set(USE_SPEECHPLAYER ON)
set(USE_MBROLA OFF)
set(USE_LIBSONIC OFF)
set(USE_LIBPCAUDIO OFF)
set(HAVE_LIBSONIC OFF)
set(HAVE_LIBPCAUDIO OFF)

# Static host libs — no .so to place, the binary is throwaway build tooling.
set(BUILD_SHARED_LIBS OFF)

# The submodule's src/CMakeLists.txt normally defines this interface target;
# recreate it with absolute paths since we skip that file.
add_library(espeak-include INTERFACE)
target_include_directories(espeak-include INTERFACE
    ${ESPEAK_ROOT}/src/include
    ${ESPEAK_ROOT}/src/include/compat
)

add_subdirectory(${ESPEAK_ROOT}/src/ucd-tools     ${CMAKE_CURRENT_BINARY_DIR}/ucd-tools)
add_subdirectory(${ESPEAK_ROOT}/src/speechPlayer  ${CMAKE_CURRENT_BINARY_DIR}/speechPlayer)
add_subdirectory(${ESPEAK_ROOT}/src/libespeak-ng  ${CMAKE_CURRENT_BINARY_DIR}/libespeak-ng)

# The CLI binary (data compiler). src/libespeak-ng defines espeak-ng +
# espeak-ng-config; mirror src/CMakeLists.txt's link line.
add_executable(espeak-ng-bin ${ESPEAK_ROOT}/src/espeak-ng.c)
set_target_properties(espeak-ng-bin PROPERTIES OUTPUT_NAME espeak-ng)
target_link_libraries(espeak-ng-bin PRIVATE espeak-ng espeak-ng-config)

# ---- Data compile (English only) --------------------------------------------
# Replicates third_party/espeak-ng/cmake/data.cmake trimmed to `en`.
set(DATA_DIST_ROOT ${CMAKE_CURRENT_BINARY_DIR})
set(DATA_DIST_DIR  ${DATA_DIST_ROOT}/espeak-ng-data)
set(DICT_TMP_DIR   ${DATA_DIST_ROOT}/dictsource)

set(DATA_SRC_DIR    ${ESPEAK_ROOT}/espeak-ng-data)
set(PHONEME_SRC_DIR ${ESPEAK_ROOT}/phsource)
set(DICT_SRC_DIR    ${ESPEAK_ROOT}/dictsource)

file(MAKE_DIRECTORY "${DATA_DIST_DIR}")
file(MAKE_DIRECTORY "${DICT_TMP_DIR}")
# lang/ (all languages' small voice defs — espeak needs lang/en/… present) and
# the !v variant voices. phsource is needed by --compile-phonemes.
file(COPY "${DATA_SRC_DIR}/lang"     DESTINATION "${DATA_DIST_DIR}")
file(COPY "${DATA_SRC_DIR}/voices/!v" DESTINATION "${DATA_DIST_DIR}/voices")
file(COPY "${PHONEME_SRC_DIR}"       DESTINATION "${DATA_DIST_ROOT}")

set(ESPEAK_RUN_CMD ${CMAKE_COMMAND} -E env "ESPEAK_DATA_PATH=${DATA_DIST_ROOT}"
    "$<TARGET_FILE:espeak-ng-bin>")

add_custom_command(
  OUTPUT "${DATA_DIST_DIR}/intonations"
  COMMAND ${ESPEAK_RUN_CMD} --compile-intonations
  WORKING_DIRECTORY "${PHONEME_SRC_DIR}"
  COMMENT "espeak-hostgen: compile intonations"
  DEPENDS "$<TARGET_FILE:espeak-ng-bin>" "${PHONEME_SRC_DIR}/intonation"
)

add_custom_command(
  OUTPUT
    "${DATA_DIST_DIR}/phondata"
    "${DATA_DIST_DIR}/phondata-manifest"
    "${DATA_DIST_DIR}/phonindex"
    "${DATA_DIST_DIR}/phontab"
  COMMAND ${ESPEAK_RUN_CMD} --compile-phonemes
  WORKING_DIRECTORY "${PHONEME_SRC_DIR}"
  COMMENT "espeak-hostgen: compile phonemes"
  DEPENDS "${DATA_DIST_DIR}/intonations" "$<TARGET_FILE:espeak-ng-bin>"
)

# English dict. Copy its sources into a tmp dir + run --compile=en from there
# (matches data.cmake). en has _rules/_list plus _listx (extra) and _emoji.
set(_en_deps "${DICT_SRC_DIR}/en_rules" "${DICT_SRC_DIR}/en_list")
if(EXISTS "${DICT_SRC_DIR}/extra/en_listx")
  list(APPEND _en_deps "${DICT_SRC_DIR}/extra/en_listx")
elseif(EXISTS "${DICT_SRC_DIR}/en_listx")
  list(APPEND _en_deps "${DICT_SRC_DIR}/en_listx")
endif()
if(EXISTS "${DICT_SRC_DIR}/en_emoji")
  list(APPEND _en_deps "${DICT_SRC_DIR}/en_emoji")
endif()

add_custom_command(
  OUTPUT "${DATA_DIST_DIR}/en_dict"
  COMMAND ${CMAKE_COMMAND} -E copy ${_en_deps} "${DICT_TMP_DIR}/"
  COMMAND ${ESPEAK_RUN_CMD} --compile=en
  WORKING_DIRECTORY "${DICT_TMP_DIR}"
  COMMENT "espeak-hostgen: compile en_dict"
  DEPENDS "$<TARGET_FILE:espeak-ng-bin>" "${DATA_DIST_DIR}/phondata"
          "${DATA_DIST_DIR}/intonations" ${_en_deps}
)

add_custom_target(espeakdata ALL
  DEPENDS
    "${DATA_DIST_DIR}/intonations"
    "${DATA_DIST_DIR}/phondata"
    "${DATA_DIST_DIR}/en_dict"
)
