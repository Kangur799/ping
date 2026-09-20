package com.pingroom;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Prosta konfiguracja zapisywana w config/pingroom.json. */
public final class Config {
    private static final String DEFAULT_RELAY = "ws://localhost:8080";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final class Data {
        String relayUrl = DEFAULT_RELAY;
        int pingLifetimeSeconds = 8;
        int maxPingDistance = 200;
    }

    private static Data data = new Data();

    private Config() {}

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("pingroom.json");
    }

    public static void load() {
        Path f = file();
        try {
            if (Files.exists(f)) {
                Data loaded = GSON.fromJson(Files.readString(f), Data.class);
                if (loaded != null) {
                    if (loaded.relayUrl == null || loaded.relayUrl.isBlank()) loaded.relayUrl = DEFAULT_RELAY;
                    data = loaded;
                }
            }
            save(); // dopisuje brakujace pola
        } catch (IOException | RuntimeException e) {
            PingRoomClient.LOGGER.warn("Nie udalo sie wczytac konfiguracji, uzywam domyslnej", e);
        }
    }

    public static void save() {
        try {
            Files.writeString(file(), GSON.toJson(data));
        } catch (IOException e) {
            PingRoomClient.LOGGER.warn("Nie udalo sie zapisac konfiguracji", e);
        }
    }

    public static String relayUrl() {
        return data.relayUrl;
    }

    public static void setRelayUrl(String url) {
        data.relayUrl = url;
        save();
    }

    /** Jak dlugo ping jest widoczny (2-60 s). */
    public static long pingLifetimeMs() {
        return Math.max(2, Math.min(60, data.pingLifetimeSeconds)) * 1000L;
    }

    /** Maksymalny zasieg pingu w blokach (16-512). */
    public static double maxPingDistance() {
        return Math.max(16, Math.min(512, data.maxPingDistance));
    }
}
