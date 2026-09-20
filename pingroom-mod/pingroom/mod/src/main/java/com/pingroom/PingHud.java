package com.pingroom;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Rysuje pingi na HUD-zie. Pozycja świata jest rzutowana na ekran ręcznie
 * (na podstawie oczu gracza, kierunku patrzenia i FOV), więc nie potrzeba mixinów.
 */
public final class PingHud {
    private static final int MARGIN = 24;
    private static final int COLOR_HERE = 0x3DDCFF;
    private static final int COLOR_DANGER = 0xFF4B4B;
    private static final int WHITE = 0xFFFFFF;

    private PingHud() {}

    public static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        List<RoomManager.Ping> pings = RoomManager.activePings();
        if (pings.isEmpty()) return;

        float partial = delta.getGameTimeDeltaPartialTick(false);
        String dim = RoomManager.dimKey(mc);
        long now = System.currentTimeMillis();
        long lifetime = Config.pingLifetimeMs();

        int w = mc.getWindow().getGuiScaledWidth();
        int h = mc.getWindow().getGuiScaledHeight();

        // baza kamery: forward / right / up (konwencja Minecrafta: yaw 0 = +Z, pitch > 0 = w dół)
        Vec3 eye = mc.player.getEyePosition(partial);
        double yaw = Math.toRadians(mc.player.getViewYRot(partial));
        double pitch = Math.toRadians(mc.player.getViewXRot(partial));
        double cy = Math.cos(yaw), sy = Math.sin(yaw), cp = Math.cos(pitch), sp = Math.sin(pitch);
        double fx = -sy * cp, fy = -sp, fz = cy * cp;
        double rx = -cy, ry = 0, rz = -sy;
        double ux = -sy * sp, uy = cp, uz = cy * sp;

        double tanHalfFov = Math.tan(Math.toRadians(mc.options.fov().get()) / 2.0);
        double aspect = (double) w / (double) h;

        for (RoomManager.Ping p : pings) {
            if (!p.dim().equals(dim)) continue;
            long age = now - p.createdMs();
            long remaining = lifetime - age;
            if (remaining <= 0) continue;
            int alpha = remaining < 1000 ? (int) (255 * remaining / 1000) : 255;
            if (alpha < 12) continue;

            double dx = p.pos().x - eye.x, dy = p.pos().y - eye.y, dz = p.pos().z - eye.z;
            double camX = dx * rx + dy * ry + dz * rz;
            double camY = dx * ux + dy * uy + dz * uz;
            double camZ = dx * fx + dy * fy + dz * fz;

            double sx, sY;
            if (camZ > 0.05) {
                double ndcX = camX / (camZ * tanHalfFov * aspect);
                double ndcY = camY / (camZ * tanHalfFov);
                sx = (ndcX * 0.5 + 0.5) * w;
                sY = (0.5 - ndcY * 0.5) * h;
                sx = clamp(sx, MARGIN, w - MARGIN);
                sY = clamp(sY, MARGIN, h - MARGIN);
            } else {
                // za plecami - pokazujemy znacznik przy krawędzi ekranu, w stronę obrotu
                double dirX = camX, dirY = -camY;
                double len = Math.hypot(dirX, dirY);
                if (len < 1e-6) { dirX = 0; dirY = 1; len = 1; }
                dirX /= len;
                dirY /= len;
                double hw = w / 2.0 - MARGIN, hh = h / 2.0 - MARGIN;
                double scaleX = Math.abs(dirX) > 1e-6 ? hw / Math.abs(dirX) : Double.MAX_VALUE;
                double scaleY = Math.abs(dirY) > 1e-6 ? hh / Math.abs(dirY) : Double.MAX_VALUE;
                double scale = Math.min(scaleX, scaleY);
                sx = w / 2.0 + dirX * scale;
                sY = h / 2.0 + dirY * scale;
            }

            double dist = eye.distanceTo(p.pos());
            int base = p.kind() == RoomManager.Kind.DANGER ? COLOR_DANGER : COLOR_HERE;
            int r = 6 + (age < 250 ? (int) (8 * (1.0 - age / 250.0)) : 0); // "pop" przy powstaniu

            int cx = (int) Math.round(sx), cyi = (int) Math.round(sY);
            diamond(g, cx, cyi, r + 2, argb(alpha, 0x000000));
            diamond(g, cx, cyi, r, argb(alpha, base));
            diamond(g, cx, cyi, Math.max(1, r / 2 - 1), argb(alpha, WHITE));

            String who = p.mine() ? "Ty" : p.owner();
            String label = (p.kind() == RoomManager.Kind.DANGER ? "! " : "") + who + " · " + Math.round(dist) + " m";
            int tw = mc.font.width(label);
            g.text(mc.font, label, cx - tw / 2, cyi - r - 14, argb(alpha, WHITE), true);
        }
    }

    private static void diamond(GuiGraphicsExtractor g, int cx, int cy, int r, int argb) {
        for (int i = -r; i <= r; i++) {
            int half = r - Math.abs(i);
            g.fill(cx - half, cy + i, cx + half + 1, cy + i + 1, argb);
        }
    }

    private static int argb(int alpha, int rgb) {
        return (alpha << 24) | (rgb & 0xFFFFFF);
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
