package app.marmalade.tts.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Swaps `Dispatchers.Main` for a test dispatcher in JUnit tests.
 *
 * `viewModelScope` defaults to `Dispatchers.Main` under the hood; without
 * this rule, any ViewModel test that launches a coroutine throws
 * `IllegalStateException: Module with the Main dispatcher had failed to
 * initialize` on the JVM.
 *
 * `UnconfinedTestDispatcher` is the default because we want
 * `viewModel.speak()` → state transition observable on the next
 * `runTest` resumption without `advanceUntilIdle()` calls everywhere.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    private val dispatcher: TestDispatcher = UnconfinedTestDispatcher(),
) : TestWatcher() {

    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
