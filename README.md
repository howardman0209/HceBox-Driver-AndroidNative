# Android Native NFC Driver

Driver app (`com.hcebox.driver.androidnative`) runs alongside HceBox and exposes
the remote [reader app](../../reader/README.md) through the existing
`ICardReaderDriver` contract (API version 2). Native NFC access belongs to the
reader device, not this driver device. See the
[execution plan](../../NFC_READER_PLAN.md) and [handoff](../../reader/HANDOFF.md).

## Build

Use JDK 21 and SDK 37. Configure your ignored `local.properties` with the SDK
path. Publish the existing driver contract from `../../cardspy` if it is absent
from Maven Local: `./gradlew :card-reader-driver-api:publishToMavenLocal`.

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Debug signing uses the standard debug keystore. APK:
`app/build/outputs/apk/debug/app-debug.apk`. Shared protocol sources are included
from `../../reader/protocol`; driver output goes to `build/protocol` so independent
builds do not overwrite each other's outputs. No protocol source duplication,
new Git submodule, or vendor SDK is involved.

## Operation

- TCP: enter reader hostname/IP and port (default 35965); Connect saves the
  endpoint. Discovery reports the configured endpoint without probing it.
- Classic: choose CLASSIC, grant permissions, pair through system settings,
  list bonded devices, and choose the reader device. Service compatibility is
  verified on connection through the fixed RFCOMM UUID and protocol handshake.
- BLE: choose BLE, grant permissions, scan for the fixed reader service UUID,
  choose a reader, then Connect. MTU negotiation can fall back to 23; CCCD
  indication subscription finishes before HELLO.

Settings and endpoint identity persist; live selection and connections do not.
While connected, the service uses a connected-device foreground notification
and survives UI unbinding. Disconnect closes the transport and removes the
reader. After OS process death, explicitly reconnect and reselect.

In HceBox: Settings -> Card Transport -> Proxy -> Scan -> Android Native NFC
Reader. The driver setup Activity handles transport-specific permissions;
TCP needs no Bluetooth runtime grant. Slot index is zero. Selection does not
wait for a card. Failures are typed AIDL errors; HceBox owns their status-word
mapping. Relay is future work and has no active selector.

## Android service smoke test

This test uses the platform instrumentation API, without another test library.
Keep the reader running with the requested transport. It performs discovery,
service binding, connect/callback checks, selection, no-card error mapping
(if no card is present), unselect, and disconnect. It sends no APDU to a present card unless explicitly given `-e apdu HEX`.
That opt-in path waits up to 10 seconds for a card, sends the specified command
once, records response/timing, and optionally compares `-e expected RESPONSE_HEX`.
Use only known non-writing test commands. It does not validate HceBox's
separate-process integration.

```sh
./gradlew :app:assembleDebugAndroidTest
adb -s DRIVER_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s DRIVER_SERIAL install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s DRIVER_SERIAL shell am instrument -w -e mode TCP -e host READER_IP com.hcebox.driver.androidnative.test/com.hcebox.driver.androidnative.DriverSmokeInstrumentation
adb -s DRIVER_SERIAL shell am instrument -w -e mode BLE com.hcebox.driver.androidnative.test/com.hcebox.driver.androidnative.DriverSmokeInstrumentation
```

For Classic, add `-e mode CLASSIC -e deviceId CLASSIC:READER_BLUETOOTH_ADDRESS`
and ensure the named device is bonded and listening. An explicit Classic ID
prevents the test from connecting to an unrelated bonded device. The smoke test
restores transport selection and disconnects before finishing.

For an explicitly authorized real-card SELECT PPSE test, append
`-e apdu 00A404000E325041592E5359532E444446303100` to the TCP/BLE command.
Re-present the card after each disconnected test session. Compare against the
local workbench with `-e expected RESPONSE_HEX`; no retry is performed on failure.

See the handoff for the exact completed checks and remaining hardware gates.

## Tag loss and controller lifetime

The process-owned `NativeApp.controller` is a class instance, shared by Activities
and the service. There is no static Context/controller field and no lint
suppression for StaticFieldLeak.

Protocol v2 STATUS includes an error reason. CARD_REMOVED is exposed through
`ReaderStatus.lastError` with presence false, while device connection and selection
remain active. New discovery clears the removal reason. Ordinary I/O failure
and timeout retain distinct codes. Both APKs must use v2; the HceBox AIDL API is
unchanged. API 37 reader callbacks can report idle removal; older readers also use a 200 ms idle presence monitor and retain NFC I/O
loss detection as a fallback. OEM presence-update behavior still requires validation.

For an idle-removal test, append `-e idleRemoval true` to the TCP/BLE instrumentation
command. Place the card before starting. When the test reports it is armed,
remove the card within 45 seconds. The test asserts the CARD_REMOVED callback,
absence, retained selection/connection, and sends no APDU. Its reported wait
includes human action time and is not a measured physical detection latency.
