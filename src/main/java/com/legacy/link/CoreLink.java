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
        getLogger().info("=== CoreLink: 1.21.11 纯原生 TCP 协议兼容版已启动 ===");

        File file = new File(getDataFolder(), "acc.json");
        
        if (!file.exists()) {
            try (FileWriter writer = new FileWriter(file, StandardCharsets.UTF_8)) {
                String defaultJson = "[\n  {\n    \"u\": \"Bot_Worker1\",\n    \"h\": \"127.0.0.1\",\n    \"p\": \"25565\"\n  }\n]";
                writer.write(defaultJson);
                writer.flush();
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
            getLogger().info("正在启动 TCP 连接 -> " + host + ":" + port + " (用户: " + username + ")");
            Socket socket = new Socket(host, port);
            activeSockets.put(username, socket);

            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());

            // 1. 发送标准握手 (Handshake Packet) - 协议号 768 (1.21.11), 状态设为 2 (Login)
            byte[] handshakeData = buildHandshake(host, port, 768, 2);
            sendPacket(out, 0x00, handshakeData);

            // 2. 发送登录开始 (Login Start Packet)
            byte[] loginStartData = buildLoginStart(username);
            sendPacket(out, 0x00, loginStartData);

            getLogger().info("机器人 [" + username + "] 握手已提交，正在监听原生 TCP 数据流...");

            // 3. 严格遵循原生项目的输入流轮询监听器 (防止发生死锁或粘包)
            scheduler.execute(() -> {
                try {
                    while (socket.isConnected() && !socket.isClosed()) {
                        int length = readVarInt(in);
                        if (length <= 0) break;
                        
                        int packetId = readVarInt(in);

                        // 捕获服务端的 Login Success 状态包
                        if (packetId == 0x02) {
                            getLogger().info("机器人 [" + username + "] 成功捕获 0x02 登录成功信号，正在转换协议阶段...");
                            
                            // 遵循项目的阶段转换应答：发送一个空的 Acknowledge 包响应服务器配置
                            sendPacket(out, 0x03, new byte[0]); 
                        }
                        
                        // 捕获服务端的 Keep Alive / Ping 请求
                        if (packetId == 0x03 || packetId == 0x15 || packetId == 0x26) {
                            // 原生项目逻辑：收到心跳立即原路返回空包或应答，确保连接不被踢
                            sendPacket(out, packetId, new byte[0]);
                        }

                        // 完全跳过包体剩余数据，防止干扰下一个循环的 VarInt 读取
                        long skipped = 0;
                        while (skipped < (length - 1)) {
                            long skipActual = in.skip((length - 1) - skipped);
                            if (skipActual <= 0) break;
                            skipped += skipActual;
                        }
                    }
                } catch (Exception e) {
                    // 流关闭或异常时转到外部的重连逻辑处理
                }
            });

            // 4. 定时发送位置状态心跳（项目维持在线的核心发包）
            scheduler.scheduleWithFixedDelay(() -> {
                try {
                    if (socket.isConnected() && !socket.isClosed()) {
                        // 构造 1.21.11 精密浮点位移数据包 (0x1C)，带随机偏置
                        byte[] moveData = buildMoveData();
                        sendPacket(out, 0x1C, moveData); 
                    } else {
                        throw new Exception("Socket Disconnected");
                    }
                } catch (Exception e) {
                    getLogger().warning("机器人 [" + username + "] 原生 TCP 连接异常中断，15秒后执行自动重连...");
                    try { socket.close(); } catch (Exception ignored) {}
                    activeSockets.remove(username);
                    // 触发安全重连
                    scheduler.schedule(() -> startTcpBot(username, host, port), 15, TimeUnit.SECONDS);
                    throw new RuntimeException("Terminate Task");
                }
            }, 10, 10, TimeUnit.SECONDS);

        } catch (Exception e) {
            getLogger().warning("机器人 [" + username + "] 建立 TCP 连接失败: " + e.getMessage() + "，15秒后重试...");
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

    private byte[] buildMoveData() throws Exception {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        DataOutputStream buf = new DataOutputStream(bytes);
        // 生成微小的非零位移，防止反作弊判定
        buf.writeDouble((random.nextDouble() - 0.5) * 0.03);
        buf.writeDouble(64.0);
        buf.writeDouble((random.nextDouble() - 0.5) * 0.03);
        buf.writeFloat(0.0f);
        buf.writeFloat(0.0f);
        buf.writeBoolean(true);
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

    private int readVarInt(DataInputStream in) throws Exception {
        int numRead = 0;
        int result = 0;
        byte read;
        do {
            read = in.readByte();
            int value = (read & 0x7F);
            result |= (value << (7 * numRead));
            numRead++;
            if (numRead > 5) {
                throw new RuntimeException("VarInt Too Long");
            }
        } while ((read & 0x80) != 0);
        return result;
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
