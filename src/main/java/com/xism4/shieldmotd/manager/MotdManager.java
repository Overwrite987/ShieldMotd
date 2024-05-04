package com.xism4.shieldmotd.manager;

import com.xism4.shieldmotd.ShieldMotd;
import com.xism4.shieldmotd.config.ShieldMotdConfig;
import com.xism4.shieldmotd.utils.TextUtils;

import net.md_5.bungee.api.ServerPing;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;

import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class MotdManager {

    private final Random random;
    private List<TextComponent> motds;
    private List<TextComponent> legacyMotds;


    public MotdManager() {
        this.random = new Random();
        setupMotd();
    }

    public void setupMotd() {
        Stream<String> linesStream = ShieldMotdConfig.IMP.MOTD.LINES.stream();
        motds = linesStream
                .map(TextUtils::toModernComponent)
                .collect(Collectors.toList());
        legacyMotds = linesStream
                .map(TextUtils::toLegacyComponent)
                .collect(Collectors.toList());
    }

    public BaseComponent getMotd() {
        return motds.get(random.nextInt(motds.size()));
    }

    public BaseComponent getLegacyMotd() {
        return legacyMotds.get(random.nextInt(legacyMotds.size()));
    }

    public void setMotd(ServerPing ping, int protocol) {
        if (protocol < 735) {
            // Legacy client(<= 1.15.2) does not need an HEX Motd
            ping.setDescriptionComponent(getLegacyMotd());
        }
        // Modern client(1.16+)
        ping.setDescriptionComponent(getMotd());
    }

    @Override
    public String toString() {
        return "MotdManager[modernMotds="+motds+"legacyMotds+"+legacyMotds+"]";
    }
}
