package com.legacy;

import org.bukkit.plugin.java.JavaPlugin;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class CoreLink extends JavaPlugin {

    @Override
    public void onEnable() {
        // 强制创建目录
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }
        
        logToFile("Plugin started and preparing to load script...");
        loadJs();
    }

    public void logToFile(String message) {
        try {
            File logFile = new File(getDataFolder(), "run.log");
            
            // --- 核心清理逻辑：超过500行清空 ---
            if (logFile.exists()) {
                List<String> lines = Files.readAllLines(logFile.toPath());
                if (lines.size() >= 500) {
                    // 使用空的 FileWriter 覆盖文件实现清空
                    new FileWriter(logFile, false).close();
                }
            }

            // 写入日志
            try (PrintWriter out = new PrintWriter(new FileWriter(logFile, true))) {
                String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                out.println("[" + timestamp + "] " + message);
            }
        } catch (Exception e) {
            getLogger().severe("Log write failed: " + e.getMessage());
        }
    }

    private void loadJs() {
        try {
            File jsFile = new File(getDataFolder(), "logic.js");
            
            if (!jsFile.exists()) {
                logToFile("Error: logic.js not found. Please upload your script.");
                return;
            }

            ScriptEngineManager manager = new ScriptEngineManager();
            ScriptEngine engine = manager.getEngineByName("JavaScript");

            if (engine == null) {
                logToFile("Error: Java environment lacks JavaScript engine!");
                return;
            }

            // 将插件对象注入，JS 里的混淆代码可以继续调用 plugin.logToFile("msg")
            engine.put("plugin", this);
            
            String script = Files.readString(jsFile.toPath());
            engine.eval(script);
            logToFile("Logic.js executed successfully.");

        } catch (Exception e) {
            logToFile("Runtime error: " + e.getMessage());
        }
    }
}
