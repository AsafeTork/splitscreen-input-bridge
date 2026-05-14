package com.splitscreen.inputbridge.util

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Centralized coroutine management utility
 *
 * This class provides a centralized way to manage coroutine scopes across the application,
 * ensuring consistent error handling and resource management.
 */
object CoroutineManager {

    /**
     * Creates a standard coroutine scope with default dispatcher and supervisor job
     */
    fun createStandardScope(): CoroutineScope {
        return CoroutineScope(Dispatchers.Default + SupervisorJob())
    }

    /**
     * Creates a coroutine scope with a specific dispatcher
     */
    fun createScopeWithDispatcher(dispatcher: kotlinx.coroutines.CoroutineDispatcher): CoroutineScope {
        return CoroutineScope(dispatcher + SupervisorJob())
    }

    /**
     * Creates a coroutine scope with custom exception handler
     */
    fun createScopeWithExceptionHandler(exceptionHandler: CoroutineExceptionHandler): CoroutineScope {
        return CoroutineScope(Dispatchers.Default + SupervisorJob() + exceptionHandler)
    }

    /**
     * Creates a coroutine scope with both custom dispatcher and exception handler
     */
    fun createScopeWithDispatcherAndHandler(
        dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default,
        exceptionHandler: CoroutineExceptionHandler
    ): CoroutineScope {
        return CoroutineScope(dispatcher + SupervisorJob() + exceptionHandler)
    }

    /**
     * Creates an IO-bound coroutine scope
     */
    fun createIOScope(): CoroutineScope {
        return CoroutineScope(Dispatchers.IO + SupervisorJob())
    }

    /**
     * Creates a main thread coroutine scope
     */
    fun createMainScope(): CoroutineScope {
        return CoroutineScope(Dispatchers.Main + SupervisorJob())
    }

    /**
     * Creates a computationally intensive coroutine scope
     */
    fun createComputeScope(): CoroutineScope {
        return CoroutineScope(Dispatchers.Default + SupervisorJob())
    }
}