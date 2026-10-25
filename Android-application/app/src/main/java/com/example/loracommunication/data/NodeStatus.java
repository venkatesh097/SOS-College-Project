package com.example.loracommunication.data;

public class NodeStatus {
    private final String mpu;
    private final String gps;
    private final String lora;
    private final String source; // "GPS", "PHONE", "NONE", or ""

    public NodeStatus(String mpu, String gps, String lora, String source) {
        this.mpu = mpu != null ? mpu : "--";
        this.gps = gps != null ? gps : "--";
        this.lora = lora != null ? lora : "--";
        this.source = source != null ? source : "";
    }

    public NodeStatus(String mpu, String gps, String lora) {
        this(mpu, gps, lora, "");
    }

    public String getMpu() {
        return mpu;
    }

    public String getGps() {
        return gps;
    }

    public String getLora() {
        return lora;
    }

    public String getSource() {
        return source;
    }

    public boolean isMpuReady() {
        return "READY".equalsIgnoreCase(mpu);
    }

    public boolean hasGpsFix() {
        return "FIX".equalsIgnoreCase(gps);
    }

    public boolean isLoraReady() {
        return "READY".equalsIgnoreCase(lora);
    }

    public boolean isPhoneSource() {
        return "PHONE".equalsIgnoreCase(source);
    }

    public boolean isGpsSource() {
        return "GPS".equalsIgnoreCase(source);
    }

    public boolean isNoneSource() {
        return "NONE".equalsIgnoreCase(source);
    }
}
