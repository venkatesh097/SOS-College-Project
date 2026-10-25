package com.example.loracommunication.protocol;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Pure Java protocol encoder and decoder for communication between Android phone and ESP32 LoRa node.
 * NO Android imports permitted here.
 */
public final class LoraProtocol {

    public static final String CMD_SOS = "SOS";
    public static final String CMD_STATUS = "STATUS";
    public static final String CMD_PING = "PING";
    public static final String CMD_READ = "READ";
    public static final String CMD_MSG_PREFIX = "MSG,";
    public static final int MAX_MSG_TEXT_LENGTH = 100;
    public static final int MAX_COMMAND_LENGTH = CMD_MSG_PREFIX.length() + MAX_MSG_TEXT_LENGTH; // 104

    public static final String GPS_INVALID = "INVALID";

    // Known node error codes
    public static final String ERROR_EMPTY_MESSAGE = "EMPTY_MESSAGE";
    public static final String ERROR_UNKNOWN_COMMAND = "UNKNOWN_COMMAND";
    public static final String ERROR_COMMAND_TOO_LONG = "COMMAND_TOO_LONG";
    public static final String ERROR_BAD_LOC = "BAD_LOC";
    public static final String ERROR_BUSY = "BUSY";

    private static final AtomicLong packetIdGenerator = new AtomicLong(1);

    private LoraProtocol() {
        // Prevent instantiation
    }

    public static String nextPacketId() {
        return String.valueOf(packetIdGenerator.getAndIncrement());
    }

    public static void resetPacketIdGeneratorForTesting() {
        packetIdGenerator.set(1);
    }

    /**
     * Builds and validates the MSG,<text> command sent from Phone to Node.
     * @param text non-empty text without newlines, max 100 characters
     * @return the formatted command string "MSG,<text>"
     * @throws IllegalArgumentException if text is empty, contains newline, or text exceeds 100 chars.
     */
    public static String buildMsgCommand(String text) {
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("Message text cannot be empty");
        }
        if (text.contains("\n") || text.contains("\r")) {
            throw new IllegalArgumentException("Message text cannot contain newlines");
        }
        if (text.length() > MAX_MSG_TEXT_LENGTH) {
            throw new IllegalArgumentException("Message text exceeds " + MAX_MSG_TEXT_LENGTH + " characters");
        }
        return CMD_MSG_PREFIX + text;
    }

    /**
     * Builds the SOS command.
     */
    public static String buildSosCommand() {
        return CMD_SOS;
    }

    /**
     * Builds the STATUS command.
     */
    public static String buildStatusCommand() {
        return CMD_STATUS;
    }

    /**
     * Builds the PING command.
     */
    public static String buildPingCommand() {
        return CMD_PING;
    }

    /**
     * Builds the READ command sent from Phone to Node to acknowledge SOS/FALL.
     * The command string must be exactly "READ\n".
     */
    public static String buildReadCommand() {
        return CMD_READ + "\n";
    }

    /**
     * Builds the LOC/GPS command sent from Phone to Node to provide phone GPS fallback coordinates.
     */
    public static String buildLocCommand(double lat, double lon) {
        return CommandBuilder.loc(lat, lon);
    }

    /**
     * Parses an incoming raw line received from the ESP32 node.
     *
     * @param rawLine incoming newline-terminated or trimmed line
     * @return ParsedLine representation
     */
    public static ParsedLine parseLine(String rawLine) {
        if (rawLine == null) {
            return ParsedLine.unknown("");
        }

        String line = rawLine.trim();
        if (line.isEmpty()) {
            return ParsedLine.unknown(rawLine);
        }

        // New firmware lines
        if (line.startsWith("HELLO,")) {
            String node = line.substring(6).trim();
            return ParsedLine.ready(node, line);
        }

        if (line.equals("PONG") || line.startsWith("PONG,") || line.startsWith("PONG|")) {
            String node = "";
            if (line.startsWith("PONG,")) {
                node = line.substring(5).trim();
            } else if (line.startsWith("PONG|")) {
                node = line.substring(5).trim();
            }
            return ParsedLine.pong(node, line);
        }

        if (line.startsWith("STATUS,")) {
            // Format: STATUS,A,src,lat,lon,fall,sos (src = GPS | PHONE | NONE)
            String[] parts = line.split(",");
            if (parts.length >= 3) {
                String rawNode = parts[1].trim();
                String node = rawNode.startsWith("NODE ") ? rawNode : (!rawNode.isEmpty() ? "NODE " + rawNode : "");
                String src = parts[2].trim().toUpperCase(Locale.US);
                String gps = "GPS".equalsIgnoreCase(src) ? "FIX" : "NO_FIX";
                String mpu = "READY";
                String lora = "READY";
                return ParsedLine.status(node, mpu, gps, lora, src, line);
            }
            return ParsedLine.unknown(line);
        }

        if (line.equals("NEED_GPS") || line.startsWith("NEED_GPS,") || line.startsWith("NEED_GPS|")) {
            return ParsedLine.needGps(line);
        }

        if (line.startsWith("MSG_IN,")) {
            String[] parts = line.split(",", 3);
            if (parts.length >= 3) {
                String rawFrom = parts[1].trim();
                String node = rawFrom.startsWith("NODE ") ? rawFrom : (!rawFrom.isEmpty() ? "NODE " + rawFrom : "");
                String text = parts[2];
                String id = nextPacketId();
                LoraPacket packet = new LoraPacket(LoraPacket.Type.MSG, node, id, 0.0, 0.0, false, text, line);
                return ParsedLine.rx(packet, line);
            }
            return ParsedLine.unknown(line);
        }

        if (line.startsWith("SOS_IN,")) {
            String[] parts = line.split(",");
            if (parts.length >= 6) {
                String rawFrom = parts[1].trim();
                String node = rawFrom.startsWith("NODE ") ? rawFrom : (!rawFrom.isEmpty() ? "NODE " + rawFrom : "");
                String reason = parts[2].trim();
                String latStr = parts[3].trim();
                String lonStr = parts[4].trim();
                String src = parts[5].trim();

                LoraPacket.Type type = "FALL".equalsIgnoreCase(reason) ? LoraPacket.Type.FALL : LoraPacket.Type.SOS;
                GpsCoord gps = parseCoordOrNa(latStr, lonStr);
                String id = nextPacketId();
                LoraPacket packet = new LoraPacket(type, node, id, gps.lat, gps.lon, gps.hasFix, "", line);
                return ParsedLine.rx(packet, line);
            }
            return ParsedLine.unknown(line);
        }

        if (line.startsWith("MSG_SENT,")) {
            String status = line.substring(9).trim();
            boolean ok = "OK".equalsIgnoreCase(status);
            return ParsedLine.msgSent(ok, line);
        }

        if (line.startsWith("SOS_SENDING,")) {
            String[] parts = line.split(",");
            if (parts.length >= 4) {
                String reason = parts[1].trim();
                String latStr = parts[2].trim();
                String lonStr = parts[3].trim();
                String src = parts.length > 4 ? parts[4].trim() : "";
                GpsCoord gps = parseCoordOrNa(latStr, lonStr);
                return ParsedLine.sosSending(reason, gps.lat, gps.lon, gps.hasFix, src, line);
            }
            return ParsedLine.unknown(line);
        }

        if (line.startsWith("SOS_SENT,")) {
            String status = line.substring(9).trim();
            boolean ok = "OK".equalsIgnoreCase(status);
            return ParsedLine.sosSent(ok, line);
        }

        if (line.startsWith("SOS_READ,")) {
            String rawFrom = line.substring(9).trim();
            String node = rawFrom.startsWith("NODE ") ? rawFrom : (!rawFrom.isEmpty() ? "NODE " + rawFrom : "");
            return ParsedLine.sosRead(node, line);
        }

        if (line.startsWith("READ_SENT,")) {
            String status = line.substring(10).trim();
            return ParsedLine.readSent(status, line);
        }

        if (line.startsWith("FALL,START")) {
            String[] parts = line.split(",");
            int sec = 120;
            if (parts.length >= 3) {
                try {
                    sec = Integer.parseInt(parts[2].trim());
                } catch (NumberFormatException ignored) {}
            }
            return ParsedLine.fallMonitoring("", sec, line);
        }

        if (line.equals("FALL,CANCEL") || line.startsWith("FALL,CANCEL,")) {
            return ParsedLine.fallCancelled("", line);
        }

        if (line.equals("FALL,SOS") || line.startsWith("FALL,SOS,")) {
            return ParsedLine.fallSos(line);
        }

        if (line.startsWith("ERR,")) {
            String reason = line.substring(4).trim();
            return ParsedLine.error(reason, line);
        }

        // Backward compatibility for pipe-based lines
        if (line.startsWith("READY|")) {
            String node = line.substring(6).trim();
            return ParsedLine.ready(node, line);
        }

        if (line.startsWith("STATUS|")) {
            return parseStatusLine(line);
        }

        if (line.startsWith("LOC_OK|")) {
            String node = line.substring(7).trim();
            return ParsedLine.locOk(node, line);
        }

        if (line.startsWith("TX|")) {
            String packetStr = line.substring(3).trim();
            LoraPacket packet = parsePacket(packetStr);
            if (packet != null) {
                return ParsedLine.tx(packet, line);
            }
            return ParsedLine.unknown(line);
        }

        if (line.startsWith("RX|")) {
            String packetStr = line.substring(3).trim();
            LoraPacket packet = parsePacket(packetStr);
            if (packet != null) {
                return ParsedLine.rx(packet, line);
            }
            return ParsedLine.unknown(line);
        }

        if (line.startsWith("FALL_MONITORING|")) {
            String node = line.substring(16).trim();
            return ParsedLine.fallMonitoring(node, line);
        }

        if (line.startsWith("FALL_CANCELLED|")) {
            String node = line.substring(15).trim();
            return ParsedLine.fallCancelled(node, line);
        }

        if (line.startsWith("ERROR|")) {
            String reason = line.substring(6).trim();
            return ParsedLine.error(reason, line);
        }

        if (line.startsWith("SIG|")) {
            String[] parts = line.split("\\|");
            if (parts.length >= 3) {
                try {
                    int rssi = Integer.parseInt(parts[1].trim());
                    float snr = Float.parseFloat(parts[2].trim());
                    return ParsedLine.sig(rssi, snr, line);
                } catch (NumberFormatException ignored) {
                }
            }
            return ParsedLine.unknown(line);
        }

        return ParsedLine.unknown(line);
    }

    private static ParsedLine parseStatusLine(String line) {
        // Format: STATUS|<NODE>|MPU=<READY|NOT_FOUND>|GPS=<FIX|NO_FIX>|LORA=READY[|SRC=<GPS|PHONE|NONE>]
        String[] parts = line.split("\\|");
        if (parts.length < 2) {
            return ParsedLine.unknown(line);
        }

        String node = parts[1].trim();
        String mpu = "";
        String gps = "";
        String lora = "";
        String src = "";

        for (int i = 2; i < parts.length; i++) {
            String part = parts[i].trim();
            if (part.startsWith("MPU=")) {
                mpu = part.substring(4).trim();
            } else if (part.startsWith("GPS=")) {
                gps = part.substring(4).trim();
            } else if (part.startsWith("LORA=")) {
                lora = part.substring(5).trim();
            } else if (part.startsWith("SRC=")) {
                src = part.substring(4).trim();
            }
            // Unknown trailing fields are intentionally ignored without breaking parsing
        }

        return ParsedLine.status(node, mpu, gps, lora, src, line);
    }

    /**
     * Parses the packet portion inside a TX or RX line.
     * Packets:
     *   MSG|<node>|<id>|<lat>|<lon>|<text>   (text is last and may contain '|')
     *   SOS|<node>|<id>|<lat>|<lon>
     *   FALL|<node>|<id>|<lat>|<lon>
     */
    public static LoraPacket parsePacket(String packetStr) {
        if (packetStr == null || packetStr.isEmpty()) {
            return null;
        }

        if (packetStr.startsWith("MSG|")) {
            int p1 = 3; // index of '|' after "MSG"
            int p2 = packetStr.indexOf('|', p1 + 1);
            int p3 = (p2 != -1) ? packetStr.indexOf('|', p2 + 1) : -1;
            int p4 = (p3 != -1) ? packetStr.indexOf('|', p3 + 1) : -1;
            int p5 = (p4 != -1) ? packetStr.indexOf('|', p4 + 1) : -1;

            if (p2 == -1 || p3 == -1 || p4 == -1 || p5 == -1) {
                return null;
            }

            String node = packetStr.substring(p1 + 1, p2).trim();
            String id = packetStr.substring(p2 + 1, p3).trim();
            String latStr = packetStr.substring(p3 + 1, p4).trim();
            String lonStr = packetStr.substring(p4 + 1, p5).trim();
            String text = packetStr.substring(p5 + 1); // retains remaining text including any '|'

            GpsCoord gps = parseCoordOrNa(latStr, lonStr);
            return new LoraPacket(LoraPacket.Type.MSG, node, id, gps.lat, gps.lon, gps.hasFix, text, packetStr);
        }

        if (packetStr.startsWith("SOS|") || packetStr.startsWith("FALL|")) {
            LoraPacket.Type type = packetStr.startsWith("SOS|") ? LoraPacket.Type.SOS : LoraPacket.Type.FALL;
            int p1 = packetStr.indexOf('|');
            int p2 = packetStr.indexOf('|', p1 + 1);
            int p3 = (p2 != -1) ? packetStr.indexOf('|', p2 + 1) : -1;
            int p4 = (p3 != -1) ? packetStr.indexOf('|', p3 + 1) : -1;

            if (p1 == -1 || p2 == -1 || p3 == -1 || p4 == -1) {
                return null;
            }

            String node = packetStr.substring(p1 + 1, p2).trim();
            String id = packetStr.substring(p2 + 1, p3).trim();
            String latStr = packetStr.substring(p3 + 1, p4).trim();
            String lonStr = packetStr.substring(p4 + 1).trim();

            GpsCoord gps = parseCoordOrNa(latStr, lonStr);
            return new LoraPacket(type, node, id, gps.lat, gps.lon, gps.hasFix, "", packetStr);
        }

        return null;
    }

    private static class GpsCoord {
        final double lat;
        final double lon;
        final boolean hasFix;

        GpsCoord(double lat, double lon, boolean hasFix) {
            this.lat = lat;
            this.lon = lon;
            this.hasFix = hasFix;
        }
    }

    private static GpsCoord parseCoordOrNa(String latStr, String lonStr) {
        if (latStr == null || lonStr == null ||
                "NA".equalsIgnoreCase(latStr.trim()) || "NA".equalsIgnoreCase(lonStr.trim()) ||
                GPS_INVALID.equalsIgnoreCase(latStr.trim()) || GPS_INVALID.equalsIgnoreCase(lonStr.trim())) {
            return new GpsCoord(0.0, 0.0, false);
        }
        try {
            double lat = Double.parseDouble(latStr.trim());
            double lon = Double.parseDouble(lonStr.trim());
            if (Double.isNaN(lat) || Double.isNaN(lon) || Double.isInfinite(lat) || Double.isInfinite(lon)) {
                return new GpsCoord(0.0, 0.0, false);
            }
            if (Math.abs(lat) < 0.000001 && Math.abs(lon) < 0.000001) {
                return new GpsCoord(0.0, 0.0, false);
            }
            return new GpsCoord(lat, lon, true);
        } catch (NumberFormatException e) {
            return new GpsCoord(0.0, 0.0, false);
        }
    }
}
