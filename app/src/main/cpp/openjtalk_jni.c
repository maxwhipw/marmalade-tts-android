// -----------------------------------------------------------------------------
// openjtalk-jni — Kotlin <-> Open JTalk frontend bridge for KokoroDirect (ja).
//
// Open JTalk is BSD-3 (unlike espeak-ng's GPL-3.0), so we statically link its
// frontend into THIS shim — no dlopen dance. The shim ships in the APK; only
// the mecab dictionary (open_jtalk_dic_utf_8-1.11, ~22 MB) ships in the engine
// bundle (too big for the APK, and it's data not code).
//
// We use ONLY Open JTalk's text-analysis frontend — the chain that turns raw
// Japanese into NJD (Naist Japanese Dictionary) features: kanji→kana reading,
// mora segmentation, and pitch-accent. We deliberately stop before
// njd2jpcommon + HTS_engine (that's the neural vocoder Open JTalk ships for its
// own synthesis — we don't link it). Kokoro is our acoustic model; Open JTalk
// is purely the g2p front half, exactly as misaki uses pyopenjtalk.
//
// Frontend chain (order matters — mirrors bin/open_jtalk.c:175-184 and
// pyopenjtalk.run_frontend):
//   text2mecab → Mecab_analysis → mecab2njd → njd_set_pronunciation →
//   njd_set_digit → njd_set_accent_phrase → njd_set_accent_type →
//   njd_set_unvoiced_vowel → njd_set_long_vowel → walk NJD nodes
//
// Per-node we hand Kotlin: string, read, pron, acc, mora_size, chain_flag, pos.
// CutletJaG2P.kt (Kotlin) maps the katakana `pron` to Kokoro's IPA.
//
// `acc` is handed over but NOT used, and that is deliberate: Kokoro v1.0 has
// no tokens for pitch markers, so the Kotlin side is segmental-only like
// misaki's cutlet path. Audited 2026-07-27 — this comment previously said
// "IPA + pitch markers", which was never true of the shipped path.
//
// Threading: Open JTalk's Mecab/NJD state is per-instance but we keep a single
// global instance (one dictionary load is expensive). The Kotlin side
// serialises calls through a Mutex; we also guard here with a pthread mutex.
// -----------------------------------------------------------------------------

#include <jni.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>

#include "mecab.h"
#include "njd.h"
#include "text2mecab.h"
#include "mecab2njd.h"
#include "njd_set_pronunciation.h"
#include "njd_set_digit.h"
#include "njd_set_accent_phrase.h"
#include "njd_set_accent_type.h"
#include "njd_set_unvoiced_vowel.h"
#include "njd_set_long_vowel.h"

#define TAG "OpenJtalkJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// Single global Open JTalk frontend instance. Loading the dictionary is the
// expensive step (~100 MB mmap'd), so we load once and reuse.
static Mecab            gMecab;
static NJD              gNjd;
static int              gOpened = 0;
static pthread_mutex_t  gLock = PTHREAD_MUTEX_INITIALIZER;

JNIEXPORT jint JNICALL
Java_app_marmalade_tts_phonemizer_OpenJtalkPhonemizer_nativeOpen(
        JNIEnv *env, jobject self, jstring dictDir) {

    pthread_mutex_lock(&gLock);
    jint ret = 0;

    if (gOpened) {
        LOGW("open called while already open — clearing previous instance");
        Mecab_clear(&gMecab);
        NJD_clear(&gNjd);
        gOpened = 0;
    }

    const char *cDict = (*env)->GetStringUTFChars(env, dictDir, NULL);
    if (!cDict) {
        LOGE("nativeOpen: null dict dir");
        goto done;
    }

    Mecab_initialize(&gMecab);
    NJD_initialize(&gNjd);

    if (Mecab_load(&gMecab, cDict) != 1) {
        LOGE("Mecab_load(%s) failed", cDict);
        Mecab_clear(&gMecab);
        NJD_clear(&gNjd);
        ret = -1;
        goto release;
    }

    gOpened = 1;
    ret = 1;
    LOGI("Open JTalk frontend loaded from %s", cDict);

release:
    (*env)->ReleaseStringUTFChars(env, dictDir, cDict);
done:
    pthread_mutex_unlock(&gLock);
    return ret;
}

/**
 * Run the frontend on [text] and return the NJD nodes serialised as a single
 * string: one node per line, fields tab-separated in the order
 *   string \t read \t pron \t acc \t mora_size \t chain_flag \t pos
 * Returns an empty string on failure or zero nodes. NULL fields render empty.
 */
JNIEXPORT jstring JNICALL
Java_app_marmalade_tts_phonemizer_OpenJtalkPhonemizer_nativeRunFrontend(
        JNIEnv *env, jobject self, jstring text) {

    pthread_mutex_lock(&gLock);
    jstring result = NULL;

    if (!gOpened) {
        LOGE("runFrontend called before open");
        goto done;
    }

    const char *cText = (*env)->GetStringUTFChars(env, text, NULL);
    if (!cText) goto done;

    // text2mecab can expand the input (escaping, normalisation). 4× + slack is
    // comfortably above worst case for the short chunks KokoroDirect feeds.
    size_t inLen = strlen(cText);
    size_t mbufCap = inLen * 4 + 1024;
    char *mbuf = (char *) malloc(mbufCap);
    if (!mbuf) {
        LOGE("runFrontend: malloc(%zu) failed", mbufCap);
        (*env)->ReleaseStringUTFChars(env, text, cText);
        goto done;
    }

    text2mecab(mbuf, cText);
    Mecab_analysis(&gMecab, mbuf);
    mecab2njd(&gNjd, Mecab_get_feature(&gMecab), Mecab_get_size(&gMecab));
    njd_set_pronunciation(&gNjd);
    njd_set_digit(&gNjd);
    njd_set_accent_phrase(&gNjd);
    njd_set_accent_type(&gNjd);
    njd_set_unvoiced_vowel(&gNjd);
    njd_set_long_vowel(&gNjd);

    // Serialise NJD nodes into a growable buffer.
    size_t cap = 1024;
    size_t len = 0;
    char *out = (char *) malloc(cap);
    if (!out) {
        LOGE("runFrontend: out malloc failed");
        free(mbuf);
        NJD_refresh(&gNjd);
        (*env)->ReleaseStringUTFChars(env, text, cText);
        goto done;
    }
    out[0] = '\0';

    for (NJDNode *node = gNjd.head; node != NULL; node = node->next) {
        const char *string = NJDNode_get_string(node);
        const char *read   = NJDNode_get_read(node);
        const char *pron   = NJDNode_get_pron(node);
        const char *pos    = NJDNode_get_pos(node);
        int acc  = NJDNode_get_acc(node);
        int mora = NJDNode_get_mora_size(node);
        int chain = NJDNode_get_chain_flag(node);
        if (!string) string = "";
        if (!read)   read = "";
        if (!pron)   pron = "";
        if (!pos)    pos = "";

        // Worst-case line length: the four strings + 3 ints (≤12 chars each) +
        // 6 tabs + newline + null. Grow if needed.
        size_t need = len + strlen(string) + strlen(read) + strlen(pron) +
                      strlen(pos) + 64;
        if (need > cap) {
            while (cap < need) cap *= 2;
            char *grown = (char *) realloc(out, cap);
            if (!grown) {
                LOGE("runFrontend: realloc to %zu failed", cap);
                free(out);
                out = NULL;
                break;
            }
            out = grown;
        }
        len += (size_t) snprintf(out + len, cap - len, "%s\t%s\t%s\t%d\t%d\t%d\t%s\n",
                                 string, read, pron, acc, mora, chain, pos);
    }

    if (out != NULL) {
        result = (*env)->NewStringUTF(env, out);
        free(out);
    }

    // Reset NJD for the next call (mirrors open_jtalk.c:205). Mecab has no
    // per-call state to reset — its lattice is rebuilt on each analysis.
    NJD_refresh(&gNjd);
    free(mbuf);
    (*env)->ReleaseStringUTFChars(env, text, cText);

done:
    pthread_mutex_unlock(&gLock);
    return result;
}

JNIEXPORT void JNICALL
Java_app_marmalade_tts_phonemizer_OpenJtalkPhonemizer_nativeClose(
        JNIEnv *env, jobject self) {
    pthread_mutex_lock(&gLock);
    if (gOpened) {
        Mecab_clear(&gMecab);
        NJD_clear(&gNjd);
        gOpened = 0;
    }
    pthread_mutex_unlock(&gLock);
}
