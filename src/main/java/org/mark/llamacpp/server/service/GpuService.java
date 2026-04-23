package org.mark.llamacpp.server.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.mark.llamacpp.server.tools.GpuStatusTool;
import org.mark.llamacpp.server.tools.GpuStatusTool.GpuInfo;
import org.mark.llamacpp.server.tools.JsonUtil;

/**
 * GPU服务
 * 初始化时探测系统GPU厂商并记录，查询时仅执行对应厂商的命令
 */
public class GpuService {

	private static final GpuService INSTANCE = new GpuService();

	public enum GpuVendor {
		NVIDIA,
		AMD,
		APPLE
	}

	private final String os;
	private final Set<GpuVendor> detectedVendors;
	private final List<GpuInfo> initialInfo;

	public static GpuService getInstance() {
		return INSTANCE;
	}

	private GpuService() {
		this.os = System.getProperty("os.name").toLowerCase();
		this.detectedVendors = new LinkedHashSet<>();
		this.initialInfo = new ArrayList<>();

		detectGpus();
	}

	/**
	 * 初始化时探测所有GPU并记录厂商信息
	 */
	private void detectGpus() {
		if (os.contains("win")) {
			detectWindows();
		} else if (os.contains("mac")) {
			detectMac();
		} else {
			detectLinux();
		}
	}

	private void detectWindows() {
		List<GpuInfo> nvidia = GpuStatusTool.detectNvidiaWindows();
		if (nvidia != null && !nvidia.isEmpty()) {
			detectedVendors.add(GpuVendor.NVIDIA);
			initialInfo.addAll(nvidia);
		}

		List<GpuInfo> amd = GpuStatusTool.detectAmdWindows();
		if (amd != null && !amd.isEmpty()) {
			detectedVendors.add(GpuVendor.AMD);
			initialInfo.addAll(amd);
		}
	}

	private void detectLinux() {
		List<GpuInfo> nvidia = GpuStatusTool.detectNvidiaLinux();
		if (nvidia != null && !nvidia.isEmpty()) {
			detectedVendors.add(GpuVendor.NVIDIA);
			initialInfo.addAll(nvidia);
		}

		List<GpuInfo> amd = GpuStatusTool.detectAmdLinux();
		if (amd != null && !amd.isEmpty()) {
			detectedVendors.add(GpuVendor.AMD);
			initialInfo.addAll(amd);
		}
	}

	private void detectMac() {
		List<GpuInfo> apple = GpuStatusTool.detectMacGpu();
		if (apple != null && !apple.isEmpty()) {
			detectedVendors.add(GpuVendor.APPLE);
			initialInfo.addAll(apple);
		}
	}

	/**
	 * 获取当前系统GPU状态
	 * 根据记录的厂商信息，只执行对应的检测命令
	 */
	public JsonObject queryGpuStatus() {
		List<GpuInfo> all = new ArrayList<>();

		for (GpuVendor vendor : detectedVendors) {
			List<GpuInfo> gpus = queryVendorGpus(vendor);
			if (gpus != null) {
				all.addAll(gpus);
			}
		}

		return buildResult(all);
	}

	/**
	 * 查询指定厂商的GPU实时状态
	 */
	private List<GpuInfo> queryVendorGpus(GpuVendor vendor) {
		switch (vendor) {
			case NVIDIA:
				if (os.contains("win")) {
					return GpuStatusTool.detectNvidiaWindows();
				} else {
					return GpuStatusTool.detectNvidiaLinux();
				}
			case AMD:
				if (os.contains("win")) {
					return GpuStatusTool.detectAmdWindows();
				} else {
					return GpuStatusTool.detectAmdLinux();
				}
			case APPLE:
				return GpuStatusTool.detectMacGpu();
			default:
				return null;
		}
	}

	/**
	 * 获取指定厂商的GPU列表
	 */
	public List<GpuInfo> getGpusByVendor(GpuVendor vendor) {
		List<GpuInfo> result = new ArrayList<>();
		for (GpuInfo info : initialInfo) {
			if (vendor.name().equals(info.getVendor())) {
				result.add(info);
			}
		}
		return result;
	}

	/**
	 * 获取所有已记录的GPU信息（初始化时的快照）
	 */
	public List<GpuInfo> getAllGpus() {
		return new ArrayList<>(initialInfo);
	}

	/**
	 * 获取已检测到的厂商列表
	 */
	public List<GpuVendor> getDetectedVendors() {
		return new ArrayList<>(detectedVendors);
	}

	/**
	 * 是否包含指定厂商的GPU
	 */
	public boolean hasVendor(GpuVendor vendor) {
		return detectedVendors.contains(vendor);
	}

	/**
	 * 获取GPU总数
	 */
	public int getTotalGpuCount() {
		return initialInfo.size();
	}

	/**
	 * 获取操作系统类型
	 */
	public String getOs() {
		return os;
	}

	/**
	 * 获取服务信息摘要
	 */
	public JsonObject getServiceInfo() {
		JsonObject info = new JsonObject();
		info.addProperty("os", os);
		info.addProperty("totalGpuCount", initialInfo.size());

		JsonArray vendors = new JsonArray();
		for (GpuVendor v : detectedVendors) {
			JsonObject vObj = new JsonObject();
			vObj.addProperty("vendor", v.name());
			vObj.addProperty("gpuCount", getGpusByVendor(v).size());
			vendors.add(vObj);
		}
		info.add("vendors", vendors);

		JsonArray gpus = new JsonArray();
		for (GpuInfo gpu : initialInfo) {
			JsonObject g = new JsonObject();
			g.addProperty("vendor", gpu.getVendor());
			g.addProperty("name", gpu.getName());
			g.addProperty("memoryTotalMiB", gpu.getMemoryTotal());
			g.addProperty("driverVersion", gpu.getDriverVersion());
			gpus.add(g);
		}
		info.add("gpus", gpus);
		return info;
	}

	private JsonObject buildResult(List<GpuInfo> gpus) {
		JsonObject result = new JsonObject();
		result.addProperty("os", os);
		result.addProperty("timestamp", System.currentTimeMillis());

		if (gpus == null || gpus.isEmpty()) {
			result.addProperty("count", 0);
			result.add("gpus", new JsonArray());
			return result;
		}

		Set<String> vendors = new LinkedHashSet<>();
		for (GpuInfo g : gpus) {
			if (g.getVendor() != null) vendors.add(g.getVendor());
		}
		result.addProperty("vendors", JsonUtil.toJson(new ArrayList<>(vendors)));
		result.addProperty("count", gpus.size());

		JsonArray gpuArray = new JsonArray();
		for (GpuInfo info : gpus) {
			JsonObject obj = new JsonObject();
			obj.addProperty("vendor", info.getVendor());
			obj.addProperty("name", info.getName());
			obj.addProperty("driverVersion", info.getDriverVersion());
			obj.addProperty("temperature", info.getTemperature());
			obj.addProperty("gpuUtilization", info.getGpuUtilization());
			obj.addProperty("memoryUsedMiB", info.getMemoryUsed());
			obj.addProperty("memoryTotalMiB", info.getMemoryTotal());
			obj.addProperty("memoryUtilization", info.getMemoryUtilization());
			obj.addProperty("powerUsageW", info.getPowerUsage());
			obj.addProperty("powerLimitW", info.getPowerLimit());
			obj.addProperty("fanSpeed", info.getFanSpeed());
			obj.addProperty("pciBusId", info.getPciBusId());
			obj.addProperty("rawOutput", info.getRawOutput());

			if (info.getMemoryTotal() > 0) {
				double pct = info.getMemoryUsed() * 100.0 / info.getMemoryTotal();
				obj.addProperty("memoryUsedPercent", Math.round(pct * 10.0) / 10.0);
			}

			gpuArray.add(obj);
		}
		result.add("gpus", gpuArray);
		return result;
	}
}
