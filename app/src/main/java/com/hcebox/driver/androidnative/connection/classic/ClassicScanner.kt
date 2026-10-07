package com.hcebox.driver.androidnative.connection.classic

import com.hcebox.driver.androidnative.connection.ReaderScanner

/** Classic lists bonded devices instead of scanning; start publishes them once. */
class ClassicScanner(private val listBonded: () -> Unit) : ReaderScanner {
    override val isRunning = false

    override fun start(failure: (Throwable) -> Unit) = listBonded()

    override fun stop() {}
}
