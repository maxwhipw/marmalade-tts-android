package app.marmalade.tts.smokeprobe;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.util.Base64;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Drives one TTS engine through the public TextToSpeech API, from adb:
 *
 * <pre>
 * am instrument -w -e engine app.marmalade.tts.rc -e mode list \
 *     app.marmalade.tts.smokeprobe/.SmokeProbe
 * am instrument -w -e engine app.marmalade.tts.rc -e mode speak \
 *     -e voice kokoro-direct-v1_0:af_heart -e text64 &lt;base64 utf-8&gt; -e out row1 \
 *     app.marmalade.tts.smokeprobe/.SmokeProbe
 * </pre>
 *
 * "list" reports every advertised voice as {@code name,languageTag} joined by
 * ';'. "voices" binds the way a voice picker does (Samsung Routines, #108):
 * construct, wait for onInit, then getVoices at once, timed ("initMs",
 * "getVoicesMs", a warm "getVoicesAgainMs"), and reports every field of every
 * Voice, including the ISO 639-2 / 3166 alpha-3 codes a picker derives from the
 * locale ("!" + the exception when the locale has none), then
 * getDefaultVoice(), getVoice(), and setLanguage() for each listed locale. "speak" renders the text with that voice to
 * {@code <external files>/<out>.wav} (nothing is played) and reports what came
 * out. {@code -e lang deu} sends that request language (ISO 639-2) with the
 * voice, as a client reading German with a multilingual voice would: the
 * voice's own locale otherwise. {@code -e rate 1.5} asks for that speech rate
 * with {@code setSpeechRate}, as a client app's speed setting would.
 *
 * "speak" also times the render without playing it: every chunk the engine
 * hands the framework reaches {@code onAudioAvailable} as it is produced, so
 * "chunks" lists {@code <ms since the request>:<bytes>} per chunk, joined by
 * ';', and "beginMs" is when {@code onBeginSynthesis} arrived. Replaying that
 * list against a real-time audio clock says whether playback would have kept
 * up (release-smoke.py's speed stage).
 *
 * Results are the instrumentation's result bundle, one line per key;
 * "status" is "ok" or "error" (with "error" saying why).
 */
public class SmokeProbe extends Instrumentation {
    private static final long INIT_TIMEOUT_S = 60;
    private Bundle args;

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        args = arguments;
        start();
    }

    @Override
    public void onStart() {
        Bundle out = new Bundle();
        TextToSpeech tts = null;
        try {
            String engine = required("engine");
            final CountDownLatch ready = new CountDownLatch(1);
            final int[] initStatus = {TextToSpeech.ERROR};
            long t0 = SystemClock.elapsedRealtime();
            tts = new TextToSpeech(getContext(), status -> {
                initStatus[0] = status;
                ready.countDown();
            }, engine);
            if (!ready.await(INIT_TIMEOUT_S, TimeUnit.SECONDS)) {
                throw new IllegalStateException("engine " + engine + " did not initialise in " + INIT_TIMEOUT_S + " s");
            }
            if (initStatus[0] != TextToSpeech.SUCCESS) {
                throw new IllegalStateException("engine " + engine + " init status " + initStatus[0]);
            }
            out.putString("initMs", Long.toString(SystemClock.elapsedRealtime() - t0));
            String mode = required("mode");
            if (mode.equals("list")) {
                list(tts, out);
            } else if (mode.equals("voices")) {
                voices(tts, out);
            } else if (mode.equals("speak")) {
                speak(tts, out);
            } else {
                throw new IllegalArgumentException("unknown mode " + mode);
            }
            out.putString("status", "ok");
        } catch (Throwable t) {
            out.putString("status", "error");
            out.putString("error", oneLine(t.toString()));
        } finally {
            if (tts != null) tts.shutdown();
        }
        finish(Activity.RESULT_OK, out);
    }

    private void list(TextToSpeech tts, Bundle out) {
        Set<Voice> voices = tts.getVoices();
        List<String> rows = new ArrayList<>();
        if (voices != null) {
            for (Voice v : voices) rows.add(v.getName() + "," + v.getLocale().toLanguageTag());
        }
        Collections.sort(rows);
        out.putString("voices", String.join(";", rows));
    }

    private void voices(TextToSpeech tts, Bundle out) {
        long t = SystemClock.elapsedRealtime();
        Set<Voice> voices = tts.getVoices();
        out.putString("getVoicesMs", Long.toString(SystemClock.elapsedRealtime() - t));
        t = SystemClock.elapsedRealtime();
        tts.getVoices();
        out.putString("getVoicesAgainMs", Long.toString(SystemClock.elapsedRealtime() - t));
        if (voices == null) {
            out.putString("count", "null");
            return;
        }
        out.putString("count", Integer.toString(voices.size()));
        Voice def = tts.getDefaultVoice();
        out.putString("defaultVoice", def == null ? "null" : def.getName());
        Set<java.util.Locale> langs = tts.getAvailableLanguages();
        out.putString("availableLanguages", langs == null ? "null" : Integer.toString(langs.size()));
        List<String> rows = new ArrayList<>();
        for (Voice v : voices) {
            java.util.Locale l = v.getLocale();
            rows.add(v.getName() + " | " + (l == null ? "null" : l.toLanguageTag() + " " + iso3(l, true) + "-" + iso3(l, false)
                    + " \"" + l.getDisplayName(java.util.Locale.US) + "\" avail=" + tts.isLanguageAvailable(l))
                    + " | q=" + v.getQuality() + " lat=" + v.getLatency() + " net=" + v.isNetworkConnectionRequired()
                    + " features=" + v.getFeatures());
        }
        Collections.sort(rows);
        for (int i = 0; i < rows.size(); i++) out.putString(String.format(java.util.Locale.ROOT, "v%03d", i), rows.get(i));
        // What a picker that preselects the current voice, or switches the
        // language per group, gets back: null / LANG_NOT_SUPPORTED (-2) when
        // the engine's default voice name isn't one of its listed voices.
        Voice current = tts.getVoice();
        out.putString("voice", current == null ? "null" : current.getName());
        java.util.TreeMap<String, Integer> setLanguage = new java.util.TreeMap<>();
        for (Voice v : voices) {
            if (v.getLocale() != null) setLanguage.put(v.getLocale().toLanguageTag(), null);
        }
        for (String tag : new ArrayList<>(setLanguage.keySet())) {
            setLanguage.put(tag, tts.setLanguage(java.util.Locale.forLanguageTag(tag)));
        }
        out.putString("setLanguage", setLanguage.toString());
    }

    private static String iso3(java.util.Locale l, boolean language) {
        try {
            return language ? l.getISO3Language() : l.getISO3Country();
        } catch (Exception e) {
            return "!" + e.getClass().getSimpleName();
        }
    }

    private void speak(TextToSpeech tts, Bundle out) throws Exception {
        String voiceName = required("voice");
        String text = new String(Base64.decode(required("text64"), Base64.DEFAULT), StandardCharsets.UTF_8);
        long timeoutS = Long.parseLong(args.getString("timeout", "180"));

        Voice voice = null;
        Set<Voice> voices = tts.getVoices();
        if (voices != null) {
            for (Voice v : voices) if (v.getName().equals(voiceName)) voice = v;
        }
        if (voice == null) throw new IllegalStateException("voice not advertised: " + voiceName);
        int set = tts.setVoice(voice);
        if (set != TextToSpeech.SUCCESS) throw new IllegalStateException("setVoice returned " + set);

        File dir = getContext().getExternalFilesDir(null);
        File wav = new File(dir, required("out") + ".wav");
        if (wav.exists() && !wav.delete()) throw new IllegalStateException("cannot replace " + wav);

        String rate = args.getString("rate");
        if (rate != null) {
            int r = tts.setSpeechRate(Float.parseFloat(rate));
            if (r != TextToSpeech.SUCCESS) throw new IllegalStateException("setSpeechRate returned " + r);
        }

        final CountDownLatch done = new CountDownLatch(1);
        final String[] failure = {null};
        final long[] t0 = {0};
        final long[] beginMs = {-1};
        final StringBuilder chunks = new StringBuilder();
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) { }
            @Override public void onBeginSynthesis(String id, int sampleRate, int format, int channels) {
                synchronized (chunks) { beginMs[0] = SystemClock.elapsedRealtime() - t0[0]; }
            }
            @Override public void onAudioAvailable(String id, byte[] audio) {
                long at = SystemClock.elapsedRealtime() - t0[0];
                synchronized (chunks) {
                    if (chunks.length() > 0) chunks.append(';');
                    chunks.append(at).append(':').append(audio.length);
                }
            }
            @Override public void onDone(String id) { done.countDown(); }
            @Override public void onError(String id) { onError(id, TextToSpeech.ERROR); }
            @Override public void onError(String id, int code) {
                failure[0] = "engine reported error " + code;
                done.countDown();
            }
            @Override public void onStop(String id, boolean interrupted) {
                failure[0] = "stopped";
                done.countDown();
            }
        });

        // The framework's own request keys (TextToSpeech.Engine.KEY_PARAM_*,
        // hidden constants): per-call values override what setVoice set.
        Bundle params = new Bundle();
        String lang = args.getString("lang");
        if (lang != null) {
            params.putString("language", lang);
            params.putString("country", "");
            params.putString("variant", "");
        }

        synchronized (chunks) { t0[0] = SystemClock.elapsedRealtime(); }
        int queued = tts.synthesizeToFile(text, params, wav, "smoke");
        if (queued != TextToSpeech.SUCCESS) throw new IllegalStateException("synthesizeToFile returned " + queued);
        if (!done.await(timeoutS, TimeUnit.SECONDS)) throw new IllegalStateException("no result in " + timeoutS + " s");
        out.putString("synthMs", Long.toString(SystemClock.elapsedRealtime() - t0[0]));
        synchronized (chunks) {
            out.putString("beginMs", Long.toString(beginMs[0]));
            out.putString("chunks", chunks.toString());
        }
        out.putString("file", wav.getAbsolutePath());
        if (failure[0] != null) throw new IllegalStateException(failure[0]);
        describeWav(wav, out);
    }

    /** Sample rate, sample count, duration, RMS and peak (dBFS) of a mono PCM16 WAV. */
    private static void describeWav(File wav, Bundle out) throws Exception {
        byte[] bytes;
        try (RandomAccessFile f = new RandomAccessFile(wav, "r")) {
            bytes = new byte[(int) f.length()];
            f.readFully(bytes);
        }
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (bytes.length < 12 || b.getInt(0) != 0x46464952 /* RIFF */) {
            throw new IllegalStateException("not a WAV (" + bytes.length + " bytes)");
        }
        int rate = 0, bits = 0, channels = 0, dataAt = -1, dataLen = 0;
        int pos = 12;
        while (pos + 8 <= bytes.length) {
            int id = b.getInt(pos), len = b.getInt(pos + 4);
            if (id == 0x20746d66 /* "fmt " */) {
                channels = b.getShort(pos + 10);
                rate = b.getInt(pos + 12);
                bits = b.getShort(pos + 22);
            } else if (id == 0x61746164 /* "data" */) {
                dataAt = pos + 8;
                // A header never patched after a streamed write says -1: take the rest.
                dataLen = len < 0 ? bytes.length - dataAt : Math.min(len, bytes.length - dataAt);
                break;
            }
            pos += 8 + len + (len & 1);
        }
        if (dataAt < 0 || bits != 16 || channels != 1 || rate <= 0) {
            throw new IllegalStateException("unexpected WAV: rate=" + rate + " bits=" + bits + " channels=" + channels);
        }
        int samples = dataLen / 2;
        double sumSq = 0;
        int peak = 0;
        for (int i = 0; i < samples; i++) {
            int s = b.getShort(dataAt + 2 * i);
            sumSq += (double) s * s;
            peak = Math.max(peak, Math.abs(s));
        }
        double rms = samples == 0 ? 0 : Math.sqrt(sumSq / samples);
        out.putString("sampleRate", Integer.toString(rate));
        out.putString("samples", Integer.toString(samples));
        out.putString("durationMs", Long.toString(samples * 1000L / rate));
        out.putString("rmsDb", String.format(java.util.Locale.ROOT, "%.1f", dbfs(rms)));
        out.putString("peakDb", String.format(java.util.Locale.ROOT, "%.1f", dbfs(peak)));
    }

    private static double dbfs(double amplitude) {
        return amplitude <= 0 ? -120.0 : 20 * Math.log10(amplitude / 32768.0);
    }

    private String required(String key) {
        String v = args.getString(key);
        if (v == null || v.isEmpty()) throw new IllegalArgumentException("missing -e " + key);
        return v;
    }

    private static String oneLine(String s) {
        return s.replace('\n', ' ').replace('\r', ' ');
    }
}
