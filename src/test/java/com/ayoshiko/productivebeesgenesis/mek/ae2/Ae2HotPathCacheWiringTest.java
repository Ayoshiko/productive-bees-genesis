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
		// 无组件物品快径与 bee_type 快径都已下沉到预采签名：内层循环只比较数组读到的补丁数，
		// 不再逐对读取组件映射（「每格一种蜜蜂」下 Item 身份剪枝完全失效，这里是主要成本）
		assertTrue(source.contains("lanePatchSize == 0 && keyPatchSize == 0"),
				"普通无组件物品必须绕过完整组件映射比较");
		assertTrue(source.contains("lanePatchSize == 1 && keyPatchSize == 1"),
				"可配置蜜脾必须走 bee_type 单组件快径，避免完整 PatchedDataComponentMap 比较");
		assertTrue(method.contains("entry.matchesComponents(slotIndex, stack, probe,"),
				"容量规划必须通过条目缓存组件匹配结果，并由调用方传入预采签名");
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
		assertTrue(source.contains("boolean resolveMarkedEntries = tagFilterActive && filter != null"),
				"标签过滤激活时必须区分标记与未标记候选");
		assertTrue(source.contains("filter.matchesAnyEntry(entry.key, sortIgnoreNbt)"),
				"标签过滤放行的未标记候选不得被误判为外层标记物品");
	}

	@Test
	@DisplayName("组件快速路径保留异常物品的完整堆叠语义")
	void componentFastPathsRequireCanonicalIdentityComponents() throws Exception {
		String puller = read("src/main/java/com/ayoshiko/productivebeesgenesis/mek/ae2/Ae2InputPuller.java");
		// 组件类型已提到局部变量并进一步下沉到共享签名提取入口（spark：DeferredHolder.get 曾占 1.47%），
		// 但"双方都必须显式携带 bee_type 才能绕过完整组件比较"这一堆叠语义约束不变：
		// 车道侧与条目侧都只在「补丁数 1 + 可配置蜜脾 + 显式 has(bee_type)」时才产出非空签名。
		String snapshot = read("src/main/java/com/ayoshiko/productivebeesgenesis/mek/ae2/"
				+ "Ae2InputLaneSnapshot.java");
		assertTrue(snapshot.contains("stack.has(beeTypeComponent) ? stack.get(beeTypeComponent) : null"),
				"可配置蜜脾只有显式携带 bee_type 时才能生成快径签名，否则必须退回完整组件比较");
		assertTrue(puller.contains("lanePatchSize == 1 && keyPatchSize == 1"
						+ " && laneBeeType != null && keyBeeType != null"),
				"快径要求双方签名都非空，即双方都显式携带 bee_type");
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
	@DisplayName("流体推送单趟扫描 + 三条推送通道，且不设满槽阈值、不取网络令牌")
	void fluidPushClampsThroughTheSinglePassSampleIndex() throws Exception {
		String source = read("src/main/java/com/ayoshiko/productivebeesgenesis/mek/ae2/Ae2FluidPusher.java");
		assertTrue(source.contains("Object2LongOpenHashMap<AEFluidKey> tankTotals = batchBuffer.beginTankSample();"),
				"槽位扫描必须借用批处理缓冲的复用采样表（跨 tick 零分配）");
		assertTrue(source.contains("batchBuffer.commitTankSample();"),
				"采样必须先并入待推送表再判定触发，否则本刻新增量会漏记");
		assertTrue(source.contains("long tankTotal = tankTotals.getLong(fluidKey);"),
				"推送上限必须查采样索引 O(1) 取得");
		assertFalse(source.contains("currentTankAmount(host, tankSnapshot, fluidKey, tankCount)"),
				"不得回退到「每个 key 重扫全部槽位」的 O(流体键数 × 槽数) 路径");

		// 三条通道：新增流体 / 该流体槽位已满 / 成熟窗口到期
		assertTrue(source.contains(
				"if (!batchBuffer.needsPush(tankTotals, tankCapacities) && !batchBuffer.isRipe()) return;"),
				"触发条件必须包含「槽位已满即推」：满槽时液面无法再上升，只比液面会退化成 10 刻一次");
		assertTrue(source.contains("Object2LongOpenHashMap<AEFluidKey> tankCapacities = batchBuffer.tankSampleCapacities();"),
				"满槽判定必须用采样期统计的该流体槽位容量之和");
		assertTrue(source.contains("if (firstPushThisTick) batchBuffer.tick();"),
				"成熟窗口必须每个真实游戏刻只推进一次（加速子 tick 在入口合并）");
		assertFalse(source.contains("saturationThreshold"),
				"不得恢复「满槽阈值」延迟推送：槽满才推会让流体在本地罐滞留");

		// 网络级令牌在昂贵网络下每刻只放行一个宿主，会让多机同网的流体推送被整轮跳过
		assertFalse(source.contains("tryAcquireNetworkWork"),
				"流体路径不得取网络级工作令牌（git 基线语义），节流交给配额/退避/成本预算");
	}

	@Test
	@DisplayName("推送尝试必须回写槽内余量，否则同一批被拒流体会每刻重推")
	void fluidPushRecordsRemainingAfterEachAttempt() throws Exception {
		String source = read("src/main/java/com/ayoshiko/productivebeesgenesis/mek/ae2/Ae2FluidPusher.java");
		assertTrue(source.contains("batchBuffer.recordAttempt(fluidKey, tankTotal);"),
				"被拒绝/退避跳过的流体必须登记槽内余量");
		assertTrue(source.contains("batchBuffer.recordAttempt(fluidKey, Math.max(0L, tankTotal - shrunk));"),
				"部分成功后必须登记剩余量，只有新增产出才触发下一轮直推");
		assertTrue(source.contains("batchBuffer.recordAttempt(fluidKey, 0L);"),
				"槽已空时必须清零余量，下一次产出才会被判定为新增");

		String buffer = read("src/main/java/com/ayoshiko/productivebeesgenesis/mek/ae2/Ae2PendingBatchBuffer.java");
		assertTrue(buffer.contains(
				"boolean needsPush(Object2LongMap<AEFluidKey> sample, Object2LongMap<AEFluidKey> capacities)"),
				"推送判定必须同时考虑「新增流体」与「该流体槽位已满」");
		assertTrue(buffer.contains("public static final int RIPE_TICKS = 10;"),
				"成熟窗口取参考实现 useless PendingAEBatch 的 10 刻");
	}

	@Test
	@DisplayName("直推流体与物品路径同构：按 key 退避 + 每真实刻配额")
	void directGeneratedFluidSharesBackoffAndPerTickQuota() throws Exception {
		String source = read("src/main/java/com/ayoshiko/productivebeesgenesis/mek/ae2/Ae2FluidPusher.java")
				.replaceAll("\\s+", " ");
		assertTrue(source.contains("if (keyBackoff.shouldSkip(key, nanoNow)) return 0L;"),
				"被拒的流体键必须在退避窗口内跳过直推（避免每刻重复发起注定失败的全量网络遍历）");
		assertTrue(source.contains(
				"!pushState.tryAcquireGeneratedFluidInsert(gameTick, MAX_DIRECT_FLUID_INSERTS_PER_TICK)"),
				"加速子 tick 必须受每真实游戏刻配额约束");
		assertTrue(source.contains("keyBackoff.recordSuccess(key);"),
				"真实插入成功必须清除该 key 的退避");
		assertTrue(source.contains("keyBackoff.recordFailure(key, System.nanoTime());"),
				"零接收或抛异常必须记入该 key 的退避");
		assertFalse(source.contains("pushState.getFluidBackoff().recordFailure("),
				"直推不得写整机级 fluidBackoff：单种流体被拒不该连带压制同机其它流体");

		String state = read("src/main/java/com/ayoshiko/productivebeesgenesis/mek/ae2/Ae2PushStateHolder.java");
		assertTrue(state.contains("public boolean tryAcquireGeneratedFluidInsert(long gameTick, int maxInserts)"),
				"配额必须由 per-tile 状态持有者提供（与 tryAcquireGeneratedItemPush 对称）");
		assertTrue(state.contains("generatedFluidInsertGameTick = Long.MIN_VALUE;"),
				"reset() 必须重置流体直推配额，避免方块重建后沿用旧刻计数");
	}

	@Test
	@DisplayName("上报量已覆盖上限时跳过实时探测，不得回退到每键全网络遍历")
	void liveProbeIsSkippedWhenTheReportedStockAlreadyCoversTheCap() throws Exception {
		String view = read("src/main/java/com/ayoshiko/productivebeesgenesis/mek/ae2/"
				+ "Ae2NetworkInventoryView.java");
		// 保留校验探针：上报量 ≥ cap 时结论恒为 cap，探测只是白花一次 NetworkStorage.extract
		assertTrue(view.contains(
				"if (cachedInventory != null && Math.max(0L, cachedInventory.get(key)) >= cap) return cap;"),
				"保留下限探针必须在报量足够时短路");
		// 可见量探针：min(max(上报, 实时), cap) 在上报 ≥ cap 时同样是 cap
		assertTrue(view.contains("if (reported >= cap) return cap;"),
				"可见量探针必须在上报量覆盖上限时短路");
		// 短路必须发生在探针之前，否则等于没省
		int fastPath = view.indexOf("if (reported >= cap) return cap;");
		int probeCall = view.indexOf("simulated = liveExtractableAmount(network, key, Long.MAX_VALUE, source);");
		assertTrue(fastPath > 0 && probeCall > fastPath,
				"上报量短路必须位于 liveExtractableAmount 之前");

		String puller = read("src/main/java/com/ayoshiko/productivebeesgenesis/mek/ae2/Ae2InputPuller.java");
		assertTrue(puller.contains("Ae2NetworkInventoryView.reserveProbeAmount(holder, gameTick,\n"
				+ "\t\t\t\t\t\tcachedInventory, meStorage, key, queryCap, actionSource)"),
				"拉取路径必须把 AE2 库存快照传给探针，否则报量短路永远不生效");
		assertTrue(puller.contains("entry.reserveFloor, availableStacks, meStorage,"),
				"批处理必须携带同一份库存快照，同刻不得重新获取");
	}

	@Test
	@DisplayName("外部存储物品快照用定长数组，禁止复用 KeyCounter 累积历史键")
	void externalStorageSnapshotAvoidsReusedKeyCounter() throws Exception {
		String source = read("src/main/java/com/ayoshiko/productivebeesgenesis/mek/ae2/"
				+ "CentrifugeExternalAeStorage.java");
		// AE2 的 KeyCounter.clear() 只清内层 VariantCounter，外层子映射会永久保留归零条目，
		// 复用会让 clear/add/iterator 成本随历史物品种类单调增长
		assertFalse(source.contains("itemSnapshot.clear()"),
				"不得复用 KeyCounter 做快照（clear 不清外层子映射）");
		assertFalse(source.contains("out.addAll(shared.itemSnapshot)"),
				"不得整表复制快照，应逐条写入调用方计数器");
		assertTrue(source.contains("private Object[] itemSnapshotKeys = new Object[0];"),
				"快照必须是定长数组，长度由输出槽数封顶");
		assertTrue(source.contains("out.add((AEItemKey) shared.itemSnapshotKeys[i], "
				+ "shared.itemSnapshotAmounts[i]);"),
				"必须逐条写入（KeyCounter.add 为累加语义，多槽同物品自动合并）");
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
