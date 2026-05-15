package com.legacy.link;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.bukkit.plugin.java.JavaPlugin;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.session.DisconnectedEvent;
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.network.tcp.TcpSession; 
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundAcceptTeleportationPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosPacket;

import java.io.File;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class CoreLink extends JavaPlugin {

    private final List<Session> activeSessions = new ArrayList<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);
    private final Random random = new Random();

    @Override
    public void onEnable() {
        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        File file = new File(getDataFolder(), "acc.json");
        if (!file.exists()) saveResource("acc.json", false);

        getLogger().info("=== CoreLink: 1.21.11 纯 Java 模式启动 ===");
        
        try (FileReader reader = new FileReader(file, StandardCharsets.UTF_8)) {
            List<Map<String, String>> accounts = new Gson().fromJson(reader, new TypeToken<List<Map<String, String>>>(){}.getType());
            for (Map<String, String> acc : accounts) {
                // 支持你之前的 u, h, p 格式
                startBot(acc.get("u"), acc.get("h"), Integer.parseInt(acc.get("p")));
            }
        } catch (Exception e) {
            getLogger().severe("acc.json 加载失败: " + e.getMessage());
        }
    }

    private void startBot(String username, String host, int port) {
        MinecraftProtocol protocol = new MinecraftProtocol(username);
        // 使用修正后的 TcpSession
        Session client = new TcpSession(host, port, protocol);

        client.addListener(new SessionAdapter() {
            @Override
            public void packetReceived(Session session, Packet packet) {
                if (packet instanceof ClientboundPlayerPositionPacket p) {
                    // 必须回应传送包，否则会被服务器因“发包频率异常”踢出
                    session.send(new ServerboundAcceptTeleportationPacket(p.getTeleportId()));
                }
            }

            @Override
            public void disconnected(DisconnectedEvent event) {
                getLogger().warning("机器人 [" + username + "] 掉线，15秒后重连...");
                activeSessions.remove(client);
                scheduler.schedule(() -> startBot(username, host, port), 15, TimeUnit.SECONDS);
            }
        });

        client.connect();
        activeSessions.add(client);
        
        // 维持在线：每10秒微动
        scheduler.scheduleWithFixedDelay(() -> {
            if (client.isConnected()) {
                // 1.21.11 构造函数: (onGround, x, y, z)
                client.send(new ServerboundMovePlayerPosPacket(true, random.nextDouble()*0.01, 0.0, random.nextDouble()*0.01));
            }
        }, 10, 10, TimeUnit.SECONDS);
    }

    @Override
    public void onDisable() {
        activeSessions.forEach(s -> s.disconnect("Plugin Disabled"));
        scheduler.shutdownNow();
    }
}
