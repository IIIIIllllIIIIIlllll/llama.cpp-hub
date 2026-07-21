package org.mark.llamacpp.win;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Windows 开机自启管理器。
 *
 * .lnk 快捷方式 → cmd.exe /c "autostart.bat" → javaw.exe
 * autostart.bat 使用 %~dp0 自定位，JVM 参数从当前运行时捕获，路径片段替换为 %APP_DIR% 批处理变量。
 * 部署目录整体搬迁后，重新点一次"开机自启"即可原地重写 .bat 和 .lnk。
 */
public class AutoStartManager {

    private static final Logger logger = LoggerFactory.getLogger(AutoStartManager.class);

    private static final String SHORTCUT_NAME = "llama.cpp-hub.lnk";
    private static final String APP_NAME = "llama.cpp-hub";
    private static final String AUTOSTART_BAT_NAME = "autostart.bat";

    private AutoStartManager() {
    }

    private static String getStartupFolderPath() {
        String appData = System.getenv("APPDATA");
        if (appData == null || appData.isEmpty()) {
            appData = System.getProperty("user.home") + "\\AppData\\Roaming";
        }
        return appData + "\\Microsoft\\Windows\\Start Menu\\Programs\\Startup";
    }

    private static String getShortcutPath() {
        return getStartupFolderPath() + "\\" + SHORTCUT_NAME;
    }

    public static boolean isAutoStartEnabled() {
        File shortcut = new File(getShortcutPath());
        return shortcut.exists() && shortcut.isFile();
    }

    public static boolean enableAutoStart() {
        Path batPath = writeAutoStartBat();
        if (batPath == null) {
            return false;
        }

        if (isAutoStartEnabled()) {
            logger.info("开机自启已启用（autostart.bat 已更新）");
            return true;
        }

        String startupFolder = getStartupFolderPath();
        try {
            Files.createDirectories(Paths.get(startupFolder));
        } catch (Exception e) {
            logger.warn("创建启动目录失败: {}", e.getMessage());
        }

        String shortcutPath = getShortcutPath();
        String systemRoot = System.getenv("SystemRoot");
        if (systemRoot == null || systemRoot.isEmpty()) {
            systemRoot = "C:\\Windows";
        }
        String cmdExe = systemRoot + "\\System32\\cmd.exe";
        String batFullPath = batPath.toString();
        String workDir = batPath.getParent().toString();

        logger.info("创建开机自启快捷方式:");
        logger.info("  快捷方式:  {}", shortcutPath);
        logger.info("  目标:      {} /c \"\" \"{}\"", cmdExe, batFullPath);
        logger.info("  工作目录:  {}", workDir);

        String psScript = String.format(
            "$shell = New-Object -ComObject WScript.Shell; " +
            "$sc = $shell.CreateShortcut('%s'); " +
            "$sc.TargetPath = '%s'; " +
            "$sc.Arguments = '%s'; " +
            "$sc.WorkingDirectory = '%s'; " +
            "$sc.WindowStyle = 7; " +
            "$sc.Description = '%s'; " +
            "$sc.Save();",
            escapeSingleQuote(shortcutPath),
            escapeSingleQuote(cmdExe),
            escapeSingleQuote("/c \"\" \"" + batFullPath + "\""),
            escapeSingleQuote(workDir),
            escapeSingleQuote(APP_NAME)
        );

        boolean success = executePowerShell(psScript);
        if (success) {
            File f = new File(shortcutPath);
            if (f.exists() && f.length() > 0) {
                logger.info("开机自启已启用，快捷方式: {} 字节", f.length());
            } else {
                logger.warn("快捷方式状态异常: exists={}, size={}", f.exists(), f.length());
            }
        }
        return success;
    }

    public static boolean disableAutoStart() {
        String shortcutPath = getShortcutPath();
        File shortcut = new File(shortcutPath);

        if (!shortcut.exists()) {
            logger.info("开机自启未启用");
            return true;
        }

        logger.info("删除开机自启快捷方式: {}", shortcutPath);

        String psScript = String.format(
            "Remove-Item -LiteralPath '%s' -Force -ErrorAction Stop;",
            escapeSingleQuote(shortcutPath)
        );

        boolean success = executePowerShell(psScript);
        if (!success) {
            success = shortcut.delete();
            if (success) {
                logger.info("开机自启已禁用（Java 删除）");
            }
        } else {
            logger.info("开机自启已禁用");
        }
        return success;
    }

    // ==================== autostart.bat 生成 ====================

    private static Path writeAutoStartBat() {
        String userDir = System.getProperty("user.dir");
        String javaHome = System.getProperty("java.home");
        Path batPath = Paths.get(userDir, AUTOSTART_BAT_NAME);

        // 计算 javaw 相对路径（从 java.home 推导，而非写死 jre\bin\javaw.exe）
        Path userDirAbs = Paths.get(userDir).toAbsolutePath().normalize();
        Path javaHomeAbs = Paths.get(javaHome).toAbsolutePath().normalize();
        String javawRelative;
        String javaHomeReplacement; // 用于替换 JVM 参数中的 javaHome 路径片段
        if (javaHomeAbs.startsWith(userDirAbs)) {
            Path rel = userDirAbs.relativize(javaHomeAbs);
            javawRelative = rel.toString() + "\\bin\\javaw.exe";
            javaHomeReplacement = "%APP_DIR%\\" + rel.toString();
            logger.info("JRE 已捆绑: appRoot + {} ", rel);
        } else {
            javawRelative = "javaw.exe";
            javaHomeReplacement = "%APP_DIR%\\jre";
            logger.info("JRE 未捆绑，使用系统 javaw.exe");
        }

        // 收集 JVM 参数，过滤不可移植/内部参数，路径片段替换为 %APP_DIR% 批处理变量
        List<String> jvmArgs = ManagementFactory.getRuntimeMXBean().getInputArguments();
        StringBuilder jvmArgsLine = new StringBuilder();
        for (String arg : jvmArgs) {
            if (!keepJvmArg(arg)) {
                continue;
            }
            String fixed = arg;
            // 先换 javaHome（更具体），再换 userDir
            fixed = fixed.replace(javaHome, javaHomeReplacement);
            fixed = fixed.replace(javaHome.replace('\\', '/'), javaHomeReplacement.replace('\\', '/'));
            fixed = fixed.replace(userDir, "%APP_DIR%");
            fixed = fixed.replace(userDir.replace('\\', '/'), "%APP_DIR%");
            jvmArgsLine.append("\"").append(fixed).append("\" ");
        }

        StringBuilder bat = new StringBuilder();
        bat.append("@echo off\r\n");
        bat.append("setlocal EnableExtensions\r\n");
        bat.append("cd /d \"%~dp0\"\r\n");
        bat.append("set \"APP_DIR=%CD%\"\r\n");
        bat.append("start \"\" \"").append(javawRelative).append("\" ")
           .append(jvmArgsLine)
           .append("-classpath \"./classes;./lib/*\" org.mark.llamacpp.server.LlamaServer\r\n");
        bat.append("endlocal\r\n");

        try {
            Files.write(batPath, bat.toString().getBytes(StandardCharsets.UTF_8));
            logger.info("已生成 autostart.bat ({} 字节)", bat.length());
            return batPath;
        } catch (IOException e) {
            logger.error("写入 autostart.bat 失败: {}", e.getMessage());
            return null;
        }
    }

    // 过滤不可移植的 JVM 内部参数
    private static boolean keepJvmArg(String arg) {
        if (arg.startsWith("-agentlib:")) return false;
        if (arg.startsWith("-javaagent:")) return false;
        if (arg.startsWith("-classpath") || arg.startsWith("--class-path")) return false;
        if (arg.startsWith("-Xlockword:")) return false;
        if (arg.startsWith("-XX:+EnsureHashed")) return false;
        if (arg.startsWith("-Dsun.java.command")) return false;
        if (arg.startsWith("-Dsun.java.launcher")) return false;
        if (arg.startsWith("-Djava.class.path")) return false;
        if (arg.startsWith("-Djava.home")) return false;
        if (arg.startsWith("-Duser.dir")) return false;
        if (arg.startsWith("-Djava.library.path")) return false;
        if (arg.contains("&") || arg.contains("|") || arg.contains("<")
                || arg.contains(">") || arg.contains("^") || arg.contains("%")) return false;
        return true;
    }

    // ==================== PowerShell 工具 ====================

    private static boolean executePowerShell(String script) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                "powershell.exe", "-ExecutionPolicy", "Bypass", "-NoProfile", "-Command", script
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    logger.debug("PS: {}", line);
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                logger.error("PowerShell 退出码: {}", exitCode);
                return false;
            }
            return true;
        } catch (Exception e) {
            logger.error("执行 PowerShell 失败", e);
            return false;
        }
    }

    private static String escapeSingleQuote(String s) {
        if (s == null) return "";
        return s.replace("'", "''");
    }
}
