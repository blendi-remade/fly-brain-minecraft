package com.fruitfly.server;

import com.fruitfly.FlyBrainService;
import com.fruitfly.FruitFlyMod;
import com.fruitfly.brain.BrainRunner;
import com.fruitfly.brain.MotorDecoder;
import com.fruitfly.brain.PopulationIndex;
import com.fruitfly.entity.FlyEntity;
import com.fruitfly.entity.WorldSenses;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;

/**
 * /fruitfly commands: spawn, stats, stim, watch, feed, loom, groom, kill, pause.
 */
public final class FruitFlyCommands {
    private FruitFlyCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("fruitfly")
                .then(Commands.literal("spawn")
                        .executes(ctx -> spawn(ctx, true, 1, 1f))
                        .then(Commands.literal("male").executes(ctx -> spawn(ctx, true, 1, 1f))
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 8)).executes(ctx -> spawn(ctx, true, IntegerArgumentType.getInteger(ctx, "count"), 1f))))
                        .then(Commands.literal("female").executes(ctx -> spawn(ctx, false, 1, 1f))
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 8)).executes(ctx -> spawn(ctx, false, IntegerArgumentType.getInteger(ctx, "count"), 1f))))
                        .then(Commands.literal("big").executes(ctx -> spawn(ctx, true, 1, 2.5f))))
                .then(Commands.literal("stats").executes(FruitFlyCommands::stats))
                .then(Commands.literal("stim")
                        .then(Commands.argument("population", StringArgumentType.string())
                                .then(Commands.argument("hz", DoubleArgumentType.doubleArg(0, 500))
                                        .executes(ctx -> stim(ctx, 2f))
                                        .then(Commands.argument("seconds", FloatArgumentType.floatArg(0.05f, 600f))
                                                .executes(ctx -> stim(ctx, FloatArgumentType.getFloat(ctx, "seconds")))))))
                .then(Commands.literal("watch")
                        .then(Commands.argument("population", StringArgumentType.string()).executes(FruitFlyCommands::watch)))
                .then(Commands.literal("feed").executes(ctx -> demo(ctx, "feed")))
                .then(Commands.literal("bitter").executes(ctx -> demo(ctx, "bitter")))
                .then(Commands.literal("loom").executes(ctx -> demo(ctx, "loom")))
                .then(Commands.literal("groom").executes(ctx -> demo(ctx, "groom")))
                .then(Commands.literal("odor").executes(ctx -> demo(ctx, "odor")))
                .then(Commands.literal("pause").executes(ctx -> pause(ctx, true)))
                .then(Commands.literal("resume").executes(ctx -> pause(ctx, false)))
                .then(Commands.literal("kill").executes(FruitFlyCommands::kill))
                .then(Commands.literal("senses").executes(FruitFlyCommands::senses)));
    }

    private static List<FlyEntity> flies(CommandSourceStack src, double radius) {
        return src.getLevel().getEntitiesOfClass(FlyEntity.class, AABB.ofSize(src.getPosition(), radius * 2, radius * 2, radius * 2), e -> true);
    }

    private static int spawn(CommandContext<CommandSourceStack> ctx, boolean male, int count, float scale) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        Vec3 p = src.getPosition();
        int spawned = 0;
        for (int i = 0; i < count; i++) {
            FlyEntity fly = FruitFlyMod.FRUIT_FLY.create(level, EntitySpawnReason.COMMAND);
            if (fly == null) break;
            double a = i * 2 * Math.PI / Math.max(1, count);
            fly.snapTo(p.x + Math.cos(a) * 0.8 * (count > 1 ? 1 : 0), p.y + 0.5, p.z + Math.sin(a) * 0.8 * (count > 1 ? 1 : 0), level.getRandom().nextFloat() * 360f, 0f);
            fly.setMale(male);
            fly.setFlyScale(scale);
            // assigns the persistent fly number + coloured name tag before the spawn packet, so clients never see "Fly-?"
            fly.finalizeSpawn(level, level.getCurrentDifficultyAt(fly.blockPosition()), EntitySpawnReason.COMMAND, null);
            level.addFreshEntity(fly);
            spawned++;
        }
        FlyBrainService svc = FruitFlyMod.BRAIN;
        String brainMsg = svc.ready() ? String.format(Locale.ROOT, "brains %d/%d in use", svc.activeBrains(), svc.maxBrains())
                : (svc.loadError() != null ? "connectome FAILED to load: " + svc.loadError() : "connectome still loading...");
        final int n = spawned;
        src.sendSuccess(() -> Component.literal("Spawned " + n + (male ? " male" : " female") + " fruit fl" + (n == 1 ? "y" : "ies") + " (" + brainMsg + ")"), false);
        return n;
    }

    private static int stats(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        FlyBrainService svc = FruitFlyMod.BRAIN;
        StringBuilder sb = new StringBuilder();
        if (!svc.ready()) {
            sb.append("Connectome: ").append(svc.loadError() != null ? "FAILED: " + svc.loadError() : "loading...").append('\n');
        } else {
            sb.append(String.format(Locale.ROOT, "Connectome: %s\nBrains: %d/%d, %s\n", svc.connectome(), svc.activeBrains(), svc.maxBrains(), svc.lifConfig()));
        }
        List<FlyEntity> flies = flies(src, 64);
        sb.append("Flies within 64 blocks: ").append(flies.size());
        for (FlyEntity f : flies) {
            BrainRunner b = f.brain();
            sb.append(String.format(Locale.ROOT, "\n #%d %s scale=%.1f hunger=%.2f %s", f.getId(), f.isMale() ? "male" : "female", f.getFlyScale(), f.getHunger(),
                    b == null ? "(no brain: reflex body)" : String.format(Locale.ROOT, "brain tick %d, %d spikes/tick, %d active, %.2fx realtime, %s%s",
                            b.snapshot().tick, b.snapshot().spikesThisTick, b.snapshot().activeNeurons, b.realTimeFactor(), f.latestCommand(),
                            f.isReflexDriving() ? " [reflex]" : "")));
        }
        src.sendSuccess(() -> Component.literal(sb.toString()), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int stim(CommandContext<CommandSourceStack> ctx, float seconds) {
        CommandSourceStack src = ctx.getSource();
        String spec = StringArgumentType.getString(ctx, "population");
        double hz = DoubleArgumentType.getDouble(ctx, "hz");
        int ticks = Math.max(1, Math.round(seconds * 20));
        List<FlyEntity> flies = flies(src, 32);
        int n = 0;
        for (FlyEntity f : flies) {
            if (f.brain() == null) continue;
            int[] ids = resolveOrFail(src, f.brain().populations, spec);
            if (ids == null) return 0;
            if (ids.length == 0) {
                src.sendFailure(Component.literal("Population '" + spec + "' matched no neurons"));
                return 0;
            }
            f.stimulate(spec, hz, ticks);
            f.watch(spec);
            n++;
        }
        final int m = n;
        src.sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "Stimulating %s at %.0f Hz for %.1f s in %d fly brain(s)", spec, hz, seconds, m)), false);
        return m;
    }

    private static int watch(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        String spec = StringArgumentType.getString(ctx, "population");
        int n = 0;
        for (FlyEntity f : flies(src, 32)) {
            if (f.brain() != null) {
                // validate on the game thread (and warm this runner's cache) so a typo is reported here, not on the brain thread
                if (resolveOrFail(src, f.brain().populations, spec) == null) return 0;
                f.watch(spec);
                n++;
            }
        }
        final int m = n;
        src.sendSuccess(() -> Component.literal("Watching " + spec + " in " + m + " brain(s)"), false);
        return m;
    }

    /** Resolve a population spec on the game thread; reports a bad spec to the player and returns null. */
    private static int[] resolveOrFail(CommandSourceStack src, PopulationIndex pi, String spec) {
        try {
            return pi.resolve(spec);
        } catch (IllegalArgumentException e) { // includes NumberFormatException from body:<id>
            src.sendFailure(Component.literal("Bad population spec '" + spec + "': " + e.getMessage()));
            return null;
        }
    }

    /** Canned demos of the validated pathways. */
    private static int demo(CommandContext<CommandSourceStack> ctx, String which) {
        CommandSourceStack src = ctx.getSource();
        int n = 0;
        for (FlyEntity f : flies(src, 32)) {
            if (f.brain() == null) continue;
            switch (which) {
                case "feed" -> {
                    f.stimulate("LB3b,LB3c", 120, 60);
                    f.stimulate("PhG1a,PhG1b,PhG1c", 100, 60);
                    f.stimulate("LgLG3", 80, 60);
                    f.watch("MN9");
                    f.watch("GNG232");
                }
                case "bitter" -> {
                    f.stimulate("LB1a,LB1b,LB1c,LB1d", 120, 60);
                    f.watch("GNG087");
                }
                case "loom" -> {
                    f.stimulate("LC4/R,LPLC2/R", 150, 8);
                    f.watch("DNp01");
                }
                case "groom" -> {
                    f.stimulate("subclass:wind_gravity,subclass:grooming", 120, 40);
                    f.watch("DNg62");
                    f.watch("DNge078");
                }
                case "odor" -> {
                    f.stimulate("prefix:ORN_DM1,prefix:ORN_VA2", 40, 60);
                    f.watch("DM1_lPN");
                }
                default -> { }
            }
            n++;
        }
        final int m = n;
        src.sendSuccess(() -> Component.literal("Demo '" + which + "' started in " + m + " brain(s); open the neuroscope (H) to watch"), false);
        return m;
    }

    private static int pause(CommandContext<CommandSourceStack> ctx, boolean paused) {
        CommandSourceStack src = ctx.getSource();
        int n = 0;
        for (FlyEntity f : flies(src, 64)) {
            if (f.brain() != null) {
                f.brain().setPaused(paused);
                n++;
            }
        }
        final int m = n;
        src.sendSuccess(() -> Component.literal((paused ? "Paused " : "Resumed ") + m + " brain(s)"), false);
        return m;
    }

    private static int kill(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        List<FlyEntity> flies = flies(src, 64);
        for (FlyEntity f : flies) f.discard();
        src.sendSuccess(() -> Component.literal("Removed " + flies.size() + " fruit flies"), false);
        return flies.size();
    }

    private static int senses(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        StringBuilder sb = new StringBuilder();
        for (FlyEntity f : flies(src, 32)) {
            var fr = f.lastFrame();
            if (fr == null) continue;
            sb.append(String.format(Locale.ROOT, "#%d mode=%s odor=%s bearing=%.0f taste=%s wind=%.2f/%.2f objects=%d dmg=%.2f%n",
                    f.getId(), f.getMode(), WorldSenses.describeOdor(fr), fr.odorBearingDeg, fr.taste.keySet(), fr.windLeft, fr.windRight,
                    fr.objects.size(), fr.damage));
        }
        if (sb.length() == 0) sb.append("No flies nearby");
        src.sendSuccess(() -> Component.literal(sb.toString()), false);
        return Command.SINGLE_SUCCESS;
    }
}
