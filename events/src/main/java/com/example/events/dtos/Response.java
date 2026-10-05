package com.example.events.dtos;

import java.time.Instant;

public abstract class Response {
    String correlationId;
    String eventype;
    Instant timestamp;
    boolean status;
    String stage;
    public Response(String correlationId,String eventype, boolean status) {
        this(correlationId, eventype, status, "PREPARE");
    }
    public Response(String correlationId, String eventype, boolean status, String stage) {
        this.correlationId = correlationId;
        this.eventype = eventype;
        this.timestamp = Instant.now();
        this.status = status;
        this.stage = stage;

    }
    public Response() {}
    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }

    public boolean getStatus() {
        return status;
    }

    public void setStatus(boolean status) {
        this.status = status;
    }

    public String getEventype() {
        return eventype;
    }

    public void setEventype(String eventype) {
        this.eventype = eventype;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public String getStage() {
        return stage;
    }

    public void setStage(String stage) {
        this.stage = stage;
    }

}
