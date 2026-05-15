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
        getLogger().info("=== CoreLink: 正在初始化 1.21.11 JS 协议环境 ===");
        
        setupJsEnvironment();
        
        if (installDependencies()) {
            loadAndStartBots();
        } else {
            getLogger().severe("JS 环境初始化失败！请检查 Node.js/npm 是否安装。");
        }
    }

    private void setupJsEnvironment() {
        if (!getDataFolder().exists()) getDataFolder().mkdirs();

        // 写入 1:1 对齐你需求的 package.json
        File pkgFile = new File(getDataFolder(), "package.json");
        String pkgContent = "{\n" +
                "  \"name\": \"corelink-bot\",\n" +
                "  \"version\": \"1.0.0\",\n" +
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
            File jsFile = new File(getDataFolder(), "bot_wrapper.js");
            if (!jsFile.exists()) saveResource("bot_wrapper.js", false);
        } catch (IOException e) {
            getLogger().severe("文件写入失败: " + e.getMessage());
        }
    }

    private boolean installDependencies() {
        File nodeModules = new File(getDataFolder(), "node_modules");
        if (nodeModules.exists()) return true;

        getLogger().info("正在为 1.21.11 版本安装核心库，这可能需要一点时间...");
        try {
            String npm = System.getProperty("os.name").toLowerCase().contains("win") ? "npm.cmd" : "npm";
            ProcessBuilder pb = new ProcessBuilder(npm, "install");
            pb.directory(getDataFolder());
            pb.inheritIO();
            return pb.start().waitFor() == 0;
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
            getLogger().severe("账号解析出错: " + e.getMessage());
        }
    }

    private void startJsProcess(String desc, String host, String port, String user) {
        new Thread(() -> {
            try {
                // 传入 1.21.11 作为强制版本参数
                ProcessBuilder pb = new ProcessBuilder("node", "bot_wrapper.js", host, port, user, desc, "1.21.11");
                pb.directory(getDataFolder());
                pb.inheritIO();
                
                Process process = pb.start();
                botProcesses.add(process);
                
                if (process.waitFor() != 0) {
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
