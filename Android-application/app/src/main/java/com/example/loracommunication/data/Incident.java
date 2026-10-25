package com.example.loracommunication.data;

import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
    tableName = "incidents",
    indices = {@Index(value = {"node", "packetId", "kind"}, unique = true)}
)
public class Incident {
    @PrimaryKey(autoGenerate = true)
    private long id;

    private String kind; // "SOS" or "FALL"
    private String node;
    private Long packetId;
    private Double lat;
    private Double lon;
    private long receivedAt;
    private boolean acknowledged;

    public Incident(long id, String kind, String node, Long packetId, Double lat, Double lon, long receivedAt, boolean acknowledged) {
        this.id = id;
        this.kind = kind != null ? kind : "SOS";
        this.node = node != null ? node : "";
        this.packetId = packetId;
        this.lat = lat;
        this.lon = lon;
        this.receivedAt = receivedAt;
        this.acknowledged = acknowledged;
    }

    @Ignore
    public Incident(String kind, String node, Long packetId, Double lat, Double lon, long receivedAt, boolean acknowledged) {
        this(0, kind, node, packetId, lat, lon, receivedAt, acknowledged);
    }

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }

    public String getNode() { return node; }
    public void setNode(String node) { this.node = node; }

    public Long getPacketId() { return packetId; }
    public void setPacketId(Long packetId) { this.packetId = packetId; }

    public Double getLat() { return lat; }
    public void setLat(Double lat) { this.lat = lat; }

    public Double getLon() { return lon; }
    public void setLon(Double lon) { this.lon = lon; }

    public long getReceivedAt() { return receivedAt; }
    public void setReceivedAt(long receivedAt) { this.receivedAt = receivedAt; }

    public boolean isAcknowledged() { return acknowledged; }
    public void setAcknowledged(boolean acknowledged) { this.acknowledged = acknowledged; }

    public boolean hasGpsFix() {
        return lat != null && lon != null;
    }
}
