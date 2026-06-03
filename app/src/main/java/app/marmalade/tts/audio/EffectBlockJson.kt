package app.marmalade.tts.audio

import org.json.JSONArray
import org.json.JSONObject

// -----------------------------------------------------------------------------
// Wire format for effect chains. A user effect is stored as one JSON string
// (the `effect.blocksJson` column): a JSON array of block objects, each tagged
// with a "type" discriminator plus its params (mirroring the sox effect args).
// Plain org.json rather than kotlinx.serialization — matches the project's
// existing precedent (PocketBundle).
//
//   [ {"type":"reverb","reverberance":80.0},
//     {"type":"echo","gainIn":0.6,"gainOut":0.6,"delayMs":120.0,"decay":0.3} ]
//
// decode() skips unknown block types rather than throwing, so a chain saved by
// a newer build that adds a block type still loads (minus the unknown block) on
// an older one.
// -----------------------------------------------------------------------------

object EffectBlockJson {

    fun encode(blocks: List<EffectBlock>): String {
        val arr = JSONArray()
        for (block in blocks) arr.put(encodeBlock(block))
        return arr.toString()
    }

    fun decode(json: String): List<EffectBlock> {
        val arr = JSONArray(json)
        val out = ArrayList<EffectBlock>(arr.length())
        for (i in 0 until arr.length()) {
            decodeBlock(arr.getJSONObject(i))?.let(out::add)
        }
        return out
    }

    private fun encodeBlock(block: EffectBlock): JSONObject = when (block) {
        is EffectBlock.Reverb -> JSONObject()
            .put("type", TYPE_REVERB)
            .put("reverberance", block.reverberance.toDouble())
        is EffectBlock.Echo -> JSONObject()
            .put("type", TYPE_ECHO)
            .put("gainIn", block.gainIn.toDouble())
            .put("gainOut", block.gainOut.toDouble())
            .put("delayMs", block.delayMs.toDouble())
            .put("decay", block.decay.toDouble())
        is EffectBlock.Overdrive -> JSONObject()
            .put("type", TYPE_OVERDRIVE)
            .put("gainDb", block.gainDb.toDouble())
        is EffectBlock.Pitch -> JSONObject()
            .put("type", TYPE_PITCH)
            .put("cents", block.cents.toDouble())
        is EffectBlock.Tempo -> JSONObject()
            .put("type", TYPE_TEMPO)
            .put("factor", block.factor.toDouble())
        is EffectBlock.Bandpass -> JSONObject()
            .put("type", TYPE_BANDPASS)
            .put("lowHz", block.lowHz.toDouble())
            .put("highHz", block.highHz.toDouble())
        is EffectBlock.Vol -> JSONObject()
            .put("type", TYPE_VOL)
            .put("factor", block.factor.toDouble())
        is EffectBlock.Treble -> JSONObject()
            .put("type", TYPE_TREBLE)
            .put("db", block.db.toDouble())
        is EffectBlock.Bass -> JSONObject()
            .put("type", TYPE_BASS)
            .put("db", block.db.toDouble())
        is EffectBlock.Mid -> JSONObject()
            .put("type", TYPE_MID)
            .put("freqHz", block.freqHz.toDouble())
            .put("gainDb", block.gainDb.toDouble())
        is EffectBlock.Lowpass -> JSONObject()
            .put("type", TYPE_LOWPASS)
            .put("freqHz", block.freqHz.toDouble())
        is EffectBlock.Highpass -> JSONObject()
            .put("type", TYPE_HIGHPASS)
            .put("freqHz", block.freqHz.toDouble())
        is EffectBlock.Tremolo -> JSONObject()
            .put("type", TYPE_TREMOLO)
            .put("speedHz", block.speedHz.toDouble())
            .put("depth", block.depth.toDouble())
        is EffectBlock.Flanger -> JSONObject()
            .put("type", TYPE_FLANGER)
            .put("speedHz", block.speedHz.toDouble())
            .put("depthMs", block.depthMs.toDouble())
        is EffectBlock.Chorus -> JSONObject()
            .put("type", TYPE_CHORUS)
            .put("speedHz", block.speedHz.toDouble())
            .put("depthMs", block.depthMs.toDouble())
        is EffectBlock.Phaser -> JSONObject()
            .put("type", TYPE_PHASER)
            .put("speedHz", block.speedHz.toDouble())
            .put("decay", block.decay.toDouble())
        is EffectBlock.Compressor -> JSONObject()
            .put("type", TYPE_COMPRESSOR)
            .put("thresholdDb", block.thresholdDb.toDouble())
            .put("ratio", block.ratio.toDouble())
        is EffectBlock.Bitcrush -> JSONObject()
            .put("type", TYPE_BITCRUSH)
            .put("bits", block.bits.toDouble())
            .put("downsample", block.downsample.toDouble())
        is EffectBlock.RingMod -> JSONObject()
            .put("type", TYPE_RINGMOD)
            .put("freqHz", block.freqHz.toDouble())
            .put("mix", block.mix.toDouble())
        is EffectBlock.Monotone -> JSONObject()
            .put("type", TYPE_MONOTONE)
            .put("targetHz", block.targetHz.toDouble())
    }

    private fun decodeBlock(obj: JSONObject): EffectBlock? = when (obj.optString("type")) {
        TYPE_REVERB -> EffectBlock.Reverb(obj.getDouble("reverberance").toFloat())
        TYPE_ECHO -> EffectBlock.Echo(
            gainIn = obj.getDouble("gainIn").toFloat(),
            gainOut = obj.getDouble("gainOut").toFloat(),
            delayMs = obj.getDouble("delayMs").toFloat(),
            decay = obj.getDouble("decay").toFloat(),
        )
        TYPE_OVERDRIVE -> EffectBlock.Overdrive(obj.getDouble("gainDb").toFloat())
        TYPE_PITCH -> EffectBlock.Pitch(obj.getDouble("cents").toFloat())
        TYPE_TEMPO -> EffectBlock.Tempo(obj.getDouble("factor").toFloat())
        TYPE_BANDPASS -> EffectBlock.Bandpass(
            lowHz = obj.getDouble("lowHz").toFloat(),
            highHz = obj.getDouble("highHz").toFloat(),
        )
        TYPE_VOL -> EffectBlock.Vol(obj.getDouble("factor").toFloat())
        TYPE_TREBLE -> EffectBlock.Treble(obj.getDouble("db").toFloat())
        TYPE_BASS -> EffectBlock.Bass(obj.getDouble("db").toFloat())
        TYPE_MID -> EffectBlock.Mid(
            freqHz = obj.getDouble("freqHz").toFloat(),
            gainDb = obj.getDouble("gainDb").toFloat(),
        )
        TYPE_LOWPASS -> EffectBlock.Lowpass(obj.getDouble("freqHz").toFloat())
        TYPE_HIGHPASS -> EffectBlock.Highpass(obj.getDouble("freqHz").toFloat())
        TYPE_TREMOLO -> EffectBlock.Tremolo(
            speedHz = obj.getDouble("speedHz").toFloat(),
            depth = obj.getDouble("depth").toFloat(),
        )
        TYPE_FLANGER -> EffectBlock.Flanger(
            speedHz = obj.getDouble("speedHz").toFloat(),
            depthMs = obj.getDouble("depthMs").toFloat(),
        )
        TYPE_CHORUS -> EffectBlock.Chorus(
            speedHz = obj.getDouble("speedHz").toFloat(),
            depthMs = obj.getDouble("depthMs").toFloat(),
        )
        TYPE_PHASER -> EffectBlock.Phaser(
            speedHz = obj.getDouble("speedHz").toFloat(),
            decay = obj.getDouble("decay").toFloat(),
        )
        TYPE_COMPRESSOR -> EffectBlock.Compressor(
            thresholdDb = obj.getDouble("thresholdDb").toFloat(),
            ratio = obj.getDouble("ratio").toFloat(),
        )
        TYPE_BITCRUSH -> EffectBlock.Bitcrush(
            bits = obj.getDouble("bits").toFloat(),
            downsample = obj.getDouble("downsample").toFloat(),
        )
        TYPE_RINGMOD -> EffectBlock.RingMod(
            freqHz = obj.getDouble("freqHz").toFloat(),
            mix = obj.getDouble("mix").toFloat(),
        )
        TYPE_MONOTONE -> EffectBlock.Monotone(
            targetHz = obj.getDouble("targetHz").toFloat(),
        )
        else -> null // unknown block type — skip for forward compatibility
    }

    private const val TYPE_REVERB = "reverb"
    private const val TYPE_ECHO = "echo"
    private const val TYPE_OVERDRIVE = "overdrive"
    private const val TYPE_PITCH = "pitch"
    private const val TYPE_TEMPO = "tempo"
    private const val TYPE_BANDPASS = "bandpass"
    private const val TYPE_VOL = "vol"
    private const val TYPE_TREBLE = "treble"
    private const val TYPE_BASS = "bass"
    private const val TYPE_MID = "mid"
    private const val TYPE_LOWPASS = "lowpass"
    private const val TYPE_HIGHPASS = "highpass"
    private const val TYPE_TREMOLO = "tremolo"
    private const val TYPE_FLANGER = "flanger"
    private const val TYPE_CHORUS = "chorus"
    private const val TYPE_PHASER = "phaser"
    private const val TYPE_COMPRESSOR = "compressor"
    private const val TYPE_BITCRUSH = "bitcrush"
    private const val TYPE_RINGMOD = "ringmod"
    private const val TYPE_MONOTONE = "monotone"
}
