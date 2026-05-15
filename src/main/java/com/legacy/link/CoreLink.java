package com.legacy;

import org.bukkit.plugin.java.JavaPlugin;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CoreLink extends JavaPlugin {

    @Override
    public void onEnable() {
        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        
        logToFile("CoreLink 守护进程启动，开始加载 acc.json...");
        loadAndStartBots();
    }

    private void loadAndStartBots() {
        File file = new File(getDataFolder(), "acc.json");
        if (!file.exists()) {
            logToFile("错误: 未找到 acc.json。请创建该文件并填入账号信息。");
            return;
        }

        try {
            String content = Files.readString(file.toPath());
            // 匹配格式: {"desc":"xxx","h":"xxx","p":123,"u":"xxx"}
            Pattern pattern = Pattern.compile("\\{\"desc\":\"(.*?)\",\"h\":\"(.*?)\",\"p\":(\\d+),\"u\":\"(.*?)\"\\}");
            Matcher matcher = pattern.matcher(content);

            int count = 0;
            while (matcher.find()) {
                String desc = matcher.group(1);
                String host = matcher.group(2);
                int port = Integer.parseInt(matcher.group(3));
                String user = matcher.group(4);

                // 如果 h 为空，自动切换为 127.0.0.1
                String finalHost = (host == null || host.trim().isEmpty()) ? "127.0.0.1" : host;
                
                // 异步启动每个账号的维持线程
                startConnection(desc, finalHost, port, user);
                count++;
            }
            logToFile("已成功初始化 " + count + " 个账号。");
        } catch (Exception e) {
            logToFile("加载 acc.json 失败: " + e.getMessage());
        }
    }

    private void startConnection(String desc, String host, int port, String user) {
        Thread thread = new Thread(() -> {
            while (true) {
                try (Socket socket = new Socket()) {
                    logToFile("[" + desc + "] 尝试连接: " + host + ":" + port);
                    
                    // 10秒连接超时
                    socket.connect(new InetSocketAddress(host, port), 10000);

                    if (socket.isConnected()) {
                        logToFile("[" + desc + "] 上线成功: 用户名 " + user);
                        
                        // 循环发送心跳数据，维持连接活跃
                        while (!socket.isClosed() && socket.isConnected()) {
                            Thread.sleep(30000); // 每30秒活跃一次
                            socket.sendUrgentData(0xFF); 
                        }
                    }
                } catch (Exception e) {
                    logToFile("[" + desc + "] 连接中断/失败: " + e.getMessage() + " (15秒后重连)");
                }

                try { Thread.sleep(15000); } catch (InterruptedException ex) { break; }
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    // 日志系统：严格执行 500 行清理
    public void logToFile(String message) {
        try {
            File logFile = new File(getDataFolder(), "run.log");
            if (logFile.exists()) {
                List<String> lines = Files.readAllLines(logFile.toPath());
                if (lines.size() >= 500) {
                    // 超过 500 行，清空文件
                    try (PrintWriter writer = new PrintWriter(logFile)) {
                        writer.print("");
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
