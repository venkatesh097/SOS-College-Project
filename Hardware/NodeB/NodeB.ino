#include <Wire.h>
#include <SPI.h>
#include <LoRa.h>
#include <LiquidCrystal_I2C.h>
#include <TinyGPSPlus.h>
#include <Adafruit_MPU6050.h>
#include <Adafruit_Sensor.h>
#include "BluetoothSerial.h"

// =====================================================
// NODE CONFIGURATION
// =====================================================

#define NODE_NAME "NODE B"

// For Node B:
// #define NODE_NAME "NODE B"


// =====================================================
// BLUETOOTH
// =====================================================

BluetoothSerial SerialBT;

String bluetoothBuffer = "";

const unsigned int BLUETOOTH_BUFFER_LIMIT = 200;


// =====================================================
// I2C PINS
// =====================================================

#define SDA_PIN 21
#define SCL_PIN 22


// =====================================================
// LCD
// =====================================================

LiquidCrystal_I2C lcd(0x27, 16, 2);

// If your LCD address is 0x3F,
// change 0x27 to 0x3F.


// =====================================================
// SOS BUTTON
// =====================================================

#define SOS_BUTTON 25


// =====================================================
// BUZZER
// =====================================================

#define BUZZER_PIN 27


// =====================================================
// GPS
// =====================================================

#define GPS_RX 16
#define GPS_TX 17

HardwareSerial GPSserial(2);

TinyGPSPlus gps;


// =====================================================
// MPU6050
// =====================================================

Adafruit_MPU6050 mpu;

bool mpuAvailable = false;


// =====================================================
// LoRa PINS
// =====================================================

#define LORA_SCK 18
#define LORA_MISO 19
#define LORA_MOSI 23

#define LORA_SS 5
#define LORA_RST 14
#define LORA_DIO0 26

#define LORA_FREQUENCY 433E6


// =====================================================
// BUTTON
// =====================================================

bool buttonHandled = false;

unsigned long buttonDebounceTime = 0;

const unsigned long BUTTON_DEBOUNCE = 50;


// =====================================================
// MESSAGE ID
// =====================================================

unsigned long messageID = 1;


// =====================================================
// FALL DETECTION SETTINGS
// =====================================================

// High impact required to start fall monitoring.

const float IMPACT_THRESHOLD_G = 2.70;

const float GRAVITY = 9.80665;


// =====================================================
// INACTIVITY PERIOD
// =====================================================

// 3 minutes after high-impact event.

const unsigned long INACTIVITY_TIME = 180000UL;


// =====================================================
// MOVEMENT THRESHOLD
// =====================================================

// Movement greater than or equal to this
// cancels fall monitoring.

const float MOVEMENT_THRESHOLD_G = 0.35;


// =====================================================
// MPU SAMPLING
// =====================================================

const unsigned long MPU_SAMPLE_INTERVAL = 100UL;

unsigned long lastMPUSampleTime = 0;


// =====================================================
// FALL STATES
// =====================================================

enum FallState
{
  FALL_NORMAL,
  FALL_MONITORING
};

FallState fallState = FALL_NORMAL;


// =====================================================
// FALL TIMERS
// =====================================================

unsigned long impactTime = 0;


// =====================================================
// PREVIOUS ACCELERATION
// =====================================================

float previousAccelerationG = 1.0;

bool previousAccelerationValid = false;


// =====================================================
// LCD FUNCTION
// =====================================================

void lcdShow(String line1, String line2)
{
  lcd.clear();

  lcd.setCursor(0, 0);

  lcd.print(
    line1.substring(
      0,
      16
    )
  );

  lcd.setCursor(0, 1);

  lcd.print(
    line2.substring(
      0,
      16
    )
  );
}


// =====================================================
// BUZZER
// =====================================================

void beepBuzzer(int times, int duration)
{
  for (int i = 0; i < times; i++)
  {
    digitalWrite(
      BUZZER_PIN,
      HIGH
    );

    delay(duration);

    digitalWrite(
      BUZZER_PIN,
      LOW
    );

    delay(duration);
  }
}


// =====================================================
// GPS UPDATE
// =====================================================

void updateGPS()
{
  while (GPSserial.available())
  {
    gps.encode(
      GPSserial.read()
    );
  }
}


// =====================================================
// GET LATITUDE
// =====================================================

String getLatitude()
{
  if (gps.location.isValid())
  {
    return String(
      gps.location.lat(),
      6
    );
  }

  return "INVALID";
}


// =====================================================
// GET LONGITUDE
// =====================================================

String getLongitude()
{
  if (gps.location.isValid())
  {
    return String(
      gps.location.lng(),
      6
    );
  }

  return "INVALID";
}


// =====================================================
// BLUETOOTH SEND
// =====================================================

void bluetoothSend(String data)
{
  if (SerialBT.hasClient())
  {
    SerialBT.println(data);
  }
}


// =====================================================
// BLUETOOTH READY MESSAGE
// =====================================================

void sendBluetoothReady()
{
  bluetoothSend(
    "READY|" + String(NODE_NAME)
  );
}


// =====================================================
// BLUETOOTH STATUS
// =====================================================

void sendBluetoothStatus()
{
  String status = "STATUS|";
  
  status += NODE_NAME;
  status += "|MPU=";
  status += mpuAvailable ? "READY" : "NOT_FOUND";

  status += "|GPS=";
  status += gps.location.isValid() ? "FIX" : "NO_FIX";

  status += "|LORA=READY";

  bluetoothSend(status);
}


// =====================================================
// SEND NORMAL MESSAGE
// =====================================================
//
// MSG|NODE A|ID|LAT|LON|MESSAGE
//
// =====================================================

void sendMessage(String message)
{
  String latitude = getLatitude();
  String longitude = getLongitude();

  String packet = "MSG|";

  packet += NODE_NAME;
  packet += "|";

  packet += String(messageID++);
  packet += "|";

  packet += latitude;
  packet += "|";

  packet += longitude;
  packet += "|";

  packet += message;


  // ===================================================
  // SERIAL DEBUG
  // ===================================================

  Serial.println();
  Serial.println("================================");
  Serial.println("SENDING MESSAGE");
  Serial.println("================================");

  Serial.print("FROM: ");
  Serial.println(NODE_NAME);

  Serial.print("MESSAGE: ");
  Serial.println(message);

  Serial.print("LATITUDE: ");
  Serial.println(latitude);

  Serial.print("LONGITUDE: ");
  Serial.println(longitude);

  Serial.print("PACKET: ");
  Serial.println(packet);


  // ===================================================
  // LCD
  // ===================================================

  lcdShow(
    "MESSAGE",
    "Sending..."
  );


  // ===================================================
  // LoRa TRANSMISSION
  // ===================================================

  LoRa.beginPacket();

  LoRa.print(packet);

  LoRa.endPacket();


  // ===================================================
  // BLUETOOTH
  // ===================================================

  bluetoothSend(
    "TX|" + packet
  );


  Serial.println("MESSAGE SENT");


  lcdShow(
    "MESSAGE SENT",
    "LoRa transmitted"
  );

  delay(1500);

  lcdShow(
    NODE_NAME,
    "READY"
  );
}


// =====================================================
// SEND MANUAL SOS
// =====================================================
//
// SOS|NODE A|ID|LAT|LON
//
// =====================================================

void sendSOS()
{
  String latitude = getLatitude();
  String longitude = getLongitude();

  String packet = "SOS|";

  packet += NODE_NAME;
  packet += "|";

  packet += String(messageID++);
  packet += "|";

  packet += latitude;
  packet += "|";

  packet += longitude;


  Serial.println();
  Serial.println("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
  Serial.println("MANUAL SOS ACTIVATED");
  Serial.println("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");

  Serial.print("FROM: ");
  Serial.println(NODE_NAME);

  Serial.print("LATITUDE: ");
  Serial.println(latitude);

  Serial.print("LONGITUDE: ");
  Serial.println(longitude);

  Serial.print("PACKET: ");
  Serial.println(packet);


  // ===================================================
  // LCD
  // ===================================================

  lcdShow(
    "!!! SOS !!!",
    "Sending..."
  );


  // ===================================================
  // MANUAL SOS SOUND
  // ===================================================

  beepBuzzer(
    2,
    150
  );


  // ===================================================
  // LoRa
  // ===================================================

  LoRa.beginPacket();

  LoRa.print(packet);

  LoRa.endPacket();


  // ===================================================
  // BLUETOOTH
  // ===================================================

  bluetoothSend(
    "TX|" + packet
  );


  Serial.println("SOS SENT");


  lcdShow(
    "SOS SENT",
    "LoRa transmitted"
  );

  delay(2000);

  lcdShow(
    NODE_NAME,
    "READY"
  );
}


// =====================================================
// SEND AUTOMATIC FALL SOS
// =====================================================
//
// FALL|NODE A|ID|LAT|LON
//
// =====================================================

void sendFallSOS()
{
  String latitude = getLatitude();
  String longitude = getLongitude();

  String packet = "FALL|";

  packet += NODE_NAME;
  packet += "|";

  packet += String(messageID++);
  packet += "|";

  packet += latitude;
  packet += "|";

  packet += longitude;


  Serial.println();
  Serial.println("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
  Serial.println("FALL CONFIRMED");
  Serial.println("AUTOMATIC FALL SOS");
  Serial.println("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");

  Serial.print("FROM: ");
  Serial.println(NODE_NAME);

  Serial.print("LATITUDE: ");
  Serial.println(latitude);

  Serial.print("LONGITUDE: ");
  Serial.println(longitude);

  Serial.print("PACKET: ");
  Serial.println(packet);


  lcdShow(
    "FALL SOS",
    "Sending..."
  );


  // ===================================================
  // LoRa
  // ===================================================

  LoRa.beginPacket();

  LoRa.print(packet);

  LoRa.endPacket();


  // ===================================================
  // BLUETOOTH
  // ===================================================

  bluetoothSend(
    "TX|" + packet
  );


  Serial.println(
    "FALL SOS SENT"
  );


  // ===================================================
  // AUTOMATIC FALL SOS SOUND
  // ===================================================

  beepBuzzer(
    5,
    250
  );


  lcdShow(
    "FALL SOS SENT",
    "LoRa transmitted"
  );

  delay(2500);

  lcdShow(
    NODE_NAME,
    "READY"
  );
}


// =====================================================
// FALL DETECTION
// =====================================================
//
// NORMAL
//    |
//    | impact >= 2.7G
//    v
// SILENT MONITORING
//    |
//    | movement >= 0.35G
//    v
// CANCEL MONITORING
//
// If no significant movement occurs for 3 minutes:
//    |
//    v
// FALL CONFIRMED
//    |
//    v
// FALL SOS
//
// No buzzer during impact detection.
// No buzzer during cancellation.
// =====================================================

void checkFallDetection()
{
  if (!mpuAvailable)
  {
    return;
  }


  // ===================================================
  // CONTROLLED MPU SAMPLING
  // ===================================================

  if (
    millis() -
    lastMPUSampleTime <
    MPU_SAMPLE_INTERVAL
  )
  {
    return;
  }

  lastMPUSampleTime = millis();


  // ===================================================
  // READ MPU
  // ===================================================

  sensors_event_t accel;
  sensors_event_t gyro;
  sensors_event_t temp;

  mpu.getEvent(
    &accel,
    &gyro,
    &temp
  );


  // ===================================================
  // TOTAL ACCELERATION
  // ===================================================

  float accelerationMagnitude =
    sqrt(
      accel.acceleration.x *
      accel.acceleration.x +

      accel.acceleration.y *
      accel.acceleration.y +

      accel.acceleration.z *
      accel.acceleration.z
    );


  float accelerationG =
    accelerationMagnitude /
    GRAVITY;


  // ===================================================
  // FIRST SENSOR SAMPLE
  // ===================================================

  if (!previousAccelerationValid)
  {
    previousAccelerationG =
      accelerationG;

    previousAccelerationValid =
      true;

    return;
  }


  // ===================================================
  // CHANGE FROM PREVIOUS SAMPLE
  // ===================================================

  float accelerationChangeG =
    fabs(
      accelerationG -
      previousAccelerationG
    );


  previousAccelerationG =
    accelerationG;


  // ===================================================
  // NORMAL STATE
  // ===================================================

  if (
    fallState ==
    FALL_NORMAL
  )
  {
    if (
      accelerationG >=
      IMPACT_THRESHOLD_G
    )
    {
      fallState =
        FALL_MONITORING;

      impactTime =
        millis();


      Serial.println();
      Serial.println(
        "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!"
      );

      Serial.println(
        "HIGH IMPACT DETECTED!"
      );

      Serial.print(
        "Impact: "
      );

      Serial.print(
        accelerationG,
        2
      );

      Serial.println(
        " G"
      );

      Serial.println(
        "Starting SILENT fall monitoring."
      );

      Serial.println(
        "No buzzer during detection."
      );

      Serial.println(
        "Monitoring for 3 minutes."
      );

      Serial.println(
        "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!"
      );


      // =================================================
      // BLUETOOTH EVENT
      // =================================================

      bluetoothSend(
        "FALL_MONITORING|" +
        String(NODE_NAME)
      );


      // =================================================
      // LCD
      // =================================================

      lcdShow(
        "HIGH IMPACT!",
        "Monitoring..."
      );
    }

    return;
  }


  // ===================================================
  // FALL MONITORING STATE
  // ===================================================

  if (
    fallState ==
    FALL_MONITORING
  )
  {
    unsigned long elapsed =
      millis() -
      impactTime;


    // =================================================
    // MOVEMENT DETECTION
    // =================================================

    bool significantMovement =
      accelerationChangeG >=
      MOVEMENT_THRESHOLD_G;


    // =================================================
    // MOVEMENT FOUND
    // =================================================
    //
    // Trekker is moving after the impact.
    // Cancel fall monitoring immediately.
    //
    // No SOS.
    // No buzzer.
    //
    // =================================================

    if (significantMovement)
    {
      Serial.println();
      Serial.println(
        "MOVEMENT DETECTED"
      );

      Serial.print(
        "Movement change: "
      );

      Serial.print(
        accelerationChangeG,
        2
      );

      Serial.println(
        " G"
      );

      Serial.println(
        "Fall monitoring cancelled."
      );


      fallState =
        FALL_NORMAL;


      bluetoothSend(
        "FALL_CANCELLED|" +
        String(NODE_NAME)
      );


      lcdShow(
        "MOVEMENT",
        "FALL CANCELLED"
      );


      delay(1500);


      lcdShow(
        NODE_NAME,
        "READY"
      );


      return;
    }


    // =================================================
    // THREE-MINUTE INACTIVITY
    // =================================================

    if (
      elapsed >=
      INACTIVITY_TIME
    )
    {
      Serial.println();
      Serial.println(
        "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!"
      );

      Serial.println(
        "FALL CONFIRMED"
      );

      Serial.println(
        "No significant movement for 3 minutes."
      );

      Serial.println(
        "Sending automatic FALL SOS."
      );

      Serial.println(
        "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!"
      );


      fallState =
        FALL_NORMAL;


      sendFallSOS();


      return;
    }
  }
}


// =====================================================
// RECEIVE NORMAL MESSAGE
// =====================================================
//
// MSG|NODE A|ID|LAT|LON|MESSAGE
//
// =====================================================

void handleReceivedMessage(String packet)
{
  int p1 =
    packet.indexOf('|');

  int p2 =
    packet.indexOf(
      '|',
      p1 + 1
    );

  int p3 =
    packet.indexOf(
      '|',
      p2 + 1
    );

  int p4 =
    packet.indexOf(
      '|',
      p3 + 1
    );

  int p5 =
    packet.indexOf(
      '|',
      p4 + 1
    );


  if (
    p1 == -1 ||
    p2 == -1 ||
    p3 == -1 ||
    p4 == -1 ||
    p5 == -1
  )
  {
    Serial.println(
      "Invalid message packet."
    );

    return;
  }


  String sender =
    packet.substring(
      p1 + 1,
      p2
    );

  String id =
    packet.substring(
      p2 + 1,
      p3
    );

  String latitude =
    packet.substring(
      p3 + 1,
      p4
    );

  String longitude =
    packet.substring(
      p4 + 1,
      p5
    );

  String message =
    packet.substring(
      p5 + 1
    );


  Serial.println();
  Serial.println(
    "================================"
  );

  Serial.println(
    "MESSAGE RECEIVED"
  );

  Serial.println(
    "================================"
  );

  Serial.print(
    "FROM: "
  );

  Serial.println(sender);

  Serial.print(
    "MESSAGE: "
  );

  Serial.println(message);

  Serial.print(
    "LATITUDE: "
  );

  Serial.println(latitude);

  Serial.print(
    "LONGITUDE: "
  );

  Serial.println(longitude);

  Serial.print(
    "RSSI: "
  );

  Serial.print(
    LoRa.packetRssi()
  );

  Serial.println(
    " dBm"
  );

  Serial.print(
    "SNR: "
  );

  Serial.print(
    LoRa.packetSnr()
  );

  Serial.println(
    " dB"
  );


  // ===================================================
  // SEND TO ANDROID
  // ===================================================

  bluetoothSend(
    "RX|" + packet
  );


  // ===================================================
  // NORMAL MESSAGE SOUND
  // ===================================================

  beepBuzzer(
    2,
    150
  );


  // ===================================================
  // LCD
  // ===================================================

  lcdShow(
    "FROM " + sender,
    "MESSAGE"
  );

  delay(1500);

  lcdShow(
    "MSG:",
    message
  );

  delay(2500);

  lcdShow(
    "LAT:",
    latitude
  );

  delay(2000);

  lcdShow(
    "LON:",
    longitude
  );

  delay(2500);

  lcdShow(
    NODE_NAME,
    "READY"
  );
}


// =====================================================
// RECEIVE MANUAL SOS
// =====================================================
//
// SOS|NODE A|ID|LAT|LON
//
// =====================================================

void handleReceivedSOS(String packet)
{
  int p1 =
    packet.indexOf('|');

  int p2 =
    packet.indexOf(
      '|',
      p1 + 1
    );

  int p3 =
    packet.indexOf(
      '|',
      p2 + 1
    );

  int p4 =
    packet.indexOf(
      '|',
      p3 + 1
    );


  if (
    p1 == -1 ||
    p2 == -1 ||
    p3 == -1 ||
    p4 == -1
  )
  {
    Serial.println(
      "Invalid SOS packet."
    );

    return;
  }


  String sender =
    packet.substring(
      p1 + 1,
      p2
    );

  String id =
    packet.substring(
      p2 + 1,
      p3
    );

  String latitude =
    packet.substring(
      p3 + 1,
      p4
    );

  String longitude =
    packet.substring(
      p4 + 1
    );


  Serial.println();
  Serial.println(
    "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!"
  );

  Serial.println(
    "MANUAL SOS RECEIVED"
  );

  Serial.println(
    "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!"
  );

  Serial.print(
    "FROM: "
  );

  Serial.println(sender);

  Serial.print(
    "LATITUDE: "
  );

  Serial.println(latitude);

  Serial.print(
    "LONGITUDE: "
  );

  Serial.println(longitude);


  // ===================================================
  // SEND TO ANDROID
  // ===================================================

  bluetoothSend(
    "RX|" + packet
  );


  // ===================================================
  // MANUAL SOS RECEIVED
  // ===================================================

  beepBuzzer(
    5,
    300
  );


  lcdShow(
    "SOS FROM",
    sender
  );

  delay(2000);

  lcdShow(
    "SOS RECEIVED",
    "ALERT!"
  );

  delay(2000);

  lcdShow(
    "LAT:",
    latitude
  );

  delay(2000);

  lcdShow(
    "LON:",
    longitude
  );

  delay(2500);

  lcdShow(
    NODE_NAME,
    "READY"
  );
}


// =====================================================
// RECEIVE AUTOMATIC FALL SOS
// =====================================================
//
// FALL|NODE A|ID|LAT|LON
//
// =====================================================

void handleReceivedFall(String packet)
{
  int p1 =
    packet.indexOf('|');

  int p2 =
    packet.indexOf(
      '|',
      p1 + 1
    );

  int p3 =
    packet.indexOf(
      '|',
      p2 + 1
    );

  int p4 =
    packet.indexOf(
      '|',
      p3 + 1
    );


  if (
    p1 == -1 ||
    p2 == -1 ||
    p3 == -1 ||
    p4 == -1
  )
  {
    Serial.println(
      "Invalid FALL packet."
    );

    return;
  }


  String sender =
    packet.substring(
      p1 + 1,
      p2
    );

  String id =
    packet.substring(
      p2 + 1,
      p3
    );

  String latitude =
    packet.substring(
      p3 + 1,
      p4
    );

  String longitude =
    packet.substring(
      p4 + 1
    );


  Serial.println();
  Serial.println(
    "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!"
  );

  Serial.println(
    "AUTOMATIC FALL SOS RECEIVED"
  );

  Serial.println(
    "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!"
  );

  Serial.print(
    "FROM: "
  );

  Serial.println(sender);

  Serial.print(
    "LATITUDE: "
  );

  Serial.println(latitude);

  Serial.print(
    "LONGITUDE: "
  );

  Serial.println(longitude);


  // ===================================================
  // SEND TO ANDROID
  // ===================================================

  bluetoothSend(
    "RX|" + packet
  );


  // ===================================================
  // FALL SOS RECEIVED
  // ===================================================

  beepBuzzer(
    8,
    300
  );


  lcdShow(
    "FALL FROM",
    sender
  );

  delay(2000);

  lcdShow(
    "FALL DETECTED",
    "SOS!"
  );

  delay(2000);

  lcdShow(
    "LAT:",
    latitude
  );

  delay(2000);

  lcdShow(
    "LON:",
    longitude
  );

  delay(2500);

  lcdShow(
    NODE_NAME,
    "READY"
  );
}


// =====================================================
// LoRa RECEIVE
// =====================================================

void processLoRa()
{
  int packetSize =
    LoRa.parsePacket();


  if (!packetSize)
  {
    return;
  }


  String packet = "";


  while (LoRa.available())
  {
    packet +=
      (char)LoRa.read();
  }


  packet.trim();


  Serial.println();
  Serial.println(
    "================================"
  );

  Serial.println(
    "INCOMING LoRa PACKET"
  );

  Serial.println(packet);

  Serial.println(
    "================================"
  );


  // ===================================================
  // NORMAL MESSAGE
  // ===================================================

  if (
    packet.startsWith("MSG|")
  )
  {
    handleReceivedMessage(
      packet
    );

    return;
  }


  // ===================================================
  // MANUAL SOS
  // ===================================================

  if (
    packet.startsWith("SOS|")
  )
  {
    handleReceivedSOS(
      packet
    );

    return;
  }


  // ===================================================
  // AUTOMATIC FALL SOS
  // ===================================================

  if (
    packet.startsWith("FALL|")
  )
  {
    handleReceivedFall(
      packet
    );

    return;
  }


  Serial.println(
    "Unknown packet type."
  );
}


// =====================================================
// BLUETOOTH COMMAND HANDLER
// =====================================================
//
// Android commands:
//
// MSG|Hello
// SOS
// STATUS
// PING
//
// =====================================================

void handleBluetoothCommand(String command)
{
  command.trim();


  if (command.length() == 0)
  {
    return;
  }


  Serial.println();
  Serial.println(
    "BLUETOOTH COMMAND:"
  );

  Serial.println(command);


  // ===================================================
  // PING
  // ===================================================

  if (
    command.equalsIgnoreCase("PING")
  )
  {
    bluetoothSend(
      "PONG|" + String(NODE_NAME)
    );

    return;
  }


  // ===================================================
  // STATUS
  // ===================================================

  if (
    command.equalsIgnoreCase("STATUS")
  )
  {
    sendBluetoothStatus();

    return;
  }


  // ===================================================
  // MANUAL SOS
  // ===================================================

  if (
    command.equalsIgnoreCase("SOS")
  )
  {
    sendSOS();

    return;
  }


  // ===================================================
  // NORMAL MESSAGE
  // ===================================================

  if (
    command.startsWith("MSG|")
  )
  {
    String message =
      command.substring(4);

    message.trim();


    if (message.length() == 0)
    {
      bluetoothSend(
        "ERROR|EMPTY_MESSAGE"
      );

      return;
    }


    sendMessage(
      message
    );

    return;
  }


  // ===================================================
  // UNKNOWN COMMAND
  // ===================================================

  bluetoothSend(
    "ERROR|UNKNOWN_COMMAND"
  );
}


// =====================================================
// BLUETOOTH RECEIVE
// =====================================================
//
// Bluetooth data may arrive in fragments.
//
// Example:
//
// "MS"
// "G|Hello\n"
//
// Therefore we buffer until '\n'.
//
// =====================================================

void processBluetooth()
{
  while (SerialBT.available())
  {
    char c =
      (char)SerialBT.read();


    // =================================================
    // END OF COMMAND
    // =================================================

    if (c == '\n')
    {
      bluetoothBuffer.trim();


      if (
        bluetoothBuffer.length() > 0
      )
      {
        handleBluetoothCommand(
          bluetoothBuffer
        );
      }


      bluetoothBuffer = "";


      continue;
    }


    // Ignore carriage return

    if (c == '\r')
    {
      continue;
    }


    // =================================================
    // ADD CHARACTER
    // =================================================

    bluetoothBuffer += c;


    // =================================================
    // BUFFER PROTECTION
    // =================================================

    if (
      bluetoothBuffer.length() >
      BLUETOOTH_BUFFER_LIMIT
    )
    {
      bluetoothBuffer = "";

      bluetoothSend(
        "ERROR|COMMAND_TOO_LONG"
      );
    }
  }
}


// =====================================================
// SETUP
// =====================================================

void setup()
{
  // ===================================================
  // SERIAL
  // ===================================================

  Serial.begin(
    115200
  );

  delay(500);


  // ===================================================
  // BLUETOOTH
  // ===================================================

  Serial.println(
    "Starting Bluetooth..."
  );


  SerialBT.begin(
    NODE_NAME
  );


  Serial.println(
    "Bluetooth started."
  );

  Serial.print(
    "Bluetooth name: "
  );

  Serial.println(
    NODE_NAME
  );


  // ===================================================
  // BUTTON
  // ===================================================

  pinMode(
    SOS_BUTTON,
    INPUT_PULLUP
  );


  // ===================================================
  // BUZZER
  // ===================================================

  pinMode(
    BUZZER_PIN,
    OUTPUT
  );

  digitalWrite(
    BUZZER_PIN,
    LOW
  );


  // ===================================================
  // I2C
  // ===================================================

  Wire.begin(
    SDA_PIN,
    SCL_PIN
  );


  // ===================================================
  // LCD
  // ===================================================

  lcd.init();

  lcd.backlight();


  lcdShow(
    "TREKKER NODE",
    "Starting..."
  );

  delay(1500);


  // ===================================================
  // MPU6050
  // ===================================================

  Serial.println(
    "Initializing MPU6050..."
  );


  if (
    mpu.begin()
  )
  {
    mpuAvailable =
      true;


    Serial.println(
      "MPU6050 detected."
    );


    mpu.setAccelerometerRange(
      MPU6050_RANGE_8_G
    );


    mpu.setGyroRange(
      MPU6050_RANGE_500_DEG
    );


    mpu.setFilterBandwidth(
      MPU6050_BAND_21_HZ
    );
  }
  else
  {
    mpuAvailable =
      false;


    Serial.println(
      "MPU6050 NOT detected!"
    );

    Serial.println(
      "Fall detection disabled."
    );
  }


  // ===================================================
  // GPS
  // ===================================================

  GPSserial.begin(
    9600,
    SERIAL_8N1,
    GPS_RX,
    GPS_TX
  );


  Serial.println(
    "GPS initialized."
  );


  // ===================================================
  // LoRa SPI
  // ===================================================

  SPI.begin(
    LORA_SCK,
    LORA_MISO,
    LORA_MOSI,
    LORA_SS
  );


  // ===================================================
  // LoRa PINS
  // ===================================================

  LoRa.setPins(
    LORA_SS,
    LORA_RST,
    LORA_DIO0
  );


  // ===================================================
  // LoRa INITIALIZATION
  // ===================================================

  Serial.println(
    "Starting LoRa..."
  );


  if (
    !LoRa.begin(
      LORA_FREQUENCY
    )
  )
  {
    Serial.println(
      "LoRa initialization FAILED!"
    );


    lcdShow(
      "LoRa ERROR",
      "Check wiring"
    );


    while (true)
    {
      digitalWrite(
        BUZZER_PIN,
        HIGH
      );

      delay(200);

      digitalWrite(
        BUZZER_PIN,
        LOW
      );

      delay(1000);
    }
  }


  // ===================================================
  // LoRa SETTINGS
  // ===================================================

  LoRa.setTxPower(
    17
  );

  LoRa.setSpreadingFactor(
    7
  );

  LoRa.setSignalBandwidth(
    125E3
  );

  LoRa.setCodingRate4(
    5
  );


  Serial.println(
    "LoRa initialization successful."
  );


  // ===================================================
  // READY
  // ===================================================

  lcdShow(
    "NODE ONLINE",
    "LoRa: READY"
  );

  delay(1500);


  lcdShow(
    NODE_NAME,
    "READY"
  );


  // ===================================================
  // SYSTEM STATUS
  // ===================================================

  Serial.println();
  Serial.println(
    "================================"
  );

  Serial.println(
    NODE_NAME
  );

  Serial.println(
    "SYSTEM READY"
  );

  Serial.println(
    "================================"
  );


  Serial.print(
    "MPU6050: "
  );

  if (mpuAvailable)
  {
    Serial.println(
      "READY"
    );
  }
  else
  {
    Serial.println(
      "NOT FOUND"
    );
  }


  Serial.println(
    "GPS: READY"
  );

  Serial.println(
    "SOS BUTTON: READY"
  );

  Serial.println(
    "BUZZER: READY"
  );

  Serial.println(
    "LoRa: READY"
  );

  Serial.print(
    "Bluetooth: "
  );

  Serial.println(
    NODE_NAME
  );


  Serial.println();

  Serial.println(
    "Serial message: type text + Enter"
  );

  Serial.println(
    "Serial SOS: type SOS"
  );

  Serial.println();

  Serial.println(
    "Bluetooth commands:"
  );

  Serial.println(
    "MSG|Hello"
  );

  Serial.println(
    "SOS"
  );

  Serial.println(
    "STATUS"
  );

  Serial.println(
    "PING"
  );

  Serial.println();

  Serial.println(
    "FALL DETECTION:"
  );

  Serial.println(
    "Impact threshold: 2.7 G"
  );

  Serial.println(
    "Monitoring time: 3 minutes"
  );

  Serial.println(
    "Movement threshold: 0.35 G"
  );

  Serial.println(
    "Movement after impact: CANCEL"
  );

  Serial.println(
    "Impact sound: DISABLED"
  );

  Serial.println(
    "ACK: DISABLED"
  );

  Serial.println(
    "RETRY: DISABLED"
  );

  Serial.println(
    "================================"
  );


  // ===================================================
  // INFORM ANDROID THAT NODE IS READY
  // ===================================================

  delay(500);

  sendBluetoothReady();
}


// =====================================================
// MAIN LOOP
// =====================================================

void loop()
{
  // ===================================================
  // GPS
  // ===================================================

  updateGPS();


  // ===================================================
  // BLUETOOTH
  // ===================================================

  processBluetooth();


  // ===================================================
  // FALL DETECTION
  // ===================================================

  checkFallDetection();


  // ===================================================
  // PHYSICAL SOS BUTTON
  // ===================================================

  bool buttonState =
    digitalRead(
      SOS_BUTTON
    );


  if (
    buttonState == LOW &&
    !buttonHandled
  )
  {
    if (
      millis() -
      buttonDebounceTime >=
      BUTTON_DEBOUNCE
    )
    {
      buttonHandled =
        true;


      Serial.println(
        "SOS BUTTON PRESSED"
      );


      sendSOS();
    }
  }


  // ===================================================
  // BUTTON RELEASE
  // ===================================================

  if (
    buttonState == HIGH
  )
  {
    buttonDebounceTime =
      millis();

    buttonHandled =
      false;
  }


  // ===================================================
  // SERIAL MONITOR
  // ===================================================

  if (
    Serial.available()
  )
  {
    String message =
      Serial.readStringUntil(
        '\n'
      );


    message.trim();


    if (
      message.length() > 0
    )
    {
      if (
        message.equalsIgnoreCase(
          "SOS"
        )
      )
      {
        sendSOS();
      }
      else
      {
        sendMessage(
          message
        );
      }
    }
  }


  // ===================================================
  // LoRa RECEIVE
  // ===================================================

  processLoRa();
}