package com.legacy.link;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileReader;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class CoreLink extends JavaPlugin {

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);
    private final Map<String, Socket> activeSockets = new HashMap<>();
    private List<Map<String, String>> accounts = new ArrayList<>();

    @Override
    public void onLoad() {
        // 在插件最顶层加载时，强制创建文件夹，确保绝对能生成
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }
    }

    @Override
    public void onEnable() {
        getLogger().info("=== CoreLink: 1.21.11 原生 TCP 协议模式已启动 ===");

        // 强行释放默认配置文件模版
        File file = new File(getDataFolder(), "acc.json");
        if (!file.exists()) {
            saveResource("acc.json", false);
        }

        try (FileReader reader = new FileReader(file, StandardCharsets.UTF_8)) {
            accounts = new Gson().fromJson(reader, new TypeToken<List<Map<String, String>>>(){}.getType());
            if (accounts != null) {
                for (Map<String, String> acc : accounts) {
                    String username = acc.get("u");
                    String host = acc.get("h");
                    int port = Integer.parseInt(acc.get("p"));
                    scheduler.execute(() -> startTcpBot(username, host, port));
                }
            }
        } catch (Exception e) {
            getLogger().severe("加载 acc.json 失败: " + e.getMessage());
        }
    }

    private void startTcpBot(String username, String host, int port) {
        try {
            getLogger().info("正在通过原生 TCP 连接服务器: " + host + ":" + port + " (用户: " + username + ")");
            Socket socket = new Socket(host, port);
            activeSockets.put(username, socket);

            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());

            // 1. 发送握手包 (Handshake Packet) - 协议版本号 768 (1.21.11)
            byte[] handshakeData = buildHandshake(host, port, 768, 2);
            sendPacket(out, 0x00, handshakeData);

            // 2. 发送登录开始包 (Login Start)
            byte[] loginStartData = buildLoginStart(username);
            sendPacket(out, 0x00, loginStartData);

            getLogger().info("机器人 [" + username + "] 登录数据包发送完毕，已进入挂机状态。");

            // 3. 心跳维持任务
            scheduler.scheduleWithFixedDelay(() -> {
                try {
                    if (socket.isConnected() && !socket.isClosed()) {
                        byte[] moveData = new byte[26];
                        sendPacket(out, 0x1A, moveData); 
                    }
                } catch (Exception e) {
                    getLogger().warning("机器人 [" + username + "] 心跳维持失败。");
                }
            }, 10, 10, TimeUnit.SECONDS);

            byte[] buffer = new byte[1024];
            while (socket.isConnected() && !socket.isClosed()) {
                int read = in.read(buffer);
                if (read == -1) break;
            }

        } catch (Exception e) {
            getLogger().warning("机器人 [" + username + "] 连接中断，15秒后自动重连...");
            scheduler.schedule(() -> startTcpBot(username, host, port), 15, TimeUnit.SECONDS);
        }
    }

    private byte[] buildHandshake(String host, int port, int protocolVersion, int nextState) throws Exception {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        DataOutputStream buf = new DataOutputStream(bytes);
        writeVarInt(buf, protocolVersion);
        writeString(buf, host);
        buf.writeShort(port);
        writeVarInt(buf, nextState);
        return bytes.toByteArray();
    }

    private byte[] buildLoginStart(String username) throws Exception {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        DataOutputStream buf = new DataOutputStream(bytes);
        writeString(buf, username);
        buf.writeBoolean(false); 
        return bytes.toByteArray();
    }

    private void sendPacket(DataOutputStream out, int packetId, byte[] data) throws Exception {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        DataOutputStream buf = new DataOutputStream(bytes);
        writeVarInt(buf, packetId);
        buf.write(data);

        byte[] packetBytes = bytes.toByteArray();
        writeVarInt(out, packetBytes.length);
        out.write(packetBytes);
        out.flush();
    }

    private void writeVarInt(DataOutputStream out, int value) throws Exception {
        while ((value & 0xFFFFFF80) != 0L) {
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.writeByte(value & 0x7F);
    }

    private void writeString(DataOutputStream out, String str) throws Exception {
        byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.write(bytes);
    }

    @Override
    public void onDisable() {
        for (Socket socket : activeSockets.values()) {
            try { socket.close(); } catch (Exception ignored) {}
        }
        scheduler.shutdownNow();
    }
}
