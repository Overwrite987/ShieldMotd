package com.xism4.shieldmotd;

import com.xism4.shieldmotd.command.ShieldMotdCommand;
import com.xism4.shieldmotd.config.ShieldMotdConfig;
import com.xism4.shieldmotd.listeners.MotdListener;
import com.xism4.shieldmotd.manager.MotdManager;

import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.api.plugin.PluginManager;

import java.util.logging.Level;

public final class ShieldMotd extends Plugin {

    private MotdManager motdManager;
    private ProxyServer proxyServer;

    @Override
    public void onEnable() {
        ShieldMotdConfig.IMP.reload();

        if(proxyServer.getName().contains("NullCordX")) {
            this.getLogger().log(
                    Level.WARNING,
                    "NullCordX is detected and is highly recommend to use native-built feature"
            );
        }

        this.motdManager = new MotdManager();

        PluginManager pluginManager = getProxy().getPluginManager();

        pluginManager.registerCommand(
                this, new ShieldMotdCommand(this)
        );

        pluginManager.registerListener(
                this, new MotdListener(this)
        );
    }

    @Override
    public void onDisable() {
        getLogger().info("ShieldMotd has been disabled, thanks for using it");
    }

    public MotdManager getMotdManager() {
        return this.motdManager;
    }
}
