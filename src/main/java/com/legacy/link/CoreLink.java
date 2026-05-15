package com.legacy;

import org.bukkit.plugin.java.JavaPlugin;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CoreLink extends JavaPlugin {

    private final List<Process> botProcesses = new ArrayList<>();

    @Override
    public void onEnable() {
        getLogger().info("=== CoreLink: 正在初始化 Mineflayer JS 环境 ===");
        
        // 1. 初始化文件
        setupJsEnvironment();
        
        // 2. 自动安装依赖
        if (installDependencies()) {
            // 3. 依赖就绪后加载账号
            loadAndStartBots();
        } else {
            getLogger().severe("JS 依赖安装失败，请检查是否安装了 Node.js 和 npm！");
        }
    }

    private void setupJsEnvironment() {
        if (!getDataFolder().exists()) getDataFolder().mkdirs();

        // 写入你提供的依赖配置到 package.json
        File pkgFile = new File(getDataFolder(), "package.json");
        String pkgContent = "{\n" +
                "  \"name\": \"corelink-bot\",\n" +
                "  \"version\": \"1.0.0\",\n" +
                "  \"description\": \"Multi-server Minecraft BOT with Auto-reconnect, PVP, and Random Walk features\",\n" +
                "  \"main\": \"bot_wrapper.js\",\n" +
                "  \"dependencies\": {\n" +
                "    \"mineflayer\": \"latest\",\n" +
                "    \"minecraft-data\": \"latest\",\n" +
                "    \"prismarine-auth\": \"latest\",\n" +
                "    \"mineflayer-pvp\": \"latest\",\n" +
                "    \"mineflayer-pathfinder\": \"latest\",\n" +
                "    \"mineflayer-armor-manager\": \"latest\",\n" +
                "    \"proxy-agent\": \"latest\"\n" +
                "  }\n" +
                "}";
        
        try {
            Files.writeString(pkgFile.toPath(), pkgContent, StandardCharsets.UTF_8);
            
            // 释放 JS 运行脚本
            File jsFile = new File(getDataFolder(), "bot_wrapper.js");
            if (!jsFile.exists()) saveResource("bot_wrapper.js", false);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private boolean installDependencies() {
        File nodeModules = new File(getDataFolder(), "node_modules");
        if (nodeModules.exists()) return true;

        getLogger().info("正在安装 JS 依赖 (mineflayer, pvp, pathfinder...)，请稍后...");
        try {
            // 根据系统判断命令前缀 (Windows使用npm.cmd)
            String npm = System.getProperty("os.name").toLowerCase().contains("win") ? "npm.cmd" : "npm";
            ProcessBuilder pb = new ProcessBuilder(npm, "install");
            pb.directory(getDataFolder());
            pb.inheritIO();
            Process p = pb.start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void loadAndStartBots() {
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
                startJsProcess(m.group(1), m.group(2).isEmpty() ? "127.0.0.1" : m.group(2), m.group(3), m.group(4));
            }
        } catch (Exception e) {
            getLogger().severe("账号加载异常: " + e.getMessage());
        }
    }

    private void startJsProcess(String desc, String host, String port, String user) {
        new Thread(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder("node", "bot_wrapper.js", host, port, user, desc);
                pb.directory(getDataFolder());
                pb.inheritIO();
                
                Process process = pb.start();
                botProcesses.add(process);
                
                int exitCode = process.waitFor();
                if (exitCode != 0) {
                    getLogger().warning("Bot [" + desc + "] 已退出，15秒后重启...");
                    Thread.sleep(15000);
                    startJsProcess(desc, host, port, user);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    @Override
    public void onDisable() {
        for (Process p : botProcesses) {
            if (p != null && p.isAlive()) p.destroy();
        }
    }
}
