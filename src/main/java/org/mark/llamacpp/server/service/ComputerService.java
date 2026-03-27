package org.mark.llamacpp.server.service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;

/**
 * 用来获取一些硬件信息，用来记录到benchmark v2的结果里。
 */
public class ComputerService {
	
	
	public static ComputerService getInstance() {
		return INSTANCE;
	}
	
	private static final ComputerService INSTANCE = new ComputerService();
	

	/**
	 * 获取 CPU 型号
	 */
	public static String getCPUModel() {
		try {
			String os = System.getProperty("os.name").toLowerCase();
			if (os.contains("win")) {
				String cpuFromRegistry = parseWindowsCpuModelFromRegistry(
						tryExecAndRead("reg", "query", "HKLM\\HARDWARE\\DESCRIPTION\\System\\CentralProcessor\\0", "/v", "ProcessorNameString"));
				if (!cpuFromRegistry.isEmpty()) return cpuFromRegistry;
				String cpuFromWmic = parseWindowsCpuModelFromWmic(tryExecAndRead("wmic", "cpu", "get", "name"));
				if (!cpuFromWmic.isEmpty()) return cpuFromWmic;
				String cpuFromEnv = normalizeCpuModel(System.getenv("PROCESSOR_IDENTIFIER"));
				if (!cpuFromEnv.isEmpty()) return cpuFromEnv;
				return "无法解析CPU信息";
			} else if (os.contains("linux")) {
				String cpuFromProc = parseLinuxCpuModel(execAndRead("cat", "/proc/cpuinfo"));
				if (!cpuFromProc.isEmpty()) return cpuFromProc;
				String cpuFromLscpu = parseLinuxCpuModel(execAndRead("lscpu"));
				if (!cpuFromLscpu.isEmpty()) return cpuFromLscpu;
				return "无法解析CPU信息";
			} else {
				return "不支持的操作系统";
			}
		} catch (Exception e) {
			e.printStackTrace();
			return "获取CPU信息失败: " + e.getMessage();
		}
	}

	private static String parseLinuxCpuModel(String rawOutput) {
		if (rawOutput == null || rawOutput.trim().isEmpty()) return "";
		for (String l : rawOutput.split("\n")) {
			if (l == null) continue;
			int idx = l.indexOf(':');
			if (idx <= 0) continue;
			String key = l.substring(0, idx).trim().toLowerCase();
			if ("model name".equals(key)) {
				String value = l.substring(idx + 1).trim();
				if (!value.isEmpty()) return value;
			}
		}
		return "";
	}

	private static String parseWindowsCpuModelFromRegistry(String rawOutput) {
		if (rawOutput == null || rawOutput.trim().isEmpty()) return "";
		for (String l : rawOutput.split("\n")) {
			String line = normalizeCpuModel(l);
			if (line.isEmpty()) continue;
			String lowerLine = line.toLowerCase();
			if (!lowerLine.contains("processornamestring")) continue;
			int idx = lowerLine.indexOf("processornamestring");
			String value = line.substring(idx + "processornamestring".length()).trim();
			value = value.replaceFirst("^REG_\\S+\\s*", "").trim();
			if (!value.isEmpty()) return value;
		}
		return "";
	}

	private static String parseWindowsCpuModelFromWmic(String rawOutput) {
		if (rawOutput == null || rawOutput.trim().isEmpty()) return "";
		for (String l : rawOutput.split("\n")) {
			String line = normalizeCpuModel(l);
			if (line.isEmpty()) continue;
			if ("name".equalsIgnoreCase(line)) continue;
			return line;
		}
		return "";
	}

	private static String normalizeCpuModel(String value) {
		if (value == null) return "";
		return value.replace('\u0000', ' ').replaceAll("\\s+", " ").trim();
	}

	private static String execAndRead(String... command) throws Exception {
		ProcessBuilder pb = new ProcessBuilder(command);
		Process process = pb.start();
		BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
		StringBuilder output = new StringBuilder();
		String line;
		while ((line = reader.readLine()) != null) {
			output.append(line).append("\n");
		}
		process.waitFor();
		return output.toString().trim();
	}

	private static String tryExecAndRead(String... command) {
		try {
			return execAndRead(command);
		} catch (Exception e) {
			return "";
		}
	}

	/**
	 * 获取物理内存大小（单位：GB）
	 */
	public static long getPhysicalMemoryKB() {
		try {
			ProcessBuilder pb;
			String os = System.getProperty("os.name").toLowerCase();
			if (os.contains("win")) {
				pb = new ProcessBuilder("wmic", "os", "get", "TotalVisibleMemorySize");
			} else if (os.contains("linux")) {
				pb = new ProcessBuilder("grep", "MemTotal", "/proc/meminfo");
			} else {
				return -1;
			}
			Process process = pb.start();
			BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
			StringBuilder output = new StringBuilder();
			String line;
			while ((line = reader.readLine()) != null) {
				output.append(line).append("\n");
			}
			process.waitFor();
			String rawOutput = output.toString().trim();
			if (os.contains("win")) {
				// Windows: 跳过标题行，取第一个非空行
				String[] lines = rawOutput.split("\n");
				for (int i = 1; i < lines.length; i++) {
					String str = lines[i].trim();
					if (!str.isEmpty()) {
						long kb = Long.parseLong(str);
						return kb;
					}
				}
			} else if (os.contains("linux")) {
				// Linux: 提取数字
				for (String l : rawOutput.split("\n")) {
					String[] parts = l.split(":");
					if (parts.length > 1) {
						String numStr = parts[1].trim().replace("kB", "").trim();
						if (!numStr.isEmpty()) {
							long kb = Long.parseLong(numStr);
							return kb;
						}
					}
				}
			}
			return -1;
		} catch (Exception e) {
			e.printStackTrace();
			return -1;
		}
	}

	/**
	 * 获取 CPU 核心数
	 */
	public static int getCPUCoreCount() {
		try {
			ProcessBuilder pb;
			String os = System.getProperty("os.name").toLowerCase();
			if (os.contains("win")) {
				pb = new ProcessBuilder("wmic", "cpu", "get", "NumberOfCores");
			} else if (os.contains("linux")) {
				pb = new ProcessBuilder("nproc");
			} else {
				return -1;
			}
			Process process = pb.start();
			BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
			StringBuilder output = new StringBuilder();
			String line;
			while ((line = reader.readLine()) != null) {
				output.append(line).append("\n");
			}
			process.waitFor();
			String rawOutput = output.toString().trim();
			if (os.contains("win")) {
				// Windows: 跳过标题行，取第一个非空行
				String[] lines = rawOutput.split("\n");
				for (int i = 1; i < lines.length; i++) {
					String str = lines[i].trim();
					if (!str.isEmpty()) {
						return Integer.parseInt(str);
					}
				}
			} else if (os.contains("linux")) {
				if (!rawOutput.isEmpty()) {
					return Integer.parseInt(rawOutput.trim());
				}
			}
			return -1;
		} catch (Exception e) {
			e.printStackTrace();
			return -1;
		}
	}

	public static String getJavaVersion() {
		return System.getProperty("java.version", "");
	}

	public static String getJavaVendor() {
		return System.getProperty("java.vendor", "");
	}

	public static String getJvmName() {
		return System.getProperty("java.vm.name", "");
	}

	public static String getJvmVersion() {
		return System.getProperty("java.vm.version", "");
	}

	public static String getJvmVendor() {
		return System.getProperty("java.vm.vendor", "");
	}

	public static String getJvmInputArguments() {
		try {
			RuntimeMXBean runtimeMxBean = ManagementFactory.getRuntimeMXBean();
			return String.join(" ", runtimeMxBean.getInputArguments());
		} catch (Exception e) {
			return "";
		}
	}

	public static long getJvmStartTime() {
		try {
			return ManagementFactory.getRuntimeMXBean().getStartTime();
		} catch (Exception e) {
			return -1;
		}
	}

	public static long getJvmMaxMemoryMB() {
		return toMb(Runtime.getRuntime().maxMemory());
	}

	public static long getJvmTotalMemoryMB() {
		return toMb(Runtime.getRuntime().totalMemory());
	}

	public static long getJvmFreeMemoryMB() {
		return toMb(Runtime.getRuntime().freeMemory());
	}

	public static long getJvmUsedMemoryMB() {
		Runtime runtime = Runtime.getRuntime();
		return toMb(runtime.totalMemory() - runtime.freeMemory());
	}

	public static int getJvmAvailableProcessors() {
		return Runtime.getRuntime().availableProcessors();
	}

	private static long toMb(long bytes) {
		if (bytes < 0) return -1;
		return bytes / 1024 / 1024;
	}
}
