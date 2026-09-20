package com.pingroom;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Wejście moda (tylko klient - działa na dowolnym serwerze, także vanilla). */
public class PingRoomClient implements ClientModInitializer {
    public static final String MOD_ID = "pingroom";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private KeyMapping pingKey;
    private KeyMapping dangerKey;

    @Override
    public void onInitializeClient() {
        Config.load();

        // --- klawisze (do zmiany w Sterowanie -> Ping Room) ---
        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
        pingKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.pingroom.ping", InputConstants.Type.KEYSYM, InputConstants.KEY_V, category));
        dangerKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.pingroom.danger", InputConstants.Type.KEYSYM, InputConstants.KEY_X, category));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (pingKey.consumeClick()) RoomManager.sendPing(client, RoomManager.Kind.HERE);
            while (dangerKey.consumeClick()) RoomManager.sendPing(client, RoomManager.Kind.DANGER);
            RoomManager.tick(client);
        });

        // --- rysowanie pingów na HUD ---
        HudElementRegistry.attachElementBefore(
                VanillaHudElements.CHAT, Identifier.fromNamespaceAndPath(MOD_ID, "pings"), PingHud::render);

        // --- komendy klienckie (/pingroom ...) - nie trafiają na serwer ---
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> dispatcher.register(
                ClientCommands.literal("pingroom")
                        .executes(ctx -> {
                            RoomManager.help();
                            return 1;
                        })
                        .then(ClientCommands.literal("create").executes(ctx -> {
                            RoomManager.create(Minecraft.getInstance());
                            return 1;
                        }))
                        .then(ClientCommands.literal("join")
                                .then(ClientCommands.argument("code", StringArgumentType.word()).executes(ctx -> {
                                    RoomManager.join(Minecraft.getInstance(), StringArgumentType.getString(ctx, "code"));
                                    return 1;
                                })))
                        .then(ClientCommands.literal("leave").executes(ctx -> {
                            RoomManager.leave(true);
                            return 1;
                        }))
                        .then(ClientCommands.literal("info").executes(ctx -> {
                            RoomManager.info();
                            return 1;
                        }))
                        .then(ClientCommands.literal("relay")
                                .then(ClientCommands.argument("url", StringArgumentType.greedyString()).executes(ctx -> {
                                    RoomManager.setRelay(StringArgumentType.getString(ctx, "url"));
                                    return 1;
                                })))));

        LOGGER.info("Ping Room załadowany. Adres relaya: {}", Config.relayUrl());
    }
}
