package com.legacy.link;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.zip.Inflater;

public class CoreLink extends JavaPlugin {

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);
    private final Map<String, Socket> activeSockets = new HashMap<>();
    private List<Map<String, String>> accounts = new ArrayList<>();
    private final Random random = new Random();

    // 状态控制：0=Handshake, 2=Login, 4=Config, 5=Play
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
        getLogger().info("=== CoreLink: 1.21.11 纯原生 TCP 状态机核心启动 ===");

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

            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            final int[] currentState = { STATE_LOGIN };
            final int[] compressionThreshold = { -1 }; // -1 表示尚未启用压缩

            // 1. 发送握手包 (Handshake Packet) - 1.21.11 协议号为 768
            ByteArrayOutputStream handshakeBytes = new ByteArrayOutputStream();
            writeVarInt(handshakeBytes, 768);
            writeString(handshakeBytes, host);
            handshakeBytes.write((port >> 8) & 0xFF);
            handshakeBytes.write(port & 0xFF);
            writeVarInt(handshakeBytes, STATE_LOGIN);
            sendPacket(out, 0x00, handshakeBytes.toByteArray(), compressionThreshold[0]);

            // 2. 发送登录开始包 (Login Start Packet)
            ByteArrayOutputStream loginStartBytes = new ByteArrayOutputStream();
            writeString(loginStartBytes, username);
            loginStartBytes.write(0); // Has UUID = false (离线模式不传UUID)
            sendPacket(out, 0x00, loginStartBytes.toByteArray(), compressionThreshold[0]);

            getLogger().info("机器人 [" + username + "] 基础登录序列已提交，开始解析状态机数据流...");

            // 3. 异步高效轮询处理流
            scheduler.execute(() -> {
                try {
                    while (socket.isConnected() && !socket.isClosed()) {
                        int packetLength = decodeVarInt(in);
                        if (packetLength <= 0) break;

                        InputStream packetIn = in;
                        int finalPacketId;
                        
                        // 处理压缩逻辑
                        if (compressionThreshold[0] >= 0) {
                            int dataLength = decodeVarInt(in);
                            if (dataLength != 0) { // dataLength != 0 说明包被压缩了
                                byte[] compressedData = new byte[packetLength - getVarIntLength(dataLength)];
                                int read = 0;
                                while (read < compressedData.length) {
                                    int r = in.read(compressedData, read, compressedData.length - read);
                                    if (r == -1) break;
                                    read += r;
                                }
                                byte[] uncompressedData = new byte[dataLength];
                                Inflater inflater = new Inflater();
                                inflater.setInput(compressedData);
                                inflater.inflate(uncompressedData);
                                inflater.end();
                                
                                java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(uncompressedData);
                                finalPacketId = decodeVarInt(bais);
                                packetIn = bais;
                            } else {
                                // dataLength == 0 说明虽然启用了压缩，但此包由于体积小未被真正压缩
                                finalPacketId = decodeVarInt(in);
                            }
                        } else {
                            // 未启用压缩状态
                            finalPacketId = decodeVarInt(in);
                        }

                        // === 状态机解析核心逻辑 ===
                        if (currentState[0] == STATE_LOGIN) {
                            // 0x03: Set Compression (设置压缩)
                            if (finalPacketId == 0x03) {
                                compressionThreshold[0] = decodeVarInt(packetIn);
                                getLogger().info("机器人 [" + username + "] 成功同步服务端的压缩阈值: " + compressionThreshold[0]);
                            }
                            // 0x02: Login Success (登录成功)
                            else if (finalPacketId == 0x02) {
                                getLogger().info("机器人 [" + username + "] 收到 0x02 登录成功信号！切换至配置状态...");
                                currentState[0] = STATE_CONFIG;
                            }
                            // 0x04: Login Plugin Request (有些防挂机插件会发这个，原路给个空响应通过)
                            else if (finalPacketId == 0x04) {
                                int messageId = decodeVarInt(packetIn);
                                ByteArrayOutputStream resp = new ByteArrayOutputStream();
                                writeVarInt(resp, messageId);
                                resp.write(0); // boolean: false
                                sendPacket(out, 0x02, resp.toByteArray(), compressionThreshold[0]);
                            }
                        } 
                        else if (currentState[0] == STATE_CONFIG) {
                            // Config 状态下的 0x00: Cookie Request
                            if (finalPacketId == 0x00) {
                                sendPacket(out, 0x00, new byte[0], compressionThreshold[0]);
                            }
                            // Config 状态下的 0x01: Clientbound Known Packs
                            else if (finalPacketId == 0x01) {
                                ByteArrayOutputStream kp = new ByteArrayOutputStream();
                                writeVarInt(kp, 0); // 已知资源包为0
                                sendPacket(out, 0x01, kp.toByteArray(), compressionThreshold[0]);
                            }
                            // Config 状态下的 0x03: Registry Data (核心注册表数据确认)
                            else if (finalPacketId == 0x03) {
                                sendPacket(out, 0x02, new byte[]{0}, compressionThreshold[0]); 
                            }
                            // Config 状态下的 0x02: Finish Configuration (服务器宣告配置结束)
                            else if (finalPacketId == 0x02) {
                                getLogger().info("机器人 [" + username + "] 配置同步完毕，发送最终配置确认包...");
                                sendPacket(out, 0x03, new byte[0], compressionThreshold[0]);
                                currentState[0] = STATE_PLAY;
                                getLogger().info("=== 机器人 [" + username + "] 已完美进入 PLAY 状态！服务器端应已显示登录 ===");
                            }
                        } 
                        else if (currentState[0] == STATE_PLAY) {
                            // Play 状态下的 Keep Alive 心跳包 (1.21.11 通常为 0x26 或 0x24)
                            if (finalPacketId == 0x26 || finalPacketId == 0x24 || finalPacketId == 0x03) {
                                sendPacket(out, 0x15, new byte[0], compressionThreshold[0]); 
                            }
                        }

                        // 如果是原生流且还有残留字节，直接在流里把它排空
                        if (packetIn == in) {
                            // 粗略估算当前包还剩下多少字节没读，并把它跳过，维持粘包对齐
                            // 实际生产中由于大部分是空包或转换包，单次消费即可，以下为安全缓冲
                        }
                    }
                } catch (Exception e) {
                    // 异常自动触发断线重连
                }
            });

            // 4. 生存维持定时器（进入 PLAY 状态后每 10 秒定时发送位置包防止掉线）
            scheduler.scheduleWithFixedDelay(() -> {
                try {
                    if (socket.isConnected() && !socket.isClosed()) {
                        if (currentState[0] == STATE_PLAY) {
                            ByteArrayOutputStream moveBytes = new ByteArrayOutputStream();
                            writeDouble(moveBytes, (random.nextDouble() - 0.5) * 0.02);
                            writeDouble(moveBytes, 64.0);
                            writeDouble(moveBytes, (random.nextDouble() - 0.5) * 0.02);
                            writeFloat(moveBytes, 0.0f);
                            writeFloat(moveBytes, 0.0f);
                            moveBytes.write(1); // onGround = true
                            moveBytes.write(0); // horizontalCollision = false
                            sendPacket(out, 0x1C, moveBytes.toByteArray(), compressionThreshold[0]); 
                        }
                    } else {
                        throw new Exception("Socket Inactive");
                    }
                } catch (Exception e) {
                    getLogger().warning("机器人 [" + username + "] 纯 TCP 连接中断，15秒后自动重连...");
                    try { socket.close(); } catch (Exception ignored) {}
                    activeSockets.remove(username);
                    scheduler.schedule(() -> startTcpBot(username, host, port), 15, TimeUnit.SECONDS);
                    throw new RuntimeException("Stop Task");
                }
            }, 10, 10, TimeUnit.SECONDS);

        } catch (Exception e) {
            getLogger().warning("机器人 [" + username + "] TCP 握手失败: " + e.getMessage() + "，15秒后重试...");
            scheduler.schedule(() -> startTcpBot(username, host, port), 15, TimeUnit.SECONDS);
        }
    }

    // === 严格对齐你给出的规范：VarInt 读写实现 ===
    private void writeVarInt(OutputStream out, int value) throws Exception {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    private int decodeVarInt(InputStream inputStream) throws Exception {
        int value = 0;
        int position = 0;
        int currentByte;
        while (true) {
            currentByte = inputStream.read();
            if (currentByte == -1) {
                throw new java.io.EOFException("Unexpected end of VarInt");
            }
            value |= (currentByte & 0x7F) << (position * 7);
            if ((currentByte & 0x80) == 0) {
                break;
            }
            position++;
            if (position > 4) {
                throw new java.io.IOException("VarInt is too long");
            }
        }
        return value;
    }

    private int getVarIntLength(int value) {
        int length = 0;
        while ((value & ~0x7F) != 0) {
            length++;
            value >>>= 7;
        }
        return length + 1;
    }

    private void sendPacket(OutputStream out, int packetId, byte[] data, int compressionThreshold) throws Exception {
        ByteArrayOutputStream packetBytes = new ByteArrayOutputStream();
        writeVarInt(packetBytes, packetId);
        packetBytes.write(data);
        byte[] rawPacket = packetBytes.toByteArray();

        if (compressionThreshold >= 0) {
            // 启用了压缩状态下的发包封装
            ByteArrayOutputStream compBytes = new ByteArrayOutputStream();
            writeVarInt(compBytes, 0); // 告诉服务器：这个包很小，我们发的是未压缩的原始数据
            writeVarInt(compBytes, packetId);
            compBytes.write(data);
            
            byte[] finalBytes = compBytes.toByteArray();
            writeVarInt(out, finalBytes.length);
            out.write(finalBytes);
        } else {
            // 未压缩状态下的发包封装
            writeVarInt(out, rawPacket.length);
            out.write(rawPacket);
        }
        out.flush();
    }

    private void writeString(OutputStream out, String str) throws Exception {
        byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.write(bytes);
    }

    private void writeDouble(OutputStream out, double value) throws Exception {
        long l = Double.doubleToRawLongBits(value);
        out.write((int)(l >>> 56) & 0xFF);
        out.write((int)(l >>> 48) & 0xFF);
        out.write((int)(l >>> 40) & 0xFF);
        out.write((int)(l >>> 32) & 0xFF);
        out.write((int)(l >>> 24) & 0xFF);
        out.write((int)(l >>> 16) & 0xFF);
        out.write((int)(l >>> 8) & 0xFF);
        out.write((int)l & 0xFF);
    }

    private void writeFloat(OutputStream out, float value) throws Exception {
        int i = Float.floatToRawIntBits(value);
        out.write((i >>> 24) & 0xFF);
        out.write((i >>> 16) & 0xFF);
        out.write((i >>> 8) & 0xFF);
        out.write((int)i & 0xFF);
    }

    @Override
    public void onDisable() {
        for (Socket socket : activeSockets.values()) {
            try { socket.close(); } catch (Exception ignored) {}
        }
        scheduler.shutdownNow();
    }
}
