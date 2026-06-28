# ChangeLog

All notable changes to this project will be documented in this file.

## [v1.0.0](https://github.com/Ayoshiko/productive-bees-genesis/releases/tag/v1.0.0)

### feat
* 万象创世蜜蜂（Myriad Creations Bee）—— 8秒彩虹渐变、随机产出资源蜜脾 ([588e190](https://github.com/Ayoshiko/productive-bees-genesis/commit/588e190))
* Mekanism 风格离心机，覆盖17个工厂等级（Mekanism / ME / EM / EME） ([588e190](https://github.com/Ayoshiko/productive-bees-genesis/commit/588e190))
* 宇宙星空渲染系统，兼容 Iris 光影 ([588e190](https://github.com/Ayoshiko/productive-bees-genesis/commit/588e190))
* 游戏内蜜蜂过滤配置界面（中英双语） ([588e190](https://github.com/Ayoshiko/productive-bees-genesis/commit/588e190))
* MixinConfigPlugin 条件加载 ([588e190](https://github.com/Ayoshiko/productive-bees-genesis/commit/588e190))
* 清理万象创世蜜蜂，优化Mek离心机产物弹出 ([5728165](https://github.com/Ayoshiko/productive-bees-genesis/commit/5728165))
* 添加可配置的蜜蜂获取方式（钓鱼、繁殖、生成） ([d3fc40c](https://github.com/Ayoshiko/productive-bees-genesis/commit/d3fc40c))
* 配方O(1)索引 — CentrifugeRecipeIndex 避免每tick全量遍历 ([5ff2716](https://github.com/Ayoshiko/productive-bees-genesis/commit/5ff2716))
* AE2/管道限流包装器 — RateLimitedItemHandler 防止高频拉取导致tick延迟 ([5ff2716](https://github.com/Ayoshiko/productive-bees-genesis/commit/5ff2716))
* 蜂箱产物路径优化 — BeeHelperMixin 短期缓存+对象池+合并堆叠+可选节流 ([5ff2716](https://github.com/Ayoshiko/productive-bees-genesis/commit/5ff2716))
* hasCentrifugeRecipe输入复用 — AbstractCombEventHandler 避免重复配方查找 ([5ff2716](https://github.com/Ayoshiko/productive-bees-genesis/commit/5ff2716))
* 性能监控命令 /pbg perf — PerfCommand 支持Spark profiler集成 ([5ff2716](https://github.com/Ayoshiko/productive-bees-genesis/commit/5ff2716))
* 性能监控JMX MBean — PerformanceMonitor 暴露tick时间/缓存命中率/能量消耗 ([5ff2716](https://github.com/Ayoshiko/productive-bees-genesis/commit/5ff2716))
* 离心机标志位优化 — 输出槽hasOutputItems/outputSlotsFull避免每次遍历 ([5ff2716](https://github.com/Ayoshiko/productive-bees-genesis/commit/5ff2716))
* 配方缓存优化 — RecipeCacheManager 使用Record CacheKey+LRU淘汰（LinkedHashMap>1024） ([5ff2716](https://github.com/Ayoshiko/productive-bees-genesis/commit/5ff2716))
* 客户端事件处理器模板方法模式 — AbstractClientCombEventHandler基类 ([5ff2716](https://github.com/Ayoshiko/productive-bees-genesis/commit/5ff2716))
* 宇宙渲染队列预分配矩阵 — CosmicRenderQueue复用REUSABLE_OLD_PROJECTION/MODEL_VIEW ([5ff2716](https://github.com/Ayoshiko/productive-bees-genesis/commit/5ff2716))
* 离心机能量计算基于实际能量差 — 摆脱getLastUsage父类语义依赖 ([5ff2716](https://github.com/Ayoshiko/productive-bees-genesis/commit/5ff2716))
* PbRecipeProcessor缓存energyPerTick/operationsPerTick — 避免循环内重复Math.pow ([5ff2716](https://github.com/Ayoshiko/productive-bees-genesis/commit/5ff2716))

### fix
* 移动CentrifugeMixinHelper从mixin到util包 — 避免Mixin框架误加载为Mixin目标 ([eed1574](https://github.com/Ayoshiko/productive-bees-genesis/commit/eed1574))
* 修复checklist验证问题 ([f37a075](https://github.com/Ayoshiko/productive-bees-genesis/commit/f37a075))
* JMX MBean注册异常 — 改用StandardMBean显式绑定接口，实现类改为static内部类 ([5ff2716](https://github.com/Ayoshiko/productive-bees-genesis/commit/5ff2716))
* 流体槽满日志刷屏 — 256倍加速下INFO日志每tick数十条，改为静默丢弃 ([5ff2716](https://github.com/Ayoshiko/productive-bees-genesis/commit/5ff2716))
* 配置文件不正确警告 — 移除dead config旧键残留，删除旧toml文件 ([5ff2716](https://github.com/Ayoshiko/productive-bees-genesis/commit/5ff2716))
* 配方序列化NPE兜底 — 五种PB配方toNetwork的Mixin HEAD拦截+fallback ([5ff2716](https://github.com/Ayoshiko/productive-bees-genesis/commit/5ff2716))
* BeeRecipeReloader未就绪时跳过替换 — 避免toNetwork崩溃 ([5ff2716](https://github.com/Ayoshiko/productive-bees-genesis/commit/5ff2716))
* getBeeIngredient containsKey校验 — 类型不存在时回退到minecraft:bee ([5ff2716](https://github.com/Ayoshiko/productive-bees-genesis/commit/5ff2716))

### refactor
* 综合代码审计 — 合规性、稳定性、性能、代码标准 ([05c9112](https://github.com/Ayoshiko/productive-bees-genesis/commit/05c9112))
* 工厂标志位管理逻辑抽取 — 3个工厂类约90行重复代码提取到MekCentrifugeFactoryHelper ([5ff2716](https://github.com/Ayoshiko/productive-bees-genesis/commit/5ff2716))
* GUI Helper组合模式 — GuiMekCentrifugeFactoryHelper工具类抽取widget创建逻辑 ([5ff2716](https://github.com/Ayoshiko/productive-bees-genesis/commit/5ff2716))
* PbRecipeContext接口扩展 — 6个新方法供工厂版PbRecipeProcessor统一调用 ([5ff2716](https://github.com/Ayoshiko/productive-bees-genesis/commit/5ff2716))

### ui
* FilterListScreen交互改进 — 点击行任意位置勾选，#表头与序号对齐 ([6dc9ced](https://github.com/Ayoshiko/productive-bees-genesis/commit/6dc9ced))
* FilterListScreen间距收紧 — INDEX_COLUMN_WIDTH 28→18, gap 4→2 ([df7f9da](https://github.com/Ayoshiko/productive-bees-genesis/commit/df7f9da))

### docs
* 中英双语README ([360f6ba](https://github.com/Ayoshiko/productive-bees-genesis/commit/360f6ba))
* 同步英文README ([d6ce954](https://github.com/Ayoshiko/productive-bees-genesis/commit/d6ce954))
* 重排README警告和宇宙纹理描述 ([31f578a](https://github.com/Ayoshiko/productive-bees-genesis/commit/31f578a))
