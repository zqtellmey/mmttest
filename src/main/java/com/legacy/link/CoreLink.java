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
        logToFile("=== 严格复刻 JS 版协议引擎 (1.21.11) ===");
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
                    s.setTcpNoDelay(true); // 1:1 复刻 JS 的低延迟特性
                    s.setSoTimeout(30000); // 避免死链接
                    s.connect(new InetSocketAddress(host, port), 10000);
                    DataOutputStream out = new DataOutputStream(s.getOutputStream());
                    DataInputStream in = new DataInputStream(s.getInputStream());

                    // --- STEP 1: Handshake (状态设为 2: LOGIN) ---
                    ByteArrayOutputStream b = new ByteArrayOutputStream();
                    DataOutputStream pkg = new DataOutputStream(b);
                    writeVarInt(pkg, 0x00);
                    writeVarInt(pkg, 774); // 协议版本 1.21.11
                    writeString(pkg, host);
                    pkg.writeShort(port);
                    writeVarInt(pkg, 2); 
                    sendPacket(out, b.toByteArray());

                    // --- STEP 2: Login Start ---
                    b.reset();
                    writeVarInt(pkg, 0x00);
                    writeString(pkg, user);
                    // 使用固定值生成 UUID，不随机化
                    UUID id = UUID.nameUUIDFromBytes(("OfflinePlayer:" + user).getBytes(StandardCharsets.UTF_8));
                    pkg.writeLong(id.getMostSignificantBits());
                    pkg.writeLong(id.getLeastSignificantBits());
                    sendPacket(out, b.toByteArray());

                    logToFile("[" + desc + "] 已发送登录请求: " + user);

                    int protocolState = 2; // 2: Login, 3: Config, 4: Play

                    while (s.isConnected() && !s.isClosed()) {
                        int len = readVarInt(in);
                        if (len <= 0) break;

                        // 核心：像 JS 的 Buffer 一样一次性读完，防止阻塞 Netty 管道
                        byte[] buffer = new byte[len];
                        in.readFully(buffer);
                        DataInputStream packetIn = new DataInputStream(new ByteArrayInputStream(buffer));
                        int packetId = readVarInt(packetIn);

                        if (protocolState == 2) { // LOGIN 阶段
                            if (packetId == 0x02) { // Login Success
                                b.reset();
                                writeVarInt(pkg, 0x03); // 发送 Login Acknowledged (必须立即发送)
                                sendPacket(out, b.toByteArray());
                                protocolState = 3;
                                logToFile("[" + desc + "] 登录确认，转换至配置状态");
                            } else if (packetId == 0x03) { // Set Compression
                                // JS 版会自动处理压缩，Java 版在此简单跳过
                            }
                        } 
                        else if (protocolState == 3) { // CONFIGURATION 阶段
                            if (packetId == 0x01) { // Config KeepAlive (必须回复)
                                long keepId = packetIn.readLong();
                                b.reset();
                                writeVarInt(pkg, 0x01); 
                                pkg.writeLong(keepId);
                                sendPacket(out, b.toByteArray());
                            } else if (packetId == 0x03) { // Finish Configuration
                                b.reset();
                                writeVarInt(pkg, 0x03); // 回复服务器：配置已完成
                                sendPacket(out, b.toByteArray());
                                protocolState = 4;
                                logToFile("[" + desc + "] 进入游戏状态");
                            }
                            // 0x07(RegistryData) 等由 buffer 自动消费，不干扰流同步
                        } 
                        else if (protocolState == 4) { // PLAY 阶段
                            if (packetId == 0x26) { // 1.21.x Play KeepAlive
                                long keepId = packetIn.readLong();
                                b.reset();
                                writeVarInt(pkg, 0x15); // 回复响应
                                pkg.writeLong(keepId);
                                sendPacket(out, b.toByteArray());
                            }
                        }
                    }
                } catch (Exception e) {
                    logToFile("[" + desc + "] 状态异常: " + e.getMessage());
                }
                // 15秒重连逻辑
                try { Thread.sleep(15000); } catch (Exception ignored) {}
            }
        }).start();
    }

    // --- 协议基础函数 ---
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

    // --- 日志系统 (500行清理) ---
    public synchronized void logToFile(String msg) {
        try {
            // 自动检查日志长度并清理
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
