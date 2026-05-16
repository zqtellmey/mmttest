package com.legacy.link;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.ByteArrayInputStream;
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
import java.util.zip.Inflater;

public class CoreLink extends JavaPlugin {

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);
    private final Map<String, Socket> activeSockets = new HashMap<>();
    private List<Map<String, String>> accounts = new ArrayList<>();
    private final Random random = new Random();
    
    private PrintWriter logWriter;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");

    private static final int STATE_LOGIN = 2;
    private static final int STATE_CONFIG = 4;
    private static final int STATE_PLAY = 5;

    @Override
    public void onLoad() {
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }
        File logFile = new File(getDataFolder(), "RUN.LOG");
        try {
            logWriter = new PrintWriter(new FileWriter(logFile, StandardCharsets.UTF_8, true), true);
            writeLog("================== 插件初始化加载，RUN.LOG 侦听启动 ==================");
        } catch (IOException e) {
            getLogger().severe("无法创建或打开 RUN.LOG 文件: " + e.getMessage());
        }
    }

    @Override
    public void onEnable() {
        getLogger().info("=== CoreLink: 1.21.11 协议对齐极致版启动 ===");
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
            socket.setTcpNoDelay(true); // 禁用 Nagle 算法，让发包立刻冲出去，绝不积压黏包
            activeSockets.put(username, socket);

            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());

            final int[] currentState = { STATE_LOGIN };
            final int[] compressionThreshold = { -1 };

            // ==========================================
            // 【核心重构】建立独立、干净的临时缓冲区，绝不复用
            // ==========================================
            
            // 1. 独立拼装 Handshake 包 (0x00)
            ByteArrayOutputStream handshakeBytes = new ByteArrayOutputStream();
            DataOutputStream handshakeBuf = new DataOutputStream(handshakeBytes);
            writeVarInt(handshakeBuf, 774); // 1.21 / 1.21.1 协议号
            writeString(handshakeBuf, host);
            handshakeBuf.writeShort(port);
            writeVarInt(handshakeBuf, STATE_LOGIN); 
            
            // 2. 独立拼装 Login Start 包 (0x00) - 严格参照你提供的 MCBOTAPP 结构
            ByteArrayOutputStream loginStartBytes = new ByteArrayOutputStream();
            DataOutputStream loginStartBuf = new DataOutputStream(loginStartBytes);
            writeString(loginStartBuf, username); // 仅写入 Username

            // 3. 顺序发送，并强制刷出数据流边界
            writeLog(String.format("[%s] 正在发送 Handshake 握手包...", username));
            sendPacket(out, 0x00, handshakeBytes.toByteArray(), -1);
            out.flush(); // 强制 TCP 管道刷新

            writeLog(String.format("[%s] 正在发送纯净 Login Start 包 (用户名: %s)...", username, username));
            sendPacket(out, 0x00, loginStartBytes.toByteArray(), -1);
            out.flush(); // 再次强制刷新，确保两条数据在 Netty 侧被切分成两个物理帧

            writeLog(String.format("[%s] 基础登录序列已成功提交到 TCP 管道。开始网络数据流轮询...", username));

            // 4. 异步流解析轮询
            scheduler.execute(() -> {
                try {
                    while (socket.isConnected() && !socket.isClosed()) {
                        int length = readVarInt(in);
                        
                        if (length <= 0) {
                            break;
                        }

                        byte[] packetBuffer = new byte[length];
                        in.readFully(packetBuffer);
                        
                        DataInputStream packetIn;
                        ByteArrayInputStream bais;

                        if (compressionThreshold[0] >= 0) {
                            bais = new ByteArrayInputStream(packetBuffer);
                            DataInputStream compReader = new DataInputStream(bais);
                            int dataLength = readVarInt(compReader);
                            
                            if (dataLength == 0) {
                                int remaining = bais.available();
                                byte[] uncompressedData = new byte[remaining];
                                compReader.readFully(uncompressedData);
                                packetIn = new DataInputStream(new ByteArrayInputStream(uncompressedData));
                            } else {
                                byte[] compressedData = new byte[bais.available()];
                                compReader.readFully(compressedData);
                                byte[] uncompressedData = decompress(compressedData, dataLength);
                                packetIn = new DataInputStream(new ByteArrayInputStream(uncompressedData));
                            }
                        } else {
                            bais = new ByteArrayInputStream(packetBuffer);
                            packetIn = new DataInputStream(bais);
                        }
                        
                        int packetId = readVarInt(packetIn);
                        writeLog(String.format("[%s][协议解析] 当前状态 [%d] -> 收到 Packet ID: 0x%s", username, currentState[0], Integer.toHexString(packetId).toUpperCase()));

                        // ==================== STATE_LOGIN 状态分支 ====================
                        if (currentState[0] == STATE_LOGIN) {
                            if (packetId == 0x00) { 
                                String reason = readString(packetIn);
                                writeLog(String.format("[%s][拒绝登录] 服务器拒绝连接，原因: %s", username, reason));
                                break;
                            }
                            else if (packetId == 0x02) { // Login Success
                                long mostSig = packetIn.readLong();
                                long leastSig = packetIn.readLong();
                                String receivedName = readString(packetIn);
                                writeLog(String.format("[%s][验证通过] 成功解析 0x02 登录成功信号。状态切入 CONFIG...", username));
                                
                                currentState[0] = STATE_CONFIG;
                                
                                // 边界缓冲空隙 25ms 延迟，优雅规避时序死锁
                                scheduler.schedule(() -> {
                                    try {
                                        if (socket.isConnected() && !socket.isClosed()) {
                                            writeLog(String.format("[%s][边界对齐] 正在提交 CONFIG 首包 0x00 Client Information...", username));
                                            ByteArrayOutputStream clientInfo = new ByteArrayOutputStream();
                                            DataOutputStream ciBuf = new DataOutputStream(clientInfo);
                                            writeString(ciBuf, "zh_CN");      
                                            ciBuf.writeByte(10);              
                                            writeVarInt(ciBuf, 0);            
                                            ciBuf.writeBoolean(true);         
                                            ciBuf.writeByte(127);             
                                            writeVarInt(ciBuf, 1);            
                                            ciBuf.writeBoolean(false);        
                                            ciBuf.writeBoolean(true);         
                                            
                                            sendPacket(out, 0x00, clientInfo.toByteArray(), compressionThreshold[0]);
                                            out.flush();
                                            writeLog(String.format("[%s][边界对齐] 0x00 Client Information 发送完成。", username));
                                        }
                                    } catch (Exception ex) {
                                        writeLog(String.format("[%s][边界对齐发送失败] 异常: %s", username, ex.getMessage()));
                                    }
                                }, 25, TimeUnit.MILLISECONDS);
                            } 
                            else if (packetId == 0x03) { // Set Compression
                                compressionThreshold[0] = readVarInt(packetIn);
                                writeLog(String.format("[%s][网络通告] 激活 Zlib 压缩，阈值设定为: %d 字节", username, compressionThreshold[0]));
                            }
                            else if (packetId == 0x04) { // Login Plugin Request
                                int messageId = readVarInt(packetIn);
                                String channel = readString(packetIn);
                                ByteArrayOutputStream resp = new ByteArrayOutputStream();
                                DataOutputStream respBuf = new DataOutputStream(resp);
                                writeVarInt(respBuf, messageId);
                                respBuf.writeBoolean(false); 
                                sendPacket(out, 0x02, resp.toByteArray(), compressionThreshold[0]);
                                out.flush();
                            }
                        } 
                        // ==================== STATE_CONFIG 状态分支 ====================
                        else if (currentState[0] == STATE_CONFIG) {
                            if (packetId == 0x01) { // Cookie Request 
                                String cookieKey = readString(packetIn);
                                writeLog(String.format("[%s][CONFIG] 响应 Cookie 校验 Key: '%s'", username, cookieKey));
                                
                                ByteArrayOutputStream cookieResp = new ByteArrayOutputStream();
                                DataOutputStream cookieRespBuf = new DataOutputStream(cookieResp);
                                writeString(cookieRespBuf, cookieKey);
                                cookieRespBuf.writeBoolean(false); 
                                
                                sendPacket(out, 0x00, cookieResp.toByteArray(), compressionThreshold[0]);
                                out.flush();
                            }
                            else if (packetId == 0x0E) { // Select Known Packs Request
                                writeLog(String.format("[%s][CONFIG] 响应资源包质询 (0x0E)", username));
                                ByteArrayOutputStream kp = new ByteArrayOutputStream();
                                DataOutputStream kpBuf = new DataOutputStream(kp);
                                writeVarInt(kpBuf, 0); 
                                sendPacket(out, 0x07, kp.toByteArray(), compressionThreshold[0]);
                                out.flush();
                            }
                            else if (packetId == 0x02) { // Finish Configuration
                                writeLog(String.format("[%s][CONFIG] 接收到 Finish Configuration 0x02！", username));
                                sendPacket(out, 0x03, new byte[0], compressionThreshold[0]);
                                out.flush();
                                
                                currentState[0] = STATE_PLAY;
                                writeLog(String.format("[%s] === [大成功] 挂机机器人已完美滑入 PLAY 阶段！ ===", username));
                            }
                            else if (packetId == 0x07) { // Custom Payload
                                String brandChannel = readString(packetIn);
                                ByteArrayOutputStream brandResp = new ByteArrayOutputStream();
                                writeString(new DataOutputStream(brandResp), "vanilla");
                                sendPacket(out, 0x02, brandResp.toByteArray(), compressionThreshold[0]); 
                                out.flush();
                            }
                        } 
                        // ==================== STATE_PLAY 状态分支 ====================
                        else if (currentState[0] == STATE_PLAY) {
                            if (packetId == 0x36 || packetId == 0x32 || packetId == 0x03) {
                                long id = packetIn.readLong();
                                ByteArrayOutputStream kaBytes = new ByteArrayOutputStream();
                                DataOutputStream kaBuf = new DataOutputStream(kaBytes);
                                kaBuf.writeLong(id);
                                sendPacket(out, 0x18, kaBytes.toByteArray(), compressionThreshold[0]);
                                out.flush();
                                writeLog(String.format("[%s][心跳] 维持服务器在线 Ping, ID: %d", username, id));
                            }
                        }
                    }
                } catch (Exception e) {
                    writeLog(String.format("[%s][断开] 错误详情: %s", username, e.getMessage() == null ? "EOF" : e.getMessage()));
                }
            });

            // 5. 定时生存守护
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
                            sendPacket(out, 0x1E, moveBytes.toByteArray(), compressionThreshold[0]);
                            out.flush();
                        }
                    } else {
                        throw new Exception("Socket closed");
                    }
                } catch (Exception e) {
                    writeLog(String.format("[%s][守护] 连接丢失，15秒后重连...", username));
                    try { socket.close(); } catch (Exception ignored) {}
                    activeSockets.remove(username);
                    scheduler.schedule(() -> startTcpBot(username, host, port), 15, TimeUnit.SECONDS);
                    throw new RuntimeException("Guard reset");
                }
            }, 10, 10, TimeUnit.SECONDS);

        } catch (Exception e) {
            writeLog(String.format("[%s][连接失败] 目标主机拒绝: %s", username, e.getMessage()));
            scheduler.schedule(() -> startTcpBot(username, host, port), 15, TimeUnit.SECONDS);
        }
    }

    private synchronized void writeLog(String message) {
        if (logWriter != null) {
            String timeStamp = dateFormat.format(new Date());
            logWriter.println("[" + timeStamp + "] " + message);
        }
    }

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

    private void sendPacket(DataOutputStream out, int packetId, byte[] data, int threshold) throws Exception {
        ByteArrayOutputStream packetBytes = new ByteArrayOutputStream();
        DataOutputStream packetBuf = new DataOutputStream(packetBytes);
        writeVarInt(packetBuf, packetId);
        packetBuf.write(data);
        byte[] rawPacket = packetBytes.toByteArray();

        ByteArrayOutputStream finalBytes = new ByteArrayOutputStream();
        DataOutputStream finalBuf = new DataOutputStream(finalBytes);

        if (threshold >= 0) {
            if (rawPacket.length >= threshold) {
                byte[] compressed = compress(rawPacket);
                writeVarInt(finalBuf, rawPacket.length);
                finalBuf.write(compressed);
            } else {
                writeVarInt(finalBuf, 0);
                finalBuf.write(rawPacket);
            }
        } else {
            finalBuf.write(rawPacket);
        }

        byte[] frame = finalBytes.toByteArray();
        writeVarInt(out, frame.length);
        out.write(frame);
    }

    private static byte[] decompress(byte[] data, int uncompressedLength) throws Exception {
        Inflater inflater = new Inflater();
        inflater.setInput(data);
        byte[] output = new byte[uncompressedLength];
        int resultLength = inflater.inflate(output);
        inflater.end();
        if (resultLength != uncompressedLength) {
            throw new IOException("Length mismatch");
        }
        return output;
    }

    private static byte[] compress(byte[] data) throws Exception {
        java.util.zip.Deflater deflater = new java.util.zip.Deflater();
        deflater.setInput(data);
        deflater.finish();
        ByteArrayOutputStream bos = new ByteArrayOutputStream(data.length);
        byte[] buffer = new byte[1024];
        while (!deflater.finished()) {
            int count = deflater.deflate(buffer);
            bos.write(buffer, 0, count);
        }
        deflater.end();
        return bos.toByteArray();
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
