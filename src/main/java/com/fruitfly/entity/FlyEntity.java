package com.fruitfly.entity;

import com.fruitfly.FlyBrainService;
import com.fruitfly.FruitFlyConfig;
import com.fruitfly.FruitFlyMod;
import com.fruitfly.brain.BrainRunner;
import com.fruitfly.brain.LifNetwork;
import com.fruitfly.brain.MotorDecoder;
import com.fruitfly.brain.PopulationIndex;
import com.fruitfly.brain.RetinaGeometry;
import com.fruitfly.brain.SensoryEncoders;
import com.fruitfly.brain.SensoryFrame;
import com.fruitfly.net.BrainTelemetryPayload;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The embodied fruit fly. Server side: samples the world into a {@link SensoryFrame}, hands it to the brain thread
 * ({@link SensoryEncoders} → {@link LifNetwork}), reads the decoded {@link MotorDecoder.MotorCommand} and moves via
 * {@link FlyBody}. Client side: interpolates position and animates from synched state.
 */
public class FlyEntity extends Mob {
    private static final EntityDataAccessor<Byte> DATA_MODE = SynchedEntityData.defineId(FlyEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Boolean> DATA_FLYING = SynchedEntityData.defineId(FlyEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Float> DATA_PROBOSCIS = SynchedEntityData.defineId(FlyEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Byte> DATA_WING_EXT = SynchedEntityData.defineId(FlyEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Byte> DATA_GROOM = SynchedEntityData.defineId(FlyEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Float> DATA_ACTIVITY = SynchedEntityData.defineId(FlyEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Boolean> DATA_MALE = SynchedEntityData.defineId(FlyEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Float> DATA_SCALE = SynchedEntityData.defineId(FlyEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Boolean> DATA_HAS_BRAIN = SynchedEntityData.defineId(FlyEntity.class, EntityDataSerializers.BOOLEAN);
    /** Persistent fly number (assigned once, saved to NBT) — the basis of the fly's name and identity colour. */
    private static final EntityDataAccessor<Integer> DATA_FLY_NO = SynchedEntityData.defineId(FlyEntity.class, EntityDataSerializers.INT);

    // server-side brain state
    private BrainRunner brain;
    private SensoryEncoders encoders;
    private MotorDecoder decoder;
    private final WorldSenses.State senseState = new WorldSenses.State();
    private final FlyBody.State bodyState = new FlyBody.State();
    private SensoryFrame lastFrame;
    private float damageAccum;
    private float hunger = 0.6f;          // 0 = starving, 1 = sated
    private int brainRetryTicks;
    private final List<Object[]> stimuli = new ArrayList<>(); // {spec, hz, ticksLeft}
    private volatile BrainTelemetryPayload lastTelemetry;

    public FlyEntity(EntityType<? extends FlyEntity> type, Level level) {
        super(type, level);
        this.setPersistenceRequired();
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 4.0)
                .add(Attributes.MOVEMENT_SPEED, 0.3)
                .add(Attributes.FLYING_SPEED, 0.6)
                .add(Attributes.FOLLOW_RANGE, 24.0);
    }

    @Override
    protected void registerGoals() { /* none: the connectome drives everything */ }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder b) {
        super.defineSynchedData(b);
        b.define(DATA_MODE, (byte) 0);
        b.define(DATA_FLYING, false);
        b.define(DATA_PROBOSCIS, 0f);
        b.define(DATA_WING_EXT, (byte) 0);
        b.define(DATA_GROOM, (byte) 0);
        b.define(DATA_ACTIVITY, 0f);
        b.define(DATA_MALE, true);
        b.define(DATA_SCALE, 1f);
        b.define(DATA_HAS_BRAIN, false);
        b.define(DATA_FLY_NO, 0);
    }

    // ------------------------------------------------------------------ identity

    /** Deterministic identity colour (0xRRGGBB) for a fly number: golden-angle hue, saturated and bright. */
    public static int colorForNumber(int no) {
        float hue = (((no * 137.508f) % 360f) + 360f) % 360f / 360f;
        return net.minecraft.util.Mth.hsvToRgb(hue, 0.72f, 1.0f) & 0xFFFFFF;
    }

    /** Colour of a fly that has not been numbered yet: grey, so it never wears another fly's hue. */
    private static final int UNNUMBERED_RGB = 0x8A8A8A;

    /** Persistent fly number (0 until assigned on the first server tick). */
    public int getFlyNumber() { return entityData.get(DATA_FLY_NO); }
    public int getFlyColor() {
        int no = getFlyNumber();
        return no <= 0 ? UNNUMBERED_RGB : colorForNumber(no);
    }
    /** Short display name, e.g. "Fly-12 ♂" ("Fly-? ♂" until numbered). */
    public String flyName() {
        int no = getFlyNumber();
        return "Fly-" + (no <= 0 ? "?" : Integer.toString(no)) + (isMale() ? " ♂" : " ♀");
    }

    /** True once this instance has taken or reported its number to the world's {@link FlyIds} counter. */
    private boolean identityRegistered;

    /**
     * Give the fly its number, coloured name tag and visible name. Server thread only. Numbers come from the per-world
     * {@link FlyIds} counter, so they stay unique across restarts and flies sleeping in unloaded chunks; a number read
     * from NBT is reported to the counter here rather than in {@link #readAdditionalSaveData}, because vanilla runs
     * chunk-entity deserialisation on a background thread and the saved-data cache is not thread-safe.
     */
    private void ensureIdentity() {
        if (!(level() instanceof ServerLevel sl)) return;
        if (!identityRegistered) {
            identityRegistered = true;
            FlyIds ids = FlyIds.get(sl.getServer());
            int no = getFlyNumber();
            if (no <= 0) entityData.set(DATA_FLY_NO, ids.take());
            else ids.noteLoaded(no);
        }
        if (!hasCustomName()) {
            setCustomName(Component.literal(flyName()).withColor(getFlyColor()));
            setCustomNameVisible(true);
        }
    }

    /** Per-world fly-number counter, persisted with the server's saved data ({@code data/fruitfly/fly_ids.dat}). */
    public static final class FlyIds extends SavedData {
        private static final Codec<FlyIds> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.optionalFieldOf("Next", 1).forGetter(ids -> ids.next)
        ).apply(i, FlyIds::new));
        // The DataFixTypes must not be null: SavedDataStorage.readTagFromDisk dereferences it unconditionally and
        // readSavedData swallows the exception, which would hand back a fresh (reset) counter on every world open.
        private static final SavedDataType<FlyIds> TYPE =
                new SavedDataType<>(FruitFlyMod.id("fly_ids"), FlyIds::new, CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE);
        private int next;

        public FlyIds() { this(1); }

        private FlyIds(int next) { this.next = Math.max(1, next); }

        public static FlyIds get(MinecraftServer server) {
            return server.getDataStorage().computeIfAbsent(TYPE);
        }

        /** Hand out the next number. */
        public int take() {
            int no = next++;
            setDirty();
            return no;
        }

        /** A fly with this saved number exists (pre-counter save, copied entity): never hand the number out again. */
        public void noteLoaded(int no) {
            if (no >= next) {
                next = no + 1;
                setDirty();
            }
        }

        /** The number the next new fly will receive. */
        public int peek() { return next; }
    }

    // ------------------------------------------------------------------ synched accessors

    public MotorDecoder.Mode getMode() {
        int m = entityData.get(DATA_MODE);
        MotorDecoder.Mode[] modes = MotorDecoder.Mode.values();
        return m >= 0 && m < modes.length ? modes[m] : MotorDecoder.Mode.IDLE;
    }
    public boolean isFlyingState() { return entityData.get(DATA_FLYING); }
    public float getProboscis() { return entityData.get(DATA_PROBOSCIS); }
    /** 0 = folded, 1 = left wing extended (song), 2 = right wing extended. */
    public byte getWingExtension() { return entityData.get(DATA_WING_EXT); }
    /** 0 none, 1 antennal, 2 head, 3 leg rubbing, 4 abdomen. */
    public byte getGroomState() { return entityData.get(DATA_GROOM); }
    /** Brain activity 0..1 (spikes per tick, normalised) for glow effects. */
    public float getActivity() { return entityData.get(DATA_ACTIVITY); }
    public boolean isMale() { return entityData.get(DATA_MALE); }
    public void setMale(boolean male) { entityData.set(DATA_MALE, male); }
    public float getFlyScale() { return entityData.get(DATA_SCALE); }
    public void setFlyScale(float s) { entityData.set(DATA_SCALE, Math.max(0.2f, Math.min(4f, s))); }
    public boolean hasBrain() { return entityData.get(DATA_HAS_BRAIN); }

    // ------------------------------------------------------------------ server-side brain plumbing

    public BrainRunner brain() { return brain; }
    public MotorDecoder decoder() { return decoder; }
    /** The decoder's latest command, or idle once the brain is released (so a brainless body never replays a stale command). */
    public MotorDecoder.MotorCommand latestCommand() {
        MotorDecoder d = decoder;
        return d == null ? MotorDecoder.MotorCommand.idle() : d.latest();
    }
    public SensoryFrame lastFrame() { return lastFrame; }
    public boolean isReflexDriving() { return bodyState.reflexDriving; }
    public BrainTelemetryPayload lastTelemetry() { return lastTelemetry; }
    public float getHunger() { return hunger; }
    public void feed(float nutrition) { hunger = Math.max(0f, Math.min(1f, hunger + nutrition)); }
    float consumeDamage() { float d = damageAccum; damageAccum = 0; return d; }

    private void acquireBrain() {
        FlyBrainService svc = FruitFlyMod.BRAIN;
        BrainRunner r = svc.acquire("fly-" + getId());
        if (r == null) return;
        PopulationIndex pi = svc.populations();
        RetinaGeometry geom = svc.geometry();
        FruitFlyConfig cfg = FruitFlyMod.CONFIG;
        SensoryEncoders.Params ep = new SensoryEncoders.Params();
        ep.vision = cfg.vision;
        ep.ornRMax = cfg.maxOrnRateHz;
        ep.laminaTonicMvPerMs = cfg.laminaTonicMvPerMs;
        this.encoders = new SensoryEncoders(svc.connectome(), pi, geom, ep);
        this.decoder = new MotorDecoder(pi);
        final MotorDecoder dec = this.decoder;
        final double tickMs = r.tickMs();
        r.setPostStepHook(net -> dec.update(net, tickMs));
        this.brain = r;
        entityData.set(DATA_HAS_BRAIN, true);
        FruitFlyMod.LOGGER.info("Fly #{} acquired a brain ({} / {})", getId(), svc.activeBrains(), svc.maxBrains());
    }

    public void releaseBrain() {
        if (brain != null) {
            brain.setPostStepHook(null); // stop the brain thread from decoding further commands
            FruitFlyMod.BRAIN.release(brain);
            brain = null;
            encoders = null;
            decoder = null;               // latestCommand() now returns idle: the body lands / stops instead of replaying a stale command
            bodyState.escapeTicks = 0;
            bodyState.smoothedForward = 0;
            bodyState.smoothedYaw = 0;
            if (!level().isClientSide()) {
                entityData.set(DATA_HAS_BRAIN, false);
                entityData.set(DATA_ACTIVITY, 0f);
            }
        }
    }

    /** Debug/demo: drive a population at a rate for a number of game ticks (applied on the brain thread). */
    public void stimulate(String spec, double hz, int ticks) {
        if (!validSpec(spec)) return;
        synchronized (stimuli) { stimuli.add(new Object[]{spec, hz, ticks}); }
    }

    public void watch(String spec) { if (brain != null && validSpec(spec)) brain.watch(spec); }

    /** Resolve on the game thread so a bad spec is logged here instead of failing on the brain thread. */
    private boolean validSpec(String spec) {
        if (brain == null) return true;
        try {
            brain.populations.resolve(spec);
            return true;
        } catch (IllegalArgumentException e) {
            FruitFlyMod.LOGGER.warn("Fly #{}: ignoring invalid population spec '{}': {}", getId(), spec, e.getMessage());
            return false;
        }
    }

    @Override
    public void tick() {
        // before the AI step so the first telemetry payload already carries the number; runs for NoAI flies too,
        // which never reach customServerAiStep
        if (!level().isClientSide()) ensureIdentity();
        super.tick();
    }

    /** Spawn egg, /summon and natural spawns: number the fly before its add packet goes out, so clients never see "Fly-?". */
    @Override
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty, EntitySpawnReason spawnReason, SpawnGroupData groupData) {
        SpawnGroupData result = super.finalizeSpawn(level, difficulty, spawnReason, groupData);
        if (level instanceof ServerLevel) ensureIdentity(); // not from world generation (WorldGenRegion on a worker thread)
        return result;
    }

    @Override
    protected void customServerAiStep(ServerLevel level) {
        if (brain == null) {
            if (--brainRetryTicks <= 0) {
                brainRetryTicks = 20;
                acquireBrain();
            }
        }
        if (brain != null && !brain.isAlive()) {
            FruitFlyMod.LOGGER.warn("Fly #{} brain thread died: {}", getId(), brain.failure());
            releaseBrain();
        }
        // sample the world (also used by the reflex layer when there is no brain)
        SensoryFrame frame = new SensoryFrame();
        WorldSenses.sample(this, frame, FruitFlyMod.BRAIN.geometry(), FruitFlyMod.CONFIG, senseState);
        lastFrame = frame;
        if (brain != null) {
            final SensoryEncoders enc = encoders;
            final double tickMs = brain.tickMs();
            final List<Object[]> stim;
            synchronized (stimuli) {
                stim = stimuli.isEmpty() ? null : new ArrayList<>(stimuli);
                stimuli.removeIf(s -> ((Integer) s[2]) <= 1);
                for (Object[] s : stimuli) s[2] = ((Integer) s[2]) - 1;
            }
            final PopulationIndex pi = brain.populations;
            brain.submit(net -> {
                enc.apply(frame, net, tickMs);
                if (stim != null) {
                    for (Object[] s : stim) {
                        int[] ids = pi.resolve((String) s[0]);
                        net.setStimulusRate(ids, ((Integer) s[2]) > 1 ? (Double) s[1] : 0.0);
                    }
                }
            });
            BrainRunner.BrainSnapshot snap = brain.snapshot();
            entityData.set(DATA_ACTIVITY, (float) Math.min(1.0, snap.spikesThisTick / 20000.0));
        }
        MotorDecoder.MotorCommand cmd = latestCommand();
        entityData.set(DATA_MODE, (byte) cmd.mode.ordinal());
        entityData.set(DATA_FLYING, bodyState.flying);
        entityData.set(DATA_PROBOSCIS, bodyState.proboscis);
        byte wing = 0;
        if (cmd.mode == MotorDecoder.Mode.SONG) wing = (byte) (cmd.yaw >= 0 ? 2 : 1);
        entityData.set(DATA_WING_EXT, wing);
        byte groom = 0;
        if (cmd.mode == MotorDecoder.Mode.GROOM) {
            double a = cmd.groomAntenna, h = cmd.groomHead, l = cmd.groomLeg, ab = cmd.groomAbdomen;
            double m = Math.max(Math.max(a, h), Math.max(l, ab));
            groom = (byte) (m == a ? 1 : m == h ? 2 : m == l ? 3 : 4);
        }
        entityData.set(DATA_GROOM, groom);
        hunger = Math.max(0f, hunger - 1f / (20 * 600)); // ~10 minutes to get hungry
        if (tickCount % Math.max(1, FruitFlyMod.CONFIG.telemetryEveryTicks) == 0) sendTelemetry(frame, cmd);
    }

    private void sendTelemetry(SensoryFrame frame, MotorDecoder.MotorCommand cmd) {
        if (!(level() instanceof ServerLevel)) return;
        BrainRunner.BrainSnapshot snap = brain == null ? null : brain.snapshot();
        String[] chNames;
        float[] chValues;
        if (cmd.channels.isEmpty()) {
            chNames = new String[0];
            chValues = new float[0];
        } else {
            chNames = new String[cmd.channels.size()];
            chValues = new float[chNames.length];
            int k = 0;
            for (Map.Entry<String, Double> e : cmd.channels.entrySet()) {
                chNames[k] = e.getKey();
                chValues[k] = e.getValue().floatValue();
                k++;
            }
        }
        String[] popNames = snap == null ? new String[0] : snap.watchedNames;
        float[] popRates = new float[popNames.length];
        if (snap != null) for (int i = 0; i < popRates.length; i++) popRates[i] = (float) snap.watchedRatesHz[i];
        byte[] rays = new byte[senseState.rayLum == null ? 0 : senseState.rayLum.length];
        if (senseState.rayLum != null) for (int i = 0; i < rays.length; i++) rays[i] = (byte) Math.round(Math.max(0, Math.min(1, senseState.rayLum[i])) * 255);
        int[] sample;
        if (snap == null) {
            sample = new int[0];
        } else {
            int n = Math.min(FruitFlyMod.CONFIG.spikeSampleSize, snap.spikeLogNeurons.length);
            sample = new int[n];
            int stride = Math.max(1, snap.spikeLogNeurons.length / Math.max(1, n));
            for (int i = 0; i < n; i++) sample[i] = snap.spikeLogNeurons[Math.min(snap.spikeLogNeurons.length - 1, i * stride)];
        }
        BrainTelemetryPayload payload = new BrainTelemetryPayload(getId(), (byte) cmd.mode.ordinal(), bodyState.reflexDriving,
                snap == null ? 0 : (int) snap.spikesThisTick, snap == null ? 0 : snap.activeNeurons,
                snap == null ? 0f : (float) snap.realTimeFactor, chNames, chValues, popNames, popRates, rays, sample);
        lastTelemetry = payload;
        double range2 = FruitFlyMod.CONFIG.telemetryRangeBlocks * FruitFlyMod.CONFIG.telemetryRangeBlocks;
        for (ServerPlayer p : PlayerLookup.tracking(this)) {
            if (p.distanceToSqr(this) <= range2) ServerPlayNetworking.send(p, payload);
        }
    }

    // ------------------------------------------------------------------ movement

    @Override
    public void travel(Vec3 ignored) {
        if (!isEffectiveAi()) return;
        FlyBody.travel(this, latestCommand(), lastFrame, FruitFlyMod.CONFIG, bodyState);
        calculateEntityAnimation(false);
    }

    @Override
    public boolean isPushable() { return false; }

    @Override
    public boolean causeFallDamage(double fallDistance, float multiplier, DamageSource source) { return false; }

    @Override
    public boolean isFlapping() { return bodyState.flying || isFlyingState(); }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        boolean r = super.hurtServer(level, source, amount);
        if (r) damageAccum = Math.min(1f, damageAccum + amount / 2f);
        return r;
    }

    @Override
    public boolean removeWhenFarAway(double distance) { return false; }

    /** The 0.5x0.3 hitbox would otherwise stop rendering at ~49 blocks, short of the 64-block telemetry range. */
    @Override
    public boolean shouldRenderAtSqrDistance(double sqrDistance) {
        double range = 64.0 * getViewScale();
        return sqrDistance < range * range;
    }

    @Override
    protected SoundEvent getAmbientSound() { return isFlapping() ? SoundEvents.BEE_LOOP : null; }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) { return SoundEvents.BEE_HURT; }

    @Override
    protected SoundEvent getDeathSound() { return SoundEvents.BEE_DEATH; }

    @Override
    public float getVoicePitch() { return 1.6f + 0.2f * (float) Math.sin(tickCount * 0.1); }

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        TasteTable.Taste t = TasteTable.forItem(stack);
        if (t != null && !level().isClientSide()) {
            // offer food by hand: labellar contact for 2 s
            for (Map.Entry<String, Float> e : t.labellar().entrySet()) stimulate(e.getKey(), 120 * e.getValue(), 40);
            for (Map.Entry<String, Float> e : t.tarsal().entrySet()) stimulate(e.getKey(), 100 * e.getValue(), 40);
            feed(t.nutrition() * 0.2f);
            return InteractionResult.SUCCESS;
        }
        return super.mobInteract(player, hand);
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput out) {
        super.addAdditionalSaveData(out);
        out.putBoolean("Male", isMale());
        out.putFloat("FlyScale", getFlyScale());
        out.putFloat("Hunger", hunger);
        out.putInt("FlyNo", getFlyNumber());
    }

    @Override
    protected void readAdditionalSaveData(ValueInput in) {
        super.readAdditionalSaveData(in);
        setMale(in.getBooleanOr("Male", isMale()));
        setFlyScale(in.getFloatOr("FlyScale", getFlyScale()));
        hunger = in.getFloatOr("Hunger", hunger);
        // the world counter learns about this number on the first server tick (ensureIdentity), not here: chunk
        // entities are deserialised off the server thread
        in.getInt("FlyNo").ifPresent(no -> entityData.set(DATA_FLY_NO, no));
    }

    @Override
    public void remove(RemovalReason reason) {
        super.remove(reason);
        if (!level().isClientSide()) releaseBrain();
    }
}
