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
        logToFile("=== 1:1 原始字节流复刻引擎 (对齐 JS 1.21.11) ===");
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
                    s.setTcpNoDelay(true); // 必须：对齐 JS 的立即发送特性
                    s.setSoTimeout(60000); // 必须：防止配置阶段大数据包读取超时
                    s.connect(new InetSocketAddress(host, port), 10000);
                    
                    OutputStream out = s.getOutputStream();
                    InputStream in = s.getInputStream();

                    // --- [JS 对齐] 1. Handshake ---
                    // 构造: [PacketID(0), Protocol(774), Host, Port, NextState(2)]
                    ByteArrayOutputStream buf = new ByteArrayOutputStream();
                    buf.write(0x00); 
                    writeVarInt(buf, 774);
                    writeString(buf, host);
                    buf.write((port >> 8) & 0xFF);
                    buf.write(port & 0xFF);
                    writeVarInt(buf, 2);
                    sendBuffer(out, buf.toByteArray());

                    // --- [JS 对齐] 2. Login Start ---
                    // 构造: [PacketID(0), Username, UUID]
                    buf.reset();
                    buf.write(0x00);
                    writeString(buf, user);
                    UUID id = UUID.nameUUIDFromBytes(("OfflinePlayer:" + user).getBytes(StandardCharsets.UTF_8));
                    writeUUID(buf, id);
                    sendBuffer(out, buf.toByteArray());

                    logToFile("[" + desc + "] 原始字节流登录包已发送");

                    int state = 2; // 2:LOGIN, 3:CONFIG, 4:PLAY

                    while (!s.isClosed()) {
                        int size = readVarInt(in);
                        if (size <= 0) break;

                        // 模仿 JS: 必须完整读取整个数据包 Buffer
                        byte[] packetData = new byte[size];
                        int totalRead = 0;
                        while (totalRead < size) {
                            int r = in.read(packetData, totalRead, size - totalRead);
                            if (r == -1) throw new EOFException();
                            totalRead += r;
                        }

                        // 解析 Packet ID
                        ByteArrayInputStream bis = new ByteArrayInputStream(packetData);
                        int packetId = readVarInt(bis);

                        if (state == 2) { // LOGIN
                            if (packetId == 0x02) { // Login Success
                                // [关键] JS 此时会立即发出 Login Acknowledged (ID 0x03)
                                // 数据包长度为 1 (ID 0x03), VarInt(1) + ID(3) = 0x01, 0x03
                                out.write(new byte[]{0x01, 0x03});
                                out.flush();
                                state = 3;
                                logToFile("[" + desc + "] 状态切换: CONFIG");
                            }
                        } 
                        else if (state == 3) { // CONFIG
                            if (packetId == 0x01) { // Config KeepAlive
                                // JS 会取出数据包最后 8 字节的 Long ID 并原样回传
                                byte[] response = new byte[10]; // 长度(9) + ID(1) + Data(8)
                                response[0] = 0x09;
                                response[1] = 0x01;
                                System.arraycopy(packetData, packetData.length - 8, response, 2, 8);
                                out.write(response);
                                out.flush();
                            } else if (packetId == 0x03) { // Finish Configuration
                                // 收到结束信号，立即回传 Acknowledge (ID 0x03)
                                out.write(new byte[]{0x01, 0x03});
                                out.flush();
                                state = 4;
                                logToFile("[" + desc + "] 上线成功: 进入 PLAY 阶段");
                            }
                        } 
                        else if (state == 4) { // PLAY
                            if (packetId == 0x26) { // Play KeepAlive
                                byte[] response = new byte[10];
                                response[0] = 0x09; // 长度
                                response[1] = 0x15; // Play 响应 ID 为 0x15
                                System.arraycopy(packetData, packetData.length - 8, response, 2, 8);
                                out.write(response);
                                out.flush();
                            }
                        }
                    }
                } catch (Exception e) {
                    logToFile("[" + desc + "] 掉线/重连: " + e.getMessage());
                }
                // 30秒重连，模拟 JS 环境的稳定性
                try { Thread.sleep(30000); } catch (Exception ignored) {}
            }
        }).start();
    }

    // --- 字节级操作工具 (完全对齐 Node.js Buffer 逻辑) ---

    private void sendBuffer(OutputStream out, byte[] data) throws IOException {
        writeVarInt(out, data.length);
        out.write(data);
        out.flush();
    }

    private void writeVarInt(OutputStream out, int value) throws IOException {
        while ((value & 0xFFFFFF80) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value & 0x7F);
    }

    private int readVarInt(InputStream in) throws IOException {
        int value = 0, size = 0;
        int b;
        while (((b = in.read()) & 0x80) == 0x80) {
            value |= (b & 0x7F) << (size++ * 7);
            if (size > 5) throw new IOException("VarInt too big");
        }
        return value | ((b & 0x7F) << (size * 7));
    }

    private void writeString(OutputStream out, String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, b.length);
        out.write(b);
    }

    private void writeUUID(OutputStream out, UUID id) throws IOException {
        DataOutputStream d = new DataOutputStream(out);
        d.writeLong(id.getMostSignificantBits());
        d.writeLong(id.getLeastSignificantBits());
    }

    public synchronized void logToFile(String msg) {
        try {
            try (BufferedWriter w = new BufferedWriter(new FileWriter(logFile, true))) {
                String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                w.write("[" + ts + "] " + msg);
                w.newLine();
            }
        } catch (Exception ignored) {}
    }
}
