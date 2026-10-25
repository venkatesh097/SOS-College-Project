package com.example.loracommunication.data;

public class FallStateModel {
    public enum Status {
        NORMAL,
        MONITORING,
        CANCELLED
    }

    private final Status status;
    private final long startedAt;
    private final String node;
    private final int durationSec;

    public FallStateModel(Status status, long startedAt, String node, int durationSec) {
        this.status = status;
        this.startedAt = startedAt;
        this.node = node != null ? node : "";
        this.durationSec = durationSec;
    }

    public FallStateModel(Status status, long startedAt, String node) {
        this(status, startedAt, node, 120);
    }

    public static FallStateModel normal() {
        return new FallStateModel(Status.NORMAL, 0, "", 0);
    }

    public static FallStateModel monitoring(long startedAt, String node, int durationSec) {
        return new FallStateModel(Status.MONITORING, startedAt, node, durationSec);
    }

    public static FallStateModel monitoring(long startedAt, String node) {
        return new FallStateModel(Status.MONITORING, startedAt, node, 120);
    }

    public static FallStateModel cancelled(String node) {
        return new FallStateModel(Status.CANCELLED, 0, node, 0);
    }

    public Status getStatus() {
        return status;
    }

    public long getStartedAt() {
        return startedAt;
    }

    public String getNode() {
        return node;
    }

    public int getDurationSec() {
        return durationSec;
    }

    public boolean isMonitoring() {
        return status == Status.MONITORING;
    }

    public boolean isCancelled() {
        return status == Status.CANCELLED;
    }

    public boolean isNormal() {
        return status == Status.NORMAL;
    }

    public String getState() {
        return status != null ? status.name() : "NORMAL";
    }
}
