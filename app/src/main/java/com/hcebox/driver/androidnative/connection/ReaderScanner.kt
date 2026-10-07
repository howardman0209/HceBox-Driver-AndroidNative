package com.hcebox.driver.androidnative.connection

/** One mode's background discovery, driven from the owner's queued Main scope. */
interface ReaderScanner {
    val isRunning: Boolean

    /** Publishes an initial result, then reports asynchronous failures through [failure]. */
    fun start(failure: (Throwable) -> Unit)

    /** Stops scanning and fences late callbacks; devices already published stay connectable. */
    fun stop()
}
