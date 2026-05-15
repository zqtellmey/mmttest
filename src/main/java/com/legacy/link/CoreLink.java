package com.legacy;

import org.bukkit.plugin.java.JavaPlugin;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class CoreLink extends JavaPlugin {

    // 1. 账号配置类
    static class BotAccount {
        String desc, host, user;
        int port;
        public BotAccount(String desc, String host, int port, String user) {
            this.desc = desc; this.host = host; this.port = port; this.user = user;
        }
    }

    private final List<BotAccount> accounts = new ArrayList<>();
    private final List<Thread> botThreads = new ArrayList<>();

    @Override
    public void onEnable() {
        if (!getDataFolder().exists()) getDataFolder().mkdirs();

        // --- 2. 账号池配置 (按你要求的缩写格式) ---
        // 如果 host 为空 ""，后续逻辑可以改为自动获取，目前按你提供的填入
        accounts.add(new BotAccount("crssssrve", "xx.xx.234.50", 5091, "How"));
        accounts.add(new BotAccount("zenix", "xx.xx.164.32", 10770, "How"));

        logToFile("CoreLink 纯 Java 增强版已启动，正在初始化账号...");

        // 启动账号线程
        for (BotAccount acc : accounts) {
            startBotThread(acc);
            try { Thread.sleep(2000); } catch (InterruptedException e) { break; }
        }
    }

    private void startBotThread(BotAccount acc) {
        Thread t = new Thread(() -> {
            while (true) {
                try (Socket socket = new Socket()) {
                    String targetHost = (acc.host == null || acc.host.isEmpty()) ? "127.0.0.1" : acc.host;
                    
                    logToFile("正在尝试连接 [" + acc.desc + "]: " + targetHost + ":" + acc.port);
                    
                    // 设置 10 秒连接超时
                    socket.connect(new InetSocketAddress(targetHost, acc.port), 10000);

                    if (socket.isConnected()) {
                        logToFile("上线成功: " + acc.user + " @ " + acc.desc);
                        
                        // 保持在线循环
                        while (!socket.isClosed() && socket.isConnected()) {
                            // 每 30 秒进行一次活跃度检查/模拟
                            Thread.sleep(30000);
                            logToFile("调度: " + acc.user + " @ " + acc.desc + " 状态正常");
                            
                            // 发送一个字节探测连接是否真的存活
                            socket.sendUrgentData(0xFF);
                        }
                    }
                } catch (Exception e) {
                    logToFile("连接异常 [" + acc.desc + "]: " + e.getMessage());
                }
                
                // 掉线或失败后 15 秒重连
                try { Thread.sleep(15000); } catch (InterruptedException e) { break; }
            }
        });
        t.setDaemon(true);
        t.start();
        botThreads.add(t);
    }

    // --- 3. 严格的日志系统 (满500行自动清空) ---
    public void logToFile(String message) {
        try {
            File logFile = new File(getDataFolder(), "run.log");
            if (logFile.exists()) {
                List<String> lines = Files.readAllLines(logFile.toPath());
                // 严格遵守要求：超过或等于 500 行清空文件
                if (lines.size() >= 500) {
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

    @Override
    public void onDisable() {
        logToFile("插件已卸载，正在关闭所有连接...");
        // 实际上线程由于是守护线程且依赖 socket 状态，会自动随插件关闭而结束
    }
}
