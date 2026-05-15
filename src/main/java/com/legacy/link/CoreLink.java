package com.legacy;

import com.github.steveice10.mc.protocol.MinecraftProtocol;
import com.github.steveice10.mc.protocol.packet.ingame.clientbound.ClientboundLoginPacket;
import com.github.steveice10.packetlib.Session;
import com.github.steveice10.packetlib.event.session.DisconnectedEvent;
import com.github.steveice10.packetlib.event.session.SessionAdapter;
import com.github.steveice10.packetlib.tcp.TcpClientSession;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;

public class CoreLink extends JavaPlugin {

    private static class Entry {
        String host;
        int port;
        String user;
    }

    private final List<Session> activeList = new CopyOnWriteArrayList<>();
    private final Gson parser = new Gson();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadData();
        startCycle();
    }

    private void loadData() {
        File file = new File(getDataFolder(), "acc.json");
        if (!file.exists()) return;

        try (FileReader reader = new FileReader(file)) {
            Type type = new TypeToken<ArrayList<Entry>>() {}.getType();
            List<Entry> entries = parser.fromJson(reader, type);

            if (entries != null) {
                for (Entry entry : entries) {
                    initLink(entry);
                }
            }
        } catch (IOException e) {
            getLogger().severe("Load failed: " + e.getMessage());
        }
    }

    private void initLink(Entry entry) {
        // 密码固定
        MinecraftProtocol sub = new MinecraftProtocol(entry.user);
        Session session = new TcpClientSession(entry.host, entry.port, sub);

        session.addListener(new SessionAdapter() {
            @Override
            public void packetReceived(Session s, Object p) {
                if (p instanceof ClientboundLoginPacket) {
                    getLogger().info("§a[Link Success] " + entry.user);
                }
            }

            @Override
            public void disconnected(DisconnectedEvent e) {
                getLogger().warning("§e[Link End] " + entry.host + " -> Retry in 15s");
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        if (isEnabled()) initLink(entry);
                    }
                }.runTaskLaterAsynchronously(CoreLink.this, 300L);
            }
        });

        session.connect();
        activeList.add(session);
    }

    private void startCycle() {
        new BukkitRunnable() {
            private final Random rand = new Random();
            @Override
            public void run() {
                for (Session s : activeList) {
                    if (s.isConnected()) {
                        // 随机动作逻辑
                        getLogger().info("§b[Action] " + s.getHost());
                    }
                }
            }
        }.runTaskTimer(this, 400L, 100L);
    }

    @Override
    public void onDisable() {
        for (Session s : activeList) s.disconnect("Shutdown");
    }
}
