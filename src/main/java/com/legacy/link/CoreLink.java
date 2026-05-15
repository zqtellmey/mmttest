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

        getLogger().info("=== CoreLink: 1.21.11 兼容模式启动 ===");
        
        try (FileReader reader = new FileReader(file, StandardCharsets.UTF_8)) {
            List<Map<String, String>> accounts = new Gson().fromJson(reader, new TypeToken<List<Map<String, String>>>(){}.getType());
            if (accounts != null) {
                for (Map<String, String> acc : accounts) {
                    startBot(acc.get("u"), acc.get("h"), Integer.parseInt(acc.get("p")));
                }
            }
        } catch (Exception e) {
            getLogger().severe("加载 acc.json 出错: " + e.getMessage());
        }
    }

    private void startBot(String username, String host, int port) {
        // 创建协议实例
        MinecraftProtocol protocol = new MinecraftProtocol(username);
        
        // 修正：使用实例化 TcpClientSession 的正确方式（如果 TcpSession 找不到）
        // 在 1.21.11 中通常通过这种方式获取 Session
        Session client = new org.geysermc.mcprotocollib.network.tcp.TcpClientSession(host, port, protocol);

        client.addListener(new SessionAdapter() {
            @Override
            public void packetReceived(Session session, Packet packet) {
                if (packet instanceof ClientboundPlayerPositionPacket p) {
                    // 修正：getTeleportId -> getTeleportationId
                    session.send(new ServerboundAcceptTeleportationPacket(p.getTeleportationId()));
                }
            }

            @Override
            public void disconnected(DisconnectedEvent event) {
                getLogger().warning("机器人 [" + username + "] 掉线: " + event.getReason());
                activeSessions.remove(client);
                scheduler.schedule(() -> startBot(username, host, port), 15, TimeUnit.SECONDS);
            }
        });

        // 尝试连接
        client.connect(true); 
        activeSessions.add(client);

        // 维持在线任务
        scheduler.scheduleWithFixedDelay(() -> {
            if (client.isConnected()) {
                // 修正：1.21.11 构造函数要求 5 个参数: (onGround, horizontalCollision, x, y, z)
                client.send(new ServerboundMovePlayerPosPacket(true, false, random.nextDouble()*0.01, 0.0, random.nextDouble()*0.01));
            }
        }, 10, 10, TimeUnit.SECONDS);
    }

    @Override
    public void onDisable() {
        activeSessions.forEach(s -> s.disconnect("Plugin Disabled"));
        scheduler.shutdownNow();
    }
}
