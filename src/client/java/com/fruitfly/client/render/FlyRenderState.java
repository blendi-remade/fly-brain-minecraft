package com.fruitfly.client.render;

import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;

/**
 * Everything {@link FlyModel#setupAnim} and the fly's render layers need for one frame, copied out of the
 * {@link com.fruitfly.entity.FlyEntity} by {@link FlyRenderer#extractRenderState}. Rendering is deferred, so the model
 * may be posed from this state long after extraction and must not read the entity.
 */
public class FlyRenderState extends LivingEntityRenderState {
    public boolean male = true;
    public boolean hasBrain;
    public float flyScale = 1F;
    /** Brain activity 0..1 (synched spikes-per-tick summary). */
    public float activity;
    /** Proboscis extension 0..1. */
    public float proboscis;
    public boolean flapping;
    /** Vertical velocity (blocks/tick), for the nose-up/down flight pitch. */
    public float verticalSpeed;
    /** Body yaw change this tick (degrees), for banking into turns. */
    public float bodyYawRate;
    /** Smoothed behaviour blends 0..1 (walking -> flying, grooming, courtship song). */
    public float flight, groom, song;
    /** Last non-zero groom kind (1 antennal, 2 head, 3 leg rubbing, 4 abdomen) and song wing (1 left, 2 right). */
    public byte groomKind, songSide;
    /** 0..1 amount of wing motion blur the wing layer draws. */
    public float wingBlur;
}
