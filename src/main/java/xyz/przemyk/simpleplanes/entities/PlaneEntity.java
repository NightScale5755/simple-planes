package xyz.przemyk.simpleplanes.entities;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.*;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.network.IPacket;
import net.minecraft.network.datasync.DataParameter;
import net.minecraft.network.datasync.DataSerializers;
import net.minecraft.network.datasync.EntityDataManager;
import net.minecraft.particles.IParticleData;
import net.minecraft.particles.ParticleTypes;
import net.minecraft.util.*;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.vector.Quaternion;
import net.minecraft.util.math.vector.Vector2f;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.util.math.vector.Vector3f;
import net.minecraft.world.GameRules;
import net.minecraft.world.World;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.network.NetworkHooks;
import xyz.przemyk.simpleplanes.Config;
import xyz.przemyk.simpleplanes.MathUtil;
import xyz.przemyk.simpleplanes.setup.SimplePlanesRegistries;
import xyz.przemyk.simpleplanes.setup.SimplePlanesSounds;
import xyz.przemyk.simpleplanes.setup.SimplePlanesUpgrades;
import xyz.przemyk.simpleplanes.upgrades.Upgrade;
import xyz.przemyk.simpleplanes.upgrades.UpgradeType;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import static net.minecraft.util.math.MathHelper.wrapDegrees;
import static xyz.przemyk.simpleplanes.MathUtil.*;

public class PlaneEntity extends Entity implements IJumpingMount {
    protected static final DataParameter<Integer> FUEL = EntityDataManager.createKey(PlaneEntity.class, DataSerializers.VARINT);
    public static final EntitySize FLYING_SIZE = EntitySize.flexible(2F, 1.5F);
    public static final EntitySize FLYING_SIZE_EASY = EntitySize.flexible(2F, 2F);

    //negative values mean left
    public static final DataParameter<Integer> MOVEMENT_RIGHT = EntityDataManager.createKey(PlaneEntity.class, DataSerializers.VARINT);
    public static final DataParameter<Integer> BOOST_TICKS = EntityDataManager.createKey(PlaneEntity.class, DataSerializers.VARINT);
    public static final DataParameter<Quaternion> Q = EntityDataManager.createKey(PlaneEntity.class, QUATERNION_SERIALIZER);
    public Quaternion Q_Lerp = Quaternion.ONE.copy();
    public static final DataParameter<Quaternion> Q_Prev = EntityDataManager.createKey(PlaneEntity.class, QUATERNION_SERIALIZER);
    public static final DataParameter<CompoundNBT> UPGRADES_NBT = EntityDataManager.createKey(PlaneEntity.class, DataSerializers.COMPOUND_NBT);

    public static final AxisAlignedBB COLLISION_AABB = new AxisAlignedBB(-1, 0, -1, 1, 0.5, 1);
    public static final int MAX_PITCH = 20;
    private double lastYd;
    protected int poweredTicks;

    //count how many ticks since on ground
    private int groundTicks;
    public HashMap<ResourceLocation, Upgrade> upgrades = new HashMap<>();
    private float nextStepDistance;
    private float nextFlap;


    //EntityType<? extends PlaneEntity> is always AbstractPlaneEntityType but I cannot change it because minecraft
    public PlaneEntity(EntityType<? extends PlaneEntity> entityTypeIn, World worldIn) {
        super(entityTypeIn, worldIn);
        this.stepHeight = 1.5f;
    }

    public PlaneEntity(EntityType<? extends PlaneEntity> entityTypeIn, World worldIn, double x, double y, double z) {
        this(entityTypeIn, worldIn);
        setPosition(x, y, z);
    }

    @Override
    protected void registerData() {
        dataManager.register(FUEL, 0);
        dataManager.register(MOVEMENT_RIGHT, 0);
        dataManager.register(BOOST_TICKS, 0);
        dataManager.register(UPGRADES_NBT, new CompoundNBT());
        dataManager.register(Q, Quaternion.ONE);
        dataManager.register(Q_Prev, Quaternion.ONE);
    }

    public void addFuel() {
        addFuel(Config.FLY_TICKS_PER_COAL.get());
    }

    public void addFuel(Integer fuel) {
        dataManager.set(FUEL, Math.max(getFuel(), fuel));
    }

    public int getFuel() {
        return dataManager.get(FUEL);
    }

    public Quaternion getQ() {
        return dataManager.get(Q).copy();
    }

    public Quaternion getQ_Lerp() {
        return Q_Lerp.copy();
    }

    public void setQ_lerp(Quaternion q) {
        Q_Lerp = q;
    }

    public Quaternion getQ_Prev() {
        return dataManager.get(Q_Prev).copy();
    }

    public void setQ_prev(Quaternion q) {
        dataManager.set(Q_Prev, q);
    }

    public void setQ(Quaternion q) {
        dataManager.set(Q, q);
    }


    public boolean isPowered() {
        return dataManager.get(FUEL) > 0 || isCreative();
    }

    @Override
    public ActionResultType processInitialInteract(PlayerEntity player, Hand hand) {
        if (player.isSneaking() && player.getHeldItem(hand).isEmpty()) {
            boolean hasplayer = false;
            for (Entity passenger : getPassengers()) {
                if ((passenger instanceof PlayerEntity)) {
                    hasplayer = true;
                    break;
                }
            }
            if ((!hasplayer) || Config.THIEF.get()) {
                this.removePassengers();
            }
            return ActionResultType.SUCCESS;
        }
        return !world.isRemote && player.startRiding(this) ? ActionResultType.SUCCESS : ActionResultType.FAIL;
    }

    @SuppressWarnings("deprecation")
    @Override
    public boolean attackEntityFrom(DamageSource source, float amount) {
        if (this.isInvulnerableTo(source)) {
            return false;
        }
        if (!(source.getTrueSource() instanceof PlayerEntity && ((PlayerEntity) source.getTrueSource()).abilities.isCreativeMode)
                && world.getGameRules().getBoolean(GameRules.DO_ENTITY_DROPS)) {

//            for (Upgrade upgrade : upgrades.values()) {
//                entityDropItem(upgrade.getType().getUpgradeItem());
//            }
            dropItem();
        }
        if (!this.world.isRemote && !this.removed) {
            remove();
            return true;
        }
        return false;
    }

    @SuppressWarnings("rawtypes")
    protected void dropItem() {
        ItemStack itemStack = new ItemStack(((AbstractPlaneEntityType) getType()).dropItem);
        itemStack.setTagInfo("EntityTag", serializeNBT());
        entityDropItem(itemStack);
    }

    public Vector2f getHorizontalFrontPos() {
        return new Vector2f(-MathHelper.sin(rotationYaw * ((float) Math.PI / 180F)), MathHelper.cos(rotationYaw * ((float) Math.PI / 180F)));
    }

    @Override
    public EntitySize getSize(Pose poseIn) {
        if (this.getControllingPassenger() instanceof PlayerEntity) {
            return Config.EASY_FLIGHT.get() ? FLYING_SIZE_EASY : FLYING_SIZE;
        }
        return super.getSize(poseIn);
        //just hate my head in the nether ceiling
    }

    @SuppressWarnings("IntegerDivisionInFloatingPointContext")
    @Override
    public void tick() {
        super.tick();
        double max_speed = 0.8;
        double lift_factor = 1 / 12.0;
        final double gravity = -0.10;
        final double drag_mul = 0.99;
        final double drag = 0.01;
        final double drag_above_max = 0.05;
        final double take_off_speed = 0.5;


        if (Double.isNaN(getMotion().length()))
            setMotion(Vector3d.ZERO);
        LivingEntity controllingPassenger = (LivingEntity) getControllingPassenger();

        Quaternion q;
        if (world.isRemote && controllingPassenger == Minecraft.getInstance().player) {
            q = getQ_Lerp();
        } else q = getQ();
        Angels angels1 = ToEulerAngles(q);
        rotationPitch = (float) angels1.pitch;
        rotationYaw = (float) angels1.yaw;
        float rotationRoll = (float) angels1.roll;
        Angels angelsOld = angels1.copy();
        if (isPowered()) {
            if (poweredTicks % 50 == 0) {
                playSound(SimplePlanesSounds.PLANE_LOOP.get(), 0.1F, 1.0F);
            }
            ++poweredTicks;
        } else {
            poweredTicks = 0;
        }

        Vector3d oldMotion = getMotion();
        recalculateSize();
        int fuel = dataManager.get(FUEL);
        if (fuel > 0) {
            --fuel;
            dataManager.set(FUEL, fuel);
        }
        float moveForward = controllingPassenger instanceof PlayerEntity ? controllingPassenger.moveForward : 0;
        final boolean passengerSprinting = controllingPassenger != null && controllingPassenger.isSprinting();

        //pitch + movement speed
        if (getOnGround() || isAboveWater()) {
            if (groundTicks == 0)
                groundTicks = 20;
            else {
                groundTicks--;
            }
            float pitch = isLarge() ? 10 : 15;

            if ((isPowered() && moveForward > 0.0F) || isAboveWater()) {
                pitch = 0;
            }
            float f = 0.99f;

            Vector3d motion = getMotion().scale(f).add(0, -0.001, 0);
            setMotion(motion);
            rotationPitch = lerpAngle(0.1f, rotationPitch, pitch);
            if (((MathUtil.degreesDifferenceAbs(rotationPitch, 0) < 5) || (getMotion().length() > 0.4))
                    && moveForward > 0.0F && isPowered()) {
                Vector3f m = transformPos(new Vector3f(0.00f, 0, 0.06f));
                setMotion(getMotion().add(m.getX(), m.getY(), m.getZ()));
                if (getMotion().length() > take_off_speed) {
                    setMotion(getMotion().add(0, 0.05, 0));
                }
            } else if (moveForward < 0) {
                Vector3d m = getVec(rotationYaw, 0, -0.01);
                setMotion(getMotion().add(m));
            }
        } else {
            groundTicks--;
            float pitch = 0f;
            float x = 0.01f;
            if (moveForward > 0.0F) {
                pitch = passengerSprinting ? 2 : 0.5f;
                x = 0.05f;
            } else if (moveForward < 0.0F) {
                pitch = passengerSprinting ? -2 : -1;

            }
            if (passengerSprinting && isPowered()) {
                x *= 2;
                dataManager.set(FUEL, fuel - 1);
            }
            if (dataManager.get(BOOST_TICKS) > 0) {
                dataManager.set(BOOST_TICKS, dataManager.get(BOOST_TICKS) - 1);
                x *= 2;
                x += 0.1f;
            }
            if (!isPowered()) {
                x = 0;
            }

            rotationPitch += pitch;
            Vector3d motion = this.getMotion();
            double speed = motion.length();
            final double speed_x = getHorizontalLength(motion);


            speed *= drag_mul;
            speed -= drag;
            speed = Math.max(speed, 0);
            if (speed > max_speed) {
                speed = MathHelper.lerp(drag_above_max, speed, max_speed);
            }
            if (speed > 2 * max_speed) {
                speed = MathHelper.lerp(2 * drag_above_max, speed, max_speed);
            }
            if (speed == 0) {
                motion = Vector3d.ZERO;
            }
            if (motion.length() > 0)
                motion = motion.scale(speed / motion.length());

            Vector3f v = transformPos(new Vector3f(0, (float) (speed_x * lift_factor), x * 2));

            motion = motion.add(v.getX(), v.getY(), v.getZ());


            pitch = MathUtil.getPitch(motion);
            if (!getOnGround() && !isAboveWater() && motion.length() > 0.5) {
                rotationPitch = lerpAngle180(0.05f, rotationPitch, pitch);

                motion = MathUtil.getVec(MathUtil.getYaw(motion), lerpAngle180(0.2f, pitch, rotationPitch), motion.length());

            }
            motion = motion.add(0, gravity, 0);

            this.setMotion(motion);

        }

        //rotating (roll + yaw)
        //########
        float moveStrafing = controllingPassenger instanceof PlayerEntity ? controllingPassenger.moveStrafing : 0;

        float f1 = 1f;
        double turn = 0;

        if (getOnGround() || isAboveWater() || !passengerSprinting) {
            int yawdiff = 2;
            float roll = rotationRoll;
            if (degreesDifferenceAbs(rotationPitch, 0) < 45) {
                for (int i = 0; i < 360; i += 180) {
                    if (MathHelper.degreesDifferenceAbs(rotationRoll, i) < 80) {
                        roll = lerpAngle(0.1f * f1, rotationRoll, i);
                        break;
                    }
                }
            }
            int r = 15;

            if (getOnGround() || isAboveWater()) {
                turn = moveStrafing > 0 ? yawdiff : moveStrafing == 0 ? 0 : -yawdiff;
                rotationRoll = roll;

            } else if (degreesDifferenceAbs(rotationRoll, 0) > 30) {
                turn = moveStrafing > 0 ? -yawdiff : moveStrafing == 0 ? 0 : yawdiff;
                rotationRoll = roll;

            } else {
                if (moveStrafing == 0) {
                    rotationRoll = lerpAngle180(0.2f, rotationRoll, 0);
                } else if (moveStrafing > 0) {
                    rotationRoll = clamp(rotationRoll + f1, 0, r);
                } else if (moveStrafing < 0) {
                    rotationRoll = clamp(rotationRoll - f1, -r, 0);
                }
                final double roll_old = ToEulerAngles(getQ()).roll;
                if (MathUtil.degreesDifferenceAbs(roll_old, 0) < 90) {
                    turn = MathHelper.clamp(roll_old / 5.0f, -yawdiff, yawdiff);
                } else {
                    turn = MathHelper.clamp((180 - roll_old) / 5.0f, -yawdiff, yawdiff);
                }
                if (moveStrafing == 0)
                    turn = 0;

            }

        } else if (moveStrafing == 0) {
            for (int i = 0; i < 360; i += 180) {
                if (MathHelper.degreesDifferenceAbs(rotationRoll, i) < 80) {
                    rotationRoll = lerpAngle(0.01f * f1, rotationRoll, i);
                    break;
                }
            }

        } else if (moveStrafing > 0) {
            rotationRoll += f1;
        } else if (moveStrafing < 0) {
            rotationRoll -= f1;
        }


        rotationYaw -= turn;
        if (MathUtil.degreesDifferenceAbs(rotationRoll, 180) < 45)
            turn = -turn;
        for (Entity passenger : getPassengers()) {
            passenger.rotationYaw -= turn;
        }
        Vector3d motion = getMotion();
        setMotion(MathUtil.getVec(lerpAngle180(0.1f, MathUtil.getYaw(motion), rotationYaw), MathUtil.getPitch(motion), motion.length()));


        //upgrades
        HashSet<Upgrade> upgradesToRemove = new HashSet<>();
        for (Upgrade upgrade : upgrades.values()) {
            if (upgrade.tick()) {
                upgradesToRemove.add(upgrade);
            }
        }

        for (Upgrade upgrade : upgradesToRemove) {
            upgrades.remove(upgrade.getType().getRegistryName());
        }


        if (isPowered() && rand.nextInt(4) == 0 && !world.isRemote) {
            spawnSmokeParticles(fuel);
        }

        // ths code is for motion to work correctly, copied from ItemEntity, maybe there is some better solution but idk
        double l = 0.02;
        if (oldMotion.length() >= getMotion().length() && oldMotion.length() < l) {
            this.setMotion(Vector3d.ZERO);
        }

        if (!this.onGround || horizontalMag(this.getMotion()) > (double) 1.0E-5F || (this.ticksExisted + this.getEntityId()) % 4 == 0) {
            if (getMotion().length() > 0)
                this.move(MoverType.SELF, this.getMotion());
            if (this.onGround) {
                float f;
                BlockPos pos = new BlockPos(this.getPosX(), this.getPosY() - 1.0D, this.getPosZ());
                f = this.world.getBlockState(pos).getSlipperiness(this.world, pos, this) * 0.98F;
                f = Math.max(f, 0.90F);
                this.setMotion(this.getMotion().mul(f, 0.98D, f));
            }
        }

        //back to q
        q.multiply(Vector3f.ZP.rotationDegrees((float) (rotationRoll - angelsOld.roll)));
        q.multiply(Vector3f.YP.rotationDegrees((float) (rotationYaw - angelsOld.yaw)));
        q.multiply(Vector3f.XN.rotationDegrees((float) (rotationPitch - angelsOld.pitch)));
        q.normalize();
        setQ_prev(getQ_Lerp());
        setQ(q);

        if (world.isRemote) {
            setQ_lerp(q);
        }

        this.tickLerp();

    }


    protected void spawnSmokeParticles(int fuel) {
        spawnParticle(ParticleTypes.LARGE_SMOKE, new Vector3f(0, 0.5f, -1), 0);
        if ((fuel > 4 && fuel < 100) || dataManager.get(BOOST_TICKS) > 0) {
            spawnParticle(ParticleTypes.LARGE_SMOKE, new Vector3f(0, 0.5f, -1), 5);
        }
    }

    public void spawnParticle(IParticleData particleData, Vector3f relPos, int particleCount) {
        transformPos(relPos);
        relPos.add(0, 0.7f, 0);
        ((ServerWorld) world).spawnParticle(particleData,
                getPosX() + relPos.getX(),
                getPosY() + relPos.getY(),
                getPosZ() + relPos.getZ(),
                particleCount, 0, 0, 0, 0.0);
    }

    private Vector3f transformPos(Vector3f relPos) {
        Angels angels = MathUtil.ToEulerAngles(getQ());
        angels.yaw = -angels.yaw;
        angels.roll = -angels.roll;
        relPos.transform(MathUtil.ToQuaternion(angels.yaw, angels.pitch, angels.roll));
        return relPos;
    }

    @Nullable
    public Entity getControllingPassenger() {
        List<Entity> list = this.getPassengers();
        return list.isEmpty() ? null : list.get(0);
    }

    @Override
    public void readAdditional(CompoundNBT compound) {
        dataManager.set(FUEL, compound.getInt("Fuel"));
        CompoundNBT upgradesNBT = compound.getCompound("upgrades");
        dataManager.set(UPGRADES_NBT, upgradesNBT);
        deserializeUpgrades(upgradesNBT);
    }

    private void deserializeUpgrades(CompoundNBT upgradesNBT) {
        for (String key : upgradesNBT.keySet()) {
            ResourceLocation resourceLocation = new ResourceLocation(key);
            UpgradeType upgradeType = SimplePlanesRegistries.UPGRADE_TYPES.getValue(resourceLocation);
            if (upgradeType != null) {
                Upgrade upgrade = upgradeType.instanceSupplier.apply(this);
                upgrade.deserializeNBT(upgradesNBT.getCompound(key));
                upgrades.put(resourceLocation, upgrade);
            }
        }
    }

    @SuppressWarnings("ConstantConditions")
    @Override
    protected void writeAdditional(CompoundNBT compound) {
        compound.putInt("Fuel", dataManager.get(FUEL));

        CompoundNBT upgradesNBT = getUpgradesNBT();

        compound.put("upgrades", upgradesNBT);
    }

    private CompoundNBT getUpgradesNBT() {
        CompoundNBT upgradesNBT = new CompoundNBT();
        for (Upgrade upgrade : upgrades.values()) {
            upgradesNBT.put(upgrade.getType().getRegistryName().toString(), upgrade.serializeNBT());
        }
        return upgradesNBT;
    }

    @Override
    protected boolean canBeRidden(Entity entityIn) {
        return true;
    }

    @Override
    public boolean canBeRiddenInWater() {
        return upgrades.containsKey(SimplePlanesUpgrades.FLOATING.getId());
    }

    @Override
    public boolean canBeRiddenInWater(Entity e) {
        return upgrades.containsKey(SimplePlanesUpgrades.FLOATING.getId());
    }

    @Override
    public boolean canBeCollidedWith() {
        return true;
    }

    @Nullable
    @Override
    public AxisAlignedBB getCollisionBoundingBox() {
        return COLLISION_AABB.offset(getPositionVec());
    }

    @Nullable
    @Override
    public AxisAlignedBB getCollisionBox(Entity entityIn) {
        return COLLISION_AABB.offset(getPositionVec());
    }

    @Override
    public IPacket<?> createSpawnPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }

    @Override
    public void notifyDataManagerChange(DataParameter<?> key) {
        super.notifyDataManagerChange(key);
        if (UPGRADES_NBT.equals(key) && world.isRemote()) {
            deserializeUpgrades(dataManager.get(UPGRADES_NBT));
        }
        if (Q.equals(key) && world.isRemote()) {
            if (Minecraft.getInstance().player != getPlayer())
                setQ_lerp(getQ());
        }
    }

    @Override
    public double getMountedYOffset() {
        return 0.375;
    }

    @Override
    public boolean isInvulnerableTo(DamageSource source) {
        if (source.getTrueSource() != null && source.getTrueSource().isRidingSameEntity(this)) {
            return true;
        }
        return super.isInvulnerableTo(source);
    }

    @SuppressWarnings("deprecation")
    @Override
    protected void updateFallState(double y, boolean onGroundIn, BlockState state, BlockPos pos) {

        if ((onGroundIn || isAboveWater()) && !isCreative() && Config.PLANE_CRUSH.get()) {
//        if (onGroundIn||isAboveWater()) {
            final float y1 = transformPos(new Vector3f(0, -1, 0)).getY();
            if (y1 > -0.5) {

                this.onLivingFall(10, 1.0F);
                if (!this.world.isRemote && !this.removed) {
                    if (world.getGameRules().getBoolean(GameRules.DO_ENTITY_DROPS)) {
                        dropItem();
                    }
                    this.remove();
                }
            }

            this.fallDistance = 0.0F;
        }

        this.lastYd = this.getMotion().y;
    }

    public boolean isCreative() {
        return getControllingPassenger() instanceof PlayerEntity && ((PlayerEntity) getControllingPassenger()).isCreative();
    }

    public boolean getOnGround() {
        return onGround || groundTicks > 0;
    }

    public boolean isAboveWater() {
        return this.world.getBlockState(new BlockPos(this.getPositionVec().add(0, 0.4, 0))).getBlock() == Blocks.WATER;
    }

    public boolean canAddUpgrade(UpgradeType upgradeType) {
        return !upgrades.containsKey(upgradeType.getRegistryName()) && !upgradeType.occupyBackSeat && upgradeType.isPlaneApplicable.test(this);
    }

    public boolean isLarge() {
        return false;
    }


    // all code down is from boat, copyright???
    public Vector3d func_230268_c_(LivingEntity livingEntity) {
        setPositionAndUpdate(this.getPosX(), this.getPosY(), this.getPosZ());

        Vector3d vector3d = func_233559_a_(this.getWidth() * MathHelper.SQRT_2, livingEntity.getWidth(), this.rotationYaw);
        double d0 = this.getPosX() + vector3d.x;
        double d1 = this.getPosZ() + vector3d.z;
        BlockPos blockpos = new BlockPos(d0, this.getBoundingBox().maxY, d1);
        BlockPos blockpos1 = blockpos.down();
        if (!this.world.hasWater(blockpos1)) {
            for (Pose pose : livingEntity.func_230297_ef_()) {
                AxisAlignedBB axisalignedbb = livingEntity.func_233648_f_(pose);
                double d2 = this.world.func_234936_m_(blockpos);
                if (TransportationHelper.func_234630_a_(d2)) {
                    Vector3d vector3d1 = new Vector3d(d0, (double) blockpos.getY() + d2, d1);
                    if (TransportationHelper.func_234631_a_(this.world, livingEntity, axisalignedbb.offset(vector3d1))) {
                        livingEntity.setPose(pose);
                        return vector3d1;
                    }
                }

                double d3 = this.world.func_234936_m_(blockpos1);
                if (TransportationHelper.func_234630_a_(d3)) {
                    Vector3d vector3d2 = new Vector3d(d0, (double) blockpos1.getY() + d3, d1);
                    if (TransportationHelper.func_234631_a_(this.world, livingEntity, axisalignedbb.offset(vector3d2))) {
                        livingEntity.setPose(pose);
                        return vector3d2;
                    }
                }
            }
        }

        return super.func_230268_c_(livingEntity);
    }


    private int lerpSteps;
    private double lerpX;
    private double lerpY;
    private double lerpZ;

    private void tickLerp() {
        if (this.canPassengerSteer()) {
            this.lerpSteps = 0;
            this.setPacketCoordinates(this.getPosX(), this.getPosY(), this.getPosZ());
        }
        Quaternion q = this.getQ();
        Angels angels1 = ToEulerAngles(q);
        rotationPitch = (float) angels1.pitch;
        rotationYaw = (float) angels1.yaw;


        if (this.lerpSteps > 0) {
            double d0 = this.getPosX() + (this.lerpX - this.getPosX()) / (double) this.lerpSteps;
            double d1 = this.getPosY() + (this.lerpY - this.getPosY()) / (double) this.lerpSteps;
            double d2 = this.getPosZ() + (this.lerpZ - this.getPosZ()) / (double) this.lerpSteps;
            --this.lerpSteps;
            this.setPosition(d0, d1, d2);
        }
    }

    @OnlyIn(Dist.CLIENT)
    public void setPositionAndRotationDirect(double x, double y, double z, float yaw, float pitch, int posRotationIncrements, boolean teleport) {
        this.lerpX = x;
        this.lerpY = y;
        this.lerpZ = z;
        this.lerpSteps = 10;

    }

    @Override
    public void setPositionAndRotation(double x, double y, double z, float yaw, float pitch) {
        double d0 = MathHelper.clamp(x, -3.0E7D, 3.0E7D);
        double d1 = MathHelper.clamp(z, -3.0E7D, 3.0E7D);
        this.prevPosX = d0;
        this.prevPosY = y;
        this.prevPosZ = d1;
        this.setPosition(d0, y, d1);
        this.rotationYaw = yaw % 360.0F;
        this.rotationPitch = pitch % 360.0F;
        if (!world.isRemote) {
            Angels angels1 = ToEulerAngles(getQ());
            setQ(ToQuaternion(rotationYaw, rotationPitch, angels1.roll));
        }

        this.prevRotationYaw = this.rotationYaw;
        this.prevRotationPitch = this.rotationPitch;
    }

    @Override
    protected void addPassenger(Entity passenger) {
        super.addPassenger(passenger);
        if (this.canPassengerSteer() && this.lerpSteps > 0) {
            this.lerpSteps = 0;
            this.setPositionAndRotation(this.lerpX, this.lerpY, this.lerpZ, (float) this.rotationYaw, (float) this.rotationPitch);
        }
    }

    public PlayerEntity getPlayer() {
        if (getControllingPassenger() instanceof PlayerEntity)
            return (PlayerEntity) getControllingPassenger();
        return null;
    }

    public void upgradeChanged() {
        this.dataManager.set(UPGRADES_NBT, getUpgradesNBT());
    }

    @Override
    public void setJumpPower(int jumpPowerIn) {
    }

    @Override
    public boolean canJump() {
        return true;
    }

    @Override
    public void handleStartJump(int perc) {
        dataManager.set(FUEL, getFuel() - 10);
        if (perc > 80) {
            dataManager.set(BOOST_TICKS, 20);
        }
    }

    @Override
    public void handleStopJump() {

    }
}
