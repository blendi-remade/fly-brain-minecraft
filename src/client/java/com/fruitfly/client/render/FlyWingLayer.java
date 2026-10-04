package com.fruitfly.client.render;

import com.fruitfly.client.FruitFlyClient;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.util.ARGB;

/**
 * Draws the two wing quads with {@code RenderTypes.entityTranslucent} (alpha blended, two-sided, lit) so the membrane
 * painted at alpha ~110/255 reads as glass, while the body stays in the opaque cutout pass. While the fly is flapping
 * two extra "ghost" strokes per wing are drawn at a low tint alpha as a cheap motion blur (a 200 Hz wing beat cannot
 * be displayed at 20 ticks/s; the vanilla-bee cadence plus ghosts reads as a buzz). Each stroke is its own wing-only
 * {@link FlyModel} instance, because the deferred renderer re-poses a model from the render state when it draws it.
 *
 * <p>Note on alpha: both entity shaders discard texels whose final alpha is below 0.1, so the ghost tint alpha (80/255)
 * times the membrane alpha (110/255) must stay above that: 0.31 * 0.43 = 0.135.
 */
public final class FlyWingLayer extends RenderLayer<FlyRenderState, FlyModel> {
    private static final int GHOST_TINT = ARGB.color(80, 255, 255, 255);
    private static final float[] GHOST_OFFSETS = {-0.65F, 0.65F};

    private final FlyModel wings;
    private final FlyModel[] ghosts = new FlyModel[GHOST_OFFSETS.length];

    public FlyWingLayer(RenderLayerParent<FlyRenderState, FlyModel> parent, EntityRendererProvider.Context context) {
        super(parent);
        wings = new FlyModel(context.bakeLayer(FruitFlyClient.FLY_LAYER), FlyModel.Parts.WINGS, 0F);
        for (int i = 0; i < ghosts.length; i++) {
            ghosts[i] = new FlyModel(context.bakeLayer(FruitFlyClient.FLY_LAYER), FlyModel.Parts.WINGS, GHOST_OFFSETS[i]);
        }
    }

    @Override
    public void submit(PoseStack poseStack, SubmitNodeCollector collector, int lightCoords, FlyRenderState state,
                       float yRot, float xRot) {
        if (state.isInvisible) return;
        RenderType type = RenderTypes.entityTranslucent(FlyRenderer.texture(state));
        int overlay = LivingEntityRenderer.getOverlayCoords(state, 0F);
        collector.submitModel(wings, state, poseStack, type, lightCoords, overlay, -1, null, state.outlineColor, null);
        if (state.wingBlur > 0.4F) {
            for (FlyModel ghost : ghosts) {
                collector.submitModel(ghost, state, poseStack, type, lightCoords, overlay, GHOST_TINT, null, 0, null);
            }
        }
    }
}
