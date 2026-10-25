package com.example.loracommunication.protocol;

import java.util.Objects;

/**
 * Represents a packet carried inside a TX or RX line.
 * Packets:
 *   MSG|<node>|<id>|<lat>|<lon>|<text>
 *   SOS|<node>|<id>|<lat>|<lon>
 *   FALL|<node>|<id>|<lat>|<lon>
 */
public class LoraPacket {
    public enum Type {
        MSG, SOS, FALL
    }

    private final Type type;
    private final String node;
    private final String id;
    private final double latitude;
    private final double longitude;
    private final boolean hasGpsFix;
    private final String text;
    private final String rawPacket;

    public LoraPacket(Type type, String node, String id, double latitude, double longitude, boolean hasGpsFix, String text, String rawPacket) {
        this.type = type;
        this.node = node != null ? node : "";
        this.id = id != null ? id : "";
        this.latitude = latitude;
        this.longitude = longitude;
        this.hasGpsFix = hasGpsFix;
        this.text = text != null ? text : "";
        this.rawPacket = rawPacket != null ? rawPacket : "";
    }

    public LoraPacket(Type type, String node, String id, double latitude, double longitude, String text) {
        this(type, node, id, latitude, longitude, true, text, "");
    }

    public Type getType() {
        return type;
    }

    public String getNode() {
        return node;
    }

    public String getId() {
        return id;
    }

    public double getLatitude() {
        return latitude;
    }

    public double getLongitude() {
        return longitude;
    }

    public boolean hasGpsFix() {
        return hasGpsFix;
    }

    public String getText() {
        return text;
    }

    public String getRawPacket() {
        return rawPacket;
    }

    /**
     * Deduplication key per node: (node, id, type)
     */
    public String getDeduplicationKey() {
        return node + "|" + id + "|" + (type != null ? type.name() : "");
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LoraPacket)) return false;
        LoraPacket that = (LoraPacket) o;
        return hasGpsFix == that.hasGpsFix &&
                Double.compare(that.latitude, latitude) == 0 &&
                Double.compare(that.longitude, longitude) == 0 &&
                type == that.type &&
                Objects.equals(node, that.node) &&
                Objects.equals(id, that.id) &&
                Objects.equals(text, that.text);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, node, id, latitude, longitude, hasGpsFix, text);
    }

    @Override
    public String toString() {
        return "LoraPacket{" +
                "type=" + type +
                ", node='" + node + '\'' +
                ", id='" + id + '\'' +
                ", lat=" + (hasGpsFix ? latitude : "INVALID") +
                ", lon=" + (hasGpsFix ? longitude : "INVALID") +
                ", text='" + text + '\'' +
                '}';
    }
}
