package com.splitscreen.inputbridge.state

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * BridgeState — State Machine pattern for InputBridgeService lifecycle
 *
 * This sealed hierarchy represents all possible states of the bridge service,
 * enabling type-safe state transitions and clear separation of concerns.
 * Each state encapsulates its own behavior and validation rules.
 */
sealed class BridgeState : Parcelable {
    @Parcelize
    object Idle : BridgeState()

    @Parcelize
    object Initializing : BridgeState()

    @Parcelize
    data class Ready(val player1Descriptor: String = "", val player2Descriptor: String = "") : BridgeState() {
        val isFullyConfigured: Boolean
            get() = player1Descriptor.isNotEmpty() && player2Descriptor.isNotEmpty()

        val hasDifferentControllers: Boolean
            get() = player1Descriptor != player2Descriptor
    }

    @Parcelize
    object Active : BridgeState()

    @Parcelize
    data class Error(val message: String, val cause: Throwable? = null) : BridgeState()

    @Parcelize
    object Stopping : BridgeState()

    /**
     * State transition validator - ensures only valid state transitions occur
     */
    fun canTransitionTo(newState: BridgeState): Boolean {
        return when (this) {
            is Idle -> newState is Initializing
            is Initializing -> newState is Ready || newState is Error
            is Ready -> newState is Active || newState is Error || newState is Idle
            is Active -> newState is Stopping || newState is Error
            is Stopping -> newState is Ready || newState is Idle
            is Error -> newState is Ready || newState is Idle
        }
    }
}

/**
 * BridgeStateManager — Manages state transitions with validation
 */
class BridgeStateManager(private var currentState: BridgeState = BridgeState.Idle) {

    private val listeners = mutableListOf<(BridgeState) -> Unit>()

    fun getCurrentState(): BridgeState = currentState

    fun transitionTo(newState: BridgeState): Boolean {
        if (!currentState.canTransitionTo(newState)) {
            throw IllegalStateException("Invalid state transition from ${currentState::class.simpleName} to ${newState::class.simpleName}")
        }

        currentState = newState
        notifyListeners(newState)
        return true
    }

    fun addListener(listener: (BridgeState) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (BridgeState) -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners(state: BridgeState) {
        listeners.forEach { it(state) }
    }
}
