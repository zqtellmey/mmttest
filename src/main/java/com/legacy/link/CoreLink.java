package com.legacy;

import org.bukkit.plugin.java.JavaPlugin;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CoreLink extends JavaPlugin {

    private File logFile;

    @Override
    public void onEnable() {
        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        
        logFile = new File(getDataFolder(), "run.log");
        if (!logFile.exists()) {
            try { logFile.createNewFile(); } catch (IOException e) { getLogger().severe(e.getMessage()); }
        }

        logToFile("=== 插件启动 ===");
        logToFile("正在从 acc.json 加载账号配置...");
        loadAndStartBots();
    }

    private void loadAndStartBots() {
        File configFile = new File(getDataFolder(), "acc.json");
        if (!configFile.exists()) {
            // 自动生成标准格式模板
            String template = "[\n  {\"desc\":\"Server1\", \"h\":\"\", \"p\":25565, \"u\":\"Player1\"}\n]";
            try {
                Files.writeString(configFile.toPath(), template);
                logToFile("未发现 acc.json，已生成模板。");
            } catch (IOException e) { logToFile("生成失败: " + e.getMessage()); }
            return;
        }

        try {
            String content = Files.readString(configFile.toPath(), StandardCharsets.UTF_8);
            // 优化后的正则表达式：兼容空格和换行
            // 匹配模式： "key"\s*:\s*"value"
            Pattern pattern = Pattern.compile("\\{\\s*\"desc\"\\s*:\\s*\"(.*?)\"\\s*,\\s*\"h\"\\s*:\\s*\"(.*?)\"\\s*,\\s*\"p\"\\s*:\\s*(\\d+)\\s*,\\s*\"u\"\\s*:\\s*\"(.*?)\"\\s*\\}");
            Matcher matcher = pattern.matcher(content);

            int count = 0;
            while (matcher.find()) {
                String desc = matcher.group(1);
                String host = matcher.group(2);
                int port = Integer.parseInt(matcher.group(3));
                String user = matcher.group(4);

                // 手动输入 IP，若为空则自动设为 127.0.0.1
                String finalHost = (host == null || host.trim().isEmpty()) ? "127.0.0.1" : host;
                
                startConnection(desc, finalHost, port, user);
                count++;
            }
            logToFile("成功初始化 " + count + " 个账号。");
        } catch (Exception e) {
            logToFile("解析 acc.json 出错: " + e.getMessage());
        }
    }

    private void startConnection(String desc, String host, int port, String user) {
        Thread thread = new Thread(() -> {
            while (true) {
                try (Socket socket = new Socket()) {
                    logToFile("[" + desc + "] 尝试连接 " + host + ":" + port);
                    socket.connect(new InetSocketAddress(host, port), 10000);

                    if (socket.isConnected()) {
                        logToFile("[" + desc + "] 上线成功: " + user);
                        while (!socket.isClosed() && socket.isConnected()) {
                            Thread.sleep(30000); // 30秒活跃一次
                            socket.sendUrgentData(0xFF); 
                        }
                    }
                } catch (Exception e) {
                    logToFile("[" + desc + "] 连接异常: " + e.getMessage() + "，15秒后重连");
                }
                try { Thread.sleep(15000); } catch (InterruptedException ex) { break; }
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    // 严格的 500 行日志清理系统
    public synchronized void logToFile(String message) {
        try {
            if (logFile.exists()) {
                List<String> lines = Files.readAllLines(logFile.toPath());
                if (lines.size() >= 500) {
                    try (PrintWriter writer = new PrintWriter(new FileWriter(logFile, false))) {
                        writer.print(""); // 满500行清空
                    }
                }
            }
            try (PrintWriter out = new PrintWriter(new FileWriter(logFile, true))) {
                String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                out.println("[" + timestamp + "] " + message);
            }
        } catch (Exception ignored) {}
    }
}
