package com.legacy;

import org.bukkit.plugin.java.JavaPlugin;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Source;
import java.io.File;

public class CoreLink extends JavaPlugin {
    private Context engine;

    @Override
    public void onEnable() {
        // 初始化环境
        this.engine = Context.newBuilder("js")
                .allowHostAccess(HostAccess.ALL)
                .allowIO(true)
                .allowAllAccess(true)
                .build();

        try {
            File jsFile = new File(getDataFolder(), "logic.js");
            if (!jsFile.exists()) {
                saveResource("logic.js", false);
            }
            // 注入插件对象
            engine.getBindings("js").putMember("plugin", this);
            Source source = Source.newBuilder("js", jsFile).build();
            engine.eval(source);
        } catch (Exception e) {
            getLogger().severe("Load Failed: " + e.getMessage());
        }
    }

    @Override
    public void onDisable() {
        if (engine != null) engine.close();
    }
}
