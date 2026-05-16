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
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class CoreLink extends JavaPlugin {

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);
    private final Map<String, Socket> activeSockets = new HashMap<>();
    private List<Map<String, String>> accounts = new ArrayList<>();
    private final Random random = new Random();
    
    // 日志文件专用写入器
    private PrintWriter logWriter;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");

    // 协议状态常量
    private static final int STATE_LOGIN = 2;
    private static final int STATE_CONFIG = 4;
    private static final int STATE_PLAY = 5;

    @Override
    public void onLoad() {
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }
        // 初始化 RUN.LOG 文件
        File logFile = new File(getDataFolder(), "RUN.LOG");
        try {
            // 使用 append = true 模式，防止重启服务器时覆盖掉之前的关键线索
            logWriter = new PrintWriter(new FileWriter(logFile, StandardCharsets.UTF_8, true), true);
            writeLog("================== 插件初始化加载，RUN.LOG 侦听启动 ==================");
        } catch (IOException e) {
            getLogger().severe("无法创建或打开 RUN.LOG 文件: " + e.getMessage());
        }
    }

    @Override
    public void onEnable() {
        getLogger().info("=== CoreLink: 1.21.11 独立日志调试版启动 (请到插件文件夹查看 RUN.LOG) ===");
        writeLog("=== 核心服务启动：开始读取账号配置 ===");

        File file = new File(getDataFolder(), "acc.json");
        if (!file.exists()) {
            try (FileWriter writer = new FileWriter(file, StandardCharsets.UTF_8)) {
                String defaultJson = "[\n  {\n    \"u\": \"Bot_Worker1\",\n    \"h\": \"127.0.0.1\",\n    \"p\": \"25565\"\n  }\n]";
                writer.write(defaultJson);
                writer.flush();
            } catch (Exception e) {
                writeLog("[错误] 初始化默认 acc.json 失败: " + e.getMessage());
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
            writeLog("[严重错误] 加载 acc.json 失败: " + e.getMessage());
        }
    }

    private void startTcpBot(String username, String host, int port) {
        try {
            writeLog(String.format("[%s] 正在发起标准 TCP 套接字连接 -> %s:%d", username, host, port));
            Socket socket = new Socket(host, port);
            activeSockets.put(username, socket);

            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());

            final int[] currentState = { STATE_LOGIN };

            // 1. 发送 1.21.11 专属握手包 (协议号 768)
            ByteArrayOutputStream handshakeBytes = new ByteArrayOutputStream();
            DataOutputStream handshakeBuf = new DataOutputStream(handshakeBytes);
            writeVarInt(handshakeBuf, 768); 
            writeString(handshakeBuf, host);
            handshakeBuf.writeShort(port);
            writeVarInt(handshakeBuf, STATE_LOGIN); 
            
            writeLog(String.format("[%s] 正在拼装并发送 Handshake 握手包...", username));
            sendPacket(out, 0x00, handshakeBytes.toByteArray());

            // 2. 发送精准 1.21.11 Login Start 包
            ByteArrayOutputStream loginStartBytes = new ByteArrayOutputStream();
            DataOutputStream loginStartBuf = new DataOutputStream(loginStartBytes);
            writeString(loginStartBuf, username);
            
            // 1.21.11 规范：先写入一个 Boolean(true) 代表紧跟 UUID，随后塞入 16 字节 UUID
            loginStartBuf.writeBoolean(true); 
            UUID mockUuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
            loginStartBuf.writeLong(mockUuid.getMostSignificantBits());
            loginStartBuf.writeLong(mockUuid.getLeastSignificantBits());
            
            writeLog(String.format("[%s] 正在发送 Login Start 包 (UUID: %s)", username, mockUuid));
            sendPacket(out, 0x00, loginStartBytes.toByteArray());

            writeLog(String.format("[%s] 基础登录序列已成功提交到 TCP 管道。网络监听就绪，开始阻塞等待服务器响应...", username));

            // 3. 异步高信息度流解析轮询
            scheduler.execute(() -> {
                try {
                    while (socket.isConnected() && !socket.isClosed()) {
                        writeLog(String.format("[%s][网络队列] 正在尝试读取下一个数据包的 VarInt 长度头...", username));
                        int length = readVarInt(in);
                        writeLog(String.format("[%s][网络队列] 成功截获数据包！声明的总长度(Length)为: %d 字节", username, length));
                        
                        if (length <= 0) {
                            writeLog(String.format("[%s][警告] 收到非法或零长度的空包包头，断开当前监听循环。", username));
                            break;
                        }

                        // 一次性无损读取该包在套接字里的完整残留，防粘包、防后续流错位
                        byte[] packetBuffer = new byte[length];
                        in.readFully(packetBuffer);
                        
                        // 核心：直接把抓到的原始网络字节以 Hex 十六进制写入 RUN.LOG 
                        writeLog(String.format("[%s][原始 Hex 监控] 传入字节流: %s", username, bytesToHex(packetBuffer)));

                        java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(packetBuffer);
                        DataInputStream packetIn = new DataInputStream(bais);
                        
                        int packetId = readVarInt(packetIn);
                        writeLog(String.format("[%s][协议解析] 当前状态 [%d] -> 成功分离出 Packet ID: 0x%s", username, currentState[0], Integer.toHexString(packetId).toUpperCase()));

                        if (currentState[0] == STATE_LOGIN) {
                            // 0x00: Disconnect (被踢出)
                            if (packetId == 0x00) {
                                String reason = readString(packetIn);
                                writeLog(String.format("[%s][拒绝登录] 服务器亮起断开红灯，原因文本: %s", username, reason));
                                break;
                            }
                            // 0x02: Login Success
                            else if (packetId == 0x02) {
                                long mostSig = packetIn.readLong();
                                long leastSig = packetIn.readLong();
                                String receivedName = readString(packetIn);
                                writeLog(String.format("[%s][验证通过] 成功解析 0x02 登录成功信号！UUID: %s, 返回名称: %s", username, new UUID(mostSig, leastSig), receivedName));
                                writeLog(String.format("[%s][状态转移] 正在将客户端底层转换为 CONFIGURATION (配置) 阶段...", username));
                                currentState[0] = STATE_CONFIG;
                            } 
                            // 0x03: Set Compression
                            else if (packetId == 0x03) {
                                int threshold = readVarInt(packetIn);
                                writeLog(String.format("[%s][网络通告] 服务器要求激活网路 Zlib 压缩，阈值指定为: %d 字节", username, threshold));
                            }
                            // 0x04: Login Plugin Request
                            else if (packetId == 0x04) {
                                int messageId = readVarInt(packetIn);
                                String channel = readString(packetIn);
                                writeLog(String.format("[%s][自定义握手] 拦截到第 3 方安全插件通道请求: %s (ID: %d)，自动下发空应答...", username, channel, messageId));
                                ByteArrayOutputStream resp = new ByteArrayOutputStream();
                                DataOutputStream respBuf = new DataOutputStream(resp);
                                writeVarInt(respBuf, messageId);
                                respBuf.writeBoolean(false); 
                                sendPacket(out, 0x02, resp.toByteArray());
                            }
                        } 
                        else if (currentState[0] == STATE_CONFIG) {
                            // 0x00: Cookie Request
                            if (packetId == 0x00) {
                                writeLog(String.format("[%s][CONFIG] 回复服务器端基础网络 Cookie 校验...", username));
                                sendPacket(out, 0x00, new byte[0]);
                            }
                            // 0x01: Clientbound Known Packs
                            else if (packetId == 0x01) {
                                writeLog(String.format("[%s][CONFIG] 响应服务器注册资源包清单声明...", username));
                                ByteArrayOutputStream kp = new ByteArrayOutputStream();
                                DataOutputStream kpBuf = new DataOutputStream(kp);
                                writeVarInt(kpBuf, 0); 
                                sendPacket(out, 0x01, kp.toByteArray());
                            }
                            // 0x02: Finish Configuration
                            else if (packetId == 0x02) {
                                writeLog(String.format("[%s][CONFIG] 拿到完成配置通告（Finish Configuration）！回传最终确认包...", username));
                                sendPacket(out, 0x03, new byte[0]);
                                currentState[0] = STATE_PLAY;
                                writeLog(String.format("[%s] === [大成功] 机器人已完美进入 PLAY 游戏状态！ ===", username));
                            }
                            // 0x03: Registry Data
                            else if (packetId == 0x03) {
                                writeLog(String.format("[%s][CONFIG] 接收核心元素注册表数据，发送回显确认...", username));
                                sendPacket(out, 0x02, new byte[]{0}); 
                            }
                        } 
                        else if (currentState[0] == STATE_PLAY) {
                            // 游戏内保持活跃心跳
                            if (packetId == 0x26 || packetId == 0x24 || packetId == 0x03) {
                                long id = packetIn.readLong();
                                ByteArrayOutputStream kaBytes = new ByteArrayOutputStream();
                                DataOutputStream kaBuf = new DataOutputStream(kaBytes);
                                kaBuf.writeLong(id);
                                sendPacket(out, 0x15, kaBytes.toByteArray());
                                writeLog(String.format("[%s][心跳生命线] 成功回应服务器 Ping 心跳，ID: %d", username, id));
                            }
                        }
                    }
                } catch (Exception e) {
                    writeLog(String.format("[%s][网络流断开或崩溃] 错误详情: %s", username, e.getMessage()));
                }
            });

            // 4. 定时生存守护
            scheduler.scheduleWithFixedDelay(() -> {
                try {
                    if (socket.isConnected() && !socket.isClosed()) {
                        if (currentState[0] == STATE_PLAY) {
                            ByteArrayOutputStream moveBytes = new ByteArrayOutputStream();
                            DataOutputStream moveBuf = new DataOutputStream(moveBytes);
                            moveBuf.writeDouble((random.nextDouble() - 0.5) * 0.01);
                            moveBuf.writeDouble(64.0);
                            moveBuf.writeDouble((random.nextDouble() - 0.5) * 0.01);
                            moveBuf.writeFloat(0.0f);
                            moveBuf.writeFloat(0.0f);
                            moveBuf.writeBoolean(true);  
                            moveBuf.writeBoolean(false); 
                            sendPacket(out, 0x1C, moveBytes.toByteArray());
                        }
                    } else {
                        throw new Exception("套接字已被服务器底层强行掐断。");
                    }
                } catch (Exception e) {
                    writeLog(String.format("[%s][挂机守护] 检测到连接丢失 (%s)，15秒后启动自动全功能重连...", username, e.getMessage()));
                    try { socket.close(); } catch (Exception ignored) {}
                    activeSockets.remove(username);
                    scheduler.schedule(() -> startTcpBot(username, host, port), 15, TimeUnit.SECONDS);
                    throw new RuntimeException("Terminate Guard");
                }
            }, 10, 10, TimeUnit.SECONDS);

        } catch (Exception e) {
            writeLog(String.format("[%s][套接字初始化失败] 无法连通目标主机: %s", username, e.getMessage()));
            scheduler.schedule(() -> startTcpBot(username, host, port), 15, TimeUnit.SECONDS);
        }
    }

    // === 内部通用流日志输出方法 ===
    private synchronized void writeLog(String message) {
        if (logWriter != null) {
            String timeStamp = dateFormat.format(new Date());
            logWriter.println("[" + timeStamp + "] " + message);
        }
    }

    // === VarInt 读写核心算法 ===
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

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString().trim();
    }

    @Override
    public void onDisable() {
        writeLog("================== 插件服务关闭，断开所有网络流 ==================");
        for (Socket socket : activeSockets.values()) {
            try { socket.close(); } catch (Exception ignored) {}
        }
        if (logWriter != null) {
            logWriter.close();
        }
        scheduler.shutdownNow();
    }
}
