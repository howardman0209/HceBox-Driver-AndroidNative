package com.hcebox.driver.androidnative.connection

/** User-selected reader link. Persisted by [name]; renaming a constant resets the saved choice. */
enum class ConnectionMode {
    TCP,
    CLASSIC,
    BLE,
}
