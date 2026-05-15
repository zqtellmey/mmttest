package com.legacy;

import org.bukkit.plugin.java.JavaPlugin;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CoreLink extends JavaPlugin {

    private File logFile;

    @Override
    public void onEnable() {
        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        logFile = new File(getDataFolder(), "run.log");
        try { if (!logFile.exists()) logFile.createNewFile(); } catch (IOException ignored) {}

        logToFile("=== 深度模拟 JS 端处理循环启动 (774) ===");
        loadAccounts();
    }

    private void loadAccounts() {
        File f = new File(getDataFolder(), "acc.json");
        if (!f.exists()) return;
        try {
            String content = Files.readString(f.toPath(), StandardCharsets.UTF_8);
            Pattern p = Pattern.compile("\\{\\s*\"desc\"\\s*:\\s*\"(.*?)\"\\s*,\\s*\"h\"\\s*:\\s*\"(.*?)\"\\s*,\\s*\"p\"\\s*:\\s*(\\d+)\\s*,\\s*\"u\"\\s*:\\s*\"(.*?)\"\\s*\\}");
            Matcher m = p.matcher(content);
            while (m.find()) {
                String host = (m.group(2).isEmpty()) ? "127.0.0.1" : m.group(2);
                startBot(m.group(1), host, Integer.parseInt(m.group(3)), m.group(4));
            }
        } catch (Exception ignored) {}
    }

    private void startBot(String desc, String host, int port, String user) {
        new Thread(() -> {
            while (true) {
                try (Socket s = new Socket()) {
                    s.connect(new InetSocketAddress(host, port), 10000);
                    s.setSoTimeout(30000); // 增加 Socket 超时控制
                    DataOutputStream out = new DataOutputStream(s.getOutputStream());
                    DataInputStream in = new DataInputStream(s.getInputStream());

                    // --- 1. Handshake ---
                    ByteArrayOutputStream b = new ByteArrayOutputStream();
                    DataOutputStream pkg = new DataOutputStream(b);
                    writeVarInt(pkg, 0x00);
                    writeVarInt(pkg, 774);
                    writeString(pkg, host);
                    pkg.writeShort(port);
                    writeVarInt(pkg, 2); 
                    sendPacket(out, b.toByteArray());

                    // --- 2. Login Start ---
                    b.reset();
                    writeVarInt(pkg, 0x00);
                    writeString(pkg, user);
                    UUID id = UUID.nameUUIDFromBytes(("OfflinePlayer:" + user).getBytes(StandardCharsets.UTF_8));
                    pkg.writeLong(id.getMostSignificantBits());
                    pkg.writeLong(id.getLeastSignificantBits());
                    sendPacket(out, b.toByteArray());

                    logToFile("[" + desc + "] 模拟 JS 发送登录: " + user);

                    int state = 2; // 2:LOGIN, 3:CONFIG, 4:PLAY

                    while (!s.isClosed()) {
                        int len = readVarInt(in);
                        if (len <= 0) break;

                        // 将当前包读取到内存，防止长包阻塞
                        byte[] data = new byte[len];
                        in.readFully(data);
                        DataInputStream packetIn = new DataInputStream(new ByteArrayInputStream(data));
                        int packetId = readVarInt(packetIn);

                        // --- 3. 仿 Mineflayer 内部事件处理器 ---
                        if (state == 2) { // LOGIN 阶段
                            if (packetId == 0x02) { // Login Success
                                b.reset();
                                writeVarInt(pkg, 0x03); // Acknowledge Login
                                sendPacket(out, b.toByteArray());
                                state = 3;
                                logToFile("[" + desc + "] 进入配置阶段...");
                            } else if (packetId == 0x03) { // Compression
                                // 忽略压缩设置以简化 Java 实现
                            }
                        } 
                        else if (state == 3) { // CONFIGURATION 阶段
                            if (packetId == 0x03) { // Finish Configuration
                                b.reset();
                                writeVarInt(pkg, 0x03); // Acknowledge Finish Config
                                sendPacket(out, b.toByteArray());
                                state = 4;
                                logToFile("[" + desc + "] 登录成功，已保持在线");
                            } else if (packetId == 0x01) { // Keep Alive (Config 阶段的心跳)
                                long keepAliveId = packetIn.readLong();
                                b.reset();
                                writeVarInt(pkg, 0x01); 
                                pkg.writeLong(keepAliveId);
                                sendPacket(out, b.toByteArray());
                            } else if (packetId == 0x00) { // Plugin Message
                                // 模拟 JS 响应：忽略但维持流，不处理特定的 Brand 信息
                            }
                        } 
                        else if (state == 4) { // PLAY 阶段
                            if (packetId == 0x26) { // Play 阶段 Keep Alive (1.21.x ID 为 0x26)
                                long keepAliveId = packetIn.readLong();
                                b.reset();
                                writeVarInt(pkg, 0x15); // 回应包 ID (1.21.x Play KeepAlive Response 通常是 0x15)
                                pkg.writeLong(keepAliveId);
                                sendPacket(out, b.toByteArray());
                            }
                        }
                        
                        // 每次循环末尾发送紧急数据维持 TCP 连接
                        s.sendUrgentData(0xFF);
                    }
                } catch (Exception e) {
                    logToFile("[" + desc + "] 掉线: " + e.getMessage());
                }
                try { Thread.sleep(15000); } catch (InterruptedException ignored) {}
            }
        }).start();
    }

    // --- 工具类：严格遵循 Minecraft 协议 ---
    private void sendPacket(DataOutputStream out, byte[] data) throws IOException {
        writeVarInt(out, data.length);
        out.write(data);
        out.flush();
    }

    private void writeVarInt(DataOutputStream out, int v) throws IOException {
        while ((v & 0xFFFFFF80) != 0L) {
            out.writeByte((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.writeByte(v & 0x7F);
    }

    private int readVarInt(DataInputStream in) throws IOException {
        int i = 0, j = 0;
        while (true) {
            int k = in.readByte();
            i |= (k & 0x7F) << j++ * 7;
            if (j > 5) return -1;
            if ((k & 0x80) != 128) break;
        }
        return i;
    }

    private int getVarIntSize(int v) {
        if ((v & 0xFFFFFF80) == 0) return 1;
        if ((v & 0xFFFFC000) == 0) return 2;
        return 3;
    }

    private void writeString(DataOutputStream out, String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, b.length);
        out.write(b);
    }

    public synchronized void logToFile(String msg) {
        try {
            if (logFile.exists() && Files.readAllLines(logFile.toPath()).size() >= 500) {
                Files.writeString(logFile.toPath(), "", StandardCharsets.UTF_8);
            }
            try (BufferedWriter w = new BufferedWriter(new FileWriter(logFile, true))) {
                String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                w.write("[" + ts + "] " + msg);
                w.newLine();
                w.flush();
            }
        } catch (Exception ignored) {}
    }
}
