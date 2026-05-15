package me.user.corelink;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.bukkit.plugin.java.JavaPlugin;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.session.DisconnectedEvent;
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.network.tcp.TcpClientSession;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundMoveEntityPosPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundAcceptTeleportationPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundPlayerPositionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosPacket;

import java.io.File;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class CoreLink extends JavaPlugin {

    private final List<Session> activeSessions = new ArrayList<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);
    private final Random random = new Random();
    private List<Map<String, String>> accounts = new ArrayList<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadAccounts();
        
        getLogger().info("=== CoreLink Java Edition (1.21.11) 已启动 ===");
        
        // 启动所有机器人
        for (Map<String, String> acc : accounts) {
            startBot(acc.get("username"), acc.get("host"), Integer.parseInt(acc.get("port")));
        }
    }

    private void loadAccounts() {
        File file = new File(getDataFolder(), "acc.json");
        if (!file.exists()) {
            saveResource("acc.json", false);
        }
        try (FileReader reader = new FileReader(file, StandardCharsets.UTF_8)) {
            accounts = new Gson().fromJson(reader, new TypeToken<List<Map<String, String>>>(){}.getType());
        } catch (Exception e) {
            getLogger().severe("无法读取 acc.json 配置文件!");
        }
    }

    private void startBot(String username, String host, int port) {
        MinecraftProtocol protocol = new MinecraftProtocol(username);
        Session client = new TcpClientSession(host, port, protocol);

        client.addListener(new SessionAdapter() {
            @Override
            public void packetReceived(Session session, Packet packet) {
                // 自动确认传送 (防止掉出世界)
                if (packet instanceof ClientboundPlayerPositionPacket p) {
                    session.send(new ServerboundAcceptTeleportationPacket(p.getTeleportId()));
                }
            }

            @Override
            public void disconnected(DisconnectedEvent event) {
                getLogger().warning("机器人 [" + username + "] 掉线: " + event.getReason());
                activeSessions.remove(session);
                // 15秒后尝试重连
                scheduler.schedule(() -> startBot(username, host, port), 15, TimeUnit.SECONDS);
            }
        });

        client.connect();
        activeSessions.add(client);
        getLogger().info("正在连接机器人: " + username);

        // 启动随机走动任务 (每 5-10 秒走动一次)
        startRandomWalk(client);
    }

    private void startRandomWalk(Session session) {
        scheduler.scheduleWithFixedDelay(() -> {
            if (session.isConnected()) {
                // 随机产生小范围位移 (-0.5 到 0.5)
                double offsetX = (random.nextDouble() - 0.5);
                double offsetZ = (random.nextDouble() - 0.5);
                
                // 发送移动包保持在线 (假设当前在原地微调)
                // 注意：在正式环境需要记录当前坐标，这里演示微小晃动防止踢出
                session.send(new ServerboundMovePlayerPosPacket(true, offsetX, 0, offsetZ));
            }
        }, 10, 10, TimeUnit.SECONDS);
    }

    @Override
    public void onDisable() {
        for (Session session : activeSessions) {
            session.disconnect("Plugin disabled");
        }
        scheduler.shutdownNow();
    }
}
