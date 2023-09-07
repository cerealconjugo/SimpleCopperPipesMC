package net.lunade.copper.block_entity;

import com.mojang.serialization.DataResult;
import com.mojang.serialization.Dynamic;
import net.lunade.copper.CopperPipeMain;
import net.lunade.copper.FittingPipeDispenses;
import net.lunade.copper.PipeMovementRestrictions;
import net.lunade.copper.PoweredPipeDispenses;
import net.lunade.copper.blocks.CopperFitting;
import net.lunade.copper.blocks.CopperPipe;
import net.lunade.copper.blocks.CopperPipeProperties;
import net.lunade.copper.leaking_pipes.LeakingPipeManager;
import net.lunade.copper.pipe_nbt.MoveablePipeDataHandler;
import net.lunade.copper.registry.RegisterCopperBlockEntities;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.WorldlyContainerHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.gameevent.BlockPositionSource;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.gameevent.GameEventListener;
import net.minecraft.world.level.gameevent.PositionSource;
import net.minecraft.world.level.gameevent.vibrations.VibrationSystem;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

public class CopperPipeEntity extends AbstractSimpleCopperBlockEntity implements GameEventListener.Holder<VibrationSystem.Listener>, VibrationSystem {

    public int transferCooldown;
    public int dispenseCooldown;
    public int noteBlockCooldown;
    public boolean canDispense;
    public boolean corroded;
    public boolean shootsControlled;
    public boolean shootsSpecial;
    public boolean canAccept;

    private VibrationSystem.Data vibrationData;
    private final VibrationSystem.Listener vibrationListener;
    private final VibrationSystem.User vibrationUser;

    public BlockPos inputGameEventPos;
    public Vec3 gameEventNbtVec3;

    public CopperPipeEntity(BlockPos blockPos, BlockState blockState) {
        super(RegisterCopperBlockEntities.COPPER_PIPE_ENTITY, blockPos, blockState, MOVE_TYPE.FROM_PIPE);
        this.noteBlockCooldown = 0;
        this.vibrationUser = this.createVibrationUser();
        this.vibrationData = new VibrationSystem.Data();
        this.vibrationListener = new VibrationSystem.Listener(this);
    }

    @Override
    public void setItem(int i, ItemStack itemStack) {
        this.unpackLootTable(null);
        if (itemStack != null) {
            this.getItems().set(i, itemStack);
            if (itemStack.getCount() > this.getMaxStackSize()) {
                itemStack.setCount(this.getMaxStackSize());
            }
        }
    }

    @Override
    public void serverTick(Level level, BlockPos blockPos, BlockState blockState) {
        VibrationSystem.Ticker.tick(this.level, this.getVibrationData(), this.createVibrationUser());
        super.serverTick(level, blockPos, blockState);
        if (!level.isClientSide) {
            if (this.noteBlockCooldown > 0) {
                --this.noteBlockCooldown;
            }
            if (this.dispenseCooldown > 0) {
                --this.dispenseCooldown;
            } else {
                this.dispense((ServerLevel) level, blockPos, blockState);
                int i = 0;
                if (level.getBlockState(blockPos.relative(blockState.getValue(BlockStateProperties.FACING).getOpposite())).getBlock() instanceof CopperFitting fitting) {
                    i = fitting.cooldown;
                } else {
                    if (blockState.getBlock() instanceof CopperPipe pipe) {
                        i = Mth.floor(pipe.cooldown * 0.5);
                    }
                }
                this.dispenseCooldown = i;
            }

            if (this.transferCooldown > 0) {
                --this.transferCooldown;
            } else {
                this.pipeMove(level, blockPos, blockState);
            }
            if (blockState.getValue(CopperPipeProperties.HAS_WATER) && blockState.getValue(BlockStateProperties.FACING) != Direction.UP) {
                LeakingPipeManager.addPos(level, blockPos);
            }
        }
    }

    @Override
    public void updateBlockEntityValues(Level world, BlockPos pos, @NotNull BlockState state) {
        if (state.getBlock() instanceof CopperPipe) {
            Direction direction = state.getValue(BlockStateProperties.FACING);
            Direction directionOpp = direction.getOpposite();
            Block dirBlock = world.getBlockState(pos.relative(direction)).getBlock();
            BlockState oppState = world.getBlockState(pos.relative(directionOpp));
            Block oppBlock = oppState.getBlock();
            this.canDispense = (dirBlock == Blocks.AIR || dirBlock == Blocks.WATER) && (oppBlock != Blocks.AIR && oppBlock != Blocks.WATER);
            this.corroded = oppBlock == CopperFitting.CORRODED_FITTING || state.getBlock() == CopperPipe.CORRODED_PIPE;
            this.shootsControlled = oppBlock == Blocks.DROPPER;
            this.shootsSpecial = oppBlock == Blocks.DISPENSER;
            this.canAccept = !(oppBlock instanceof CopperPipe) && !(oppBlock instanceof CopperFitting) && !oppState.isRedstoneConductor(world, pos);
        }
    }

    @Nullable
    public static Container getContainerAt(Level world, @NotNull BlockPos blockPos) {
        return getContainerAt(world, (double)blockPos.getX() + 0.5D, (double)blockPos.getY() + 0.5D, (double)blockPos.getZ() + 0.5D);
    }

    @Nullable
    public static Container getContainerAt(@NotNull Level level, double d, double e, double f) {
        BlockEntity blockEntity;
        Container container = null;
        BlockPos blockPos = BlockPos.containing(d, e, f);
        BlockState blockState = level.getBlockState(blockPos);
        Block block = blockState.getBlock();
        if (block instanceof WorldlyContainerHolder worldlyContainerHolder) {
            container = worldlyContainerHolder.getContainer(blockState, level, blockPos);
        } else if (blockState.hasBlockEntity() && (blockEntity = level.getBlockEntity(blockPos)) instanceof Container && (container = (Container) blockEntity) instanceof ChestBlockEntity && block instanceof ChestBlock) {
            container = ChestBlock.getContainer((ChestBlock)block, blockState, level, blockPos, true);
        }
        return container;
    }

    public void pipeMove(Level level, BlockPos blockPos, @NotNull BlockState blockState) {
        Direction facing = blockState.getValue(BlockStateProperties.FACING);
        boolean bl1 = moveOut(level, blockPos, facing);
        int bl2 = moveIn(level, blockPos, blockState, facing);
        if (bl1 || bl2 >= 2) {
            setCooldown(blockState);
            setChanged(level, blockPos, blockState);
            if (bl2 == 3) {
                level.playSound(null, blockPos, CopperPipeMain.ITEM_IN, SoundSource.BLOCKS, 0.2F, (level.random.nextFloat() * 0.25F) + 0.8F);
            }
        }
    }

    public static boolean canTransfer(Level level, BlockPos pos, boolean to, @NotNull CopperPipeEntity copperPipe) {
        if (copperPipe.transferCooldown > 0) {
            return false;
        }
        BlockEntity entity = level.getBlockEntity(pos);
        if (entity != null) {
            if (entity instanceof CopperPipeEntity pipe) {
                return to || pipe.transferCooldown <= 0;
            }
            if (entity instanceof CopperFittingEntity) {
                return false;
            }
            if (to) {
                PipeMovementRestrictions.CanTransferTo<BlockEntity> canTransfer = PipeMovementRestrictions.getCanTransferTo(entity);
                if (canTransfer != null) {
                    return canTransfer.canTransfer((ServerLevel)level, pos, level.getBlockState(pos), copperPipe, entity);
                }
            } else {
                PipeMovementRestrictions.CanTakeFrom<BlockEntity> canTake = PipeMovementRestrictions.getCanTakeFrom(entity);
                if (canTake != null) {
                    return canTake.canTake((ServerLevel)level, pos, level.getBlockState(pos), copperPipe, entity);
                }
            }
            return true;
        }
        return false;
    }

    private int moveIn(Level level, BlockPos blockPos, BlockState blockState, Direction facing) {
        Direction opposite = facing.getOpposite();
        BlockPos offsetOppPos = blockPos.relative(opposite);
        Container container = getContainerAt(level, offsetOppPos);
        if (container != null && canTransfer(level, offsetOppPos, false, this)) {
            if (HopperBlockEntity.isEmptyContainer(container, opposite)) {
                return 0;
            }
            if (HopperBlockEntity.getSlots(container, opposite).anyMatch(i -> tryTakeInItemFromSlot(container, i, opposite))) {
                if (blockState.is(CopperPipeMain.SILENT_PIPES)) {
                    return 2;
                }
                Block block = level.getBlockState(offsetOppPos).getBlock();
                if (!(block instanceof CopperPipe) && !(block instanceof CopperFitting)) {
                    return 3;
                }
                return 2;
            }
        }
        return 0;
    }

    private boolean tryTakeInItemFromSlot(Container container, int i, Direction direction) {
        ItemStack itemStack = container.getItem(i);
        if (!itemStack.isEmpty() && HopperBlockEntity.canTakeItemFromContainer(this, container, itemStack, i, direction)) {
            ItemStack itemStack2 = itemStack.copy();
            ItemStack itemStack3 = addItem(container, this, container.removeItem(i, 1), null);
            if (itemStack3.isEmpty()) {
                container.setChanged();
                return true;
            }
            container.setItem(i, itemStack2);
        }
        return false;
    }

    public static ItemStack addItem(@Nullable Container container, Container container2, ItemStack itemStack, @Nullable Direction direction) {
        if (container2 instanceof WorldlyContainer worldlyContainer) {
            if (direction != null) {
                int[] is = worldlyContainer.getSlotsForFace(direction);
                int i = 0;
                while (i < is.length) {
                    if (itemStack.isEmpty()) return itemStack;
                    itemStack = tryMoveInItem(container, container2, itemStack, is[i], direction);
                    ++i;
                }
                return itemStack;
            }
        }
        int j = container2.getContainerSize();
        int i = 0;
        while (i < j) {
            if (itemStack.isEmpty()) return itemStack;
            itemStack = tryMoveInItem(container, container2, itemStack, i, direction);
            ++i;
        }
        return itemStack;
    }

    private boolean moveOut(Level world, BlockPos blockPos, Direction facing) {
        BlockPos offsetPos = blockPos.relative(facing);
        Container inventory2 = getContainerAt(world, offsetPos);
        if (inventory2 != null && canTransfer(world, offsetPos, true, this)) {
            Direction opp = facing.getOpposite();
            boolean canMove = true;
            BlockState state = world.getBlockState(offsetPos);
            if (state.getBlock() instanceof CopperPipe) {
                canMove = state.getValue(BlockStateProperties.FACING) != facing;
            }
            if (canMove) {
                for (int i = 0; i < this.getContainerSize(); ++i) {
                    if (HopperBlockEntity.isFullContainer(inventory2, facing)) {
                        return false;
                    }
                    ItemStack stack = this.getItem(i);
                    if (!stack.isEmpty()) {
                        setCooldown(world, offsetPos);
                        ItemStack itemStack = stack.copy();
                        ItemStack itemStack2 = addItem(this, inventory2, this.removeItem(i, 1), opp);
                        if (itemStack2.isEmpty()) {
                            inventory2.setChanged();
                            return true;
                        }
                        this.setItem(i, itemStack);
                    }
                }

            }
        }
        return false;
    }

    private static ItemStack tryMoveInItem(@Nullable Container container, Container container2, ItemStack itemStack, int i, @Nullable Direction direction) {
        ItemStack itemStack2 = container2.getItem(i);
        if (HopperBlockEntity.canPlaceItemInContainer(container2, itemStack, i, direction)) {
            int k;
            boolean bl = false;
            if (itemStack2.isEmpty()) {
                container2.setItem(i, itemStack);
                itemStack = ItemStack.EMPTY;
                bl = true;
            } else if (HopperBlockEntity.canMergeItems(itemStack2, itemStack)) {
                int j = itemStack.getMaxStackSize() - itemStack2.getCount();
                k = Math.min(itemStack.getCount(), j);
                itemStack.shrink(k);
                itemStack2.grow(k);
                bl = k > 0;
            }
            if (bl) {
                container2.setChanged();
            }
        }
        return itemStack;
    }

    private boolean dispense(ServerLevel serverWorld, BlockPos blockPos, BlockState blockState) {
        Direction direction = blockState.getValue(BlockStateProperties.FACING);
        Direction directionOpp = direction.getOpposite();
        boolean powered = blockState.getValue(CopperPipe.POWERED);
        if (this.canDispense) {
            BlockSourceImpl blockPointerImpl = new BlockSourceImpl(serverWorld, blockPos);
            int i = this.chooseNonEmptySlot(serverWorld.random);
            if (!(i < 0)) {
                ItemStack itemStack = this.getItem(i);
                if (!itemStack.isEmpty()) {
                    ItemStack itemStack2;
                    int o = 4;
                    if (this.shootsControlled) { //If Dropper
                        o = 10;
                        serverWorld.playSound(null, blockPos, CopperPipeMain.LAUNCH, SoundSource.BLOCKS, 0.2F, (serverWorld.random.nextFloat()*0.25F) + 0.8F);
                    } else if (this.shootsSpecial) { //If Dispenser, Use Pipe-Specific Launch Length
                        if (blockState.getBlock() instanceof CopperPipe pipe) {
                            o = pipe.dispenserShotLength;
                            serverWorld.playSound(null, blockPos, CopperPipeMain.LAUNCH, SoundSource.BLOCKS, 0.2F, (serverWorld.random.nextFloat()*0.25F) + 0.8F);
                        } else {
                            o= 12;
                        }
                    }
                    boolean silent = blockState.is(CopperPipeMain.SILENT_PIPES);
                    if (serverWorld.getBlockState(blockPos.relative(directionOpp)).getBlock() instanceof CopperFitting) {
                        itemStack2 = canonShoot(blockPointerImpl, itemStack, blockState, o, powered, true, silent, this.corroded);
                    } else {
                        itemStack2 = canonShoot(blockPointerImpl, itemStack, blockState, o, powered, false, silent, this.corroded);
                        blockPointerImpl.getLevel().levelEvent(2000, blockPointerImpl.getPos(), direction.get3DDataValue());
                    }
                    this.setItem(i, itemStack2);
                    return true;
                }
            }
        }
        return false;
    }

    private ItemStack canonShoot(BlockSource blockPointer, ItemStack itemStack, BlockState state, int i, boolean powered, boolean fitting, boolean silent, boolean corroded) {
        ServerLevel world = blockPointer.getLevel();
        BlockPos pos = blockPointer.getPos();
        Direction direction = state.getValue(BlockStateProperties.FACING);
        Position position = CopperPipe.getOutputLocation(blockPointer, direction);
        ItemStack itemStack2 = itemStack;
        if (powered) { //Special Behavior When Powered
            PoweredPipeDispenses.PoweredDispense poweredDispense = PoweredPipeDispenses.getDispense(itemStack2.getItem());
            if (poweredDispense != null) {
                itemStack2=itemStack.split(1);
                poweredDispense.dispense(world, itemStack2, i, direction, position, state, corroded, pos, this);
                if (!fitting && !silent) {
                    world.playSound(null, pos, CopperPipeMain.ITEM_OUT, SoundSource.BLOCKS, 0.2F, (world.random.nextFloat()*0.25F) + 0.8F);
                    world.gameEvent(null, GameEvent.ENTITY_PLACE, pos);
                }
                return itemStack;
            }
        }
        if (fitting) {
            FittingPipeDispenses.FittingDispense fittingDispense = FittingPipeDispenses.getDispense(itemStack2.getItem());
            if (fittingDispense != null) { //Particle Emitters With Fitting
                fittingDispense.dispense(world, itemStack2, i, direction, position, state, corroded, pos, this);
            } else { //Spawn Item W/O Sound With Fitting
                itemStack2=itemStack.split(1);
                spawnItem(world, itemStack2, i, direction, position, direction, corroded);
                world.levelEvent(2000, pos, direction.get3DDataValue());
            }
        } else {
            itemStack2=itemStack.split(1);
            world.levelEvent(2000, blockPointer.getPos(), direction.get3DDataValue());
            spawnItem(world, itemStack2, i, direction, position, direction, corroded);
            if (!silent) {
                world.gameEvent(null, GameEvent.ENTITY_PLACE, pos);
                world.playSound(null, blockPointer.getPos(), CopperPipeMain.ITEM_OUT, SoundSource.BLOCKS, 0.2F, (world.random.nextFloat() * 0.25F) + 0.8F);
            }
        }
        return itemStack;
    }

    public static void spawnItem(Level world, ItemStack itemStack, int i, Direction direction, Position position, Direction facing, boolean corroded) { //Simply Spawn An Item
        double d = position.x();
        double e = position.y();
        double f = position.z();
        if (direction.getAxis() == Direction.Axis.Y) {
            e -= 0.125D;
        } else {
            e -= 0.15625D;
        }
        double x = 0;
        double y = 0;
        double z = 0;
        Direction.Axis axis = facing.getAxis();
        x = axis == Direction.Axis.X ? (i * facing.getStepX()) * 0.1 : corroded ? (world.random.nextDouble() * 0.6) - 0.3 : x;
        y = axis == Direction.Axis.Y ? (i * facing.getStepY()) * 0.1 : corroded ? (world.random.nextDouble() * 0.6) - 0.3 : y;
        z = axis == Direction.Axis.Z ? (i * facing.getStepZ()) * 0.1 : corroded ? (world.random.nextDouble() * 0.6) - 0.3 : z;
        ItemEntity itemEntity = new ItemEntity(world, d, e, f, itemStack);
        itemEntity.setDeltaMovement(x, y, z);
        world.addFreshEntity(itemEntity);
    }

    public int chooseNonEmptySlot(RandomSource random) {
        this.unpackLootTable(null);
        int i = -1;
        int j = 1;
        for (int k = 0; k < this.inventory.size(); ++k) {
            if (!this.inventory.get(k).isEmpty() && random.nextInt(j++) == 0) {
                i = k;
            }
        } return i;
    }

    public void setCooldown(BlockState state) {
        int i = 2;
        if (state.getBlock() instanceof CopperPipe pipe) {
            i = pipe.cooldown;
        }
        this.transferCooldown = i;
    }

    public static void setCooldown(Level world, BlockPos blockPos) {
        BlockEntity entity = world.getBlockEntity(blockPos);
        BlockState state = world.getBlockState(blockPos);
        if (entity instanceof CopperPipeEntity pipe) {
            pipe.setCooldown(state);
        }
    }

    @Override
    public void load(CompoundTag nbtCompound) {
        super.load(nbtCompound);
        this.transferCooldown = nbtCompound.getInt("transferCooldown");
        this.dispenseCooldown = nbtCompound.getInt("dispenseCooldown");
        this.noteBlockCooldown = nbtCompound.getInt("noteBlockCooldown");
        this.canDispense = nbtCompound.getBoolean("canDispense");
        this.corroded = nbtCompound.getBoolean("corroded");
        this.shootsControlled = nbtCompound.getBoolean("shootsControlled");
        this.shootsSpecial = nbtCompound.getBoolean("shootsSpecial");
        this.canAccept = nbtCompound.getBoolean("canAccept");
        if (nbtCompound.contains("listener", 10)) {
            DataResult<Data> var10000 = Data.CODEC.parse(new Dynamic<>(NbtOps.INSTANCE, nbtCompound.getCompound("listener")));
            Objects.requireNonNull(CopperPipeMain.LOGGER);
            var10000.resultOrPartial(CopperPipeMain.LOGGER::error).ifPresent((data) -> this.vibrationData = data);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag nbtCompound) {
        super.saveAdditional(nbtCompound);
        nbtCompound.putInt("transferCooldown", this.transferCooldown);
        nbtCompound.putInt("dispenseCooldown", this.dispenseCooldown);
        nbtCompound.putInt("noteBlockCooldown", this.noteBlockCooldown);
        nbtCompound.putBoolean("canDispense", this.canDispense);
        nbtCompound.putBoolean("corroded", this.corroded);
        nbtCompound.putBoolean("shootsControlled", this.shootsControlled);
        nbtCompound.putBoolean("shootsSpecial", this.shootsSpecial);
        nbtCompound.putBoolean("canAccept", this.canAccept);
        DataResult<Tag> dataResult = Data.CODEC.encodeStart(NbtOps.INSTANCE, this.vibrationData);
        Objects.requireNonNull(CopperPipeMain.LOGGER);
        dataResult.resultOrPartial(CopperPipeMain.LOGGER::error).ifPresent((tag) -> nbtCompound.put("listener", tag));
    }

    public VibrationSystem.User createVibrationUser() {
        return new VibrationUser(this.getBlockPos());
    }

    @Override
    @NotNull
    public VibrationSystem.Data getVibrationData() {
        return this.vibrationData;
    }

    @Override
    @NotNull
    public VibrationSystem.User getVibrationUser() {
        return this.vibrationUser;
    }

    @Override
    @NotNull
    public VibrationSystem.Listener getListener() {
        return this.vibrationListener;
    }

    public class VibrationUser implements VibrationSystem.User {
        protected final BlockPos blockPos;
        private final PositionSource positionSource;

        public VibrationUser(BlockPos blockPos) {
            this.blockPos = blockPos;
            this.positionSource = new BlockPositionSource(blockPos);
        }

        @Override
        public int getListenerRadius() {
            return 8;
        }

        @Override
        @NotNull
        public PositionSource getPositionSource() {
            return this.positionSource;
        }

        @Override
        public boolean canTriggerAvoidVibration() {
            return false;
        }

        @Override
        public boolean canReceiveVibration(ServerLevel serverLevel, BlockPos blockPos, GameEvent gameEvent, @Nullable GameEvent.Context context) {
            boolean placeDestroy = gameEvent == GameEvent.BLOCK_DESTROY || gameEvent == GameEvent.BLOCK_PLACE;
            if ((serverLevel.getBlockState(blockPos).getBlock() instanceof CopperPipe) || (blockPos == CopperPipeEntity.this.getBlockPos() && placeDestroy)) {
                return false;
            }
            if (CopperPipeEntity.this.canAccept) {
                CopperPipeEntity.this.moveablePipeDataHandler.addSaveableMoveablePipeNbt(new MoveablePipeDataHandler.SaveableMovablePipeNbt(gameEvent, Vec3.atCenterOf(blockPos), context, CopperPipeEntity.this.getBlockPos()).withShouldMove(true).withShouldSave(true));
                return true;
            }
            return false;
        }

        @Override
        public void onReceiveVibration(ServerLevel serverLevel, BlockPos blockPos, GameEvent gameEvent, @Nullable Entity entity, @Nullable Entity entity2, float f) {

        }

        @Override
        public void onDataChanged() {
            CopperPipeEntity.this.setChanged();
        }

        @Override
        public boolean requiresAdjacentChunksToBeTicking() {
            return true;
        }
    }

    @Override
    public boolean canAcceptMoveableNbt(MOVE_TYPE moveType, Direction moveDirection, BlockState fromState) {
        if (moveType == MOVE_TYPE.FROM_FITTING) {
            return this.getBlockState().getValue(BlockStateProperties.FACING) == moveDirection;
        }
        return this.getBlockState().getValue(BlockStateProperties.FACING) == moveDirection || moveDirection == fromState.getValue(BlockStateProperties.FACING);
    }

    @Override
    public boolean canMoveNbtInDirection(Direction direction, BlockState state) {
        return direction != state.getValue(BlockStateProperties.FACING).getOpposite();
    }

    @Override
    public void dispenseMoveableNbt(ServerLevel serverWorld, BlockPos blockPos, BlockState blockState) {
        if (this.canDispense) {
            ArrayList<MoveablePipeDataHandler.SaveableMovablePipeNbt> nbtList = this.moveablePipeDataHandler.getSavedNbtList();
            if (!nbtList.isEmpty()) {
                for (MoveablePipeDataHandler.SaveableMovablePipeNbt nbt : nbtList) {
                    if (nbt.getShouldMove()) {
                        nbt.dispense(serverWorld, blockPos, blockState, this);
                    }
                }
                this.moveMoveableNbt(serverWorld, blockPos, blockState);
            }
        }
    }

}
