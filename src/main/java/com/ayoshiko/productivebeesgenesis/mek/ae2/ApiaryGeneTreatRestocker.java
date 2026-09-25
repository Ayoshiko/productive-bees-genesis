package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.storage.StorageHelper;
import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary;
import mekanism.api.Action;
import mekanism.api.AutomationType;

/** 服务端精确补货桥；仅在 AE2 加载守卫之后调用，不扫描网络库存。 */
public final class ApiaryGeneTreatRestocker {

	private ApiaryGeneTreatRestocker() { }

	/** 按完整组件键补充一槽小食；网络异常结果未知时暂停，禁止自动重复提取。 */
	public static void tick(TileEntityMekApiary tile) {
		var level = tile.getLevel();
		if (level == null || level.isClientSide) return;
		var state = tile.getGeneTreatRestock();
		long tick = level.getGameTime();
		if (!state.tryBegin(tick, tile.getBlockPos().asLong())) return;
		var slot = tile.getGeneTreatSlot();
		// 满槽不复制模板，也不访问网络；完整组件比较仍由 observe 保证。
		if (!slot.isEmpty() && slot.getCount() >= slot.getLimit(slot.getStack())) return;
		var template = state.template();
		template.setCount(template.getMaxStackSize());
		var candidate = template;
		int demand = candidate.getCount()
				- slot.insertItem(candidate, Action.SIMULATE, AutomationType.INTERNAL).getCount();
		if (demand <= 0) return;
		if (Ae2GridNodeManager.getGridNodeState(tile) != Ae2GridNodeManager.STATE_ONLINE) return;
		var holder = tile.productivebeesgenesis$getAe2StateHolder();
		var grid = Ae2GridNodeManager.getCachedGrid(holder, tile);
		var storage = Ae2GridNodeManager.getCachedMeStorage(holder, tile);
		if (grid == null || storage == null || !holder.tryAcquireNetworkWork(storage, tick)) return;
		if (!(tile.productivebeesgenesis$getAe2GridNode() instanceof IManagedGridNode node)) return;
		var key = state.extractionKey() instanceof AEItemKey cached ? cached : AEItemKey.of(template);
		if (key == null) return;
		state.cacheExtractionKey(key);
		long extracted;
		try {
			extracted = StorageHelper.poweredExtraction(grid.getEnergyService(), storage, key, demand,
					IActionSource.ofMachine(node::getNode));
			if (extracted < 0 || extracted > demand) {
				throw new IllegalStateException("Invalid AE2 gene-treat extraction count: " + extracted);
			}
			if (extracted > 0) {
				// 先登记已提取物品，再交付本地槽；满槽时由持久化状态持有。
				state.acceptExtracted(template.copyWithCount((int) extracted));
			}
		} catch (RuntimeException e) {
			state.quarantineExtraction();
			tile.setChanged();
			ProductiveBeesGenesis.LOGGER.error(
					"Gene-treat restock quarantined at {} in {}: extraction result may be unknown",
					tile.getBlockPos(), level.dimension().location(), e);
			return;
		}
		if (extracted > 0) {
			tile.setChanged();
			try {
				state.deliverPending(slot);
			} catch (RuntimeException e) {
				state.suspend();
				ProductiveBeesGenesis.LOGGER.error(
						"Gene-treat delivery suspended at {} in {}: inspect pending-item state before resuming",
						tile.getBlockPos(), level.dimension().location(), e);
			} finally {
				tile.setChanged();
			}
		}
		state.complete(tick, extracted > 0);
	}
}
