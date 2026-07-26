package app.marmalade.tts.audio

// -----------------------------------------------------------------------------
// Effect vocabulary + presets — a Kotlin mirror of the CLI's sox effects
// (marmalade_tts/effects.py). The CLI shells out to sox; on Android we can't,
// so each sox effect is reimplemented as a streaming DSP block in
// [StreamingEffectChain] (the single engine — see below). The block params map
// 1:1 to the sox effect args so the presets reproduce the CLI's sound.
//
//   sox effect          EffectBlock
//   ----------          -----------
//   reverb N            Reverb(reverberance = N)
//   echo gi go d dk     Echo(gainIn, gainOut, delayMs, decay)
//   overdrive N         Overdrive(gainDb = N)
//   pitch N             Pitch(cents = N)            (preserves duration)
//   tempo F             Tempo(factor = F)           (preserves pitch)
//   sinc lo-hi          Bandpass(lowHz, highHz)
//   vol F               Vol(factor = F)
//   treble N            Treble(db = N)
//   bass N              Bass(db = N)
//   equalizer f 1q g    Mid(freqHz, gainDb)          (peaking EQ, fixed Q=1)
//   lowpass f           Lowpass(freqHz)
//   highpass f          Highpass(freqHz)
//   tremolo s d         Tremolo(speedHz, depth)      (amplitude LFO)
//   flanger …           Flanger(speedHz, depthMs)    (swept comb)
//   chorus …            Chorus(speedHz, depthMs)     (doubled voice)
//   phaser …            Phaser(speedHz, decay)       (swept comb + feedback)
//   compand …           Compressor(thresholdDb, ratio)
//   (none — sox)        Bitcrush(bits, downsample)   Android-only
//   (none — sox)        RingMod(freqHz, mix)         Android-only
//   (none — sox)        Monotone(targetHz)           Android-only (YIN+dyn-shift)
//
// Single DSP engine: all shaping runs through [StreamingEffectChain] (per-chunk,
// state carried across seams). [applyChain] here is a thin whole-buffer adapter
// (process the whole array as one chunk + flush) for the system-TTS / foreground
// services, which synthesize the full clip before their callback anyway.
// -----------------------------------------------------------------------------

/** Legacy preset selector — the alias-editor dropdown bridge, retired in E-G. */
enum class EffectPreset { NONE, CAVE, TELEPHONE }

/**
 * A single composable DSP block. A user effect is an ordered list of these.
 * Pure data — the DSP lives in [StreamingEffectChain], JSON (de)serialization in
 * [EffectBlockJson] — so this stays free of Android/audio deps and is trivially
 * testable. Param names and units mirror the matching sox effect.
 */
sealed interface EffectBlock {
    /**
     * sox `reverb` — algorithmic room reverb (freeverb). [reverberance] is the
     * 0..100 "amount" (room size / tail length); higher = bigger, longer.
     */
    data class Reverb(val reverberance: Float) : EffectBlock

    /**
     * sox `echo gainIn gainOut delay decay` — a single feed-forward delay tap.
     * [delayMs] is the tap time; the echo is attenuated by [gainOut]·[decay].
     */
    data class Echo(
        val gainIn: Float,
        val gainOut: Float,
        val delayMs: Float,
        val decay: Float,
    ) : EffectBlock

    /** sox `overdrive` — waveshaping distortion. [gainDb] pre-gain in dB. */
    data class Overdrive(val gainDb: Float) : EffectBlock

    /**
     * sox `pitch` — shift pitch by [cents] (100 cents = 1 semitone) WITHOUT
     * changing duration. Positive = up.
     */
    data class Pitch(val cents: Float) : EffectBlock

    /**
     * sox `tempo` — change speed by [factor] (>1 faster, <1 slower) WITHOUT
     * changing pitch.
     */
    data class Tempo(val factor: Float) : EffectBlock

    /** sox `sinc lo-hi` — keep only the [lowHz]..[highHz] band. */
    data class Bandpass(val lowHz: Float, val highHz: Float) : EffectBlock

    /** sox `vol` — linear gain. [factor] 2.0 = +6 dB, 0.5 = −6 dB. */
    data class Vol(val factor: Float) : EffectBlock

    /** sox `treble` — high-shelf EQ, [db] boost (+) or cut (−). */
    data class Treble(val db: Float) : EffectBlock

    /** sox `bass` — low-shelf EQ, [db] boost (+) or cut (−). */
    data class Bass(val db: Float) : EffectBlock

    /**
     * sox `equalizer` — peaking (mid-band) EQ centered at [freqHz], [gainDb]
     * boost (+) or cut (−). Fixed Q≈1. Complements Bass (low-shelf) / Treble
     * (high-shelf) with a tunable mid band.
     */
    data class Mid(val freqHz: Float, val gainDb: Float) : EffectBlock

    /** sox `lowpass` — roll off everything above [freqHz]. */
    data class Lowpass(val freqHz: Float) : EffectBlock

    /** sox `highpass` — roll off everything below [freqHz]. */
    data class Highpass(val freqHz: Float) : EffectBlock

    /**
     * sox `tremolo` — amplitude modulation at [speedHz]. [depth] 0..1 is how
     * deep the volume dips (0 = none, 1 = down to silence at the trough).
     */
    data class Tremolo(val speedHz: Float, val depth: Float) : EffectBlock

    /**
     * sox `flanger` — a short comb delay swept by an LFO at [speedHz] over
     * [depthMs] of delay. The classic "jet" sweep. No feedback (sox regen=0).
     */
    data class Flanger(val speedHz: Float, val depthMs: Float) : EffectBlock

    /**
     * sox `chorus` — a longer modulated delay (≈25 ms base) swept at [speedHz]
     * over [depthMs], mixed with the dry signal for a doubled-voice thickening.
     */
    data class Chorus(val speedHz: Float, val depthMs: Float) : EffectBlock

    /**
     * sox `phaser` — a swept comb at [speedHz] with [decay] feedback (0..0.9),
     * producing moving notches. Higher decay = more resonant sweep.
     */
    data class Phaser(val speedHz: Float, val decay: Float) : EffectBlock

    /**
     * A downward compressor: above [thresholdDb] (dBFS, negative) the level is
     * reduced by [ratio]:1. A simplified feed-forward envelope compressor with
     * fixed 5 ms attack / 100 ms release — not a full sox `compand` port (the
     * CLI maps this to a two-segment compand with the same threshold/ratio).
     */
    data class Compressor(val thresholdDb: Float, val ratio: Float) : EffectBlock

    /**
     * Lo-fi crusher: quantize to [bits] bit-depth (1..16) and sample-and-hold
     * every [downsample] samples (1 = off) to drop the effective sample rate.
     * The 8-bit / retro / glitch sound. Android-only — sox has no bitcrush.
     */
    data class Bitcrush(val bits: Float, val downsample: Float) : EffectBlock

    /**
     * Ring modulator: multiply by a [freqHz] carrier sine, blended with the dry
     * signal by [mix] (0 = dry, 1 = fully ringed). The classic Dalek/cyborg
     * timbre. Android-only — sox has no ring modulator.
     */
    data class RingMod(val freqHz: Float, val mix: Float) : EffectBlock

    /**
     * Pitch flattener — detect the input's pitch (YIN, ~1024-sample windows)
     * and dynamically shift it toward [targetHz]. Gives the deadpan-monotone
     * "auto-tune flat" / GLaDOS phrasing that a static [Pitch] block can't,
     * because Pitch blindly adds an offset whereas Monotone follows the input
     * and corrects it. Android-only — no sox equivalent. Detector has ~43 ms
     * of warmup latency before the first lock; output glides smoothly.
     */
    data class Monotone(val targetHz: Float) : EffectBlock
}

/**
 * Preset block lists (the exact CLI recipes) + the whole-buffer [applyChain]
 * adapter. The DSP itself lives in [StreamingEffectChain].
 */
object EffectChain {

    // -- CLI preset recipes (marmalade_tts/effects.py BUILTIN_PRESETS) ---------

    val CAVE_BLOCKS: List<EffectBlock> = listOf(
        EffectBlock.Reverb(reverberance = 80f),
        EffectBlock.Echo(gainIn = 0.6f, gainOut = 0.6f, delayMs = 120f, decay = 0.3f),
    )
    val CHIPMUNK_BLOCKS: List<EffectBlock> = listOf(
        EffectBlock.Pitch(cents = 900f),
    )
    val DEEP_BLOCKS: List<EffectBlock> = listOf(
        EffectBlock.Pitch(cents = -400f),
        EffectBlock.Bass(db = 6f),
    )
    val TELEPHONE_BLOCKS: List<EffectBlock> = listOf(
        EffectBlock.Bandpass(lowHz = 300f, highHz = 3400f),
        EffectBlock.Overdrive(gainDb = 5f),
        EffectBlock.Vol(factor = 1.3f),
    )
    val STADIUM_BLOCKS: List<EffectBlock> = listOf(
        EffectBlock.Reverb(reverberance = 90f),
        EffectBlock.Echo(gainIn = 0.8f, gainOut = 0.7f, delayMs = 80f, decay = 0.25f),
    )
    val MEGAPHONE_BLOCKS: List<EffectBlock> = listOf(
        EffectBlock.Bandpass(lowHz = 500f, highHz = 4000f),
        EffectBlock.Overdrive(gainDb = 30f),
        EffectBlock.Vol(factor = 1.1f),
    )

    // -- Curated voice stackups (E-K) ------------------------------------------
    // Professional vocal-chain + character recipes (also mirrored in the CLI's
    // BUILTIN_PRESETS). Ordering follows the pro convention: filters/EQ →
    // compression → drive → modulation → reverb last.

    // Clean broadcast-DJ polish: low cut, mud cut, compression, presence + air.
    val BROADCASTER_BLOCKS: List<EffectBlock> = listOf(
        EffectBlock.Highpass(freqHz = 90f),
        EffectBlock.Mid(freqHz = 300f, gainDb = -3f),
        EffectBlock.Compressor(thresholdDb = -18f, ratio = 3f),
        EffectBlock.Mid(freqHz = 3000f, gainDb = 3f),
        EffectBlock.Treble(db = 3f),
        EffectBlock.Bass(db = 2f),
    )
    // Warm, intimate podcast tone.
    val PODCAST_BLOCKS: List<EffectBlock> = listOf(
        EffectBlock.Highpass(freqHz = 80f),
        EffectBlock.Bass(db = 3f),
        EffectBlock.Compressor(thresholdDb = -20f, ratio = 2.5f),
        EffectBlock.Mid(freqHz = 250f, gainDb = -2f),
        EffectBlock.Treble(db = 2f),
    )
    // Deep cinematic trailer voice: subtle pitch-down, squashed dynamics,
    // controlled grandeur reverb. Signed off on device 2026-07-26 — this recipe
    // is where Max wants it; don't retune it without asking.
    val TRAILER_BLOCKS: List<EffectBlock> = listOf(
        EffectBlock.Pitch(cents = -250f),
        EffectBlock.Bass(db = 5f),
        EffectBlock.Compressor(thresholdDb = -18f, ratio = 4f),
        EffectBlock.Mid(freqHz = 2500f, gainDb = 2f),
        EffectBlock.Reverb(reverberance = 22f),
    )
    // Even, controlled narration with a hint of room.
    val AUDIOBOOK_BLOCKS: List<EffectBlock> = listOf(
        EffectBlock.Highpass(freqHz = 85f),
        EffectBlock.Compressor(thresholdDb = -22f, ratio = 3f),
        EffectBlock.Mid(freqHz = 2500f, gainDb = 2f),
        EffectBlock.Reverb(reverberance = 10f),
    )
    // Handheld two-way radio: tight band, hard drive, digital-radio grit
    // (1× crush = bit-depth quantize only), hard squash, makeup gain.
    val WALKIE_TALKIE_BLOCKS: List<EffectBlock> = listOf(
        EffectBlock.Highpass(freqHz = 400f),
        EffectBlock.Lowpass(freqHz = 5000f),
        EffectBlock.Overdrive(gainDb = 25f),
        EffectBlock.Bitcrush(bits = 11f, downsample = 1f),
        EffectBlock.Compressor(thresholdDb = -32f, ratio = 6f),
        EffectBlock.Vol(factor = 1.5f),
    )
    // Old AM radio — modelled on the canonical Audacity "AM Radio" preset
    // (HP ~400, LP ~4k, +12 dB at 1 kHz). The +12 dB mid peak at 1 kHz is the
    // defining feature: it's the small-cone-speaker "honk" the ear reads as
    // vintage AM. Add soft tube overdrive, a light leveling compressor (slow
    // release like an old broadcast limiter), a subtle AM tremolo wobble, and
    // a small cabinet reverb. NO chorus — references confirm wow-and-flutter
    // is a tape/turntable cue, not a radio cue.
    val VINTAGE_RADIO_BLOCKS: List<EffectBlock> = listOf(
        EffectBlock.Highpass(freqHz = 400f),
        EffectBlock.Lowpass(freqHz = 4000f),
        EffectBlock.Mid(freqHz = 1000f, gainDb = 12f),
        EffectBlock.Overdrive(gainDb = 8f),
        EffectBlock.Compressor(thresholdDb = -26f, ratio = 3f),
        EffectBlock.Tremolo(speedHz = 4f, depth = 0.15f),
        EffectBlock.Reverb(reverberance = 8f),
        EffectBlock.Vol(factor = 1.3f),
    )
    // PA / intercom: midrange horn, heavy drive, room slap.
    val INTERCOM_BLOCKS: List<EffectBlock> = listOf(
        EffectBlock.Bandpass(lowHz = 450f, highHz = 2500f),
        EffectBlock.Overdrive(gainDb = 18f),
        EffectBlock.Mid(freqHz = 1500f, gainDb = 4f),
        EffectBlock.Reverb(reverberance = 30f),
        EffectBlock.Vol(factor = 1.2f),
    )
    // Submerged: dark low-pass, chorus shimmer, slight pitch + wobble. The
    // low-pass + tremolo eat level, so a Vol makeup keeps it audible.
    val UNDERWATER_BLOCKS: List<EffectBlock> = listOf(
        EffectBlock.Lowpass(freqHz = 700f),
        EffectBlock.Chorus(speedHz = 0.3f, depthMs = 4f),
        EffectBlock.Pitch(cents = -80f),
        EffectBlock.Tremolo(speedHz = 1.5f, depth = 0.2f),
        EffectBlock.Vol(factor = 1.35f),
    )
    // Synthetic and not-quite-human: pitch up, phaser + flanger sweep, big
    // space. (Was "Alien" — same chain, renamed to AI, which is what it reads
    // as. The old Monotone-based AI chain was retired in its favour.)
    val AI_BLOCKS: List<EffectBlock> = listOf(
        EffectBlock.Pitch(cents = 150f),
        EffectBlock.Phaser(speedHz = 0.4f, decay = 0.5f),
        EffectBlock.Flanger(speedHz = 0.3f, depthMs = 3f),
        EffectBlock.Reverb(reverberance = 30f),
    )
    // Ethereal haunt: thin low end, pitch shimmer, long reverb, tremolo flutter.
    val ETHEREAL_BLOCKS: List<EffectBlock> = listOf(
        EffectBlock.Highpass(freqHz = 250f),
        EffectBlock.Pitch(cents = 120f),
        EffectBlock.Reverb(reverberance = 70f),
        EffectBlock.Tremolo(speedHz = 3f, depth = 0.25f),
        EffectBlock.Treble(db = 3f),
    )
    // Dragon: a big cavern first, then the whole wet signal dropped most of an
    // octave and dragged to 0.85× — reverb before the pitch/tempo is what makes
    // the tail sound like a huge slow throat rather than a room the voice sits
    // in. Mid scoop keeps the growl from honking; light grit and a slow chorus
    // add bulk.
    val DRAGON_BLOCKS: List<EffectBlock> = listOf(
        EffectBlock.Reverb(reverberance = 45f),
        EffectBlock.Pitch(cents = -649f),
        EffectBlock.Bass(db = 0f),
        EffectBlock.Mid(freqHz = 1058f, gainDb = -2f),
        EffectBlock.Overdrive(gainDb = 7f),
        EffectBlock.Chorus(speedHz = 0.25f, depthMs = 2f),
        EffectBlock.Tempo(factor = 0.85f),
    )

    // -- Stackups using the Android-only blocks (E-L) — no CLI equivalent -------

    // Classic Dalek/cyborg: ring mod through a telephone band + light grit.
    val CYBORG_BLOCKS: List<EffectBlock> = listOf(
        EffectBlock.RingMod(freqHz = 60f, mix = 0.7f),
        EffectBlock.Bandpass(lowHz = 300f, highHz = 3400f),
        EffectBlock.Overdrive(gainDb = 6f),
    )
    // 8-bit retro game voice: band-limit first so the 8× sample-and-hold folds
    // less garbage back down, then 7-bit quantize for the console grain. No Vol
    // makeup — the crush's own gain compensation already lands it loud enough.
    val EIGHT_BIT_BLOCKS: List<EffectBlock> = listOf(
        EffectBlock.Lowpass(freqHz = 3446f),
        EffectBlock.Bitcrush(bits = 7f, downsample = 8f),
    )
    // Glitchy lo-fi transmission: mild crush + ring shimmer through a radio band,
    // with a Vol makeup for the band-limiting loss.
    val GLITCH_BLOCKS: List<EffectBlock> = listOf(
        EffectBlock.Bitcrush(bits = 8f, downsample = 3f),
        EffectBlock.RingMod(freqHz = 120f, mix = 0.4f),
        EffectBlock.Bandpass(lowHz = 400f, highHz = 3000f),
        EffectBlock.Vol(factor = 1.35f),
    )

    /**
     * Block list for a legacy [EffectPreset] (the dropdown bridge). NONE = dry.
     */
    fun blocksForPreset(preset: EffectPreset): List<EffectBlock> = when (preset) {
        EffectPreset.NONE -> emptyList()
        EffectPreset.CAVE -> CAVE_BLOCKS
        EffectPreset.TELEPHONE -> TELEPHONE_BLOCKS
    }

    /** Legacy preset entry point — bridges to [applyChain]. */
    fun apply(pcm: ShortArray, sampleRate: Int, preset: EffectPreset): ShortArray =
        applyChain(pcm, sampleRate, blocksForPreset(preset))

    /**
     * Whole-buffer adapter over [StreamingEffectChain]: feed the entire [pcm] as
     * one chunk, then drain the tail (reverb/echo ring-out). An empty chain
     * returns the input unchanged. Used by the system-TTS / foreground services,
     * which already have the full clip; the in-app path streams chunk-by-chunk.
     */
    fun applyChain(pcm: ShortArray, sampleRate: Int, blocks: List<EffectBlock>): ShortArray {
        if (blocks.isEmpty()) return pcm
        val chain = StreamingEffectChain(blocks, sampleRate)
        val body = chain.process(pcm)
        val tail = chain.flush()
        return if (tail.isEmpty()) body else body + tail
    }
}
