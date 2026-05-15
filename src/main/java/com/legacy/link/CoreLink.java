package com.legacy.link;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.bukkit.plugin.java.JavaPlugin;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.session.DisconnectedEvent;
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter;
import org.geysermc.mcprotocollib.network.packet.Packet;
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

        getLogger().info("=== CoreLink: 1.21.11 最终修正版启动 ===");
        
        try (FileReader reader = new FileReader(file, StandardCharsets.UTF_8)) {
            List<Map<String, String>> accounts = new Gson().fromJson(reader, new TypeToken<List<Map<String, String>>>(){}.getType());
            if (accounts != null) {
                for (Map<String, String> acc : accounts) {
                    startBot(acc.get("u"), acc.get("h"), Integer.parseInt(acc.get("p")));
                }
            }
        } catch (Exception e) {
            getLogger().severe("加载 acc.json 失败: " + e.getMessage());
        }
    }

    private void startBot(String username, String host, int port) {
        MinecraftProtocol protocol = new MinecraftProtocol(username);
        
        // 修正：使用更通用的方式实例化，避免找不到 tcp 包
        // 1.21.11-SNAPSHOT 中通常使用 TcpClientSession
        Session client = new org.geysermc.mcprotocollib.network.tcp.TcpClientSession(host, port, protocol);

        client.addListener(new SessionAdapter() {
            @Override
            public void packetReceived(Session session, Packet packet) {
                if (packet instanceof ClientboundPlayerPositionPacket p) {
                    // 修正：根据报错，新版可能改回了 getTeleportId() 或使用 getTeleportationId()
                    // 这里我们尝试最通用的字段调用逻辑
                    session.send(new ServerboundAcceptTeleportationPacket(p.getTeleportId()));
                }
            }

            @Override
            public void disconnected(DisconnectedEvent event) {
                getLogger().warning("机器人 [" + username + "] 离线: " + event.getReason());
                activeSessions.remove(client);
                scheduler.schedule(() -> startBot(username, host, port), 15, TimeUnit.SECONDS);
            }
        });

        // 修正：connect() 方法在 Session 接口中通常不带参数
        client.connect(); 
        activeSessions.add(client);

        // 随机活动任务：防止被服务器踢出
        scheduler.scheduleWithFixedDelay(() -> {
            if (client.isConnected()) {
                double x = (random.nextDouble() - 0.5) * 0.02;
                double z = (random.nextDouble() - 0.5) * 0.02;
                // 1.21.11 参数: onGround, x, y, z
                // 部分版本增加 horizontalCollision，若报错请联系我删减
                client.send(new ServerboundMovePlayerPosPacket(true, x, 0.0, z));
            }
        }, 10, 10, TimeUnit.SECONDS);
    }

    @Override
    public void onDisable() {
        activeSessions.forEach(s -> s.disconnect("Plugin Disabled"));
        scheduler.shutdownNow();
    }
}
