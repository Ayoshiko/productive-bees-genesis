---
navigation:
  parent: index.md
  title: 第一次开机
  icon: "minecraft:crafting_table"
  position: 1
---
# 第一次开机

本章目标只有一个：让一只蜜蜂在机械蜂箱里产出蜜脾，再让离心机把蜜脾拆成资源。先别急着做工厂、AE2 或高倍升级，基础生产线跑通后再扩建最省心。

## 先认识三个词

- **FE**：科技模组常用的电力单位。机器能量条不再下降到 0，就说明供电跟得上。
- **侧面配置**：决定机器每一面负责进电、进物品还是出产物。颜色只是提示，真正有用的是“输入、输出、无”。
- **游戏刻**：Minecraft 每秒通常运行 20 刻。教程里说 200 刻，大约就是 10 秒。

如果你第一次玩 Minecraft：右键方块打开界面；物品跟随鼠标时左键放下一整组、右键放一个；按住 Shift 再点击通常可以快速搬运物品。

## 需要准备什么

<ItemGrid>
  <ItemIcon id="productivebeesgenesis:mek_apiary" />
  <ItemIcon id="productivebeesgenesis:mek_centrifuge" />
  <ItemIcon id="mekanism:basic_energy_cube" />
  <ItemIcon id="mekanism:basic_universal_cable" />
  <ItemIcon id="minecraft:barrel" />
  <ItemIcon id="minecraft:poppy" />
</ItemGrid>

你需要一台能发 FE 的设备、线缆或能量立方、机械蜂箱、离心机、至少一只 PB 蜜蜂，以及这只蜜蜂需要的花朵或花粉。JEI 中把鼠标移到物品上按“查看配方”键，可以查到获取方式。

### 两台核心机器的配方

下面就是当前游戏实际加载的配方。把鼠标移到材料上可以看名称；整合包若替换了这两份配方，JEI 显示的结果优先。

<RecipeFor id="productivebeesgenesis:mek_apiary" fallbackText="当前整合包替换或关闭了机械蜂箱配方，请在 JEI 中搜索机械蜂箱。" />

<RecipeFor id="productivebeesgenesis:mek_centrifuge" fallbackText="当前整合包替换或关闭了离心机配方，请在 JEI 中搜索通用机械离心机。" />

## 照着搭第一条线

<GameScene zoom="4" interactive={true} background="transparent" fullWidth={true} padding="5">
  <ImportStructure src="../assets/assemblies/first_line.snbt" />
  <IsometricCamera yaw="30" pitch="28" />
</GameScene>

场景可以拖动旋转：从左到右是基础能量立方、第一段通用线缆、蜂箱、第二段通用线缆、离心机和接成品的木桶。第一段线缆连接能量立方与蜂箱，第二段线缆连接蜂箱与离心机，木桶贴着离心机；场景只表示推荐摆法，机器各面的输入输出仍要在界面里设置。

1. **接电。** 给两台机器连接 FE。打开界面，确认能量条已经有数值。
2. **放蜜蜂。** 把蜜蜂或装着蜜蜂的笼子放进蜂箱的蜜蜂槽。
3. **放花。** 打开喂食器窗口，放入 JEI 提示的花朵或花粉。这里的物品是工作条件，不会每轮都被吃掉。
4. **等蜜脾。** 进度条走满后，输出区应出现蜜脾。若没出现，先看本页末尾的检查表。
5. **送进离心机。** 可以手动搬运，也可以让相邻蜂箱优先把蜜脾送给离心机。
6. **取成品。** 离心机完成后会产出物品，有些配方还会产出蜂蜜等流体。

## 你成功时会看到

- 两台机器能量条有电，而且不会长期归零。
- 蜂箱进度条持续前进，输出区出现蜜脾。
- 离心机进度条接着前进，输出槽出现资源。
- 输出满时机器会等待，不会悄悄吞掉输入。

## 第一次没跑起来

按这个顺序查，通常一分钟内就能找到原因：

1. 能量条是不是 0？
2. 红石控制是不是“忽略”？
3. 蜜蜂、花朵和配方是不是互相匹配？
4. 输出槽或流体罐是不是满了？
5. 线缆和管道连接的那一面，是否真的设置成输入或输出？

跑通以后继续看[机械蜂箱](../machines/apiary.md)，了解每个槽位和按钮；遇到异常也可以直接跳到[故障排查](../troubleshooting.md)。
