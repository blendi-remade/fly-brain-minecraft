package com.fruitfly.client;

import com.fruitfly.FruitFlyMod;
import com.fruitfly.client.hud.BrainViewHud;
import com.fruitfly.client.hud.FlyFocus;
import com.fruitfly.client.hud.NeuroscopeHud;
import com.fruitfly.client.hud.TelemetryStore;
import com.fruitfly.client.render.FlyModel;
import com.fruitfly.client.render.FlyRenderer;
import com.fruitfly.net.BrainTelemetryPayload;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.ModelLayerRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.renderer.entity.EntityRenderers;
import org.lwjgl.glfw.GLFW;

/**
 * Client entrypoint: fly model layer + renderer, brain-telemetry receiver, and the Neuroscope HUD toggle key (H).
 */
public final class FruitFlyClient implements ClientModInitializer {
    public static final ModelLayerLocation FLY_LAYER = new ModelLayerLocation(FruitFlyMod.id("fruit_fly"), "main");
    public static final KeyMapping.Category KEY_CATEGORY = KeyMapping.Category.register(FruitFlyMod.id("main"));

    private static KeyMapping neuroscopeKey;
    private static KeyMapping brainViewKey;

    public static KeyMapping neuroscopeKey() { return neuroscopeKey; }
    public static KeyMapping brainViewKey() { return brainViewKey; }

    @Override
    public void onInitializeClient() {
        ModelLayerRegistry.registerModelLayer(FLY_LAYER, FlyModel::createBodyLayer);
        EntityRenderers.register(FruitFlyMod.FRUIT_FLY, FlyRenderer::new);

        // S2C brain telemetry (payload type is registered in the common initializer); handlers run on the client thread
        ClientPlayNetworking.registerGlobalReceiver(BrainTelemetryPayload.TYPE,
                (payload, context) -> TelemetryStore.accept(payload));
        // Drop telemetry from a previous world immediately instead of waiting for the 5 s staleness window, and forget
        // the fly lock: it is a network id, which the next world/server hands out again to some other entity
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            TelemetryStore.clear();
            FlyFocus.reset();
            BrainViewHud.reset();
        });

        neuroscopeKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.fruitfly.neuroscope", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_H, KEY_CATEGORY));
        brainViewKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.fruitfly.brainview", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_B, KEY_CATEGORY));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (neuroscopeKey.consumeClick()) NeuroscopeHud.toggle();
            while (brainViewKey.consumeClick()) BrainViewHud.toggle();
        });
        NeuroscopeHud.register();
        BrainViewHud.register();
        BrainViewCommands.register();
        FruitFlyMod.LOGGER.info("Fruit Fly Connectome client initialised (H = neuroscope, B = brain view, /brainview)");
    }
}
