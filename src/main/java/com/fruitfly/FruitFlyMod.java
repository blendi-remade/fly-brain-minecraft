package com.fruitfly;

import com.fruitfly.entity.FlyEntity;
import com.fruitfly.net.BrainTelemetryPayload;
import com.fruitfly.server.BrainCommands;
import com.fruitfly.server.FruitFlyCommands;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.SpawnEggItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

public final class FruitFlyMod implements ModInitializer {
    public static final String MOD_ID = "fruitfly";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("fruitfly.json");
    public static final FruitFlyConfig CONFIG = FruitFlyConfig.load(CONFIG_PATH);
    public static final FlyBrainService BRAIN = new FlyBrainService(CONFIG);

    public static Identifier id(String path) { return Identifier.fromNamespaceAndPath(MOD_ID, path); }

    private static final ResourceKey<EntityType<?>> FRUIT_FLY_KEY = ResourceKey.create(Registries.ENTITY_TYPE, id("fruit_fly"));
    public static final EntityType<FlyEntity> FRUIT_FLY = Registry.register(
            BuiltInRegistries.ENTITY_TYPE, FRUIT_FLY_KEY,
            EntityType.Builder.of(FlyEntity::new, MobCategory.CREATURE)
                    .sized(0.5f, 0.3f)
                    .eyeHeight(0.2f)
                    .clientTrackingRange(10)
                    .updateInterval(1)
                    .build(FRUIT_FLY_KEY));

    private static final ResourceKey<Item> FRUIT_FLY_SPAWN_EGG_KEY = ResourceKey.create(Registries.ITEM, id("fruit_fly_spawn_egg"));
    public static final Item FRUIT_FLY_SPAWN_EGG = Registry.register(
            BuiltInRegistries.ITEM, FRUIT_FLY_SPAWN_EGG_KEY,
            new SpawnEggItem(new Item.Properties().spawnEgg(FRUIT_FLY).setId(FRUIT_FLY_SPAWN_EGG_KEY)));

    @Override
    public void onInitialize() {
        LOGGER.info("Fruit Fly Connectome initialising (config {})", CONFIG_PATH);
        FabricDefaultAttributeRegistry.register(FRUIT_FLY, FlyEntity.createAttributes());
        if (CONFIG.spawnEggInCreativeTab) {
            CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.SPAWN_EGGS).register(output -> output.accept(FRUIT_FLY_SPAWN_EGG));
        }
        PayloadTypeRegistry.clientboundPlay().register(BrainTelemetryPayload.TYPE, BrainTelemetryPayload.CODEC);
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            FruitFlyCommands.register(dispatcher);
            BrainCommands.register(dispatcher);
        });

        ServerLifecycleEvents.SERVER_STARTING.register(server -> BRAIN.preload());
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> BRAIN.shutdown());
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
            if (entity instanceof FlyEntity fly) fly.releaseBrain();
        });
    }
}
