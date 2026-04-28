package org.mark.llamacpp.server.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.mark.llamacpp.server.struct.ActiveRequest;
import org.mark.llamacpp.server.struct.ActiveRequest.Phase;
import org.mark.llamacpp.server.struct.Timing;
import org.mark.llamacpp.server.websocket.WebSocketManager;
import org.mark.llamacpp.server.tools.JsonUtil;

import com.google.gson.JsonObject;

public class ModelRequestTracker {

    private static final ModelRequestTracker INSTANCE = new ModelRequestTracker();

    private final ConcurrentMap<String, Set<String>> modelActiveRequests = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ActiveRequest> allActiveRequests = new ConcurrentHashMap<>();

    public static ModelRequestTracker getInstance() {
        return INSTANCE;
    }

    public String createRequest(String modelId, String endpoint) {
        String requestId = UUID.randomUUID().toString();
        ActiveRequest req = new ActiveRequest(requestId, modelId, endpoint);
        allActiveRequests.put(requestId, req);
        modelActiveRequests.computeIfAbsent(modelId, k -> ConcurrentHashMap.newKeySet()).add(requestId);
        broadcastBusy(modelId, true);
        return requestId;
    }

    public void removeRequest(String requestId) {
        if (requestId == null) return;
        ActiveRequest req = allActiveRequests.remove(requestId);
        if (req == null) return;
        String modelId = req.getModelId();
        Set<String> reqs = modelActiveRequests.get(modelId);
        if (reqs != null) {
            reqs.remove(requestId);
            if (reqs.isEmpty()) {
                modelActiveRequests.remove(modelId);
            }
        }
        if (req.getTiming() != null) {
            LlamaRecordService.getInstance().recordRequest(req);
        }
        boolean stillBusy = modelActiveRequests.containsKey(modelId);
        broadcastBusy(modelId, stillBusy);
    }

    public ActiveRequest getActiveRequest(String requestId) {
        return requestId == null ? null : allActiveRequests.get(requestId);
    }

    public void updateTiming(String requestId, Timing timing) {
        if (requestId == null || timing == null) return;
        ActiveRequest req = allActiveRequests.get(requestId);
        if (req != null) {
            req.setTiming(timing);
        }
    }

    public void updatePhase(String requestId, Phase phase) {
        if (requestId == null || phase == null) return;
        ActiveRequest req = allActiveRequests.get(requestId);
        if (req == null) return;
        req.setPhase(phase);
        broadcastBusy(req.getModelId(), true);
    }

    public String getModelAggregatedPhase(String modelId) {
        if (modelId == null) return "prefill";
        Set<String> reqs = modelActiveRequests.get(modelId);
        if (reqs == null || reqs.isEmpty()) return "prefill";
        for (String rid : reqs) {
            ActiveRequest ar = allActiveRequests.get(rid);
            if (ar != null && ar.getPhase() == Phase.GENERATION) {
                return "generation";
            }
        }
        return "prefill";
    }

    public boolean isModelBusy(String modelId) {
        if (modelId == null) return false;
        Set<String> reqs = modelActiveRequests.get(modelId);
        return reqs != null && !reqs.isEmpty();
    }

    public Set<String> getBusyModels() {
        return Collections.unmodifiableSet(modelActiveRequests.keySet());
    }

    public int getModelActiveCount(String modelId) {
        if (modelId == null) return 0;
        Set<String> reqs = modelActiveRequests.get(modelId);
        return reqs == null ? 0 : reqs.size();
    }

    public List<ActiveRequest> getModelRequests(String modelId) {
        if (modelId == null) return Collections.emptyList();
        Set<String> reqs = modelActiveRequests.get(modelId);
        if (reqs == null || reqs.isEmpty()) return Collections.emptyList();
        List<ActiveRequest> result = new ArrayList<>(reqs.size());
        for (String rid : reqs) {
            ActiveRequest ar = allActiveRequests.get(rid);
            if (ar != null) {
                result.add(ar);
            }
        }
        return result;
    }

    private void broadcastBusy(String modelId, boolean busy) {
        try {
            JsonObject event = new JsonObject();
            event.addProperty("type", "model_busy");
            event.addProperty("modelId", modelId == null ? "" : modelId);
            event.addProperty("busy", busy);
            event.addProperty("activeCount", getModelActiveCount(modelId));
            WebSocketManager.getInstance().broadcast(JsonUtil.toJson(event));
        } catch (Exception e) {
        }
    }
}
