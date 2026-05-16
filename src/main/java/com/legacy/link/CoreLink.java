package com.legacy.link;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
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

    // 1.21.11 协议状态机常量
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
        getLogger().info("=== CoreLink: 1.21.11 原生 TCP 精准协议版启动 ===");

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
            getLogger().info("正在发起标准 TCP 连接 -> " + host + ":" + port + " (用户: " + username + ")");
            Socket socket = new Socket(host, port);
            activeSockets.put(username, socket);

            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());

            final int[] currentState = { STATE_LOGIN };

            // 1. 发送 1.21.11 专属握手包 (协议号 768)
            ByteArrayOutputStream handshakeBytes = new ByteArrayOutputStream();
            DataOutputStream handshakeBuf = new DataOutputStream(handshakeBytes);
            writeVarInt(handshakeBuf, 768); // 1.21.11 Protocol Version
            writeString(handshakeBuf, host);
            handshakeBuf.writeShort(port);
            writeVarInt(handshakeBuf, STATE_LOGIN); // Next State = 2
            sendPacket(out, 0x00, handshakeBytes.toByteArray());

            // 2. 发送 1.21.11 专属 Login Start 包 (结构已彻底改变，包含UUID标记)
            ByteArrayOutputStream loginStartBytes = new ByteArrayOutputStream();
            DataOutputStream loginStartBuf = new DataOutputStream(loginStartBytes);
            writeString(loginStartBuf, username);
            
            // 关键：1.21.11 离线模式下，必须写入一个固定的伪造 UUID 来补全流，否则服务器解包崩溃
            UUID mockUuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
            loginStartBuf.writeLong(mockUuid.getMostSignificantBits());
            loginStartBuf.writeLong(mockUuid.getLeastSignificantBits());
            sendPacket(out, 0x00, loginStartBytes.toByteArray());

            getLogger().info("机器人 [" + username + "] 1.21.11 规范登录流已提交，开始解析状态机数据...");

            // 3. 异步监听接收
            scheduler.execute(() -> {
                try {
                    while (socket.isConnected() && !socket.isClosed()) {
                        int length = readVarInt(in);
                        if (length <= 0) break;

                        int packetId = readVarInt(in);

                        if (currentState[0] == STATE_LOGIN) {
                            // 0x02: Login Success (升级至 1.21.11 结构读取)
                            if (packetId == 0x02) {
                                // 1.21.11 的 Login Success 内部前16字节是二进制 UUID，后面是用户名 String
                                long mostSig = in.readLong();
                                long leastSig = in.readLong();
                                String receivedName = readString(in);
                                
                                getLogger().info("机器人 [" + username + "] 成功解析 0x02 登录成功包！进入配置阶段...");
                                currentState[0] = STATE_CONFIG;
                            } 
                            // 0x03: Set Compression
                            else if (packetId == 0x03) {
                                int threshold = readVarInt(in);
                                getLogger().info("服务器请求设置压缩阈值: " + threshold + " (离线连接自动忽略挂载)");
                            }
                        } 
                        else if (currentState[0] == STATE_CONFIG) {
                            // 1.21.11 配置阶段的 0x00: Cookie Request 
                            if (packetId == 0x00) {
                                sendPacket(out, 0x00, new byte[0]); // 响应空 Cookie
                            }
                            // 1.21.11 配置阶段的 0x01: 已知资源包请求
                            else if (packetId == 0x01) {
                                ByteArrayOutputStream kp = new ByteArrayOutputStream();
                                DataOutputStream kpBuf = new DataOutputStream(kp);
                                writeVarInt(kpBuf, 0); // 数量为0
                                sendPacket(out, 0x01, kp.toByteArray());
                            }
                            // 1.21.11 配置阶段的 0x02: Finish Configuration (配置结束)
                            else if (packetId == 0x02) {
                                getLogger().info("机器人 [" + username + "] 收到完成配置指令，正在下发 Acknowledge 确认...");
                                // 发送配置阶段结束确认应答包 (0x03)
                                sendPacket(out, 0x03, new byte[0]);
                                currentState[0] = STATE_PLAY;
                                getLogger().info("=== 机器人 [" + username + "] 已完美跨入 PLAY 状态！服务器端应已显示登录 ===");
                            }
                        } 
                        else if (currentState[0] == STATE_PLAY) {
                            // 1.21.11 正式游戏内的 Keep Alive 心跳维持
                            if (packetId == 0x26 || packetId == 0x24) {
                                // 提取心跳 ID 并原路应答
                                long id = in.readLong();
                                ByteArrayOutputStream kaBytes = new ByteArrayOutputStream();
                                DataOutputStream kaBuf = new DataOutputStream(kaBytes);
                                kaBuf.writeLong(id);
                                sendPacket(out, 0x15, kaBytes.toByteArray());
                            }
                        }
                    }
                } catch (Exception e) {
                    // 异常自动丢给下面的定时守护任务进行安全重连
                }
            });

            // 4. 生存与定时坐标维持守护线程
            scheduler.scheduleWithFixedDelay(() -> {
                try {
                    if (socket.isConnected() && !socket.isClosed()) {
                        if (currentState[0] == STATE_PLAY) {
                            // 1.21.11 标准静止挂机位移包 (0x1C)
                            ByteArrayOutputStream moveBytes = new ByteArrayOutputStream();
                            DataOutputStream moveBuf = new DataOutputStream(moveBytes);
                            moveBuf.writeDouble((random.nextDouble() - 0.5) * 0.01);
                            moveBuf.writeDouble(64.0);
                            moveBuf.writeDouble((random.nextDouble() - 0.5) * 0.01);
                            moveBuf.writeFloat(0.0f);
                            moveBuf.writeFloat(0.0f);
                            moveBuf.writeBoolean(true);  // onGround
                            moveBuf.writeBoolean(false); // horizontalCollision
                            sendPacket(out, 0x1C, moveBytes.toByteArray());
                        }
                    } else {
                        throw new Exception("Socket closed");
                    }
                } catch (Exception e) {
                    getLogger().warning("机器人 [" + username + "] 连接断开，15秒后自动重连...");
                    try { socket.close(); } catch (Exception ignored) {}
                    activeSockets.remove(username);
                    scheduler.schedule(() -> startTcpBot(username, host, port), 15, TimeUnit.SECONDS);
                    throw new RuntimeException("Exit Task");
                }
            }, 10, 10, TimeUnit.SECONDS);

        } catch (Exception e) {
            getLogger().warning("机器人 [" + username + "] 纯 TCP 握手失败: " + e.getMessage() + "，15秒后重试...");
            scheduler.schedule(() -> startTcpBot(username, host, port), 15, TimeUnit.SECONDS);
        }
    }

    // === 完全复刻自 McBotApp 项目官方规范的 VarInt 读写方法 ===
    private static int readVarInt(DataInputStream in) throws IOException {
        int value = 0;
        int position = 0;
        byte currentByte;
        do {
            currentByte = in.readByte();
            value |= (currentByte & 0x7F) << (position++ * 7);
            if (position > 5) {
                throw new IOException("VarInt too big");
            }
        } while ((currentByte & 0x80) != 0);
        return value;
    }

    private static void writeVarInt(DataOutputStream out, int value) throws IOException {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    private static String readString(DataInputStream in) throws IOException {
        int length = readVarInt(in);
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeString(DataOutputStream out, String str) throws IOException {
        byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.write(bytes);
    }

    private void sendPacket(DataOutputStream out, int packetId, byte[] data) throws Exception {
        ByteArrayOutputStream packetBytes = new ByteArrayOutputStream();
        DataOutputStream packetBuf = new DataOutputStream(packetBytes);
        writeVarInt(packetBuf, packetId);
        packetBuf.write(data);

        byte[] rawPacket = packetBytes.toByteArray();
        writeVarInt(out, rawPacket.length);
        out.write(rawPacket);
        out.flush();
    }

    @Override
    public void onDisable() {
        for (Socket socket : activeSockets.values()) {
            try { socket.close(); } catch (Exception ignored) {}
        }
        scheduler.shutdownNow();
    }
}
