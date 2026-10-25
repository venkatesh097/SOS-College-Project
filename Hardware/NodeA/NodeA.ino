/*
  LoRa Trekker Emergency Node  (ESP32 DevKit V1)
  - LoRa text messages with ACK + retry (1.5 s wait, 4 total attempts)
  - Manual SOS (1 press), SOS read-confirm (3 presses), 2 presses = nothing
  - MPU6050 fall detection: hard impact + 120 s without movement -> auto SOS
  - NEO-6M GPS, with fallback to the phone's GPS (via Bluetooth)
  - Bluetooth Classic (SPP) link to the phone app: device name "NODE A" / "NODE B"
  - 16x2 I2C LCD with automatic address detection
  NO message queue and NO 10-second retry scheduler.

  LoRa packet formats:
    D|sender|dest|id|payload   data / SOS
    A|sender|dest|id           acknowledgement
    R|sender|dest|sosID        SOS read confirmation
  SOS payload: SOS,reason,lat,lon,source   (source = GPS or PHONE)
            or SOS,reason,GPS_NO_FIX

  Phone -> node (Bluetooth, one command per line):
    MSG,text          send text to the other node
    SOS               send manual SOS
    READ              confirm the received SOS was read
    GPS,lat,lon       phone location (app should send every ~5 s)
    PING              node answers PONG
    (any other text is sent as a message)

  Node -> phone (Bluetooth, one event per line):
    HELLO,NODE A                         on connect
    STATUS,A,src,lat,lon,fall,sos        every 5 s (src = GPS / PHONE / NONE)
    NEED_GPS                             node asks the phone for its location
    MSG_IN,from,text                     message received
    SOS_IN,from,reason,lat,lon,src       SOS received (lat/lon = NA if none)
    SOS_READ,from                        the other node read our SOS
    MSG_SENT,OK|FAIL
    SOS_SENDING,reason,lat,lon,src
    SOS_SENT,OK|FAIL
    READ_SENT,OK|FAIL
    FALL,START,120 | FALL,CANCEL | FALL,SOS
    PONG | ERR,BUSY
*/

#include <SPI.h>
#include <Wire.h>
#include <LoRa.h>
#include <LiquidCrystal_I2C.h>
#include <Adafruit_MPU6050.h>
#include <Adafruit_Sensor.h>
#include <TinyGPSPlus.h>
#include "BluetoothSerial.h"

#if !defined(CONFIG_BT_ENABLED) || !defined(CONFIG_BLUEDROID_ENABLED)
#error Bluetooth is not enabled. Select board "ESP32 Dev Module".
#endif

// ====================== NODE IDENTITY ======================
#define NODE_ID "A"            // <-- "A" on Node A, "B" on Node B
const String MY_ID   = NODE_ID;
const String PEER_ID = (MY_ID == "A") ? "B" : "A";

// ====================== PINS ======================
#define LORA_SCK   18
#define LORA_MISO  19
#define LORA_MOSI  23
#define LORA_CS     5
#define LORA_RST   14
#define LORA_DIO0  26
#define I2C_SDA    21
#define I2C_SCL    22
#define GPS_RX_PIN 16          // ESP32 RX2  <- GPS TX
#define GPS_TX_PIN 17          // ESP32 TX2  -> GPS RX
#define BUTTON_PIN 32          // button to GND, INPUT_PULLUP
#define BUZZER_PIN 25          // active buzzer
#define LED_PIN    27          // LED + 220-330 ohm resistor

// ====================== LORA SETTINGS ======================
#define LORA_FREQ      433E6
#define LORA_SYNC      0x12
#define LORA_SF        7
#define LORA_BW        125E3
#define LORA_CR        5       // coding rate 4/5
#define LORA_TX_POWER  17      // dBm - check local regulations

// ====================== RETRY SETTINGS ======================
#define ACK_WAIT_MS    1500UL
#define MAX_ATTEMPTS   4
#define MAX_TEXT_LEN   100

// ====================== BUTTON SETTINGS ======================
#define DEBOUNCE_MS        30UL
#define MULTI_PRESS_GAP_MS 600UL

// ====================== FALL DETECTION (EXPERIMENTAL) ======================
#define FALL_IMPACT_G        2.8f      // sudden hard impact threshold (g)
#define FALL_COUNTDOWN_MS    120000UL  // no movement for this long -> SOS (use 15000UL while testing)
#define FALL_GRACE_MS        3000UL    // ignore the shaking right after the impact itself
#define MOVE_ACCEL_DELTA_G   0.30f     // |magnitude - 1 g| above this = movement
#define MOVE_GYRO_RAD_S      1.0f      // rotation above this = movement
#define MOVE_HITS_NEEDED     5         // movement samples needed to cancel
#define FALL_COOLDOWN_MS     10000UL
#define IMU_INTERVAL_MS      20UL

// ====================== GPS SETTINGS ======================
#define GPS_MAX_AGE_MS        10000UL  // NEO-6M fix older than this = not recent
#define PHONE_GPS_MAX_AGE_MS  60000UL  // phone location older than this = not recent
#define PHONE_GPS_WAIT_MS     2000UL   // how long an SOS waits for a fresh phone location

// ====================== OBJECTS ======================
LiquidCrystal_I2C *lcd = nullptr;      // created in setup after the address is found
Adafruit_MPU6050 mpu;
TinyGPSPlus gps;
HardwareSerial gpsSerial(2);
BluetoothSerial SerialBT;

// ====================== STATE ======================
struct Packet {
  char type;
  String from;
  String to;
  unsigned long id;
  String payload;
};

bool mpuReady = false;
uint8_t lcdAddr = 0x27;

unsigned long nextMsgId = 1;
unsigned long lastAckId = 0;
unsigned long lastRxId = 0;
unsigned long lastSentSosId = 0;

// incoming SOS alert (receiver side)
bool   sosAlertActive = false;
unsigned long sosId = 0;
String sosFrom = "";
String sosReason = "";
String sosLat = "";
String sosLon = "";
String sosSrc = "";
bool   sosHasGps = false;

// phone GPS
bool   phoneGpsValid = false;
double phoneLat = 0, phoneLon = 0;
unsigned long phoneGpsTime = 0;

// Bluetooth
String btBuf = "";
String pendingCmd = "";            // one command waiting to run from loop()
bool btWasConnected = false;
unsigned long lastStatus = 0;
unsigned long lastBtByteTime = 0;
#define BT_IDLE_FLUSH_MS 400UL   // treat text as a full line after this much silence

// fall detection
bool fallActive = false;
unsigned long fallStart = 0;
unsigned long fallCooldownUntil = 0;
unsigned long lastImuRead = 0;
int moveHits = 0;
unsigned long lastMoveHitTime = 0;

// button
bool lastReading = HIGH;
bool stableState = HIGH;
unsigned long lastDebounceTime = 0;
unsigned long lastReleaseTime = 0;
int pressCount = 0;

// buzzer / LED
unsigned long beepUntil = 0;

// LCD
String shown1 = "", shown2 = "";
String temp1 = "", temp2 = "";
unsigned long tempUntil = 0;
unsigned long lastDisplayUpdate = 0;
unsigned long lastLcdRefresh = 0;
unsigned long lastLcdReinit = 0;

// serial input
String serialBuf = "";
unsigned long lastGpsDebug = 0;

// ====================== LCD ======================
// Looks for the LCD backpack (PCF8574 = 0x20-0x27, PCF8574A = 0x38-0x3F) and
// prints every I2C device found (the MPU6050 should show up at 0x68).
uint8_t i2cScan() {
  uint8_t lcdFound = 0;
  Serial.println("[I2C] scanning...");
  for (uint8_t a = 1; a < 127; a++) {
    Wire.beginTransmission(a);
    if (Wire.endTransmission() == 0) {
      Serial.println("[I2C] device found at 0x" + String(a, HEX));
      if (lcdFound == 0 && ((a >= 0x20 && a <= 0x27) || (a >= 0x38 && a <= 0x3F))) {
        lcdFound = a;
      }
    }
  }
  return lcdFound;
}

void lcdHardwareInit() {
  lcd->init();
  lcd->backlight();
  lcd->clear();
  shown1 = "";
  shown2 = "";
}

void lcdWrite(const String &l1, const String &l2) {
  if (lcd == nullptr) return;
  String a = l1.substring(0, 16);
  String b = l2.substring(0, 16);
  while (a.length() < 16) a += ' ';
  while (b.length() < 16) b += ' ';
  if (a == shown1 && b == shown2) return;
  lcd->setCursor(0, 0);
  lcd->print(a);
  lcd->setCursor(0, 1);
  lcd->print(b);
  shown1 = a;
  shown2 = b;
}

void showTemp(const String &l1, const String &l2, unsigned long ms) {
  temp1 = l1;
  temp2 = l2;
  tempUntil = millis() + ms;
  lcdWrite(l1, l2);
}

// Recovery: rewrite the text every 2 s and re-initialise the LCD every 30 s,
// so a brown-out or loose wire cannot leave the screen blank for good.
void lcdMaintenance() {
  if (lcd == nullptr) return;
  unsigned long now = millis();
  if (now - lastLcdReinit > 30000UL) {
    lastLcdReinit = now;
    lastLcdRefresh = now;
    lcdHardwareInit();
  } else if (now - lastLcdRefresh > 2000UL) {
    lastLcdRefresh = now;
    shown1 = "";
    shown2 = "";
  }
}

// ====================== GPS HELPERS ======================
void pollGps() {
  while (gpsSerial.available()) {
    gps.encode(gpsSerial.read());
  }
}

bool gpsHasFix() {
  return gps.location.isValid() && gps.location.age() < GPS_MAX_AGE_MS;
}

bool phoneGpsFresh() {
  return phoneGpsValid && (millis() - phoneGpsTime) < PHONE_GPS_MAX_AGE_MS;
}

// Best location available right now (no waiting): NEO-6M first, then phone.
bool getLocationNow(String &lat, String &lon, String &src) {
  if (gpsHasFix()) {
    lat = String(gps.location.lat(), 6);
    lon = String(gps.location.lng(), 6);
    src = "GPS";
    return true;
  }
  if (phoneGpsFresh()) {
    lat = String(phoneLat, 6);
    lon = String(phoneLon, 6);
    src = "PHONE";
    return true;
  }
  return false;
}

// ====================== BLUETOOTH ======================
void btSend(const String &line) {
  if (SerialBT.hasClient()) SerialBT.println(line);
}

void notifyPhone(const String &line) {
  Serial.println("[BT>] " + line);
  btSend(line);
}

void handlePhoneGps(const String &line) {            // "GPS,lat,lon"
  String r = line.substring(4);
  int c = r.indexOf(',');
  if (c <= 0) return;
  double la = r.substring(0, c).toDouble();
  double lo = r.substring(c + 1).toDouble();
  if (fabs(la) > 90.0 || fabs(lo) > 180.0) return;
  if (la == 0.0 && lo == 0.0) return;                // phone has no real location
  phoneLat = la;
  phoneLon = lo;
  phoneGpsValid = true;
  phoneGpsTime = millis();
}

// Handles parsed lines from Bluetooth
void handleBtLine(String line) {
  line.trim();
  if (line.length() == 0) return;
  Serial.println("[BT<] " + line);

  String up = line;
  up.toUpperCase();
  if (up.startsWith("GPS,")) {          // handled immediately
    handlePhoneGps(line);
    return;
  }
  if (pendingCmd.length() == 0) pendingCmd = line;
  else btSend("ERR,BUSY");
}

// Reads Bluetooth text. GPS lines are handled at once; every other command is
// stored in a single slot and run later from loop() (never in the middle of a send).
void btReadLines() {
  while (SerialBT.available()) {
    char c = SerialBT.read();
    lastBtByteTime = millis();

    // debug: shows exactly what the app sends (hex)
    Serial.print("[BT raw] 0x");
    Serial.println((uint8_t)c, HEX);

    if (c == '\n' || c == '\r') {
      if (btBuf.length() > 0) {
        String line = btBuf;
        btBuf = "";
        handleBtLine(line);
      }
    } else if (btBuf.length() < 160) {
      btBuf += c;
    }
  }

  // App sent text without a newline: finish the line after a short pause
  if (btBuf.length() > 0 && (millis() - lastBtByteTime) > BT_IDLE_FLUSH_MS) {
    String line = btBuf;
    btBuf = "";
    handleBtLine(line);
  }
}

void sendStatus() {
  String lat, lon, src;
  if (!getLocationNow(lat, lon, src)) {
    src = "NONE";
    lat = "NA";
    lon = "NA";
  }
  btSend("STATUS," + MY_ID + "," + src + "," + lat + "," + lon + "," +
         String(fallActive ? 1 : 0) + "," + String(sosAlertActive ? 1 : 0));
}

void handleBluetoothConnection() {
  bool connected = SerialBT.hasClient();
  if (connected && !btWasConnected) {
    Serial.println("[BT] phone connected");
    btSend("HELLO,NODE " + MY_ID);
    sendStatus();
  } else if (!connected && btWasConnected) {
    Serial.println("[BT] phone disconnected");
  }
  btWasConnected = connected;

  if (connected && millis() - lastStatus > 5000UL) {
    lastStatus = millis();
    sendStatus();
  }
}

// Location for an SOS: NEO-6M, else phone. If neither is available, ask the
// phone for its location and wait a short time for the answer.
bool getLocationForSos(String &lat, String &lon, String &src) {
  if (getLocationNow(lat, lon, src)) return true;

  if (SerialBT.hasClient()) {
    notifyPhone("NEED_GPS");
    unsigned long before = phoneGpsTime;
    unsigned long start = millis();
    while (millis() - start < PHONE_GPS_WAIT_MS) {
      pollGps();
      btReadLines();
      pollLoRa();
      updateIndicators();
      if (phoneGpsFresh() && phoneGpsTime != before) break;
      delay(5);
    }
    return getLocationNow(lat, lon, src);
  }
  return false;
}

// ====================== BUZZER / LED ======================
void beepFor(unsigned long ms) {
  beepUntil = millis() + ms;
}

void updateIndicators() {
  unsigned long now = millis();
  bool on = false;

  if (fallActive) {
    unsigned long elapsed = now - fallStart;
    unsigned long remaining = (elapsed >= FALL_COUNTDOWN_MS) ? 0 : (FALL_COUNTDOWN_MS - elapsed);
    unsigned long period = (remaining < 20000UL) ? 300 : 1000;
    on = (now % period) < 150;
  } else if (sosAlertActive) {
    on = (now % 1000) < 500;
  } else if (now < beepUntil) {
    on = true;
  }

  digitalWrite(BUZZER_PIN, on ? HIGH : LOW);
  digitalWrite(LED_PIN, on ? HIGH : LOW);
}

// ====================== LORA: LOW LEVEL ======================
void sendRaw(const String &s) {
  LoRa.beginPacket();
  LoRa.print(s);
  LoRa.endPacket();
  LoRa.receive();
  Serial.println("[TX] " + s);
}

void sendAck(const String &to, unsigned long id) {
  sendRaw("A|" + MY_ID + "|" + to + "|" + String(id));
}

bool decodePacket(const String &raw, Packet &p) {
  int i1 = raw.indexOf('|');
  if (i1 != 1) return false;
  int i2 = raw.indexOf('|', i1 + 1);
  if (i2 < 0) return false;
  int i3 = raw.indexOf('|', i2 + 1);
  if (i3 < 0) return false;

  p.type = raw.charAt(0);
  p.from = raw.substring(i1 + 1, i2);
  p.to   = raw.substring(i2 + 1, i3);

  if (p.type == 'D') {
    int i4 = raw.indexOf('|', i3 + 1);
    if (i4 < 0) return false;
    p.id = raw.substring(i3 + 1, i4).toInt();
    p.payload = raw.substring(i4 + 1);
    return true;
  } else if (p.type == 'A' || p.type == 'R') {
    p.id = raw.substring(i3 + 1).toInt();
    p.payload = "";
    return true;
  }
  return false;
}

// ====================== LORA: HANDLE INCOMING ======================
void handleIncomingSos(const Packet &p) {
  String rest = p.payload.substring(4);              // after "SOS,"
  int c = rest.indexOf(',');
  sosReason = (c < 0) ? rest : rest.substring(0, c);
  String loc = (c < 0) ? "" : rest.substring(c + 1);

  sosHasGps = false;
  sosLat = "";
  sosLon = "";
  sosSrc = "";
  if (loc.length() > 0 && !loc.startsWith("GPS_NO_FIX")) {
    int c2 = loc.indexOf(',');
    if (c2 > 0) {
      sosLat = loc.substring(0, c2);
      String r2 = loc.substring(c2 + 1);
      int c3 = r2.indexOf(',');
      if (c3 < 0) {
        sosLon = r2;
        sosSrc = "GPS";
      } else {
        sosLon = r2.substring(0, c3);
        sosSrc = r2.substring(c3 + 1);
      }
      sosHasGps = true;
    }
  }

  sosId = p.id;
  sosFrom = p.from;
  sosAlertActive = true;
  tempUntil = 0;

  Serial.println("!!! SOS from " + sosFrom + " reason=" + sosReason);
  if (sosHasGps) {
    Serial.println("    Location (" + sosSrc + "): " + sosLat + "," + sosLon);
    Serial.println("    Map: https://maps.google.com/?q=" + sosLat + "," + sosLon);
  } else {
    Serial.println("    Location: GPS_NO_FIX");
  }
  Serial.println("    Triple-press the button to confirm you read it.");

  notifyPhone("SOS_IN," + sosFrom + "," + sosReason + "," +
              (sosHasGps ? sosLat : String("NA")) + "," +
              (sosHasGps ? sosLon : String("NA")) + "," +
              (sosHasGps ? sosSrc : String("NONE")));
}

void handleIncomingText(const Packet &p) {
  Serial.println("[MSG from " + p.from + "] " + p.payload);
  notifyPhone("MSG_IN," + p.from + "," + p.payload);
  String l1 = p.from + ":" + p.payload.substring(0, 14);
  String l2 = (p.payload.length() > 14) ? p.payload.substring(14, 30) : "";
  showTemp(l1, l2, 8000);
  beepFor(200);
}

void handlePacket(const Packet &p, int rssi) {
  Serial.println("[RX] type=" + String(p.type) + " from=" + p.from + " id=" + String(p.id) +
                 " rssi=" + String(rssi));

  if (p.type == 'A') {
    lastAckId = p.id;
    return;
  }

  if (p.type == 'D') {
    sendAck(p.from, p.id);
    if (p.id == lastRxId) {
      Serial.println("[RX] duplicate ignored");
      return;
    }
    lastRxId = p.id;
    if (p.payload.startsWith("SOS,")) handleIncomingSos(p);
    else handleIncomingText(p);
    return;
  }

  if (p.type == 'R') {
    sendAck(p.from, p.id);
    if (lastSentSosId != 0 && p.id == lastSentSosId) {
      Serial.println("[SOS READ] Node " + p.from + " has read your SOS.");
      notifyPhone("SOS_READ," + p.from);
      showTemp("SOS READ by " + p.from, "Help is aware", 10000);
      beepFor(1000);
    }
    return;
  }
}

void pollLoRa() {
  int size = LoRa.parsePacket();
  if (size == 0) return;

  String raw = "";
  while (LoRa.available()) raw += (char)LoRa.read();
  int rssi = LoRa.packetRssi();

  Packet p;
  if (!decodePacket(raw, p)) {
    Serial.println("[RX] ignored (bad format): " + raw);
    return;
  }
  if (p.to != MY_ID || p.from != PEER_ID) return;
  handlePacket(p, rssi);
}

// ====================== RELIABLE SEND (ACK + RETRY) ======================
// Sends immediately, waits up to 1.5 s for the matching ACK, max 4 attempts.
bool sendReliable(const String &packet, unsigned long expectedId) {
  lastAckId = 0;

  for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
    Serial.println("[SEND] attempt " + String(attempt) + "/" + String(MAX_ATTEMPTS));
    sendRaw(packet);

    unsigned long start = millis();
    while (millis() - start < ACK_WAIT_MS) {
      pollLoRa();
      pollGps();
      btReadLines();
      updateIndicators();
      if (lastAckId == expectedId) {
        Serial.println("[SEND] ACK received");
        return true;
      }
      delay(1);
    }
  }
  Serial.println("[SEND] FAILED - no ACK");
  return false;
}

unsigned long newMsgId() {
  nextMsgId++;
  if (nextMsgId == 0) nextMsgId = 1;
  return nextMsgId;
}

// ====================== SEND TEXT / SOS ======================
void sendText(String text) {
  text.trim();
  text.replace('|', '/');
  if (text.length() == 0) return;
  if (text.length() > MAX_TEXT_LEN) text = text.substring(0, MAX_TEXT_LEN);

  unsigned long id = newMsgId();
  String packet = "D|" + MY_ID + "|" + PEER_ID + "|" + String(id) + "|" + text;

  showTemp("Sending...", text.substring(0, 16), 6000);
  if (sendReliable(packet, id)) {
    showTemp("Message sent", "ACK received", 3000);
    notifyPhone("MSG_SENT,OK");
  } else {
    showTemp("Send FAILED", "No ACK", 4000);
    notifyPhone("MSG_SENT,FAIL");
  }
}

void sendSos(const String &reason) {
  showTemp("SENDING SOS...", reason, 7000);
  beepFor(300);

  String lat, lon, src;
  bool haveLoc = getLocationForSos(lat, lon, src);     // NEO-6M, else phone
  String loc = haveLoc ? (lat + "," + lon + "," + src) : String("GPS_NO_FIX");

  unsigned long id = newMsgId();
  lastSentSosId = id;
  String payload = "SOS," + reason + "," + loc;
  String packet = "D|" + MY_ID + "|" + PEER_ID + "|" + String(id) + "|" + payload;

  Serial.println("[SOS] sending: " + payload);
  notifyPhone("SOS_SENDING," + reason + "," +
              (haveLoc ? lat : String("NA")) + "," +
              (haveLoc ? lon : String("NA")) + "," +
              (haveLoc ? src : String("NONE")));

  if (sendReliable(packet, id)) {
    showTemp("SOS DELIVERED", "Wait for READ", 6000);
    notifyPhone("SOS_SENT,OK");
  } else {
    showTemp("SOS NOT ACKED!", "Press to retry", 6000);
    notifyPhone("SOS_SENT,FAIL");
  }
}

void confirmSosRead() {
  if (!sosAlertActive) {
    showTemp("No SOS to read", "", 2000);
    return;
  }
  String packet = "R|" + MY_ID + "|" + sosFrom + "|" + String(sosId);
  showTemp("Sending READ...", "", 6000);

  if (sendReliable(packet, sosId)) {
    sosAlertActive = false;
    showTemp("READ sent", "SOS acknowledged", 4000);
    notifyPhone("READ_SENT,OK");
  } else {
    showTemp("READ not sent", "3 presses=retry", 4000);   // alert stays active
    notifyPhone("READ_SENT,FAIL");
  }
}

// ====================== BUTTON ======================
// 1 press = SOS, 2 presses = nothing, 3 presses = confirm SOS read.
void handleButton() {
  unsigned long now = millis();
  bool reading = digitalRead(BUTTON_PIN);

  if (reading != lastReading) {
    lastDebounceTime = now;
    lastReading = reading;
  }

  if ((now - lastDebounceTime) > DEBOUNCE_MS && reading != stableState) {
    stableState = reading;
    if (stableState == LOW) {
      pressCount++;
    } else {
      lastReleaseTime = now;
    }
  }

  if (pressCount > 0 && stableState == HIGH && (now - lastReleaseTime) > MULTI_PRESS_GAP_MS) {
    int count = pressCount;
    pressCount = 0;
    Serial.println("[BTN] presses = " + String(count));

    if (count == 1) {
      sendSos("MANUAL");
    } else if (count == 2) {
      Serial.println("[BTN] two presses: ignored");
    } else if (count == 3) {
      confirmSosRead();
    } else {
      Serial.println("[BTN] too many presses: ignored");
    }
  }
}

// ====================== FALL DETECTION ======================
// Logic: sudden hard impact (>= 2.8 g) -> if there is NO movement for 120 s,
// an SOS is sent automatically. Sustained movement cancels it.
void startFallCountdown() {
  fallActive = true;
  fallStart = millis();
  moveHits = 0;
  lastMoveHitTime = 0;
  notifyPhone("FALL,START," + String(FALL_COUNTDOWN_MS / 1000));
}

void handleFallDetection() {
  if (!mpuReady) return;
  unsigned long now = millis();
  if (now - lastImuRead < IMU_INTERVAL_MS) return;
  lastImuRead = now;

  sensors_event_t a, g, t;
  mpu.getEvent(&a, &g, &t);

  float accelG = sqrt(a.acceleration.x * a.acceleration.x +
                      a.acceleration.y * a.acceleration.y +
                      a.acceleration.z * a.acceleration.z) / 9.80665f;
  float gyroMag = sqrt(g.gyro.x * g.gyro.x +
                       g.gyro.y * g.gyro.y +
                       g.gyro.z * g.gyro.z);

  if (!fallActive) {
    if (now > fallCooldownUntil && accelG >= FALL_IMPACT_G) {
      Serial.println("[FALL] impact " + String(accelG, 2) + " g - waiting for movement");
      startFallCountdown();
    }
    return;
  }

  unsigned long elapsed = now - fallStart;

  if (elapsed >= FALL_GRACE_MS) {
    bool moving = (fabs(accelG - 1.0f) > MOVE_ACCEL_DELTA_G) || (gyroMag > MOVE_GYRO_RAD_S);
    if (moving) {
      moveHits++;
      lastMoveHitTime = now;
    } else if (moveHits > 0 && (now - lastMoveHitTime) > 1000) {
      moveHits = 0;                                  // movement must be sustained
    }

    if (moveHits >= MOVE_HITS_NEEDED) {
      fallActive = false;
      fallCooldownUntil = now + FALL_COOLDOWN_MS;
      Serial.println("[FALL] movement detected - cancelled");
      notifyPhone("FALL,CANCEL");
      showTemp("Fall cancelled", "Movement seen", 4000);
      return;
    }
  }

  if (elapsed >= FALL_COUNTDOWN_MS) {
    fallActive = false;
    Serial.println("[FALL] no movement - sending SOS");
    notifyPhone("FALL,SOS");
    sendSos("FALL");
    fallCooldownUntil = millis() + FALL_COOLDOWN_MS;
  }
}

// ====================== DISPLAY ======================
void updateDisplay() {
  unsigned long now = millis();
  if (now - lastDisplayUpdate < 250) return;
  lastDisplayUpdate = now;

  if (now < tempUntil) {
    lcdWrite(temp1, temp2);
    return;
  }

  if (fallActive) {
    unsigned long elapsed = now - fallStart;
    int secs = (elapsed >= FALL_COUNTDOWN_MS) ? 0 : (int)((FALL_COUNTDOWN_MS - elapsed) / 1000);
    char buf[20];
    snprintf(buf, sizeof(buf), "SOS in %3ds MOVE", secs);
    lcdWrite("FALL DETECTED!", String(buf));
    return;
  }

  if (sosAlertActive) {
    bool page = ((now / 3000) % 2) == 0;
    if (page || !sosHasGps) {
      lcdWrite("!!SOS from " + sosFrom + "!!",
               sosHasGps ? (sosSrc + " 3x=read") : String("No GPS 3x=read"));
    } else {
      lcdWrite(sosLat, sosLon);
    }
    return;
  }

  String gpsTxt = gpsHasFix() ? "FIX" : (phoneGpsFresh() ? "PHN" : "---");
  String btTxt  = SerialBT.hasClient() ? "BT:ON " : "BT:off";
  lcdWrite("Node " + MY_ID + " GPS:" + gpsTxt, btTxt + " Ready");
}

// ====================== COMMANDS (Serial Monitor + Bluetooth) ======================
void processCommand(String line) {
  line.trim();
  if (line.length() == 0) return;

  String up = line;
  up.toUpperCase();

  if (up == "/SOS" || up == "SOS") {
    sendSos("MANUAL");
  } else if (up == "/FALL") {                 // test only
    if (!fallActive) {
      Serial.println("[FALL] simulated fall");
      startFallCountdown();
    }
  } else if (up == "/READ" || up == "READ") {
    confirmSosRead();
  } else if (up == "PING") {
    notifyPhone("PONG");
  } else if (up.startsWith("GPS,")) {
    handlePhoneGps(line);
  } else if (up.startsWith("MSG,") || up.startsWith("MSG:")) {
    sendText(line.substring(4));
  } else {
    sendText(line);                           // plain text = message
  }
}

void handleSerial() {
  while (Serial.available()) {
    char c = Serial.read();
    if (c == '\n' || c == '\r') {
      if (serialBuf.length() > 0) {
        String line = serialBuf;
        serialBuf = "";
        processCommand(line);
      }
    } else if (serialBuf.length() < 150) {
      serialBuf += c;
    }
  }
}

void runPendingCommand() {
  if (pendingCmd.length() == 0) return;
  String line = pendingCmd;
  pendingCmd = "";
  processCommand(line);
}

// ====================== SETUP ======================
void setup() {
  Serial.begin(115200);
  delay(300);
  Serial.println();
  Serial.println("=== LoRa Trekker Node " + MY_ID + " ===");

  pinMode(BUTTON_PIN, INPUT_PULLUP);
  pinMode(BUZZER_PIN, OUTPUT);
  pinMode(LED_PIN, OUTPUT);
  digitalWrite(BUZZER_PIN, LOW);
  digitalWrite(LED_PIN, LOW);

  randomSeed(esp_random());
  nextMsgId = random(1000, 900000);

  // I2C + LCD (address is detected automatically)
  Wire.begin(I2C_SDA, I2C_SCL);
  delay(100);
  uint8_t found = i2cScan();
  if (found != 0) {
    lcdAddr = found;
    Serial.println("[LCD] using address 0x" + String(lcdAddr, HEX));
  } else {
    lcdAddr = 0x27;
    Serial.println("[LCD] NOT FOUND on I2C. Check SDA=21, SCL=22, VCC=5V, GND. Trying 0x27 anyway.");
  }
  lcd = new LiquidCrystal_I2C(lcdAddr, 16, 2);
  lcdHardwareInit();
  lcdWrite("Node " + MY_ID, "Starting...");
  delay(800);

  // MPU6050
  if (mpu.begin()) {
    mpu.setAccelerometerRange(MPU6050_RANGE_8_G);    // 2 g range would clip a 2.8 g impact
    mpu.setGyroRange(MPU6050_RANGE_500_DEG);
    mpu.setFilterBandwidth(MPU6050_BAND_44_HZ);
    mpuReady = true;
    Serial.println("[MPU] OK");
  } else {
    mpuReady = false;
    Serial.println("[MPU] NOT FOUND - fall detection disabled");
    showTemp("MPU6050 missing", "No fall detect", 3000);
    delay(1500);
  }

  // GPS
  gpsSerial.begin(9600, SERIAL_8N1, GPS_RX_PIN, GPS_TX_PIN);
  Serial.println("[GPS] UART2 started (RX=16, TX=17)");

  // LoRa
  SPI.begin(LORA_SCK, LORA_MISO, LORA_MOSI, LORA_CS);
  LoRa.setPins(LORA_CS, LORA_RST, LORA_DIO0);
  if (!LoRa.begin(LORA_FREQ)) {
    Serial.println("[LoRa] INIT FAILED - check wiring and 3.3V power");
    lcdWrite("LoRa FAILED", "Check wiring");
    while (true) delay(1000);
  }
  LoRa.setSyncWord(LORA_SYNC);
  LoRa.setSpreadingFactor(LORA_SF);
  LoRa.setSignalBandwidth(LORA_BW);
  LoRa.setCodingRate4(LORA_CR);
  LoRa.setTxPower(LORA_TX_POWER);
  LoRa.enableCrc();
  LoRa.receive();
  Serial.println("[LoRa] OK");

  // Bluetooth
  String btName = "NODE " + MY_ID;
  if (SerialBT.begin(btName)) {
    Serial.println("[BT] started, device name: " + btName);
  } else {
    Serial.println("[BT] FAILED to start");
  }

  Serial.println("Type a message + Enter to send. Commands: /sos  /fall  /read");
  showTemp("Node " + MY_ID + " ready", "BT: " + btName, 2500);
  beepFor(150);
}

// ====================== LOOP ======================
void loop() {
  pollGps();
  pollLoRa();
  btReadLines();
  handleButton();
  handleFallDetection();
  handleSerial();
  runPendingCommand();
  handleBluetoothConnection();
  updateIndicators();
  lcdMaintenance();
  updateDisplay();

  if (millis() - lastGpsDebug > 10000UL) {
    lastGpsDebug = millis();
    Serial.println("[GPS] chars=" + String(gps.charsProcessed()) +
                   " fixSentences=" + String(gps.sentencesWithFix()) +
                   " sats=" + String(gps.satellites.isValid() ? gps.satellites.value() : 0) +
                   " fix=" + String(gpsHasFix() ? "YES" : "NO") +
                   " phoneGps=" + String(phoneGpsFresh() ? "YES" : "NO"));
  }
}