package org.mark.llamacpp.server.service;

import java.io.BufferedReader;
import java.io.InputStreamReader;

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
				String rawOutput = execAndRead("wmic", "cpu", "get", "name");
				String[] lines = rawOutput.split("\n");
				for (int i = 1; i < lines.length; i++) {
					String model = lines[i].trim();
					if (!model.isEmpty()) {
						return model;
					}
				}
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
}
