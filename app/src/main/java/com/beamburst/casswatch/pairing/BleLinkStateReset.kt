package com.beamburst.casswatch.pairing

import org.avmedia.gshockapi.ble.Connection
import timber.log.Timber
import java.lang.reflect.Field

/**
 * Workaround for Nordic Android-BLE-Library 2.11.0 as used by GShockAPI.
 *
 * GShockAPI ends an app-initiated connection with `BleManager.close()`. That sets the
 * handler to DISCONNECTED but leaves `servicesDiscovered` / `serviceDiscoveryRequested`
 * set; the disconnect callback that would clear them then returns early because the
 * state is already DISCONNECTED. The next connection sees discovery as already
 * requested, never discovers services and never becomes ready, so the app stays on
 * "Connecting" until the process restarts.
 *
 * Call only after the library's close has returned. The flags are cleared exactly as
 * Nordic's own disconnect handling clears them, and only while no link is up.
 */
internal object BleLinkStateReset {
    private val STALE_FLAGS = listOf(
        "servicesDiscovered",
        "serviceDiscoveryRequested",
        "ready",
        "deviceNotSupported",
    )

    fun afterClose() {
        runCatching {
            val managerField = Connection::class.java.getDeclaredField("bleManager")
            managerField.isAccessible = true
            val manager = managerField.get(null) ?: return
            val handler = requestHandlerOf(manager) ?: return
            if (resetFlags(handler)) Timber.d("Cleared stale BLE discovery state after close")
        }.onFailure { Timber.w(it, "Could not clear BLE discovery state after close") }
    }

    /**
     * GShockAPI's `IGShockManager` delegates to a private `BleManager` subclass, so the
     * Nordic handler sits one object down. Look through the wrapper's own fields rather
     * than relying on the compiler-generated delegate name.
     */
    internal fun requestHandlerOf(manager: Any): Any? {
        handlerFieldOf(manager.javaClass)?.let { return it.get(manager) }
        for (field in manager.javaClass.declaredFields) {
            if (field.type.isPrimitive) continue
            field.isAccessible = true
            val inner = field.get(manager) ?: continue
            handlerFieldOf(inner.javaClass)?.let { return it.get(inner) }
        }
        return null
    }

    private fun handlerFieldOf(type: Class<*>): Field? =
        runCatching { findField(type, "requestHandler") }.getOrNull()

    /** Returns false when a link is starting or up again, leaving its flags untouched. */
    internal fun resetFlags(
        handler: Any,
        isConnectionActive: () -> Boolean = { Connection.isConnecting() || Connection.isConnected() },
    ): Boolean {
        val type = handler.javaClass
        val lock = findField(type, "LOCK").get(handler) ?: handler
        synchronized(lock) {
            if (isConnectionActive()) {
                Timber.d("Skipping BLE discovery state reset: connection is starting or connected")
                return false
            }
            if (findField(type, "connected").getBoolean(handler)) return false
            // Nordic callbacks do not take LOCK: a connection can still start after these checks.
            for (name in STALE_FLAGS) findField(type, name).setBoolean(handler, false)
        }
        return true
    }

    private fun findField(start: Class<*>, name: String): Field {
        var type: Class<*>? = start
        while (type != null) {
            runCatching { type.getDeclaredField(name) }.getOrNull()?.let {
                it.isAccessible = true
                return it
            }
            type = type.superclass
        }
        throw NoSuchFieldException(name)
    }
}
