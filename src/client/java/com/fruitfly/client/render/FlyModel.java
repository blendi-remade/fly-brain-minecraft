package com.fruitfly.client.render;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.util.Mth;

import java.util.List;

/**
 * Voxel model of a male/female <i>Drosophila melanogaster</i>, built at "bee scale" (about 20 px from antennae to
 * abdomen tip) on a 64x64 atlas and shrunk in {@link FlyRenderer#scale}. Follows docs/research/fly-model-art.md §2.4.
 *
 * <p>Hierarchy: {@code root -> body -> { thorax, head -> { eyes, antennae -> aristae, proboscis -> labellum },
 * abdomen -> abdomen_tip, wings, halteres, 6 legs (femur -> tibia -> tarsus) }}.
 *
 * <p>Texture atlas (u,v = top-left of the box footprint; footprint = 2(w+d) x (d+h); must match tools/gen_fly_texture.py):
 * <pre>
 *   thorax       ( 0, 0)  box(-3,-3,-3, 6,6,6)                 x 0..23  y 0..11
 *   head         (24, 0)  box(-2,-2,-4, 4,4,4)                 x 24..39 y 0..7
 *   eye          (40, 0)  box( 2,-1.5,-3.5, 1,3,3) grow 0.2    x 40..47 y 0..5   (right eye = mirror)
 *   proboscis    (48, 0)  box(-0.5,0,-0.5, 1,3,1)              x 48..51 y 0..3
 *   labellum     (48, 4)  box(-1,0,-1, 2,1,2)                  x 48..55 y 4..6
 *   antenna      (56, 0)  box(-0.5,-0.5,-1, 1,1,1)             x 56..59 y 0..1
 *   arista       (60, 0)  box( 0,-2,-0.5, 0,2,1) grow 0.001    x 60..61 y 0..2
 *   abdomen      ( 0,12)  box(-2.5,-2.5,0, 5,5,8)              x 0..25  y 12..24
 *   abdomen_tip  (26,12)  box(-1.5,-1.5,8, 3,3,2)              x 26..35 y 12..16  (male: black)
 *   haltere      (36,12)  box( 0,-0.5,-0.5, 2,1,1)             x 36..41 y 12..13
 *   femur        (42,12)  box( 0,-0.5,-0.5, 4,1,1)             x 42..51 y 12..13
 *   tibia        (42,14)  same                                 x 42..51 y 14..15
 *   tarsus       (42,16)  same                                 x 42..51 y 16..17
 *   wing         ( 0,26)  box(-0.5,0,0, 4,0,14) grow 0.001     x 0..35  y 26..39  (top face (14,26) 4x14, bottom (18,26) 4x14)
 * </pre>
 * Model space: 16 px = 1 block, +Y down, ground at y = 24, the fly faces -Z; +X is the fly's LEFT.
 *
 * <p>Sign conventions (from {@code ModelPart.translateAndRotate}'s rotationZYX): for a LEFT leg (box along +x) positive
 * {@code yRot} swings the tip forward and positive {@code zRot} tilts the tip down; right legs are the negation. For a
 * wing (box along +z) {@code yRot = +pi/2} points the left wing out to +x; once out, positive {@code zRot} beats it down
 * (negative for the right wing).
 *
 * <p>Rendering is deferred and re-poses the model from a {@link FlyRenderState} at draw time, so each instance draws a
 * fixed subset of the parts ({@link Parts}): the renderer's own instance draws the opaque body with the wings hidden,
 * {@link FlyWingLayer} has wing-only instances (drawn with {@code RenderTypes.entityTranslucent}, one per motion-blur
 * ghost stroke) and {@link FlyGlowLayer} a head-only one.
 */
public class FlyModel extends EntityModel<FlyRenderState> {
    /** Leg order: L1, R1, L2, R2, L3, R3 (1-based "1,4,5" vs "2,3,6" = tripod {L1,R2,L3} vs {R1,L2,R3}). */
    public static final String[] LEG_NAMES = {"left_front_leg", "right_front_leg", "left_middle_leg", "right_middle_leg", "left_hind_leg", "right_hind_leg"};

    /** Which parts an instance draws; the full hierarchy is still posed so the drawn parts sit where they belong. */
    public enum Parts { BODY, WINGS, HEAD }

    public final ModelPart body;
    public final ModelPart thorax;
    public final ModelPart head;
    public final ModelPart leftEye, rightEye;
    public final ModelPart leftAntenna, rightAntenna;
    public final ModelPart leftArista, rightArista;
    public final ModelPart proboscis, labellum;
    public final ModelPart abdomen, abdomenTip;
    public final ModelPart leftWing, rightWing;
    public final ModelPart leftHaltere, rightHaltere;
    public final Leg[] legs = new Leg[6];
    /** Extra wing stroke angle (times {@link FlyRenderState#wingBlur}) for a motion-blur ghost instance; 0 otherwise. */
    private final float ghostOffset;

    /** One leg: femur pivots at the thorax, tibia at the knee, tarsus at the ankle. */
    public static final class Leg {
        public final ModelPart femur, tibia, tarsus;
        /** +1 left, -1 right. */
        public final float side;
        /** Resting yaw (front legs forward, hind legs back), already multiplied by side. */
        public final float restYaw;
        /** Tripod gait phase: 0 or PI. */
        public final float phase;
        /** 0 front, 1 middle, 2 hind. */
        public final int pair;

        Leg(ModelPart femur, float side, float restYaw, float phase, int pair) {
            this.femur = femur;
            this.tibia = femur.getChild("tibia");
            this.tarsus = tibia.getChild("tarsus");
            this.side = side;
            this.restYaw = restYaw;
            this.phase = phase;
            this.pair = pair;
        }
    }

    public FlyModel(ModelPart root) {
        this(root, Parts.BODY, 0F);
    }

    public FlyModel(ModelPart root, Parts parts, float ghostOffset) {
        super(root);
        this.ghostOffset = ghostOffset;
        this.body = root.getChild("body");
        this.thorax = body.getChild("thorax");
        this.head = body.getChild("head");
        this.leftEye = head.getChild("left_eye");
        this.rightEye = head.getChild("right_eye");
        this.leftAntenna = head.getChild("left_antenna");
        this.rightAntenna = head.getChild("right_antenna");
        this.leftArista = leftAntenna.getChild("left_arista");
        this.rightArista = rightAntenna.getChild("right_arista");
        this.proboscis = head.getChild("proboscis");
        this.labellum = proboscis.getChild("labellum");
        this.abdomen = body.getChild("abdomen");
        this.abdomenTip = abdomen.getChild("abdomen_tip");
        this.leftWing = body.getChild("left_wing");
        this.rightWing = body.getChild("right_wing");
        this.leftHaltere = body.getChild("left_haltere");
        this.rightHaltere = body.getChild("right_haltere");
        float[] restYaw = {0.7F, 0F, -0.7F};
        for (int i = 0; i < 6; i++) {
            int pair = i / 2;
            float side = (i % 2 == 0) ? 1F : -1F;
            // tripod: {L1, R2, L3} in phase 0, {R1, L2, R3} in phase PI
            float phase = (i == 0 || i == 3 || i == 4) ? 0F : Mth.PI;
            legs[i] = new Leg(body.getChild(LEG_NAMES[i]), side, restYaw[pair] * side, phase, pair);
        }
        // visibility is not part of the pose (resetPose leaves it alone), so the mask is applied once here
        switch (parts) {
            case BODY -> { // wings are drawn by FlyWingLayer (translucent); keep them out of the cutout pass
                leftWing.visible = false;
                rightWing.visible = false;
            }
            case WINGS -> showOnly(leftWing, rightWing);
            case HEAD -> showOnly(head);
        }
    }

    /** Hide every part hanging off the body except {@code shown} (and their children); the body's own box is skipped. */
    private void showOnly(ModelPart... shown) {
        body.skipDraw = true;
        List<ModelPart> keep = List.of(shown);
        ModelPart[] children = {thorax, head, abdomen, leftWing, rightWing, leftHaltere, rightHaltere,
                legs[0].femur, legs[1].femur, legs[2].femur, legs[3].femur, legs[4].femur, legs[5].femur};
        for (ModelPart c : children) c.visible = keep.contains(c);
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        PartDefinition body = root.addOrReplaceChild("body", CubeListBuilder.create(), PartPose.offset(0F, 18F, 0F));

        body.addOrReplaceChild("thorax", CubeListBuilder.create().texOffs(0, 0)
                .addBox(-3F, -3F, -3F, 6F, 6F, 6F), PartPose.ZERO);

        PartDefinition head = body.addOrReplaceChild("head", CubeListBuilder.create().texOffs(24, 0)
                .addBox(-2F, -2F, -4F, 4F, 4F, 4F), PartPose.offset(0F, -0.5F, -3F));
        head.addOrReplaceChild("left_eye", CubeListBuilder.create().texOffs(40, 0)
                .addBox(2F, -1.5F, -3.5F, 1F, 3F, 3F, new CubeDeformation(0.2F)), PartPose.ZERO);
        head.addOrReplaceChild("right_eye", CubeListBuilder.create().texOffs(40, 0).mirror()
                .addBox(-3F, -1.5F, -3.5F, 1F, 3F, 3F, new CubeDeformation(0.2F)), PartPose.ZERO);
        PartDefinition leftAntenna = head.addOrReplaceChild("left_antenna", CubeListBuilder.create().texOffs(56, 0)
                .addBox(-0.5F, -0.5F, -1F, 1F, 1F, 1F), PartPose.offsetAndRotation(1F, -1F, -4F, -0.5F, 0F, 0F));
        leftAntenna.addOrReplaceChild("left_arista", CubeListBuilder.create().texOffs(60, 0)
                .addBox(0F, -2F, -0.5F, 0F, 2F, 1F, new CubeDeformation(0.001F)),
                PartPose.offsetAndRotation(0F, -0.5F, -0.5F, 0.9F, -0.6F, 0F));
        PartDefinition rightAntenna = head.addOrReplaceChild("right_antenna", CubeListBuilder.create().texOffs(56, 0).mirror()
                .addBox(-0.5F, -0.5F, -1F, 1F, 1F, 1F), PartPose.offsetAndRotation(-1F, -1F, -4F, -0.5F, 0F, 0F));
        rightAntenna.addOrReplaceChild("right_arista", CubeListBuilder.create().texOffs(60, 0).mirror()
                .addBox(0F, -2F, -0.5F, 0F, 2F, 1F, new CubeDeformation(0.001F)),
                PartPose.offsetAndRotation(0F, -0.5F, -0.5F, 0.9F, 0.6F, 0F));
        // proboscis hangs from the underside of the head, folded back (+xRot) when not feeding
        PartDefinition proboscis = head.addOrReplaceChild("proboscis", CubeListBuilder.create().texOffs(48, 0)
                .addBox(-0.5F, 0F, -0.5F, 1F, 3F, 1F), PartPose.offsetAndRotation(0F, 2F, -2F, 1.2F, 0F, 0F));
        proboscis.addOrReplaceChild("labellum", CubeListBuilder.create().texOffs(48, 4)
                .addBox(-1F, 0F, -1F, 2F, 1F, 2F), PartPose.offset(0F, 3F, 0F));

        PartDefinition abdomen = body.addOrReplaceChild("abdomen", CubeListBuilder.create().texOffs(0, 12)
                .addBox(-2.5F, -2.5F, 0F, 5F, 5F, 8F), PartPose.offset(0F, 0.5F, 3F));
        abdomen.addOrReplaceChild("abdomen_tip", CubeListBuilder.create().texOffs(26, 12)
                .addBox(-1.5F, -1.5F, 8F, 3F, 3F, 2F), PartPose.ZERO);

        // wings: zero-thickness quads hinged on top of the thorax, lying back over the abdomen at rest
        body.addOrReplaceChild("left_wing", CubeListBuilder.create().texOffs(0, 26)
                .addBox(-0.5F, 0F, 0F, 4F, 0F, 14F, new CubeDeformation(0.001F)),
                PartPose.offsetAndRotation(1F, -3F, 0F, 0F, 0.12F, 0F));
        body.addOrReplaceChild("right_wing", CubeListBuilder.create().texOffs(0, 26).mirror()
                .addBox(-3.5F, 0F, 0F, 4F, 0F, 14F, new CubeDeformation(0.001F)),
                PartPose.offsetAndRotation(-1F, -3.1F, 0F, 0F, -0.12F, 0F));

        // halteres: little clubs behind the wing hinges, angled back
        body.addOrReplaceChild("left_haltere", CubeListBuilder.create().texOffs(36, 12)
                .addBox(0F, -0.5F, -0.5F, 2F, 1F, 1F), PartPose.offsetAndRotation(2.5F, 0.5F, 2.5F, 0F, -0.5F, 0F));
        body.addOrReplaceChild("right_haltere", CubeListBuilder.create().texOffs(36, 12).mirror()
                .addBox(-2F, -0.5F, -0.5F, 2F, 1F, 1F), PartPose.offsetAndRotation(-2.5F, 0.5F, 2.5F, 0F, 0.5F, 0F));

        // legs: femur (thorax -> knee), tibia (knee -> ankle), tarsus (ankle -> claw). Rest pose puts the claw on the
        // ground: root y=20.5, femur -0.35 rad rises 1.37, tibia (+1.55 -> 1.20 cumulative) drops 3.73,
        // tarsus (-1.0 -> 0.20 cumulative) drops 0.79 -> claw at y ~ 23.65 + half thickness = ground.
        float[] legZ = {-2F, 0F, 2F};
        float[] restYaw = {0.7F, 0F, -0.7F};
        for (int pair = 0; pair < 3; pair++) {
            PartDefinition lf = body.addOrReplaceChild(LEG_NAMES[pair * 2], CubeListBuilder.create().texOffs(42, 12)
                    .addBox(0F, -0.5F, -0.5F, 4F, 1F, 1F),
                    PartPose.offsetAndRotation(2.5F, 2.5F, legZ[pair], 0F, restYaw[pair], -0.35F));
            PartDefinition lt = lf.addOrReplaceChild("tibia", CubeListBuilder.create().texOffs(42, 14)
                    .addBox(0F, -0.5F, -0.5F, 4F, 1F, 1F), PartPose.offsetAndRotation(4F, 0F, 0F, 0F, 0F, 1.55F));
            lt.addOrReplaceChild("tarsus", CubeListBuilder.create().texOffs(42, 16)
                    .addBox(0F, -0.5F, -0.5F, 4F, 1F, 1F), PartPose.offsetAndRotation(4F, 0F, 0F, 0F, 0F, -1.0F));

            PartDefinition rf = body.addOrReplaceChild(LEG_NAMES[pair * 2 + 1], CubeListBuilder.create().texOffs(42, 12).mirror()
                    .addBox(-4F, -0.5F, -0.5F, 4F, 1F, 1F),
                    PartPose.offsetAndRotation(-2.5F, 2.5F, legZ[pair], 0F, -restYaw[pair], 0.35F));
            PartDefinition rt = rf.addOrReplaceChild("tibia", CubeListBuilder.create().texOffs(42, 14).mirror()
                    .addBox(-4F, -0.5F, -0.5F, 4F, 1F, 1F), PartPose.offsetAndRotation(-4F, 0F, 0F, 0F, 0F, -1.55F));
            rt.addOrReplaceChild("tarsus", CubeListBuilder.create().texOffs(42, 16).mirror()
                    .addBox(-4F, -0.5F, -0.5F, 4F, 1F, 1F), PartPose.offsetAndRotation(-4F, 0F, 0F, 0F, 0F, 1.0F));
        }
        return LayerDefinition.create(mesh, 64, 64);
    }

    // ------------------------------------------------------------------------------------------------------ animation

    @Override
    public void setupAnim(FlyRenderState state) {
        super.setupAnim(state);               // reset to the rest pose
        float ageInTicks = state.ageInTicks;
        float netHeadYaw = state.yRot, headPitch = state.xRot;
        float limbSwing = state.walkAnimationPos, limbSwingAmount = state.walkAnimationSpeed;
        float fl = state.flight;              // 0 walking .. 1 flying
        float gr = state.groom;               // 0 .. 1 grooming
        float so = state.song;                // 0 .. 1 singing (unilateral wing extension)
        float fe = state.proboscis;           // proboscis extension (already smooth, from the body)
        float t = ageInTicks * 2.1F;          // wing beat cadence (2.1 rad/tick, vanilla bee)

        // ---- head: flies barely turn their heads
        head.yRot = netHeadYaw * Mth.DEG_TO_RAD * 0.4F;
        head.xRot = headPitch * Mth.DEG_TO_RAD * 0.4F;

        // ---- idle life: antenna twitch, abdominal breathing
        float idle = Mth.cos(ageInTicks * 0.18F);
        leftAntenna.xRot += idle * 0.08F;
        rightAntenna.xRot += Mth.cos(ageInTicks * 0.18F + 1.3F) * 0.07F;
        abdomen.zScale = 1F + Mth.sin(ageInTicks * 0.18F) * 0.03F;
        abdomen.yScale = 1F + Mth.sin(ageInTicks * 0.18F + 0.5F) * 0.02F;

        // ---- legs: tripod gait (blended out while flying/grooming)
        float walk = (1F - fl) * (1F - gr);
        float f = limbSwing * 3.0F;
        float amp = 0.5F * limbSwingAmount;
        float lift = 0.35F * limbSwingAmount;
        for (Leg leg : legs) {
            float ph = f + leg.phase;
            float swing = Mth.cos(ph) * amp;
            float up = Math.max(0F, -Mth.sin(ph)) * lift;      // lifted during protraction (swing) only
            float s = leg.side;
            float femurYaw = leg.restYaw + s * swing * walk;
            float femurRoll = s * (-0.35F - up * walk);
            float tibiaRoll = s * (1.55F + up * 0.8F * walk);
            float tarsusRoll = s * (-1.0F + up * 0.3F * walk);
            // flight tuck: front legs folded up by the head, mid legs tucked, hind legs trailing
            float[] tuckYaw = {1.0F, 0.2F, -1.3F};
            femurYaw = Mth.lerp(fl, femurYaw, s * tuckYaw[leg.pair]);
            femurRoll = Mth.lerp(fl, femurRoll, s * 0.55F);
            tibiaRoll = Mth.lerp(fl, tibiaRoll, s * 1.95F);
            tarsusRoll = Mth.lerp(fl, tarsusRoll, s * -0.35F);
            leg.femur.yRot = femurYaw;
            leg.femur.zRot = femurRoll;
            leg.tibia.zRot = tibiaRoll;
            leg.tarsus.zRot = tarsusRoll;
        }
        body.y += Math.abs(Mth.cos(f)) * 0.15F * limbSwingAmount * walk;   // tiny walking bounce

        // ---- flight: wings out and beating, halteres in antiphase, nose-up hover, bank into turns
        if (fl > 0.001F) {
            float stroke = Mth.cos(t) * 1.15F;                       // +-66 deg -> ~130 deg total (Fry 2005)
            float twist = Mth.sin(t) * 0.35F;
            leftWing.yRot = Mth.lerp(fl, 0.12F, 1.25F);
            rightWing.yRot = Mth.lerp(fl, -0.12F, -1.25F);
            leftWing.zRot = fl * (-0.35F + stroke);
            rightWing.zRot = fl * (0.35F - stroke);
            leftWing.xRot = fl * twist;
            rightWing.xRot = fl * -twist;
            leftHaltere.zRot = fl * -Mth.cos(t) * 0.8F;
            rightHaltere.zRot = fl * Mth.cos(t) * 0.8F;
            float climb = Mth.clamp(state.verticalSpeed * 3F, -0.5F, 0.5F);
            float yawRate = state.bodyYawRate;
            body.xRot += fl * (-0.25F - climb);
            body.zRot += fl * -Mth.clamp(yawRate * 0.03F, -0.5F, 0.5F);
            body.y += fl * (-1.0F + Mth.sin(ageInTicks * 0.35F) * 0.4F);
            // antennae blown back a little by the airflow
            leftAntenna.xRot += fl * 0.2F;
            rightAntenna.xRot += fl * 0.2F;
        }

        // ---- courtship song: extend one wing ~65 deg laterally and vibrate it
        if (so > 0.001F) {
            float side = state.songSide == 2 ? -1F : 1F;           // 1 = left wing, 2 = right wing
            ModelPart w = side > 0 ? leftWing : rightWing;
            float env = 0.5F + 0.5F * Mth.sin(ageInTicks * 0.6F);   // pulse/sine bouts alternate every ~1 s
            w.yRot = Mth.lerp(so, w.yRot, side * 1.1F);
            w.zRot = Mth.lerp(so, w.zRot, side * -0.1F + Mth.sin(ageInTicks * 4.0F) * 0.08F * env);
            w.xRot = Mth.lerp(so, w.xRot, -0.1F);
            abdomen.yRot += so * side * 0.15F;
            head.yRot += so * side * 0.15F;
        }

        // ---- grooming: 6 Hz rubs; kind 1 antennal sweep, 2 head rub, 3 leg rubbing, 4 abdomen sweep
        if (gr > 0.001F) {
            float g = Mth.sin(ageInTicks * 1.9F);                  // 1.9 rad/tick = 6 Hz
            byte kind = state.groomKind;
            Leg l1 = legs[0], r1 = legs[1], l3 = legs[4], r3 = legs[5];
            switch (kind) {
                case 1 -> { // front legs sweep forward and up over the antennae, head bows, antennae flick
                    setLeg(l1, gr, 1.35F, -0.65F + g * 0.22F, 1.9F, -0.6F);
                    setLeg(r1, gr, -1.35F, 0.65F - g * 0.22F, -1.9F, 0.6F);
                    head.xRot += gr * (0.35F + g * 0.15F);
                    leftAntenna.xRot += gr * Math.max(0F, g) * 0.4F;
                    rightAntenna.xRot += gr * Math.max(0F, g) * 0.4F;
                    body.xRot += gr * 0.15F;
                }
                case 2 -> { // front legs rub the head in opposition
                    setLeg(l1, gr, 1.3F, -0.25F + g * 0.25F, 1.9F, -0.6F);
                    setLeg(r1, gr, -1.3F, 0.25F + g * 0.25F, -1.9F, 0.6F);
                    head.xRot += gr * 0.3F;
                    head.yRot += gr * g * 0.1F;
                    body.xRot += gr * 0.15F;
                }
                case 3 -> { // front legs rub each other below the head
                    setLeg(l1, gr, 0.9F + g * 0.2F, 0.15F + g * 0.3F, 2.1F, -0.8F);
                    setLeg(r1, gr, -0.9F + g * 0.2F, -0.15F + g * 0.3F, -2.1F, 0.8F);
                    body.xRot += gr * 0.1F;
                }
                default -> { // hind legs sweep the abdomen/wings, body pitches nose-down, wings lift slightly
                    setLeg(l3, gr, -1.5F, -0.55F + g * 0.25F, 1.6F, -0.5F);
                    setLeg(r3, gr, 1.5F, 0.55F - g * 0.25F, -1.6F, 0.5F);
                    body.xRot += gr * 0.2F;
                    abdomen.xRot += gr * (-0.15F + g * 0.05F);
                    leftWing.xRot += gr * 0.15F;
                    rightWing.xRot += gr * 0.15F;
                    leftWing.yRot += gr * 0.15F;
                    rightWing.yRot -= gr * 0.15F;
                }
            }
        }

        // ---- feeding: proboscis unfolds forward/down, telescopes, labellar lobes spread, head tips to the food
        if (fe > 0.001F) {
            proboscis.xRot = Mth.lerp(fe, 1.2F, -0.15F);
            float pump = fe * Mth.sin(ageInTicks * 0.9F) * 0.08F;
            float ys = Mth.lerp(fe, 1.0F, 1.8F) + pump;
            proboscis.yScale = ys;
            labellum.yScale = 1F / ys;                              // keep the labellum 1 px tall at the moving tip
            labellum.xScale = labellum.zScale = Mth.lerp(fe, 1.0F, 1.4F);
            head.xRot += fe * 0.35F;
        }

        // ---- motion-blur ghost stroke (wing-only instances drawn by FlyWingLayer)
        if (ghostOffset != 0F) {
            leftWing.zRot += ghostOffset * state.wingBlur;
            rightWing.zRot -= ghostOffset * state.wingBlur;
        }
    }

    private static void setLeg(Leg leg, float w, float femurYaw, float femurRoll, float tibiaRoll, float tarsusRoll) {
        leg.femur.yRot = Mth.lerp(w, leg.femur.yRot, femurYaw);
        leg.femur.zRot = Mth.lerp(w, leg.femur.zRot, femurRoll);
        leg.tibia.zRot = Mth.lerp(w, leg.tibia.zRot, tibiaRoll);
        leg.tarsus.zRot = Mth.lerp(w, leg.tarsus.zRot, tarsusRoll);
    }
}
