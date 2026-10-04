package com.fruitfly.server;

import com.fruitfly.FruitFlyMod;
import com.fruitfly.brain.BrainRunner;
import com.fruitfly.brain.Connectome;
import com.fruitfly.entity.FlyEntity;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Puts the connectome into the world literally: every neuron with a reconstructed soma (141,781) is binned onto a
 * voxel grid that fits inside {@code size} blocks and placed as a stained-glass block coloured by superclass
 * (optic lobe cyan, central brain white/magenta, VNC lime, sensory yellow, descending red, motor orange, ascending
 * light blue). When linked to a living fly, the cells whose neurons spiked in the last brain tick flash as sea
 * lanterns for one tick.
 *
 * <p>Blocks are placed progressively (a few thousand per server tick) so the server never stalls. Clearing restores
 * air (the structure remembers which positions it owns).</p>
 */
public final class BrainBuilder {
    /** One built brain structure. */
    public static final class Structure {
        public final ServerLevel level;
        public final BlockPos origin;
        public final int size;
        final Map<BlockPos, Byte> cells = new HashMap<>();      // owned block -> superclass class id
        final int[] neuronCell;                                  // dense neuron index -> cell key (packed) or -1
        final Map<Integer, BlockPos> cellPos = new HashMap<>();  // packed cell key -> position
        final ArrayDeque<BlockPos> placeQueue = new ArrayDeque<>();
        final List<BlockPos> flashed = new ArrayList<>();
        FlyEntity linked;
        long lastFlashTick = -1;
        boolean clearing;

        Structure(ServerLevel level, BlockPos origin, int size, int n) {
            this.level = level;
            this.origin = origin;
            this.size = size;
            this.neuronCell = new int[n];
            java.util.Arrays.fill(neuronCell, -1);
        }

        public int totalCells() { return cells.size(); }
        public int remaining() { return placeQueue.size(); }
        public boolean isBuilding() { return !placeQueue.isEmpty() && !clearing; }
    }

    private static final List<Structure> STRUCTURES = new ArrayList<>();
    private static boolean tickHookRegistered;
    private static final int BLOCKS_PER_TICK = 2500;

    private BrainBuilder() {}

    /** Superclass → colour class id. */
    static byte classOf(Connectome c, int i) {
        String sc = c.superclass(i);
        if (sc.startsWith("ol_")) return 1;                 // optic lobe
        if (sc.equals("descending_neuron")) return 5;
        if (sc.endsWith("_motor") || sc.endsWith("_efferent")) return 6;
        if (sc.contains("sensory")) return 4;
        if (sc.startsWith("ascending")) return 7;
        if (sc.startsWith("vnc_")) return 3;
        if (sc.startsWith("visual_")) return 8;
        if (sc.startsWith("cb_")) return 2;
        return 0;
    }

    static Block blockFor(byte cls) {
        return switch (cls) {
            case 1 -> Blocks.STAINED_GLASS.cyan();
            case 2 -> Blocks.STAINED_GLASS.white();
            case 3 -> Blocks.STAINED_GLASS.lime();
            case 4 -> Blocks.STAINED_GLASS.yellow();
            case 5 -> Blocks.STAINED_GLASS.red();
            case 6 -> Blocks.STAINED_GLASS.orange();
            case 7 -> Blocks.STAINED_GLASS.lightBlue();
            case 8 -> Blocks.STAINED_GLASS.magenta();
            default -> Blocks.STAINED_GLASS.gray();
        };
    }

    /**
     * Plan a structure whose longest axis spans {@code size} blocks, centred horizontally on {@code origin} and
     * standing on it. Placement happens over the following ticks.
     */
    public static Structure build(ServerLevel level, BlockPos origin, int size, Connectome c) {
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (int i = 0; i < c.n; i++) {
            if (!c.hasSoma(i)) continue;
            float x = c.soma[3 * i], y = c.soma[3 * i + 1], z = c.soma[3 * i + 2];
            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
            minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
        }
        double span = Math.max(maxX - minX, Math.max(maxY - minY, maxZ - minZ));
        double scale = (size - 1) / Math.max(1e-6, span);
        // neuPrint frame: y grows ventrally, the CNS long axis (brain -> nerve cord) is roughly along y; stand it up.
        int h = (int) Math.ceil((maxY - minY) * scale) + 1;
        int w = (int) Math.ceil((maxX - minX) * scale) + 1;
        int d = (int) Math.ceil((maxZ - minZ) * scale) + 1;
        Structure s = new Structure(level, origin, size, c.n);
        Map<Integer, int[]> votes = new HashMap<>(); // cell key -> counts per class
        for (int i = 0; i < c.n; i++) {
            if (!c.hasSoma(i)) continue;
            int gx = (int) ((c.soma[3 * i] - minX) * scale);
            int gy = h - 1 - (int) ((c.soma[3 * i + 1] - minY) * scale); // flip so dorsal (low y) is up
            int gz = (int) ((c.soma[3 * i + 2] - minZ) * scale);
            int key = (gx << 20) | (gy << 10) | gz;
            s.neuronCell[i] = key;
            int[] v = votes.computeIfAbsent(key, k -> new int[9]);
            v[classOf(c, i)]++;
        }
        int bx = origin.getX() - w / 2, by = origin.getY(), bz = origin.getZ() - d / 2;
        for (Map.Entry<Integer, int[]> e : votes.entrySet()) {
            int key = e.getKey();
            int gx = key >>> 20, gy = (key >>> 10) & 1023, gz = key & 1023;
            int[] v = e.getValue();
            byte best = 0;
            for (byte k = 1; k < 9; k++) if (v[k] > v[best]) best = k;
            BlockPos p = new BlockPos(bx + gx, by + gy, bz + gz);
            s.cells.put(p, best);
            s.cellPos.put(key, p);
            s.placeQueue.add(p);
        }
        synchronized (STRUCTURES) {
            STRUCTURES.add(s);
            ensureTickHook();
        }
        FruitFlyMod.LOGGER.info("Brain structure planned: {} cells ({}x{}x{} blocks) from {} somata", s.cells.size(), w, h, d, votes.size());
        return s;
    }

    /** Start clearing every structure in this level (restores air). */
    public static int clear(ServerLevel level) {
        int n = 0;
        synchronized (STRUCTURES) {
            for (Structure s : STRUCTURES) {
                if (s.level == level && !s.clearing) {
                    s.clearing = true;
                    s.placeQueue.clear();
                    s.placeQueue.addAll(s.cells.keySet());
                    n++;
                }
            }
        }
        return n;
    }

    public static List<Structure> structures(ServerLevel level) {
        List<Structure> r = new ArrayList<>();
        synchronized (STRUCTURES) {
            for (Structure s : STRUCTURES) if (s.level == level) r.add(s);
        }
        return r;
    }

    /** Link a fly so its spiking neurons flash in the structure. */
    public static void link(Structure s, FlyEntity fly) { s.linked = fly; }

    private static void ensureTickHook() {
        if (tickHookRegistered) return;
        tickHookRegistered = true;
        ServerTickEvents.END_SERVER_TICK.register(server -> tick());
    }

    private static void tick() {
        List<Structure> snapshot;
        synchronized (STRUCTURES) { snapshot = new ArrayList<>(STRUCTURES); }
        for (Structure s : snapshot) {
            // progressive placement / clearing
            int budget = BLOCKS_PER_TICK;
            while (budget-- > 0 && !s.placeQueue.isEmpty()) {
                BlockPos p = s.placeQueue.poll();
                if (s.clearing) {
                    if (s.level.getBlockState(p).getBlock() instanceof net.minecraft.world.level.block.StainedGlassBlock
                            || s.level.getBlockState(p).is(Blocks.SEA_LANTERN)) {
                        s.level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
                    }
                } else {
                    Byte cls = s.cells.get(p);
                    if (cls != null && s.level.getBlockState(p).isAir()) s.level.setBlock(p, blockFor(cls).defaultBlockState(), 2);
                }
            }
            if (s.clearing && s.placeQueue.isEmpty()) {
                synchronized (STRUCTURES) { STRUCTURES.remove(s); }
                continue;
            }
            // revert last tick's flashes
            for (BlockPos p : s.flashed) {
                Byte cls = s.cells.get(p);
                if (cls != null) s.level.setBlock(p, blockFor(cls).defaultBlockState(), 2);
            }
            s.flashed.clear();
            // flash neurons that spiked in the linked fly's last brain tick
            FlyEntity fly = s.linked;
            if (fly == null || fly.isRemoved() || fly.brain() == null || !s.placeQueue.isEmpty()) continue;
            BrainRunner.BrainSnapshot snap = fly.brain().snapshot();
            if (snap.tick == s.lastFlashTick) continue;
            s.lastFlashTick = snap.tick;
            int n = Math.min(snap.spikeLogNeurons.length, 400);
            int stride = Math.max(1, snap.spikeLogNeurons.length / Math.max(1, n));
            BlockState lantern = Blocks.SEA_LANTERN.defaultBlockState();
            for (int k = 0; k < snap.spikeLogNeurons.length && s.flashed.size() < n; k += stride) {
                int neuron = snap.spikeLogNeurons[k];
                if (neuron < 0 || neuron >= s.neuronCell.length) continue;
                int key = s.neuronCell[neuron];
                if (key < 0) continue;
                BlockPos p = s.cellPos.get(key);
                if (p == null) continue;
                s.level.setBlock(p, lantern, 2);
                s.flashed.add(p);
            }
        }
    }
}
