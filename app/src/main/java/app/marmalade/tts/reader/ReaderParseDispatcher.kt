package app.marmalade.tts.reader

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * The dispatcher [ArticleExtractor] runs on. Parsing a page with jsoup and
 * Readability4J is pure CPU work that can take a noticeable slice of a second
 * on a long article, so it must stay off the main thread. A qualifier (rather
 * than a hardcoded `Dispatchers.Default` in the ViewModel) so the ViewModel
 * tests can hand in their test dispatcher and stay deterministic.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ReaderParseDispatcher

@Module
@InstallIn(SingletonComponent::class)
object ReaderParseDispatcherModule {

    @Provides
    @ReaderParseDispatcher
    fun provideReaderParseDispatcher(): CoroutineDispatcher = Dispatchers.Default
}
