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
        // 1. 确保目录和文件初始化
        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        logFile = new File(getDataFolder(), "run.log");
        
        try {
            if (!logFile.exists()) logFile.createNewFile();
        } catch (IOException e) {
            getLogger().severe("Cannot create log file: " + e.getMessage());
        }

        logToFile("=== 插件已成功载入系统 ===");
        loadAndStartBots();
    }

    private void loadAndStartBots() {
        File configFile = new File(getDataFolder(), "acc.json");
        if (!configFile.exists()) {
            logToFile("错误: 未找到 acc.json 配置文件。");
            return;
        }

        try {
            String content = Files.readString(configFile.toPath(), StandardCharsets.UTF_8);
            // 增强型正则：匹配 {"desc":"xxx","h":"xxx","p":123,"u":"xxx"}
            Pattern pattern = Pattern.compile("\\{\\s*\"desc\"\\s*:\\s*\"(.*?)\"\\s*,\\s*\"h\"\\s*:\\s*\"(.*?)\"\\s*,\\s*\"p\"\\s*:\\s*(\\d+)\\s*,\\s*\"u\"\\s*:\\s*\"(.*?)\"\\s*\\}");
            Matcher matcher = pattern.matcher(content);

            int count = 0;
            while (matcher.find()) {
                String desc = matcher.group(1);
                String host = matcher.group(2);
                int port = Integer.parseInt(matcher.group(3));
                String user = matcher.group(4);

                // 自动 IP 处理
                String finalHost = (host == null || host.trim().isEmpty()) ? "127.0.0.1" : host;
                
                startBotProcess(desc, finalHost, port, user);
                count++;
            }
            logToFile("配置加载完毕，共初始化 " + count + " 个账号。");
        } catch (Exception e) {
            logToFile("读取 acc.json 异常: " + e.getMessage());
        }
    }

    private void startBotProcess(String desc, String host, int port, String user) {
        Thread t = new Thread(() -> {
            while (true) {
                try (Socket socket = new Socket()) {
                    logToFile("[" + desc + "] 正在建立连接: " + host + ":" + port);
                    socket.connect(new InetSocketAddress(host, port), 10000);
                    
                    DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                    InputStream in = socket.getInputStream();

                    // --- 模拟 MC 离线模式登录流程 ---
                    // Handshake
                    ByteArrayOutputStream b = new ByteArrayOutputStream();
                    DataOutputStream hand = new DataOutputStream(b);
                    writeVarInt(hand, 0x00); 
                    writeVarInt(hand, 767); 
                    writeString(hand, host);
                    hand.writeShort(port);
                    writeVarInt(hand, 2); 
                    sendPacket(out, b.toByteArray());

                    // Login Start
                    b.reset();
                    writeVarInt(hand, 0x00);
                    writeString(hand, user);
                    hand.writeLong(0); hand.writeLong(0);
                    sendPacket(out, b.toByteArray());

                    logToFile("[" + desc + "] 登录数据已发送，用户: " + user);

                    // 维持在线：读取服务器返回并防止超时
                    byte[] buf = new byte[1024];
                    while (socket.isConnected() && !socket.isClosed()) {
                        int r = in.read(buf);
                        if (r == -1) break; 
                        
                        // 定期发送 KeepAlive
                        socket.sendUrgentData(0xFF);
                        Thread.sleep(1000); 
                    }
                } catch (Exception e) {
                    logToFile("[" + desc + "] 状态离线: " + e.getMessage() + " (15秒后自动重连)");
                }
                try { Thread.sleep(15000); } catch (InterruptedException ignored) {}
            }
        });
        t.setDaemon(true);
        t.start();
    }

    // --- 协议工具 ---
    private void sendPacket(DataOutputStream out, byte[] data) throws IOException {
        writeVarInt(out, data.length);
        out.write(data);
    }

    private void writeVarInt(DataOutputStream out, int value) throws IOException {
        while ((value & 0xFFFFFF80) != 0L) {
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.writeByte(value & 0x7F);
    }

    private void writeString(DataOutputStream out, String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, b.length);
        out.write(b);
    }

    // --- 严格的日志系统 ---
    public synchronized void logToFile(String message) {
        try {
            // 检查行数：如果满 500 行则立即清空
            if (logFile.exists()) {
                List<String> lines = Files.readAllLines(logFile.toPath());
                if (lines.size() >= 500) {
                    Files.writeString(logFile.toPath(), "", StandardCharsets.UTF_8);
                }
            }

            // 使用 FileWriter 并立即关闭以确保翼龙面板能刷新文件状态
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(logFile, true))) {
                String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                writer.write("[" + ts + "] " + message);
                writer.newLine();
                writer.flush(); // 强制刷新流
            }
        } catch (Exception ignored) {}
    }
}
