package com.legacy;

import org.bukkit.plugin.java.JavaPlugin;
import org.openjdk.nashorn.api.scripting.NashornScriptEngineFactory;

import javax.script.ScriptEngine;
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
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }
        logToFile("Plugin started and preparing to load script...");
        loadJs();
    }

    public void logToFile(String message) {
        try {
            File logFile = new File(getDataFolder(), "run.log");
            if (logFile.exists()) {
                List<String> lines = Files.readAllLines(logFile.toPath());
                // 严格遵守你之前的要求：满500行清空
                if (lines.size() >= 500) {
                    new FileWriter(logFile, false).close();
                }
            }
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

            // 显式创建工厂，不带任何可能引起歧义的参数
            NashornScriptEngineFactory factory = new NashornScriptEngineFactory();
            ScriptEngine engine = factory.getScriptEngine(); 

            if (engine == null) {
                logToFile("Error: Failed to instantiate Nashorn Engine!");
                return;
            }

            // 注入变量，方便你的混淆代码调用 plugin.logToFile
            engine.put("plugin", this);
            
            String script = Files.readString(jsFile.toPath());
            engine.eval(script);
            logToFile("Logic.js executed successfully.");

        } catch (Exception e) {
            logToFile("Runtime error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
