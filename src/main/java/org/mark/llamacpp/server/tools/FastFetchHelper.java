package org.mark.llamacpp.server.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class FastFetchHelper {

    private static final FastFetchHelper INSTANCE = new FastFetchHelper();
    private static final String CACHE_DIR_NAME = "cache/tools";
    private static final String DEFAULT_STRUCTURE = "CPU:GPU:Memory:Battery";

    private volatile boolean initialized = false;
    private String exePath;
    private boolean available = false;
    private String initError;

    private FastFetchHelper() {}

    public static FastFetchHelper getInstance() {
        return INSTANCE;
    }

    /**
     * 检测平台并从 classpath 提取 fastfetch 二进制到 cache/tools/，初始化成功后 isAvailable() 返回 true。
     * 幂等，可重复调用。
     */
    public synchronized String init() {
        if (initialized)
            return initError;
        initialized = true;

        try {
            String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
            String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);

            String platform;
            if (os.contains("win")) {
                platform = "windows";
            } else if (os.contains("linux")) {
                platform = "linux";
            } else {
                throw new UnsupportedOperationException("Unsupported OS: " + os);
            }

            String archDir = (arch.equals("aarch64") || arch.equals("arm64")) ? "aarch64" : "amd64";
            String dirName = "fastfetch-" + platform + "-" + archDir;
            String exeName = platform.equals("windows") ? "fastfetch.exe" : "fastfetch";

            Path cacheDir = Paths.get(CACHE_DIR_NAME, dirName);
            Files.createDirectories(cacheDir);

            String resourceRoot = "/tools/" + dirName + "/";
            String classpathSuffix = "";
            copyFromClasspath(resourceRoot + classpathSuffix + exeName, cacheDir.resolve(exeName));
            if (platform.equals("windows")) {
                copyFromClasspath(resourceRoot + "vulkan-1.dll", cacheDir.resolve("vulkan-1.dll"));
                copyFromClasspath(resourceRoot + "OpenCL.dll", cacheDir.resolve("OpenCL.dll"));
            }

            exePath = cacheDir.resolve(exeName).toAbsolutePath().toString();
            File exeFile = new File(exePath);
            if (!exeFile.exists()) {
                throw new FileNotFoundException("fastfetch binary not extracted: " + exePath);
            }
            if (!exeFile.setExecutable(true, false) && !exeFile.canExecute()) {
                if (platform.equals("linux")) {
                    try {
                        new ProcessBuilder("chmod", "+x", exePath).start().waitFor();
                    } catch (Exception ignored) {}
                }
                if (!exeFile.canExecute()) {
                    throw new IOException("Failed to set executable permission: " + exePath);
                }
            }

            available = true;
            return null;
        } catch (Exception e) {
            available = false;
            initError = e.getMessage();
            return initError;
        }
    }

    public boolean isAvailable() {
        if (!initialized)
            init();
        return available;
    }

    public String getInitError() {
        return initError;
    }

    /**
     * 调用 fastfetch -s "CPU:Memory:GPU" --json，解析返回 ComputerInfo。
     */
    public ComputerInfo getInfo() {
        ComputerInfo info = new ComputerInfo();
        try {
            if (!isAvailable()) {
                info.error = "fastfetch not available: " + initError;
                return info;
            }
            info.rawOutput = execRaw();
            JsonArray arr = execJson(DEFAULT_STRUCTURE);
            for (JsonElement elem : arr) {
                JsonObject obj = elem.getAsJsonObject();
                String type = obj.get("type").getAsString();
                switch (type) {
                    case "CPU": {
                        JsonObject r = obj.getAsJsonObject("result");
                        info.cpuModel = getString(r, "cpu");
                        info.cpuTemperature = getInt(r, "temperature", -1);
                        JsonObject cores = r.getAsJsonObject("cores");
                        if (cores != null) {
                            info.physicalCores = getInt(cores, "physical", -1);
                            info.logicalCores = getInt(cores, "logical", -1);
                        }
                        break;
                    }
                    case "Memory": {
                        JsonObject r = obj.getAsJsonObject("result");
                        info.memoryBytes = getLong(r, "total", -1L);
                        break;
                    }
                    case "GPU": {
                        JsonArray gpuArr = obj.getAsJsonArray("result");
                        for (JsonElement ge : gpuArr) {
                            JsonObject g = ge.getAsJsonObject();
                            GpuInfo gpu = new GpuInfo();
                            gpu.name = getString(g, "name");
                            gpu.vendor = getString(g, "vendor");
                            gpu.type = getString(g, "type");
                            gpu.driver = getString(g, "driver");
                            gpu.frequency = getInt(g, "frequency", -1);
                            gpu.temperature = getInt(g, "temperature", -1);
                            gpu.coreCount = getInt(g, "coreCount", -1);
                            gpu.coreUsage = getInt(g, "coreUsage", -1);
                            gpu.deviceId = getString(g, "deviceId");
                            Integer idx = getInt(g, "index", -1);
                            gpu.index = idx != null ? idx.intValue() : -1;
                            JsonObject mem = g.getAsJsonObject("memory");
                            if (mem != null) {
                                JsonObject ded = mem.getAsJsonObject("dedicated");
                                if (ded != null) {
                                    gpu.dedicatedMemoryBytes = getLong(ded, "total", -1L);
                                    gpu.dedicatedMemoryUsed = getLong(ded, "used", -1L);
                                }
                                JsonObject shrd = mem.getAsJsonObject("shared");
                                if (shrd != null) {
                                    gpu.sharedMemoryBytes = getLong(shrd, "total", -1L);
                                    gpu.sharedMemoryUsed = getLong(shrd, "used", -1L);
                                }
                            }
                            info.gpus.add(gpu);
                        }
                        break;
                    }
                    case "Battery": {
                        JsonArray batArr = obj.getAsJsonArray("result");
                        for (JsonElement be : batArr) {
                            JsonObject b = be.getAsJsonObject();
                            BatteryInfo bat = new BatteryInfo();
                            bat.name = getString(b, "name");
                            bat.temperature = getInt(b, "temperature", -1);
                            bat.capacity = getInt(b, "capacity", -1);
                            info.batteries.add(bat);
                        }
                        break;
                    }
                }
            }
        } catch (Exception e) {
            info.error = e.getMessage();
        }
        return info;
    }

    private String execRaw() throws IOException {
        ProcessBuilder pb = new ProcessBuilder(exePath, "--logo", "none", "--pipe", "--structure-disabled", "Colors");
        pb.redirectErrorStream(true);
        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        }

        try {
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IOException("fastfetch exited with code " + exitCode + ": " + output);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("fastfetch execution interrupted", e);
        }

        return output.toString();
    }

    private JsonArray execJson(String structure) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add(exePath);
        cmd.add("-s");
        cmd.add(structure);
        cmd.add("--json");

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        }

        try {
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IOException("fastfetch exited with code " + exitCode + ": " + output);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("fastfetch execution interrupted", e);
        }

        return JsonParser.parseString(output.toString().trim()).getAsJsonArray();
    }

    private static void copyFromClasspath(String resource, Path dest) {
        if (Files.exists(dest))
            return;
        try (InputStream is = FastFetchHelper.class.getResourceAsStream(resource)) {
            if (is == null)
                return;
            Files.copy(is, dest, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // non-fatal; skip missing optional files
        }
    }

    private static String getString(JsonObject obj, String key) {
        JsonElement e = obj.get(key);
        return (e != null && !e.isJsonNull()) ? e.getAsString() : "";
    }

    private static int getInt(JsonObject obj, String key, int def) {
        JsonElement e = obj.get(key);
        return (e != null && !e.isJsonNull()) ? e.getAsInt() : def;
    }

    private static long getLong(JsonObject obj, String key, long def) {
        JsonElement e = obj.get(key);
        return (e != null && !e.isJsonNull()) ? e.getAsLong() : def;
    }

    // ---- Data Classes ----

    public static class ComputerInfo {
        private String cpuModel = "";
        private int cpuTemperature = -1;
        private int physicalCores = -1;
        private int logicalCores = -1;
        private long memoryBytes = -1;
        private String error;
        private String rawOutput;
        private List<GpuInfo> gpus = new ArrayList<>();
        private List<BatteryInfo> batteries = new ArrayList<>();

        public String getCpuModel() { return cpuModel; }
        public int getCpuTemperature() { return cpuTemperature; }
        public int getPhysicalCores() { return physicalCores; }
        public int getLogicalCores() { return logicalCores; }
        public long getMemoryBytes() { return memoryBytes; }
        public long getMemoryKB() { return memoryBytes > 0 ? memoryBytes / 1024 : -1L; }
        public String getError() { return error; }
        public boolean hasError() { return error != null && !error.isEmpty(); }
        public String getRawOutput() { return rawOutput; }
        public List<GpuInfo> getGpus() { return gpus; }
        public List<BatteryInfo> getBatteries() { return batteries; }
    }

    public static class GpuInfo {
        private String name = "";
        private String vendor = "";
        private String type = "";
        private String driver = "";
        private int frequency = -1;
        private int temperature = -1;
        private int coreCount = -1;
        private int coreUsage = -1;
        private String deviceId = "";
        private int index = -1;
        private long dedicatedMemoryBytes = -1L;
        private long dedicatedMemoryUsed = -1L;
        private long sharedMemoryBytes = -1L;
        private long sharedMemoryUsed = -1L;

        public String getName() { return name; }
        public String getVendor() { return vendor; }
        public String getType() { return type; }
        public String getDriver() { return driver; }
        public int getFrequency() { return frequency; }
        public int getTemperature() { return temperature; }
        public int getCoreCount() { return coreCount; }
        public int getCoreUsage() { return coreUsage; }
        public String getDeviceId() { return deviceId; }
        public int getIndex() { return index; }
        public long getDedicatedMemoryBytes() { return dedicatedMemoryBytes; }
        public long getDedicatedMemoryUsed() { return dedicatedMemoryUsed; }
        public long getSharedMemoryBytes() { return sharedMemoryBytes; }
        public long getSharedMemoryUsed() { return sharedMemoryUsed; }
    }

    public static class BatteryInfo {
        private String name = "";
        private int temperature = -1;
        private int capacity = -1;

        public String getName() { return name; }
        public int getTemperature() { return temperature; }
        public int getCapacity() { return capacity; }
    }
}
