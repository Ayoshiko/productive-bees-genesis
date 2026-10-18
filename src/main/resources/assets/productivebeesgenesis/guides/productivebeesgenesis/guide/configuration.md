---
navigation:
  parent: reference-index.md
  title: 配置
  icon: "minecraft:comparator"
  position: 1
---
# 配置

<ItemImage id="minecraft:comparator" scale="2" />

**第一次游玩请保持默认值。** 默认配置已经能完整体验蜜蜂、蜂箱、离心机和 AE2 联动。只有整合包平衡、服务器性能或个人视觉偏好确实需要时再改。

## 从哪里打开

在模组列表中选择“资源蜜蜂：创世”，点击**配置**。本模组已经把设置分成中文分类，日常使用不需要手动编辑 TOML，也不需要背英文配置键。

多人游戏中，产量、能耗、机器容量和蜜蜂规则由服务器决定；客户端只能修改自己看到的彩虹、粒子、光晕、端口提示和窗口位置。

## 四个最常用的页面

### 玩法设置

这里控制万象创世蜜蜂、平衡模式、获得方式、过滤名单、属性和高倍加速预算。

- **万象创世总开关**：只关闭这只蜜蜂；机械蜂箱、离心机和升级仍保留。
- **平衡模式**：普通整合包选“基础”；需要自由堆叠的高倍率包再选“悖论无限”；“自定义”留给明确知道各项影响的作者。
- **蜂箱同时生产全部花源**：木厂、采石和染料蜜蜂每轮生产喂食槽内所有不同有效花源。基础默认关闭、悖论无限开启；先切换并保存为“自定义”才能独立修改，切换时继承原预设的值。重复槽位与堆叠数量不增加次数。
- **万象创世过滤**：黑名单排除不想要的资源，白名单只保留指定资源。
- **蜜蜂获得方式**：分别控制物品转化、繁殖、钓鱼和蜂巢自然生成。
- **蜜蜂属性与产出**：改变工作条件和产量，服务器修改后所有玩家都会受影响。
- **高倍加速**：每真实刻默认兑现 2048 个虚拟 tick，可调至 4096；银行默认暂存 8192，可调至 32768。银行只缓冲积压，高 MSPT 时仍自动降低兑现预算。已有配置保留原值。

### 机器设置

这里控制蜂箱、离心机、AE2 联动、升级上限和外部物流。

- **每轮耗电和时间**：机器设置 → 蜂箱或离心机 → 基础参数，建议先保持默认。
- **流体分槽**：离心机 → 基础参数，保持开启。
- **AE2 输出与 Applied Flux 供电**：对应机器 → AE2 集成。
- **PB 升级数量**：对应机器 → PB 升级上限，不要一次拉满。
- **相邻容器直出**：外部物流，普通管道线可开启。

离心机的 AE2 主动取料有服务器总开关，但每台机器默认仍需要玩家在 AE2 输入窗口单独开启，避免刚接上线就大量抽空网络。

蜂箱的**基因小食补货**在每台机器的小食槽下方单独开启，默认关闭。先放入模板小食，它只从本机连接的在线 ME 网络提取物品及全部组件完全相同的小食；与离心机取料和蜂箱产物输出开关独立。详见[机械蜂箱](machines/apiary.md)。

### 容量矩阵

这里按工厂等级设置输入、输出和流体罐倍率。数组顺序已经在配置界面提示中写明。除非你在制作高倍率整合包，否则不要手动改；容量太小会堵，设得极端大也会增加存档和界面同步压力。

### 客户端设置

这里只改变你的画面：彩虹变化、粒子数量、光晕、配置器端口提示范围，以及几个可拖动窗口的位置。关闭这些效果不会降低服务器产量。

## 改配置的安全步骤

1. 退出当前世界，服务器则先正常停服。
2. 备份世界和配置。
3. 一次只改一组相关设置，并记下旧值。
4. 重进世界，用一台机器验证能耗、速度和产物。
5. 出现异常就恢复刚才那一组，不要同时乱改十几项。

本模组的世界级服务器配置会保存在存档的 `serverconfig` 中，并拆分为玩法、机器和容量三个文件；旧版单文件会由迁移逻辑处理。手动维护整合包时，应以当前配置界面和生成的新文件为准，不要继续复制旧单文件覆盖新设置。

## 什么时候才需要看原始键名

只有整合包作者做批量分发、脚本替换或版本迁移时才需要。普通玩家直接使用中文配置界面即可。若你必须手改文件，请同时参考文件中的中文注释，并避免在服务器运行时保存不完整内容。

## 整合包作者：做一只扩展资源蜜蜂

先用氢气蜜蜂跑通“蜂箱产蜜脾 → 扩展资源离心 → 输出氢气”，再修改资源、颜色和花源。使用 JSON 或 KubeJS 即可，不需要写 Java。

**扩展资源不局限于本页列出的种类。** 氢气、FE、魔源、灵魂、压缩空气、EMC、经验和闪电是内置适配示例。其他模组的自定义资源，只要在 AE2 注册了可完整编码、解码的 AEKey，也可通过通用 ae_key 产物注册，沿用同一套蜂箱和离心配方。

通用键还需要安装提供该键的模组，并准备能接收它的 ME 存储。没有 AEKey 的新资源需要补充资源类型与传输适配；仅填写一个名字不会自动建立适配。

Genesis 机械蜂箱、机械离心机及各级工厂保存待输出资源，可使用机器原生能力或本机 ME 连接输出。图标仅作显示，不能拖进背包；不需要额外资源接口方块。

### 1. 从仓库获取示例

打开 [GitHub 示例目录](https://github.com/Ayoshiko/productive-bees-genesis/tree/main-neo/1.21.1/examples/resource-bees)。示例没有打进正式模组 JAR；请下载仓库中与模组版本相符的文件。

目录按用途分开，与 PB 模板的 data 和 assets 分工一致：

- **datapack**：蜂种、配方和花源标签。把此文件夹放入存档的 datapacks 目录，根部应直接看到 pack.mcmeta 和 data。
- **resourcepack**：蜜蜂名称的中英文翻译。放入游戏的 resourcepacks 目录，再在“选项 → 资源包”启用。根部应直接看到 pack.mcmeta 和 assets。
- **kubejs/server_scripts**：配方脚本，可替代对应 JSON 配方。
- **api**：Java 产物注册与其他机器接入示例。

数据包格式为 48，资源包格式为 34，均对应 Minecraft 1.21.1。服务器装数据包；每位客户端都要启用语言资源包。只在服务器放 assets 不会给玩家加载翻译。

开发用 runClient 自动加载示例数据和语言。仓库的 packageResourceBeeExamples 构建任务可以生成两个可直接安装的 ZIP，具体命令见 [README 中文教程](https://github.com/Ayoshiko/productive-bees-genesis/blob/main-neo/1.21.1/README_zh.md#扩展资源蜜蜂)。

### 2. 氢气蜜蜂需要哪些文件

**蜂种定义：** [hydrogen.json](https://github.com/Ayoshiko/productive-bees-genesis/blob/main-neo/1.21.1/examples/resource-bees/datapack/data/example/productivebees/hydrogen.json)。

位置依次为 data → example → productivebees。example 是命名空间，文件名决定蜂种 ID：example:hydrogen。颜色由 primaryColor 和 secondaryColor 设置，flowerBlock 使用 minecraft:iron_block。

**蜂箱配方：** [hydrogen_honeycomb.json](https://github.com/Ayoshiko/productive-bees-genesis/blob/main-neo/1.21.1/examples/resource-bees/datapack/data/example/recipe/hydrogen_honeycomb.json)。

位置依次为 data → example → recipe。类型为 productivebees:advanced_beehive。ingredient 与产物组件 productivebees:bee_type 都填 example:hydrogen，产出 PB 可配置蜜脾。

**扩展资源离心配方：** [hydrogen_centrifuging.json](https://github.com/Ayoshiko/productive-bees-genesis/blob/main-neo/1.21.1/examples/resource-bees/datapack/data/example/recipe/hydrogen_centrifuging.json)。

类型为 productivebeesgenesis:resource_centrifuging，bee 填 example:hydrogen。outputs 中 kind 为 chemical，id 为 mekanism:hydrogen，amount 为字符串 "100"，chance 为数字 1.0，processing_time 为 20。

示例中的 _comment 是合法 JSON 说明字段，可以保留；JSON 不接受 // 注释。recipe 目录用单数。新增蜂种后重新进入世界；修改已有配方后可执行 /reload。

### 3. 名称翻译必须一起添加

打开 [中文名称](https://github.com/Ayoshiko/productive-bees-genesis/blob/main-neo/1.21.1/examples/resource-bees/resourcepack/assets/productivebees/lang/zh_cn.json) 和 [英文名称](https://github.com/Ayoshiko/productive-bees-genesis/blob/main-neo/1.21.1/examples/resource-bees/resourcepack/assets/productivebees/lang/en_us.json)。

语言文件位于 assets → productivebees → lang。氢气蜂使用键 **entity.productivebees.hydrogen_bee**，中文值为“氢气蜜蜂”。

PB 的可配置蜂名称键固定使用 productivebees，加蜂种 ID 的路径和 _bee；不使用蜂种命名空间 example。改为 mypack:my_hydrogen 时，名称键应改为 entity.productivebees.my_hydrogen_bee。

新建蜜蜂时一并更新：蜂种文件名、所有配方的蜂种 ID、中英文名称键。不同命名空间也应选择不同的蜂种路径，避免 PB 名称键冲突。刷怪蛋、蜜脾、JEI 和蜂箱预览会复用蜂种名称。

看到 entity.productivebees 开头的文字时，先核对语言包是否启用、键名是否正确，再重新加载资源。改语言文件用 F3+T；/reload 只重载数据，不能代替客户端资源重载。

### 4. 让氢气产线工作

示例不添加自然生成。创造测试可取对应刷怪蛋；生存获得方式由作者另配 PB 繁殖或物品转化配方。

1. 在 Genesis 机械蜂箱放入氢气蜜蜂，采蜜槽放铁块，并供电。
2. 把产出的氢气蜜脾放入 Genesis 机械离心机并供电。
3. 单个蜜脾基础产出 100 mB 氢气；蜜脾块为四份，即 400 mB。概率配方的四份分别抽样。
4. 打开机器的 Mekanism 原生侧面配置窗口，在与物品、流体、ENERGY 并列的化学品页选择输出面。开启自动弹出送入相邻储罐；也可关闭自动弹出，由化学品管道提取，或安装 AE2 与 Applied Mekanistics 直连离心机。
5. ME 网络需要供电、频道和可接收化学品的存储，机器 AE 输出开关也要开启。

侧面“扩展资源”窗口默认显示生产监控，可切换待输出库存。产率为近 5 秒实际成功结算量，含升级与加速；空库存或蜜脾返网后仍可看已观察资源和等待状态，停产后归零。只在打开本窗口时同步变化，关闭后停止；Jade 不同步产率。化学品罐显示待输出库存，“弹空”尝试向 ME 或相邻容器输出，拒收余量保留。已入网库存去 ME 终端查看。

不走 ME 时，打开机器的 Mekanism 原生侧面配置窗口，资源页与物品、流体和 ENERGY 并列；每个资源页只在对应模组加载时出现。化学品页使用 MEK 原生化学品侧面图标，其他资源页使用对应模组的代表性物品图标并居中显示。选择资源并把对应面设为蓝色输出。开启自动弹出后送入相邻兼容容器／机器；关闭自动弹出仍允许支持对应能力的外部管道提取。资源按对应原生接口工作；空气通过安全压力下的主动供气进入气动管网。FE 产物输出面供外部线缆提取，其他面仍给工作电池充电。

旧机器默认保留化学品等资源的六面提取，自动弹出关闭；空气自动供气开启，FE 产物面默认全关。设置随存档、安装器升级、拆卸／挖掘的机器物品及合成升级保存。MEK 配置卡 Shift 右键读取，普通右键粘贴到兼容机器，只复制侧面和自动弹出设置，不复制资源账户、余额或 EMC 收款人；多机器合成沿矩阵顺序取首份设置。

**Genesis 扩展资源产出暂未适配 PB 原版蜂箱和离心机，请使用 Genesis 机械蜂箱／离心机。** PB 的普通产物仍按 PB 配方工作。相关蜂笼、刷怪蛋、蜜脾／蜜脾块和机械蜂箱蜂槽会默认显示提示，无需按 Shift。

扩展功能及其兼容问题请反馈到 [Genesis 问题页](https://github.com/Ayoshiko/productive-bees-genesis/issues)，避免将上述适配限制误报给 PB 上游。

### 5. 换一种生产方式

- **直接产 FE：** 使用 [energy.json](https://github.com/Ayoshiko/productive-bees-genesis/blob/main-neo/1.21.1/examples/resource-bees/datapack/data/example/productivebees/energy.json) 与 [energy_production.json](https://github.com/Ayoshiko/productive-bees-genesis/blob/main-neo/1.21.1/examples/resource-bees/datapack/data/example/recipe/energy_production.json)。花源为铜块，类型为 resource_production，kind 为 energy，energy_type 为 FE。产物 FE 与工作电池分开，ME 输出需要 Applied Flux。
- **先产蜜脾再产 FE：** 使用独立蜂种 energy_comb 及它的蜂箱、离心配方。
- **直接产极高压闪电：** 参考 [lightning_ehv_production.json](https://github.com/Ayoshiko/productive-bees-genesis/blob/main-neo/1.21.1/examples/resource-bees/datapack/data/example/recipe/lightning_ehv_production.json)。kind 为 lightning，tier 为 extreme_high_voltage。高压使用 high_voltage，电压之间不换算。
- **物品与资源同时产出：** mixed 同时保留 PB 蜂箱配方与直接资源配方。mixed_essence 演示精华转换，mixed_tag 演示标签花源。

createComb 只允许使用蜜脾外观，不会自动创建产蜜脾的配方。给同一蜂种同时设置直接生产和蜜脾离心，会在两个阶段分别产资源。

### 6. 常用字段与内置适配

- **bee、ingredient、bee_type**：都使用完整蜂种 ID，修改时一起改。
- **amount**：正整数字符串，例如 "1000"。其他资源上限 9223372036854775807；EMC 支持最多 4096 位十进制整数。不接受小数或科学记数法。
- **chance**：0 到 1 的数字。1.0 为必定，0.25 为每份 25%。
- **processing_time**：离心基础时间，20 tick 为一秒。
- **flowerBlock、flowerItem**：放进采蜜槽的方块或物品。普通静态花源重复使用。
- **flowerTag**：标签 ID 不加 #，参考 mixed_tag。
- **include_pb_outputs**：默认 false。改为 true 时，同次运行匹配的 PB 普通离心配方。

下面是内置适配的接线示例，**不是资源白名单**：

- chemical：Mekanism 化学品；ME 桥为 Applied Mekanistics。
- energy：FE；ME 桥为 Applied Flux。
- source：Ars Nouveau 魔源；ME 桥为 Ars Énergistique。
- soul：Industrial Foregoing Souls 灵魂；ME 桥为 Soulplied Energistics。
- air：PneumaticCraft 空气；ME 桥为 Applied Pneumatics。
- emc：ProjectE EMC，自动入账在线收款玩家，无需 EMC-ME 扩展。
- experience：ExperienceLib 经验点；ME 桥为 Applied Experienced。
- lightning：AE2 Lightning Tech 闪电，使用对应电压存储。
- ae_key：其他注册到 AE2 的有效资源键，使用提供方的存储与完整身份格式。

所有 ME 路线都需要 AE2 和对应存储。空气 1000 原始单位等于 1 L，bar 是压力而非产量；气动管路须封闭。经验和魔源不会直接写入玩家个人数值。

EMC 默认入账机器拥有者的 ProjectE 账户，并同步转化界面。无需 ME 存储；同时安装 ProjectE 与 FTB Teams 时才显示收款人按钮，机主或管理员点击资源窗口的“收款人”切换同队在线玩家，空库存也可切换。收款人离线、退队或能力不可用时 EMC 留在机器，恢复后继续入账。未知写入隔离，通知重试不重复加钱。

### 容器花源：按里面的资源匹配

Genesis 机械蜂箱及其工厂支持把装有资源的物品作为可重复使用的花源。普通物品花源只看物品 ID；容器花源会核对内容，所以空罐、装错资源或数量不足都不能工作。

JEI 使用 AE2 元件展示实际资源和最低含量；对应键桥与兼容元件可用时，展示副本存有所需资源。气体桶、Mekanism 化学品罐和提供对应能力的容器均按内容识别，单件单储槽达标且不消耗。

扩展资源没有可用出口或暂存额度不足时，机械离心机把原蜜脾交给持久化返还缓冲，ME 可接收时返网，并暂停该类蜜脾的新取料。出口恢复后自动续料；已付周期保留原配方、种子和完整输入身份，未知扣料／接收结果继续保管，不自动重放。

在蜂种 JSON 中添加 **flowerResource**，填写 kind 和字符串 amount；amount 表示最低数量，省略时为 "1"。不要同时保留 flowerBlock、flowerItem、flowerTag、flowerFluid，也不支持 inverseFlower 或采后回调；冲突或错误定义会拒绝工作。

- **化学品容器**：kind 为 chemical，id 例如 mekanism:hydrogen，amount 例如 "1000"。使用提供 Mekanism 化学品物品能力的桶、罐或其他容器。参考 [氢气容器蜂](https://github.com/Ayoshiko/productive-bees-genesis/blob/main-neo/1.21.1/examples/resource-bees/datapack/data/example/productivebees/container_hydrogen.json)。
- **液体容器**：kind 为 fluid，id 例如 minecraft:water，amount 例如 "1000"。支持 NeoForge 流体能力物品与传统桶。参考 [水容器蜂](https://github.com/Ayoshiko/productive-bees-genesis/blob/main-neo/1.21.1/examples/resource-bees/datapack/data/example/productivebees/container_water.json)。
- **能量容器**：kind 为 energy，energy_type 为 FE。支持标准 FE 能力物品，以及按当前换算读取的 Mekanism 能量物品。参考 [能量容器蜂](https://github.com/Ayoshiko/productive-bees-genesis/blob/main-neo/1.21.1/examples/resource-bees/datapack/data/example/productivebees/container_energy.json)。
- **其他 AEKey 资源**：kind 为 ae_key，填写真实 type、id、完整 data。放入已存有该资源的真实 ME 存储元件，不需要把蜂箱接入网络才能检查这个元件。参考 [ME 元件花源蜂](https://github.com/Ayoshiko/productive-bees-genesis/blob/main-neo/1.21.1/examples/resource-bees/datapack/data/example/productivebees/container_ae_key.json)。

液体和化学品的 id 也可写带 # 的资源标签。已有 flowerFluid 蜂种继续使用原字段，现可识别普通桶以外的流体能力容器；其最低要求是存在正数量的对应液体。

**采蜜不抽取资源，不消耗容器。** 数量按单件容器检查，不乘物品堆叠数量，也不合并多个采蜜槽。原生容器须有一个内部储槽达到门槛，最多检查 64 个内部槽；ME 元件按一个完整资源键的可用量判断。蜜蜂基因和升级不放大花源门槛。

容器被清空、换内容或数量不足后，最迟下一个真实服务器 tick 停止采蜜；禁用槽立即退出匹配。资源包只改变外观，不会改变所装资源身份。

包裹资源图标、显示模板和连接远端仓库的卡片不等同于真实本地库存。其他模组的原生资源物品只有提供上述读取能力才可直接使用；已注册 AEKey 的资源可以使用对应真实存储元件。此项花源扩展当前只接入 Genesis 机械宿主，PB 原版／JDTE 不能按新字段生产。

新增蜂种时同步中英文名称键。KubeJS 配方写法不变，flowerResource 保留在数据包的蜂种 JSON 中。

### 7. KubeJS 与组合离心

打开 [脚本示例目录](https://github.com/Ayoshiko/productive-bees-genesis/tree/main-neo/1.21.1/examples/resource-bees/kubejs/server_scripts)。蜂种 JSON 留在数据包里；脚本复制到 kubejs 的 server_scripts，删除脚本替代的配方 JSON，再执行 /reload。同一配方只选一种注册方式，避免同 ID 覆盖。

也可以像 PB 作者模板一样，把数据包的 data 复制到 kubejs/data，把资源包的 assets 复制到 kubejs/assets。两者保持原来的命名空间目录。每位客户端需要相同的 assets；不要再同时启用那份重复的数据包。

hydrogen.js 替代两份氢气配方；energy.js 演示直接生产与蜜脾离心两条路线。数量仍写成字符串。

combined 设置 include_pb_outputs 为 true，并保留匹配的 PB 离心配方。同次只扣一次输入和费用；没有匹配的普通配方会暂停。精华转换后再做粗矿熔炼，超过三个输出槽的已产余量留在机器内。

生产力、蜜脾块、基因采样和既有升级继续使用机器原规则。纯资源蜂没有普通产物时，不额外生成默认蜂蜜。

### 8. 通用产物注册与其他机器

[ae_key_production.json](https://github.com/Ayoshiko/productive-bees-genesis/blob/main-neo/1.21.1/examples/resource-bees/datapack/data/example/recipe/ae_key_production.json) 用 AE2 自带的 ae2:i 物品键演示钻石。换成其他模组资源时，填写它实际注册的 type、id、完整 data 和字符串 amount。

完整身份最大 1024 字节，必须可编码解码往返；缺失内容占位键不接受。通用键只向 ME 输出，不能自动变成对应模组的原生能力。包裹物品仅用于展示或模板，不给库存入账。

[Java 示例](https://github.com/Ayoshiko/productive-bees-genesis/blob/main-neo/1.21.1/examples/resource-bees/api/ResourceBeeApiExample.java) 使用 RegisterResourceRecipesEvent 注册产物；自定义资源有有效 AEKey 时也可按通用键注册。其他机器通过 RegisterResourceHostsEvent 和 ResourceHostApi 接入。数据包的同 ID 配方覆盖 Java 默认配方。

机器接入必须在服务器线程上预约、扣料和提交；transfer 回调返回实际接收量。外部结果未知时暂停，不能按零接收重试。具体编译命令见仓库 README。

Useless 2.4.6 使用已有原生配方 API，无需额外补丁。高级合金炉以 PB 离心机为模具、对应蜜脾／蜜脾块为输入，按自己的并行、催化剂、费用及输出规则执行。需 AE2、资源键桥和存储；只接确定性独立资源离心，随机和 PB 组合不接入。JDTE 0.6.4.3 官方版仍缺扩展蜂配方入口；本地 API v3 补丁只接配方产物，由生物工厂负责费用、原有周期暂存与 AE 输出升级回网，不使用 Genesis 账户或物流。拒收保留余量，未知接收结果暂停重发；PR 留待 Genesis 发布后提交。

### 9. 遇到问题按顺序检查

1. **没有蜂种或 JEI 配方：** 检查目录、ID 和 JSON，新蜂种重新进世界。
2. **名称显示翻译键：** 启用客户端语言资源包，检查名称键，再用 F3+T 重载。
3. **等待花源：** 检查采蜜槽、开关和花源；氢气用铁块，FE 与闪电用铜块。
4. **资源未入网：** 检查机器输出开关、频道、供电、键提供方和存储。
5. **本机已满：** 修复输出后继续，原料或已产资源会保留。
6. **显示待核验：** 保留存档并核对两端，管理员再处理；不要删除账户或反复补发。

管理员用 /pbgresources 查看资源账户，用 /pbgoutput 查看普通产物保管。retry-data 重读依赖恢复后的数据，confirm-production-settled 不补发产物。正常保存和升级保留资产。

不再使用 .session 标记决定停产。未知资源生产只隔离原槽，未知输出只隔离对应资源，其他生产继续。缺依赖资源蜜脾不进入万象候选和离心输入；已有蜜脾转入返还缓冲，断网时保管，回网后送回。安装 Project Expansion 后使用其短格式显示 EMC，后台整数仍精确。真实保存故障与损坏数据须排查；跨存储不保证断电原子恢复。
