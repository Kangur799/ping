package com.pingroom;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Stan pokoju + logika pingow. Wszystko tu dzieje sie na watku gry
 * (wiadomosci z sieci sa przenoszone przez Minecraft#execute).
 */
public final class RoomManager {

    public enum Kind { HERE, DANGER }

    public record Ping(String owner, Vec3 pos, String dim, Kind kind, long createdMs, boolean mine) {}

    private static final int MAX_PINGS_PER_OWNER = 3;
    private static final long MIN_PING_INTERVAL_MS = 400;

    private static RelayClient relay;
    private static JsonObject pendingRequest;
    private static String pendingServer = "";

    private static String code;         // null = nie jestem w pokoju
    private static String roomServer = "";
    private static String myName = "";
    private static final Set<String> members = new LinkedHashSet<>();
    private static final List<Ping> pings = new ArrayList<>();
    private static long lastPingSentMs;
    private static long noWorldSinceMs;

    private RoomManager() {}

    private static final RelayClient.Handler HANDLER = new RelayClient.Handler() {
        @Override
        public void onOpen(RelayClient c) {
            Minecraft.getInstance().execute(() -> {
                if (c == relay && pendingRequest != null) {
                    c.send(pendingRequest);
                    pendingRequest = null;
                }
            });
        }

        @Override
        public void onMessage(RelayClient c, JsonObject m) {
            Minecraft.getInstance().execute(() -> {
                if (c == relay) handle(m);
            });
        }

        @Override
        public void onClosed(RelayClient c, String reason) {
            Minecraft.getInstance().execute(() -> {
                if (c == relay) relayClosed(reason);
            });
        }
    };

    // ------------------------------------------------------------------ komendy

    public static void create(Minecraft mc) {
        if (!checkReady(mc)) return;
        JsonObject req = new JsonObject();
        req.addProperty("t", "create");
        req.addProperty("server", serverKey(mc));
        req.addProperty("name", playerName(mc));
        request(req);
    }

    public static void join(Minecraft mc, String rawCode) {
        if (!checkReady(mc)) return;
        JsonObject req = new JsonObject();
        req.addProperty("t", "join");
        req.addProperty("code", rawCode.trim().toUpperCase(Locale.ROOT));
        req.addProperty("server", serverKey(mc));
        req.addProperty("name", playerName(mc));
        request(req);
    }

    public static void leave(boolean announce) {
        boolean wasActive = code != null || relay != null;
        if (relay != null) {
            JsonObject bye = new JsonObject();
            bye.addProperty("t", "leave");
            relay.send(bye);
            relay.close();
            relay = null;
        }
        pendingRequest = null;
        clearRoom();
        if (announce) msg(wasActive ? "§7Opuściłeś pokój." : "§7Nie jesteś w żadnym pokoju.");
    }

    public static void info() {
        if (code == null) {
            msg("§7Nie jesteś w żadnym pokoju. §f/pingroom create §7albo §f/pingroom join <kod>");
        } else {
            msg("§aPokój: §e" + code + " §7(serwer: " + roomServer + ")");
            msg("§7Członkowie (" + members.size() + "): §f" + String.join(", ", members));
        }
        msg("§7Relay: §f" + Config.relayUrl());
    }

    public static void help() {
        msg("§f/pingroom create §7- utwórz pokój i dostań kod");
        msg("§f/pingroom join <kod> §7- dołącz do pokoju znajomego (ten sam serwer!)");
        msg("§f/pingroom leave §7- wyjdź z pokoju");
        msg("§f/pingroom info §7- kod i lista członków");
        msg("§f/pingroom relay <adres> §7- ustaw adres relaya (ws:// lub wss://)");
        msg("§7Pingi: klawisze z menu Sterowanie → Ping Room (domyślnie §fV§7 i §fX§7).");
    }

    public static void setRelay(String url) {
        String u = url.trim();
        if (!(u.startsWith("ws://") || u.startsWith("wss://"))) {
            msg("§cAdres musi zaczynać się od ws:// albo wss://");
            return;
        }
        Config.setRelayUrl(u);
        msg("§aUstawiono relay: §f" + u);
    }

    // ------------------------------------------------------------------ pingi

    public static void sendPing(Minecraft mc, Kind kind) {
        if (mc.player == null || mc.level == null) return;
        if (code == null || relay == null || !relay.isOpen()) {
            msg("§cNie jesteś w żadnym pokoju. Użyj §f/pingroom create§c albo §f/pingroom join <kod>§c.");
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastPingSentMs < MIN_PING_INTERVAL_MS) return;

        HitResult hit = mc.player.pick(Config.maxPingDistance(), 1.0F, false);
        if (hit.getType() == HitResult.Type.MISS) {
            msg("§7Nie ma w co pingować (za daleko albo niebo).");
            return;
        }
        lastPingSentMs = now;
        Vec3 pos = hit.getLocation();
        String dim = dimKey(mc);

        addPing(new Ping(myName, pos, dim, kind, now, true));
        playSound(kind);

        JsonObject o = new JsonObject();
        o.addProperty("t", "ping");
        o.addProperty("x", pos.x);
        o.addProperty("y", pos.y);
        o.addProperty("z", pos.z);
        o.addProperty("dim", dim);
        o.addProperty("kind", kind == Kind.DANGER ? "danger" : "here");
        relay.send(o);
    }

    public static List<Ping> activePings() {
        return Collections.unmodifiableList(pings);
    }

    private static void addPing(Ping p) {
        int count = 0;
        for (Ping o : pings) if (o.owner().equals(p.owner())) count++;
        while (count >= MAX_PINGS_PER_OWNER) {
            for (int i = 0; i < pings.size(); i++) {
                if (pings.get(i).owner().equals(p.owner())) {
                    pings.remove(i);
                    count--;
                    break;
                }
            }
        }
        pings.add(p);
    }

    private static void onRemotePing(JsonObject m) {
        String from = str(m, "from");
        String dim = str(m, "dim");
        String kindStr = str(m, "kind");
        if (from == null || dim == null || kindStr == null) return;
        double x, y, z;
        try {
            x = m.get("x").getAsDouble();
            y = m.get("y").getAsDouble();
            z = m.get("z").getAsDouble();
        } catch (RuntimeException e) {
            return;
        }
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) return;
        Kind kind = "danger".equals(kindStr) ? Kind.DANGER : Kind.HERE;
        addPing(new Ping(from, new Vec3(x, y, z), dim, kind, System.currentTimeMillis(), false));
        playSound(kind);
    }

    // ------------------------------------------------------------------ tick

    /** Wolane co tick klienta: wygasza pingi i pilnuje, czy nadal jesteśmy na tym samym serwerze. */
    public static void tick(Minecraft mc) {
        long now = System.currentTimeMillis();
        long lifetime = Config.pingLifetimeMs();
        pings.removeIf(p -> now - p.createdMs() > lifetime);

        if (code == null) return;
        boolean inWorld = mc.player != null && mc.level != null;
        if (inWorld && serverKey(mc).equals(roomServer)) {
            noWorldSinceMs = 0;
        } else if (inWorld) {
            leave(false);
            msg("§7Zmieniłeś serwer - wyszedłeś z pokoju.");
        } else if (noWorldSinceMs == 0) {
            noWorldSinceMs = now;
        } else if (now - noWorldSinceMs > 3000) {
            leave(false); // wyszedłeś z serwera / świata
        }
    }

    // ------------------------------------------------------------------ sieć

    private static boolean checkReady(Minecraft mc) {
        if (mc.player == null || mc.level == null) {
            msg("§cMusisz być w grze (na serwerze).");
            return false;
        }
        if (code != null) {
            msg("§cJesteś już w pokoju §e" + code + "§c. Najpierw §f/pingroom leave§c.");
            return false;
        }
        return true;
    }

    private static void request(JsonObject req) {
        pendingServer = req.get("server").getAsString();
        myName = req.get("name").getAsString();

        if (relay != null && relay.isOpen()) {
            relay.send(req);
            return;
        }
        if (relay != null) relay.close();
        msg("§7Łączenie z relayem...");
        pendingRequest = req;
        relay = new RelayClient(HANDLER);
        relay.connect(Config.relayUrl());
    }

    private static void handle(JsonObject m) {
        String t = str(m, "t");
        if (t == null) return;
        switch (t) {
            case "created" -> {
                code = str(m, "code");
                roomServer = pendingServer;
                members.clear();
                members.add(myName);
                msg("§aPokój utworzony! Kod: §e§l" + code);
                msg("§7Znajomi na tym samym serwerze dołączają komendą: §f/pingroom join " + code);
            }
            case "joined" -> {
                code = str(m, "code");
                roomServer = pendingServer;
                members.clear();
                if (m.has("members") && m.get("members").isJsonArray()) {
                    JsonArray arr = m.getAsJsonArray("members");
                    for (JsonElement e : arr) members.add(e.getAsString());
                }
                members.add(myName);
                msg("§aDołączyłeś do pokoju §e" + code + "§a. Członkowie: §f" + String.join(", ", members));
            }
            case "member_join" -> {
                String n = str(m, "name");
                if (n != null && members.add(n)) msg("§e" + n + " §7dołączył do pokoju.");
            }
            case "member_leave" -> {
                String n = str(m, "name");
                if (n != null && members.remove(n)) {
                    pings.removeIf(p -> p.owner().equals(n));
                    msg("§e" + n + " §7wyszedł z pokoju.");
                }
            }
            case "ping" -> onRemotePing(m);
            case "error" -> {
                String text = str(m, "msg");
                msg("§c" + (text == null ? "Błąd relaya." : text));
                if (code == null && relay != null) { // nieudane create/join - zwalniamy połączenie
                    relay.close();
                    relay = null;
                }
            }
            default -> { }
        }
    }

    private static void relayClosed(String reason) {
        relay = null;
        pendingRequest = null;
        boolean wasInRoom = code != null;
        clearRoom();
        msg("§c" + (wasInRoom ? "Rozłączono z relayem: " : "Relay: ") + reason);
    }

    private static void clearRoom() {
        code = null;
        roomServer = "";
        noWorldSinceMs = 0;
        members.clear();
        pings.clear();
    }

    // ------------------------------------------------------------------ pomocnicze

    /** Identyfikator serwera - pokój działa tylko dla graczy z tym samym kluczem. */
    public static String serverKey(Minecraft mc) {
        ServerData data = mc.getCurrentServer();
        if (data == null || data.ip == null) return "local";
        String ip = data.ip.trim().toLowerCase(Locale.ROOT);
        if (ip.endsWith(":25565")) ip = ip.substring(0, ip.length() - 6);
        return ip.isEmpty() ? "local" : ip;
    }

    /** Ping jest widoczny tylko w tym samym wymiarze, w którym został postawiony. */
    public static String dimKey(Minecraft mc) {
        return mc.level.dimension().toString();
    }

    private static String playerName(Minecraft mc) {
        return mc.player.getName().getString();
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
    }

    private static void playSound(Kind kind) {
        Minecraft mc = Minecraft.getInstance();
        float pitch = kind == Kind.DANGER ? 0.7F : 1.6F;
        mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, pitch, 0.7F));
    }

    static void msg(String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.sendSystemMessage(Component.literal("§b[PingRoom] §r" + text));
        }
    }
}
