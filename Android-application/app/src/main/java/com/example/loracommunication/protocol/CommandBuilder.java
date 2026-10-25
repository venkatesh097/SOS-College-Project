package com.example.loracommunication.protocol;

import java.util.Locale;

/**
 * Pure Java builder for commands sent from the Android phone to the ESP32 LoRa node.
 * No Android dependencies permitted.
 */
public final class CommandBuilder {

    private CommandBuilder() {
        // Prevent instantiation
    }

    public static String msg(String text) {
        return LoraProtocol.buildMsgCommand(text);
    }

    public static String sos() {
        return LoraProtocol.buildSosCommand();
    }

    public static String status() {
        return LoraProtocol.buildStatusCommand();
    }

    public static String ping() {
        return LoraProtocol.buildPingCommand();
    }

    public static String read() {
        return LoraProtocol.buildReadCommand();
    }

    /**
     * Builds the LOC command sent from Phone to Node to provide phone GPS fallback coordinates.
     * Format: GPS,<lat>,<lon> (decimal degrees, 6 decimal places, US locale).
     *
     * @param lat latitude between -90.0 and 90.0
     * @param lon longitude between -180.0 and 180.0
     * @return formatted string "GPS,%.6f,%.6f"
     * @throws IllegalArgumentException if coordinates are NaN, infinite, out of bounds, or exactly 0,0
     */
    public static String loc(double lat, double lon) {
        if (Double.isNaN(lat) || Double.isNaN(lon) || Double.isInfinite(lat) || Double.isInfinite(lon)) {
            throw new IllegalArgumentException("Coordinates cannot be NaN or Infinite");
        }
        if (lat < -90.0 || lat > 90.0) {
            throw new IllegalArgumentException("Latitude out of range [-90, 90]: " + lat);
        }
        if (lon < -180.0 || lon > 180.0) {
            throw new IllegalArgumentException("Longitude out of range [-180, 180]: " + lon);
        }
        if (Math.abs(lat) < 0.000001 && Math.abs(lon) < 0.000001) {
            throw new IllegalArgumentException("Coordinates cannot be exactly (0.0, 0.0)");
        }
        return String.format(Locale.US, "GPS,%.6f,%.6f", lat, lon);
    }
}
