package com.fruitfly.client;

import com.fruitfly.client.hud.BrainViewHud;
import com.fruitfly.client.hud.FlyFocus;
import com.fruitfly.client.hud.NeuroscopeHud;
import com.fruitfly.client.hud.TelemetryStore;
import com.fruitfly.entity.FlyEntity;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/**
 * Client-side /brainview commands: pin the live brain map, choose and lock the fly it shows.
 *
 * <pre>
 * /brainview                 toggle the pinned brain map
 * /brainview on | off
 * /brainview lock            lock onto the fly currently shown
 * /brainview lock &lt;number&gt;   lock onto Fly-&lt;number&gt; (the number on its name tag; falls back to the network id)
 * /brainview look            lock onto the fly under the crosshair (up to 48 blocks away)
 * /brainview nearest         back to automatic nearest-fly selection
 * /brainview view dorsal|frontal|side
 * /brainview size &lt;0.15..0.75&gt;   maximum panel height as a fraction of the screen
 * /brainview list            flies nearby: number, name colour, distance, telemetry, focus
 * </pre>
 */
public final class BrainViewCommands {
    private BrainViewCommands() { }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                literal("brainview")
                        .executes(ctx -> { BrainViewHud.toggle(); return feedback(ctx, "Brain view " + (BrainViewHud.isVisible() ? "on" : "off")); })
                        .then(literal("on").executes(ctx -> { BrainViewHud.setVisible(true); return feedback(ctx, "Brain view on"); }))
                        .then(literal("off").executes(ctx -> { BrainViewHud.setVisible(false); return feedback(ctx, "Brain view off"); }))
                        .then(literal("lock")
                                .executes(ctx -> {
                                    if (!FlyFocus.lockCurrent()) return error(ctx, "No fly is currently shown; get within " + (int) FlyFocus.RANGE + " blocks of one or use /brainview lock <number>");
                                    BrainViewHud.setVisible(true);
                                    return feedback(ctx, "Locked on " + name(FlyFocus.current()));
                                })
                                .then(argument("fly", IntegerArgumentType.integer(0)).executes(ctx -> {
                                    int no = IntegerArgumentType.getInteger(ctx, "fly");
                                    FlyEntity f = FlyFocus.find(no);
                                    if (f == null) return error(ctx, "No loaded fly with number or id " + no + " (see /brainview list)");
                                    FlyFocus.lock(f.getId());
                                    BrainViewHud.setVisible(true);
                                    return feedback(ctx, "Locked on " + name(f));
                                })))
                        .then(literal("look").executes(ctx -> {
                            if (!FlyFocus.lockLooked()) return error(ctx, "Point the crosshair at a fly first");
                            BrainViewHud.setVisible(true);
                            return feedback(ctx, "Locked on " + name(FlyFocus.current()));
                        }))
                        .then(literal("nearest").executes(ctx -> { FlyFocus.unlock(); return feedback(ctx, "Following the nearest fly"); }))
                        .then(literal("view")
                                .then(literal("dorsal").executes(ctx -> view(ctx, BrainViewHud.View.DORSAL)))
                                .then(literal("frontal").executes(ctx -> view(ctx, BrainViewHud.View.FRONTAL)))
                                .then(literal("side").executes(ctx -> view(ctx, BrainViewHud.View.SIDE))))
                        .then(literal("size").then(argument("fraction", FloatArgumentType.floatArg(0.15f, 0.75f)).executes(ctx -> {
                            BrainViewHud.setSize(FloatArgumentType.getFloat(ctx, "fraction"));
                            return feedback(ctx, String.format(Locale.ROOT, "Brain view: panel up to %.0f%% of the screen height", BrainViewHud.size() * 100));
                        })))
                        .then(literal("list").executes(BrainViewCommands::list))
                        .then(literal("neuroscope").executes(ctx -> { NeuroscopeHud.toggle(); return feedback(ctx, "Neuroscope " + (NeuroscopeHud.isVisible() ? "on" : "off")); }))));
    }

    private static int view(CommandContext<FabricClientCommandSource> ctx, BrainViewHud.View v) {
        BrainViewHud.setView(v);
        BrainViewHud.setVisible(true);
        return feedback(ctx, "Brain view: " + v.name().toLowerCase(Locale.ROOT));
    }

    private static int list(CommandContext<FabricClientCommandSource> ctx) {
        Minecraft mc = Minecraft.getInstance();
        List<FlyEntity> flies = FlyFocus.nearby(128);
        if (flies.isEmpty()) return feedback(ctx, "No flies loaded within 128 blocks");
        FlyEntity cur = FlyFocus.current();
        // one line per fly, the name in the fly's identity colour (N + 1 chat lines, not 2N + 1)
        ctx.getSource().sendFeedback(Component.literal("Flies (" + flies.size() + "):"));
        for (FlyEntity f : flies) {
            String detail = String.format(Locale.ROOT, "  id %d  %.1f m  %s%s%s", f.getId(),
                    mc.player == null ? 0 : f.distanceTo(mc.player),
                    TelemetryStore.hasFresh(f.getId()) ? "telemetry" : (f.hasBrain() ? "brain, no telemetry yet" : "no brain (reflex body)"),
                    f == cur ? "  <- shown" : "",
                    FlyFocus.isLocked() && FlyFocus.lockedId() == f.getId() ? " [LOCKED]" : "");
            ctx.getSource().sendFeedback(Component.literal("■ " + f.flyName()).withColor(f.getFlyColor())
                    .append(Component.literal(detail).withStyle(ChatFormatting.GRAY)));
        }
        return flies.size();
    }

    private static String name(FlyEntity f) { return f == null ? "?" : f.flyName() + " (id " + f.getId() + ")"; }

    private static int feedback(CommandContext<FabricClientCommandSource> ctx, String msg) {
        ctx.getSource().sendFeedback(Component.literal(msg));
        return 1;
    }

    private static int error(CommandContext<FabricClientCommandSource> ctx, String msg) {
        ctx.getSource().sendError(Component.literal(msg));
        return 0;
    }
}
