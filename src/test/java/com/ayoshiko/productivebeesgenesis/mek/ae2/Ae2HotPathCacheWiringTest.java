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
 *   <li>BkTP3d9oSc（JDTE 时间加速 + 398 mods，TPS 12.13）：本模组 total 5.88%，其中
 *       {@code Ae2InputPuller.pullInputs} 4.55%。四条子热点分别是
 *       保留下限实时探测 612ms、指纹编码 372ms（仅为 pending 闸门服务）、
 *       SMELTING 分类链 784ms（含 {@code DeferredHolder.value} 320ms）、
 *       逐槽 validator 探测 236ms；全服第 5 热方法 {@code AEItemKey.equals} 1416ms
 *       亦主要来自候选去重与按完整键记忆的缓存。</li>
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
		assertTrue(method.contains("entry.acceptsProbe(basicSlot, probe)"),
				"validator 判定必须走按轮次记忆的入口，不得为每个槽位重复调用整条校验链");
		assertTrue(source.contains("slot.isItemValidForInsertion(probe, AutomationType.INTERNAL)"),
				"标准槽位必须保留 validator 和 AutomationType 语义");
		assertTrue(source.contains("if (validatorState >= 0 && validatorSlotType == slotType)"),
				"validator 记忆必须以槽位实现类为守卫，遇自定义槽实现立即重新判定");
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

	@Test
	@DisplayName("多流体外部能力只扫描非空槽，且空仓保留 capability 哨兵")
	void externalFluidCapabilityUsesActiveTankSnapshot() throws Exception {
		String holder = read("src/main/java/com/ayoshiko/productivebeesgenesis/mek/fluid/MultiFluidTankHolder.java");
		String view = read("src/main/java/com/ayoshiko/productivebeesgenesis/mek/fluid/ExternalFluidTankView.java");
		assertTrue(view.contains("new CopyOnWriteArrayList<>(refreshed)"),
				"外部流体能力必须持有可复用的活跃槽视图");
		assertTrue(view.contains("refreshIfNeeded();"),
				"带方向的外部访问必须在返回列表前刷新失效快照");
		assertTrue(view.contains("private final AtomicLong invalidationVersion"),
				"活跃槽失效必须使用代数计数，避免并发刷新丢失通知");
		assertTrue(holder.contains("BasicFluidTank.output(tankCapacity, this::onTankContentsChanged)"),
				"槽内容变化必须使活跃槽快照失效");
		assertTrue(view.contains("refreshed.add(source.get(0));"),
				"空仓必须保留一个哨兵槽，避免 capability 缓存为空");
		assertTrue(holder.contains("return externalTankView.forTick(gameTimeSupplier.getAsLong());"),
				"活跃槽仍需按游戏刻轮转，避免单一流体槽饥饿");
		assertTrue(holder.contains("if (side == null) return unmodifiableTanksView;"),
				"内部/MEK 弹出访问必须保留完整槽位顺序");
	}

	@Test
	@DisplayName("三处 per-host 布尔判定缓存统一走无锁分代表，不得回退到同步 LRU")
	void perHostBooleanCachesUseLockFreeGenerationalMemo() throws Exception {
		String[] caches = {
			"src/main/java/com/ayoshiko/productivebeesgenesis/mek/ae2/Ae2SmeltingInputCache.java",
			"src/main/java/com/ayoshiko/productivebeesgenesis/mek/ae2/Ae2TagFilterCache.java",
			"src/main/java/com/ayoshiko/productivebeesgenesis/mek/ae2/Ae2CombProcessableCache.java",
		};
		for (String path : caches) {
			String source = read(path);
			// 断言构造调用而不是类型名：三个类的注释里都会提到这个容器，只匹配类型名
			// 会让断言被注释"意外满足"，改了实现也不报警。
			assertTrue(source.contains("new BoundedBooleanMemo<>("),
					path + " 必须使用无锁分代记忆表");
			assertFalse(source.contains("synchronized "),
					path + " 热路径不得加锁：只有 tick 线程读写，跨线程失效走 requestClear");
			assertFalse(source.contains("new LinkedHashMap<>(64, 0.75f, true)"),
					path + " 不得回退到访问顺序 LinkedHashMap（命中也要重排链表）");
			assertTrue(source.contains(".requestClear();"),
					path + " 跨线程失效必须只投递请求，由 tick 线程惰性清表");
		}
	}

	@Test
	@DisplayName("SMELTING 判定按 Item 分档记忆，并把 Mekanism 输入缓存句柄随配方版本缓存")
	void smeltingCacheKeysByItemAndCachesRecipeHandle() throws Exception {
		String source = read("src/main/java/com/ayoshiko/productivebeesgenesis/mek/ae2/"
				+ "Ae2SmeltingInputCache.java");
		assertTrue(source.contains("BoundedBooleanMemo<Item> plainItems"),
				"无组件补丁的键必须按 Item 引用记忆，避开 AEItemKey.equals 的组件比较");
		assertTrue(source.contains("if (input.getComponentsPatch().isEmpty())"),
				"分档条件必须是「有无组件补丁」，否则会牺牲组件敏感配方的正确性");
		assertTrue(source.contains("BoundedBooleanMemo<AEItemKey> componentKeys"),
				"带组件补丁的键必须退化为完整键，保持 ComponentSensitiveInputCache 语义");
		assertTrue(source.contains("private InputRecipeCache.SingleItem<ItemStackToItemStackRecipe> inputCache"),
				"Mekanism 输入缓存句柄必须缓存，避免每次未命中都穿一层 DeferredHolder.value");
		assertTrue(source.contains("inputCache = MekanismRecipeType.SMELTING.getInputCache();"),
				"句柄必须在配方版本变化时重新解析，否则会按过期配方表作答");
		assertFalse(source.contains("MekanismRecipeType.SMELTING.getInputCache().containsInput"),
				"查询路径不得回退到每次重新解析句柄");
	}

	@Test
	@DisplayName("标签过滤判定以 Item 为键：结果本就只由 Item 决定")
	void tagFilterCacheKeysByItem() throws Exception {
		String source = read("src/main/java/com/ayoshiko/productivebeesgenesis/mek/ae2/"
				+ "Ae2TagFilterCache.java");
		assertTrue(source.contains("BoundedBooleanMemo<Item> results"),
				"标签判定输入只有 key.getItem()，按完整键记忆纯属浪费命中率与比较成本");
		assertTrue(source.contains("Ae2ItemTagView.candidateOf(item)"),
				"必须复用已取出的 Item，不得再从 key 二次取值");
	}

	@Test
	@DisplayName("保留下限的实时探测按 (key, game tick) 记忆，且提交抽取后同步扣减")
	void reserveProbeIsMemoizedPerGameTick() throws Exception {
		String view = read("src/main/java/com/ayoshiko/productivebeesgenesis/mek/ae2/"
				+ "Ae2NetworkInventoryView.java");
		assertTrue(view.contains("static long reserveProbeAmount("),
				"必须提供按刻记忆化的保留探测入口");
		assertTrue(view.contains("reserveAmounts") && view.contains("reserveCaps"),
				"必须同时记录探测结果与当时使用的上限，才能判断结果是否被截断");
		assertTrue(view.contains("if (cachedCap >= cap || cached < cachedCap)"),
				"只有「上限不小于本次」或「未被截断」时才可复用，否则必须重新探测");
		assertTrue(view.contains("long reserved = cache.reserveAmounts.getLong(key);"),
				"recordExtract 必须扣减保留视图，漏扣会让同刻后续抽取越过保留线");

		String puller = read("src/main/java/com/ayoshiko/productivebeesgenesis/mek/ae2/Ae2InputPuller.java");
		assertTrue(puller.contains("Ae2NetworkInventoryView.reserveProbeAmount(holder, gameTick,"),
				"保留校验必须走记忆化入口；直接 liveExtractableAmount 会在时间加速下每刻重复穿透全部存储元件");
	}

	@Test
	@DisplayName("候选分类结果随条目传递，排序阶段不再重跑分类")
	void classificationIsCarriedByPullEntry() throws Exception {
		String puller = read("src/main/java/com/ayoshiko/productivebeesgenesis/mek/ae2/Ae2InputPuller.java");
		assertTrue(puller.contains("entry.smelting = kind.isSmelting();"),
				"直探路径必须直接沿用 classify 的返回值");
		assertTrue(puller.contains("entry.smelting = index < smeltingSelected;"),
				"扫描路径必须用优先组分界还原分类，而不是再查一次配方缓存");
		assertFalse(puller.contains("entry.smelting = Ae2InputCandidatePolicy.classify("),
				"排序阶段不得为每个条目重跑 classify");
	}

	@Test
	@DisplayName("游标扫描用哈希集合去重，不得退回 out.contains 线性比较")
	void cursorScanDeduplicatesWithHashSet() throws Exception {
		String scan = read("src/main/java/com/ayoshiko/productivebeesgenesis/mek/ae2/Ae2CursorScan.java");
		assertTrue(scan.contains("if (key == null || seen.contains(key)) continue;"),
				"主扫描必须用哈希去重：候选列表可达数千项，线性去重会放大 AEItemKey.equals");
		assertFalse(scan.contains("out.contains(key)"),
				"不得回退到 O(候选数 × 选中上限) 的线性去重");

		String buffers = read("src/main/java/com/ayoshiko/productivebeesgenesis/mek/ae2/Ae2PushBuffers.java");
		assertTrue(buffers.contains("final Set<AEItemKey> scanSeenKeys = new HashSet<>()"),
				"去重集合必须跨 tick 复用，避免每次拉取分配");

		String puller = read("src/main/java/com/ayoshiko/productivebeesgenesis/mek/ae2/Ae2InputPuller.java");
		assertTrue(puller.contains("Set<AEItemKey> seenKeys = buffers.borrowScanSeenKeys();"),
				"拉取必须借用复用集合");
		assertTrue(puller.contains("seenKeys.clear();"),
				"借用后必须与 selectedKeys 一起清空，否则会跨轮次误判已选中");
	}
}
