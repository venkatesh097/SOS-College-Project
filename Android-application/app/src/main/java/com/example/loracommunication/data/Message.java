package com.example.loracommunication.data;

import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;

@Entity(tableName = "messages")
public class Message {
    public static final String DIRECTION_SENT = "SENT";
    public static final String DIRECTION_RECEIVED = "RECEIVED";

    public static final String KIND_MSG = "MSG";
    public static final String KIND_SOS = "SOS";
    public static final String KIND_FALL = "FALL";

    public static final String STATE_SENDING = "SENDING";
    public static final String STATE_SENT_OVER_LORA = "SENT_OVER_LORA";
    public static final String STATE_FAILED = "FAILED";

    @PrimaryKey(autoGenerate = true)
    private long id;

    private String node;
    private Long packetId;
    private String direction;
    private String kind;
    private String content;
    private long timestamp;
    private Double latitude;
    private Double longitude;
    private String sendState;

    public Message(long id, String node, Long packetId, String direction, String kind,
                   String content, long timestamp, Double latitude, Double longitude, String sendState) {
        this.id = id;
        this.node = node != null ? node : "";
        this.packetId = packetId;
        this.direction = direction != null ? direction : DIRECTION_RECEIVED;
        this.kind = kind != null ? kind : KIND_MSG;
        this.content = content != null ? content : "";
        this.timestamp = timestamp;
        this.latitude = latitude;
        this.longitude = longitude;
        this.sendState = sendState != null ? sendState : STATE_SENT_OVER_LORA;
    }

    @Ignore
    public Message(String node, Long packetId, String direction, String kind,
                   String content, long timestamp, Double latitude, Double longitude, String sendState) {
        this(0, node, packetId, direction, kind, content, timestamp, latitude, longitude, sendState);
    }

    // Convenience constructor for tests/code
    @Ignore
    public Message(String node, String content, long timestamp, Double latitude, Double longitude,
                   boolean isSos, boolean isSent) {
        this(0, node, null, isSent ? DIRECTION_SENT : DIRECTION_RECEIVED,
                isSos ? KIND_SOS : KIND_MSG, content, timestamp, latitude, longitude, STATE_SENT_OVER_LORA);
    }

    // Getters and Setters
    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public String getNode() { return node; }
    public void setNode(String node) { this.node = node; }

    public Long getPacketId() { return packetId; }
    public void setPacketId(Long packetId) { this.packetId = packetId; }

    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }

    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    public Double getLatitude() { return latitude; }
    public void setLatitude(Double latitude) { this.latitude = latitude; }

    public Double getLongitude() { return longitude; }
    public void setLongitude(Double longitude) { this.longitude = longitude; }

    public String getSendState() { return sendState; }
    public void setSendState(String sendState) { this.sendState = sendState; }

    // Backward-compatible helper methods for existing adapters and UI
    public String getSenderId() { return node != null ? node : ""; }
    public boolean isSent() { return DIRECTION_SENT.equalsIgnoreCase(direction); }
    public boolean isSos() { return KIND_SOS.equalsIgnoreCase(kind) || KIND_FALL.equalsIgnoreCase(kind); }
    public String getPacketType() { return kind != null ? kind : KIND_MSG; }
    public boolean hasGpsFix() { return latitude != null && longitude != null; }
    public String getStatus() { return sendState != null ? sendState : ""; }
    public String getMessage() { return content; }
}
