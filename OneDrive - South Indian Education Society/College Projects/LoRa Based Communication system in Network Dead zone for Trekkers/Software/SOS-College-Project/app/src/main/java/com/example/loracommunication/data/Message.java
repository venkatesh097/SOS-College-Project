package com.example.loracommunication.data;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "messages")
public class Message {
    @PrimaryKey(autoGenerate = true)
    private int id;
    
    private String senderId;
    private String content;
    private long timestamp;
    private double latitude;
    private double longitude;
    private boolean isSos;
    private boolean isSent;

    public Message(String senderId, String content, long timestamp, double latitude, double longitude, boolean isSos, boolean isSent) {
        this.senderId = senderId;
        this.content = content;
        this.timestamp = timestamp;
        this.latitude = latitude;
        this.longitude = longitude;
        this.isSos = isSos;
        this.isSent = isSent;
    }

    // Getters and Setters
    public int getId() { return id; }
    public void setId(int id) { this.id = id; }
    public String getSenderId() { return senderId; }
    public String getContent() { return content; }
    public long getTimestamp() { return timestamp; }
    public double getLatitude() { return latitude; }
    public double getLongitude() { return longitude; }
    public boolean isSos() { return isSos; }
    public boolean isSent() { return isSent; }
}
