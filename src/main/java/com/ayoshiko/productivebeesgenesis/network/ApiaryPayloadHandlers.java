package com.ayoshiko.productivebeesgenesis.network;

import com.ayoshiko.productivebeesgenesis.apiary.ApiaryHoneyTreatFeeder;
import com.ayoshiko.productivebeesgenesis.apiary.IPbUpgradeProvider;
import com.ayoshiko.productivebeesgenesis.apiary.MekApiaryFactoryContainer;
import com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeType;
import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary;
import com.ayoshiko.productivebeesgenesis.util.LogThrottle;
import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.common.inventory.container.tile.MekanismTileContainer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
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
	 *   <li>处理喂食槽逐格禁用（{@link ToggleFeederSlotDisabledPayload}）</li>
	 *   <li>处理喂食槽批量禁用/恢复（{@link SetAllFeederSlotsDisabledPayload}）</li>
	 * </ol>
	 * 所有方法包级可见，由 {@link ModPayloads#register} 通过方法引用挂载到对应数据包。
	 * <p>
	 * 所有服务端操作先通过 {@link MachineMenuAccess} 复核当前菜单和实时权限，
	 * 再校验对应宿主类型、槽位和操作预算。
	 */
final class ApiaryPayloadHandlers {

	/**
	 * 喂食槽禁用类操作共用的限频 key
	 * <br/>
	 * 单格切换与批量设置共用同一预算：批量代价高于单格，若各自计费会给恶意客户端
	 * 留出"交替发两种包绕过限频"的空间。
	 */
	private static final String FEEDER_SLOT_TOGGLE_RATE_KEY = "feeder_slot_toggle";

	private ApiaryPayloadHandlers() {
	}

	static void handleToggleApiaryGeneTreatRestock(ToggleApiaryGeneTreatRestockPayload payload,
			IPayloadContext context) {
		if (!MachineMenuAccess.canAccess(context.player(), payload.pos())) {
			return;
		}
		ServerPlayer player = (ServerPlayer) context.player();
		var container = (MekanismTileContainer<?>) player.containerMenu;
		if (!(container.getTileEntity() instanceof TileEntityMekApiary apiary)) {
			return;
		}
		if (!PayloadRateLimiter.tryAccept(player, "gene_treat_restock_toggle",
				NetworkSecurityConstants.PAYLOAD_RATE_LIMIT_INTERVAL_MS)) return;
		apiary.setGeneTreatRestockEnabled(!apiary.isGeneTreatRestockEnabled());
	}

	static void handleToggleApiaryDirectEject(ToggleApiaryDirectEjectPayload payload, IPayloadContext context) {
		if (!MachineMenuAccess.canAccess(context.player(), payload.pos())) {
			return;
		}
		ServerPlayer serverPlayer = (ServerPlayer) context.player();
		if (!PayloadRateLimiter.tryAccept(serverPlayer, "apiary_handleToggleApiaryDirectEject",
				NetworkSecurityConstants.PAYLOAD_RATE_LIMIT_INTERVAL_MS)) return;
		var tileContainer = (MekanismTileContainer<?>) serverPlayer.containerMenu;
		if (!(tileContainer.getTileEntity() instanceof TileEntityMekApiary apiary)) return;
		apiary.toggleDirectEject();
	}

	/**
	 * 服务端处理：切换喂食槽物品转化开关
	 * <br/>
	 * 安全模型与 {@link #handleToggleApiaryDirectEject} 一致：玩家身份 → 打开的容器指向同一方块
	 * → 方块实体类型 → 8 格交互距离，避免恶意客户端远距离改别人蜂箱的开关。
	 */
	static void handleToggleApiaryFeederConversion(ToggleApiaryFeederConversionPayload payload,
			IPayloadContext context) {
		if (!MachineMenuAccess.canAccess(context.player(), payload.pos())) {
			return;
		}
		ServerPlayer serverPlayer = (ServerPlayer) context.player();
		if (!PayloadRateLimiter.tryAccept(serverPlayer, "apiary_handleToggleApiaryFeederConversion",
				NetworkSecurityConstants.PAYLOAD_RATE_LIMIT_INTERVAL_MS)) return;
		var tileContainer = (MekanismTileContainer<?>) serverPlayer.containerMenu;
		if (!(tileContainer.getTileEntity() instanceof TileEntityMekApiary apiary)) return;
		apiary.toggleFeederConversion();
	}

	/**
	 * 服务端处理：切换单个喂食槽的禁用状态
	 * <br/>
	 * 安全模型与 {@link #handleToggleApiaryDirectEject} 一致（玩家身份 → 容器指向同一方块
	 * → 方块实体类型 → 8 格交互距离），额外做槽位索引边界校验。
	 * 空格子由 {@code toggleFeederSlotDisabled} 内部拒绝（"格子没有物品时无效"）。
	 * <p>
	 * 限频：单格切换是低频 GUI 操作，但一次切换会触发容器状态广播，
	 * 故与 AE2 过滤类包同款按玩家 500ms 限频，防止恶意客户端高频发包放大流量。
	 */
	static void handleToggleFeederSlotDisabled(ToggleFeederSlotDisabledPayload payload, IPayloadContext context) {
		if (!MachineMenuAccess.canAccess(context.player(), payload.pos())) {
			return;
		}
		ServerPlayer serverPlayer = (ServerPlayer) context.player();
		var tileContainer = (MekanismTileContainer<?>) serverPlayer.containerMenu;
		if (!(tileContainer.getTileEntity() instanceof TileEntityMekApiary apiary)) return;
		int slotIndex = payload.slotIndex();
		if (slotIndex < 0 || slotIndex >= apiary.getFeederSlotManager().getFeederSlotCount()) {
			LogThrottle.warn("feeder_slot_toggle_invalid", "玩家 {} 尝试切换无效喂食槽位：{}（总槽位 {}）",
					serverPlayer.getName().getString(), slotIndex,
					apiary.getFeederSlotManager().getFeederSlotCount());
			return;
		}
		if (!PayloadRateLimiter.tryAccept(serverPlayer, FEEDER_SLOT_TOGGLE_RATE_KEY,
				NetworkSecurityConstants.PAYLOAD_RATE_LIMIT_INTERVAL_MS)) {
			return;
		}
		apiary.toggleFeederSlotDisabled(slotIndex);
	}

	/**
	 * 服务端处理：批量设置全部喂食槽禁用状态（Shift + 点击「禁」按钮）
	 * <br/>
	 * 校验链与 {@link #handleToggleFeederSlotDisabled} 一致（少了槽位索引校验，因为本包不带索引），
	 * 并共用 {@link #FEEDER_SLOT_TOGGLE_RATE_KEY} 限频预算。
	 * 空格子由 {@code setAllFeederSlotsDisabled} 内部跳过。
	 */
	static void handleSetAllFeederSlotsDisabled(SetAllFeederSlotsDisabledPayload payload,
			IPayloadContext context) {
		if (!MachineMenuAccess.canAccess(context.player(), payload.pos())) {
			return;
		}
		ServerPlayer serverPlayer = (ServerPlayer) context.player();
		var tileContainer = (MekanismTileContainer<?>) serverPlayer.containerMenu;
		if (!(tileContainer.getTileEntity() instanceof TileEntityMekApiary apiary)) return;
		if (!PayloadRateLimiter.tryAccept(serverPlayer, FEEDER_SLOT_TOGGLE_RATE_KEY,
				NetworkSecurityConstants.PAYLOAD_RATE_LIMIT_INTERVAL_MS)) {
			return;
		}
		apiary.setAllFeederSlotsDisabled(payload.disabled());
	}

	/**
	 * 服务端处理：选中蜜蜂槽位（Bug 9）
	 * <br/>
	 * 校验玩家当前打开的容器指向目标方块实体，校验槽位索引合法后调用 setSelectedBeeSlot。
	 * -1 表示取消选择，0~beeSlotCount-1 表示选中对应槽位。
	 */
	static void handleApiarySelectBee(ApiarySelectBeePayload payload, IPayloadContext context) {
		if (!MachineMenuAccess.canAccess(context.player(), payload.pos())) {
			return;
		}
		ServerPlayer serverPlayer = (ServerPlayer) context.player();
		if (!PayloadRateLimiter.tryAccept(serverPlayer, "apiary_handleApiarySelectBee",
				NetworkSecurityConstants.PAYLOAD_RATE_LIMIT_INTERVAL_MS)) return;
		var tileContainer = (MekanismTileContainer<?>) serverPlayer.containerMenu;
		if (!(tileContainer.getTileEntity() instanceof TileEntityMekApiary apiary)) return;
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
	 * 玩家手持蜂笼或 PB 资源蜜蜂刷怪蛋右键点击蜜蜂槽位时触发。校验玩家身份、方块实体类型、
	 * 8格交互距离、槽位索引合法性后，从 containerMenu 获取光标蜂笼：
	 * <ul>
	 *   <li>EXTRACT（取出）：委托 {@link #handleCageExtraction} 按光标→物品栏→cageOutSlot 优先级分配</li>
	 *   <li>INSERT（放入）：调用 releaseBeeAtSlot，内部处理 cursor.shrink 和 cageOutSlot 输出</li>
	 *   <li>INSERT_SPAWN_EGG（刷怪蛋放入）：服务端校验 PB 类型后直接写入空槽</li>
	 * </ul>
	 */
	static void handleApiaryCageOperation(ApiaryCageOperationPayload payload, IPayloadContext context) {
		if (!MachineMenuAccess.canAccess(context.player(), payload.pos())) {
			return;
		}
		ServerPlayer serverPlayer = (ServerPlayer) context.player();
		if (!PayloadRateLimiter.tryAccept(serverPlayer, "apiary_handleApiaryCageOperation",
				NetworkSecurityConstants.PAYLOAD_RATE_LIMIT_INTERVAL_MS)) return;
		var tileContainer = (MekanismTileContainer<?>) serverPlayer.containerMenu;
		if (!(tileContainer.getTileEntity() instanceof TileEntityMekApiary apiary)) return;
		// 槽位索引校验
		int slotIndex = payload.slotIndex();
		if (slotIndex < 0 || slotIndex >= apiary.getBeeSlotCount()) {
			LogThrottle.warn("apiary_cage_invalid", "玩家 {} 尝试桶式操作无效蜜蜂槽位：{}（总槽位 {}）",
					serverPlayer.getName().getString(), slotIndex, apiary.getBeeSlotCount());
			return;
		}
		// 获取玩家光标手持物品
		ItemStack cursor = serverPlayer.containerMenu.getCarried();
		ApiaryCageOperationPayload.OperationType operation = payload.operation();
		if (operation == ApiaryCageOperationPayload.OperationType.EXTRACT) {
			// 取出操作：按光标→物品栏→cageOutSlot 优先级分配蜂笼去向
			handleCageExtraction(serverPlayer, apiary, slotIndex, cursor);
		} else if (operation == ApiaryCageOperationPayload.OperationType.INSERT) {
			// 放入操作：releaseBeeAtSlot 内部处理 cursor.shrink 和 cageOutSlot 输出
			boolean success = apiary.releaseBeeAtSlot(slotIndex, cursor);
			if (success) {
				if (cursor.isEmpty()) {
					serverPlayer.containerMenu.setCarried(ItemStack.EMPTY);
				}
				serverPlayer.containerMenu.broadcastChanges();
			}
		} else if (operation == ApiaryCageOperationPayload.OperationType.INSERT_SPAWN_EGG) {
			boolean success = apiary.insertBeeFromSpawnEgg(slotIndex, cursor, serverPlayer);
			if (success) {
				if (cursor.isEmpty()) {
					serverPlayer.containerMenu.setCarried(ItemStack.EMPTY);
				}
				serverPlayer.containerMenu.broadcastChanges();
			}
		}
	}

	/**
	 * 服务端处理：在机械蜂箱 GUI 中给指定蜜蜂喂食基因小食。
	 * <p>
	 * 校验当前容器绑定位置、交互距离、槽位边界和服务端光标物品后，委托
	 * {@link ApiaryHoneyTreatFeeder} 复用 Productive Bees 原版喂食行为。
	 */
	static void handleApiaryFeedBee(ApiaryFeedBeePayload payload, IPayloadContext context) {
		if (!MachineMenuAccess.canAccess(context.player(), payload.pos())) {
			return;
		}
		ServerPlayer serverPlayer = (ServerPlayer) context.player();
		if (!PayloadRateLimiter.tryAccept(serverPlayer, "apiary_handleApiaryFeedBee",
				NetworkSecurityConstants.PAYLOAD_RATE_LIMIT_INTERVAL_MS)) return;
		var tileContainer = (MekanismTileContainer<?>) serverPlayer.containerMenu;
		if (!(tileContainer.getTileEntity() instanceof TileEntityMekApiary apiary)) return;

		int slotIndex = payload.slotIndex();
		if (slotIndex < 0 || slotIndex >= apiary.getBeeSlotCount()) return;
		ItemStack cursor = serverPlayer.containerMenu.getCarried();
		if (ApiaryHoneyTreatFeeder.feedGeneTreat(apiary, slotIndex, serverPlayer, cursor)) {
			if (cursor.isEmpty()) {
				serverPlayer.containerMenu.setCarried(ItemStack.EMPTY);
			}
			serverPlayer.containerMenu.broadcastChanges();
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
	 * 统一入口复核权限、位置和距离；此处限定工厂蜂箱容器和宿主。
	 */
	static void handleApiaryToggleSorting(ApiaryToggleSortingPayload payload, IPayloadContext context) {
		if (!MachineMenuAccess.canAccess(context.player(), payload.pos())) {
			return;
		}
		ServerPlayer serverPlayer = (ServerPlayer) context.player();
		if (!PayloadRateLimiter.tryAccept(serverPlayer, "apiary_handleApiaryToggleSorting",
				NetworkSecurityConstants.PAYLOAD_RATE_LIMIT_INTERVAL_MS)) return;
		// 安全校验：玩家当前打开的容器必须是工厂版蜂箱容器
		if (!(serverPlayer.containerMenu instanceof MekApiaryFactoryContainer factoryContainer)) {
			return;
		}
		factoryContainer.getTileEntity().toggleSorting();
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
		if (!MachineMenuAccess.canAccess(context.player(), payload.pos())) {
			return;
		}
		ServerPlayer serverPlayer = (ServerPlayer) context.player();
		if (!PayloadRateLimiter.tryAccept(serverPlayer, "apiary_handlePbUpgradeExtract",
				NetworkSecurityConstants.PAYLOAD_RATE_LIMIT_INTERVAL_MS)) return;
		PbUpgradeType type = payload.getUpgradeType();
		if (type == null || type.isBuiltin()) {
			LogThrottle.warn("apiary_upgrade_invalid", "玩家 {} 尝试卸载无效PB升级类型：{}",
					serverPlayer.getName().getString(), payload.upgradeTypeId());
			return;
		}
		var tileContainer = (MekanismTileContainer<?>) serverPlayer.containerMenu;
		// 接口多态判定 — 所有蜂箱和离心机均实现 IPbUpgradeProvider
		// 通过接口统一获取升级上限和提取函数，避免引用 ME/EME 可选依赖的具体子类
		if (!(tileContainer.getTileEntity() instanceof IPbUpgradeProvider provider)) {
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
