package com.mark.test.tools;

import java.util.List;

import org.mark.llamacpp.server.service.GpuService;
import org.mark.llamacpp.server.service.GpuService.GpuVendor;
import org.mark.llamacpp.server.tools.GpuStatusTool;
import org.mark.llamacpp.server.tools.GpuStatusTool.GpuInfo;
import org.mark.llamacpp.server.tools.JsonUtil;

public class GpuStatusTest {

	public static void main(String[] args) {
		System.out.println("======== GpuService Info ======== ");
		String infoJson = JsonUtil.toJson(GpuService.getInstance().getServiceInfo());
		System.out.println(infoJson);

		System.out.println("\n======== Detected Vendors ======== ");
		List<GpuVendor> vendors = GpuService.getInstance().getDetectedVendors();
		System.out.println("Vendors: " + vendors);
		System.out.println("Total GPUs: " + GpuService.getInstance().getTotalGpuCount());
		System.out.println("Has NVIDIA: " + GpuService.getInstance().hasVendor(GpuVendor.NVIDIA));
		System.out.println("Has AMD:    " + GpuService.getInstance().hasVendor(GpuVendor.AMD));
		System.out.println("Has APPLE:  " + GpuService.getInstance().hasVendor(GpuVendor.APPLE));

		System.out.println("\n======== Initial GPU Snapshot ======== ");
		List<GpuInfo> allGpus = GpuService.getInstance().getAllGpus();
		for (GpuInfo gpu : allGpus) {
			System.out.println("[" + gpu.getVendor() + "] " + gpu.getName()
					+ " | VRAM: " + gpu.getMemoryTotal() + " MiB"
					+ " | Driver: " + gpu.getDriverVersion()
					+ " | PCI: " + gpu.getPciBusId());
		}

		System.out.println("\n======== Live GPU Status Query ======== ");
		String statusJson = JsonUtil.toJson(GpuService.getInstance().queryGpuStatus());
		System.out.println(statusJson);

		System.out.println("\n======== GpuStatusTool (direct) ======== ");
		String toolJson = JsonUtil.toJson(GpuStatusTool.getGpuStatus());
		System.out.println(toolJson);
	}
}
