package org.mark.llamacpp.server.struct;

public class ActiveRequest {

    public enum RequestStatus {
        CREATED,
        PROXYING,
        COMPLETED,
        FAILED
    }

    public enum Phase {
        PREFILL,
        GENERATION
    }

    private final String requestId;
    private final String modelId;
    private final String endpoint;
    private final long startTime;
    private Timing timing;
    private RequestStatus status;
    private Phase phase;

    public ActiveRequest(String requestId, String modelId, String endpoint) {
        this.requestId = requestId;
        this.modelId = modelId;
        this.endpoint = endpoint;
        this.startTime = System.nanoTime();
        this.status = RequestStatus.CREATED;
        this.phase = Phase.PREFILL;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getModelId() {
        return modelId;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public long getStartTime() {
        return startTime;
    }

    public Timing getTiming() {
        return timing;
    }

    public void setTiming(Timing timing) {
        this.timing = timing;
    }

    public RequestStatus getStatus() {
        return status;
    }

    public void setStatus(RequestStatus status) {
        this.status = status;
    }

    public Phase getPhase() {
        return phase;
    }

    public void setPhase(Phase phase) {
        this.phase = phase;
    }

    public long elapsedMs() {
        return (System.nanoTime() - startTime) / 1_000_000L;
    }
}
