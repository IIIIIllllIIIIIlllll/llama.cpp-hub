package org.mark.llamacpp.win;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Windows 开机自启管理器。
 *
 * 在 Startup 目录创建 .lnk 快捷方式，直接指向 llama.cpp-hub.exe（launcher）。
 * launcher 自行处理工作目录、JRE 加载和 JVM 配置（launcher.conf）。
 */
public class AutoStartManager {

    private static final Logger logger = LoggerFactory.getLogger(AutoStartManager.class);

    private static final String SHORTCUT_NAME = "llama.cpp-hub.lnk";
    private static final String APP_NAME = "llama.cpp-hub";
    private static final String LAUNCHER_EXE = "llama.cpp-hub.exe";

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
        String userDir = System.getProperty("user.dir");
        Path launcherPath = Paths.get(userDir, LAUNCHER_EXE);

        if (!Files.exists(launcherPath)) {
            logger.error("未找到 {}，无法设置开机自启", LAUNCHER_EXE);
            return false;
        }

        String startupFolder = getStartupFolderPath();
        try {
            Files.createDirectories(Paths.get(startupFolder));
        } catch (Exception e) {
            logger.warn("创建启动目录失败: {}", e.getMessage());
        }

        String shortcutPath = getShortcutPath();

        logger.info("创建开机自启快捷方式:");
        logger.info("  快捷方式:  {}", shortcutPath);
        logger.info("  目标:      {}", launcherPath);
        logger.info("  工作目录:  {}", userDir);

        String psScript = String.format(
            "$shell = New-Object -ComObject WScript.Shell; " +
            "$sc = $shell.CreateShortcut('%s'); " +
            "$sc.TargetPath = '%s'; " +
            "$sc.WorkingDirectory = '%s'; " +
            "$sc.WindowStyle = 7; " +
            "$sc.Description = '%s'; " +
            "$sc.Save();",
            escapeSingleQuote(shortcutPath),
            escapeSingleQuote(launcherPath.toString()),
            escapeSingleQuote(userDir),
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
