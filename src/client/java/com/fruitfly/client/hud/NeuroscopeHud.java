package com.fruitfly.client.hud;

import com.fruitfly.FruitFlyMod;
import com.fruitfly.brain.Connectome;
import com.fruitfly.brain.MotorDecoder;
import com.fruitfly.brain.RetinaGeometry;
import com.fruitfly.client.BrainWorldOverlay;
import com.fruitfly.entity.FlyEntity;
import com.fruitfly.net.BrainTelemetryPayload;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.Mth;
import org.joml.Matrix3x2fStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The "neuroscope": a compact right-hand HUD panel showing what the selected fly's connectome is doing right now.
 *
 * <p>Layout (top to bottom): title, status line, motor channel bars, watched-population rate bars, spike raster
 * (spikes per brain tick over the last {@value #RASTER_TICKS} payloads), retina inset (two eyes), legend.
 * Everything is drawn in "panel units" (font pixels) under a uniform pose-matrix scale chosen so that text
 * stays an integer number of screen pixels tall and the panel never exceeds 40 % of the screen width.</p>
 *
 * <p>Wiring: {@code FruitFlyClient} calls {@link #register()} once and {@link #toggle()} from its key binding;
 * payloads arrive through {@link TelemetryStore#accept}. Default hidden.</p>
 */
public final class NeuroscopeHud {
    /** Nearest fly with fresh telemetry within this many blocks of the player is shown (selection lives in {@link FlyFocus}). */
    public static final double SELECT_RANGE = FlyFocus.RANGE;
    /** Panel width in panel units (font pixels). */
    static final int PANEL_W = 300;
    static final int PAD = 4;
    static final int LINE = 9;
    static final int GAP = 3;
    static final int RASTER_TICKS = 100;
    static final int RASTER_H = 30;
    static final int EYE_H = 60;

    private static volatile boolean visible = false;
    private static boolean registered = false;
    private static final int[] SPIKE_BUF = new int[RASTER_TICKS];
    private static final String DEFAULT_DATASET = "male-cns:v1.0";

    /** {channel key, label}; key "stop" = max(halt, brake), "groom" = max of the four groom channels. */
    private static final String[][] MOTOR_ROWS = {
            {"forward", "fwd"}, {"yaw", "yaw"}, {"backward", "back"}, {"stop", "stop"}, {"landing", "land"},
            {"flightPower", "flight"}, {"feed", "feed"}, {"groom", "groom"}, {"song", "song"}, {"courtship", "court"}
    };
    private static final int[] MOTOR_COLORS = {
            HudStyle.GREEN, HudStyle.ACCENT, HudStyle.ORANGE, HudStyle.YELLOW, HudStyle.TEAL,
            HudStyle.CYAN, HudStyle.PINK, HudStyle.PURPLE, 0xFF8C9EFF, HudStyle.PINK
    };
    private static final String[] LEGEND = {
            "DNp09 fwd", "DNa02 L/R turn", "MDN back", "DNg60 halt", "DNp01 GF jump", "DNp07/10 land",
            "aDN1/2 groom", "MN9 proboscis", "pIP10 song", "LC4/LPLC2 loom", "KC mushroom body", "PN antennal lobe"
    };

    private NeuroscopeHud() { }

    /** Register the HUD render callback and the in-world brain overlay. Idempotent. */
    public static void register() {
        if (registered) return;
        registered = true;
        // after the last vanilla element: drawn on top, and hidden with the rest of the HUD by F1
        HudElementRegistry.attachElementAfter(VanillaHudElements.SUBTITLES, FruitFlyMod.id("neuroscope"), NeuroscopeHud::render);
        BrainWorldOverlay.register();
    }

    public static void toggle() { visible = !visible; }

    public static boolean isVisible() { return visible; }

    public static void setVisible(boolean v) { visible = v; }

    /** Network id of the fly currently shown, or -1 (selection is shared with the brain view via {@link FlyFocus}). */
    public static int selectedEntityId() { return visible ? FlyFocus.currentId() : -1; }

    /** The fly currently shown; null when hidden or none selected. */
    public static FlyEntity selectedFly() {
        return visible ? FlyFocus.current() : null;
    }

    // ------------------------------------------------------------------ rendering

    private static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
        if (!visible) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        if (mc.getDebugOverlay().showDebugScreen()) return;
        Font font = mc.font;

        FlyEntity fly = FlyFocus.current();
        BrainTelemetryPayload p = fly == null ? null : TelemetryStore.latest(fly.getId());
        TelemetryStore.Entry entry = fly == null ? null : TelemetryStore.get(fly.getId());

        int guiW = g.guiWidth(), guiH = g.guiHeight();
        double guiScale = Math.max(1e-3, mc.getWindow().getGuiScale());
        // text stays an integer number of real pixels tall: fontPx real px per font texel
        int fontPx = Math.max(1, (int) Math.round(guiScale / 2.0));
        float s = (float) Math.min(1.0, fontPx / guiScale);

        int inner = PANEL_W - 2 * PAD;
        List<String> legendLines = wrap(font, String.join("  ·  ", LEGEND), inner);

        if (fly == null || p == null) {
            int h = PAD + LINE + 1 + LINE + 1 + LINE + PAD;
            s = Math.min(s, Math.min(0.4f * guiW / PANEL_W, 0.97f * guiH / h));
            Matrix3x2fStack pose = g.pose();
            pose.pushMatrix();
            pose.translate(guiW - PANEL_W * s - 4, 4);
            pose.scale(s, s);
            g.fill(0, 0, PANEL_W, h, HudStyle.BG);
            g.outline(0, 0, PANEL_W, h, HudStyle.BORDER);
            int y = PAD;
            g.text(font, "NEUROSCOPE", PAD, y, HudStyle.ACCENT, false);
            g.text(font, datasetName(), PAD + font.width("NEUROSCOPE  "), y, HudStyle.TEXT, false);
            y += LINE + 1;
            if (FlyFocus.isLocked()) {
                // a lock hides every other fly; say so and how to get out of it
                g.text(font, fly == null ? FlyFocus.describe(null) : FlyFocus.describe(fly) + " on " + fly.flyName() + ": no telemetry", PAD, y, HudStyle.YELLOW, false);
                y += LINE + 1;
                g.text(font, "/brainview nearest to release the lock", PAD, y, HudStyle.DIM, false);
            } else {
                g.text(font, "no fly with telemetry within " + (int) SELECT_RANGE + " blocks", PAD, y, HudStyle.DIM, false);
                y += LINE + 1;
                g.text(font, "/fruitfly spawn  ·  /fruitfly stats", PAD, y, HudStyle.DIM, false);
            }
            pose.popMatrix();
            return;
        }

        int nPops = Math.min(p.popNames().length, p.popRates().length);
        int popCols = nPops > 12 ? 2 : 1;
        int popRows = (nPops + popCols - 1) / popCols;
        int motorRows = (MOTOR_ROWS.length + 1) / 2;
        int height = PAD
                + LINE + 1 + LINE + 1 + GAP                     // title, status, separator
                + LINE + motorRows * LINE + GAP                 // motor
                + LINE + popRows * LINE + GAP                   // populations
                + LINE + RASTER_H + 2 + GAP                     // raster
                + LINE + EYE_H + 2 + GAP                        // retina
                + LINE + legendLines.size() * LINE              // legend
                + PAD;
        s = Math.min(s, Math.min(0.4f * guiW / PANEL_W, 0.97f * guiH / height));

        Matrix3x2fStack pose = g.pose();
        pose.pushMatrix();
        pose.translate(guiW - PANEL_W * s - 4, 4);
        pose.scale(s, s);

        g.fill(0, 0, PANEL_W, height, HudStyle.BG);
        g.outline(0, 0, PANEL_W, height, HudStyle.BORDER);
        int x = PAD, y = PAD;

        // ---- title
        String sex = fly.isMale() ? "male" : "female";
        int tx = x;
        g.text(font, "NEUROSCOPE", tx, y, HudStyle.ACCENT, false);
        tx += font.width("NEUROSCOPE") + 8;
        g.text(font, datasetName(), tx, y, HudStyle.TEXT, false);
        tx += font.width(datasetName()) + 8;
        int idCol = 0xFF000000 | fly.getFlyColor();
        g.fill(tx, y + 1, tx + 6, y + 7, idCol);
        tx += 8;
        String flyName = fly.flyName();
        g.text(font, flyName, tx, y, idCol, false);
        tx += font.width(flyName) + 6;
        String focus = FlyFocus.isLocked() ? "LOCKED" : "nearest";
        g.text(font, focus, tx, y, FlyFocus.isLocked() ? HudStyle.YELLOW : HudStyle.DIM, false);
        tx += font.width(focus) + 6;
        g.text(font, sex, tx, y, fly.isMale() ? HudStyle.CYAN : HudStyle.PINK, false);
        String age = entry == null ? "" : String.format(Locale.ROOT, "%.0f ms", entry.ageMillis());
        g.text(font, age, PANEL_W - PAD - font.width(age), y, HudStyle.DIM, false);
        y += LINE + 1;

        // ---- status
        MotorDecoder.Mode mode = HudStyle.mode(p.mode());
        tx = x;
        g.text(font, mode.name(), tx, y, HudStyle.modeColor(mode), false);
        tx += font.width(mode.name()) + 4;
        if (p.reflex()) {
            g.text(font, "[REFLEX]", tx, y, HudStyle.ORANGE, false);
            tx += font.width("[REFLEX]") + 4;
        }
        if (!fly.hasBrain()) {
            g.text(font, "[NO BRAIN]", tx, y, HudStyle.RED, false);
            tx += font.width("[NO BRAIN]") + 4;
        }
        String spk = HudStyle.fmtCount(p.spikesThisTick()) + " spk/tick";
        g.text(font, spk, tx, y, HudStyle.TEXT, false);
        tx += font.width(spk) + 6;
        String act = HudStyle.fmtCount(p.activeNeurons()) + " active";
        g.text(font, act, tx, y, HudStyle.TEXT, false);
        String rt = String.format(Locale.ROOT, "RT %.2fx", p.realTimeFactor());
        g.text(font, rt, PANEL_W - PAD - font.width(rt), y, p.realTimeFactor() < 0.9f ? HudStyle.RED : HudStyle.GREEN, false);
        y += LINE + 1;
        g.fill(x, y, x + inner, y + 1, HudStyle.SEPARATOR);
        y += GAP;

        // ---- motor channels
        g.text(font, "MOTOR", x, y, HudStyle.DIM, false);
        y += LINE;
        int cw = inner / 2;
        for (int i = 0; i < MOTOR_ROWS.length; i++) {
            int col = i / motorRows, row = i % motorRows;
            int cx = x + col * cw, cy = y + row * LINE;
            String key = MOTOR_ROWS[i][0];
            float v = motorValue(p, key);
            g.text(font, MOTOR_ROWS[i][1], cx, cy, HudStyle.TEXT, false);
            int bx = cx + 34, bw = cw - 34 - 6;
            if (key.equals("yaw")) bipolarBar(g, bx, cy + 1, bw, LINE - 3, v, MOTOR_COLORS[i]);
            else bar(g, bx, cy + 1, bw, LINE - 3, v, MOTOR_COLORS[i]);
        }
        y += motorRows * LINE + GAP;

        // ---- populations
        g.text(font, "POPULATIONS  Hz  (bar = 100 Hz)", x, y, HudStyle.DIM, false);
        y += LINE;
        int pcw = inner / popCols;
        int labelW = popCols == 2 ? 60 : 120, valW = 26;
        for (int i = 0; i < nPops; i++) {
            int col = i / popRows, row = i % popRows;
            int cx = x + col * pcw, cy = y + row * LINE;
            String spec = p.popNames()[i];
            float hz = p.popRates()[i];
            int color = HudStyle.populationColor(spec);
            String label = font.plainSubstrByWidth(HudStyle.populationLabel(spec), labelW - 2);
            g.text(font, label, cx, cy, color, false);
            String val = HudStyle.fmtHz(hz);
            g.text(font, val, cx + labelW + valW - font.width(val), cy, HudStyle.TEXT, false);
            int bx = cx + labelW + valW + 3, bw = pcw - labelW - valW - 3 - 6;
            bar(g, bx, cy + 1, bw, LINE - 3, hz / 100f, color);
            if (entry != null) { // peak-hold tick over the last ~20 payloads
                float peak = entry.rateMax(spec, 20);
                int px = bx + Math.round(Mth.clamp(peak / 100f, 0f, 1f) * (bw - 1));
                g.fill(px, cy + 1, px + 1, cy + LINE - 2, HudStyle.withAlpha(color, 0xA0));
            }
        }
        y += popRows * LINE + GAP;

        // ---- spike raster
        int nHist = entry == null ? 0 : entry.spikeHistory(SPIKE_BUF);
        int maxSpk = 1000;
        for (int v : SPIKE_BUF) maxSpk = Math.max(maxSpk, v);
        g.text(font, "SPIKES / TICK   last " + RASTER_TICKS + "   max " + HudStyle.fmtCount(maxSpk), x, y, HudStyle.DIM, false);
        y += LINE;
        g.fill(x, y, x + inner, y + RASTER_H + 2, HudStyle.BG_INSET);
        float step = inner / (float) RASTER_TICKS;
        int bw = Math.max(1, (int) step - 1);
        int bottom = y + RASTER_H + 1;
        for (int i = 0; i < RASTER_TICKS; i++) {
            int v = SPIKE_BUF[i];
            if (v <= 0) continue;
            float f = v / (float) maxSpk;
            int h = Math.max(1, Math.round(f * RASTER_H));
            int bx = x + Math.round(i * step);
            g.fill(bx, bottom - h, bx + bw, bottom, HudStyle.lerp(f, HudStyle.RASTER_LOW, HudStyle.YELLOW));
        }
        if (nHist == 0) g.text(font, "no history", x + 2, y + 2, HudStyle.DIM, false);
        y += RASTER_H + 2 + GAP;

        // ---- retina
        RetinaGeometry geom = FruitFlyMod.BRAIN.ready() ? FruitFlyMod.BRAIN.geometry() : null;
        byte[] rays = p.retinaRays();
        int nL = geom == null ? 0 : geom.rays(RetinaGeometry.LEFT).size();
        int nR = geom == null ? 0 : geom.rays(RetinaGeometry.RIGHT).size();
        boolean mapped = geom != null && rays.length > 0 && rays.length == nL + nR;
        g.text(font, "RETINA  " + rays.length + " rays" + (mapped ? "  (az/el)" : rays.length > 0 ? "  (strip)" : ""), x, y, HudStyle.DIM, false);
        y += LINE;
        int eyeW = (inner - 4) / 2;
        int lx = x, rx = x + eyeW + 4;
        g.fill(lx, y, lx + eyeW, y + EYE_H, HudStyle.BG_INSET);
        g.fill(rx, y, rx + eyeW, y + EYE_H, HudStyle.BG_INSET);
        if (mapped) {
            drawEye(g, geom, RetinaGeometry.LEFT, rays, 0, lx, y, eyeW, EYE_H);
            drawEye(g, geom, RetinaGeometry.RIGHT, rays, nL, rx, y, eyeW, EYE_H);
        } else if (rays.length > 0) {
            drawStrip(g, rays, lx, y, inner, EYE_H);
        } else {
            g.text(font, "no retina data", lx + 2, y + 2, HudStyle.DIM, false);
        }
        g.text(font, "L", lx + 2, y + EYE_H - LINE, HudStyle.DIM, false);
        g.text(font, "R", rx + eyeW - 2 - font.width("R"), y + EYE_H - LINE, HudStyle.DIM, false);
        y += EYE_H + 2 + GAP;

        // ---- legend
        g.text(font, "KEY POPULATIONS", x, y, HudStyle.DIM, false);
        y += LINE;
        for (String line : legendLines) {
            g.text(font, line, x, y, HudStyle.DIM, false);
            y += LINE;
        }

        pose.popMatrix();
    }

    // ------------------------------------------------------------------ widgets

    private static float motorValue(BrainTelemetryPayload p, String key) {
        return switch (key) {
            case "stop" -> Math.max(p.channel("halt"), p.channel("brake"));
            case "groom" -> Math.max(Math.max(p.channel("groomAntenna"), p.channel("groomHead")),
                    Math.max(p.channel("groomLeg"), p.channel("groomAbdomen")));
            default -> p.channel(key);
        };
    }

    static void bar(GuiGraphicsExtractor g, int x, int y, int w, int h, float v, int color) {
        if (w <= 0 || h <= 0) return;
        g.fill(x, y, x + w, y + h, HudStyle.BAR_BG);
        int fw = Math.round(Mth.clamp(v, 0f, 1f) * w);
        if (fw > 0) g.fill(x, y, x + fw, y + h, color);
    }

    /** −1..1 bar filling from the centre: negative to the left, positive to the right. */
    static void bipolarBar(GuiGraphicsExtractor g, int x, int y, int w, int h, float v, int color) {
        if (w <= 0 || h <= 0) return;
        g.fill(x, y, x + w, y + h, HudStyle.BAR_BG);
        int mid = x + w / 2;
        int half = w / 2;
        int fw = Math.round(Mth.clamp(Math.abs(v), 0f, 1f) * half);
        if (fw > 0) {
            if (v >= 0) g.fill(mid, y, mid + fw, y + h, color);
            else g.fill(mid - fw, y, mid, y + h, color);
        }
        g.fill(mid, y, mid + 1, y + h, HudStyle.withAlpha(HudStyle.TEXT, 0x90));
    }

    /**
     * One eye: each coarse ray is placed on a gridW x gridH cell grid by its (azimuth, elevation) within the eye's
     * extent (x increases with azimuth, y increases downward with decreasing elevation) and filled with its luminance.
     */
    private static void drawEye(GuiGraphicsExtractor g, RetinaGeometry geom, int side, byte[] rays, int offset, int x, int y, int w, int h) {
        List<RetinaGeometry.Ray> list = geom.rays(side);
        int n = list.size();
        if (n == 0) return;
        float azMin = Float.MAX_VALUE, azMax = -Float.MAX_VALUE, elMin = Float.MAX_VALUE, elMax = -Float.MAX_VALUE;
        for (RetinaGeometry.Ray r : list) {
            azMin = Math.min(azMin, r.azimuthDeg);
            azMax = Math.max(azMax, r.azimuthDeg);
            elMin = Math.min(elMin, r.elevationDeg);
            elMax = Math.max(elMax, r.elevationDeg);
        }
        int gridW = Math.max(1, (int) Math.ceil(Math.sqrt(n * 1.4)));
        int gridH = Math.max(1, (int) Math.ceil(n / (double) gridW));
        float cellW = (w - 2) / (float) gridW, cellH = (h - 2) / (float) gridH;
        int pw = Math.max(1, Math.round(cellW) - 1), ph = Math.max(1, Math.round(cellH) - 1);
        for (int i = 0; i < n; i++) {
            RetinaGeometry.Ray r = list.get(i);
            float ax = azMax > azMin ? (r.azimuthDeg - azMin) / (azMax - azMin) : 0.5f;
            float ey = elMax > elMin ? (elMax - r.elevationDeg) / (elMax - elMin) : 0.5f;
            int gx = Math.min(gridW - 1, (int) (ax * gridW * 0.999f));
            int gy = Math.min(gridH - 1, (int) (ey * gridH * 0.999f));
            int px = x + 1 + Math.round(gx * cellW), py = y + 1 + Math.round(gy * cellH);
            g.fill(px, py, px + pw, py + ph, HudStyle.grey(rays[offset + i] & 0xFF));
        }
    }

    /** Fallback when no retina geometry is available on this client: rays as a two-row strip, left half then right. */
    private static void drawStrip(GuiGraphicsExtractor g, byte[] rays, int x, int y, int w, int h) {
        int rows = 4;
        int cols = Math.max(1, (rays.length + rows - 1) / rows);
        float cellW = (w - 2) / (float) cols, cellH = (h - 2) / (float) rows;
        int pw = Math.max(1, Math.round(cellW) - 1), ph = Math.max(1, Math.round(cellH) - 1);
        for (int i = 0; i < rays.length; i++) {
            int c = i % cols, r = i / cols;
            int px = x + 1 + Math.round(c * cellW), py = y + 1 + Math.round(r * cellH);
            g.fill(px, py, px + pw, py + ph, HudStyle.grey(rays[i] & 0xFF));
        }
    }

    private static String datasetName() {
        if (FruitFlyMod.BRAIN.ready()) {
            Connectome c = FruitFlyMod.BRAIN.connectome();
            if (c != null && c.dataset != null && !c.dataset.isBlank()) return c.dataset;
        }
        return DEFAULT_DATASET;
    }

    /** Greedy word wrap by font width. */
    private static List<String> wrap(Font font, String text, int maxW) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            if (word.isEmpty()) continue;
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (font.width(candidate) > maxW && !line.isEmpty()) {
                out.add(line.toString());
                line.setLength(0);
                line.append(word);
            } else {
                line.setLength(0);
                line.append(candidate);
            }
        }
        if (!line.isEmpty()) out.add(line.toString());
        return out;
    }
}
