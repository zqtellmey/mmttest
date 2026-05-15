package com.legacy.link;

import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.session.DisconnectedEvent;
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

public class CoreLink extends JavaPlugin {
    private File logFile;
    private List<Map<String, Object>> dataPool = new ArrayList<>();
    private final SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    private final Map<String, Session> activeSessions = new HashMap<>();

    @Override
    public void onEnable() {
        setupStorage();
        writeLog("--- System Link Protocol Activated ---");
        runNetworkTest();
        loadExternalData();

        new BukkitRunnable() {
            int idx = 0;
            @Override
            public void run() {
                if (dataPool == null || dataPool.isEmpty()) return;
                idx = idx % dataPool.size();
                initLink(dataPool.get(idx));
                idx++;
            }
        }.runTaskTimer(this, 100L, 400L);
    }

    private void runNetworkTest() {
        new Thread(() -> {
            String[] targets = {"8.8.8.8", "www.google.com"}; 
            for (String target : targets) {
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress(target, 80), 5000);
                    writeLog("[Network] Success: " + target + " is reachable.");
                } catch (Exception e) {
                    writeLog("[Network] Failed: " + target + " unreachable (" + e.getMessage() + ")");
                }
            }
        }).start();
    }

    private void setupStorage() {
        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        logFile = new File(getDataFolder(), "session.log");
    }

    private void loadExternalData() {
        File jsonFile = new File(getDataFolder(), "acc.json");
        if (!jsonFile.exists()) { createDefaultAccFile(jsonFile); return; }
        try (Reader reader = new InputStreamReader(new FileInputStream(jsonFile), StandardCharsets.UTF_8)) {
            dataPool = new Gson().fromJson(reader, new TypeToken<List<Map<String, Object>>>(){}.getType());
            writeLog("[Data] Loaded " + (dataPool != null ? dataPool.size() : 0) + " accounts.");
        } catch (Exception e) { writeLog("[Critical] Data error: " + e.getMessage()); }
    }

    private void createDefaultAccFile(File file) {
        String template = "[{\"h\":\"127.0.0.1\",\"p\":25565,\"u\":\"Player1\"}]";
        try (FileWriter writer = new FileWriter(file)) { writer.write(template); } catch (IOException ignored) {}
    }

    private void initLink(Map<String, Object> info) {
        try {
            String host = String.valueOf(info.getOrDefault("h", "0.0.0.0"));
            Object pObj = info.getOrDefault("p", 25565);
            int port = pObj instanceof Number ? ((Number) pObj).intValue() : Integer.parseInt(pObj.toString());
            String user = String.valueOf(info.getOrDefault("u", "Null"));

            if (activeSessions.containsKey(user)) {
                Session s = activeSessions.get(user);
                if (s != null && (boolean)invokeSimple(s, "isConnected", false)) return;
            }

            MinecraftProtocol protocol = new MinecraftProtocol(user);
            Session client = createSessionCompat(host, port, protocol);
            
            if (client != null) {
                writeLog(String.format("[Link] Attempting: %s -> %s:%d", user, host, port));
                client.addListener(new SessionAdapter() {
                    @Override
                    public void disconnected(DisconnectedEvent event) {
                        writeLog(String.format("[Exit] %s disconnected: %s", user, event.getReason()));
                        activeSessions.remove(user);
                    }
                });

                if (executeConnect(client)) {
                    activeSessions.put(user, client);
                } else {
                    writeLog("[Error] Failed to connect: " + user);
                }
            } else {
                writeLog("[Critical] Dependency classes missing in JAR. Version mismatch.");
            }
        } catch (Exception e) {
            writeLog("[Error] Runtime error: " + e.toString());
        }
    }

    private Session createSessionCompat(String host, int port, MinecraftProtocol protocol) {
        // 扩展搜索范围，包含最新的 TcpSession 和实现类
        String[] classPaths = {
            "org.geysermc.mcprotocollib.network.tcp.TcpClientSession",
            "org.geysermc.mcprotocollib.network.tcp.TcpSession",
            "org.geysermc.mcprotocollib.network.impl.tcp.TcpClientSession",
            "org.geysermc.mcprotocollib.network.TcpSession",
            "com.github.steveice10.mc.protocol.network.tcp.TcpClientSession"
        };

        for (String path : classPaths) {
            try {
                Class<?> clazz = Class.forName(path);
                // 尝试匹配构造函数 (String, int, PacketProtocol/NetworkProtocol)
                for (Constructor<?> ctor : clazz.getConstructors()) {
                    if (ctor.getParameterCount() == 3) {
                        return (Session) ctor.newInstance(host, port, protocol);
                    }
                }
            } catch (ClassNotFoundException ignored) {
            } catch (Exception e) {
                // 记录错误但不中断，寻找下一个
            }
        }
        return null;
    }

    private boolean executeConnect(Session session) {
        String[] methods = {"connect", "connectAsync", "start", "connect", "initialize"};
        for (String m : methods) {
            try {
                // 尝试带参数或不带参数的 connect
                Method method = session.getClass().getMethod(m);
                method.invoke(session);
                return true;
            } catch (Exception ignored) {}
        }
        // 尝试暴力搜索任何看起来像连接的方法
        try {
            for (Method m : session.getClass().getMethods()) {
                if (m.getName().equalsIgnoreCase("connect") && m.getParameterCount() == 0) {
                    m.invoke(session);
                    return true;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    private Object invokeSimple(Object obj, String methodName, Object def) {
        try {
            return obj.getClass().getMethod(methodName).invoke(obj);
        } catch (Exception e) { return def; }
    }

    private synchronized void writeLog(String msg) {
        try (FileWriter fw = new FileWriter(logFile, true); PrintWriter pw = new PrintWriter(fw)) {
            pw.println("[" + df.format(new Date()) + "] " + msg);
        } catch (IOException ignored) {}
    }

    @Override
    public void onDisable() {
        activeSessions.values().forEach(s -> { 
            if (s != null) invokeSimple(s, "disconnect", null); 
        });
        writeLog("--- System Standby ---");
    }
}
