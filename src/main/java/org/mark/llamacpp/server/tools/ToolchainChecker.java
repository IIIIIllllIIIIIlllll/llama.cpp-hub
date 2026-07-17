package org.mark.llamacpp.server.tools;

import java.io.File;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public class ToolchainChecker {

    private static final int TIMEOUT_SECONDS = 10;

    public static class CheckResult {
        public String name;
        public boolean available;
        public String version;
        public String path;
        public String details;

        public CheckResult(String name, boolean available, String version, String path, String details) {
            this.name = name;
            this.available = available;
            this.version = version;
            this.path = path;
            this.details = details;
        }
    }

    public static Map<String, CheckResult> checkAll() {
        Map<String, CheckResult> results = new LinkedHashMap<>();
        results.put("os", checkOsInfo());
        results.put("cmake", checkCmake());
        results.put("git", checkGit());
        results.put("cuda", checkCuda());
        results.put("rocm", checkRocm());
        results.put("sycl", checkSycl());
        results.put("vulkan", checkVulkan());
        if (isWindows()) {
            results.put("msvc", checkWindowsBuildTools());
        }
        return results;
    }

    public static CheckResult checkOsInfo() {
        String name = System.getProperty("os.name", "Unknown");
        String version = System.getProperty("os.version", "Unknown");
        String arch = System.getProperty("os.arch", "Unknown");
        return new CheckResult("OS", true, version, arch, name);
    }

    public static CheckResult checkCmake() {
        CommandLineRunner.CommandResult result = CommandLineRunner.execute(new String[]{"cmake", "--version"}, TIMEOUT_SECONDS);
        if (result.getExitCode() != null && result.getExitCode() == 0) {
            String output = result.getOutput();
            String version = extractFirstMatch(output, "(\\d+\\.\\d+\\.\\d+)");
            String path = findExecutable("cmake");
            return new CheckResult("CMake", true, version, path, null);
        }
        return new CheckResult("CMake", false, null, null, "未找到 cmake，请从 https://cmake.org/download/ 安装");
    }

    public static CheckResult checkGit() {
        CommandLineRunner.CommandResult result = CommandLineRunner.execute(new String[]{"git", "--version"}, TIMEOUT_SECONDS);
        if (result.getExitCode() != null && result.getExitCode() == 0) {
            String output = result.getOutput();
            String version = extractFirstMatch(output, "(\\d+\\.\\d+\\.\\d+)");
            String path = findExecutable("git");
            return new CheckResult("Git", true, version, path, null);
        }
        return new CheckResult("Git", false, null, null, "未找到 git，请从 https://git-scm.com/downloads 安装");
    }

    public static CheckResult checkCuda() {
        String nvccCmd = isWindows() ? "nvcc.exe" : "nvcc";
        CommandLineRunner.CommandResult result = CommandLineRunner.execute(new String[]{nvccCmd, "--version"}, TIMEOUT_SECONDS);
        if (result.getExitCode() != null && result.getExitCode() == 0) {
            String output = result.getOutput();
            String version = extractFirstMatch(output, "(\\d+\\.\\d+)");
            String path = findNvccPath();
            return new CheckResult("CUDA", true, version, path, null);
        }
        String cudaPath = System.getenv("CUDA_PATH");
        if (cudaPath != null && !cudaPath.isBlank()) {
            File cudaDir = new File(cudaPath);
            if (cudaDir.isDirectory()) {
                return new CheckResult("CUDA", true, null, cudaPath, "通过 CUDA_PATH 环境变量发现");
            }
        }
        if (isWindows()) {
            File programFiles = new File("C:\\Program Files\\NVIDIA GPU Computing Toolkit\\CUDA");
            File[] dirs = programFiles.listFiles(File::isDirectory);
            if (dirs != null && dirs.length > 0) {
                Arrays.sort(dirs, Comparator.comparing(File::getName).reversed());
                return new CheckResult("CUDA", true, dirs[0].getName(), dirs[0].getAbsolutePath(), null);
            }
        } else {
            File cudaDir = new File("/usr/local/cuda");
            if (cudaDir.isDirectory()) {
                return new CheckResult("CUDA", true, null, cudaDir.getAbsolutePath(), null);
            }
        }
        return new CheckResult("CUDA", false, null, null, "未发现 CUDA，请从 https://developer.nvidia.com/cuda-downloads 安装");
    }

    public static CheckResult checkRocm() {
        if (isWindows()) {
            File rocmRoot = new File("C:\\Program Files\\AMD\\ROCm");
            File[] versions = rocmRoot.listFiles(File::isDirectory);
            if (versions != null && versions.length > 0) {
                Arrays.sort(versions, Comparator.comparing(File::getName).reversed());
                String version = extractRocmVersion(versions[0].getName());
                return new CheckResult("ROCm", true, version, versions[0].getAbsolutePath(), null);
            }
            String[] fallbacks = {
                "C:\\Program Files\\AMD\\AI_Bundle\\ROCm",
                "C:\\Program Files\\AMD\\AI_Bundle"
            };
            for (String fb : fallbacks) {
                File f = new File(fb);
                if (f.isDirectory()) {
                    return new CheckResult("ROCm", true, null, f.getAbsolutePath(), null);
                }
            }
        } else {
            String[] paths = {"/opt/rocm/core-7.14", "/opt/rocm/core", "/opt/rocm", "/usr/local/rocm"};
            for (String p : paths) {
                File dir = new File(p);
                if (dir.isDirectory()) {
                    String version = extractRocmVersion(dir.getName());
                    return new CheckResult("ROCm", true, version, dir.getAbsolutePath(), null);
                }
            }
        }
        return new CheckResult("ROCm", false, null, null, "未发现 ROCm");
    }

    public static CheckResult checkSycl() {
        if (isWindows()) {
            String oneapiRoot = System.getenv("ONEAPI_ROOT");
            if (oneapiRoot != null && !oneapiRoot.isBlank()) {
                File rootDir = new File(oneapiRoot);
                if (rootDir.isDirectory()) {
                    return findSyclInDir(rootDir);
                }
            }
            String[] roots = {
                "C:\\Program Files (x86)\\Intel\\oneAPI",
                "C:\\Program Files\\Intel\\oneAPI"
            };
            for (String root : roots) {
                File rootDir = new File(root);
                if (rootDir.isDirectory()) {
                    CheckResult result = findSyclInDir(rootDir);
                    if (result.available) return result;
                }
            }
        } else {
            String[] paths = {"/opt/intel/oneapi", "/usr/local/intel/oneapi"};
            for (String p : paths) {
                File dir = new File(p);
                if (dir.isDirectory()) {
                    CheckResult result = findSyclInDir(dir);
                    if (result.available) return result;
                }
            }
        }
        return new CheckResult("SYCL (oneAPI)", false, null, null, "未发现 Intel oneAPI");
    }

    private static CheckResult findSyclInDir(File rootDir) {
        File latest = new File(rootDir, "latest");
        if (latest.isDirectory()) {
            File compilerLatest = new File(latest, "compiler");
            if (compilerLatest.isDirectory()) {
                String version = extractVersionFromDir(compilerLatest.getName());
                return new CheckResult("SYCL (oneAPI)", true, version, compilerLatest.getAbsolutePath(), null);
            }
        }
        File compilerLatest = new File(rootDir, "compiler" + File.separator + "latest");
        if (compilerLatest.isDirectory()) {
            String version = null;
            File[] entries = rootDir.listFiles(File::isDirectory);
            if (entries != null) {
                for (File e : entries) {
                    String v = extractVersionFromDir(e.getName());
                    if (v != null) {
                        version = v;
                        break;
                    }
                }
            }
            return new CheckResult("SYCL (oneAPI)", true, version, compilerLatest.getAbsolutePath(), null);
        }
        return new CheckResult("SYCL (oneAPI)", false, null, null, null);
    }

    public static CheckResult checkVulkan() {
        String sdkPath = System.getenv("VULKAN_SDK");
        if (sdkPath != null && !sdkPath.isBlank()) {
            File sdkDir = new File(sdkPath);
            if (sdkDir.isDirectory()) {
                String version = extractVulkanSdkVersion(sdkPath);
                return new CheckResult("Vulkan", true, version, sdkPath, "通过 VULKAN_SDK 环境变量发现");
            }
        }
        if (isWindows()) {
            File sdkRoot = new File("C:\\VulkanSDK");
            File[] versions = sdkRoot.listFiles(File::isDirectory);
            if (versions != null && versions.length > 0) {
                Arrays.sort(versions, Comparator.comparing(File::getName).reversed());
                return new CheckResult("Vulkan", true, versions[0].getName(), versions[0].getAbsolutePath(), null);
            }
        } else {
            File sdkDir = new File("/usr/share/vulkan");
            if (sdkDir.isDirectory()) {
                return new CheckResult("Vulkan", true, null, sdkDir.getAbsolutePath(), null);
            }
        }
        return new CheckResult("Vulkan", false, null, null, "未发现 Vulkan SDK，请从 https://vulkan.lunarg.com/sdk/home 安装");
    }

    private static String extractVulkanSdkVersion(String sdkPath) {
        if (sdkPath == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+\\.\\d+\\.\\d+\\.\\d+)").matcher(sdkPath);
        if (m.find()) return m.group(1);
        m = java.util.regex.Pattern.compile("(\\d+\\.\\d+\\.\\d+)").matcher(sdkPath);
        return m.find() ? m.group(1) : null;
    }

    public static CheckResult checkWindowsBuildTools() {
        if (!isWindows()) {
            return new CheckResult("MSVC Build Tools", false, null, null, "仅 Windows 平台需要");
        }

        CheckResult fromVsWhere = tryVsWhere();
        if (fromVsWhere != null) return fromVsWhere;

        String[] candidates = {
            "C:\\Program Files\\Microsoft Visual Studio\\2022\\Community\\VC\\Tools\\MSVC",
            "C:\\Program Files\\Microsoft Visual Studio\\2022\\Professional\\VC\\Tools\\MSVC",
            "C:\\Program Files\\Microsoft Visual Studio\\2022\\Enterprise\\VC\\Tools\\MSVC",
            "C:\\Program Files (x86)\\Microsoft Visual Studio\\2022\\BuildTools\\VC\\Tools\\MSVC",
            "C:\\Program Files (x86)\\Microsoft Visual Studio\\2019\\Community\\VC\\Tools\\MSVC",
            "C:\\Program Files (x86)\\Microsoft Visual Studio\\2019\\Professional\\VC\\Tools\\MSVC",
            "C:\\Program Files (x86)\\Microsoft Visual Studio\\2019\\Enterprise\\VC\\Tools\\MSVC",
            "C:\\Program Files (x86)\\Microsoft Visual Studio\\2019\\BuildTools\\VC\\Tools\\MSVC",
            "C:\\Program Files (x86)\\Microsoft Visual Studio\\2017\\Community\\VC\\Tools\\MSVC",
            "C:\\Program Files (x86)\\Microsoft Visual Studio\\2017\\Professional\\VC\\Tools\\MSVC",
            "C:\\Program Files (x86)\\Microsoft Visual Studio\\2017\\Enterprise\\VC\\Tools\\MSVC",
            "C:\\Program Files (x86)\\Microsoft Visual Studio\\2017\\BuildTools\\VC\\Tools\\MSVC",
        };

        for (String candidate : candidates) {
            File msvcDir = new File(candidate);
            if (!msvcDir.isDirectory()) continue;
            File[] versions = msvcDir.listFiles(File::isDirectory);
            if (versions == null || versions.length == 0) continue;
            Arrays.sort(versions, Comparator.comparing(File::getName).reversed());
            File latest = versions[0];
            File clExe = findClInMsvcDir(latest);
            if (clExe != null) {
                String vsVersion = detectVsVersion(candidate);
                return new CheckResult("MSVC Build Tools", true, vsVersion + " (" + latest.getName() + ")", clExe.getAbsolutePath(), null);
            }
        }

        return new CheckResult("MSVC Build Tools", false, null, null,
                "未发现 MSVC Build Tools，请安装 Visual Studio Build Tools: https://visualstudio.microsoft.com/downloads/#build-tools-for-visual-studio-2022");
    }

    private static CheckResult tryVsWhere() {
        File vswhere = new File("C:\\Program Files (x86)\\Microsoft Visual Studio\\Installer\\vswhere.exe");
        if (!vswhere.isFile()) return null;

        CommandLineRunner.CommandResult result = CommandLineRunner.execute(
                new String[]{vswhere.getAbsolutePath(), "-format", "json", "-products", "*",
                        "-requires", "Microsoft.VisualStudio.Component.VC.Tools.x86.x64"},
                TIMEOUT_SECONDS);
        if (result.getExitCode() == null || result.getExitCode() != 0) return null;
        String output = result.getOutput();
        if (output == null || output.isBlank()) return null;

        try {
            com.google.gson.JsonArray arr = com.google.gson.JsonParser.parseString(output).getAsJsonArray();
            for (int i = 0; i < arr.size(); i++) {
                com.google.gson.JsonObject entry = arr.get(i).getAsJsonObject();
                String installPath = JsonUtil.getJsonString(entry, "installationPath", null);
                String installVersion = JsonUtil.getJsonString(entry, "installationVersion", null);
                String productLine = null;
                if (entry.has("catalog") && entry.get("catalog").isJsonObject()) {
                    productLine = JsonUtil.getJsonString(entry.get("catalog").getAsJsonObject(), "productLineVersion", null);
                }
                if (installPath == null || installPath.isBlank()) continue;

                File msvcRoot = new File(installPath, "VC" + File.separator + "Tools" + File.separator + "MSVC");
                if (!msvcRoot.isDirectory()) continue;
                File[] versions = msvcRoot.listFiles(File::isDirectory);
                if (versions == null || versions.length == 0) continue;
                Arrays.sort(versions, Comparator.comparing(File::getName).reversed());
                File clExe = findClInMsvcDir(versions[0]);
                if (clExe != null) {
                    String label = (productLine != null ? productLine : (installVersion != null ? installVersion : ""));
                    return new CheckResult("MSVC Build Tools", true, label + " (" + versions[0].getName() + ")", clExe.getAbsolutePath(), null);
                }
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    private static File findClInMsvcDir(File toolchainDir) {
        File clExe = new File(toolchainDir, "bin" + File.separator + "Hostx64" + File.separator + "x64" + File.separator + "cl.exe");
        if (clExe.isFile()) return clExe;
        clExe = new File(toolchainDir, "bin" + File.separator + "Hostx86" + File.separator + "x64" + File.separator + "cl.exe");
        if (clExe.isFile()) return clExe;
        return null;
    }

    private static String detectVsVersion(String msvcPath) {
        if (msvcPath.contains("2022")) return "2022";
        if (msvcPath.contains("2019")) return "2019";
        if (msvcPath.contains("2017")) return "2017";
        return null;
    }

    private static String findExecutable(String name) {
        if (isWindows() && !name.endsWith(".exe")) {
            name += ".exe";
        }
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) return null;
        String separator = isWindows() ? ";" : ":";
        for (String dir : pathEnv.split(separator)) {
            File f = new File(dir, name);
            if (f.isFile()) {
                return f.getAbsolutePath();
            }
        }
        return null;
    }

    private static String findNvccPath() {
        String nvccName = isWindows() ? "nvcc.exe" : "nvcc";
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            String separator = isWindows() ? ";" : ":";
            for (String dir : pathEnv.split(separator)) {
                File f = new File(dir, nvccName);
                if (f.isFile()) return f.getAbsolutePath();
            }
        }
        String cudaPath = System.getenv("CUDA_PATH");
        if (cudaPath != null) {
            File f = new File(cudaPath, "bin" + File.separator + nvccName);
            if (f.isFile()) return f.getAbsolutePath();
        }
        return null;
    }

    private static String extractFirstMatch(String text, String regex) {
        if (text == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(regex).matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private static String extractRocmVersion(String dirName) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+\\.\\d+\\.\\d+)").matcher(dirName);
        if (m.find()) return m.group(1);
        m = java.util.regex.Pattern.compile("(\\d+\\.\\d+)").matcher(dirName);
        return m.find() ? m.group(1) : null;
    }

    private static String extractVersionFromDir(String dirName) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{4}\\.\\d+(\\.\\d+)?)").matcher(dirName);
        if (m.find()) return m.group(1);
        m = java.util.regex.Pattern.compile("(\\d+\\.\\d+\\.\\d+)").matcher(dirName);
        if (m.find()) return m.group(1);
        m = java.util.regex.Pattern.compile("(\\d+\\.\\d+)").matcher(dirName);
        return m.find() ? m.group(1) : null;
    }

    private static boolean isWindows() {
        String osName = System.getProperty("os.name");
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        return os.contains("win");
    }
}
