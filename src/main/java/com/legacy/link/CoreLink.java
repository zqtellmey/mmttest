package com.legacy;

import org.bukkit.plugin.java.JavaPlugin;
import java.io.*;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class CoreLink extends JavaPlugin {
    private Process nodeProcess;

    @Override
    public void onEnable() {
        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        logToFile("守护进程启动中...");
        startNodeBot();
    }

    private void startNodeBot() {
        new Thread(() -> {
            try {
                File jsFile = new File(getDataFolder(), "logic.js");
                if (!jsFile.exists()) {
                    logToFile("错误: 未找到 logic.js，请确保它在插件文件夹内。");
                    return;
                }

                // 执行 node logic.js 命令
                ProcessBuilder pb = new ProcessBuilder("node", "logic.js");
                pb.directory(getDataFolder());
                pb.redirectErrorStream(true);
                nodeProcess = pb.start();

                // 读取 Node.js 的输出并写入 run.log
                BufferedReader reader = new BufferedReader(new InputStreamReader(nodeProcess.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    logToFile(line);
                }
            } catch (Exception e) {
                logToFile("启动 Node 进程失败: " + e.getMessage());
            }
        }).start();
    }

    public void logToFile(String message) {
        try {
            File logFile = new File(getDataFolder(), "run.log");
            if (logFile.exists()) {
                List<String> lines = Files.readAllLines(logFile.toPath());
                if (lines.size() >= 500) { // 严格执行 500 行清理要求
                    new FileWriter(logFile, false).close();
                }
            }
            try (PrintWriter out = new PrintWriter(new FileWriter(logFile, true))) {
                String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                out.println("[" + ts + "] " + message);
            }
        } catch (Exception e) {}
    }

    @Override
    public void onDisable() {
        if (nodeProcess != null) nodeProcess.destroy();
    }
}
