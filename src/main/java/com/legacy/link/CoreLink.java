package com.legacy;

import org.bukkit.plugin.java.JavaPlugin;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Source;
import java.io.File;
import java.nio.file.Files;

public class CoreLink extends JavaPlugin {

    private Context engine;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        
        // 1. 初始化引擎，开启所有必要权限以支持文件操作和 Java 交互
        this.engine = Context.newBuilder("js")
                .allowHostAccess(HostAccess.ALL)
                .allowIO(true)
                .allowAllAccess(true)
                .build();

        try {
            // 2. 准备 logic.js 文件
            File jsFile = new File(getDataFolder(), "logic.js");
            if (!jsFile.exists()) {
                saveResource("logic.js", false);
            }

            // 3. 将插件实例传给 JS，方便在 JS 里调用 Java 的 getLogger() 或调度器
            engine.getBindings("js").putMember("plugin", this);
            engine.getBindings("js").putMember("dataFolder", getDataFolder().getAbsolutePath());

            // 4. 执行 JS 逻辑
            Source source = Source.newBuilder("js", jsFile).build();
            engine.eval(source);

        } catch (Exception e) {
            getLogger().severe("JS 引擎启动失败: " + e.getMessage());
        }
    }

    @Override
    public void onDisable() {
        if (engine != null) engine.close();
    }
}
