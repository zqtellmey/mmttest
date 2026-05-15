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
        logToFile("=== 1:1 模拟 JS 协议状态机 (774) ===");
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
                String host = (m.group(2).isEmpty()) ? "127.0.0.1" : m.group(2); // 手动输入 IP 或自动获取
                startBot(m.group(1), host, Integer.parseInt(m.group(3)), m.group(4));
            }
        } catch (Exception ignored) {}
    }

    private void startBot(String desc, String host, int port, String user) {
        new Thread(() -> {
            while (true) {
                try (Socket s = new Socket()) {
                    s.setTcpNoDelay(true);
                    s.setSoTimeout(30000); 
                    s.connect(new InetSocketAddress(host, port), 10000);
                    
                    DataOutputStream out = new DataOutputStream(s.getOutputStream());
                    DataInputStream in = new DataInputStream(s.getInputStream());

                    // --- STEP 1: Handshake (JS: client.write('handshake', ...)) ---
                    ByteArrayOutputStream b = new ByteArrayOutputStream();
                    DataOutputStream pkg = new DataOutputStream(b);
                    writeVarInt(pkg, 0x00);
                    writeVarInt(pkg, 774); 
                    writeString(pkg, host);
                    pkg.writeShort(port);
                    writeVarInt(pkg, 2); 
                    sendPacket(out, b.toByteArray());

                    // --- STEP 2: Login Start (JS: client.write('login_start', ...)) ---
                    b.reset();
                    writeVarInt(pkg, 0x00);
                    writeString(pkg, user);
                    UUID id = UUID.nameUUIDFromBytes(("OfflinePlayer:" + user).getBytes(StandardCharsets.UTF_8));
                    pkg.writeLong(id.getMostSignificantBits());
                    pkg.writeLong(id.getLeastSignificantBits());
                    sendPacket(out, b.toByteArray());

                    logToFile("[" + desc + "] 已执行 JS 登录步骤 1 & 2");

                    int protocolState = 2; // 2:LOGIN, 3:CONFIG, 4:PLAY

                    while (!s.isClosed()) {
                        int packetLen = readVarInt(in);
                        if (packetLen <= 0) break;

                        // JS 模式：立即读入完整 Buffer 防止流阻塞
                        byte[] buffer = new byte[packetLen];
                        in.readFully(buffer);
                        DataInputStream pIn = new DataInputStream(new ByteArrayInputStream(buffer));
                        int packetId = readVarInt(pIn);

                        // --- STEP 3: 状态分发逻辑 ---
                        if (protocolState == 2) {
                            if (packetId == 0x02) { // Login Success
                                b.reset();
                                writeVarInt(pkg, 0x03); // JS: login_acknowledged
                                sendPacket(out, b.toByteArray());
                                protocolState = 3;
                                logToFile("[" + desc + "] 步骤 3 完成: 切换至 CONFIG");
                            }
                        } 
                        else if (protocolState == 3) {
                            if (packetId == 0x01) { // JS: config_keep_alive
                                long keepId = pIn.readLong();
                                b.reset();
                                writeVarInt(pkg, 0x01); 
                                pkg.writeLong(keepId);
                                sendPacket(out, b.toByteArray());
                            } else if (packetId == 0x03) { // JS: config_finished
                                b.reset();
                                writeVarInt(pkg, 0x03); // JS: finish_configuration (acknowledge)
                                sendPacket(out, b.toByteArray());
                                protocolState = 4;
                                logToFile("[" + desc + "] 步骤 4 完成: 进入 PLAY 保持在线");
                            }
                        } 
                        else if (protocolState == 4) {
                            if (packetId == 0x26) { // JS: keep_alive (Play State)
                                long keepId = pIn.readLong();
                                b.reset();
                                writeVarInt(pkg, 0x15); // JS: keep_alive response ID
                                pkg.writeLong(keepId);
                                sendPacket(out, b.toByteArray());
                            }
                        }
                    }
                } catch (Exception e) {
                    logToFile("[" + desc + "] 连接中断: " + e.getMessage() + " (15秒后重连)");
                }
                try { Thread.sleep(15000); } catch (Exception ignored) {}
            }
        }).start();
    }

    // --- 协议工具 ---
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
            }
        } catch (Exception ignored) {}
    }
}
