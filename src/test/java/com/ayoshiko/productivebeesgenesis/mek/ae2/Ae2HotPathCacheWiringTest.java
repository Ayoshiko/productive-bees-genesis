package com.ayoshiko.productivebeesgenesis.mek.ae2;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 时间加速热路径的两处记忆化接线校验（源码级断言，不需要 Minecraft/AE2 运行时）。
 * <p>
 * 这两处优化一旦被重构改回原样，行为完全正确、无任何报错，只是 MSPT 悄悄涨回去，
 * 纯逻辑单测无法发现，故用源码断言把调用点钉住。
 * <p>
 * 依据（spark 采样，NeoForge 21.1.214 / MC 1.21.1 / 44 mods）：
 * <ul>
 *   <li>gUqyZmn5q6（加速可熔炼配方）：{@code BasicInventorySlot.productivebeesgenesis$getCachedBaseLimit}
 *       自耗 1272ms / 4.24%，全服第 2 热点；</li>
 *   <li>BHSGIz87Uw（部分机器时间手杖）：同方法 1464ms / 2.44%，全服第 3 热点；</li>
 *   <li>ejYMNQjDf7（无加速）：{@code Ae2ItemFingerprint.encode} 拉取侧 432ms / 1.44%
 *       + 推送侧 408ms / 1.36%，成本来自 {@code AEItemKey.toTag} 的 Codec 编码与
 *       {@code CompoundTag.toString} 的 StringTagVisitor 遍历。</li>
 * </ul>
 */
class Ae2HotPathCacheWiringTest {

	private static String read(String relativePath) throws Exception {
		return Files.readString(Path.of(relativePath));
	}

	@Test
	@DisplayName("输出账本与输入 pending 都走 per-tile 指纹缓存，不再每次重新编码")
	void fingerprintEncodingIsMemoizedPerHost() throws Exception {
		String buffers = read("src/main/java/com/ayoshiko/productivebeesgenesis/mek/ae2/Ae2PushBuffers.java");
		assertTrue(buffers.contains("final Ae2FingerprintCache fingerprintCache = new Ae2FingerprintCache()"),
				"指纹缓存必须与其他 per-tile 缓冲同生命周期");

		String committer = read("src/main/java/com/ayoshiko/productivebeesgenesis/mek/ae2/"
				+ "Ae2OutputCommitter.java");
		assertTrue(committer.contains("buffers.fingerprintCache.get(key, registries)"),
				"输出槽收集必须复用缓存指纹");
		assertFalse(committer.contains("Ae2ItemFingerprint.encode(key, registries)"),
				"collectSlot 不得再直接编码（每个非空输出槽每刻一次）");

		String puller = read("src/main/java/com/ayoshiko/productivebeesgenesis/mek/ae2/Ae2InputPuller.java");
		assertTrue(puller.contains("fingerprintCache.get(key, level.registryAccess())"),
				"抽取前的 pending 条目位检查必须复用缓存指纹");
		assertTrue(puller.contains("buffers.fingerprintCache"),
				"缓存必须由 per-tile 缓冲传入 pullBatchForType，不能新建");
	}

	@Test
	@DisplayName("指纹缓存按 LRU 有界且随注册表切换整表失效")
	void fingerprintCacheIsBoundedAndRegistryAware() throws Exception {
		String cache = read("src/main/java/com/ayoshiko/productivebeesgenesis/mek/ae2/"
				+ "Ae2FingerprintCache.java");
		assertTrue(cache.contains("MAX_ENTRIES"), "必须有条目上限，防止内存无界增长");
		assertTrue(cache.contains("if (registries != provider)"),
				"注册表访问器变化（换存档/重启）必须整表清空，否则可能返回旧注册表的编码");
		assertTrue(cache.contains("BoundedLruMap.accessOrdered(MAX_ENTRIES)"),
				"超上限必须按 LRU 淘汰最久未使用条目；旧的\"满即整表清空\"在物品种类超上限的"
						+ "大网络里会周期性丢弃全部热条目，命中率塌陷");
		assertFalse(cache.contains("if (cache.size() >= MAX_ENTRIES) cache.clear();"),
				"不得回退到满即整表清空");
	}

	@Test
	@DisplayName("四个 getLimit 拦截点都先查已乘倍率的最终上限缓存")
	void everyGetLimitInterceptorPeeksEffectiveLimit() throws Exception {
		String[] mixins = {
			"src/main/java/com/ayoshiko/productivebeesgenesis/mixin/mek/BasicInventorySlotMixin.java",
			"src/main/java/com/ayoshiko/productivebeesgenesis/mixin/mek/"
					+ "ExtraFactoryInputInventorySlotMixin.java",
			"src/main/java/com/ayoshiko/productivebeesgenesis/mixin/mek/"
					+ "ExtraFactoryOutputInventorySlotMixin.java",
			"src/main/java/com/ayoshiko/productivebeesgenesis/mixin/mek/"
					+ "EMExtraFactoryInputInventorySlotMixin.java",
			"src/main/java/com/ayoshiko/productivebeesgenesis/mixin/mek/"
					+ "EMExtraFactoryOutputInventorySlotMixin.java",
		};
		for (String path : mixins) {
			String source = read(path);
			assertTrue(source.contains("peekEffectiveLimit(stack)"),
					path + " 必须先查最终上限缓存");
			assertTrue(source.contains("storeEffectiveLimit(stack, effective)"),
					path + " 必须回填最终上限缓存，否则每次都重算");
		}
	}

	@Test
	@DisplayName("最终上限缓存以 Item + 倍率版本为键，换供应商时立即失效")
	void effectiveLimitCacheKeyAndInvalidation() throws Exception {
		String cache = read("src/main/java/com/ayoshiko/productivebeesgenesis/inventory/SlotLimitCache.java");
		assertTrue(cache.contains("effectiveVersion == TieredInputSlot.MULTIPLIER_VERSION.get()"),
				"配置 reload 递增版本号后必须失效");
		assertTrue(cache.contains("public void invalidate()"),
				"必须提供本地立即失效入口");

		String mixin = read("src/main/java/com/ayoshiko/productivebeesgenesis/mixin/mek/"
				+ "BasicInventorySlotMixin.java");
		assertTrue(mixin.contains("if (limitCache != null) limitCache.invalidate();"),
				"setInputStackMultiplier 换供应商不递增全局版本号，必须本地清缓存");
		assertTrue(mixin.contains("if (productivebeesgenesis$inputMultiplier == null)"),
				"非本模组分等级槽位必须提前返回，不得污染缓存或改变 Mekanism 原逻辑");
	}

	@Test
	@DisplayName("输入槽容量查询复现 BasicInventorySlot 语义并保留自定义槽回退")
	void inputCapacityProbeOwnsItemMatching() throws Exception {
		String source = read("src/main/java/com/ayoshiko/productivebeesgenesis/mek/ae2/Ae2InputPuller.java");
		int methodStart = source.indexOf("private static long getSlotRemainingCapacity(");
		int methodEnd = source.indexOf("\n\t/**", methodStart);
		String method = source.substring(methodStart, methodEnd);

		assertTrue(method.contains("slot instanceof BasicInventorySlot basicSlot"),
				"标准 Mekanism 槽位必须走无分配的直接容量探测");
		assertTrue(method.contains("stack.getItem() != key.getItem()"),
				"物品不同的槽位必须在组件比较前廉价拒绝");
		assertTrue(source.contains("ItemStack.isSameItemSameComponents(stack, probe)"),
				"标准槽位必须保留组件级物品匹配语义");
		assertTrue(source.contains("stack.getComponentsPatch().isEmpty()"
				+ " && probe.getComponentsPatch().isEmpty()"),
				"普通无组件物品必须绕过完整组件映射比较");
		assertTrue(method.contains("entry.matchesComponents(slotIndex, stack, probe)"),
				"容量规划必须通过条目缓存组件匹配结果");
		assertTrue(method.contains("basicSlot.isItemValidForInsertion(probe, AutomationType.INTERNAL)"),
				"标准槽位必须保留 validator 和 AutomationType 语义");
		assertTrue(method.contains("slot.insertItem(probe, Action.SIMULATE, AutomationType.INTERNAL)"),
				"非标准 IInventorySlot 必须保留完整模拟插入回退");
		assertFalse(method.contains("key.matches(stack)"),
				"容量探测不得调用 AEItemKey.matches 造成重复组件比较");
	}

	@Test
	@DisplayName("公平轮容量规划复用有界组件匹配缓存并及时释放栈引用")
	void componentMatchCacheIsBoundedToPullEntriesAndOnePass() throws Exception {
		String source = read("src/main/java/com/ayoshiko/productivebeesgenesis/mek/ae2/Ae2InputPuller.java");
		assertTrue(source.contains("entry.beginComponentMatchCache(processCount)"),
				"每次容量规划必须为条目开启当前轮次缓存");
		assertTrue(source.contains("entry.clearComponentMatchCache()"),
				"容量规划结束后必须清除栈引用，避免复用池延长对象生命周期");
		assertTrue(source.contains("private ItemStack[] componentMatchStacks"),
				"组件缓存必须按条目和槽位有界保存");
		assertTrue(source.contains("componentMatchGenerations"),
				"组件缓存必须用轮次标记避免每次候选重置数组");
	}

	@Test
	@DisplayName("候选缓存命中后不重复分类，默认非无限模式不重复遍历过滤槽")
	void candidateSelectionReusesClassificationAndFilterAdmission() throws Exception {
		String source = read("src/main/java/com/ayoshiko/productivebeesgenesis/mek/ae2/Ae2InputPuller.java");
		int methodStart = source.indexOf("private static int getPullCandidateAmount(");
		int methodEnd = source.indexOf("\n\t/**", methodStart);
		String method = source.substring(methodStart, methodEnd);

		assertFalse(method.contains("Ae2InputCandidatePolicy.classify"),
				"已按版本缓存的候选列表不得在每轮选择时重复做 SMELTING 分类");
		assertTrue(source.contains("unlimitedMode && filter.isUnlimitedForKey"),
				"默认无无限配置时必须跳过逐键过滤槽遍历");
		assertFalse(source.contains("filter.matchesAnyEntry(entry.key"),
				"黑白名单准入结果已确定 marked 状态，不得为排序再次扫描过滤槽");
	}

	@Test
	@DisplayName("组件快速路径保留异常物品的完整堆叠语义")
	void componentFastPathsRequireCanonicalIdentityComponents() throws Exception {
		String puller = read("src/main/java/com/ayoshiko/productivebeesgenesis/mek/ae2/Ae2InputPuller.java");
		// 组件类型已提到局部变量（spark：DeferredHolder.get 曾占 1.47%），但"双方都必须显式
		// 携带 bee_type 才能绕过完整组件比较"这一堆叠语义约束不变。
		assertTrue(puller.contains("stack.has(beeTypeComponent) && probe.has(beeTypeComponent)"),
				"可配置蜜脾只有双方显式携带 bee_type 时才能绕过完整组件比较");
		assertFalse(puller.contains("ModDataComponents.BEE_TYPE.get()"),
				"热路径不得回退到逐次 DeferredHolder 注册表查找");

		String validation = read("src/main/java/com/ayoshiko/productivebeesgenesis/util/"
				+ "InputValidationCache.java");
		assertTrue(validation.contains("if (stack.getComponentsPatch().isEmpty())"),
				"普通无补丁熔炼输入必须跳过完整组件哈希");
		assertTrue(validation.contains("ItemStack.hashItemAndComponents(stack)"),
				"带组件补丁的普通输入必须保留完整哈希回退");
	}

	@Test
	@DisplayName("空 pending 与空输出账本跳过快照和逐槽检查")
	void emptyPersistentStateSkipsHotPathWork() throws Exception {
		String puller = read("src/main/java/com/ayoshiko/productivebeesgenesis/mek/ae2/Ae2InputPuller.java");
		assertTrue(puller.contains("if (hadPendingItems) {\n\t\t\tretryPendingItems"),
				"pending 为空时不得构建回送快照");

		String pusher = read("src/main/java/com/ayoshiko/productivebeesgenesis/mek/ae2/Ae2OutputPusher.java");
		assertTrue(pusher.contains("outputLedger.size() == 0 ? 0"),
				"空账本不得进入结算快照");
		assertTrue(pusher.contains("if (outputLedger.size() > 0) {\n\t\t\tentries.removeIf"),
				"空账本不得为每个输出槽查询账本");
	}

	@Test
	@DisplayName("多流体推送在同一轮复用稳定槽位列表")
	void multiFluidPushReusesOneTankSnapshot() throws Exception {
		String source = read("src/main/java/com/ayoshiko/productivebeesgenesis/mek/ae2/Ae2FluidPusher.java");
		assertTrue(source.contains("multiFluidHost.getFluidTanks()"),
				"多槽宿主必须只在推送轮开始获取槽位列表");
		assertTrue(source.contains("outputTank(host, tankSnapshot, i)"),
				"统计、匹配和扣减阶段必须复用同一槽位列表");
		assertTrue(source.contains("shrinkStackSafely(host, tankSnapshot, fluidKey, inserted, tankCount)"),
				"实际扣减不得退回到重复构建槽位列表的查询路径");
	}
}
