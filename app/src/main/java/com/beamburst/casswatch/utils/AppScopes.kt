package com.beamburst.casswatch.utils

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import timber.log.Timber

/**
 * Application-wide coroutine scopes for fire-and-forget work launched outside a
 * lifecycle-bound scope (event-bus handlers, services, storage writes).
 *
 * Unlike a bare `CoroutineScope(Dispatchers.IO)`, these scopes install a
 * [CoroutineExceptionHandler]: an exception escaping a top-level `launch` is logged
 * instead of being routed to the default uncaught-exception handler, which would
 * crash the process. `SupervisorJob` keeps one failed child from cancelling the rest.
 */
object AppScopes {
    private fun handler(name: String) = CoroutineExceptionHandler { _, t ->
        Timber.e(t, "Uncaught coroutine exception in AppScopes.%s", name)
    }

    val io: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + handler("io"))
    val default: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + handler("default"))
    val main: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main + handler("main"))
}
