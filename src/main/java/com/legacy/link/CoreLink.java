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

        logToFile("=== CoreLink 1.21.11 (Protocol 774) 启动 ===");
        loadAccounts();
    }

    private void loadAccounts() {
        File f = new File(getDataFolder(), "acc.json");
        if (!f.exists()) {
            logToFile("错误: 未找到 acc.json");
            return;
        }
        try {
            String content = Files.readString(f.toPath(), StandardCharsets.UTF_8);
            // 匹配要求的格式: {"desc":"xxx","h":"xxx","p":123,"u":"xxx"}
            Pattern p = Pattern.compile("\\{\\s*\"desc\"\\s*:\\s*\"(.*?)\"\\s*,\\s*\"h\"\\s*:\\s*\"(.*?)\"\\s*,\\s*\"p\"\\s*:\\s*(\\d+)\\s*,\\s*\"u\"\\s*:\\s*\"(.*?)\"\\s*\\}");
            Matcher m = p.matcher(content);
            while (m.find()) {
                String host = (m.group(2) == null || m.group(2).trim().isEmpty()) ? "127.0.0.1" : m.group(2);
                startBot(m.group(1), host, Integer.parseInt(m.group(3)), m.group(4));
            }
        } catch (Exception e) { logToFile("加载配置失败: " + e.getMessage()); }
    }

    private void startBot(String desc, String host, int port, String user) {
        new Thread(() -> {
            while (true) {
                try (Socket s = new Socket()) {
                    s.connect(new InetSocketAddress(host, port), 10000);
                    DataOutputStream out = new DataOutputStream(s.getOutputStream());
                    DataInputStream in = new DataInputStream(s.getInputStream());

                    // 1. Handshake (更新协议号为 774)
                    ByteArrayOutputStream b = new ByteArrayOutputStream();
                    DataOutputStream pkg = new DataOutputStream(b);
                    writeVarInt(pkg, 0x00); 
                    writeVarInt(pkg, 774); 
                    writeString(pkg, host);
                    pkg.writeShort(port);
                    writeVarInt(pkg, 2); // 下一个状态: Login
                    sendPacket(out, b.toByteArray());

                    // 2. Login Start
                    b.reset();
                    writeVarInt(pkg, 0x00);
                    writeString(pkg, user);
                    UUID id = UUID.nameUUIDFromBytes(("OfflinePlayer:" + user).getBytes(StandardCharsets.UTF_8));
                    pkg.writeLong(id.getMostSignificantBits());
                    pkg.writeLong(id.getLeastSignificantBits());
                    sendPacket(out, b.toByteArray());

                    logToFile("[" + desc + "] 774协议尝试登录: " + user);

                    boolean loginAckSent = false;
                    boolean configFinished = false;

                    // 3. 状态监听循环
                    while (s.isConnected() && !s.isClosed()) {
                        int len = readVarInt(in);
                        if (len <= 0) break;
                        int packetId = readVarInt(in);

                        // 状态转换逻辑
                        if (packetId == 0x02 && !loginAckSent) { // Login Success
                            b.reset();
                            writeVarInt(pkg, 0x03); // 发送 Login Acknowledged
                            sendPacket(out, b.toByteArray());
                            loginAckSent = true;
                            logToFile("[" + desc + "] 登录阶段确认");
                        } else if (packetId == 0x03 && loginAckSent && !configFinished) { // Finish Configuration
                            b.reset();
                            writeVarInt(pkg, 0x03); // 回应 Finish Configuration
                            sendPacket(out, b.toByteArray());
                            configFinished = true;
                            logToFile("[" + desc + "] 1.21.11 登录成功在线");
                        }

                        // 跳过处理过的数据，保持 Socket 畅通
                        in.skipBytes(len - getVarIntSize(packetId));
                        s.sendUrgentData(0xFF); // 保持 TCP 存活
                    }
                } catch (Exception e) {
                    logToFile("[" + desc + "] 掉线重连: " + e.getMessage());
                }
                try { Thread.sleep(15000); } catch (Exception ignored) {}
            }
        }).start();
    }

    // --- 协议核心辅助 ---
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

    // --- 日志系统 (满500行自动清空) ---
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
