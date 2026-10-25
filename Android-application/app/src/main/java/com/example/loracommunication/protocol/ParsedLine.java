package com.example.loracommunication.protocol;

import java.util.Objects;

/**
 * Represents a parsed line received from the ESP32 node.
 */
public class ParsedLine {
    public enum LineType {
        READY,
        PONG,
        STATUS,
        LOC_OK,
        TX,
        RX,
        FALL_MONITORING,
        FALL_CANCELLED,
        FALL_SOS,
        ERROR,
        SIG,
        UNKNOWN,
        NEED_GPS,
        MSG_SENT,
        SOS_SENDING,
        SOS_SENT,
        SOS_READ,
        READ_SENT
    }

    private final LineType lineType;
    private final String node;
    private final LoraPacket packet;
    private final String mpuStatus;
    private final String gpsStatus;
    private final String loraStatus;
    private final String locSource;
    private final String errorReason;
    private final Integer rssi;
    private final Float snr;
    private final String rawLine;

    private final boolean success;
    private final int fallDurationSec;
    private final double packetLatitude;
    private final double packetLongitude;
    private final boolean hasGpsFix;

    public ParsedLine(LineType lineType, String node, LoraPacket packet, String mpuStatus, String gpsStatus, String loraStatus, String locSource, String errorReason, Integer rssi, Float snr, String rawLine, boolean success, int fallDurationSec, double packetLatitude, double packetLongitude, boolean hasGpsFix) {
        this.lineType = lineType;
        this.node = node != null ? node : "";
        this.packet = packet;
        this.mpuStatus = mpuStatus != null ? mpuStatus : "";
        this.gpsStatus = gpsStatus != null ? gpsStatus : "";
        this.loraStatus = loraStatus != null ? loraStatus : "";
        this.locSource = locSource != null ? locSource : "";
        this.errorReason = errorReason != null ? errorReason : "";
        this.rssi = rssi;
        this.snr = snr;
        this.rawLine = rawLine != null ? rawLine : "";
        this.success = success;
        this.fallDurationSec = fallDurationSec;
        this.packetLatitude = packetLatitude;
        this.packetLongitude = packetLongitude;
        this.hasGpsFix = hasGpsFix;
    }

    public ParsedLine(LineType lineType, String node, LoraPacket packet, String mpuStatus, String gpsStatus, String loraStatus, String locSource, String errorReason, Integer rssi, Float snr, String rawLine) {
        this(lineType, node, packet, mpuStatus, gpsStatus, loraStatus, locSource, errorReason, rssi, snr, rawLine, false, 120, 0.0, 0.0, false);
    }

    public ParsedLine(LineType lineType, String node, LoraPacket packet, String mpuStatus, String gpsStatus, String loraStatus, String errorReason, Integer rssi, Float snr, String rawLine) {
        this(lineType, node, packet, mpuStatus, gpsStatus, loraStatus, "", errorReason, rssi, snr, rawLine);
    }

    public ParsedLine(LineType lineType, String node, LoraPacket packet, String mpuStatus, String gpsStatus, String loraStatus, String errorReason, String rawLine) {
        this(lineType, node, packet, mpuStatus, gpsStatus, loraStatus, "", errorReason, null, null, rawLine);
    }

    public static ParsedLine ready(String node, String rawLine) {
        return new ParsedLine(LineType.READY, node, null, null, null, null, "", null, null, null, rawLine);
    }

    public static ParsedLine pong(String node, String rawLine) {
        return new ParsedLine(LineType.PONG, node, null, null, null, null, "", null, null, null, rawLine);
    }

    public static ParsedLine status(String node, String mpu, String gps, String lora, String locSource, String rawLine) {
        return new ParsedLine(LineType.STATUS, node, null, mpu, gps, lora, locSource, null, null, null, rawLine);
    }

    public static ParsedLine status(String node, String mpu, String gps, String lora, String rawLine) {
        return status(node, mpu, gps, lora, "", rawLine);
    }

    public static ParsedLine locOk(String node, String rawLine) {
        return new ParsedLine(LineType.LOC_OK, node, null, null, null, null, "", null, null, null, rawLine);
    }

    public static ParsedLine tx(LoraPacket packet, String rawLine) {
        return new ParsedLine(LineType.TX, packet != null ? packet.getNode() : "", packet, null, null, null, "", null, null, null, rawLine);
    }

    public static ParsedLine rx(LoraPacket packet, String rawLine) {
        return new ParsedLine(LineType.RX, packet != null ? packet.getNode() : "", packet, null, null, null, "", null, null, null, rawLine);
    }

    public static ParsedLine fallMonitoring(String node, int durationSec, String rawLine) {
        return new ParsedLine(LineType.FALL_MONITORING, node, null, null, null, null, "", null, null, null, rawLine, false, durationSec, 0.0, 0.0, false);
    }

    public static ParsedLine fallMonitoring(String node, String rawLine) {
        return fallMonitoring(node, 120, rawLine);
    }

    public static ParsedLine fallCancelled(String node, String rawLine) {
        return new ParsedLine(LineType.FALL_CANCELLED, node, null, null, null, null, "", null, null, null, rawLine);
    }

    public static ParsedLine fallSos(String rawLine) {
        return new ParsedLine(LineType.FALL_SOS, "", null, null, null, null, "", null, null, null, rawLine);
    }

    public static ParsedLine needGps(String rawLine) {
        return new ParsedLine(LineType.NEED_GPS, "", null, null, null, null, "", null, null, null, rawLine);
    }

    public static ParsedLine msgSent(boolean success, String rawLine) {
        return new ParsedLine(LineType.MSG_SENT, "", null, null, null, null, "", success ? "OK" : "FAIL", null, null, rawLine, success, 0, 0.0, 0.0, false);
    }

    public static ParsedLine sosSent(boolean success, String rawLine) {
        return new ParsedLine(LineType.SOS_SENT, "", null, null, null, null, "", success ? "OK" : "FAIL", null, null, rawLine, success, 0, 0.0, 0.0, false);
    }

    public static ParsedLine sosSending(String reason, double lat, double lon, boolean hasGpsFix, String src, String rawLine) {
        return new ParsedLine(LineType.SOS_SENDING, "", null, null, null, null, src, reason, null, null, rawLine, false, 0, lat, lon, hasGpsFix);
    }

    public static ParsedLine sosRead(String node, String rawLine) {
        return new ParsedLine(LineType.SOS_READ, node, null, null, null, null, "", null, null, null, rawLine);
    }

    public static ParsedLine readSent(String status, String rawLine) {
        boolean ok = "OK".equalsIgnoreCase(status);
        return new ParsedLine(LineType.READ_SENT, "", null, null, null, null, "", status, null, null, rawLine, ok, 120, 0.0, 0.0, false);
    }

    public static ParsedLine error(String reason, String rawLine) {
        return new ParsedLine(LineType.ERROR, "", null, null, null, null, "", reason, null, null, rawLine);
    }

    public static ParsedLine sig(int rssi, float snr, String rawLine) {
        return new ParsedLine(LineType.SIG, "", null, null, null, null, "", null, rssi, snr, rawLine);
    }

    public static ParsedLine unknown(String rawLine) {
        return new ParsedLine(LineType.UNKNOWN, "", null, null, null, null, "", null, null, null, rawLine);
    }

    public LineType getLineType() {
        return lineType;
    }

    public String getNode() {
        return node;
    }

    public LoraPacket getPacket() {
        return packet;
    }

    public String getMpuStatus() {
        return mpuStatus;
    }

    public boolean isMpuReady() {
        return "READY".equalsIgnoreCase(mpuStatus);
    }

    public String getGpsStatus() {
        return gpsStatus;
    }

    public boolean hasGpsFix() {
        if (packet != null) {
            return packet.hasGpsFix();
        }
        return hasGpsFix || "FIX".equalsIgnoreCase(gpsStatus);
    }

    public String getLoraStatus() {
        return loraStatus;
    }

    public boolean isLoraReady() {
        return "READY".equalsIgnoreCase(loraStatus);
    }

    public String getLocSource() {
        return locSource;
    }

    public String getErrorReason() {
        return errorReason;
    }

    public Integer getRssi() {
        return rssi;
    }

    public Float getSnr() {
        return snr;
    }

    public String getRawLine() {
        return rawLine;
    }

    public boolean isSuccess() {
        return success;
    }

    public int getFallDurationSec() {
        return fallDurationSec;
    }

    public double getPacketLatitude() {
        return packetLatitude;
    }

    public double getPacketLongitude() {
        return packetLongitude;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ParsedLine)) return false;
        ParsedLine that = (ParsedLine) o;
        return lineType == that.lineType &&
                success == that.success &&
                fallDurationSec == that.fallDurationSec &&
                Double.compare(that.packetLatitude, packetLatitude) == 0 &&
                Double.compare(that.packetLongitude, packetLongitude) == 0 &&
                hasGpsFix == that.hasGpsFix &&
                Objects.equals(node, that.node) &&
                Objects.equals(packet, that.packet) &&
                Objects.equals(mpuStatus, that.mpuStatus) &&
                Objects.equals(gpsStatus, that.gpsStatus) &&
                Objects.equals(loraStatus, that.loraStatus) &&
                Objects.equals(locSource, that.locSource) &&
                Objects.equals(errorReason, that.errorReason) &&
                Objects.equals(rssi, that.rssi) &&
                Objects.equals(snr, that.snr);
    }

    @Override
    public int hashCode() {
        return Objects.hash(lineType, node, packet, mpuStatus, gpsStatus, loraStatus, locSource, errorReason, rssi, snr, success, fallDurationSec, packetLatitude, packetLongitude, hasGpsFix);
    }

    @Override
    public String toString() {
        return "ParsedLine{" +
                "lineType=" + lineType +
                ", node='" + node + '\'' +
                ", packet=" + packet +
                ", mpuStatus='" + mpuStatus + '\'' +
                ", gpsStatus='" + gpsStatus + '\'' +
                ", loraStatus='" + loraStatus + '\'' +
                ", locSource='" + locSource + '\'' +
                ", errorReason='" + errorReason + '\'' +
                ", rssi=" + rssi +
                ", snr=" + snr +
                ", success=" + success +
                ", fallDurationSec=" + fallDurationSec +
                ", packetLatitude=" + packetLatitude +
                ", packetLongitude=" + packetLongitude +
                ", hasGpsFix=" + hasGpsFix +
                '}';
    }
}
