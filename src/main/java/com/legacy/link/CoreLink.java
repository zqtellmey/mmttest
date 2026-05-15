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
        logToFile("=== 1:1 字节流复刻 (修正 VarInt) ===");
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
                String host = (m.group(2).isEmpty()) ? "127.0.0.1" : m.group(2); // 手动输入 IP，为空则自动
                startBot(m.group(1), host, Integer.parseInt(m.group(3)), m.group(4));
            }
        } catch (Exception ignored) {}
    }

    private void startBot(String desc, String host, int port, String user) {
        new Thread(() -> {
            while (true) {
                try (Socket s = new Socket()) {
                    s.setTcpNoDelay(true); // 对齐 JS 立即发送
                    s.setSoTimeout(60000); 
                    s.connect(new InetSocketAddress(host, port), 10000);
                    
                    OutputStream out = s.getOutputStream();
                    InputStream in = s.getInputStream();

                    // --- STEP 1: Handshake (JS: handshake 状态 2) ---
                    ByteArrayOutputStream b = new ByteArrayOutputStream();
                    writeVarInt(b, 0x00);
                    writeVarInt(b, 774); // 1.21.11 协议号
                    writeString(b, host);
                    b.write((port >> 8) & 0xFF);
                    b.write(port & 0xFF);
                    writeVarInt(b, 2);
                    sendPacket(out, b.toByteArray());

                    // --- STEP 2: Login Start (JS: login_start) ---
                    b.reset();
                    writeVarInt(b, 0x00);
                    writeString(b, user);
                    // 固定密码/离线 UUID
                    UUID id = UUID.nameUUIDFromBytes(("OfflinePlayer:" + user).getBytes(StandardCharsets.UTF_8));
                    DataOutputStream d = new DataOutputStream(b);
                    d.writeLong(id.getMostSignificantBits());
                    d.writeLong(id.getLeastSignificantBits());
                    sendPacket(out, b.toByteArray());

                    logToFile("[" + desc + "] JS 握手字节流发送完毕");

                    int state = 2; // 2:LOGIN, 3:CONFIG, 4:PLAY

                    while (!s.isClosed()) {
                        // 模仿 JS 的解码过程
                        int packetLen = readVarInt(in);
                        if (packetLen <= 0) break;

                        // 必须精确读取 packetLen 长度，不多不少
                        byte[] data = new byte[packetLen];
                        int totalRead = 0;
                        while (totalRead < packetLen) {
                            int r = in.read(data, totalRead, packetLen - totalRead);
                            if (r == -1) throw new EOFException();
                            totalRead += r;
                        }

                        ByteArrayInputStream bis = new ByteArrayInputStream(data);
                        int packetId = readVarInt(bis);

                        if (state == 2) {
                            if (packetId == 0x02) { // Login Success
                                // JS 行为：立即回复 Acknowledge (ID 0x03)
                                out.write(new byte[]{0x01, 0x03}); 
                                out.flush();
                                state = 3;
                                logToFile("[" + desc + "] 状态 -> CONFIG");
                            }
                        } else if (state == 3) {
                            if (packetId == 0x01) { // Config KeepAlive
                                ByteArrayOutputStream rb = new ByteArrayOutputStream();
                                writeVarInt(rb, 0x01);
                                rb.write(data, data.length - 8, 8); // 镜像回传 ID
                                sendPacket(out, rb.toByteArray());
                            } else if (packetId == 0x03) { // Finish Config
                                out.write(new byte[]{0x01, 0x03});
                                out.flush();
                                state = 4;
                                logToFile("[" + desc + "] 状态 -> PLAY (上线完成)");
                            }
                        } else if (state == 4) {
                            if (packetId == 0x26) { // Play KeepAlive
                                ByteArrayOutputStream rb = new ByteArrayOutputStream();
                                writeVarInt(rb, 0x15); // 响应 ID 0x15
                                rb.write(data, data.length - 8, 8);
                                sendPacket(out, rb.toByteArray());
                            }
                        }
                    }
                } catch (Exception e) {
                    logToFile("[" + desc + "] 掉线/重连: " + e.getMessage());
                }
                try { Thread.sleep(20000); } catch (Exception ignored) {}
            }
        }).start();
    }

    // --- 严丝合缝的协议工具 ---

    private void sendPacket(OutputStream out, byte[] data) throws IOException {
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
        int value = 0;
        int bitOffset = 0;
        while (true) {
            int b = in.read();
            if (b == -1) throw new EOFException();
            value |= (b & 0x7F) << bitOffset;
            if ((b & 0x80) == 0) break;
            bitOffset += 7;
            if (bitOffset >= 35) throw new IOException("VarInt too big");
        }
        return value;
    }

    private void writeString(OutputStream out, String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, b.length);
        out.write(b);
    }

    public synchronized void logToFile(String msg) {
        try {
            try (BufferedWriter w = new BufferedWriter(new FileWriter(logFile, true))) {
                String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                w.write("[" + ts + "] " + msg + "\n");
            }
        } catch (Exception ignored) {}
    }
}
