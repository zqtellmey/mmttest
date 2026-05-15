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
        logToFile("=== 1:1 字节流复刻 JS 模式 (1.21.11) ===");
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
                    s.setTcpNoDelay(true); // 必须！防止 JS 那种小确认包被延迟
                    s.setKeepAlive(true);
                    s.setSoTimeout(30000);
                    s.connect(new InetSocketAddress(host, port), 10000);
                    
                    OutputStream out = s.getOutputStream();
                    InputStream in = s.getInputStream();

                    // --- [JS 复刻] 1. Handshake (状态设为 2) ---
                    ByteArrayOutputStream b = new ByteArrayOutputStream();
                    writeVarInt(b, 0x00); // Packet ID
                    writeVarInt(b, 774);  // 1.21.11
                    writeString(b, host);
                    b.write((port >> 8) & 0xFF); // 端口高位
                    b.write(port & 0xFF);        // 端口低位
                    writeVarInt(b, 2);    // Next State: 2
                    sendRawPacket(out, b.toByteArray());

                    // --- [JS 复刻] 2. Login Start ---
                    b.reset();
                    writeVarInt(b, 0x00); // Packet ID
                    writeString(b, user);
                    // 离线 UUID 固定生成
                    UUID id = UUID.nameUUIDFromBytes(("OfflinePlayer:" + user).getBytes(StandardCharsets.UTF_8));
                    DataOutputStream d = new DataOutputStream(b);
                    d.writeLong(id.getMostSignificantBits());
                    d.writeLong(id.getLeastSignificantBits());
                    sendRawPacket(out, b.toByteArray());

                    logToFile("[" + desc + "] JS 字节流握手完成: " + user);

                    int protocolState = 2; // 2:LOGIN, 3:CONFIG, 4:PLAY

                    while (!s.isClosed()) {
                        int size = readVarInt(in);
                        if (size <= 0) break;

                        // 核心：JS 风格的一次性 Buffer 读取
                        byte[] buffer = new byte[size];
                        int totalRead = 0;
                        while (totalRead < size) {
                            int r = in.read(buffer, totalRead, size - totalRead);
                            if (r == -1) break;
                            totalRead += r;
                        }

                        // 直接解析第一个字节：Packet ID
                        ByteArrayInputStream bis = new ByteArrayInputStream(buffer);
                        int packetId = readVarInt(bis);

                        if (protocolState == 2) {
                            if (packetId == 0x02) { // LOGIN_SUCCESS
                                // [JS 核心动作] 立即发送 0x03 确认包，包长固定为 1
                                out.write(new byte[]{0x01, 0x03}); 
                                out.flush();
                                protocolState = 3;
                                logToFile("[" + desc + "] 状态: CONFIG (已发送确认)");
                            } else if (packetId == 0x03) { // SET_COMPRESSION
                                // 1.21.11 登录时如果服务器开了压缩，这里必须处理，否则协议会错位
                                // 但通常挂机 Bot 这里选择不解压，仅做跳过即可
                            }
                        } 
                        else if (protocolState == 3) {
                            if (packetId == 0x01) { // CONFIG_KEEP_ALIVE
                                // 原样写回 KeepAlive ID
                                ByteArrayOutputStream rb = new ByteArrayOutputStream();
                                writeVarInt(rb, 0x01);
                                rb.write(buffer, size - 8, 8); // 最后的 8 字节是 Long 型 ID
                                sendRawPacket(out, rb.toByteArray());
                            } else if (packetId == 0x03) { // CONFIG_FINISHED
                                out.write(new byte[]{0x01, 0x03}); // 发送确认进入 PLAY
                                out.flush();
                                protocolState = 4;
                                logToFile("[" + desc + "] 状态: PLAY (保持在线)");
                            }
                        } 
                        else if (protocolState == 4) {
                            if (packetId == 0x26) { // PLAY_KEEP_ALIVE (1.21.x)
                                ByteArrayOutputStream rb = new ByteArrayOutputStream();
                                writeVarInt(rb, 0x15); // Play 响应 ID 为 0x15
                                rb.write(buffer, size - 8, 8);
                                sendRawPacket(out, rb.toByteArray());
                            }
                        }
                    }
                } catch (Exception e) {
                    logToFile("[" + desc + "] 掉线/异常: " + (e.getMessage() == null ? "Socket Closed" : e.getMessage()));
                }
                // 手动重连间隔
                try { Thread.sleep(15000); } catch (Exception ignored) {}
            }
        }).start();
    }

    // --- 字节流级工具函数 ---

    private void sendRawPacket(OutputStream out, byte[] data) throws IOException {
        writeVarInt(out, data.length);
        out.write(data);
        out.flush();
    }

    private void writeVarInt(OutputStream out, int v) throws IOException {
        while ((v & 0xFFFFFF80) != 0L) {
            out.write((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.write(v & 0x7F);
    }

    private int readVarInt(InputStream in) throws IOException {
        int i = 0, j = 0;
        while (true) {
            int k = in.read();
            if (k == -1) return -1;
            i |= (k & 0x7F) << j++ * 7;
            if (j > 5) return -1;
            if ((k & 0x80) != 128) break;
        }
        return i;
    }

    private void writeString(OutputStream out, String s) throws IOException {
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
            }
        } catch (Exception ignored) {}
    }
}
