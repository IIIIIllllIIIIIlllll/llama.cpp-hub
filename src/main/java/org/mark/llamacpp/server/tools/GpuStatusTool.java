package org.mark.llamacpp.server.tools;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * GPU状态检测工具
 * 通过命令行抓取当前系统的GPU信息，支持NVIDIA/AMD/macOS
 */
public class GpuStatusTool {

	private GpuStatusTool() {
	}

	/**
	 * GPU信息对象
	 */
	public static class GpuInfo {
		private String vendor;
		private int index;
		private String name;
		private String driverVersion;
		private Double temperature;
		private Double gpuUtilization;
		private Double memoryUtilization;
		private long memoryUsed;
		private long memoryTotal;
		private Double powerUsage;
		private Double powerLimit;
		private String fanSpeed;
		private String pciBusId;
		private String rawOutput;

		public String getVendor() {
			return vendor;
		}

		public void setVendor(String vendor) {
			this.vendor = vendor;
		}

		public int getIndex() {
			return index;
		}

		public void setIndex(int index) {
			this.index = index;
		}

		public String getName() {
			return name;
		}

		public void setName(String name) {
			this.name = name;
		}

		public String getDriverVersion() {
			return driverVersion;
		}

		public void setDriverVersion(String driverVersion) {
			this.driverVersion = driverVersion;
		}

		public Double getTemperature() {
			return temperature;
		}

		public void setTemperature(Double temperature) {
			this.temperature = temperature;
		}

		public Double getGpuUtilization() {
			return gpuUtilization;
		}

		public void setGpuUtilization(Double gpuUtilization) {
			this.gpuUtilization = gpuUtilization;
		}

		public Double getMemoryUtilization() {
			return memoryUtilization;
		}

		public void setMemoryUtilization(Double memoryUtilization) {
			this.memoryUtilization = memoryUtilization;
		}

		public long getMemoryUsed() {
			return memoryUsed;
		}

		public void setMemoryUsed(long memoryUsed) {
			this.memoryUsed = memoryUsed;
		}

		public long getMemoryTotal() {
			return memoryTotal;
		}

		public void setMemoryTotal(long memoryTotal) {
			this.memoryTotal = memoryTotal;
		}

		public Double getPowerUsage() {
			return powerUsage;
		}

		public void setPowerUsage(Double powerUsage) {
			this.powerUsage = powerUsage;
		}

		public Double getPowerLimit() {
			return powerLimit;
		}

		public void setPowerLimit(Double powerLimit) {
			this.powerLimit = powerLimit;
		}

		public String getFanSpeed() {
			return fanSpeed;
		}

		public void setFanSpeed(String fanSpeed) {
			this.fanSpeed = fanSpeed;
		}

		public String getPciBusId() {
			return pciBusId;
		}

		public void setPciBusId(String pciBusId) {
			this.pciBusId = pciBusId;
		}

		public String getRawOutput() {
			return rawOutput;
		}

		public void setRawOutput(String rawOutput) {
			this.rawOutput = rawOutput;
		}
	}

	/**
	 * 获取所有GPU的JSON信息（支持混合GPU）
	 *
	 * @return 包含所有GPU信息的JSON对象
	 */
	public static JsonObject getGpuStatus() {
		return buildJsonResult(detectAllGpus());
	}

	/**
	 * 获取所有GPU信息列表（支持混合GPU）
	 *
	 * @return GPU信息列表
	 */
	public static List<GpuInfo> getGpuInfoList() {
		return detectAllGpus();
	}

	/**
	 * 探测当前系统所有GPU（跨厂商混合检测）
	 */
	public static List<GpuInfo> detectAllGpus() {
		List<GpuInfo> all = new ArrayList<>();
		String os = System.getProperty("os.name").toLowerCase();

		if (os.contains("win")) {
			List<GpuInfo> nvidia = detectNvidiaWindows();
			if (nvidia != null) all.addAll(nvidia);
			List<GpuInfo> amd = detectAmdWindows();
			if (amd != null) all.addAll(amd);
		} else if (os.contains("mac")) {
			List<GpuInfo> apple = detectMacGpu();
			if (apple != null) all.addAll(apple);
		} else {
			List<GpuInfo> nvidia = detectNvidiaLinux();
			if (nvidia != null) all.addAll(nvidia);
			List<GpuInfo> amd = detectAmdLinux();
			if (amd != null) all.addAll(amd);
		}

		return all;
	}

	/**
	 * 根据GPU列表构建JSON结果
	 */
	private static JsonObject buildJsonResult(List<GpuInfo> gpus) {
		JsonObject result = new JsonObject();
		result.addProperty("os", System.getProperty("os.name").toLowerCase());

		if (gpus == null || gpus.isEmpty()) {
			result.addProperty("vendor", "unknown");
			result.addProperty("count", 0);
			result.addProperty("error", "no supported GPU detected");
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
			gpuArray.add(toJson(info));
		}
		result.add("gpus", gpuArray);
		return result;
	}

	/**
	 * Windows下执行裸nvidia-smi，原始输出不做任何处理
	 */
	public static List<GpuInfo> detectNvidiaWindows() {
		CommandLineRunner.CommandResult queryResult = CommandLineRunner.execute(
				new String[]{"nvidia-smi"},
				10
		);

		if (queryResult == null || queryResult.getExitCode() == null || queryResult.getExitCode() != 0) {
			return null;
		}

		String output = queryResult.getOutput();
		if (output == null || output.trim().isEmpty()) {
			return null;
		}

		GpuInfo info = new GpuInfo();
		info.setVendor("NVIDIA");
		if (queryResult.getError() != null && !queryResult.getError().trim().isEmpty()) {
			info.setRawOutput(output + "\nstderr: " + queryResult.getError());
		} else {
			info.setRawOutput(output);
		}

		return List.of(info);
	}

	/**
	 * Windows下通过wmic查询AMD GPU（wmic不可用时fallback到PowerShell多源查询），原始输出不做任何处理
	 */
	public static List<GpuInfo> detectAmdWindows() {
		CommandLineRunner.CommandResult queryResult = CommandLineRunner.execute(
				new String[]{"wmic", "path", "Win32_VideoController", "where",
						"(Name like '%AMD%' or Name like '%RADEON%' or Name like '%Radeon%')",
						"get", "Name,AdapterRAM,DriverVersion,PNPDeviceID,AdapterCompatibility,"
								+ "VideoModeDescription,CurrentHorizontalResolution,CurrentVerticalResolution,"
								+ "CurrentRefreshRate,Description,DeviceID,SystemName", "/format:list"},
				10
		);

		if (queryResult != null && queryResult.getExitCode() != null && queryResult.getExitCode() == 0) {
			String output = queryResult.getOutput();
			if (output != null && !output.trim().isEmpty()) {
				GpuInfo info = new GpuInfo();
				info.setVendor("AMD");
				if (queryResult.getError() != null && !queryResult.getError().trim().isEmpty()) {
					info.setRawOutput(output + "\nstderr: " + queryResult.getError());
				} else {
					info.setRawOutput(output);
				}
				return List.of(info);
			}
		}

		String psCmd = "$ErrorActionPreference='SilentlyContinue';" +
				"$results=@();" +
				"$gpus=Get-CimInstance Win32_VideoController | Where-Object {$_.Name -match 'AMD|RADEON| Radeon'};" +
				"foreach($gpu in $gpus) {" +
				"  $pnp=Get-PnpDevice -InterfaceClass 'DISPLAY' | Where-Object {$_.FriendlyName -match 'AMD|RADEON| Radeon'};" +
				"  $obj=[Ordered]@{};" +
				"  $obj['Name']=$gpu.Name;" +
				"  $obj['Description']=$gpu.Description;" +
				"  $obj['AdapterCompatibility']=$gpu.AdapterCompatibility;" +
				"  $obj['AdapterRAM_MiB']=if($gpu.AdapterRAM){[math]::Round($gpu.AdapterRAM/1MB,1)}else{'N/A'};" +
				"  $obj['DriverVersion']=$gpu.DriverVersion;" +
				"  $obj['VideoModeDescription']=$gpu.VideoModeDescription;" +
				"  $obj['Resolution']='{0}x{1}' -f $gpu.CurrentHorizontalResolution,$gpu.CurrentVerticalResolution;" +
				"  $obj['RefreshRate']=$gpu.CurrentRefreshRate;" +
				"  $obj['PNPDeviceID']=$gpu.PNPDeviceID;" +
				"  $obj['Status']=$gpu.Status;" +
				"  $obj['Availability']=$gpu.Availability;" +
				"  $obj['DeviceID']=$gpu.DeviceID;" +
				"  $obj['SystemName']=$gpu.SystemName;" +
				"  $obj['ConfigManagerErrorCode']=$gpu.ConfigManagerErrorCode;" +
				"  $obj['ConfigManagerUserConfig']=$gpu.ConfigManagerUserConfig;" +
				"  if($pnp){" +
				"    foreach($d in $pnp){" +
				"      $obj['PnpStatus']=$d.Status;" +
				"      $obj['PnpClass']=$d.Class;" +
				"      $obj['PnpFriendlyName']=$d.FriendlyName;" +
				"      $obj['PnpInstanceId']=$d.InstanceId;" +
				"      break;" +
				"    }" +
				"  }" +
				"  $results+=[PSCustomObject]$obj;" +
				"}" +
				"if($results){$results|ConvertTo-Json -Depth 3}else{Write-Output 'No AMD GPU found'}";

		queryResult = CommandLineRunner.execute(
				new String[]{"powershell", "-NoProfile", "-NonInteractive", "-Command", psCmd},
				15
		);

		if (queryResult == null || queryResult.getExitCode() == null || queryResult.getExitCode() != 0) {
			return null;
		}

		String output = queryResult.getOutput();
		if (output == null || output.trim().isEmpty()) {
			return null;
		}

		if (output.trim().startsWith("No AMD GPU found")) {
			return null;
		}

		GpuInfo info = new GpuInfo();
		info.setVendor("AMD");
		if (queryResult.getError() != null && !queryResult.getError().trim().isEmpty()) {
			info.setRawOutput(output + "\nstderr: " + queryResult.getError());
		} else {
			info.setRawOutput(output);
		}

		return List.of(info);
	}

	/**
	 * Linux下执行裸nvidia-smi，原始输出不做任何处理
	 */
	public static List<GpuInfo> detectNvidiaLinux() {
		CommandLineRunner.CommandResult queryResult = CommandLineRunner.execute(
				new String[]{"nvidia-smi"},
				10
		);

		if (queryResult == null || queryResult.getExitCode() == null || queryResult.getExitCode() != 0) {
			return null;
		}

		String output = queryResult.getOutput();
		if (output == null || output.trim().isEmpty()) {
			return null;
		}

		GpuInfo info = new GpuInfo();
		info.setVendor("NVIDIA");
		if (queryResult.getError() != null && !queryResult.getError().trim().isEmpty()) {
			info.setRawOutput(output + "\nstderr: " + queryResult.getError());
		} else {
			info.setRawOutput(output);
		}

		return List.of(info);
	}

	/**
	 * Linux下执行裸rocm-smi，原始输出不做任何处理
	 */
	public static List<GpuInfo> detectAmdLinux() {
		CommandLineRunner.CommandResult queryResult = CommandLineRunner.execute(
				new String[]{"rocm-smi"},
				10
		);

		if (queryResult == null || queryResult.getExitCode() == null || queryResult.getExitCode() != 0) {
			return null;
		}

		String output = queryResult.getOutput();
		if (output == null || output.trim().isEmpty()) {
			return null;
		}

		GpuInfo info = new GpuInfo();
		info.setVendor("AMD");
		if (queryResult.getError() != null && !queryResult.getError().trim().isEmpty()) {
			info.setRawOutput(output + "\nstderr: " + queryResult.getError());
		} else {
			info.setRawOutput(output);
		}

		return List.of(info);
	}

	/**
	 * macOS下执行裸system_profiler，原始输出不做任何处理
	 */
	public static List<GpuInfo> detectMacGpu() {
		CommandLineRunner.CommandResult queryResult = CommandLineRunner.execute(
				new String[]{"/usr/sbin/system_profiler", "SPDisplaysDataType", "-json"},
				15
		);

		if (queryResult == null || queryResult.getExitCode() == null || queryResult.getExitCode() != 0) {
			return null;
		}

		String output = queryResult.getOutput();
		if (output == null || output.trim().isEmpty()) {
			return null;
		}

		GpuInfo info = new GpuInfo();
		info.setVendor("Apple");
		if (queryResult.getError() != null && !queryResult.getError().trim().isEmpty()) {
			info.setRawOutput(output + "\nstderr: " + queryResult.getError());
		} else {
			info.setRawOutput(output);
		}

		return List.of(info);
	}

	/**
	 * GpuInfo转为JsonObject
	 */
	private static JsonObject toJson(GpuInfo info) {
		JsonObject obj = new JsonObject();
		obj.addProperty("index", info.getIndex());
		obj.addProperty("name", info.getName());
		obj.addProperty("driverVersion", info.getDriverVersion());
		obj.addProperty("temperature", info.getTemperature());
		obj.addProperty("gpuUtilization", info.getGpuUtilization());
		obj.addProperty("memoryUtilization", info.getMemoryUtilization());
		obj.addProperty("memoryUsedMiB", info.getMemoryUsed());
		obj.addProperty("memoryTotalMiB", info.getMemoryTotal());
		obj.addProperty("powerUsageW", info.getPowerUsage());
		obj.addProperty("powerLimitW", info.getPowerLimit());
		obj.addProperty("fanSpeed", info.getFanSpeed());
		obj.addProperty("pciBusId", info.getPciBusId());
		obj.addProperty("vendor", info.getVendor());
		obj.addProperty("rawOutput", info.getRawOutput());

		if (info.getMemoryTotal() > 0) {
			double pct = info.getMemoryUsed() * 100.0 / info.getMemoryTotal();
			obj.addProperty("memoryUsedPercent", Math.round(pct * 10.0) / 10.0);
		}
		return obj;
	}

}
