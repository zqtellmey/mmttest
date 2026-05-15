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
        logToFile("=== 1:1 像素级复刻 JS 协议逻辑 (1.21.11) ===");
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
                // 如果 IP 为空，则自动获取 (127.0.0.1)
                String host = (m.group(2).isEmpty()) ? "127.0.0.1" : m.group(2);
                startBot(m.group(1), host, Integer.parseInt(m.group(3)), m.group(4));
            }
        } catch (Exception ignored) {}
    }

    private void startBot(String desc, String host, int port, String user) {
        new Thread(() -> {
            while (true) {
                try (Socket s = new Socket()) {
                    s.setTcpNoDelay(true); // 极其重要：确保小包（确认包）立即发出，不等待缓冲区
                    s.setSoTimeout(30000);
                    s.connect(new InetSocketAddress(host, port), 10000);
                    
                    OutputStream out = s.getOutputStream();
                    InputStream in = s.getInputStream();

                    // --- STEP 1: Handshake (模仿 JS: client.write('handshake', ...)) ---
                    writePacket(out, buildHandshake(host, port));

                    // --- STEP 2: Login Start (模仿 JS: client.write('login_start', ...)) ---
                    writePacket(out, buildLoginStart(user));

                    logToFile("[" + desc + "] 步骤 1&2 已发送 (JS 对齐)");

                    int protocolState = 2; // 2:LOGIN, 3:CONFIG, 4:PLAY

                    while (!s.isClosed()) {
                        int size = readVarInt(in);
                        if (size <= 0) break;

                        // 模仿 JS 的解包：先全部读入内存，保证流的绝对同步
                        byte[] data = new byte[size];
                        int read = 0;
                        while(read < size) {
                            int r = in.read(data, read, size - read);
                            if(r == -1) break;
                            read += r;
                        }

                        DataInputStream pIn = new DataInputStream(new ByteArrayInputStream(data));
                        int packetId = readVarInt(pIn);

                        if (protocolState == 2) {
                            if (packetId == 0x02) { // Login Success
                                // 必须立即回传 Login Acknowledged (0x03)
                                writePacket(out, new byte[]{0x03}); 
                                protocolState = 3;
                                logToFile("[" + desc + "] 状态: CONFIG (同步完成)");
                            }
                        } 
                        else if (protocolState == 3) {
                            if (packetId == 0x01) { // Config KeepAlive
                                long keepId = pIn.readLong();
                                ByteArrayOutputStream b = new ByteArrayOutputStream();
                                writeVarInt(b, 0x01);
                                new DataOutputStream(b).writeLong(keepId);
                                writePacket(out, b.toByteArray());
                            } else if (packetId == 0x03) { // Finish Configuration
                                writePacket(out, new byte[]{0x03}); // 回传 Finish Acknowledge
                                protocolState = 4;
                                logToFile("[" + desc + "] 状态: PLAY (保持在线)");
                            }
                        } 
                        else if (protocolState == 4) {
                            if (packetId == 0x26) { // Play KeepAlive
                                long keepId = pIn.readLong();
                                ByteArrayOutputStream b = new ByteArrayOutputStream();
                                writeVarInt(b, 0x15); // Play 阶段响应 ID 是 0x15
                                new DataOutputStream(b).writeLong(keepId);
                                writePacket(out, b.toByteArray());
                            }
                        }
                    }
                } catch (Exception e) {
                    logToFile("[" + desc + "] 异常中断: " + e.getMessage());
                }
                try { Thread.sleep(15000); } catch (Exception ignored) {}
            }
        }).start();
    }

    // --- 数据包构造 (1:1 像素级复刻 JS 结构) ---

    private byte[] buildHandshake(String host, int port) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        writeVarInt(b, 0x00); // Packet ID
        writeVarInt(b, 774);  // Protocol 1.21.11
        writeString(b, host);
        b.write((port >> 8) & 0xFF);
        b.write(port & 0xFF);
        writeVarInt(b, 2);    // Next State: Login
        return b.toByteArray();
    }

    private byte[] buildLoginStart(String user) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        writeVarInt(b, 0x00); // Packet ID
        writeString(b, user);
        // 离线 UUID (JS 版固定逻辑)
        UUID id = UUID.nameUUIDFromBytes(("OfflinePlayer:" + user).getBytes(StandardCharsets.UTF_8));
        DataOutputStream d = new DataOutputStream(b);
        d.writeLong(id.getMostSignificantBits());
        d.writeLong(id.getLeastSignificantBits());
        return b.toByteArray();
    }

    // --- 协议工具 ---

    private void writePacket(OutputStream out, byte[] data) throws IOException {
        writeVarInt(out, data.length);
        out.write(data);
        out.flush(); // 必须强制 Flush，确保服务器立刻收到 Acknowledge
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
