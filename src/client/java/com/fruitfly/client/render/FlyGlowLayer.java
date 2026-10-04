package com.fruitfly.client.render;

import com.fruitfly.client.FruitFlyClient;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.ARGB;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;

/**
 * Subtle "brain glow": re-draws the head (with its eyes, antennae and proboscis) full-bright through
 * {@code RenderTypes.entityTranslucentEmissive} tinted pink, with an alpha that follows the synched brain-activity
 * summary ({@link FlyRenderState#activity}, spikes per tick normalised). Flies without a brain do not glow.
 * The overlay is a head-only {@link FlyModel} posed like the body, so it shares the head's exact geometry and with the
 * LEQUAL depth test lands on top without z-fighting.
 */
public final class FlyGlowLayer extends RenderLayer<FlyRenderState, FlyModel> {
    private final FlyModel head;

    public FlyGlowLayer(RenderLayerParent<FlyRenderState, FlyModel> parent, EntityRendererProvider.Context context) {
        super(parent);
        head = new FlyModel(context.bakeLayer(FruitFlyClient.FLY_LAYER), FlyModel.Parts.HEAD, 0F);
    }

    @Override
    public void submit(PoseStack poseStack, SubmitNodeCollector collector, int lightCoords, FlyRenderState state,
                       float yRot, float xRot) {
        if (!state.hasBrain || state.isInvisible) return;
        float activity = Mth.clamp(state.activity, 0F, 1F);
        if (activity < 0.04F) return;
        // gentle 1 Hz shimmer so the glow reads as "alive" even at a steady rate; alpha floor keeps texels above the
        // shaders' 0.1 discard threshold
        float shimmer = 0.85F + 0.15F * Mth.sin(state.ageInTicks * 0.31F);
        int alpha = Mth.clamp((int) ((40F + 110F * activity) * shimmer), 32, 160);
        int tint = ARGB.color(alpha, 255, 140, 205);
        collector.submitModel(head, state, poseStack, RenderTypes.entityTranslucentEmissive(FlyRenderer.texture(state)),
                LightCoordsUtil.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, tint, null, 0, null);
    }
}
