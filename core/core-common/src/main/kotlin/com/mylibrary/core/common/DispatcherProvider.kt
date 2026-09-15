package com.mylibrary.core.common

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Indirection over [Dispatchers] so that anything which needs a background thread can be tested
 * deterministically with `StandardTestDispatcher` instead of a real thread pool.
 *
 * Injected through Hilt in production; hand-rolled test doubles in unit tests.
 */
interface DispatcherProvider {
    val main: CoroutineDispatcher
    val default: CoroutineDispatcher
    val io: CoroutineDispatcher
    val unconfined: CoroutineDispatcher
}

/** The production [DispatcherProvider], backed by the real coroutine dispatchers. */
class DefaultDispatcherProvider : DispatcherProvider {
    override val main: CoroutineDispatcher = Dispatchers.Main
    override val default: CoroutineDispatcher = Dispatchers.Default
    override val io: CoroutineDispatcher = Dispatchers.IO
    override val unconfined: CoroutineDispatcher = Dispatchers.Unconfined
}
