package com.mylibrary.core.ui.mvi

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The Model-View-Intent base every MyLibrary screen builds on.
 *
 * The contract is deliberately small, and enforced by types rather than by convention:
 *
 *  - **One immutable state object per screen.** A screen exposes exactly one [StateFlow] of one
 *    type, so "is it loading, is there also an error, is a dialog open" is a single value that
 *    cannot be internally inconsistent.
 *  - **Every user action is an [Intent].** The screen holds no logic; it renders state and reports
 *    what happened. That is what makes a screen previewable from a hand-written state and testable
 *    without a device.
 *  - **One-shot events are [UiEffect]s, not state.** Navigation and snackbars must be consumed
 *    exactly once; modelling them as state re-fires them on every recomposition and every
 *    configuration change, which is the most common bug in this pattern.
 *
 * `StateFlow` and `SharedFlow` only — there is no `LiveData` anywhere in MyLibrary.
 */
abstract class MviViewModel<S : Any, I : Any, E : Any>(initialState: S) : ViewModel() {

    private val _state = MutableStateFlow(initialState)

    /** The screen's state. Always has a value, so the UI never needs a null branch. */
    val state: StateFlow<S> = _state.asStateFlow()

    // Buffered rather than conflated: an effect carries information — which error occurred, which
    // book was deleted — and dropping one because the collector was briefly busy would lose it.
    private val effectChannel = Channel<E>(capacity = Channel.BUFFERED)

    /** One-shot effects, delivered to exactly one collector. */
    val effects: Flow<E> = effectChannel.receiveAsFlow()

    /** The state as of this moment, for reading inside intent handling. */
    protected val currentState: S get() = _state.value

    /** Applies a reducer to the current state. Reducers must be pure and total. */
    protected fun setState(reducer: S.() -> S) {
        _state.update(reducer)
    }

    /** Emits a one-shot effect. Suspends only if the buffer is full, which it should never be. */
    protected suspend fun sendEffect(effect: E) {
        effectChannel.send(effect)
    }

    /**
     * Runs work in the ViewModel's scope, cancelled automatically when the screen goes away.
     *
     * Returns the [Job] so callers can cancel superseded work — a debounced save, for instance,
     * where each new event must cancel the previous one's pending write.
     */
    protected fun launch(block: suspend CoroutineScope.() -> Unit): Job =
        viewModelScope.launch(block = block)

    /** Handles one user action. Implementations must be exhaustive over their intent type. */
    abstract fun onIntent(intent: I)
}

/**
 * Collects a screen's [MviViewModel.effects] for as long as it is composed.
 *
 * The callback is read through `rememberUpdatedState` so the lambda that runs is always the latest
 * one, without restarting the collection — restarting would drop effects already sitting in the
 * channel's buffer.
 */
@Composable
fun <E : Any> ObserveEffects(effects: Flow<E>, onEffect: (E) -> Unit) {
    val currentOnEffect = rememberUpdatedState(onEffect)
    LaunchedEffect(effects) {
        effects.collect { effect -> currentOnEffect.value(effect) }
    }
}
