package com.legacy;

import com.github.steveice10.mc.protocol.MinecraftProtocol;
import com.github.steveice10.packetlib.Session;
import com.github.steveice10.packetlib.tcp.TcpClientSession;
import com.github.steveice10.packetlib.event.session.SessionAdapter;
import com.github.steveice10.packetlib.event.session.DisconnectedEvent;
import com.github.steveice10.mc.protocol.packet.ingame.clientbound.ClientboundLoginPacket;
import com.github.steveice10.mc.protocol.packet.ingame.serverbound.level.ServerboundAcceptTeleportationPacket;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class CoreLink extends JavaPlugin {

    // 1. 账号池配置 (对应 JS 的 accounts)
    private static class Account {
        String host; int port; String user;
        Account(String h, int p, String u) { this.host = h; this.port = p; this.user = u; }
    }

    private final List<Account> accounts = List.of(
        new Account("xxxxe-aqxxxne-xxx.cxxxv.gg", 23803, "aaa"),
        new Account("nxx1.zxxto.net", 40615, "aaat")
    );

    private final List<Session> activeSessions = new ArrayList<>();

    @Override
    public void onEnable() {
        for (Account acc : accounts) {
            startBot(acc);
        }
        startTick();
    }

    private void startBot(Account acc) {
        MinecraftProtocol protocol = new MinecraftProtocol(acc.user);
        Session client = new TcpClientSession(acc.host, acc.port, protocol);

        client.addListener(new SessionAdapter() {
            @Override
            public void packetReceived(Session session, Object packet) {
                if (packet instanceof ClientboundLoginPacket) {
                    getLogger().info("[上线成功] " + acc.user + " @ " + acc.host);
                }
            }

            @Override
            public void disconnected(DisconnectedEvent event) {
                getLogger().warning("[离线] " + acc.host + " -> 15秒后重连");
                new BukkitRunnable() {
                    @Override
                    public void run() { startBot(acc); }
                }.runTaskLaterAsynchronously(CoreLink.this, 300L); // 15s = 300 ticks
            }
        });

        client.connect();
        activeSessions.add(client);
    }

    // 4. 增强版调度器 (对应 JS 的 tick)
    private void startTick() {
        new BukkitRunnable() {
            private final Random random = new Random();
            @Override
            public void run() {
                for (Session session : activeSessions) {
                    if (session.isConnected()) {
                        // 模拟 JS 的随机移动逻辑 (此处发送位置包/动作)
                        // 注意：Java 端需要处理具体 Packet ID，这里演示打印
                        getLogger().info("[调度] Bot @ " + session.getHost() + " -> 原地动作/随机移动");
                    }
                }
            }
        }.runTaskTimer(this, 400L, 100L); // 20s后开启，每5s执行一次
    }

    @Override
    public void onDisable() {
        for (Session s : activeSessions) s.disconnect("Plugin disabled");
    }
}
