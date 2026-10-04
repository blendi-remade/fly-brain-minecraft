package com.fruitfly.client.hud;

import com.fruitfly.FruitFlyMod;
import com.fruitfly.brain.Connectome;
import com.fruitfly.brain.MotorDecoder;
import com.fruitfly.entity.FlyEntity;
import com.fruitfly.net.BrainTelemetryPayload;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import org.joml.Matrix3x2fStack;

import java.util.Arrays;
import java.util.Locale;

/**
 * The pinned "brain view": a live map of the whole CNS in the corner of the screen, showing exactly which neurons
 * fired in the focused fly's last brain tick and where.
 *
 * <p>Once per view, every neuron with a reconstructed soma is projected onto a 2-D image (dorsal, frontal or side
 * view; axes derived from the data: the brain→nerve-cord direction is the longest axis, left/right is the axis along
 * which the optic lobes are widest, dorsal is low neuPrint y). The projection is baked into a static background
 * texture coloured by region. Every telemetry payload splats the sampled spikes into a heat buffer that decays over
 * ~300 ms and is uploaded as a second texture, so activity reads as glowing, fading points. Below the map: per-region
 * spike counts for this tick with peak-hold, and a spikes-per-tick history.</p>
 *
 * <p>Layout is done in "panel units" (font pixels): the panel is as wide as its widest text row and the map fills that
 * width, then one uniform pose-matrix scale shrinks the whole panel so it never exceeds 45 % of the screen width
 * or {@link #size()} of the screen height (same approach as {@link NeuroscopeHud}). The GUI renderer batches fills
 * itself, so the ~100 bars and outlines cost a handful of draw calls per frame.</p>
 *
 * <p>Selection comes from {@link FlyFocus}; the focused fly's identity colour and name head the panel and match its
 * name tag and the in-world marker, so with several flies it is always clear whose brain this is.</p>
 */
public final class BrainViewHud {
    public enum View { DORSAL, FRONTAL, SIDE }

    static final int REGIONS = 8;
    static final String[] REGION_NAMES = {"optic lobe L", "optic lobe R", "central brain", "nerve cord", "descending", "motor", "sensory", "other"};
    static final int[] REGION_RGB = {0x3FB8B0, 0x6FE0D8, 0x9DB0E6, 0xC89A6A, 0x5B9BFF, 0xFFA040, 0x55D66E, 0x8A8A8A};
    private static final float HEAT_TAU_S = 0.30f;
    private static final int RASTER_TICKS = 60;
    private static final int PAD = 4, LINE = 10, RASTER_H = 20;

    private static volatile boolean visible = false;
    private static boolean registered = false;
    private static View view = View.DORSAL;
    /** Maximum panel height as a fraction of the screen height (the panel is scaled down uniformly to fit). */
    private static float sizeFrac = 0.60f;

    // projection cache (per connectome + view)
    private static Connectome source;
    private static View builtView;
    private static int texW, texH;
    private static int[] pixelOf;        // neuron -> pixel index or -1
    private static byte[] regionOf;      // neuron -> region id
    private static byte[] pixelRegion;   // pixel -> dominant region (or -1)
    private static float[] heat;
    private static NativeImage bgImg, heatImg;
    private static DynamicTexture bgTex, heatTex;
    private static final Identifier BG_LOC = FruitFlyMod.id("brainview_bg"), HEAT_LOC = FruitFlyMod.id("brainview_heat");
    private static boolean loggedBuild;

    // activity state
    private static long lastSeq = -1;
    private static int lastFlyId = -1;
    private static long lastFrameNanos, lastUploadNanos;
    private static boolean heatDirty;
    private static final int[] regionCounts = new int[REGIONS];
    private static final float[] regionPeak = new float[REGIONS];
    private static final int[] SPIKE_HIST = new int[RASTER_TICKS];

    private BrainViewHud() { }

    public static void register() {
        if (registered) return;
        registered = true;
        // after the last vanilla element: drawn on top, and hidden with the rest of the HUD by F1
        HudElementRegistry.attachElementAfter(VanillaHudElements.SUBTITLES, FruitFlyMod.id("brain_view"), BrainViewHud::render);
    }

    public static void toggle() { visible = !visible; }
    public static boolean isVisible() { return visible; }
    public static void setVisible(boolean v) { visible = v; }
    public static View view() { return view; }
    public static void setView(View v) { view = v; }
    public static float size() { return sizeFrac; }
    public static void setSize(float frac) { sizeFrac = Mth.clamp(frac, 0.15f, 0.75f); }

    /** Forget per-fly activity (call on disconnect: the next world may reuse the same network id for another fly). */
    public static void reset() {
        lastFlyId = -1;
        lastSeq = -1;
        if (heat != null) Arrays.fill(heat, 0f);
        Arrays.fill(regionCounts, 0);
        Arrays.fill(regionPeak, 0f);
        heatDirty = true;
    }

    // ------------------------------------------------------------------ projection

    private static byte regionFor(Connectome c, int i) {
        String sc = c.superclass(i);
        if (sc.equals("descending_neuron")) return 4;
        if (sc.endsWith("_motor") || sc.endsWith("_efferent")) return 5;
        if (sc.contains("sensory")) return 6;
        if (sc.startsWith("ol_")) return (byte) ("L".equals(c.side(i)) ? 0 : 1);
        if (sc.startsWith("vnc_") || sc.startsWith("ascending")) return 3;
        if (sc.startsWith("cb_") || sc.startsWith("visual_")) return 2;
        return 7;
    }

    private static void ensureBuilt(Connectome c) {
        if (source == c && builtView == view && bgTex != null) return;
        long t0 = System.nanoTime();
        int n = c.n;
        // --- data-derived axes ---
        double[] brainMean = new double[3], vncMean = new double[3];
        int nb = 0, nv = 0;
        double[] olMean = new double[3], olVar = new double[3];
        int nol = 0;
        for (int i = 0; i < n; i++) {
            if (!c.hasSoma(i)) continue;
            String sc = c.superclass(i);
            if (sc.equals("cb_intrinsic")) { for (int a = 0; a < 3; a++) brainMean[a] += c.soma[3 * i + a]; nb++; }
            else if (sc.equals("vnc_intrinsic")) { for (int a = 0; a < 3; a++) vncMean[a] += c.soma[3 * i + a]; nv++; }
            if (sc.equals("ol_intrinsic")) { for (int a = 0; a < 3; a++) olMean[a] += c.soma[3 * i + a]; nol++; }
        }
        for (int a = 0; a < 3; a++) {
            brainMean[a] /= Math.max(1, nb);
            vncMean[a] /= Math.max(1, nv);
            olMean[a] /= Math.max(1, nol);
        }
        for (int i = 0; i < n; i++) {
            if (!c.hasSoma(i) || !c.superclass(i).equals("ol_intrinsic")) continue;
            for (int a = 0; a < 3; a++) { double d = c.soma[3 * i + a] - olMean[a]; olVar[a] += d * d; }
        }
        int ap = 0;
        for (int a = 1; a < 3; a++) if (Math.abs(brainMean[a] - vncMean[a]) > Math.abs(brainMean[ap] - vncMean[ap])) ap = a;
        int lr = -1;
        for (int a = 0; a < 3; a++) if (a != ap && (lr < 0 || olVar[a] > olVar[lr])) lr = a;
        int dv = 3 - ap - lr;
        double apSign = brainMean[ap] <= vncMean[ap] ? 1 : -1;   // brain at the top (small v)
        double lrSign = lr == 0 ? -1 : 1;                        // neuPrint x grows to the animal's left → image right = −x
        double dvSign = 1;                                       // neuPrint y grows ventrally → dorsal = small v

        // --- project ---
        int[] region = new int[n];
        double[] u = new double[n], v = new double[n];
        boolean[] has = new boolean[n];
        double uMin = Double.MAX_VALUE, uMax = -Double.MAX_VALUE, vMin = Double.MAX_VALUE, vMax = -Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            region[i] = regionFor(c, i);
            if (!c.hasSoma(i)) continue;
            double x = c.soma[3 * i], y = c.soma[3 * i + 1], z = c.soma[3 * i + 2];
            double[] p = {x, y, z};
            double cAP = apSign * p[ap], cLR = lrSign * p[lr], cDV = dvSign * p[dv];
            double uu, vv;
            switch (view) {
                // face-on view: the animal's right is on the viewer's left, so mirror left/right
                case FRONTAL -> { uu = -cLR; vv = cDV; }
                // seen from the animal's left: anterior on the viewer's left
                case SIDE -> { uu = cAP; vv = cDV; }
                // seen from above with the head up the page: the animal's left is on the viewer's left
                default -> { uu = cLR; vv = cAP; }
            }
            u[i] = uu; v[i] = vv; has[i] = true;
            uMin = Math.min(uMin, uu); uMax = Math.max(uMax, uu);
            vMin = Math.min(vMin, vv); vMax = Math.max(vMax, vv);
        }
        double uRange = Math.max(1e-6, uMax - uMin), vRange = Math.max(1e-6, vMax - vMin);
        int w = 256;
        int h = (int) Mth.clamp(Math.round(w * vRange / uRange), 96, 512);
        double scale = Math.min((w - 12) / uRange, (h - 12) / vRange);
        int offU = (int) Math.round((w - uRange * scale) / 2), offV = (int) Math.round((h - vRange * scale) / 2);

        int[] px = new int[n];
        Arrays.fill(px, -1);
        int[] count = new int[w * h];
        int[] regionHist = new int[w * h * REGIONS];
        for (int i = 0; i < n; i++) {
            if (!has[i]) continue;
            int ix = Mth.clamp((int) ((u[i] - uMin) * scale) + offU, 0, w - 1);
            int iy = Mth.clamp((int) ((v[i] - vMin) * scale) + offV, 0, h - 1);
            int p = iy * w + ix;
            px[i] = p;
            count[p]++;
            regionHist[p * REGIONS + region[i]]++;
        }
        int maxCount = 1;
        for (int cnt : count) maxCount = Math.max(maxCount, cnt);
        // --- textures ---
        releaseTextures();
        NativeImage bg = new NativeImage(w, h, true);
        NativeImage ht = new NativeImage(w, h, true);
        byte[] pr = new byte[w * h];
        Arrays.fill(pr, (byte) -1);
        double logMax = Math.log1p(maxCount);
        for (int p = 0; p < w * h; p++) {
            if (count[p] == 0) { bg.setPixelABGR(p % w, p / w, 0); ht.setPixelABGR(p % w, p / w, 0); continue; }
            int best = 0;
            for (int r = 1; r < REGIONS; r++) if (regionHist[p * REGIONS + r] > regionHist[p * REGIONS + best]) best = r;
            pr[p] = (byte) best;
            float f = (float) (Math.log1p(count[p]) / logMax);
            float bright = 0.30f + 0.65f * f;
            int rgb = REGION_RGB[best];
            int r = (int) (((rgb >> 16) & 255) * bright), gg = (int) (((rgb >> 8) & 255) * bright), b = (int) ((rgb & 255) * bright);
            bg.setPixelABGR(p % w, p / w, abgr(255, r, gg, b));
            ht.setPixelABGR(p % w, p / w, 0);
        }
        Minecraft mc = Minecraft.getInstance();
        bgImg = bg;
        heatImg = ht;
        bgTex = new DynamicTexture(BG_LOC::toString, bg);       // uploads the image
        heatTex = new DynamicTexture(HEAT_LOC::toString, ht);
        mc.getTextureManager().register(BG_LOC, bgTex);
        mc.getTextureManager().register(HEAT_LOC, heatTex);
        texW = w;
        texH = h;
        pixelOf = px;
        regionOf = new byte[n];
        for (int i = 0; i < n; i++) regionOf[i] = (byte) region[i];
        pixelRegion = pr;
        heat = new float[w * h];
        Arrays.fill(regionCounts, 0);
        Arrays.fill(regionPeak, 0f);
        lastSeq = -1;
        source = c;
        builtView = view;
        if (!loggedBuild) {
            loggedBuild = true;
            FruitFlyMod.LOGGER.info("Brain view: projected {} somata onto {}x{} ({} view; axes ap={}, lr={}, dv={}) in {} ms",
                    Arrays.stream(px).filter(q -> q >= 0).count(), w, h, view, ap, lr, dv, (System.nanoTime() - t0) / 1_000_000);
        }
    }

    private static void releaseTextures() {
        Minecraft mc = Minecraft.getInstance();
        if (bgTex != null) mc.getTextureManager().release(BG_LOC);
        if (heatTex != null) mc.getTextureManager().release(HEAT_LOC);
        bgTex = heatTex = null;
        bgImg = heatImg = null;
    }

    /**
     * Linear filtering and clamped edges for drawing the map: {@link DynamicTexture}'s own sampler is GL_NEAREST, which
     * drops single-texel spikes whenever the map is drawn smaller than the texture.
     */
    private static GpuSampler linearSampler() {
        return RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
    }

    private static int abgr(int a, int r, int g, int b) {
        return (a << 24) | (b << 16) | (g << 8) | r;
    }

    // ------------------------------------------------------------------ activity

    private static void inject(int[] sample) {
        Arrays.fill(regionCounts, 0);
        if (sample == null) return;
        int w = texW, h = texH;
        for (int id : sample) {
            if (id < 0 || id >= pixelOf.length) continue;
            regionCounts[regionOf[id]]++;
            int p = pixelOf[id];
            if (p < 0) continue;
            heat[p] = 1f;
            int x = p % w, y = p / w;
            if (x > 0) heat[p - 1] = Math.max(heat[p - 1], 0.55f);
            if (x < w - 1) heat[p + 1] = Math.max(heat[p + 1], 0.55f);
            if (y > 0) heat[p - w] = Math.max(heat[p - w], 0.55f);
            if (y < h - 1) heat[p + w] = Math.max(heat[p + w], 0.55f);
        }
        for (int r = 0; r < REGIONS; r++) regionPeak[r] = Math.max(regionPeak[r] * 0.97f, regionCounts[r]);
        heatDirty = true;
    }

    private static void uploadHeat() {
        int w = texW, h = texH;
        for (int p = 0; p < w * h; p++) {
            float v = heat[p];
            if (v < 0.02f) {
                if (heatImg.getPixel(p % w, p / w) != 0) heatImg.setPixelABGR(p % w, p / w, 0);
                continue;
            }
            int region = pixelRegion[p] < 0 ? 7 : pixelRegion[p];
            int rgb = REGION_RGB[region];
            // hot pixels tend to white-yellow, cooler ones keep the region hue
            float wht = v * v;
            int r = (int) Mth.lerp(wht, ((rgb >> 16) & 255) * 0.6f + 100, 255);
            int g = (int) Mth.lerp(wht, ((rgb >> 8) & 255) * 0.6f + 100, 250);
            int b = (int) Mth.lerp(wht, (rgb & 255) * 0.6f + 60, 200);
            heatImg.setPixelABGR(p % w, p / w, abgr((int) (255 * Math.min(1f, v)), Math.min(255, r), Math.min(255, g), Math.min(255, b)));
        }
        heatTex.upload();
    }

    // ------------------------------------------------------------------ layout

    /** Width of the panel interior in panel units: the widest row of text the panel can show (font metrics are fixed). */
    private static int innerWidth(Font font) {
        int widestMode = 0;
        for (MotorDecoder.Mode m : MotorDecoder.Mode.values()) widestMode = Math.max(widestMode, font.width(m.name()));
        int status = widestMode + 5 + font.width("[REFLEX]") + 5 + font.width("999k spk/tick") + 6
                + Math.max(font.width("RT 9.99x"), font.width("stale 999 s"));
        int headings = Math.max(font.width("SPIKES THIS TICK BY REGION (sampled)"),
                font.width("spikes/tick  last " + RASTER_TICKS + "  max 999k"));
        int empty = font.width("BRAIN VIEW") + 6 + font.width("no fly with telemetry nearby");
        int bars = regionLabelWidth(font) + 4 + font.width("9999") + 4 + 40;
        return Math.max(Math.max(status, headings), Math.max(empty, bars));
    }

    private static int regionLabelWidth(Font font) {
        int w = 0;
        for (String name : REGION_NAMES) w = Math.max(w, font.width(name));
        return w;
    }

    // ------------------------------------------------------------------ render

    private static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
        if (!visible) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        if (mc.getDebugOverlay().showDebugScreen()) return; // F3 owns the top-left corner
        Font font = mc.font;
        int guiW = g.guiWidth(), guiH = g.guiHeight();
        final int pad = PAD, line = LINE;

        if (!FruitFlyMod.BRAIN.ready()) {
            FruitFlyMod.BRAIN.preload();
            g.fill(4, 4, 4 + 190, 4 + 2 * line + 2 * pad, HudStyle.BG);
            g.outline(4, 4, 190, 2 * line + 2 * pad, HudStyle.BORDER);
            g.text(font, "BRAIN VIEW", 4 + pad, 4 + pad, HudStyle.ACCENT, false);
            g.text(font, FruitFlyMod.BRAIN.loadError() != null ? "connectome failed to load" : "loading connectome...", 4 + pad, 4 + pad + line, HudStyle.DIM, false);
            return;
        }
        Connectome c = FruitFlyMod.BRAIN.connectome();
        ensureBuilt(c);

        FlyEntity fly = FlyFocus.current();
        TelemetryStore.Entry entry = fly == null ? null : TelemetryStore.get(fly.getId());
        BrainTelemetryPayload p = entry == null ? null : entry.latest();
        long now = System.nanoTime();
        boolean fresh = entry != null && entry.fresh(now);

        // ---- activity update
        float dt = lastFrameNanos == 0 ? 0f : Math.min(0.25f, (now - lastFrameNanos) / 1e9f);
        lastFrameNanos = now;
        if (dt > 0) {
            float decay = (float) Math.exp(-dt / HEAT_TAU_S);
            boolean any = false;
            for (int i = 0; i < heat.length; i++) {
                if (heat[i] > 0.005f) { heat[i] *= decay; any = true; } else heat[i] = 0f;
            }
            if (any) heatDirty = true;
        }
        int flyId = fly == null ? -1 : fly.getId();
        if (flyId != lastFlyId) {
            lastFlyId = flyId;
            lastSeq = -1;
            Arrays.fill(heat, 0f);
            Arrays.fill(regionCounts, 0);
            Arrays.fill(regionPeak, 0f);
            heatDirty = true;
        }
        if (entry != null && entry.seq() != lastSeq) {
            lastSeq = entry.seq();
            if (p != null) inject(p.spikeSample());
        }
        if (!fresh) Arrays.fill(regionCounts, 0); // stale payload: no spikes "this tick"
        if (heatDirty && now - lastUploadNanos > 30_000_000L) {
            uploadHeat();
            heatDirty = false;
            lastUploadNanos = now;
        }

        // ---- layout in panel units, then one uniform scale so the panel fits the screen
        final int inner = innerWidth(font);
        final int panelW = inner + 2 * pad;
        final int imgW = inner;
        final int imgH = Math.round(imgW * texH / (float) texW);
        final int regionsH = REGIONS * 8 + 2;
        final int mapY = pad + line + line + 2;
        final int panelH = mapY + imgH + 2 + line + regionsH + 2 + line + RASTER_H + pad;
        float s = Math.min(1f, Math.min(0.45f * guiW / panelW, sizeFrac * guiH / panelH));

        Matrix3x2fStack pose = g.pose();
        pose.pushMatrix();
        pose.translate(4, 4);
        pose.scale(s, s);
        final int x = pad;
        int y = pad;

        // ---- chrome: panel background, outline, identity swatch, map inset
        final int idCol = fly == null ? 0 : 0xFF000000 | fly.getFlyColor();
        g.fill(0, 0, panelW, panelH, HudStyle.BG);
        g.outline(0, 0, panelW, panelH, HudStyle.BORDER);
        if (idCol != 0) {
            g.fill(pad, pad, pad + 7, pad + 7, idCol);
            g.outline(pad, pad, 7, 7, 0x80FFFFFF);
        }
        g.fill(pad, mapY, pad + imgW, mapY + imgH, HudStyle.BG_INSET);

        // ---- title: identity swatch + name + focus mode + distance
        if (fly != null) {
            String name = fly.flyName();
            String lock = FlyFocus.isLocked() ? "LOCKED" : "nearest";
            String dist = String.format(Locale.ROOT, "%.1f m", fly.distanceTo(mc.player));
            int tx = x + 10;
            g.text(font, name, tx, y, idCol, false);
            tx += font.width(name) + 6;
            g.text(font, lock, tx, y, FlyFocus.isLocked() ? HudStyle.YELLOW : HudStyle.DIM, false);
            tx += font.width(lock) + 4;
            if (tx + font.width(dist) <= x + inner) g.text(font, dist, x + inner - font.width(dist), y, HudStyle.DIM, false);
        } else {
            g.text(font, "BRAIN VIEW", x, y, HudStyle.ACCENT, false);
            String st = FlyFocus.isLocked() ? "locked fly not loaded" : "no fly with telemetry nearby";
            g.text(font, st, x + inner - font.width(st), y, FlyFocus.isLocked() ? HudStyle.YELLOW : HudStyle.DIM, false);
        }
        y += line;
        // ---- status
        if (p != null) {
            MotorDecoder.Mode mode = HudStyle.mode(p.mode());
            int tx = x;
            g.text(font, mode.name(), tx, y, fresh ? HudStyle.modeColor(mode) : HudStyle.DIM, false);
            tx += font.width(mode.name()) + 5;
            if (p.reflex()) {
                g.text(font, "[REFLEX]", tx, y, fresh ? HudStyle.ORANGE : HudStyle.DIM, false);
                tx += font.width("[REFLEX]") + 5;
            }
            String spk = HudStyle.fmtCount(p.spikesThisTick()) + " spk/tick";
            g.text(font, spk, tx, y, fresh ? HudStyle.TEXT : HudStyle.DIM, false);
            tx += font.width(spk) + 6;
            String right;
            int rightCol;
            if (fresh) {
                right = String.format(Locale.ROOT, "RT %.2fx", p.realTimeFactor());
                rightCol = p.realTimeFactor() < 0.9f ? HudStyle.RED : HudStyle.GREEN;
            } else {
                right = String.format(Locale.ROOT, "stale %.0f s", entry.ageMillis() / 1000.0);
                rightCol = HudStyle.ORANGE;
            }
            if (tx + font.width(right) <= x + inner) g.text(font, right, x + inner - font.width(right), y, rightCol, false);
        } else if (fly != null) {
            g.text(font, fly.hasBrain() ? "waiting for telemetry" : "no brain (reflex body)", x, y, HudStyle.DIM, false);
        } else if (FlyFocus.isLocked()) {
            g.text(font, "/brainview nearest to release", x, y, HudStyle.DIM, false);
        } else {
            g.text(font, c.dataset + "  " + HudStyle.fmtCount(c.n) + " neurons", x, y, HudStyle.DIM, false);
        }
        y = mapY;

        // ---- brain map: static region map, then the alpha-blended heat on top
        g.blit(bgTex.getTextureView(), linearSampler(), x, y, x + imgW, y + imgH, 0f, 1f, 0f, 1f);
        g.blit(heatTex.getTextureView(), linearSampler(), x, y, x + imgW, y + imgH, 0f, 1f, 0f, 1f);
        // orientation labels
        switch (view) {
            case DORSAL -> {
                g.text(font, "L", x + 2, y + 2, HudStyle.DIM, false);
                g.text(font, "R", x + imgW - 2 - font.width("R"), y + 2, HudStyle.DIM, false);
                g.text(font, "brain", x + 2, y + imgH / 2 - 14, HudStyle.DIM, false);
                g.text(font, "VNC", x + 2, y + imgH - line - 1, HudStyle.DIM, false);
            }
            case FRONTAL -> {
                g.text(font, "R", x + 2, y + 2, HudStyle.DIM, false);
                g.text(font, "L", x + imgW - 2 - font.width("L"), y + 2, HudStyle.DIM, false);
                g.text(font, "dorsal", x + 2, y + imgH - line - 1, HudStyle.DIM, false);
            }
            case SIDE -> {
                g.text(font, "front", x + 2, y + 2, HudStyle.DIM, false);
                g.text(font, "back", x + imgW - 2 - font.width("back"), y + 2, HudStyle.DIM, false);
            }
        }
        String vw = view.name().toLowerCase(Locale.ROOT);
        g.text(font, vw, x + imgW - 2 - font.width(vw), y + imgH - line - 1, HudStyle.DIM, false);
        y += imgH + 2;

        // ---- regions: label | bar (peak-hold tick) | count
        g.text(font, "SPIKES THIS TICK BY REGION (sampled)", x, y, HudStyle.DIM, false);
        y += line;
        final int labelW = regionLabelWidth(font) + 4;
        final int bx = x + labelW, bw = Math.max(1, inner - labelW - font.width("9999") - 4);
        float peakAll = 1f;
        for (int r = 0; r < REGIONS; r++) peakAll = Math.max(peakAll, regionPeak[r]);
        final float peak = peakAll;
        final int barsY = y;
        for (int r = 0; r < REGIONS; r++) {
            int ry = barsY + r * 8;
            g.fill(bx, ry, bx + bw, ry + 6, HudStyle.BAR_BG);
            int fw = Math.round(Mth.clamp(regionCounts[r] / peak, 0f, 1f) * bw);
            if (fw > 0) g.fill(bx, ry, bx + fw, ry + 6, 0xFF000000 | REGION_RGB[r]);
            int pk = bx + Math.round(Mth.clamp(regionPeak[r] / peak, 0f, 1f) * (bw - 1));
            g.fill(pk, ry, pk + 1, ry + 6, 0x80FFFFFF);
        }
        for (int r = 0; r < REGIONS; r++) {
            int ry = y + r * 8;
            g.text(font, REGION_NAMES[r], x, ry - 1, 0xFF000000 | REGION_RGB[r], false);
            String cnt = Integer.toString(regionCounts[r]);
            g.text(font, cnt, x + inner - font.width(cnt), ry - 1, HudStyle.TEXT, false);
        }
        y += regionsH + 2;

        // ---- spikes per tick history
        int nHist;
        if (entry == null) {
            Arrays.fill(SPIKE_HIST, 0); // otherwise the previous fly's bars would linger under "no history"
            nHist = 0;
        } else {
            nHist = entry.spikeHistory(SPIKE_HIST);
        }
        int maxSpk = 500;
        for (int v : SPIKE_HIST) maxSpk = Math.max(maxSpk, v);
        g.text(font, "spikes/tick  last " + RASTER_TICKS + "  max " + HudStyle.fmtCount(maxSpk), x, y, HudStyle.DIM, false);
        y += line;
        final int rasterY = y, maxV = maxSpk;
        g.fill(x, rasterY, x + inner, rasterY + RASTER_H, HudStyle.BG_INSET);
        float step = inner / (float) RASTER_TICKS;
        int barW = Math.max(1, (int) step - 1);
        for (int i = 0; i < RASTER_TICKS; i++) {
            int v = SPIKE_HIST[i];
            if (v <= 0) continue;
            float f = v / (float) maxV;
            int bh = Math.max(1, Math.round(f * (RASTER_H - 2)));
            int barX = x + Math.round(i * step);
            g.fill(barX, rasterY + RASTER_H - 1 - bh, barX + barW, rasterY + RASTER_H - 1, HudStyle.lerp(f, HudStyle.RASTER_LOW, HudStyle.YELLOW));
        }
        if (nHist == 0) g.text(font, "no history", x + 2, y + 2, HudStyle.DIM, false);

        pose.popMatrix();
    }
}
