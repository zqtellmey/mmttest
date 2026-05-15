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
        // 确保插件目录存在
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }
        
        // 初始化日志文件对象
        logFile = new File(getDataFolder(), "run.log");
        
        // 如果日志文件不存在，立即创建一个空的，防止读取报错
        if (!logFile.exists()) {
            try {
                logFile.createNewFile();
            } catch (IOException e) {
                getLogger().severe("无法创建日志文件: " + e.getMessage());
            }
        }

        logToFile("=== 插件启动 ===");
        logToFile("正在从 acc.json 加载账号配置...");
        loadAndStartBots();
    }

    private void loadAndStartBots() {
        File configFile = new File(getDataFolder(), "acc.json");
        
        // 如果没有 acc.json，生成一个默认模板，方便你在面板修改
        if (!configFile.exists()) {
            String template = "[\n  {\"desc\":\"示例服务器\",\"h\":\"\",\"p\":25565,\"u\":\"Player\"}\n]";
            try {
                Files.writeString(configFile.toPath(), template);
                logToFile("未发现 acc.json，已生成模板。");
            } catch (IOException e) {
                logToFile("生成 acc.json 失败: " + e.getMessage());
            }
            return;
        }

        try {
            String content = Files.readString(configFile.toPath(), StandardCharsets.UTF_8);
            // 正则匹配 JSON 格式
            Pattern pattern = Pattern.compile("\\{\"desc\":\"(.*?)\",\"h\":\"(.*?)\",\"p\":(\\d+),\"u\":\"(.*?)\"\\}");
            Matcher matcher = pattern.matcher(content);

            int count = 0;
            while (matcher.find()) {
                String desc = matcher.group(1);
                String host = matcher.group(2);
                int port = Integer.parseInt(matcher.group(3));
                String user = matcher.group(4);

                // 自动 IP 处理：如果 h 为空，设为 127.0.0.1
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
                    
                    // 连接超时设为 10 秒
                    socket.connect(new InetSocketAddress(host, port), 10000);

                    if (socket.isConnected()) {
                        logToFile("[" + desc + "] 上线成功: " + user);
                        
                        while (!socket.isClosed() && socket.isConnected()) {
                            // 每 30 秒维持心跳
                            Thread.sleep(30000);
                            socket.sendUrgentData(0xFF); 
                        }
                    }
                } catch (Exception e) {
                    logToFile("[" + desc + "] 连接异常: " + e.getMessage() + "，15秒后重连");
                }

                try { 
                    Thread.sleep(15000); 
                } catch (InterruptedException ex) { 
                    break; 
                }
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    // 日志系统：带 500 行清理功能
    public synchronized void logToFile(String message) {
        try {
            if (logFile.exists()) {
                List<String> lines = Files.readAllLines(logFile.toPath());
                // 严格执行要求：满 500 行则清空文件
                if (lines.size() >= 500) {
                    try (PrintWriter writer = new PrintWriter(new FileWriter(logFile, false))) {
                        writer.print(""); 
                    }
                }
            }

            try (PrintWriter out = new PrintWriter(new FileWriter(logFile, true))) {
                String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                out.println("[" + timestamp + "] " + message);
            }
        } catch (Exception ignored) {
            // 最后的防线：如果文件操作失败，打印到控制台以供调试
            System.out.println("[CoreLink Error] Log failed: " + message);
        }
    }
}
