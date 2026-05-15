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
        logToFile("=== 1:1 深度复刻 JS 协议引擎 (1.21.11) ===");
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
                    s.setTcpNoDelay(true);
                    s.setSoTimeout(30000);
                    // 增加缓冲区大小，防止大数据包导致阻塞
                    s.setReceiveBufferSize(1024 * 512); 
                    
                    s.connect(new InetSocketAddress(host, port), 10000);
                    DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
                    DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));

                    // --- STEP 1: Handshake ---
                    ByteArrayOutputStream b = new ByteArrayOutputStream();
                    DataOutputStream pkg = new DataOutputStream(b);
                    writeVarInt(pkg, 0x00);
                    writeVarInt(pkg, 774); // 1.21.11 协议号
                    writeString(pkg, host);
                    pkg.writeShort(port);
                    writeVarInt(pkg, 2); // 下一个状态: Login
                    sendPacket(out, b.toByteArray());

                    // --- STEP 2: Login Start ---
                    b.reset();
                    writeVarInt(pkg, 0x00);
                    writeString(pkg, user);
                    // 严格按照 JS 的 Offline UUID 生成方式
                    UUID id = UUID.nameUUIDFromBytes(("OfflinePlayer:" + user).getBytes(StandardCharsets.UTF_8));
                    pkg.writeLong(id.getMostSignificantBits());
                    pkg.writeLong(id.getLeastSignificantBits());
                    sendPacket(out, b.toByteArray());

                    logToFile("[" + desc + "] 已执行 JS 登录步骤 1 & 2: " + user);

                    int state = 2; // 2: LOGIN, 3: CONFIG, 4: PLAY

                    while (!s.isClosed()) {
                        int len = readVarInt(in);
                        if (len <= 0) break;

                        // 模仿 JS 的解包过程：必须读完整个包
                        byte[] buffer = new byte[len];
                        in.readFully(buffer);
                        DataInputStream pIn = new DataInputStream(new ByteArrayInputStream(buffer));
                        int packetId = readVarInt(pIn);

                        if (state == 2) { // LOGIN 阶段
                            if (packetId == 0x02) { // Login Success
                                // 必须立即发送 Login Acknowledged
                                b.reset();
                                writeVarInt(pkg, 0x03); 
                                sendPacket(out, b.toByteArray());
                                state = 3;
                                logToFile("[" + desc + "] 状态切换: CONFIGURATION");
                            } else if (packetId == 0x03) {
                                // 兼容服务器发送的 Set Compression 包，此处虽然不启用解压，但必须读过它
                                logToFile("[" + desc + "] 收到压缩包请求 (已跳过)");
                            }
                        } 
                        else if (state == 3) { // CONFIGURATION 阶段
                            if (packetId == 0x01) { // Config KeepAlive
                                long keepId = pIn.readLong();
                                b.reset();
                                writeVarInt(pkg, 0x01); 
                                pkg.writeLong(keepId);
                                sendPacket(out, b.toByteArray());
                            } else if (packetId == 0x03) { // Finish Configuration
                                b.reset();
                                writeVarInt(pkg, 0x03); 
                                sendPacket(out, b.toByteArray());
                                state = 4;
                                logToFile("[" + desc + "] 状态切换: PLAY (上线成功)");
                            }
                        } 
                        else if (state == 4) { // PLAY 阶段
                            if (packetId == 0x26) { // 1.21.x Play KeepAlive
                                long keepId = pIn.readLong();
                                b.reset();
                                writeVarInt(pkg, 0x15); 
                                pkg.writeLong(keepId);
                                sendPacket(out, b.toByteArray());
                            }
                        }
                    }
                } catch (Exception e) {
                    logToFile("[" + desc + "] 连接中断: " + (e.getMessage() == null ? "服务器断开" : e.getMessage()) + " (15秒后重连)");
                }
                // 延长重连间隔，防止被服务器屏蔽
                try { Thread.sleep(15000); } catch (Exception ignored) {}
            }
        }).start();
    }

    private void sendPacket(DataOutputStream out, byte[] data) throws IOException {
        writeVarInt(out, data.length);
        out.write(data);
        out.flush(); // JS 版中 write 是带 flush 逻辑的
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
