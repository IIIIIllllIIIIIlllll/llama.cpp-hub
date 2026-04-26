package org.mark.llamacpp.server;

import java.util.ArrayList;
import java.util.List;

public class NodesConfigData {
    private List<LlamaHubNode> nodes;

    public NodesConfigData() {
        this.nodes = new ArrayList<>();
    }

    public NodesConfigData(List<LlamaHubNode> nodes) {
        this.nodes = nodes != null ? nodes : new ArrayList<>();
    }

    public List<LlamaHubNode> getNodes() {
        return nodes;
    }

    public void setNodes(List<LlamaHubNode> nodes) {
        this.nodes = nodes != null ? nodes : new ArrayList<>();
    }
}
