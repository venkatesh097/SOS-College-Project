# Trekker Node Android App: Antigravity Prompt Pack

Run the prompts in order. Finish and test each phase before starting the next. Paste Prompt 0 first and keep it as the workspace rule or context so every later prompt inherits it.

---

## Prompt 0: Project context (paste first, keep as workspace rule)

```
You are building an Android app named "Trekker Node" in Kotlin + Jetpack Compose (Material 3). Package: com.trekker.node. minSdk 26, latest targetSdk. Android only.

PURPOSE
Companion app for an ESP32 LoRa trekker SOS node (NODE A / NODE B, identical firmware). The phone talks to the node over CLASSIC Bluetooth SPP (UUID 00001101-0000-1000-8000-00805F9B34FB), not BLE. All data is newline-terminated text lines.

PHONE -> NODE commands
  MSG|<text>
  SOS
  STATUS
  PING

NODE -> PHONE lines
  READY|<NODE>
  PONG|<NODE>
  STATUS|<NODE>|MPU=<READY|NOT_FOUND>|GPS=<FIX|NO_FIX>|LORA=READY
  TX|<packet>        (packet this node just sent over LoRa)
  RX|<packet>        (packet received over LoRa from the other node)
  FALL_MONITORING|<NODE>   (high impact detected, 3-minute silent monitoring started)
  FALL_CANCELLED|<NODE>    (movement detected, monitoring cancelled)
  ERROR|<EMPTY_MESSAGE|UNKNOWN_COMMAND|COMMAND_TOO_LONG>

LoRa packets inside TX/RX
  MSG|<node>|<id>|<lat>|<lon>|<message text>
  SOS|<node>|<id>|<lat>|<lon>
  FALL|<node>|<id>|<lat>|<lon>
lat/lon can be the literal string INVALID when the node has no GPS fix. The message text is the LAST field and may itself contain '|', so split with a limit.

PROTOCOL FACTS
- There is no ACK or retry. The UI may say "sent over LoRa" but never "delivered".
- Message ids are per node. De-duplicate on (node, id).
- Command max length is 200 chars; a MSG command must contain no newline.
- The node only forwards data to the phone while a Bluetooth client is connected.

APP MODES (one app, mode switch)
- Trekker mode: connection status, chat, quick replies, hold-to-confirm SOS, fall-monitoring banner with 3-minute countdown and cancelled state.
- Rescue mode: incident-first dashboard for incoming SOS/FALL (sender, lat/lon, time received), chat secondary.

MAP (v1)
No embedded map. Show coordinates as text and an "Open in Maps" button using a geo: intent. Handle INVALID coordinates.

ARCHITECTURE RULES
- Single-activity, Navigation Compose, MVVM with ViewModel + StateFlow.
- Kotlin coroutines/Flow. Room for persistence. No third-party Bluetooth libraries; use android.bluetooth APIs.
- Protocol parsing is pure Kotlin with no Android dependencies, so it is unit-testable.
- Bluetooth lives in a foreground service; UI observes a repository.
- Keep code small and readable. Add KDoc only where logic is non-obvious.
- After each task: build, run unit tests, and summarize what changed and how to verify.
```

---

## Prompt 1: Project scaffold

```
Create the Android project scaffold per the project context.

- Gradle Kotlin DSL, version catalog, Compose + Material 3, Navigation Compose, Room (with KSP), coroutines.
- Packages: bluetooth/, protocol/, data/, ui/ (screens, components, theme), service/, util/.
- MainActivity with a bottom bar or top toggle for modes (Trekker / Rescue) and a placeholder screen for each plus a Settings screen.
- AndroidManifest permissions: BLUETOOTH_CONNECT and BLUETOOTH_SCAN (Android 12+), BLUETOOTH and BLUETOOTH_ADMIN with maxSdkVersion=30, ACCESS_FINE_LOCATION only if required for legacy discovery, POST_NOTIFICATIONS, FOREGROUND_SERVICE, FOREGROUND_SERVICE_CONNECTED_DEVICE, USE_FULL_SCREEN_INTENT, VIBRATE.
- Dark and light theme. App builds and launches.

Verify: ./gradlew assembleDebug succeeds and the app shows the three placeholder screens.
```

---

## Prompt 2: Protocol parser (pure Kotlin) + tests

```
Implement the protocol layer in package protocol/, pure Kotlin.

1. Sealed class NodeEvent: Ready(node), Pong(node), Status(node, mpu, gps, lora), Tx(packet: LoraPacket), Rx(packet: LoraPacket), FallMonitoring(node), FallCancelled(node), Error(reason), Unknown(raw).
2. Sealed class LoraPacket: Msg(node, id, lat, lon, text), Sos(node, id, lat, lon), Fall(node, id, lat, lon). lat/lon are Double? (null when INVALID).
3. object ProtocolParser { fun parseLine(line: String): NodeEvent }. Handle: trailing \r, empty lines, message text containing '|' (split with limit), malformed packets (return Unknown, never throw), non-numeric ids.
4. object CommandBuilder: msg(text) -> "MSG|text\n" (strip newlines/CR, trim, reject empty, truncate so the whole command stays <= 200 chars), sos(), status(), ping().
5. class LineBuffer: feed(bytes/chars) -> List<String> of complete lines; handles fragmented input like "MS" then "G|Hello\n"; drops lines over a safe limit.

Write JUnit tests covering every event type, INVALID coordinates, '|' inside the message, fragmented input, and malformed packets.

Verify: ./gradlew test passes.
```

---

## Prompt 3: Bluetooth connection manager

```
Implement bluetooth/BluetoothSppClient using android.bluetooth classic RFCOMM.

- Runtime permission flow for BLUETOOTH_CONNECT / BLUETOOTH_SCAN on Android 12+, legacy behavior below.
- List bonded devices; let the user pick a node (default filter: names starting with "NODE"). Pairing itself is done in system settings; show a button that opens it.
- connect(device): createRfcommSocketToServiceRecord(SPP UUID), connect on Dispatchers.IO, cancel discovery first.
- Expose StateFlow<ConnectionState> (Disconnected, Connecting, Connected(deviceName), Error(msg)) and a SharedFlow<NodeEvent> built from InputStream -> LineBuffer -> ProtocolParser.
- send(command: String) writes to OutputStream on IO, serialized with a Mutex.
- Auto-reconnect with exponential backoff (cap 30s) when the link drops unexpectedly; no reconnect after a user-initiated disconnect.
- Heartbeat: send PING every 15s while connected; if no PONG or any line within ~45s, treat as dead and reconnect. Send STATUS once on connect.
- Clean up sockets and coroutines on disconnect. Never block the main thread.

Create a FakeNodeConnection implementing the same interface that emits scripted lines (including an SOS and a FALL) so the UI can be developed without hardware.

Verify: unit-test the reconnect/heartbeat logic with a fake socket and virtual time.
```

---

## Prompt 4: Foreground service + repository + Room

```
Add service/NodeConnectionService (foreground service, foregroundServiceType=connectedDevice) that owns BluetoothSppClient so the link survives screen-off and app backgrounding.

- Persistent notification showing connection state (e.g. "Connected to NODE A") with a Disconnect action.
- A NodeRepository (singleton) exposing: connectionState, nodeStatus (MPU/GPS/LORA), fallState (Normal, Monitoring(startedAt), Cancelled), messages, incidents. UI never talks to the socket directly.
- Room entities:
  - MessageEntity(id PK auto, node, packetId, direction SENT|RECEIVED, text, lat, lon, timestamp)
  - IncidentEntity(id PK auto, type SOS|FALL, node, packetId, lat, lon, receivedAt, acknowledged Boolean)
- On Rx/Tx events: parse, de-duplicate on (node, packetId, type), insert. Tx MSG becomes a SENT message; Rx MSG becomes RECEIVED; Rx SOS/FALL becomes an incident. Tx SOS/FALL is stored as a sent event so the trekker sees their own history.
- FallMonitoring starts a 3-minute countdown state in the repository (derived from startedAt); FallCancelled clears it.
- Provide DAOs with Flow queries and a settings DataStore (selected device address, last mode, own node name).

Verify: instrumented or Robolectric tests for de-duplication and incident creation using the fake connection.
```

---

## Prompt 5: Trekker mode UI

```
Build the Trekker mode screen with Compose.

- Top: connection chip (Connected / Connecting / Disconnected) with device name, plus small status badges for MPU, GPS (FIX / NO FIX), LORA from STATUS. Tapping the chip opens the device picker / reconnect.
- Fall banner: when fallState is Monitoring show an amber banner "High impact detected: monitoring" with a live countdown from the 3-minute window; on Cancelled show a brief green "Movement detected, fall cancelled" banner.
- Chat list (LazyColumn) of sent/received messages with timestamp and, for received, sender and coordinates (tappable, opens Maps via geo: intent; show "No GPS fix" for INVALID).
- Compose bar: free-text field (disabled when disconnected, character counter, enforce the command length limit) and a row of quick-reply chips: "All OK", "Need help", "Injured", "Need water", "Lost, returning", "Resting". Tapping a chip sends immediately.
- SOS button: large red, hold for 2 seconds with a progress ring to trigger; haptic feedback; sends "SOS". Show a "SOS sent over LoRa" confirmation (not "delivered").
- Sending states: "Sending..." then "Sent over LoRa" when the matching TX|MSG echo arrives; "Failed" if the node returns ERROR or the link is down.

Verify with FakeNodeConnection: all states reachable, no crashes on rotation, accessible content descriptions on the SOS button.
```

---

## Prompt 6: Alerts for incoming SOS / FALL

```
Implement the alert system.

- Create notification channels: "Emergency alerts" (IMPORTANCE_HIGH, bypass DND if the user grants it, alarm sound, vibration) and "Connection" (low).
- On Rx SOS or Rx FALL: post a high-priority notification with a full-screen intent to AlertActivity, even when the screen is off.
- AlertActivity (Compose): shows over the lock screen, red background, "SOS from NODE B" or "FALL DETECTED from NODE B", time, coordinates (or "No GPS fix"), buttons "Open in Maps", "Acknowledge", "Reply". Loops an alarm sound on the alarm audio stream and vibrates until acknowledged. Acknowledging stops the sound and marks the incident acknowledged.
- Distinguish FALL (automatic, person may be unconscious) from SOS (manual) with different titles and copy.
- Handle Android 13+ POST_NOTIFICATIONS and Android 14+ full-screen intent permission; guide the user to settings if denied.
- Repeat the notification if unacknowledged after 60 seconds.

Verify: trigger from FakeNodeConnection while the app is backgrounded and the screen is locked.
```

---

## Prompt 7: Rescue mode UI

```
Build the Rescue mode screen.

- Incident-first layout: a list of active incidents (unacknowledged first, newest first) as cards: type badge (SOS / FALL), sender node, time received and "x min ago", coordinates with Copy and Open in Maps buttons, Acknowledge button. Group repeated packets from the same node + type.
- Sender "last known position" section: for each node seen, latest valid lat/lon and last-heard time.
- Secondary tab: the same chat view as Trekker mode, with quick-reply chips tuned for rescue ("Help is on the way", "Stay where you are", "Send status", "Share your condition").
- Header shows connection state and counts of unacknowledged incidents.
- Empty state: "No incidents. Monitoring NODE link."
- Settings toggle remembers the last selected mode.

Verify with FakeNodeConnection: SOS and FALL from the other node create incidents, acknowledge works, INVALID coordinates render sensibly.
```

---

## Prompt 8: Settings, history, and polish

```
Finish the app.

- Settings: selected node, own node name label, alarm sound volume and test button, notification permission status, battery-optimization exemption prompt (the foreground service must stay alive), clear history.
- History screen: all messages and incidents with filters (All / Messages / SOS / FALL), export as a text or CSV file via the share sheet.
- Error handling: friendly messages for Bluetooth off (prompt to enable), permission denied, device not bonded, connection failed.
- Edge cases: duplicate packets, very long messages, rapid sends, app killed and restarted while the service runs, Bluetooth toggled off mid-session.
- Performance: no main-thread I/O, bounded in-memory lists, Room queries paged.
- Add a debug-only "Simulator" screen that injects sample lines (MSG, SOS, FALL, STATUS, INVALID coordinates) through the fake connection.

Verify: manual checklist in README.md with each scenario and expected result.
```

---

## Prompt 9: End-to-end hardware test plan

```
Write TESTING.md for testing with two real nodes (NODE A and NODE B), one phone per node.

Cover: pairing and first connect, STATUS badges, MSG both directions, quick replies, manual SOS from button on node and from app, automatic FALL (impact then no movement for 3 minutes) and cancelled FALL (movement), INVALID GPS indoors versus valid outdoors, Bluetooth drop and auto-reconnect, screen-off and locked-phone alert delivery, battery-saver behavior, and range checks. For each: steps, expected phone behavior, expected node LCD/buzzer behavior, and a pass/fail box.
```

---

## Optional follow-ups (later)

- Forward RSSI/SNR from firmware over Bluetooth (`RX|...|RSSI=..|SNR=..` or a separate line) and show signal strength in the app.
- Offline map tiles with a locally cached map view.
- Firmware ACK/retry so the app can show "delivered".
