//Code for testing ESP32 Connected with LoRa module + LoRa antenna

#include <RadioLib.h>

// Ra-02 / SX1278 connections
// NSS  -> GPIO 5
// DIO0 -> GPIO 26
// RESET -> GPIO 14
// DIO1 -> not connected
SX1278 radio = new Module(5, 26, 14, -1);

void setup() {
  Serial.begin(115200);
  delay(1000);

  Serial.println();
  Serial.println("==============================");
  Serial.println("   Ra-02 SX1278 Test");
  Serial.println("==============================");

  // Initialize SX1278
  int state = radio.begin(433.0);

  if (state == RADIOLIB_ERR_NONE) {
    Serial.println("SUCCESS!");
    Serial.println("Ra-02 / SX1278 detected.");
    Serial.println("ESP32 <-> LoRa SPI communication OK.");
  } 
  else {
    Serial.println("FAILED!");
    Serial.print("Error code: ");
    Serial.println(state);
  }
}

void loop() {
  // Nothing to do for this test
}