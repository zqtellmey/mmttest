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
import java.util.zip.Inflater;

public class CoreLink extends JavaPlugin {

    private File logFile;

    @Override
    public void onEnable() {
        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        logFile = new File(getDataFolder(), "run.log");
        try { if (!logFile.exists()) logFile.createNewFile(); } catch (IOException ignored) {}

        logToFile("=== 启动协议转换引擎 (1.21.11 / 774) ===");
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
                    DataOutputStream out = new DataOutputStream(s.getOutputStream());
                    DataInputStream in = new DataInputStream(s.getInputStream());

                    // 1. Handshake (774)
                    ByteArrayOutputStream b = new ByteArrayOutputStream();
                    DataOutputStream pkg = new DataOutputStream(b);
                    writeVarInt(pkg, 0x00); 
                    writeVarInt(pkg, 774); 
                    writeString(pkg, host);
                    pkg.writeShort(port);
                    writeVarInt(pkg, 2); 
                    sendPacket(out, b.toByteArray());

                    // 2. Login Start (UUID + Username)
                    b.reset();
                    writeVarInt(pkg, 0x00);
                    writeString(pkg, user);
                    UUID id = UUID.nameUUIDFromBytes(("OfflinePlayer:" + user).getBytes(StandardCharsets.UTF_8));
                    pkg.writeLong(id.getMostSignificantBits());
                    pkg.writeLong(id.getLeastSignificantBits());
                    sendPacket(out, b.toByteArray());

                    logToFile("[" + desc + "] 正在模拟 Mineflayer 登录流程: " + user);

                    // 3. 仿 Mineflayer 协议监听器
                    int compressionThreshold = -1;
                    while (s.isConnected() && !s.isClosed()) {
                        int packetLength = readVarInt(in);
                        if (packetLength <= 0) break;

                        // 处理压缩包逻辑 (Mineflayer 的核心之一)
                        InputStream packetStream = in;
                        if (compressionThreshold != -1) {
                            int uncompressedSize = readVarInt(in);
                            if (uncompressedSize != 0) {
                                byte[] compressed = new byte[packetLength - getVarIntSize(uncompressedSize)];
                                in.readFully(compressed);
                                byte[] uncompressed = new byte[uncompressedSize];
                                Inflater inflater = new Inflater();
                                inflater.setInput(compressed);
                                inflater.inflate(uncompressed);
                                inflater.end();
                                packetStream = new DataInputStream(new ByteArrayInputStream(uncompressed));
                            }
                        }

                        DataInputStream packetIn = new DataInputStream(packetStream);
                        int packetId = readVarInt(packetIn);

                        // --- 关键步骤处理 ---
                        if (packetId == 0x03) { // Set Compression
                            compressionThreshold = readVarInt(packetIn);
                        } else if (packetId == 0x02) { // Login Success
                            // 回应 Login Acknowledged (0x03)
                            b.reset();
                            writeVarInt(pkg, 0x03);
                            sendPacket(out, b.toByteArray());
                            logToFile("[" + desc + "] 协议成功跳转至 Configuration");
                        } else if (packetId == 0x03 && compressionThreshold != -1) { // Finish Configuration (在 Config 状态 ID 可能重叠)
                            b.reset();
                            writeVarInt(pkg, 0x03); // Finish Configuration Response
                            sendPacket(out, b.toByteArray());
                            logToFile("[" + desc + "] 登录成功并保持在线");
                        }

                        // 定期发送 TCP KeepAlive
                        s.sendUrgentData(0xFF);
                    }
                } catch (Exception e) {
                    logToFile("[" + desc + "] 连接中断: " + e.getMessage() + " (15秒后重连)");
                }
                try { Thread.sleep(15000); } catch (Exception ignored) {}
            }
        }).start();
    }

    // --- 仿 Mineflayer 协议工具集 ---
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
                String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                w.write("[" + time + "] " + msg);
                w.newLine();
                w.flush();
            }
        } catch (Exception ignored) {}
    }
}
