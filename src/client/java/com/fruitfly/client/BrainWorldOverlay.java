package com.fruitfly.client;

import com.fruitfly.FruitFlyMod;
import com.fruitfly.brain.Connectome;
import com.fruitfly.client.hud.NeuroscopeHud;
import com.fruitfly.client.hud.TelemetryStore;
import com.fruitfly.entity.FlyEntity;
import com.fruitfly.entity.WorldSenses;
import com.fruitfly.net.BrainTelemetryPayload;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.Arrays;

/**
 * Translucent "brain cloud" hologram floating above the fly selected by the {@link NeuroscopeHud}.
 *
 * <p>Once per JVM, ~{@value #SAMPLE} soma positions are sampled (uniform stride over neurons with a known soma) from
 * {@code FruitFlyMod.BRAIN.connectome()}, centred and scaled to {@value #SIZE_BLOCKS} blocks and coloured by
 * superclass. Every frame the cloud is submitted as additive camera-facing billboards from
 * {@code LevelRenderEvents.COLLECT_SUBMITS} with camera-relative coordinates (fabric-api.md §12 item 9,
 * fly-model-art.md §4.2); being blended custom geometry it draws in the translucent-features pass, i.e. before
 * translucent terrain. Neurons in the payload's {@code spikeSample()} flash bright yellow; because the sample is
 * only ~2 % of the brain, each spiking neuron also lights up the sampled somata in its spatial neighbourhood so the
 * activity shows up as a regional glow. The hologram rotates with the fly's body yaw.</p>
 *
 * <p>Axis convention (data-access.md §1.6, schematic only): neuPrint voxel x increases toward the animal's left,
 * y increases ventrally, z runs anterior → posterior; so local right = −x, up = −y, forward = −z.</p>
 *
 * <p>Only active in single-player (the connectome is not available on a dedicated-server client); null-safe.</p>
 */
public final class BrainWorldOverlay {
    public static final int SAMPLE = 3000;
    public static final float SIZE_BLOCKS = 0.8f;
    /** Hologram centre height above the fly's feet, in blocks (plus the hitbox height). */
    private static final float HOVER = 0.75f;
    private static final double MAX_DISTANCE = 48.0;
    private static final int GRID = 16;
    private static final float ACT_TAU_S = 0.22f, HIT_TAU_S = 0.15f;

    /** Monotonic time base for the focus-marker animation (a modulo clock would snap the ring once a minute). */
    private static final long T0 = System.nanoTime();

    /** Additive (SRC_ALPHA, ONE) position-colour quads: depth-tested against the world, no depth write, two-sided. */
    private static final RenderPipeline GLOW_PIPELINE = RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                    .withLocation(FruitFlyMod.id("pipeline/brain_glow"))
                    .withColorTargetState(new ColorTargetState(BlendFunction.LIGHTNING))
                    .build());
    private static final RenderType GLOW = RenderType.create("fruitfly_brain_glow", RenderSetup.builder(GLOW_PIPELINE).createRenderSetup());

    private static boolean registered = false;
    private static Cloud cloud;
    private static Connectome cloudSource;
    private static long lastSeq = -1;
    private static int lastFlyId = -1;
    private static long lastFrameNanos = 0L;

    private BrainWorldOverlay() { }

    public static void register() {
        if (registered) return;
        registered = true;
        LevelRenderEvents.COLLECT_SUBMITS.register(BrainWorldOverlay::render);
    }

    /** Number of sampled somata (0 until the connectome is ready and the overlay has drawn once). */
    public static int pointCount() { return cloud == null ? 0 : cloud.n; }

    // ------------------------------------------------------------------ cached cloud

    private static final class Cloud {
        final int n;
        /** Dense neuron index per point. */
        final int[] neuron;
        /** Local coordinates per point (right, up, forward), blocks, centred on the hologram origin. */
        final float[] local;
        /** 0xRRGGBB per point. */
        final int[] rgb;
        /** Regional activity and direct-hit traces, 0..1, decayed every frame. */
        final float[] act, hit;
        /** Neuron index → point slot, or −1. */
        final int[] slotOf;
        /** CSR spatial grid over local coordinates for the neighbourhood glow. */
        final int[] cellStart, cellItems;
        final float minX, minY, minZ, cell;

        Cloud(int n, int[] neuron, float[] local, int[] rgb, int[] slotOf, int[] cellStart, int[] cellItems,
              float minX, float minY, float minZ, float cell) {
            this.n = n;
            this.neuron = neuron;
            this.local = local;
            this.rgb = rgb;
            this.slotOf = slotOf;
            this.cellStart = cellStart;
            this.cellItems = cellItems;
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.cell = cell;
            this.act = new float[n];
            this.hit = new float[n];
        }
    }

    private static Cloud cloud(Connectome c) {
        if (cloud != null && cloudSource == c) return cloud;
        cloud = build(c);
        cloudSource = c;
        lastSeq = -1;
        return cloud;
    }

    private static Cloud build(Connectome c) {
        int total = 0;
        for (int i = 0; i < c.n; i++) if (c.hasSoma(i)) total++;
        int want = Math.min(SAMPLE, total);
        int[] neuron = new int[Math.max(1, want)];
        float[] raw = new float[3 * Math.max(1, want)];
        int k = 0;
        if (want > 0) {
            double stride = total / (double) want, next = 0;
            int seen = 0;
            for (int i = 0; i < c.n && k < want; i++) {
                if (!c.hasSoma(i)) continue;
                if (seen >= next) {
                    neuron[k] = i;
                    raw[3 * k] = c.soma[3 * i];
                    raw[3 * k + 1] = c.soma[3 * i + 1];
                    raw[3 * k + 2] = c.soma[3 * i + 2];
                    k++;
                    next += stride;
                }
                seen++;
            }
        }
        int n = k;
        float[] mn = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        float[] mx = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (int i = 0; i < n; i++) {
            for (int a = 0; a < 3; a++) {
                mn[a] = Math.min(mn[a], raw[3 * i + a]);
                mx[a] = Math.max(mx[a], raw[3 * i + a]);
            }
        }
        float extent = n == 0 ? 1f : Math.max(1e-3f, Math.max(mx[0] - mn[0], Math.max(mx[1] - mn[1], mx[2] - mn[2])));
        float scale = SIZE_BLOCKS / extent;
        float cx = n == 0 ? 0 : (mn[0] + mx[0]) * 0.5f, cy = n == 0 ? 0 : (mn[1] + mx[1]) * 0.5f, cz = n == 0 ? 0 : (mn[2] + mx[2]) * 0.5f;
        somaCentre[0] = cx;
        somaCentre[1] = cy;
        somaCentre[2] = cz;
        somaScale = scale;
        float[] local = new float[3 * Math.max(1, n)];
        int[] rgb = new int[Math.max(1, n)];
        int[] slotOf = new int[c.n];
        Arrays.fill(slotOf, -1);
        for (int i = 0; i < n; i++) {
            local[3 * i] = -(raw[3 * i] - cx) * scale;         // right  = −voxel x
            local[3 * i + 1] = -(raw[3 * i + 1] - cy) * scale; // up     = −voxel y
            local[3 * i + 2] = -(raw[3 * i + 2] - cz) * scale; // forward = −voxel z
            rgb[i] = colorOf(c, neuron[i]);
            slotOf[neuron[i]] = i;
        }
        // CSR grid
        float half = SIZE_BLOCKS * 0.5f + 1e-3f;
        float cell = (2 * half) / GRID;
        int cells = GRID * GRID * GRID;
        int[] counts = new int[cells + 1];
        int[] cellIdx = new int[Math.max(1, n)];
        for (int i = 0; i < n; i++) {
            int gx = Mth.clamp((int) ((local[3 * i] + half) / cell), 0, GRID - 1);
            int gy = Mth.clamp((int) ((local[3 * i + 1] + half) / cell), 0, GRID - 1);
            int gz = Mth.clamp((int) ((local[3 * i + 2] + half) / cell), 0, GRID - 1);
            cellIdx[i] = (gx * GRID + gy) * GRID + gz;
            counts[cellIdx[i] + 1]++;
        }
        for (int i = 0; i < cells; i++) counts[i + 1] += counts[i];
        int[] items = new int[Math.max(1, n)];
        int[] fill = Arrays.copyOf(counts, counts.length);
        for (int i = 0; i < n; i++) items[fill[cellIdx[i]]++] = i;
        FruitFlyMod.LOGGER.info("Brain overlay: sampled {} of {} somata (extent {} voxels -> {} blocks)", n, total, (int) extent, SIZE_BLOCKS);
        return new Cloud(n, neuron, local, rgb, slotOf, counts, items, -half, -half, -half, cell);
    }

    private static int colorOf(Connectome c, int i) {
        String sc = c.superclass(i);
        String cl = c.neuronClass(i);
        if (sc.startsWith("descending")) return 0x5B9BFF;
        if (sc.contains("motor") || sc.contains("efferent")) return 0xFFA040;
        if (sc.contains("sensory")) return 0x55D66E;
        if (sc.startsWith("visual") || sc.equals("ol_intrinsic")) return 0x3FB8B0;
        if (sc.startsWith("ascending")) return 0xE0C060;
        if (sc.startsWith("vnc")) return 0xC08A60;
        if (sc.equals("cb_intrinsic")) {
            if (cl.equals("Kenyon_Cell") || cl.equals("ALPN") || cl.equals("MBON") || cl.equals("DAN") || cl.equals("APL")) return 0xB98CFF;
            if (cl.equals("CX")) return 0xFF7FB8;
            return 0x8FA3D9;
        }
        return 0x909090;
    }

    // ------------------------------------------------------------------ activity

    private static void inject(Cloud cl, Connectome c, int[] sample) {
        if (sample == null || cl.n == 0) return;
        float radius = cl.cell * 1.5f, r2 = radius * radius;
        for (int id : sample) {
            if (id < 0 || id >= cl.slotOf.length) continue;
            int slot = cl.slotOf[id];
            if (slot >= 0) {
                cl.act[slot] = 1f;
                cl.hit[slot] = 1f;
            }
            if (!c.hasSoma(id)) continue;
            // regional glow around the spiking neuron's soma (converted to local hologram coordinates)
            float sx = -(c.soma[3 * id] - somaCentre[0]) * somaScale;
            float sy = -(c.soma[3 * id + 1] - somaCentre[1]) * somaScale;
            float sz = -(c.soma[3 * id + 2] - somaCentre[2]) * somaScale;
            int gx = Mth.clamp((int) ((sx - cl.minX) / cl.cell), 0, GRID - 1);
            int gy = Mth.clamp((int) ((sy - cl.minY) / cl.cell), 0, GRID - 1);
            int gz = Mth.clamp((int) ((sz - cl.minZ) / cl.cell), 0, GRID - 1);
            for (int dx = -1; dx <= 1; dx++) {
                int x = gx + dx;
                if (x < 0 || x >= GRID) continue;
                for (int dy = -1; dy <= 1; dy++) {
                    int y = gy + dy;
                    if (y < 0 || y >= GRID) continue;
                    for (int dz = -1; dz <= 1; dz++) {
                        int z = gz + dz;
                        if (z < 0 || z >= GRID) continue;
                        int cell = (x * GRID + y) * GRID + z;
                        for (int q = cl.cellStart[cell]; q < cl.cellStart[cell + 1]; q++) {
                            int j = cl.cellItems[q];
                            float ddx = cl.local[3 * j] - sx, ddy = cl.local[3 * j + 1] - sy, ddz = cl.local[3 * j + 2] - sz;
                            float d2 = ddx * ddx + ddy * ddy + ddz * ddz;
                            if (d2 > r2) continue;
                            float glow = 0.75f * (1f - (float) Math.sqrt(d2 / r2));
                            if (glow > cl.act[j]) cl.act[j] = glow;
                        }
                    }
                }
            }
        }
    }

    /** Centre/scale used to map any soma (not only sampled ones) into hologram space; set in {@link #build}. */
    private static final float[] somaCentre = new float[3];
    private static float somaScale = 1f;

    // ------------------------------------------------------------------ render

    private static void render(LevelRenderContext ctx) {
        boolean neuroscope = NeuroscopeHud.isVisible();
        if (!neuroscope && !com.fruitfly.client.hud.BrainViewHud.isVisible()) return;
        FlyEntity fly = com.fruitfly.client.hud.FlyFocus.current();
        if (fly == null) return;
        drawFocusMarker(ctx, fly);
        if (!neuroscope) return;
        if (!FruitFlyMod.BRAIN.ready()) return;
        Connectome c = FruitFlyMod.BRAIN.connectome();
        if (c == null) return;
        Cloud cl = cloud(c);
        if (cl.n == 0) return;

        long now = System.nanoTime();
        float dt = lastFrameNanos == 0 ? 0f : Math.min(0.25f, (now - lastFrameNanos) / 1e9f);
        lastFrameNanos = now;
        float actDecay = (float) Math.exp(-dt / ACT_TAU_S), hitDecay = (float) Math.exp(-dt / HIT_TAU_S);
        for (int i = 0; i < cl.n; i++) {
            cl.act[i] *= actDecay;
            cl.hit[i] *= hitDecay;
        }
        if (fly.getId() != lastFlyId) {
            lastFlyId = fly.getId();
            lastSeq = -1;
            Arrays.fill(cl.act, 0f);
            Arrays.fill(cl.hit, 0f);
        }
        TelemetryStore.Entry entry = TelemetryStore.get(fly.getId());
        if (entry != null && entry.seq() != lastSeq) {
            lastSeq = entry.seq();
            BrainTelemetryPayload p = entry.latest();
            if (p != null) inject(cl, c, p.spikeSample());
        }

        float pt = partialTick();
        Vec3 origin = fly.getPosition(pt).add(0, fly.getBbHeight() + HOVER, 0);
        CameraRenderState camera = ctx.levelState().cameraRenderState;
        Vec3 cam = camera.pos;
        if (origin.distanceToSqr(cam) > MAX_DISTANCE * MAX_DISTANCE) return;
        float yaw = Mth.rotLerp(pt, fly.yBodyRotO, fly.yBodyRot);
        Vec3 fwd = WorldSenses.forward(yaw), rgt = WorldSenses.right(yaw);
        float ox = (float) (origin.x - cam.x), oy = (float) (origin.y - cam.y), oz = (float) (origin.z - cam.z);
        float fx = (float) fwd.x, fz = (float) fwd.z, rx = (float) rgt.x, rz = (float) rgt.z;
        Vector3f left = cameraLeft(camera), up = cameraUp(camera);

        // the geometry is built when the submit is drawn, later this frame; act/hit only change in the next collect
        ctx.submitNodeCollector().submitCustomGeometry(ctx.poseStack(), GLOW, (pose, bb) -> drawCloud(cl, pose, bb, left, up, ox, oy, oz, fx, fz, rx, rz));
    }

    private static void drawCloud(Cloud cl, PoseStack.Pose pose, VertexConsumer bb, Vector3f left, Vector3f up,
                                  float ox, float oy, float oz, float fx, float fz, float rx, float rz) {
        for (int i = 0; i < cl.n; i++) {
            float lx = cl.local[3 * i], ly = cl.local[3 * i + 1], lz = cl.local[3 * i + 2];
            float x = ox + rx * lx + fx * lz;
            float y = oy + ly;
            float z = oz + rz * lx + fz * lz;
            float a = cl.act[i], h = cl.hit[i];
            float s = 0.010f + 0.028f * a;
            int base = cl.rgb[i];
            int r = (base >> 16) & 255, gg = (base >> 8) & 255, b = base & 255;
            // brighten with regional activity, blend toward yellow on a direct spike
            r = (int) Mth.lerp(h, Math.min(255, r + 120 * a), 255);
            gg = (int) Mth.lerp(h, Math.min(255, gg + 120 * a), 235);
            b = (int) Mth.lerp(h, Math.min(255, b + 60 * a), 80);
            int alpha = (int) (36 + 200 * Math.max(a, h));
            float lxs = left.x * s, lys = left.y * s, lzs = left.z * s;
            float uxs = up.x * s, uys = up.y * s, uzs = up.z * s;
            bb.addVertex(pose, x - lxs - uxs, y - lys - uys, z - lzs - uzs).setColor(r, gg, b, alpha);
            bb.addVertex(pose, x + lxs - uxs, y + lys - uys, z + lzs - uzs).setColor(r, gg, b, alpha);
            bb.addVertex(pose, x + lxs + uxs, y + lys + uys, z + lzs + uzs).setColor(r, gg, b, alpha);
            bb.addVertex(pose, x - lxs + uxs, y - lys + uys, z - lzs + uzs).setColor(r, gg, b, alpha);
        }
    }

    private static float partialTick() {
        return Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(true);
    }

    /** Camera basis vectors, as {@code Camera} derives them from its orientation. */
    private static Vector3f cameraLeft(CameraRenderState camera) { return camera.orientation.transform(new Vector3f(-1f, 0f, 0f)); }

    private static Vector3f cameraUp(CameraRenderState camera) { return camera.orientation.transform(new Vector3f(0f, 1f, 0f)); }

    // ------------------------------------------------------------------ focus marker

    /**
     * Identity marker above the focused fly: a slowly rotating ring of glowing points plus a short beam, in the fly's
     * identity colour (the same colour as its name tag and the HUD panels), so it is always clear whose brain is shown.
     */
    private static void drawFocusMarker(LevelRenderContext ctx, FlyEntity fly) {
        float pt = partialTick();
        CameraRenderState camera = ctx.levelState().cameraRenderState;
        Vec3 cam = camera.pos;
        Vec3 base = fly.getPosition(pt).add(0, fly.getBbHeight() + 0.12, 0);
        if (base.distanceToSqr(cam) > 96 * 96) return;
        int rgb = fly.getFlyColor();
        int r = (rgb >> 16) & 255, gg = (rgb >> 8) & 255, b = rgb & 255;
        double t = (System.nanoTime() - T0) / 1e9;
        float pulse = 0.7f + 0.3f * (float) Math.sin(t * Math.PI * 1.5);
        float scale = Math.max(0.6f, fly.getFlyScale());
        float ringR = 0.26f * scale, ringY = 0.45f * scale;

        Vector3f left = cameraLeft(camera), up = cameraUp(camera);
        float ox = (float) (base.x - cam.x), oy = (float) (base.y - cam.y), oz = (float) (base.z - cam.z);
        ctx.submitNodeCollector().submitCustomGeometry(ctx.poseStack(), GLOW, (pose, bb) -> {
            int segments = 32;
            for (int i = 0; i < segments; i++) {
                double a = t * 1.3 + i * (2 * Math.PI / segments);
                float px = ox + ringR * (float) Math.cos(a), pz = oz + ringR * (float) Math.sin(a);
                float wobble = 0.02f * (float) Math.sin(a * 3 + t * 4);
                billboard(bb, pose, left, up, px, oy + ringY + wobble, pz, 0.028f * scale, r, gg, b, (int) (210 * pulse));
            }
            for (int i = 0; i < 10; i++) {
                float f = i / 9f;
                billboard(bb, pose, left, up, ox, oy + f * ringY, oz, 0.016f * scale, r, gg, b, (int) (140 * pulse * (1f - 0.5f * f)));
            }
        });
    }

    private static void billboard(VertexConsumer bb, PoseStack.Pose pose, Vector3f left, Vector3f up, float x, float y, float z, float s,
                                  int r, int g, int b, int a) {
        float lxs = left.x * s, lys = left.y * s, lzs = left.z * s;
        float uxs = up.x * s, uys = up.y * s, uzs = up.z * s;
        bb.addVertex(pose, x - lxs - uxs, y - lys - uys, z - lzs - uzs).setColor(r, g, b, a);
        bb.addVertex(pose, x + lxs - uxs, y + lys - uys, z + lzs - uzs).setColor(r, g, b, a);
        bb.addVertex(pose, x + lxs + uxs, y + lys + uys, z + lzs + uzs).setColor(r, g, b, a);
        bb.addVertex(pose, x - lxs + uxs, y - lys + uys, z - lzs + uzs).setColor(r, g, b, a);
    }
}
