/* ESP32-WROOM-32 + TWO BNO055 breakouts in UART mode (PS1=1, PS0=0).
 * Reference: Bosch BST-BNO055-DS000-18 sections 4.5, 4.7, 5.3.
 * Acquisition scaffold only: not flashed or tested on physical hardware.
 * UART pins: sensor SDA/COM0 = TX, sensor SCL/COM1 = RX.
 * No external Arduino libraries; install Espressif's Arduino ESP32 core.
 */
#include <Arduino.h>
#include <math.h>

HardwareSerial &parentPort = Serial1;
HardwareSerial &childPort = Serial2;
bool parentReady = false, childReady = false;
uint32_t sequenceNo = 0, lastSampleMs = 0;

void discardRx(HardwareSerial &port) { while (port.available()) port.read(); }

bool readRegister(HardwareSerial &port, uint8_t reg, uint8_t *data, uint8_t length) {
  for (int attempt = 0; attempt < 2; ++attempt) {
    discardRx(port);
    uint8_t command[] = {0xAA, 0x01, reg, length};
    port.write(command, sizeof(command)); port.flush();
    uint8_t header[2];
    if (port.readBytes(header, 2) == 2 && header[0] == 0xBB && header[1] == length &&
        port.readBytes(data, length) == length) return true;
    delay(5);
  }
  return false;
}

bool writeRegister(HardwareSerial &port, uint8_t reg, uint8_t value) {
  for (int attempt = 0; attempt < 2; ++attempt) {
    discardRx(port);
    uint8_t command[] = {0xAA, 0x00, reg, 1, value};
    port.write(command, sizeof(command)); port.flush();
    uint8_t header[2];
    if (port.readBytes(header, 2) == 2 && header[0] == 0xEE && header[1] == 1) return true;
    delay(5);
  }
  return false;
}

bool initializeSensor(HardwareSerial &port) {
  uint8_t id;
  if (!readRegister(port, 0x00, &id, 1) || id != 0xA0) return false;
  if (!writeRegister(port, 0x3D, 0x00)) return false; // CONFIGMODE
  delay(30);
  if (!writeRegister(port, 0x07, 0x00)) return false; // PAGE_ID=0
  if (!writeRegister(port, 0x3E, 0x00)) return false; // normal power
  if (!writeRegister(port, 0x3D, 0x0C)) return false; // NDOF
  delay(30);
  return true;
}

void emitSensor(HardwareSerial &port, const char *name, bool initialized) {
  uint8_t data[8], calibration = 0;
  bool ok = initialized && readRegister(port, 0x20, data, 8) && readRegister(port, 0x35, &calibration, 1);
  float q[4] = {0, 0, 0, 0};
  if (ok) {
    for (int i = 0; i < 4; ++i) q[i] = (int16_t)((uint16_t)data[2*i] | ((uint16_t)data[2*i+1] << 8)) / 16384.0f;
    float norm = 0;
    for (float value : q) norm += value * value;
    ok = isfinite(norm) && norm > 0.8f && norm < 1.2f;
  }
  Serial.printf("\"%s\":{\"ok\":%s,\"sample_ms\":%lu,\"quaternion_wxyz\":", name, ok?"true":"false", (unsigned long)millis());
  if (ok) Serial.printf("[%.6f,%.6f,%.6f,%.6f]", q[0],q[1],q[2],q[3]);
  else Serial.print("null");
  Serial.printf(",\"calibration\":{\"system\":%u,\"gyro\":%u,\"accel\":%u,\"mag\":%u}}",
                (calibration>>6)&3,(calibration>>4)&3,(calibration>>2)&3,calibration&3);
}

void setup() {
  Serial.begin(115200); // USB bridge / UART0 to PC
  parentPort.begin(115200, SERIAL_8N1, 16, 17); // RX=16, TX=17
  childPort.begin(115200, SERIAL_8N1, 26, 27);  // RX=26, TX=27
  parentPort.setTimeout(100); childPort.setTimeout(100);
  delay(1200); // Allow sensor power-on boot.
  parentReady = initializeSensor(parentPort);
  childReady = initializeSensor(childPort);
}

void loop() {
  if (millis() - lastSampleMs < 50) { delay(1); return; }
  lastSampleMs = millis();
  Serial.printf("{\"schema\":\"bno055-uart-pair-v1\",\"sequence\":%lu,\"uptime_ms\":%lu,", (unsigned long)sequenceNo++, (unsigned long)millis());
  emitSensor(parentPort, "parent", parentReady); Serial.print(',');
  emitSensor(childPort, "child", childReady); Serial.println('}');
}
