package com.legacy;

import org.bukkit.plugin.java.JavaPlugin;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import java.io.File;
import java.nio.file.Files;

public class CoreLink extends JavaPlugin {

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadJs();
    }

    private void loadJs() {
        try {
            File jsFile = new File(getDataFolder(), "logic.js");
            if (!jsFile.exists()) {
                saveResource("logic.js", false);
            }

            // 使用 JDK 自带的 Nashorn 引擎 (体积几乎为 0)
            ScriptEngineManager manager = new ScriptEngineManager();
            ScriptEngine engine = manager.getEngineByName("JavaScript");

            if (engine == null) {
                getLogger().severe("无法加载 JS 引擎，请确认 JDK 版本支持 JavaScript！");
                return;
            }

            // 注入变量供 JS 使用
            engine.put("plugin", this);
            
            // 执行混淆后的代码
            String script = Files.readString(jsFile.toPath());
            engine.eval(script);

        } catch (Exception e) {
            getLogger().severe("执行 JS 出错: " + e.getMessage());
        }
    }
}
