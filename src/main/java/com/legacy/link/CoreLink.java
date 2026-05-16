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
    public void onEnable() {
        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        File file = new File(getDataFolder(), "acc.json");
        if (!file.exists()) saveResource("acc.json", false);

        getLogger().info("=== CoreLink: 1.21.11 原生 TCP 协议模式已启动 ===");

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

            // 1. 发送握手包 (Handshake Packet) - 1.21.11 对应的协议版本号是 768
            byte[] handshakeData = buildHandshake(host, port, 768, 2);
            sendPacket(out, 0x00, handshakeData);

            // 2. 发送登录开始包 (Login Start)
            byte[] loginStartData = buildLoginStart(username);
            sendPacket(out, 0x00, loginStartData);

            getLogger().info("机器人 [" + username + "] 登录数据包发送完毕，已进入挂机状态。");

            // 3. 开启心跳与随机移动任务 (每10秒发送一次位置包，防止掉线)
            scheduler.scheduleWithFixedDelay(() -> {
                try {
                    if (socket.isConnected() && !socket.isClosed()) {
                        // 发送微小的位置移动包 (ServerboundMovePlayerPosPacket)
                        // 1.21.x 状态下的 0x1A 或 0x1B 位置包
                        byte[] moveData = new byte[26]; // 3个double(24字节) + 2个boolean(2字节)
                        // 这里填充纯 0 或者是微小浮点数数据
                        sendPacket(out, 0x1A, moveData); 
                    }
                } catch (Exception e) {
                    getLogger().warning("机器人 [" + username + "] 心跳维持失败，准备重连...");
                }
            }, 10, 10, TimeUnit.SECONDS);

            // 4. 监听服务器返回，保持连接不被挂起
            byte[] buffer = new byte[1024];
            while (socket.isConnected() && !socket.isClosed()) {
                int read = in.read(buffer);
                if (read == -1) break; // 断开连接
            }

        } catch (Exception e) {
            getLogger().warning("机器人 [" + username + "] 连接中断: " + e.getMessage() + "，15秒后自动重连...");
            // 掉线重连逻辑
            scheduler.schedule(() -> startTcpBot(username, host, port), 15, TimeUnit.SECONDS);
        }
    }

    // 构建 Minecraft 握手数据
    private byte[] buildHandshake(String host, int port, int protocolVersion, int nextState) throws Exception {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        DataOutputStream buf = new DataOutputStream(bytes);
        writeVarInt(buf, protocolVersion);
        writeString(buf, host);
        buf.writeShort(port);
        writeVarInt(buf, nextState);
        return bytes.toByteArray();
    }

    // 构建登录数据
    private byte[] buildLoginStart(String username) throws Exception {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        DataOutputStream buf = new DataOutputStream(bytes);
        writeString(buf, username);
        // 1.21.11 包含 UUID 存在标志 (false 表示不带 UUID 传参，由服务器生成)
        buf.writeBoolean(false); 
        return bytes.toByteArray();
    }

    // 发送标准 Minecraft 封包
    private void sendPacket(DataOutputStream out, int packetId, byte[] data) throws Exception {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        DataOutputStream buf = new DataOutputStream(bytes);
        writeVarInt(buf, packetId);
        buf.write(data);

        byte[] packetBytes = bytes.toByteArray();
        writeVarInt(out, packetBytes.length); // 写入整个包的长度 (VarInt)
        out.write(packetBytes);
        out.flush();
    }

    // Helper: 写入 VarInt 变长整型
    private void writeVarInt(DataOutputStream out, int value) throws Exception {
        while ((value & 0xFFFFFF80) != 0L) {
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.writeByte(value & 0x7F);
    }

    // Helper: 写入 Minecraft 编码字符串
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
