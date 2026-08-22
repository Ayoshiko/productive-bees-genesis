# Debug Session: energy-input-regression
- **Status**: [COMPLETE]
- **Issue**: v1.0.2 后离心机能量升级卸载会增加容量，直连输入被限制为单种蜜脾且每槽 64，并需依据既有 Spark 样本检查其他性能与功能回退。
- **Debug Server**: 未建立新会话；仅分析用户提供的既有 Spark / Observable 采样
- **Log File**: 由 Spark profile `VYfcyF5bKF`、`vtoa38qwC1`、`pGePcbWlRy`、`SKUqpPmTJg`、`9XCUXaYub2`、`Q0YqLdXXzx` 与 Observable `luy4m` 提供

## Reproduction Steps
1. 在离心机工厂装满能量升级后逐个卸载，观察最大电量反而增加并持续累积。
2. 使用相邻机器或管道直连向离心机输入多种蜜脾，观察每次只接纳一种且每槽停在 64。
3. 对照 AE2 主动拉取路径，确认其能将同种蜜脾填充到高倍率槽位上限。
4. 分析用户提供的两份 Spark profile，定位本模组高频调用和回归窗口。

## Hypotheses & Verification
| ID | Hypothesis | Likelihood | Effort | Evidence |
|----|------------|------------|--------|----------|
| A | 动态容量按 ENERGY 调整后的实际能耗计算且只增不减，卸载后反向扩容 | Confirmed | Low | `ensureCapacity` 使用当前 `energyPerTick`，旧实现无缩容回调 |
| B | 32 个能量升级对应的容量公式缺少有限上限 | Confirmed | Low | 旧实现可直接把 `maxEnergy` 提升到任意 `required`，无基础容量倍数上限 |
| C | 外部存储防垄断策略错误覆盖普通直连的 EXTERNAL 插入 | Confirmed | Low | 直连目标插入使用 `EXTERNAL`，触发 `MIN_WORKING_SET=64` 和每 tick 单指纹槽准入 |
| D | 1.0.2 高频 AE2 路径对昂贵外部存储执行重复网络遍历 | Confirmed | Medium | Spark 显示 `pullInputs` 4.47%/6.09%，`ExternalStorageFacade.extract` 10.04%，EnderDrives extract 15.16% |
| E | 1.0.2 同步/缓存改动造成额外功能回退 | Confirmed | Medium | 能量 tracker 可能误替换工厂 `lastUsage`；PB 客户端和自动安装路径未失效倍率缓存；输出缓冲部分接纳未标脏 |

## Log Evidence
- Profile `VYfcyF5bKF` (`72308c2dc66f`): TPS 8.58，MSPT median 68.63ms，max 12572.48ms。
- 该样本中 `Ae2InputPuller.pullInputs` 1340ms/4.47%，`pullBatchForType` 1292ms/4.31%，`Ae2LeftoverReturner.returnLeftoverToMe` 1276ms/4.25%，`Ae2OutputPusher.pushOutputs` 944ms/3.15%。
- 同一样本 `ExternalStorageFacade.extract` 3012ms/10.04%，证明外部存储门面遍历是主要放大器，而本模组 self-time 并非主要负载。
- Profile `vtoa38qwC1` (`69aaa122fbec`): TPS 20，MSPT median 36.2ms，max 324.9ms。
- 该样本中 `Ae2InputPuller.pullInputs` 1808ms/6.09%，`pullBatchForType` 1768ms/5.95%；`EnderDiskInventory.extract` 4500ms/15.16%，其 WAL/fsync 路径会把一次无效拉取/回送放大为主线程 I/O。
- Profile `pGePcbWlRy` (`ed16abcfa09f`): TPS 5.63，MSPT median 76.56ms，p95 155.1ms，max 8886.36ms；物理内存占用 88.8%，交换文件已用约 35GB。
- 第三份样本与前两份不同：本模组工厂总路径仅 590ms/0.27%，`Ae2InputPuller` 350ms/0.16%，`Ae2CursorScan.collectMapped` 130ms/0.06%，不是主要瓶颈。
- 主瓶颈是 AE2 大量区块首次加载/网络重建：`TickHandler.onServerLevelTickEnd` 54480ms/24.49%（self 19.80%），`readyBlockEntities` 7560ms/3.40%，AE2LT `PackagedPatternProviderLogic.updatePatterns` 6270ms/2.82%，`Grid.onServerEndTick` 14200ms/6.38%；另见 NeoEcoAE 接口映射与 OmniCells 存量扫描。
- 因第三份热点归属 AE2/AE2LT/NeoEcoAE/OmniCells 和区块加载，未据此向本模组加入侵入性 workaround；现有 AE2 操作聚合、预算和退避已足以限制本模组的额外放大效应。
- Profile `SKUqpPmTJg` (`72fd51446d54`): TPS 3.05，MSPT median 182.56ms，p95 357.04ms；`ExternalStorageFacade.extract` 15956ms/53.19%，`ItemStack.<init>` self 26.79%，`MutableBigInteger.clear` self 10.71%。调用树明确落到 ProjectExpansion 转化接口；本模组 `pushOutputs` 912ms/3.04%，是昂贵下游被重复触发的次级放大器。
- 同一玩家拆除 ProjectExpansion 转化接口后的对照 Profile `9XCUXaYub2` (`b58906f9021b`): TPS 9.34，MSPT median 50.22ms；`ExternalStorageFacade.extract` 降到 696ms/2.32%，`MutableBigInteger` 热点完全消失，本模组 `pushOutputs` 降到 64ms/0.21%，`pushItemStack` 降到 16ms/0.05%。该 A/B 对照确认转化接口是第四份报告的主要根因。
- 对照 Observable `luy4m` (`436f9c3be05b`): 112 ticks / 44.67ms MSPT；本模组 9 个方块实体合计 995.6us/tick，最重的无限离心工厂 684.329us/tick，其余机器无异常本体耗时。当前首要方块实体热点是 4 个 Torcherino，共 3186.7us/tick。
- 新增 Profile `Q0YqLdXXzx` (`9ef6e49a81c8`): TPS 10.14，MSPT median 41.33ms，p95 83.2ms，max 921.01ms；该报告运行的 `productivebeesgenesis` 版本为 `2.0.9-hotfix`，与本调试目标 `1.0.2` 不同，不能作为 1.0.2 回归的直接复现证据。
- `Q0YqLdXXzx` 中本模组 self-time 约 60ms/30000ms（0.20%），完整工厂调用链最高约 380ms（1.27%），`Ae2OutputPusher.pushOutputs` 约 112ms（0.37%）；未发现新的本模组机器本体热点。
- 该样本的主要热点是 AE2 `StorageService.updateCachedStacks` 5280ms/17.60%，其中 OmniCells `AEUniversalCellInventory.getAvailableStacks` 3848ms/12.83%，以及 `KeyCounter`/`AEItemKey.equals` 的全量库存重建；这属于 AE2/OmniCells 网络级缓存更新，不是本模组单次推送逻辑。
- 同样本还包含 Just Dire Things Clicker 约 1.51%、Industrial Foregoing Dissolution Chamber 配方匹配约 2.92%，并有 3806 个区块、物理内存 83.2%、交换文件约 31GB；这些外部负载会放大整体 TPS 波动。当前不对本模组加入侵入式规避逻辑，建议用实际 `1.0.2` 修复版重新采样并单独对照 AE2/OmniCells 存储网络。

## Applied Fixes
- 直连蜂箱到离心机的目标槽改用 `AutomationType.INTERNAL`；源槽提取与回滚继续使用 `EXTERNAL`。
- 容量改为只由注册基础容量与当前 ENERGY 升级确定，每次加载、升级重算和服务端能量注入前均可向上或向下归一化；旧存档历史峰值不会继续累积。
- 运行时 STACK 数量与当前平衡档案的安装上限一致；旧存档中的超额 STACK 只影响计算，不会继续放大并行和能耗。
- AE2 能量需求按当前输入槽可执行并行数计算，且在低于一批需求前不重复发起网络提取，减少少量蜜脾场景的能量条大幅摆动。
- AE2 注入采用两批低水位、四批高水位，不再尝试填满历史遗留的异常容量，也不再使用 64M FE/t 固定请求预算。
- 批量配方能耗乘法改为饱和运算，防止 long 溢出后少扣电或预算失真。
- 删除按值匹配并重排 Mekanism tracker 的 `EnergySyncThrottler`，恢复原版属性索引与能量同步。
- AE2 拉取增加目标槽 validator 预检、慢 extract 全服预算和退避；输出侧两个及以上槽位按 key 聚合。
- 成功但超过 500us 的 AE2 insert 仍完成物品转移，但对应 key 进入独立指数退避；避免新产物写槽重置整机退避后持续重击昂贵外部存储。
- 修复输出缓冲“部分合并后仍溢出”未递增版本/未标脏，以及 PB 升级客户端同步和自动安装未失效倍率缓存。

## Verification Conclusion
- `./gradlew test --no-daemon`: 完整测试通过；46 个测试套件共 211 项测试，0 失败、0 错误、0 跳过。
- 静态回归覆盖：直连目标 automation type、ENERGY 升级容量归一化、能量批量乘法溢出、STACK 8/16、16 SPEED + 16 ENERGY、AE2 输出聚合边界。
- 未保留运行时插桩或调试会话；实际整合包中的长期运行表现继续由正式版反馈验证。

## Local runClient follow-up (ntJirMkt7O / LshBd)
- Spark `ntJirMkt7O` (`9f22bba6c297`): TPS 19.9, MSPT median 9.32ms, p95 14.54ms; this is a healthy average runtime with occasional spikes, not a general machine-tick collapse.
- The profile reports Productive Bees Genesis self-time 4.66%; the newly input-aware energy calculation is visible (`activeOperations` / `requiredEnergyPerTick`) but remains below 1% self-time combined.
- Observable `LshBd` (`81c5507db5cb`): 266 Productive Bees Genesis block entities total 2549.3us/tick; top tested centrifuge is 114.22us/tick and top tested apiary is 37.108us/tick. The reported problem is energy delivery/synchronization, not an individual 5ms block entity.
- Confirmed remaining regression: AE energy input still used a `Long.MAX_VALUE / 4` network probe and a fixed 5% reserve cap, and energy was only injected before processing. High-demand machines could therefore expose a post-consumption low-energy snapshot and enter the next tick short of a complete batch.
- Applied follow-up: bounded SIMULATE uses the actual requested FE amount; fixed 5% truncation removed; two-batch low-water/four-batch high-water hysteresis; a post-consumption refill for apiaries, basic centrifuges, all factory implementations and coalesced flush; 750ms client-only energy-usage display debounce.
- Server-side accounting and extraction are not visually averaged: only the Mekanism tooltip number is debounced. Local energy is still charged at the exact calculated amount.
## Full-load / 256x acceleration follow-up (2a1GqJcgSW / XWAR8 / xQRksxaGGa / KewGQ)
- Spark `2a1GqJcgSW` (`dd2a7fcfa706`): TPS 20, MSPT median 9.39ms, p95 12.8ms; Productive Bees Genesis self-time 4.74%.
- Observable `XWAR8` (`b69832d2f80f`): 266 Productive Bees Genesis block entities total 3814.7us/tick; the heaviest factory is 389.431us/tick. The server is not showing a single-machine 5ms+ hotspot in this sample.
- Spark `xQRksxaGGa` (`4d3554613cb9`) after 256x acceleration: TPS 20, MSPT median 9.41ms, Productive Bees Genesis self-time 4.80%.
- Observable `KewGQ` (`ce375a323f92`): Productive Bees Genesis total 2696.6us/tick; the heaviest factory is 233.859us/tick. The 256x path remains bounded at the block-entity level; Just Dire Things time-wand entities are a larger entity-side cost.
- The temporary `64,000,000 FE/t` request budget is removed. AE2 requests the complete demand-scaled refill amount up to the deterministic local capacity.
- High parallelism is linear through 16 operations per lane; above that, each doubling adds one billable operation. The same curve now applies to PB recipes, Myriad Creations and the first/full tick of smelting-compatible `CachedRecipe` processing.
- Built-in balance changes configured `50 FE/t` to `10 FE/t` and halves registered base capacity. With 32 SPEED + 32 ENERGY, the tested operation price is `100,000 FE/op`.
- Exact 19-lane, full-productivity (`480x`) peaks are: no STACK `39,900 / 399,000 / 39,900,000 FE/t`, STACK 8 `55,100 / 551,000 / 55,100,000 FE/t`, and STACK 16 `70,300 / 703,000 / 70,300,000 FE/t` for `8+8 / 16+16 / 32+32` SPEED+ENERGY respectively.
- The highest smelting-compatible STACK 16 path is `53,200,000 FE/t` across 19 lanes because normal smelting does not receive the PB productivity multiplier.

## Full-capacity AE2 charging follow-up
- Root cause: the 1.0.3 two-batch/four-batch watermarks intentionally stopped charging above four batches, and `requiredThisTick == 0` prevented idle machines from charging at all. Creative Energy Cells therefore could not make the GUI buffer reach full.
- The fill target is now the complete remaining difference of the already-normalized Mekanism capacity. Historical oversized buffers remain bounded because normalization still runs before every AE2 injection entry.
- Applied Flux and native AE extraction now use one bounded MODULATE call instead of SIMULATE followed by MODULATE, halving provider/storage traversals on successful charging.
- Charging no longer scans recipe inputs to calculate batch demand; the pre-processing call exits immediately when full and the post-processing call replaces the exact consumed difference.

## MCP Spark / Observable follow-up (2026-08-22)
- MCP loaded the four user-provided reports: accelerated Spark `2fc97ea54d3b` (`3s2RUzNEWj`), non-accelerated Spark `c38791d57399` (`CR3Kejwwix`), accelerated Observable `2f2e918e25fc` (`4Cq8p`), and non-accelerated Observable `b6d2bafcff85` (`Nhv70`). All reports are NeoForge `21.1.214` / Minecraft `1.21.1`; the Spark samples report `productivebeesgenesis` `1.0.3` and AE2 `19.2.17`.
- Accelerated Spark: TPS 20, MSPT median 9.53 ms, p95 12.74 ms, max 130.3 ms; `productivebeesgenesis` self-time 4.98%. Non-accelerated Spark: TPS 18.9, MSPT median 9.47 ms, p95 16.02 ms, max 97.31 ms; `productivebeesgenesis` self-time 4.80%, AE2 self-time 2.03%.
- MCP call-tree/source searches place `Ae2InputPuller.pullInputs` at roughly 0.01% self-time in the accelerated sample and 0.67% in the non-accelerated sample; `getSlotRemainingCapacity` is roughly 0.04% / 0.31%. The non-accelerated AE2-side frames are `AEItemKey.matches`, `ItemStack.isSameItemSameComponents`, `Ae2InputFilterQuerySupport.pullLimitIfAllowed`, and `Ae2FilterEntrySupport.matchesDirectEntry`, which are bounded by the new candidate, inventory, and per-tick caches.
- Accelerated Observable: 565 block entities cost 3223.5 us/tick; `productivebeesgenesis` contributes 2630 us/tick across 268 blocks (81.6%), while AE2LT contributes 172.6 us/tick. Non-accelerated Observable: 565 block entities cost 5251.8 us/tick; `productivebeesgenesis` contributes 4617.6 us/tick (87.9%), while AE2LT contributes 124.3 us/tick. The heaviest entries are high-tier centrifuge factories, not the AE2 inventory probe itself; the samples also differ in duration and acceleration state, so totals are not a direct speedup ratio.
- Accelerated Observable additionally shows `justdirethings:time_wand_entity` as the dominant entity-side cost. MCP diagnose reports host physical memory near 93% with about 29 GB swap in use and intermittent tick spikes; this is test-host pressure and must not be attributed to the AE2 pull implementation. The clean-network comparison therefore supports keeping AE2 storage access aggregated and cached rather than adding cross-machine shared reservation state.
- Applied performance direction: reuse the holder-level grid/storage/cache, reuse one `AEItemKey.toStack(1)` probe per item type across all input slots, perform one `MEStorage.extract` per type and distribute locally, refresh candidate keys at a bounded interval, and retain slow-operation budgets/backoff. These choices follow AE2LT's cached-inventory and per-tick simulation pattern while avoiding a network-wide per-machine polling loop.

## MCP Spark / Observable follow-up (2026-08-22, QgFuv / Zi1UM8tszQ / HpFapq178W / IDOnT)
- MCP reloaded and compared all four new reports (`4d8a593f0202`, `98cfa6197a29`, `cee7d30f0c36`, `d3b741789d47`). AE2 input remains below 1% in the new samples; the dominant mod-side cost is high-tier factory/PB processing, while AE2 grid work and AE2LT stay secondary.
- Accelerated Spark `Zi1UM8tszQ`: TPS 20, median MSPT 9.75 ms, p95 14.69 ms, max 330.88 ms; Productive Bees Genesis self-time 5.20%, AE2 1.41%. Non-accelerated `HpFapq178W`: median 12.25 ms, p95 16.44 ms, Productive Bees Genesis 7.98%, AE2 1.72%.
- `IAe2OutputHostBase.getAe2StateHolder` reached 0.78% / 1.09% self-time in the two Spark samples. All current AE2 hosts now return the stable state holder directly, bypassing the lifecycle-handler interface chain on hot paths; lifecycle behavior is unchanged.
- `MekCentrifugeEnergyScaling.normalizeCapacity` was about 0.21% self-time in the accelerated sample. A holder-local capacity cache now skips repeated Mekanism upgrade-capacity math when base/current capacity still match, invalidating on tile clear and creative-mode transitions. The cache is guarded by AE2 runtime detection to preserve no-AE2 class-loading safety.
- Observable `QgFuv` / `IDOnT` show Productive Bees Genesis at 85.6% / 89.8% of block-entity cost, with AE2LT at 3.2% / 2.2%. The most expensive entries are high-tier centrifuge factories. `IDOnT` also contains a JDTE time-wand entity at roughly 80% of entity cost; this is external to AE2 pull and is not handled with an invasive workaround.
- Both Observable samples report severe host memory pressure (about 91-93% physical memory and roughly 30 GB swap) and intermittent tick spikes. These conditions explain outliers and should be controlled in future clean-network A/B captures before attributing them to AE2 interaction.
- `OutputSlotFlagManager` now counts synchronous listener events against explicit `updateSlotOnly` snapshots. Normal PB flushes can aggregate one process without a factory-wide scan; reusable Myriad plans that do not expose slot indices still aggregate their affected process, while mismatched or externally-mutated batches conservatively fall back to a full refresh.
