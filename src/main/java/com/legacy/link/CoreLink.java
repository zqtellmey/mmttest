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

    // 协议状态常量定义
    private static final int STATE_LOGIN = 2;
    private static final int STATE_CONFIG = 4;
    private static final int STATE_PLAY = 5;

    @Override
    public void onLoad() {
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }
    }

    @Override
    public void onEnable() {
        getLogger().info("=== CoreLink: 1.21.11 原生 TCP 状态机模式已启动 ===");

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
            getLogger().info("正在建立原生 TCP 链路 -> " + host + ":" + port + " (用户: " + username + ")");
            Socket socket = new Socket(host, port);
            activeSockets.put(username, socket);

            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());

            // 当前连接所处的 Minecraft 内部协议状态，初始化为 LOGIN
            final int[] currentState = { STATE_LOGIN };

            // 1. 发送标准握手包 (Handshake) -> 1.21.11 协议号 768, 下一个状态行为 2 (Login)
            byte[] handshakeData = buildHandshake(host, port, 768, STATE_LOGIN);
            sendPacket(out, 0x00, handshakeData);

            // 2. 发送登录开始包 (Login Start)
            byte[] loginStartData = buildLoginStart(username);
            sendPacket(out, 0x00, loginStartData);

            getLogger().info("机器人 [" + username + "] 基础登录序列已提交，开始解析状态机数据流...");

            // 3. 核心：原生 TCP 数据流单线程轮询器，负责状态升级转换
            scheduler.execute(() -> {
                try {
                    while (socket.isConnected() && !socket.isClosed()) {
                        int length = readVarInt(in);
                        if (length <= 0) break;
                        
                        int packetId = readVarInt(in);

                        // 根据当前客户端所处的协议状态（State），分别处理对应的包 ID
                        if (currentState[0] == STATE_LOGIN) {
                            // Login 状态下的 0x02: Login Success (登录成功)
                            if (packetId == 0x02) {
                                getLogger().info("机器人 [" + username + "] 成功通过 Login 验证，等待转换为 Configuration 状态...");
                            }
                            // Login 状态下的 0x03: Set Compression (设置压缩，部分离线服会跳过或发送)
                            else if (packetId == 0x03) {
                                getLogger().info("机器人 [" + username + "] 收到压缩限制调整信号。");
                            }
                            // Login 状态下的 0x04: Login Plugin Request
                            else if (packetId == 0x04) {
                                int messageId = readVarInt(in);
                                // 原生直接对其进行空应答，拒绝或跳过特定的插件握手
                                byte[] resp = new byte[5]; // 模拟空的 Plugin Response
                                sendPacket(out, 0x02, resp);
                            }
                            
                            // 关键：在 1.21.11 中，服务器发送完登录成功后，会自动触发状态切换，
                            // 我们在此平滑地将接收器状态切换至 CONFIG 阶段
                            if (packetId == 0x02) {
                                currentState[0] = STATE_CONFIG;
                            }

                        } else if (currentState[0] == STATE_CONFIG) {
                            // Config 状态下的 0x00: Cookie Request
                            if (packetId == 0x00) {
                                sendPacket(out, 0x00, new byte[0]); // 应答空 Cookie
                            }
                            // Config 状态下的 0x01: Clientbound Known Packs (已知资源包同步)
                            else if (packetId == 0x01) {
                                // 告诉服务器：我们是一个没有安装任何额外 Mod 的原生客户端
                                java.io.ByteArrayOutputStream kBytes = new java.io.ByteArrayOutputStream();
                                DataOutputStream kBuf = new DataOutputStream(kBytes);
                                writeVarInt(kBuf, 0); // 已知 Pack 数量为 0
                                sendPacket(out, 0x01, kBytes.toByteArray());
                            }
                            // Config 状态下的 0x03: Registry Data (游戏核心注册表，比如方块、生物列表)
                            else if (packetId == 0x03) {
                                // 1.21.11 服务器在发完注册表后，客户端必须回显确认接收完毕
                                // 发送 Serverbound Select Known Packs / Config Acknowledge
                                sendPacket(out, 0x02, new byte[]{0}); 
                            }
                            // Config 状态下的 0x02: Finish Configuration (服务器宣告配置阶段结束！)
                            else if (packetId == 0x02) {
                                getLogger().info("机器人 [" + username + "] 收到 Finish Configuration，正在向服务器发送最终确认包...");
                                
                                // 客户端回应：Serverbound Finish Configuration Acknowledge (通常为 0x03 或 0x02)
                                sendPacket(out, 0x03, new byte[0]);
                                
                                // 正式跨入 PLAY 状态！此时服务器控制台将真正亮起玩家登录日志！
                                currentState[0] = STATE_PLAY;
                                getLogger().info("=== 机器人 [" + username + "] 已成功跨入 PLAY 游戏状态！服务器应当已显示加入日志 ===");
                            }

                        } else if (currentState[0] == STATE_PLAY) {
                            // Play 状态下的通用 Keep Alive 心跳请求 (1.21.11 通常为 0x26 或 0x24)
                            // 收到后必须将包里的 Long 类型 KeepAlive ID 原路返回
                            if (packetId == 0x26 || packetId == 0x24 || packetId == 0x03) {
                                // 原路应答心跳
                                sendPacket(out, 0x15, new byte[0]); 
                            }
                        }

                        // 这一步至关重要：彻底排空当前数据包中未被读取的残余字节，防止污染下一个 VarInt 包头的读取
                        long skipped = 0;
                        while (skipped < (length - 1)) {
                            long skipActual = in.skip((length - 1) - skipped);
                            if (skipActual <= 0) break;
                            skipped += skipActual;
                        }
                    }
                } catch (Exception e) {
                    // 异常断开时由外部的守护定时器处理重连
                }
            });

            // 4. 定时维持在线的任务（每10秒发送一次位置与生存状态同步）
            scheduler.scheduleWithFixedDelay(() -> {
                try {
                    if (socket.isConnected() && !socket.isClosed()) {
                        // 只有真正进入 PLAY 状态后，发位置同步包（0x1C）才合法有效
                        if (currentState[0] == STATE_PLAY) {
                            byte[] moveData = buildMoveData();
                            sendPacket(out, 0x1C, moveData); 
                        }
                    } else {
                        throw new Exception("Socket Lost");
                    }
                } catch (Exception e) {
                    getLogger().warning("机器人 [" + username + "] 连接断开，准备触发自动重连机制。");
                    try { socket.close(); } catch (Exception ignored) {}
                    activeSockets.remove(username);
                    scheduler.schedule(() -> startTcpBot(username, host, port), 15, TimeUnit.SECONDS);
                    throw new RuntimeException("Kill Task");
                }
            }, 10, 10, TimeUnit.SECONDS);

        } catch (Exception e) {
            getLogger().warning("机器人 [" + username + "] TCP 初始化失败: " + e.getMessage() + "，15秒后自动重连...");
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
        buf.writeDouble((random.nextDouble() - 0.5) * 0.02);
        buf.writeDouble(64.0);
        buf.writeDouble((random.nextDouble() - 0.5) * 0.02);
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
