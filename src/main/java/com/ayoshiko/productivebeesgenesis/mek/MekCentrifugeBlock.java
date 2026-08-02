package com.ayoshiko.productivebeesgenesis.mek;

import java.util.ArrayList;
import java.util.List;

import com.ayoshiko.productivebeesgenesis.util.WrenchCapabilityHelper;
import mekanism.common.block.attribute.Attribute;
import mekanism.common.block.attribute.AttributeState;
import mekanism.common.block.attribute.AttributeStateFacing;
import mekanism.common.block.attribute.Attributes;
import mekanism.common.block.interfaces.IHasDescription;
import mekanism.common.block.interfaces.IHasTileEntity;
import mekanism.common.block.interfaces.ITypeBlock;
import mekanism.common.block.states.BlockStateHelper;
import mekanism.common.content.blocktype.BlockType;
import mekanism.common.content.blocktype.BlockTypeTile;
import mekanism.common.lib.security.ISecurityTile;
import mekanism.common.network.to_client.security.PacketSyncSecurity;
import mekanism.common.registration.impl.TileEntityTypeRegistryObject;
import mekanism.common.tile.base.TileEntityMekanism;
import mekanism.common.tile.base.TileEntityUpdateable;
import mekanism.common.util.WorldUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.network.PacketDistributor;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * MEK离心机方块
 * <br/>
 * 参考Mek-Energistics的MeMekanismMachineBlock，支持Mekanism的Attribute系统。
 * 实现IHasTileEntity/ITypeBlock/IHasDescription接口，与Mekanism的GUI/侧面配置/升级体系兼容。
 * <p>
 * 支持基础机器和工厂版，通过泛型TYPE参数区分BlockType类型。
 */
public class MekCentrifugeBlock<TILE extends TileEntityMekanism, TYPE extends BlockTypeTile<TILE>>
		extends Block implements IHasDescription, ITypeBlock, IHasTileEntity<TILE> {

	/** 方块状态属性：朝向 + 活跃状态 */
	private static final List<Attribute> STATE_ATTRIBUTES = List.of(
			new AttributeStateFacing(), Attributes.ACTIVE_LIGHT);

	private final TYPE blockType;

	public MekCentrifugeBlock(TYPE blockType) {
		super(properties(blockType));
		this.blockType = blockType;
		// 使用所有AttributeState设置默认状态（facing + active）
		BlockState defaultState = this.stateDefinition.any();
		for (Attribute attr : STATE_ATTRIBUTES) {
			if (attr instanceof AttributeState atr) {
				defaultState = atr.getDefaultState(defaultState);
			}
		}
		this.registerDefaultState(defaultState);
	}

	/** 根据BlockType属性调整方块属性 */
	private static BlockBehaviour.Properties properties(BlockTypeTile<?> blockType) {
		BlockBehaviour.Properties props = BlockBehaviour.Properties.of()
				.strength(3.5F, 16.0F)
				.requiresCorrectToolForDrops();
		for (Attribute attribute : blockType.getAll()) {
			attribute.adjustProperties(props);
		}
		return props;
	}

	@Override
	public BlockType getType() {
		return blockType;
	}

	@Override
	public mekanism.api.text.ILangEntry getDescription() {
		return blockType.getDescription();
	}

	@Override
	public MutableComponent getName() {
		return super.getName();
	}

	@Override
	public TileEntityTypeRegistryObject<TILE> getTileType() {
		return blockType.getTileType();
	}

	/** 使用Mekanism Attribute系统填充BlockState定义 */
	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		super.createBlockStateDefinition(builder);
		List<Property<?>> properties = new ArrayList<>();
		for (Attribute attr : STATE_ATTRIBUTES) {
			if (attr instanceof AttributeState atr) {
				atr.fillBlockStateContainer(this, properties);
			}
		}
		if (!properties.isEmpty()) {
			builder.add(properties.toArray(new Property[0]));
		}
	}

	/** 放置时根据玩家朝向设置facing方向 — 参考Mekanism BlockMekanism */
	@Nullable
	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		return BlockStateHelper.getStateForPlacement(super.getStateForPlacement(context), context);
	}

	/** 邻居方块变化时通知TileEntity更新红石状态 — 参考Mekanism BlockTile */
	@Override
	protected void neighborChanged(BlockState state, Level world, BlockPos pos, Block neighborBlock,
									BlockPos neighborPos, boolean isMoving) {
		if (!world.isClientSide) {
			TileEntityMekanism tile = WorldUtils.getTileEntity(TileEntityMekanism.class, world, pos);
			if (tile != null) {
				tile.onNeighborChange(neighborBlock, neighborPos);
			}
		}
	}

	/** 右键空手 — 打开GUI */
	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
												Player player, BlockHitResult hitResult) {
		if (level.getBlockEntity(pos) instanceof TileEntityMekanism tile) {
			if (level.isClientSide) {
				return InteractionResult.SUCCESS;
			}
			return tile.openGui(player);
		}
		return InteractionResult.PASS;
	}

	/**
	 * 右键物品 — 扳手拆卸/旋转
	 * <br/>
	 * 模块 1 修复（v2.4 最终版）：客户端通过 {@link WrenchCapabilityHelper} 判定扳手后返回 SUCCESS
	 * 阻止 GUI 打开，服务端委托 {@code tile.tryWrench} 处理（与 MEK 原版 {@code BlockTile.useItemOn} 一致）。
	 * tryWrench 通过 {@link mekanism.common.tags.MekanismTags.Items#CONFIGURATORS} 标签判定扳手，
	 * 该标签引用 {@code c:tools/wrench}，AE2/OmniTools 扳手均在此标签中。
	 * <ul>
	 *   <li>shift+右键：tryWrench → tryWrenchDismantle → dismantleBlock → 拆卸</li>
	 *   <li>右键（非shift）：tryWrench → tryWrenchRotate → 旋转方向</li>
	 * </ul>
	 * 详见 {@link com.ayoshiko.productivebeesgenesis.apiary.MekApiaryBlock#useItemOn} 的完整说明。
	 */
	@Override
	protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level,
			BlockPos pos, Player player, InteractionHand hand,
			BlockHitResult hitResult) {
		if (stack.isEmpty()) {
			return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
		}
		TileEntityMekanism tile = WorldUtils.getTileEntity(TileEntityMekanism.class, level, pos);
		if (tile == null) {
			return ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION;
		}
		boolean isWrench = WrenchCapabilityHelper.canUseAsWrench(stack);
		// 客户端：扳手返回 SUCCESS 阻止 GUI 打开，非扳手走默认交互
		if (level.isClientSide) {
			if (isWrench) {
				return ItemInteractionResult.SUCCESS;
			}
			return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
		}
		// 服务端：shift+扳手优先拆卸，直接返回 SUCCESS 阻止 omnitools 的 Item.useOn 拦截
		// 详见 MekApiaryBlock.useItemOn 的根因说明
		if (player.isShiftKeyDown() && isWrench) {
			if (tile.getRadiationScale() <= 0) {
				WorldUtils.dismantleBlock(state, level, pos, tile, player, stack);
				return ItemInteractionResult.SUCCESS;
			}
			return ItemInteractionResult.FAIL;
		}
		// 非 shift 场景委托 tryWrench 处理旋转
		return tile.tryWrench(state, player, stack).getInteractionResult();
	}

	/**
	 * 掉落物 — 保留DataComponent + 自定义方块实体数据（Bug 6 + Task 10 合并方案）
	 * <br/>
	 * Bug 6原理：离心机的 PB 配方处理进度不在 MEK 标准 DataComponents 体系中，
	 * collectComponents 无法序列化，导致扳手拆卸后 PB 处理进度丢失。
	 * 修复：将自定义 NBT 写入 BLOCK_ENTITY_DATA 组件，放置时自动调用 loadAdditional 恢复。
	 * <p>
	 * Task 10 修复：完全覆盖 bug 根因
	 * <br/>
	 * 原 getDrops 中 {@code drop.set(DataComponents.BLOCK_ENTITY_DATA, CustomData.of(customNbt))}
	 * 完全覆盖了 {@code drop.applyComponents(updateable.collectComponents())} 写入的完整 BLOCK_ENTITY_DATA,
	 * 导致 MEK 标准组件数据（如红石控制、能量、安全拥有者等）丢失,放置后方块实体状态不一致。
	 * <p>
	 * 合并方案原理：读取 collectComponents 写入的现有 BLOCK_ENTITY_DATA,
	 * 将 customNbt 字段逐个合并进去（跳过 "id" 键避免覆盖方块实体类型）,
	 * 保证 MEK 标准数据 + 自定义数据同时保留。仅当现有 BLOCK_ENTITY_DATA 为 null 时直接覆盖。
	 * <p>
	 * 模块 3 Bug 1：保存 BLOCK_ENTITY_DATA 后调用 {@link TileEntityMekCentrifuge#saveAllItemsForDrop()}
	 * 清空所有槽位，防止 setRemoved 触发 Ejector 重复 popResource 导致物品复制。
	 */
	@Override
	protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
		List<ItemStack> drops = super.getDrops(state, params);
		if (params.getOptionalParameter(LootContextParams.BLOCK_ENTITY) instanceof TileEntityUpdateable updateable) {
			HolderLookup.Provider provider = params.getLevel().registryAccess();
			for (ItemStack drop : drops) {
				if (drop.is(this.asItem())) {
					drop.applyComponents(updateable.collectComponents());
					if (updateable instanceof TileEntityMekanism mekanismTile && mekanismTile.getCustomName() != null) {
						drop.set(DataComponents.CUSTOM_NAME, mekanismTile.getCustomName());
					}
					// Bug 6 + Task 10：写入自定义方块实体数据，确保扳手拆卸后 PB 配方处理进度不丢失
					// 合并方案：避免覆盖 collectComponents 写入的 MEK 标准 BLOCK_ENTITY_DATA
					if (updateable instanceof com.ayoshiko.productivebeesgenesis.ICustomDataPersistable persistable) {
						CompoundTag customNbt = persistable.saveCustomDataForItem(provider);
						if (!customNbt.isEmpty()) {
							net.minecraft.world.item.component.CustomData existing = drop.get(DataComponents.BLOCK_ENTITY_DATA);
							if (existing != null) {
								// 合并方案：读取现有 BLOCK_ENTITY_DATA,将 customNbt 字段合并（跳过 id 避免覆盖方块实体类型）
								CompoundTag merged = existing.copyTag();
								for (String key : customNbt.getAllKeys()) {
									if (!"id".equals(key)) {
										merged.put(key, customNbt.get(key));
									}
								}
								drop.set(DataComponents.BLOCK_ENTITY_DATA,
										net.minecraft.world.item.component.CustomData.of(merged));
							} else {
								// 现有为空,直接覆盖（无 MEK 标准数据需要保留）
								drop.set(DataComponents.BLOCK_ENTITY_DATA,
										net.minecraft.world.item.component.CustomData.of(customNbt));
							}
						}
					}
				}
			}
			// 模块 3 Bug 1：所有数据保存到掉落物后清空槽位，防止 setRemoved 触发 Ejector 重复 popResource
			if (updateable instanceof TileEntityMekCentrifuge centrifugeTile) {
				centrifugeTile.saveAllItemsForDrop();
			}
		}
		return drops;
	}

	/** 放置时初始化方块实体并设置安全拥有者 */
	@Override
	public void setPlacedBy(Level level, BlockPos pos, BlockState state,
			@Nullable LivingEntity placer, ItemStack stack) {
		super.setPlacedBy(level, pos, state, placer, stack);
		if (level.getBlockEntity(pos) instanceof TileEntityUpdateable updateable) {
			updateable.onAdded();
			// 设置安全系统拥有者（参考BlockMekanism.setPlacedBy）
			if (updateable instanceof ISecurityTile securityTile
					&& securityTile.getOwnerUUID() == null && placer != null) {
				securityTile.setOwnerUUID(placer.getUUID());
				if (!level.isClientSide && placer instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
					// Task 6：定向发送给放置者，避免全服广播（安全拥有者仅需放置者客户端同步）
					PacketDistributor.sendToPlayer(serverPlayer, new PacketSyncSecurity(placer.getUUID()));
				}
			}
		}
	}

	/**
	 * 方块被移除时清理 — 模块 2 修复（v2.4）
	 * <br/>
	 * 参考MEK原版 {@code BlockMekanism.onRemove}：调用 {@code tile.blockRemoved()} 触发清理。
	 * <p>
	 * 模块 2 根因修复：v2.3 的 {@code saveAllItemsForDrop} 仅在 {@code getDrops} 中调用，
	 * 但创造模式左键破坏不走 {@code getDrops}，导致槽位未清空，{@code setRemoved} 触发
	 * Ejector 组件 {@code popResource} 将物品弹出世界。
	 * <p>
	 * 修复方案：覆写 {@code onRemove}，在 {@code tile.blockRemoved()} 之前调用
	 * {@code saveAllItemsForDrop} 清空所有槽位。这样无论是否走 {@code getDrops}，
	 * {@code setRemoved} 时 Ejector 检测到空槽位，不执行 {@code popResource}。
	 * <p>
	 * 调用顺序保证：
	 * <ul>
	 *   <li>非创造模式：{@code getDrops}（保存数据到掉落物 + 清空槽位）→ {@code onRemove}（幂等清空）→ {@code setRemoved}</li>
	 *   <li>创造模式：{@code onRemove}（清空槽位）→ {@code setRemoved}（不popResource）</li>
	 *   <li>扳手拆卸：{@code WorldUtils.dismantleBlock} 内部 {@code getDrops} → {@code onRemove} → {@code setRemoved}</li>
	 * </ul>
	 * 性能：{@code saveAllItemsForDrop} 是幂等操作，重复调用无副作用，仅在槽位非空时执行清空。
	 */
	@Override
	protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
		if (state.hasBlockEntity() && !state.is(newState.getBlock())) {
			// 模块 2 修复：清空所有槽位，防止 setRemoved 触发 Ejector.popResource
			if (!level.isClientSide && level.getBlockEntity(pos) instanceof TileEntityMekCentrifuge centrifugeTile) {
				centrifugeTile.saveAllItemsForDrop();
			}
			TileEntityUpdateable tile = WorldUtils.getTileEntity(TileEntityUpdateable.class, level, pos);
			if (tile != null) {
				tile.blockRemoved();
			}
		}
		super.onRemove(state, level, pos, newState, isMoving);
	}
}
