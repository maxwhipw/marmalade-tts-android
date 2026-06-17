package app.marmalade.tts.pro

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class ProGateModule {

    @Binds
    abstract fun bindProGate(impl: NoopProGate): ProGate
}
