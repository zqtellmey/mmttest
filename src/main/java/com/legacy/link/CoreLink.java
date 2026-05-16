package com.legacy.link;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
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
    private final Random random = new Random();

    @Override
    public void onLoad() {
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }
    }

    @Override
    public void onEnable() {
        getLogger().info("=== CoreLink: 1.21.11 原生 TCP 协议模式已启动 ===");

        File file = new File(getDataFolder(), "acc.json");
        
        if (!file.exists()) {
            try (FileWriter writer = new FileWriter(file, StandardCharsets.UTF_8)) {
                String defaultJson = "[\n  {\n    \"u\": \"Bot_Worker1\",\n    \"h\": \"127.0.0.1\",\n    \"p\": \"25565\"\n  }\n]";
                writer.write(defaultJson);
                writer.flush();
                getLogger().info("未检测到配置文件，已成功创建默认的 acc.json 模板。");
            } catch (Exception e) {
                getLogger().severe("初始化默认 acc.json 失败: " + e.getMessage());
            }
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

            // 3. 心跳维持任务 (每10秒发送一次精心构造的1.21.11位置与姿态包)
            scheduler.scheduleWithFixedDelay(() -> {
                try {
                    if (socket.isConnected() && !socket.isClosed()) {
                        // 1.21.11 的 ServerboundMovePlayerPosRotPacket (移动+旋转包) 通常最稳定
                        // 包 ID 在最新版游戏状态下切换为 0x1C 或者是特定偏移
                        // 构造真实数据：X, Y, Z, Yaw, Pitch, Flags (或 OnGround, HorizontalCollision)
                        byte[] moveData = buildMoveData();
                        sendPacket(out, 0x1C, moveData); 
                    } else {
                        throw new Exception("Socket 已断开");
                    }
                } catch (Exception e) {
                    getLogger().warning("机器人 [" + username + "] 心跳维持失败，触发自动重连机制。");
                    try { socket.close(); } catch (Exception ignored) {}
                    activeSockets.remove(username);
                    // 延迟15秒重连
                    scheduler.schedule(() -> startTcpBot(username, host, port), 15, TimeUnit.SECONDS);
                    throw new RuntimeException("Stop Task"); // 结束当前心跳调度，防止多重并发
                }
            }, 10, 10, TimeUnit.SECONDS);

            // 4. 持续维持流读取，拦截并清空服务端返回的 TCP 缓冲区（防止缓冲区满被踢）
            byte[] buffer = new byte[2048];
            while (socket.isConnected() && !socket.isClosed()) {
                int read = in.read(buffer);
                if (read == -1) break;
            }

        } catch (Exception e) {
            getLogger().warning("机器人 [" + username + "] 连接异常: " + e.getMessage() + "，15秒后自动重连...");
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
        buf.writeBoolean(false); // 不携带特定的UUID，让目标服务器在线/离线模式自行分配
        return bytes.toByteArray();
    }

    // 1.21.11 专用的复合运动与姿态字节数据构建器
    private byte[] buildMoveData() throws Exception {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        DataOutputStream buf = new DataOutputStream(bytes);
        
        // 构造微小的随机位移，防止服务端反作弊去检测完全静止的机器人
        double x = (random.nextDouble() - 0.5) * 0.05;
        double z = (random.nextDouble() - 0.5) * 0.05;

        buf.writeDouble(x);      // X 轴
        buf.writeDouble(64.0);   // Y 轴（默认常规高度，防止掉落虚空）
        buf.writeDouble(z);      // Z 轴
        buf.writeFloat(0.0f);    // Yaw (视角)
        buf.writeFloat(0.0f);    // Pitch (视角)
        buf.writeBoolean(true);  // OnGround (在地面上)
        buf.writeBoolean(false); // HorizontalCollision (无水平碰撞)
        
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
