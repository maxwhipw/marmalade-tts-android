package app.marmalade.tts.engine.pocket

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

// -----------------------------------------------------------------------------
// On-disk format for a user-cloned voice's mimi_encoder embedding.
//
// File path: `engines/pocket-tts-en-v2026_04/cloned_voices/<id>.bin`
// Naming:    `<id>` is the local part of the voice ID (e.g. `cloned-abc123`).
//
// Wire layout (little-endian throughout):
//   Offset  Size  Field
//   0       4     Magic "PVS1" (0x50 0x56 0x53 0x31)
//   4       4     displayNameLen (int)
//   8       N     displayName UTF-8 bytes
//   8+N     8     createdAtMillis (long, epoch ms)
//   16+N    4     numFrames (int)
//   20+N    M     embedding float32 LE bytes, M = numFrames * 1024 * 4
//
// The ID itself is encoded in the filename, not the file — keeps the
// invariant "filename ↔ ID" explicit and avoids the desync risk that
// would come from storing the ID inside as well.
//
// Why a separate format from `voice_cache/<name>.emb` (the built-in
// voice cache, which is just `int numFrames + floats`):
//   - Cloned voices carry user-visible metadata (display name + creation
//     timestamp) that the picker UI will eventually surface. Built-ins
//     don't — their names come from `PocketVoiceCatalog`, baked into the
//     binary.
//   - Versioning. The magic + format-version-in-magic ("PVS1") leaves
//     room for breaking changes without a separate version byte. If we
//     bump to "PVS2" the loader can recognise and reject (or migrate)
//     old files explicitly.
//
// Why not match NekoSpeak's PKVS format byte-for-byte: NekoSpeak's
// format isn't part of any Kyutai-blessed spec, so there's no interop
// gain. Our format is simpler and we own it.
// -----------------------------------------------------------------------------

private val MAGIC = byteArrayOf(
    'P'.code.toByte(),
    'V'.code.toByte(),
    'S'.code.toByte(),
    '1'.code.toByte(),
)

/** Bounds — protect against corrupted files driving huge allocations. */
private const val MAX_DISPLAY_NAME_BYTES = 256
private const val MAX_NUM_FRAMES = 50_000 // ~67 min @ 12.5 fps; far above any real clone

/**
 * Metadata + embedding for one cloned voice. The embedding is the
 * `mimi_encoder` output (shape `[numFrames, 1024]`) flattened
 * row-major.
 */
data class PocketClonedVoice(
    /** Voice's local ID — the part after the `:` in the full voice ID. */
    val id: String,
    val displayName: String,
    val createdAtMillis: Long,
    /** Number of mimi latent frames. `embedding.size == numFrames * 1024`. */
    val numFrames: Int,
    val embedding: FloatArray,
)

/**
 * Lightweight view of a cloned voice — used for listings where reading
 * the whole embedding would be wasteful. Read by [PocketClonedVoiceStore.list].
 */
data class PocketClonedVoiceSummary(
    val id: String,
    val displayName: String,
    val createdAtMillis: Long,
    val numFrames: Int,
)

object PocketClonedVoiceStore {

    /**
     * Persist [voice] to [dir] / `<voice.id>.bin`. Creates [dir] if it
     * doesn't exist. Overwrites any existing file with the same ID.
     */
    fun write(dir: File, voice: PocketClonedVoice) {
        require(voice.displayName.isNotEmpty()) { "Cloned voice display name must not be empty" }
        require(voice.numFrames > 0) { "Cloned voice must have > 0 frames" }
        require(voice.embedding.size == voice.numFrames * EMBED_DIM) {
            "Embedding size ${voice.embedding.size} doesn't match ${voice.numFrames} × $EMBED_DIM"
        }
        if (!dir.exists()) dir.mkdirs()
        val nameBytes = voice.displayName.toByteArray(Charsets.UTF_8)
        require(nameBytes.size <= MAX_DISPLAY_NAME_BYTES) {
            "Display name exceeds $MAX_DISPLAY_NAME_BYTES bytes"
        }
        val totalBytes = 4 + 4 + nameBytes.size + 8 + 4 + voice.embedding.size * 4
        val buf = ByteBuffer.allocate(totalBytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(MAGIC)
        buf.putInt(nameBytes.size)
        buf.put(nameBytes)
        buf.putLong(voice.createdAtMillis)
        buf.putInt(voice.numFrames)
        buf.asFloatBuffer().put(voice.embedding)
        // asFloatBuffer doesn't advance the parent position; manually
        // skip past the float region so the .array() call (or anyone
        // checking position later) sees consistent state.
        buf.position(buf.position() + voice.embedding.size * 4)
        File(dir, "${voice.id}.bin").writeBytes(buf.array())
    }

    /**
     * Load a cloned voice from disk. Throws if the file is missing,
     * truncated, or has an unrecognised magic.
     */
    fun read(file: File): PocketClonedVoice {
        val bytes = file.readBytes()
        require(bytes.size >= MIN_HEADER_BYTES) { "$file is too short to be a valid cloned voice" }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(4).also { buf.get(it) }
        require(magic.contentEquals(MAGIC)) {
            "$file is not a PVS1 cloned voice (magic=${magic.joinToString { "%02x".format(it) }})"
        }
        val nameLen = buf.int
        require(nameLen in 1..MAX_DISPLAY_NAME_BYTES) { "$file: invalid display-name length $nameLen" }
        val nameBytes = ByteArray(nameLen).also { buf.get(it) }
        val displayName = String(nameBytes, Charsets.UTF_8)
        val createdAt = buf.long
        val numFrames = buf.int
        require(numFrames in 1..MAX_NUM_FRAMES) { "$file: invalid numFrames $numFrames" }

        val expectedEmbedSize = numFrames * EMBED_DIM
        val remaining = bytes.size - buf.position()
        require(remaining == expectedEmbedSize * 4) {
            "$file: embedding length mismatch (header says $expectedEmbedSize floats, " +
                "file has ${remaining / 4})"
        }
        val embedding = FloatArray(expectedEmbedSize)
        buf.asFloatBuffer().get(embedding)
        return PocketClonedVoice(
            id = file.nameWithoutExtension,
            displayName = displayName,
            createdAtMillis = createdAt,
            numFrames = numFrames,
            embedding = embedding,
        )
    }

    /**
     * Read just the header of every `.bin` file in [dir]. Used by UI
     * listings — avoids the cost of loading every embedding into
     * memory at startup or whenever the picker is shown.
     *
     * Returns an empty list if [dir] doesn't exist or contains nothing.
     */
    fun list(dir: File): List<PocketClonedVoiceSummary> {
        if (!dir.isDirectory) return emptyList()
        val out = ArrayList<PocketClonedVoiceSummary>()
        for (f in dir.listFiles { _, name -> name.endsWith(".bin") } ?: emptyArray()) {
            try {
                out.add(readSummary(f))
            } catch (_: Throwable) {
                // Skip corrupt files rather than blocking the whole listing.
                // The bad file will surface to the user the next time they
                // try to use it directly.
            }
        }
        return out
    }

    /** Header-only read for [list]. Same wire format, just doesn't slurp the floats. */
    private fun readSummary(file: File): PocketClonedVoiceSummary {
        // Read just enough bytes to cover the header — keeps listings
        // fast even with many large embeddings on disk.
        val bytes = ByteArray(MIN_HEADER_BYTES + MAX_DISPLAY_NAME_BYTES)
        val read = file.inputStream().use { it.read(bytes) }
        require(read >= MIN_HEADER_BYTES) { "$file too short for header" }
        val buf = ByteBuffer.wrap(bytes, 0, read).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(4).also { buf.get(it) }
        require(magic.contentEquals(MAGIC)) { "$file is not a PVS1 cloned voice" }
        val nameLen = buf.int
        require(nameLen in 1..MAX_DISPLAY_NAME_BYTES) { "$file: invalid display-name length $nameLen" }
        require(buf.remaining() >= nameLen + 8 + 4) { "$file truncated" }
        val nameBytes = ByteArray(nameLen).also { buf.get(it) }
        val displayName = String(nameBytes, Charsets.UTF_8)
        val createdAt = buf.long
        val numFrames = buf.int
        return PocketClonedVoiceSummary(
            id = file.nameWithoutExtension,
            displayName = displayName,
            createdAtMillis = createdAt,
            numFrames = numFrames,
        )
    }

    fun delete(dir: File, id: String): Boolean {
        return File(dir, "$id.bin").delete()
    }

    private const val EMBED_DIM = 1024

    /** Smallest possible valid file: magic + nameLen(=1) + 1 char + createdAt + numFrames. */
    private const val MIN_HEADER_BYTES = 4 + 4 + 1 + 8 + 4
}
