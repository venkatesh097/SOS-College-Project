# End-to-End Field Testing & Verification Guide: Dual-Node LoRa Emergency Network

This document defines the comprehensive manual test protocol for verifying the **LoRa Emergency Communication System** using two physical hardware nodes (**NODE A** and **NODE B**) and two Android smartphones (**Phone 1** and **Phone 2**).

---

## Hardware & Environment Setup

| Component | Node A Setup | Node B Setup |
| :--- | :--- | :--- |
| **Microcontroller** | ESP32-WROOM-32 | ESP32-WROOM-32 |
| **LoRa Transceiver** | SX1278 (433MHz) / SX1276 (868/915MHz) | SX1278 / SX1276 |
| **IMU / Accelerometer** | MPU6050 (I2C: SDA 21, SCL 22) | MPU6050 (I2C: SDA 21, SCL 22) |
| **GPS Module** | NEO-6M / NEO-8M (Hardware Serial) | NEO-6M / NEO-8M (Hardware Serial) |
| **Display** | I2C LCD 16x2 / 20x4 or SSD1306 OLED | I2C LCD 16x2 / 20x4 or SSD1306 OLED |
| **Alert Output** | Active Buzzer + Status LED | Active Buzzer + Status LED |
| **SOS Button** | Tactile Push Button to GND | Tactile Push Button to GND |
| **Paired Android** | Phone 1 (Trekker Mode) | Phone 2 (Rescue Mode) |

---

## Field Test Scenarios

### 1. Bluetooth Pairing & Auto-Reconnection
- **Objective:** Verify pairing, filtering to `NODE*`, discovery lifecycle, and reconnect behavior on app restart.
- **Pre-conditions:** Bluetooth enabled on Phone 1; Node A powered on.
- **Test Steps:**
  1. Open app on Phone 1 and navigate to the **Bluetooth** tab.
  2. Verify that only devices prefixed with `NODE` appear by default.
  3. Toggle "Filter NODE* devices only" OFF; verify all surrounding Bluetooth devices appear. Toggle back ON.
  4. Select **NODE A** to initiate connection.
  5. Check "Connect automatically on launch".
  6. Force kill the app from Android Recents and re-launch.
- **Expected Phone Behavior:**
  - Phone connects over SPP (`00001101-0000-1000-8000-00805F9B34FB`).
  - Foreground notification displays: *"Connected to NODE A"*.
  - On app re-launch, app auto-reconnects to Node A without user intervention.
- **Expected Node LCD/Buzzer:**
  - LCD displays: `BT: CONNECTED` and `CLIENT: ACTIVE`. Short 100ms chirp.
- **Status:** [ ] PASS / [ ] FAIL

---

### 2. Status Badges & Hardware Diagnostics
- **Objective:** Verify status query parsing and live hardware state display (MPU, GPS, LoRa).
- **Pre-conditions:** Phone 1 connected to Node A.
- **Test Steps:**
  1. Navigate to **Home** screen.
  2. Observe the hardware diagnostic card.
  3. Tap **Refresh Status** or observe periodic status updates (every 30 seconds).
  4. Disconnect GPS antenna or cover in metal enclosure to force `NO_FIX`.
- **Expected Phone Behavior:**
  - Badges update to match node telemetry:
    - MPU: `READY` (Green) or `Fall detection OFF` (Gray if not found).
    - GPS: `FIX` (Green) or `NO_FIX` (Amber).
    - LoRa: `READY` (Green).
  - Ticker shows "Last heard Xs ago" updating continuously.
- **Expected Node LCD/Buzzer:**
  - Node answers `STATUS` query with `STATUS|NODE A|MPU=...|GPS=...|LORA=...`.
  - LCD shows satellite count / fix status and IMU state.
- **Status:** [ ] PASS / [ ] FAIL

---

### 3. Bidirectional Messages (Phone 1 &harr; Phone 2)
- **Objective:** Verify full-duplex LoRa message exchange between two independent pairs.
- **Pre-conditions:** Phone 1 connected to Node A; Phone 2 connected to Node B.
- **Test Steps:**
  1. On Phone 1, open **Messages** tab. Type *"Basecamp reached, checking in"* and tap **Send**.
  2. Observe outgoing bubble on Phone 1.
  3. Observe incoming bubble on Phone 2.
  4. On Phone 2, reply *"Copy that, stay safe"* and tap **Send**.
- **Expected Phone Behavior:**
  - Phone 1: Bubble starts in `Sending...`, transitions to `Sent over LoRa` when `TX|MSG|...` echo arrives.
  - Character counter enforces 196 max text limit (`MSG|` prefix = 200 total limit).
  - Phone 2: Receives message immediately. Short vibration and notification sound. Auto-scrolls to bottom.
- **Expected Node LCD/Buzzer:**
  - Node A: LCD flashes `TX MSG`, transmits LoRa packet.
  - Node B: LCD flashes `RX MSG` from Node A, emits double beep, sends `RX|...` line over Bluetooth.
- **Status:** [ ] PASS / [ ] FAIL

---

### 4. Quick-Reply Chips (Trekker vs Rescue Mode)
- **Objective:** Verify single-tap transmission of situational canned messages.
- **Pre-conditions:** Phone 1 in Trekker Mode; Phone 2 in Rescue Mode.
- **Test Steps:**
  1. On Phone 1, inspect quick-reply chips: *"All OK"*, *"Need help"*, *"Injured"*, *"Need water"*, *"Lost, returning"*, *"Resting"*.
  2. Tap *"Need water"*.
  3. On Phone 2, verify message received.
  4. Inspect Phone 2 chips (Rescue Mode): *"Help is on the way"*, *"Stay where you are"*, *"Send status"*, *"Share your condition"*.
  5. Tap *"Help is on the way"*.
- **Expected Phone Behavior:**
  - Instant transmission upon chip tap without typing.
  - Correct role-specific replies displayed on each device.
- **Expected Node LCD/Buzzer:**
  - Transmissions proceed cleanly without command formatting errors.
- **Status:** [ ] PASS / [ ] FAIL

---

### 5. App SOS Trigger (2-Second Hold & Cooldown)
- **Objective:** Prevent accidental emergency triggers while guaranteeing high-priority dispatch.
- **Pre-conditions:** Phone 1 on **Home** screen connected to Node A.
- **Test Steps:**
  1. Tap the SOS button once quickly.
  2. Press and hold the SOS button for 1 second, then release.
  3. Press and hold the SOS button continuously for 2.0 seconds with haptic feedback.
  4. Immediately try to tap SOS again.
- **Expected Phone Behavior:**
  - Short tap: No transmission (displays *"Hold for 2 seconds"* hint).
  - 1-second release: Progress resets, no transmission.
  - 2-second hold: Circular progress completes, strong haptic pulse, sends `SOS`.
  - SOS button enters 10-second cooldown with visible countdown.
  - Once `TX|SOS|...` echo arrives, UI shows *"SOS sent over LoRa"*.
- **Expected Node LCD/Buzzer:**
  - Node A transmits SOS LoRa packet with GPS coordinates. LCD shows `*** SOS BROADCAST ***`. Buzzer pulses.
- **Status:** [ ] PASS / [ ] FAIL

---

### 6. Physical Hardware Button SOS (No Phone Interaction Needed)
- **Objective:** Verify that pressing the physical node button without using the phone triggers LoRa SOS and updates the app.
- **Pre-conditions:** Node A running with paired Phone 1 nearby.
- **Test Steps:**
  1. Press the physical SOS push button on Node A hardware.
- **Expected Phone Behavior:**
  - Phone receives `TX|SOS|NODE A|<id>|<lat>|<lon>`.
  - An outgoing SOS row is inserted into local history marked `Sent over LoRa`.
  - **CRITICAL:** Phone 1 does NOT sound an alarm or create an incoming incident (own echo suppression verified!).
- **Expected Node B & Phone 2 Behavior:**
  - Node B receives LoRa SOS packet, triggers continuous local alarm buzzer.
  - Phone 2 triggers full-screen `AlertActivity` emergency alarm.
- **Status:** [ ] PASS / [ ] FAIL

---

### 7. Automatic Fall Detection (Hard Shake + 3-Min Inactivity)
- **Objective:** Verify MPU6050 impact detection, silent monitoring, and automatic fall dispatch.
- **Pre-conditions:** Node A resting on flat surface; Phone 1 connected.
- **Test Steps:**
  1. Firmly shake / drop Node A onto a padded surface to trigger impact acceleration threshold (>3.0g).
  2. Keep Node A completely motionless.
  3. Observe Phone 1 **Home** screen.
  4. Wait for the 3-minute monitoring countdown to expire without moving Node A.
- **Expected Phone Behavior:**
  - Phone 1 receives `FALL_MONITORING|NODE A`.
  - Amber banner appears: *"High impact detected: monitoring"* with live `mm:ss` countdown from 3:00.
  - Note indicates *"Automatic FALL SOS follows if inactivity persists"*.
  - After 3:00 expires, node broadcasts `FALL`. Phone receives `TX|FALL` echo.
- **Expected Node B & Phone 2 Behavior:**
  - Phone 2 receives `RX|FALL|NODE A|...`.
  - Emergency full-screen alarm fires: *"FALL DETECTED from NODE A: no movement for 3 minutes"*.
- **Status:** [ ] PASS / [ ] FAIL

---

### 8. Cancelled Fall (Movement Resumed During Countdown)
- **Objective:** Verify false-alarm cancellation when user moves after an impact.
- **Pre-conditions:** Node A running; Phone 1 connected.
- **Test Steps:**
  1. Trigger impact on Node A (shake).
  2. Observe amber countdown banner start on Phone 1.
  3. At 2:30 remaining, pick up Node A and shake/move it.
- **Expected Phone Behavior:**
  - Node detects motion and sends `FALL_CANCELLED|NODE A`.
  - Amber banner is replaced by green banner: *"Movement detected, fall cancelled"*.
  - Green banner automatically dismisses after ~4 seconds.
  - No SOS transmission occurs.
- **Expected Node B & Phone 2 Behavior:**
  - Zero alarms triggered on Node B or Phone 2.
- **Status:** [ ] PASS / [ ] FAIL

---

### 9. GPS Coordinates: INVALID vs Valid Fix
- **Objective:** Verify fallback when satellite visibility is lost (caves, deep valleys, indoor testing).
- **Pre-conditions:** Node A without satellite lock (`INVALID` coordinates).
- **Test Steps:**
  1. Send SOS or message from Node A while GPS shows `NO_FIX`.
  2. Inspect message/incident bubble on Phone 2.
  3. Move Node A outdoors until satellite fix is acquired (`GPS=FIX`).
  4. Send another message with coordinates.
- **Expected Phone Behavior:**
  - When coordinates are `INVALID`:
    - Chat bubble shows *"No GPS fix"*; no Maps button is shown; database records `latitude = null, longitude = null` (no 0.0, 0.0 coordinates).
  - When coordinates are valid:
    - Chip shows formatted latitude/longitude (e.g. `13.0827, 80.2707`).
    - Tapping chip opens Google Maps via `geo:<lat>,<lon>?q=<lat>,<lon>(Location)`.
- **Status:** [ ] PASS / [ ] FAIL

---

### 10. Bluetooth Drop & Exponential Backoff Reconnection
- **Objective:** Verify connection recovery when walking out of Bluetooth range or toggling Bluetooth.
- **Pre-conditions:** Phone 1 connected to Node A.
- **Test Steps:**
  1. Turn OFF Bluetooth in Phone 1 quick settings.
  2. Wait 5 seconds; turn Bluetooth back ON.
  3. Walk Phone 1 out of Bluetooth range from Node A (~15 meters through walls) until link drops.
  4. Walk back into range within 1 minute.
- **Expected Phone Behavior:**
  - Connection drops: Repository transitions to `NONE`. Foreground notification shows *"Disconnected"*.
  - Exponential backoff kicks in (2s, 4s, 8s, 16s... up to 30s).
  - When back in range, app automatically reconnects without user intervention.
  - Foreground notification returns to *"Connected to NODE A"*.
- **Status:** [ ] PASS / [ ] FAIL

---

### 11. Screen-Off Alert Delivery & Full-Screen Intent
- **Objective:** Verify life-safety alert delivery when the phone is locked and display is off.
- **Pre-conditions:** Phone 2 screen locked and display OFF in pocket.
- **Test Steps:**
  1. Trigger SOS from Node A.
- **Expected Phone 2 Behavior:**
  - Phone display turns ON automatically (`turnScreenOn = true`, `showWhenLocked = true`).
  - `AlertActivity` appears over the lock screen.
  - Alarm sound plays loudly on `STREAM_ALARM` even if phone is on Silent/Vibrate.
  - Continuous vibration pattern pulses.
  - Tapping **Acknowledge** silences the alarm and marks incident acknowledged in database.
  - Tapping **Open in Maps** launches external navigation app.
- **Status:** [ ] PASS / [ ] FAIL

---

### 12. Rescue Mode & Incidents Dashboard
- **Objective:** Verify triage capabilities in Rescue Mode.
- **Pre-conditions:** Phone 2 in Rescue Mode with 3 unacknowledged incidents.
- **Test Steps:**
  1. Open app on Phone 2.
  2. Check bottom navigation: Start destination is **Incidents** tab.
  3. Check badge on bottom navigation: Displays badge with count `3`.
  4. View incident cards: SOS/FALL badges, elapsed time ("5m ago"), coordinates.
  5. Tap **Copy** on coordinates. Verify clipboard content.
  6. Tap **Acknowledge** on an incident.
- **Expected Phone Behavior:**
  - Acknowledged incident is re-ordered to bottom / marked acknowledged.
  - Bottom navigation badge count decrements from 3 to 2.
  - "Last Known Positions" card updates with the node's latest valid GPS fix.
- **Status:** [ ] PASS / [ ] FAIL

---

### 13. LoRa Range & Signal Quality Test
- **Objective:** Determine reliable line-of-sight and non-line-of-sight range.
- **Pre-conditions:** Node A fixed at basecamp; Node B mobile.
- **Test Steps:**
  1. Walk Node B at 100m intervals (100m, 250m, 500m, 1km, 2km).
  2. At each interval, send a test ping or message.
  3. Observe RSSI and SNR reported on node display and packet roundtrip.
- **Expected Node Behavior:**
  - Packets received down to -120 dBm RSSI and -10 dB SNR.
  - Corrupt or CRC-failed packets dropped silently without crashing parser.
- **Status:** [ ] PASS / [ ] FAIL


---

### 14. Phone GPS Fallback & GPS Coordination
- **Objective:** Verify phone GPS fallback coordinates are pushed to the node when the node has `NO_FIX`, ensuring emergency SOS, FALL, and MSG packets always carry coordinates.
- **Pre-conditions:** Phone 1 connected to Node A. Node A has no GPS fix (`GPS=NO_FIX`, e.g. indoor or covered antenna).
- **Test Steps:**
  1. Navigate to **Home** tab:
     - Observe the diagnostic badges: `GPS: NO_FIX`.
     - Observe the location source: Shows **Phone GPS** (green).
     - Verify the amber warning banner is hidden because fallback coordinates are available.
  2. Navigate to **Settings** tab:
     - Verify toggle **"Use phone GPS when node has no fix"** is ON by default.
     - Tap **"Send location now"**.
     - Verify command is sent immediately as `GPS,lat,lon` without delay.
  3. Send an SOS from Phone 1 or press the physical hardware SOS button on Node A:
     - Verify outgoing SOS contains the phone's 6-decimal latitude and longitude instead of `NA`/`INVALID`.
     - Verify Phone 2 receives the alert with clickable coordinates linking to Google Maps.
  4. Move Node A outdoors so it acquires a valid satellite fix (`GPS=FIX` sustained for >= 10s):
     - Observe phone stops pushing location updates to conserve battery.
     - Home screen location source badge updates to **Node GPS**.
  5. Deny location permissions or disable fallback in Settings:
     - Verify Home screen displays amber warning banner: *"SOS alerts will have no coordinates - tap to enable phone GPS"*.
     - Tap the warning banner: Verify it prompts for permission or opens location settings.
- **Status:** [ ] PASS / [ ] FAIL
