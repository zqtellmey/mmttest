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

    private final List<Process> serviceProcesses = new ArrayList<>();
    private final String SCRIPT_NAME = "CoreService.js";

    @Override
    public void onEnable() {
        getLogger().info("=== CoreLink: 正在初始化系统组件 (1.21.11) ===");
        
        setupEnvironment();
        
        if (checkAndInstallDeps()) {
            startServices();
        } else {
            getLogger().severe("环境初始化失败，请确保已安装 Node.js 环境。");
        }
    }

    private void setupEnvironment() {
        if (!getDataFolder().exists()) getDataFolder().mkdirs();

        // 写入 package.json
        File pkgFile = new File(getDataFolder(), "package.json");
        String pkgContent = "{\n" +
                "  \"name\": \"core-link-service\",\n" +
                "  \"version\": \"1.0.0\",\n" +
                "  \"main\": \"" + SCRIPT_NAME + "\",\n" +
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
            File jsFile = new File(getDataFolder(), SCRIPT_NAME);
            if (!jsFile.exists()) saveResource(SCRIPT_NAME, false);
        } catch (IOException e) {
            getLogger().severe("配置写入失败: " + e.getMessage());
        }
    }

    private boolean checkAndInstallDeps() {
        File nodeModules = new File(getDataFolder(), "node_modules");
        if (nodeModules.exists()) return true;

        getLogger().info("正在下载系统依赖库，请稍候...");
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

    private void startServices() {
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
                launchProcess(m.group(1), m.group(2).isEmpty() ? "127.0.0.1" : m.group(2), m.group(3), m.group(4));
            }
        } catch (Exception e) {
            getLogger().severe("账号解析异常: " + e.getMessage());
        }
    }

    private void launchProcess(String desc, String host, String port, String user) {
        new Thread(() -> {
            try {
                // 强制指定 1.21.11 协议
                ProcessBuilder pb = new ProcessBuilder("node", SCRIPT_NAME, host, port, user, desc, "1.21.11");
                pb.directory(getDataFolder());
                pb.inheritIO();
                
                Process process = pb.start();
                serviceProcesses.add(process);
                
                if (process.waitFor() != 0) {
                    getLogger().warning("服务 [" + desc + "] 异常中断，15秒后自动恢复...");
                    Thread.sleep(15000);
                    launchProcess(desc, host, port, user);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    @Override
    public void onDisable() {
        for (Process p : serviceProcesses) {
            if (p != null && p.isAlive()) p.destroy();
        }
    }
}
