package com.ayoshiko.productivebeesgenesis.network;

import com.ayoshiko.productivebeesgenesis.apiary.IPbUpgradeProvider;
import com.ayoshiko.productivebeesgenesis.apiary.MekApiaryFactoryContainer;
import com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeType;
import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary;
import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiaryFactory;
import com.ayoshiko.productivebeesgenesis.util.LogThrottle;

import mekanism.api.Action;
import mekanism.api.AutomationType;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 蜂箱相关数据包的服务端处理集合
 * <p>
 * 从 {@link ModPayloads} 拆分而来，职责：
 * <ol>
 *   <li>处理蜜蜂槽位选中（{@link ApiarySelectBeePayload}）</li>
 *   <li>处理桶式蜂笼操作（{@link ApiaryCageOperationPayload}）</li>
 *   <li>处理工厂蜂箱排序切换（{@link ApiaryToggleSortingPayload}）</li>
 *   <li>处理 PB 升级卸载（{@link PbUpgradeExtractPayload}）</li>
 * </ol>
 * 所有方法包级可见，由 {@link ModPayloads#register} 通过方法引用挂载到对应数据包。
 * <p>
 * 安全模型：每个 handler 均校验玩家身份（{@link ServerPlayer}）、
 * 方块实体类型与 8 格 GUI 交互距离（8² = 64），防止恶意客户端远距离操作。
 */
final class ApiaryPayloadHandlers {

	private ApiaryPayloadHandlers() {
	}

	/**
	 * 服务端处理：选中蜜蜂槽位（Bug 9）
	 * <br/>
	 * 校验玩家当前打开的容器指向目标方块实体，校验槽位索引合法后调用 setSelectedBeeSlot。
	 * -1 表示取消选择，0~beeSlotCount-1 表示选中对应槽位。
	 */
	static void handleApiarySelectBee(ApiarySelectBeePayload payload, IPayloadContext context) {
		if (!(context.player() instanceof ServerPlayer serverPlayer)) {
			return;
		}
		if (serverPlayer.containerMenu == null || serverPlayer.level() == null) {
			return;
		}
		// 容器与目标坐标一致性校验：防止玩家打开自己的工厂蜂箱后操作附近 8 格内他人蜂箱
		// 仅校验 MekApiaryFactoryContainer（基础蜂箱容器类型不同，无法获取 pos）
		if (serverPlayer.containerMenu instanceof MekApiaryFactoryContainer factoryContainer
				&& !factoryContainer.getTileEntity().getBlockPos().equals(payload.pos())) {
			LogThrottle.warn("apiary_select_pos_mismatch",
					"玩家 {} 当前打开容器位置 {} 与目标选中蜂箱位置 {} 不一致",
					serverPlayer.getName().getString(),
					factoryContainer.getTileEntity().getBlockPos(),
					payload.pos());
			return;
		}
		BlockEntity be = serverPlayer.level().getBlockEntity(payload.pos());
		if (!(be instanceof TileEntityMekApiary apiary)) {
			return;
		}
		double distance = serverPlayer.distanceToSqr(payload.pos().getCenter());
		if (distance > NetworkSecurityConstants.GUI_INTERACTION_DISTANCE_SQ) {
			LogThrottle.warn("apiary_select_distance", "玩家 {} 尝试远距离选中蜜蜂槽：距离 {} 格",
					serverPlayer.getName().getString(), Math.sqrt(distance));
			return;
		}
		int slotIndex = payload.slotIndex();
		if (slotIndex != -1 && (slotIndex < 0 || slotIndex >= apiary.getBeeSlotCount())) {
			LogThrottle.warn("apiary_select_invalid", "玩家 {} 尝试选中无效蜜蜂槽位：{}（总槽位 {}）",
					serverPlayer.getName().getString(), slotIndex, apiary.getBeeSlotCount());
			return;
		}
		apiary.setSelectedBeeSlot(slotIndex);
	}

	/**
	 * 服务端处理：桶式蜂笼操作
	 * <br/>
	 * 玩家手持蜂笼右键点击蜜蜂槽位时触发。校验玩家身份、方块实体类型、
	 * 8格交互距离、槽位索引合法性后，从 containerMenu 获取光标蜂笼：
	 * <ul>
	 *   <li>EXTRACT（取出）：委托 {@link #handleCageExtraction} 按光标→物品栏→cageOutSlot 优先级分配</li>
	 *   <li>INSERT（放入）：调用 releaseBeeAtSlot，内部处理 cursor.shrink 和 cageOutSlot 输出</li>
	 * </ul>
	 */
	static void handleApiaryCageOperation(ApiaryCageOperationPayload payload, IPayloadContext context) {
		if (!(context.player() instanceof ServerPlayer serverPlayer)) {
			return;
		}
		if (serverPlayer.containerMenu == null || serverPlayer.level() == null) {
			return;
		}
		// 容器与目标坐标一致性校验：防止玩家打开自己的工厂蜂箱后操作附近 8 格内他人蜂箱
		// 仅校验 MekApiaryFactoryContainer（基础蜂箱容器类型不同，无法获取 pos）
		if (serverPlayer.containerMenu instanceof MekApiaryFactoryContainer factoryContainer
				&& !factoryContainer.getTileEntity().getBlockPos().equals(payload.pos())) {
			LogThrottle.warn("apiary_cage_pos_mismatch",
					"玩家 {} 当前打开容器位置 {} 与目标蜂笼操作蜂箱位置 {} 不一致",
					serverPlayer.getName().getString(),
					factoryContainer.getTileEntity().getBlockPos(),
					payload.pos());
			return;
		}
		BlockEntity be = serverPlayer.level().getBlockEntity(payload.pos());
		if (!(be instanceof TileEntityMekApiary apiary)) {
			return;
		}
		// 距离校验：标准 8 格 GUI 交互距离
		double distance = serverPlayer.distanceToSqr(payload.pos().getCenter());
		if (distance > NetworkSecurityConstants.GUI_INTERACTION_DISTANCE_SQ) {
			LogThrottle.warn("apiary_cage_distance", "玩家 {} 尝试远距离桶式操作蜜蜂槽：距离 {} 格",
					serverPlayer.getName().getString(), Math.sqrt(distance));
			return;
		}
		// 槽位索引校验
		int slotIndex = payload.slotIndex();
		if (slotIndex < 0 || slotIndex >= apiary.getBeeSlotCount()) {
			LogThrottle.warn("apiary_cage_invalid", "玩家 {} 尝试桶式操作无效蜜蜂槽位：{}（总槽位 {}）",
					serverPlayer.getName().getString(), slotIndex, apiary.getBeeSlotCount());
			return;
		}
		// 获取玩家光标手持物品
		ItemStack cursor = serverPlayer.containerMenu.getCarried();
		if (payload.operation() == ApiaryCageOperationPayload.OperationType.EXTRACT) {
			// 取出操作：按光标→物品栏→cageOutSlot 优先级分配蜂笼去向
			handleCageExtraction(serverPlayer, apiary, slotIndex, cursor);
		} else {
			// 放入操作：releaseBeeAtSlot 内部处理 cursor.shrink 和 cageOutSlot 输出
			boolean success = apiary.releaseBeeAtSlot(slotIndex, cursor);
			if (success) {
				if (cursor.isEmpty()) {
					serverPlayer.containerMenu.setCarried(ItemStack.EMPTY);
				}
				serverPlayer.containerMenu.broadcastChanges();
			}
		}
	}

	/**
	 * 处理桶式取出蜜蜂 — 按优先级分配装好的蜂笼去向
	 * <br/>
	 * 优先级：光标（仅当光标仅1个蜂笼时）→ 玩家物品栏 → cageOutSlot。
	 * 全部失败则阻止取出（不消耗蜂笼，不清空 BeeSlot），保证蜜蜂不会丢失。
	 * <p>
	 * 原理：cageBeeAtSlot 仅生成装好的蜂笼而不修改源数据，只有成功找到去处后
	 * 才调用 confirmCageExtraction 清空 BeeSlot，实现"原子性"取出语义。
	 *
	 * @param serverPlayer 服务端玩家
	 * @param apiary       蜂箱方块实体
	 * @param slotIndex    蜜蜂槽位索引
	 * @param cursor       玩家光标手持的空蜂笼
	 */
	private static void handleCageExtraction(ServerPlayer serverPlayer, TileEntityMekApiary apiary,
			int slotIndex, ItemStack cursor) {
		// 生成装好的蜂笼（不修改 BeeSlot 和 cursor）
		ItemStack filledCage = apiary.cageBeeAtSlot(slotIndex, cursor);
		if (filledCage.isEmpty()) return; // 取出失败

		// 优先级1：光标 — 仅当光标只有1个蜂笼时，装好的蜂笼直接替换光标
		if (cursor.getCount() == 1) {
			serverPlayer.containerMenu.setCarried(filledCage);
			apiary.confirmCageExtraction(slotIndex);
			serverPlayer.containerMenu.broadcastChanges();
			return;
		}

		// 优先级2：玩家物品栏（Inventory.add 对 count=1 是全接收或全拒绝）
		// 传入 copy 防止 add 内部修改 filledCage，保证优先级3仍可用原始蜂笼
		boolean inventoryAccepted = serverPlayer.getInventory().add(filledCage.copy());
		if (inventoryAccepted) {
			apiary.confirmCageExtraction(slotIndex);
			cursor.shrink(1);
			if (cursor.isEmpty()) serverPlayer.containerMenu.setCarried(ItemStack.EMPTY);
			serverPlayer.containerMenu.broadcastChanges();
			return;
		}

		// 优先级3：cageOutSlot（insertItem 自动检查同种蜜蜂组件一致性并堆叠）
		ItemStack cageRemainder = apiary.getCageOutSlot()
				.insertItem(filledCage, Action.EXECUTE, AutomationType.INTERNAL);
		if (cageRemainder.isEmpty()) {
			apiary.confirmCageExtraction(slotIndex);
			cursor.shrink(1);
			if (cursor.isEmpty()) serverPlayer.containerMenu.setCarried(ItemStack.EMPTY);
			serverPlayer.containerMenu.broadcastChanges();
			return;
		}

		// 全部失败：阻止取出，不调用 confirmCageExtraction，蜜蜂不被取走，蜂笼不消耗
	}

	/**
	 * 服务端处理：通用机械蜂箱工厂切换排序开关
	 * <br/>
	 * 校验玩家当前打开的容器是否指向目标方块实体，防止恶意客户端随意切换任意蜂箱。
	 * <p>
	 * 安全校验链：
	 * <ol>
	 *   <li>containerMenu 必须是 {@link MekApiaryFactoryContainer} — 防止玩家打开任意容器后操作 8 格内他人蜂箱</li>
	 *   <li>容器绑定的 BlockPos 必须与 payload.pos() 一致 — 防止玩家打开自己的蜂箱后操作附近他人蜂箱</li>
	 *   <li>方块实体类型必须为 {@link TileEntityMekApiaryFactory} — 防御性校验</li>
	 *   <li>玩家与方块实体距离不超过 8 格 — 标准 GUI 交互距离</li>
	 * </ol>
	 */
	static void handleApiaryToggleSorting(ApiaryToggleSortingPayload payload, IPayloadContext context) {
		if (!(context.player() instanceof ServerPlayer serverPlayer)) {
			return;
		}
		// 安全校验：玩家当前打开的容器必须是工厂版蜂箱容器
		if (!(serverPlayer.containerMenu instanceof MekApiaryFactoryContainer factoryContainer)) {
			return;
		}
		// 容器绑定的方块坐标必须与 payload 目标坐标一致
		if (!factoryContainer.getTileEntity().getBlockPos().equals(payload.pos())) {
			LogThrottle.warn("apiary_sort_pos_mismatch",
					"玩家 {} 当前打开容器位置 {} 与目标排序蜂箱位置 {} 不一致",
					serverPlayer.getName().getString(),
					factoryContainer.getTileEntity().getBlockPos(),
					payload.pos());
			return;
		}
		// 通过玩家所在 level 查找方块实体
		if (serverPlayer.level() == null) {
			return;
		}
		BlockEntity be = serverPlayer.level().getBlockEntity(payload.pos());
		if (be instanceof TileEntityMekApiaryFactory factory) {
			// 二次校验：玩家与方块实体距离不超过 8 格（标准 GUI 交互距离）
			double distance = serverPlayer.distanceToSqr(payload.pos().getCenter());
			if (distance > NetworkSecurityConstants.GUI_INTERACTION_DISTANCE_SQ) {
				LogThrottle.warn("apiary_sort_distance", "玩家 {} 尝试远距离切换蜂箱排序：距离 {} 格",
						serverPlayer.getName().getString(), Math.sqrt(distance));
				return;
			}
			factory.toggleSorting();
		}
	}

	/**
	 * 服务端处理：卸载指定类型的PB升级到输出槽
	 * <br/>
	 * 校验玩家当前打开的容器指向目标方块实体，然后调用extractPbUpgradeByType
	 * 将指定类型的升级移到输出槽。
	 * 支持所有实现 {@link IPbUpgradeProvider} 的方块实体（蜂箱含工厂版子类、离心机含工厂版子类），
	 * 通过接口多态统一处理，避免引用 ME/EME 可选依赖的具体子类（依赖倒置原则）。
	 * <p>
	 * 安全限制：{@code removeAll=true} 路径单次最多移除 64 个升级，
	 * 防止恶意客户端一次性清空异常大量的升级导致服务端性能压力。
	 */
	static void handlePbUpgradeExtract(PbUpgradeExtractPayload payload, IPayloadContext context) {
		if (!(context.player() instanceof ServerPlayer serverPlayer)) {
			return;
		}
		if (serverPlayer.containerMenu == null || serverPlayer.level() == null) {
			return;
		}
		// 防御性字符串长度校验：StreamCodec 已限制 64 字符，此处冗余校验防止协议层变更后绕过
		String upgradeTypeId = payload.upgradeTypeId();
		if (upgradeTypeId == null || upgradeTypeId.length() > NetworkSecurityConstants.MAX_UPGRADE_TYPE_ID_LENGTH) {
			LogThrottle.warn("apiary_upgrade_invalid_length", "玩家 {} 尝试卸载PB升级类型ID长度异常：{}",
					serverPlayer.getName().getString(),
					upgradeTypeId == null ? "null" : upgradeTypeId.length());
			return;
		}
		PbUpgradeType type = payload.getUpgradeType();
		if (type == null || type.isBuiltin()) {
			LogThrottle.warn("apiary_upgrade_invalid", "玩家 {} 尝试卸载无效PB升级类型：{}",
					serverPlayer.getName().getString(), payload.upgradeTypeId());
			return;
		}
		BlockEntity be = serverPlayer.level().getBlockEntity(payload.pos());
		// 接口多态判定 — 所有蜂箱和离心机均实现 IPbUpgradeProvider
		// 通过接口统一获取升级上限和提取函数，避免引用 ME/EME 可选依赖的具体子类
		if (!(be instanceof IPbUpgradeProvider provider)) {
			return;
		}
		double distance = serverPlayer.distanceToSqr(payload.pos().getCenter());
		if (distance > NetworkSecurityConstants.GUI_INTERACTION_DISTANCE_SQ) {
			LogThrottle.warn("apiary_upgrade_distance", "玩家 {} 尝试远距离卸载PB升级：距离 {} 格",
					serverPlayer.getName().getString(), Math.sqrt(distance));
			return;
		}
		if (payload.removeAll()) {
			// 单次 removeAll 操作上限 64，防止恶意客户端一次性清空异常大量升级
			int maxRemove = NetworkSecurityConstants.MAX_PB_UPGRADE_REMOVE_ALL;
			int limit = Math.min(provider.getPbUpgradeLimit(type), maxRemove);
			int removed = 0;
			for (int i = 0; i < limit; i++) {
				if (!provider.extractPbUpgradeByType(type)) {
					break;
				}
				removed++;
			}
			if (removed == maxRemove) {
				LogThrottle.warn("apiary_removeall_limit", "玩家 {} 单次 removeAll 操作达到上限 {}，可能存在滥用",
						serverPlayer.getName().getString(), maxRemove);
			}
		} else {
			provider.extractPbUpgradeByType(type);
		}
	}
}
