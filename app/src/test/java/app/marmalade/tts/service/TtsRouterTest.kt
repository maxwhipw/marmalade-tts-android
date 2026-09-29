package app.marmalade.tts.service

import app.marmalade.tts.data.db.AppAliasMapping
import app.marmalade.tts.data.db.AppAliasMappingDao
import app.marmalade.tts.data.db.VoiceAlias
import app.marmalade.tts.data.db.VoiceAliasDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [TtsRouter.fallbackVoiceIdFor] — the offline voice a cloud alias speaks
 * with when its own voice can't be reached.
 *
 * The alias editor stored the target alias's NAME in `fallbackAliasId` from
 * the db v10 UUID re-key until v1.1.0, so every fallback configured in that
 * window dangled against an id-only lookup and the protection silently never
 * fired. Both shapes have to resolve.
 *
 * Also [TtsRouter.resolveAlias]'s caller-named alias, which the reader uses to
 * read an article in a voice that speaks its language.
 */
class TtsRouterTest {

    private val settings = FakePreprocessSettings()

    private val kitten = alias(id = "uuid-kitten", name = "Kitty", voiceId = "kitten-direct-v0_8:Bella")

    private fun alias(id: String, name: String, voiceId: String, fallback: String? = null) =
        VoiceAlias(
            id = id,
            name = name,
            engine = voiceId.substringBefore(':'),
            voiceId = voiceId,
            speed = 1f,
            effectPreset = "NONE",
            createdAt = 0L,
            fallbackAliasId = fallback,
        )

    private fun routerWith(vararg aliases: VoiceAlias) = TtsRouter(
        mappingDao = NoMappings,
        aliasDao = MapAliasDao(aliases.toList()),
        settings = settings,
    )

    private val cloudVoice = "cloud-api-v1:venice:tts-kokoro:af_sky"

    @Test
    fun `fallback stored as an alias id resolves`() = runBlocking {
        val cloud = alias("uuid-cloud", "Sky", cloudVoice, fallback = kitten.id)
        assertEquals(kitten.voiceId, routerWith(cloud, kitten).fallbackVoiceIdFor(cloud))
    }

    @Test
    fun `fallback stored as an alias name by the pre-v1_1 editor still resolves`() = runBlocking {
        val cloud = alias("uuid-cloud", "Sky", cloudVoice, fallback = kitten.name)
        assertEquals(kitten.voiceId, routerWith(cloud, kitten).fallbackVoiceIdFor(cloud))
    }

    @Test
    fun `an id match wins over an alias whose name happens to equal it`() = runBlocking {
        // Contrived, but pins the precedence: ids are what the editor writes now.
        val impostor = alias("uuid-impostor", name = kitten.id, voiceId = "kokoro-direct-v1_0:af_bella")
        val cloud = alias("uuid-cloud", "Sky", cloudVoice, fallback = kitten.id)
        assertEquals(kitten.voiceId, routerWith(cloud, kitten, impostor).fallbackVoiceIdFor(cloud))
    }

    @Test
    fun `a dangling fallback reference resolves to no fallback`() = runBlocking {
        val cloud = alias("uuid-cloud", "Sky", cloudVoice, fallback = "Deleted Alias")
        assertNull(routerWith(cloud, kitten).fallbackVoiceIdFor(cloud))
    }

    @Test
    fun `no fallback configured resolves to no fallback`() = runBlocking {
        val cloud = alias("uuid-cloud", "Sky", cloudVoice)
        assertNull(routerWith(cloud, kitten).fallbackVoiceIdFor(cloud))
    }

    // -- An alias named by the caller (the reader's article-language switch) ---

    @Test
    fun `a named alias wins over the primary`() = runBlocking {
        val zh = alias("uuid-zh", "Xiaoni", "kokoro-direct-v1_0:zf_xiaoni")
        val router = routerWith(kitten, zh)
        settings.setPrimaryAliasId(kitten.id)

        assertEquals(zh, router.resolveAlias(callerPackage = null, aliasId = zh.id))
        assertEquals(kitten, router.resolveAlias(callerPackage = null))
    }

    /** Deleted mid-article: read on in the primary rather than fail. */
    @Test
    fun `a named alias that no longer exists falls back to the primary`() = runBlocking {
        val router = routerWith(kitten)
        settings.setPrimaryAliasId(kitten.id)

        assertEquals(kitten, router.resolveAlias(callerPackage = null, aliasId = "uuid-deleted"))
    }

    private class MapAliasDao(private val rows: List<VoiceAlias>) : VoiceAliasDao {
        override fun getAll(): Flow<List<VoiceAlias>> = flowOf(rows)
        override suspend fun findById(id: String) = rows.firstOrNull { it.id == id }
        override suspend fun findByName(name: String) = rows.firstOrNull { it.name == name }
        override suspend fun upsert(alias: VoiceAlias) = Unit
        override suspend fun delete(id: String) = Unit
        override suspend fun repointEngine(fromEngine: String, toEngine: String) = Unit
    }

    private object NoMappings : AppAliasMappingDao {
        override fun getAll(): Flow<List<AppAliasMapping>> = flowOf(emptyList())
        override suspend fun findByPackage(packageName: String) = null
        override suspend fun upsert(mapping: AppAliasMapping) = Unit
        override suspend fun delete(packageName: String) = Unit
        override suspend fun releaseAppsRoutedTo(aliasName: String) = Unit
    }
}
