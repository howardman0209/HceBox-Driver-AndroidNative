# Android Native NFC Driver

Driver app (`com.hcebox.driver.androidnative`) runs alongside HceBox and exposes
the remote [reader app](https://github.com/howardman0209/HceBox-Reader-Android) through the existing
`ICardReaderDriver` contract (API version 2). Native NFC access belongs to the
reader device, not this driver device. See the
[execution plan](https://github.com/howardman0209/HceBox/blob/main/platforms/android/NFC_READER_PLAN.md)
and [handoff](https://github.com/howardman0209/HceBox-Reader-Android/blob/main/HANDOFF.md).

## Build

Use JDK 21 and SDK 37. Configure your ignored `local.properties` with the SDK
path. Prepare these artifacts in Maven Local before building:

- `com.hcebox:reader-protocol:0.1.0`: in Reader, run
  `./gradlew :protocol:test :protocol:publishToMavenLocal`.
- `com.hcebox:card-reader-driver-api:1.0.0-SNAPSHOT`: in HceBox's
  `platforms/android/cardspy`, run `./gradlew :card-reader-driver-api:publishToMavenLocal`.

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Debug and release use the ACS-style release signing properties:
`HCEBOX_RELEASE_STORE_FILE`, `HCEBOX_RELEASE_STORE_PASSWORD`,
`HCEBOX_RELEASE_KEY_ALIAS`, `HCEBOX_RELEASE_KEY_PASSWORD`. Supply them through
user Gradle properties/CI, never commit signing material. APK output directory:
`app/build/outputs/apk/debug/`. Driver builds only `:app`, consuming
the pinned protocol JAR and published AIDL AAR. It has no sibling-project source
paths, copied protocol code, vendor SDK or embedded protocol Gradle project.
See the [protocol source map](https://github.com/howardman0209/HceBox-Reader-Android/blob/main/protocol/README.md).

HceBox includes this repository as `platforms/android/driver/android-native`.
A standalone checkout builds after artifact preparation, with JDK 21 and SDK 37.
HceBox's `platforms/android/build-native-readers.sh` provides the same bootstrap
steps for local development/CI after initializing submodules.


## Operation

Bottom navigation separates Readers, Settings and Diagnostics. The blue Material 3
palette follows system light/dark mode. Detailed logs stay in Diagnostics, with
Copy/Clear actions; regular screens show actionable connection errors.

- TCP: choose TCP on Readers; discovery starts automatically. Tap a discovered
  Reader to connect and open its details. NSD resolves the actual port and
  IPv4/IPv6 addresses. Manual connection opens a separate hostname/IP and port
  form (default 35965); saved manual endpoints do not claim reachability.
- Classic: choose Classic, allow access if requested, and pair through system
  settings. Tap a paired device to connect; service compatibility is verified
  through the fixed RFCOMM UUID and protocol handshake.
- BLE: choose BLE, allow access, and tap a discovered reader. MTU negotiation can
  fall back to 23; CCCD indication subscription finishes before HELLO.

Reader details show connection/card/use status. Back and bottom navigation
preserve the connection; Disconnect closes it. Unexpected disconnection offers
Reconnect without automatic retry. Settings contain pairing and HceBox guidance.
SetupActivity is a transparent, permission-only entry point shared with HceBox,
following ACS/FEITIAN. Notification permission is optional and its denial does
not change transport readiness. Only selected transport permissions are required.

Settings and endpoint identity persist; live selection and connections do not.
While connected, the service uses a connected-device foreground notification
and survives UI unbinding. Disconnect closes the transport and removes the
reader. After OS process death, explicitly reconnect and reselect.

In HceBox: Settings -> Card Transport -> Proxy -> Scan -> Android Native NFC
Reader. The driver setup Activity handles transport-specific permissions;
TCP needs no Bluetooth runtime grant; Android 17 requires the local-network
permission through the same setup Activity. Slot index is zero. Selection does not
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
adb -s DRIVER_SERIAL install -r "$DRIVER_APK"
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

NSD discovery is local multicast, so VLAN/AP isolation may prevent it even when
manual TCP is reachable. Service loss removes the discovery item without forcing
disconnection. A selected endpoint is re-resolved under its original connect
deadline, including after discovery stops, and HELLO checks announced identity.
TXT/HELLO consistency is not peer authentication. Protocol v2 and AIDL are unchanged.

For a test that must use NSD (ignoring any saved manual endpoint), add
`-e nsd true` to a TCP instrumentation command and omit `-e host`. Add
`-e heartbeat true` to observe a heartbeat while idle; no card APDU is sent unless
explicitly provided. Android 34+ service-info callbacks track address updates;
older resolvers are serialized, with fresh resolution on connect.

## Source responsibilities

Android entry points stay in the root package to preserve installed component
names. `connection/` coordinates discovery and remote sessions; its `tcp/` and
`ble/` packages own platform transport details. `setup/` contains permission rules.
`ui/` owns navigation, `ui/screens/` renders individual pages, `ui/components/`
contains reusable rows/cards, and `ui/theme/` contains the palette.
`driver/DriverStatusMapper` projects protocol state/errors into AIDL DTOs. Discovery
receives mode/permission/device providers rather than looking up NativeApp through
a Context; permission rules take the selected transport explicitly.

Launcher masters: `app/src/main/ic_launcher-source.png` (original generated
1254px artwork) and `ic_launcher-playstore.png` (512px store export). Runtime icons
are density-specific WebP resources, adaptive icons and a monochrome vector.

## App version and signing

App versionCode is `10000` for versionName `1.0.0`, using
`major * 10000 + minor * 100 + patch` (minor/patch 0..99); debug
adds `.debug`. Archive names follow `<applicationId>_1.0.0-10000-YYYYMMDD`, with
variant suffixes supplied by Android Gradle Plugin. Read the actual filename from
`app/build/outputs/apk/<variant>/output-metadata.json`; for the smoke-test command,
set `DRIVER_APK` to that generated debug APK path.

Debug and release use the same configured release certificate. Existing installs
signed by the old Android debug key cannot be updated in place with a different
certificate; handle any reinstall/data reset explicitly. Protocol artifact `0.1.0`
and wire protocol v2 remain independent of the app version.

Release builds enable R8 code optimization and resource shrinking with
`proguard-android-optimize.txt` and narrow app rules. Android manifest entry points
use platform defaults; Native Driver relies on the published AIDL consumer rules.
Keep `app/build/outputs/mapping/release/` with each shipped APK for crash decoding.
Debug remains unminified for development. Protocol artifact/wire versions are unchanged.

## Asynchronous work

ReaderDiscovery uses an Application-owned coroutine scope with queued
`Dispatchers.Main` commands. Its BLE expiry Job uses `delay(1.seconds)` and is
cancelled when discovery stops; generation checks reject stale callbacks. Shared
UI/AIDL scan ownership and the 20-second observation lifetime are unchanged.
TcpDiscovery shares this scope for NSD callbacks and the platform-required Executor
adapter. Its worker-only synchronous refresh, connect deadline, serialized legacy
resolution queue and retained discovery identity are unchanged.
Follow the HceBox [Android asynchronous conventions](https://github.com/howardman0209/HceBox/blob/main/platforms/android/ASYNC_CONVENTIONS.md)
for subsequent migrations.

Verification (2026-10-07): debug unit tests, debug/test APK builds, lint and the
R8 release build passed. On SM-S9160 with SAH55, debug instrumentation verified
queued Main commands, provider isolation, shared discovery ownership, TCP NSD
with two reconnects and heartbeat, and BLE discovery/connect/heartbeat. BLE expiry
start/stop logs confirmed Job cancellation on scan stop. These were no-card tests;
real-card APDU/terminal acceptance and a runtime smoke of this updated release
remain separate. Existing dependency/icon lint warnings remain.

The subsequent TcpDiscovery migration passed the same debug unit/build/lint checks
and R8 release build. It removes application Handler/Looper scheduling without
changing NSD resolution semantics. Device reconnect verification is pending because
SM-S9160 disconnected from ADB before installation; the earlier hardware results
above apply to the preceding ReaderDiscovery revision.
