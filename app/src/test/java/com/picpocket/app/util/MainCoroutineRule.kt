package com.picpocket.app.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@ExperimentalCoroutinesApi
class MainCoroutineRule(
    val dispatcher: TestDispatcher = StandardTestDispatcher(),
) : TestWatcher() {

    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        // Drain any Main work a ViewModel leaked out of its test (viewModelScope
        // coroutines are not cancelled by runTest). If a pending dispatch is
        // still queued when the next test calls setMain(), it fires into the
        // old dispatcher and trips "Dispatchers.Main is used concurrently with
        // setting it". Running it here, while Main is still valid, prevents the
        // leak from contaminating the next test.
        try {
            dispatcher.scheduler.advanceUntilIdle()
        } catch (_: Throwable) {
            // best-effort drain: never mask the test's own outcome
        }
        Dispatchers.resetMain()
    }
}
