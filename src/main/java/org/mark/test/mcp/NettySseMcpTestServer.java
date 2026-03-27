package org.mark.test.mcp;

import java.nio.file.Path;

import org.mark.test.mcp.tools.ReadStaticImageTool;


/**
 * 	一个测试用的启动入口。
 */
public class NettySseMcpTestServer {

	private final DefaultMcpServiceImpl service;

	public NettySseMcpTestServer(int port) {
		this.service = new DefaultMcpServiceImpl(port);
	}

	public void start() throws Exception {
		this.service.start();
	}

	public void stop() {
		this.service.stop();
	}

	public void registerDefaultTools(String serviceKey, IMCPTool tool) {
		this.service.registerTool(serviceKey, tool);
	}

	public static void main(String[] args) throws Exception {
		int port = 18081;
		NettySseMcpTestServer server = new NettySseMcpTestServer(port);
		
		
		ReadStaticImageTool tool = new ReadStaticImageTool(Path.of("C:\\Users\\Mark\\Pictures\\95d7453e8a551e35a7d4b4e58d74a218.jpeg"));
		
		server.service.registerTool("llama_server", tool);
		
		server.start();
	}
}
