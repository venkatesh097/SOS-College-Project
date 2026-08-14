#include <BluetoothSerial.h>
#include <RadioLib.h>

BluetoothSerial SerialBT;

// SX1278
// NSS  = GPIO 5
// DIO0 = GPIO 26
// RESET = GPIO 14
// DIO1 = not connected
SX1278 radio = new Module(5, 26, 14, -1);

// Change this for Node B
const char* NODE_NAME = "LoRa_Node_B";

volatile bool receivedFlag = false;

// Called automatically when LoRa packet is received
void setFlag(void) {
  receivedFlag = true;
}

void setup() {
  Serial.begin(115200);
  delay(1000);

  Serial.println();
  Serial.println("================================");
  Serial.println(" BIDIRECTIONAL LORA NODE");
  Serial.println("================================");

  // -----------------------------
  // Bluetooth
  // -----------------------------
  if (!SerialBT.begin(NODE_NAME)) {
    Serial.println("Bluetooth start failed!");
    while (true) {
      delay(1000);
    }
  }

  Serial.print("Bluetooth started: ");
  Serial.println(NODE_NAME);

  // -----------------------------
  // LoRa
  // -----------------------------
  int state = radio.begin();

  if (state == RADIOLIB_ERR_NONE) {
    Serial.println("LoRa OK");
  } else {
    Serial.print("LoRa initialization failed: ");
    Serial.println(state);

    while (true) {
      delay(1000);
    }
  }

  // Set interrupt for LoRa reception
  radio.setDio0Action(setFlag, RISING);

  // Start receiving
  radio.startReceive();

  Serial.println("LoRa RX ready");
  Serial.println("Waiting for Bluetooth or LoRa messages...");
  Serial.println();
}

void loop() {

  // =====================================================
  // PHONE → BLUETOOTH → ESP32 → LORA
  // =====================================================

  if (SerialBT.available()) {

    String message = SerialBT.readStringUntil('\n');
    message.trim();

    if (message.length() > 0) {

      Serial.print("Bluetooth RX: ");
      Serial.println(message);

      // Send message through LoRa
      int state = radio.transmit(message);

      if (state == RADIOLIB_ERR_NONE) {

        Serial.print("LoRa TX: ");
        Serial.println(message);

        // Tell Android app that transmission succeeded
        SerialBT.println("LoRa TX OK: " + message);

      } els e {

        Serial.print("LoRa TX failed: ");
        Serial.println(state);

        SerialBT.println("LoRa TX FAILED");
      }

      // Return radio to receive mode
      radio.startReceive();
    }
  }


  // =====================================================
  // LORA → ESP32 → BLUETOOTH → PHONE
  // =====================================================

  if (receivedFlag) {

    receivedFlag = false;

    String message;

    int state = radio.readData(message);

    if (state == RADIOLIB_ERR_NONE) {

      Serial.print("LoRa RX: ");
      Serial.println(message);

      // Forward LoRa message to Android
      SerialBT.println(message);

    } else {

      Serial.print("LoRa RX failed: ");
      Serial.println(state);
    }

    // Continue listening
    radio.startReceive();
  }
}