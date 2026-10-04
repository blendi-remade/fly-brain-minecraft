package com.fruitfly.client.render;

import com.fruitfly.FruitFlyMod;
import com.fruitfly.client.FruitFlyClient;
import com.fruitfly.entity.FlyEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Renders {@link FlyEntity} with {@link FlyModel}. The model is built at bee scale, so it is shrunk by
 * {@code 0.6 * flyScale} here (fly scale 1.0 = the 0.5 x 0.3 block hitbox). Body opaque via the model's default
 * {@code entityCutout}; wings translucent via {@link FlyWingLayer}; activity glow via {@link FlyGlowLayer}.
 */
public class FlyRenderer extends MobRenderer<FlyEntity, FlyRenderState, FlyModel> {
    private static final Identifier TEXTURE_MALE = FruitFlyMod.id("textures/entity/fruit_fly.png");
    private static final Identifier TEXTURE_FEMALE = FruitFlyMod.id("textures/entity/fruit_fly_female.png");
    private static final float BASE_SCALE = 0.6F;
    private static final float SHADOW_RADIUS = 0.15F;

    /** Per-entity smoothed behaviour blends (one renderer serves every fly of the type). */
    private final Map<FlyEntity, AnimState> animStates = new WeakHashMap<>();

    private static final class AnimState {
        float flight, groom, song, lastAge = Float.NaN;
        byte groomKind, songSide;
    }

    public FlyRenderer(EntityRendererProvider.Context context) {
        super(context, new FlyModel(context.bakeLayer(FruitFlyClient.FLY_LAYER)), SHADOW_RADIUS);
        addLayer(new FlyWingLayer(this, context));
        addLayer(new FlyGlowLayer(this, context));
    }

    @Override
    public FlyRenderState createRenderState() {
        return new FlyRenderState();
    }

    @Override
    public void extractRenderState(FlyEntity fly, FlyRenderState state, float partialTick) {
        state.flyScale = fly.getFlyScale();   // before super: it computes the shadow radius from it
        super.extractRenderState(fly, state, partialTick);
        state.male = fly.isMale();
        state.hasBrain = fly.hasBrain();
        state.activity = fly.getActivity();
        state.proboscis = Mth.clamp(fly.getProboscis(), 0F, 1F);
        state.flapping = fly.isFlapping();
        state.verticalSpeed = (float) fly.getDeltaMovement().y;
        state.bodyYawRate = Mth.wrapDegrees(fly.yBodyRot - fly.yBodyRotO);
        AnimState st = advance(fly, state.ageInTicks);
        state.flight = st.flight;
        state.groom = st.groom;
        state.song = st.song;
        state.groomKind = st.groomKind;
        state.songSide = st.songSide;
        state.wingBlur = state.flapping ? Math.max(st.flight, 0.5F) : st.flight;
    }

    /** Smooth the synched behaviour booleans into 0..1 blends so wings/legs do not pop between poses. */
    private AnimState advance(FlyEntity fly, float ageInTicks) {
        AnimState st = animStates.computeIfAbsent(fly, k -> new AnimState());
        float dt = Float.isNaN(st.lastAge) ? 1F : Mth.clamp(ageInTicks - st.lastAge, 0F, 1F);
        st.lastAge = ageInTicks;
        boolean flying = fly.isFlyingState() || fly.isFlapping();
        byte groom = fly.getGroomState();
        byte wing = fly.getWingExtension();
        if (groom != 0) st.groomKind = groom;
        if (wing != 0) st.songSide = wing;
        st.flight = Mth.approach(st.flight, flying ? 1F : 0F, dt * 0.35F);
        st.groom = Mth.approach(st.groom, groom != 0 && !flying ? 1F : 0F, dt * 0.2F);
        st.song = Mth.approach(st.song, wing != 0 && !flying ? 1F : 0F, dt * 0.2F);
        return st;
    }

    @Override
    protected void scale(FlyRenderState state, PoseStack poseStack) {
        float s = BASE_SCALE * state.flyScale;
        poseStack.scale(s, s, s);
    }

    @Override
    protected float getShadowRadius(FlyRenderState state) {
        return super.getShadowRadius(state) * state.flyScale;
    }

    @Override
    public Identifier getTextureLocation(FlyRenderState state) {
        return texture(state);
    }

    static Identifier texture(FlyRenderState state) {
        return state.male ? TEXTURE_MALE : TEXTURE_FEMALE;
    }
}
