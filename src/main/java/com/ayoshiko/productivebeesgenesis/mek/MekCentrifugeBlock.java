package com.ayoshiko.productivebeesgenesis.mek;

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
import net.minecraft.network.chat.MutableComponent;
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
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

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
	 * 掉落物 — 使用 vanilla 标准 {@code saveToItem} 路径保留完整方块实体数据
	 * <br/>
	 * {@code BlockEntity.saveToItem} 内部执行：
	 * <ol>
	 *   <li>{@code saveCustomOnly} → {@code saveAdditional}：保存 PB进度/PB升级/流体罐到 NBT</li>
	 *   <li>{@code removeComponentsFromTag}：移除已被 collectImplicitComponents 处理的 MEK 标准字段</li>
	 *   <li>{@code BlockItem.setBlockEntityData}：设置 BLOCK_ENTITY_DATA 组件（含 id）</li>
	 *   <li>{@code collectComponents} → {@code collectImplicitComponents}：收集 ATTACHED_ITEMS/UPGRADES 等组件</li>
	 *   <li>{@code stack.applyComponents}：设置组件到 ItemStack</li>
	 * </ol>
	 * 放置时 NeoForge 双路径恢复：
	 * <ol>
	 *   <li>{@code loadCustomOnly} → {@code loadAdditional}：从 BLOCK_ENTITY_DATA 恢复 PB进度/PB升级</li>
	 *   <li>{@code applyImplicitComponents}：从 ATTACHED_ITEMS/UPGRADES 恢复 MEK 标准槽位（输出槽/输入槽/能量槽）</li>
	 * </ol>
	 * PB进度/PB升级不在 ContainerType 中，不会被 applyImplicitComponents 覆盖。
	 * <p>
	 * 此路径统一了镐子挖掘和扳手拆卸的数据保存方式，确保两者 NBT 结构一致、物品栏可合并。
	 */
	@Override
	protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
		List<ItemStack> drops = super.getDrops(state, params);
		if (params.getOptionalParameter(LootContextParams.BLOCK_ENTITY) instanceof TileEntityUpdateable updateable) {
			// getDrops 幂等防护：二次调用时数据已清空，跳过重复序列化（防异常场景读到空数据）
			boolean alreadySerialized = updateable instanceof TileEntityMekCentrifuge centrifuge0
					&& centrifuge0.isDropsSerialized();
			if (!alreadySerialized) {
				HolderLookup.Provider provider = params.getLevel().registryAccess();
				for (ItemStack drop : drops) {
					if (drop.is(this.asItem())) {
						// vanilla 标准路径：saveToItem 同时设置 BLOCK_ENTITY_DATA 和 DataComponents
						updateable.saveToItem(drop, provider);
						if (updateable instanceof TileEntityMekanism mekanismTile && mekanismTile.getCustomName() != null) {
							drop.set(DataComponents.CUSTOM_NAME, mekanismTile.getCustomName());
						}
					}
				}
				// 保存数据后清空所有槽位，防止 setRemoved 触发 Ejector 重复 popResource
				if (updateable instanceof TileEntityMekCentrifuge centrifugeTile) {
					centrifugeTile.markDropsSerialized();
					centrifugeTile.saveAllItemsForDrop();
				}
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
	 * 方块被移除时清理 — 对齐 MEK 原版 {@code BlockMekanism.onRemove}
	 * <br/>
	 * NeoForge 1.21.1 方块破坏顺序：{@code onRemove} 在 {@code getDrops} 之前调用，
	 * 因此<strong>不能在此清空槽位</strong>，否则 {@code getDrops} 读到空数据。
	 * 槽位清空由 {@code getDrops} 中的 {@code saveAllItemsForDrop} 在 {@code saveToItem} 后执行。
	 */
	@Override
	protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
		if (state.hasBlockEntity() && !state.is(newState.getBlock())) {
			TileEntityUpdateable tile = WorldUtils.getTileEntity(TileEntityUpdateable.class, level, pos);
			if (tile != null) {
				tile.blockRemoved();
			}
		}
		super.onRemove(state, level, pos, newState, isMoving);
	}
}
