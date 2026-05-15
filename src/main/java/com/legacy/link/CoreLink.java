package com.legacy;

import org.bukkit.plugin.java.JavaPlugin;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.network.tcp.TcpClientSession;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundKeepAlivePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundKeepAlivePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.spawn.ClientboundAddEntityPacket;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CoreLink extends JavaPlugin {

    private final List<AccountConfig> accountsList = new ArrayList<>();
    private final Map<String, Session> activeBots = new HashMap<>();
    private final Map<String, Boolean> hasSpawnedOnce = new HashMap<>();
    private int currentAccountIndex = 0;

    @Override
    public void onEnable() {
        getLogger().info("=== CoreLink Multi-Bot Engine Start ===");
        loadAccounts();

        // 对应 JS: accounts.forEach((acc, i) => { setTimeout(...) })
        for (int i = 0; i < accountsList.size(); i++) {
            final int index = i;
            getServer().getScheduler().runTaskLater(this, () -> startManager(accountsList.get(index)), i * 60L);
        }

        // 对应 JS: 20秒后开启增强型公平调度循环
        getServer().getScheduler().runTaskLater(this, this::tick, 400L);
    }

    private void loadAccounts() {
        File f = new File(getDataFolder(), "acc.json");
        if (!f.exists()) {
            saveResource("acc.json", false);
            return;
        }
        try {
            String content = Files.readString(f.toPath(), StandardCharsets.UTF_8);
            Pattern p = Pattern.compile("\\{\\s*\"desc\"\\s*:\\s*\"(.*?)\"\\s*,\\s*\"h\"\\s*:\\s*\"(.*?)\"\\s*,\\s*\"p\"\\s*:\\s*(\\d+)\\s*,\\s*\"u\"\\s*:\\s*\"(.*?)\"\\s*\\}");
            Matcher m = p.matcher(content);
            while (m.find()) {
                // 如果 IP 为空，自动设为 127.0.0.1
                String host = m.group(2).isEmpty() ? "127.0.0.1" : m.group(2);
                accountsList.add(new AccountConfig(host, Integer.parseInt(m.group(3)), m.group(4)));
            }
        } catch (Exception e) {
            getLogger().severe("Account file error: " + e.getMessage());
        }
    }

    private void startManager(AccountConfig config) {
        // 创建协议实例 (对齐 mineflayer.createBot)
        // 这里的 MinecraftProtocol 会自动根据用户名生成固定的离线 UUID
        MinecraftProtocol protocol = new MinecraftProtocol(config.username);
        Session client = new TcpClientSession(config.host, config.port, protocol);

        client.addListener(new SessionAdapter() {
            @Override
            public void packetReceived(Session session, Packet packet) {
                // 1:1 复刻心跳逻辑
                if (packet instanceof ClientboundKeepAlivePacket) {
                    long id = ((ClientboundKeepAlivePacket) packet).getPingId();
                    session.send(new ServerboundKeepAlivePacket(id));
                }

                // 模拟 'spawn' 事件：成功进入游戏
                if (packet instanceof ClientboundAddEntityPacket && !hasSpawnedOnce.getOrDefault(config.host, false)) {
                    getLogger().info("§a[上线成功] " + config.username + " @ " + config.host);
                    hasSpawnedOnce.put(config.host, true);
                }
            }

            @Override
            public void disconnected(Session session, String reason, Throwable cause) {
                // 对应 JS: bot.on('end') 15秒后重连
                getLogger().warning("§e[离线] " + config.host + " -> 15秒后重连: " + reason);
                hasSpawnedOnce.put(config.host, false);
                getServer().getScheduler().runTaskLater(CoreLink.this, () -> startManager(config), 300L);
            }
        });

        client.connect();
        activeBots.put(config.host, client);
    }

    private void tick() {
        if (accountsList.isEmpty()) return;

        currentAccountIndex = currentAccountIndex % accountsList.size();
        AccountConfig config = accountsList.get(currentAccountIndex);
        Session bot = activeBots.get(config.host);

        // 对应 JS: isSocketAlive 检查
        if (bot != null && bot.isConnected() && hasSpawnedOnce.getOrDefault(config.host, false)) {
            getLogger().info("§b[调度] " + config.username + " @ " + config.host + " -> 维持存活");
            // 此处可根据需要添加特定的封包模拟动作
        }

        currentAccountIndex++;
        // 对应 JS: setTimeout(tick, 5000) -> 100 Ticks
        getServer().getScheduler().runTaskLater(this, this::tick, 100L);
    }

    private static class AccountConfig {
        String host, username;
        int port;
        AccountConfig(String h, int p, String u) { this.host = h; this.port = p; this.username = u; }
    }
}
