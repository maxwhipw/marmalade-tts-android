cmake_minimum_required(VERSION 3.22.1)

# C for the espeak shim + openjtalk_jni; CXX because Open JTalk's bundled
# MeCab is C++.
project("marmalade-native" LANGUAGES C CXX)

# -----------------------------------------------------------------------------
# espeak-ng — built from source (pinned submodule) into libespeak-ng.so, which
# ships inside the APK. Required by Google Play (no runtime download of
# executable code) and preferred by F-Droid (built from source on their
# buildserver). GPL-3.0-or-later; see espeak-ng/CMakeLists.txt and NOTICE.md.
# -----------------------------------------------------------------------------
add_subdirectory(espeak-ng)

# -----------------------------------------------------------------------------
# espeak-jni — tiny JNI shim that dlopens libespeak-ng.so at runtime from the
# APK's own native-library directory. Still dlopen rather than a build-time
# link so the shim's source stays free of espeak headers; the combined APK is
# GPL-3.0-or-later regardless because libespeak-ng.so ships in it.
# -----------------------------------------------------------------------------
add_library(espeak-jni SHARED
        espeak_jni.c
)
find_library(log-lib log)
target_link_libraries(espeak-jni
        ${log-lib}
        dl
)

# -----------------------------------------------------------------------------
# openjtalk — vendored Open JTalk frontend (BSD-3). Unlike espeak we statically
# link this into our shim: BSD lets us, and it keeps the GPL-isolation dance
# unnecessary. The `openjtalk` static-lib target comes from the vendored
# upstream CMakeLists (frontend only — bin/ + HTS_engine are gated off by its
# BUILD_PROGRAMS=OFF default). The mecab dictionary ships in the engine bundle,
# not here.
# -----------------------------------------------------------------------------
add_subdirectory(openjtalk)

add_library(openjtalk-jni SHARED
        openjtalk_jni.c
)

# Header dirs for the frontend chain the shim calls into.
target_include_directories(openjtalk-jni PRIVATE
        openjtalk/mecab/src
        openjtalk/njd
        openjtalk/text2mecab
        openjtalk/mecab2njd
        openjtalk/njd_set_pronunciation
        openjtalk/njd_set_digit
        openjtalk/njd_set_accent_phrase
        openjtalk/njd_set_accent_type
        openjtalk/njd_set_unvoiced_vowel
        openjtalk/njd_set_long_vowel
)

target_link_libraries(openjtalk-jni
        openjtalk
        ${log-lib}
)
