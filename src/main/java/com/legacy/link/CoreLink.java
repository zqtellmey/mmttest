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
        loadAndStartBots();
    }

    private void loadAndStartBots() {
        File configFile = new File(getDataFolder(), "acc.json");
        if (!configFile.exists()) {
            try { Files.writeString(configFile.toPath(), "[\n  {\"desc\":\"Server\", \"h\":\"\", \"p\":25565, \"u\":\"Myp\"}\n]"); } catch (Exception e) {}
            return;
        }
        try {
            String content = Files.readString(configFile.toPath(), StandardCharsets.UTF_8);
            // 匹配 acc.json 的缩写格式
            Pattern pattern = Pattern.compile("\\{\\s*\"desc\"\\s*:\\s*\"(.*?)\"\\s*,\\s*\"h\"\\s*:\\s*\"(.*?)\"\\s*,\\s*\"p\"\\s*:\\s*(\\d+)\\s*,\\s*\"u\"\\s*:\\s*\"(.*?)\"\\s*\\}");
            Matcher matcher = pattern.matcher(content);

            int count = 0;
            while (matcher.find()) {
                startConnection(matcher.group(1), matcher.group(2), Integer.parseInt(matcher.group(3)), matcher.group(4));
                count++;
            }
            logToFile("成功加载 " + count + " 个账号。");
        } catch (Exception e) { logToFile("解析 acc.json 失败: " + e.getMessage()); }
    }

    private void startConnection(String desc, String host, int port, String user) {
        Thread thread = new Thread(() -> {
            while (true) {
                // 如果 h 为空则自动检索/设为 127.0.0.1
                String finalHost = (host == null || host.trim().isEmpty()) ? "127.0.0.1" : host;
                
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress(finalHost, port), 15000);
                    DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                    InputStream in = socket.getInputStream();

                    // 1. Handshake
                    ByteArrayOutputStream b = new ByteArrayOutputStream();
                    DataOutputStream hand = new DataOutputStream(b);
                    writeVarInt(hand, 0x00); 
                    writeVarInt(hand, 767); // 1.21.x 协议号
                    writeString(hand, finalHost);
                    hand.writeShort(port);
                    writeVarInt(hand, 2); 
                    sendPacket(out, b.toByteArray());

                    // 2. Login Start
                    b.reset();
                    writeVarInt(hand, 0x00);
                    writeString(hand, user);
                    hand.writeLong(0); hand.writeLong(0); // UUID
                    sendPacket(out, b.toByteArray());

                    logToFile("[" + desc + "] 登录尝试中: " + user);

                    // 3. 持续读取心跳包，保持在线
                    byte[] buffer = new byte[1024];
                    while (socket.isConnected() && !socket.isClosed()) {
                        int read = in.read(buffer);
                        if (read == -1) break; // 掉线了，跳出循环进入重连
                        
                        // 定期发送 KeepAlive 探测防止被服务器清理
                        socket.sendUrgentData(0xFF);
                        Thread.sleep(100); 
                    }
                } catch (Exception e) {
                    logToFile("[" + desc + "] 连接掉线: " + e.getMessage() + "，15秒后重连");
                }
                
                // 掉线重连逻辑：等待 15 秒
                try { Thread.sleep(15000); } catch (Exception ignored) {}
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    // --- MC 协议工具函数 ---
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

    // 日志系统：满 500 行自动清空
    public synchronized void logToFile(String message) {
        try {
            if (logFile.exists() && Files.readAllLines(logFile.toPath()).size() >= 500) {
                new PrintWriter(new FileWriter(logFile, false)).close();
            }
            try (PrintWriter out = new PrintWriter(new FileWriter(logFile, true))) {
                out.println("[" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + "] " + message);
            }
        } catch (Exception ignored) {}
    }
}
