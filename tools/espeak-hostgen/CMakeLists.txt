# -----------------------------------------------------------------------------
# Host-side espeak-ng-data generator (BUILD TOOLING — never shipped, never
# committed output).
#
# Purpose: compile the FULL espeak-ng data set (every language dict) FROM the
# pinned third_party/espeak-ng submodule, AT BUILD TIME on the build host. The
# generated `espeak-ng-data/` is GPL-3.0-or-later and lands only in the Gradle
# build directory — it is assembled into the APK's assets, exactly the way
# libespeak-ng.so is compiled-in but never committed. The repo stays
# espeak-free and MIT.
#
# The data serves ONE app-level shared espeak-ng-data (seeded to filesDir by
# SharedEspeakData) used by every espeak engine in the process. It must be the
# superset: espeak_Initialize takes exactly one data path, Kokoro phonemizes
# es/fr/hi/it/pt (+en) through espeak, and the alias editor's phonemization-
# language override accepts any espeak language code.
#
# Why a bespoke host CMake instead of the submodule's own build:
#   * The submodule top-level CMake pulls in cmake/deps.cmake, which does a
#     FetchContent GIT clone of libsonic at configure time — that would break
#     F-Droid's OFFLINE reproducible buildserver. We include NEITHER deps.cmake
#     NOR config.cmake and set the feature flags by hand (same set the Android
#     lib build at app/src/main/cpp/espeak-ng/CMakeLists.txt already proves
#     compiles from the manual flags alone).
# This is a HOST build (native cmake/gcc), not the NDK cross-compile. It builds
# a host `espeak-ng-bin` and runs it to compile the dictionaries — the same
# mechanism as the submodule's cmake/data.cmake (its full dict loop, minus the
# mbrola section since USE_MBROLA is off).
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

# ---- Data compile (full dictionary set) -------------------------------------
# Replicates third_party/espeak-ng/cmake/data.cmake for every language in
# _dict_compile_list below.
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

# Per-language dicts — the full list from cmake/data.cmake. For each language:
# copy its sources (_rules/_list, plus _listx — preferring extra/ — and _emoji
# when present) into the shared tmp dir and run --compile=<lang> from there.
# Same recipe data.cmake uses; the extra/ listx variants are its default-ON
# option branch.
list(APPEND _dict_compile_list
  af am an ar as az
  ba be bg bn bpy bs
  ca chr cmn cs cv cy
  da de
  el en eo es et eu
  fa fi fr
  ga gd gn grc gu
  hak haw he hi hr ht hu hy
  ia id io is it
  ja jbo
  ka kk kl kn kok ko ku ky
  la lb lfn lt lv
  mi mk ml mr ms mto mt my
  nci ne nl nog no
  om or
  pap pa piqd pl pt py
  qdb quc qu qya
  ro ru
  sd shn si sjn sk sl smj sq sr sv sw
  ta te ti th tk tn tr tt
  ug uk ur uz
  vi
  yue
)

list(APPEND _dict_targets)
foreach(_dict_name ${_dict_compile_list})
  set(_dict_target "${DATA_DIST_DIR}/${_dict_name}_dict")
  set(_dict_deps
    "${DICT_SRC_DIR}/${_dict_name}_rules"
    "${DICT_SRC_DIR}/${_dict_name}_list"
  )
  list(APPEND _dict_targets ${_dict_target})

  if(EXISTS "${DICT_SRC_DIR}/extra/${_dict_name}_listx")
    list(APPEND _dict_deps "${DICT_SRC_DIR}/extra/${_dict_name}_listx")
  elseif(EXISTS "${DICT_SRC_DIR}/${_dict_name}_listx")
    list(APPEND _dict_deps "${DICT_SRC_DIR}/${_dict_name}_listx")
  endif()
  if(EXISTS "${DICT_SRC_DIR}/${_dict_name}_emoji")
    list(APPEND _dict_deps "${DICT_SRC_DIR}/${_dict_name}_emoji")
  endif()

  add_custom_command(
    OUTPUT "${_dict_target}"
    COMMAND ${CMAKE_COMMAND} -E copy ${_dict_deps} "${DICT_TMP_DIR}/"
    COMMAND ${ESPEAK_RUN_CMD} --compile=${_dict_name}
    WORKING_DIRECTORY "${DICT_TMP_DIR}"
    COMMENT "espeak-hostgen: compile ${_dict_name}_dict"
    DEPENDS "$<TARGET_FILE:espeak-ng-bin>" "${DATA_DIST_DIR}/phondata"
            "${DATA_DIST_DIR}/intonations" ${_dict_deps}
  )
endforeach()

add_custom_target(espeakdata ALL
  DEPENDS
    "${DATA_DIST_DIR}/intonations"
    "${DATA_DIST_DIR}/phondata"
    ${_dict_targets}
)
