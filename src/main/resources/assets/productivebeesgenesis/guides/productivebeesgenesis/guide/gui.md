---
navigation:
  parent: index.md
  title: 界面与按钮
  icon: "mekanism:configurator"
  position: 5
---
# 界面与按钮

<ItemImage id="mekanism:configurator" scale="2" />

本模组机器沿用 Mekanism 的操作习惯。第一次打开时不用一次学完所有标签：先看能量、输入、输出和警告，机器跑起来后再碰自动化按钮。

## 所有机器都有的内容

- **能量条**：显示当前 FE 与容量。一直是 0 说明没接上电；忽高忽低通常说明供电不够稳定。
- **进度条**：显示本轮工作进度。悬停可看到正在处理什么或还差什么。
- **侧面配置**：指定每个方向允许进出哪类内容。接了管道但没反应，先看这里。
- **自动弹出**：让机器主动向相邻管道或容器推送。仅把一面设为输出，并不一定会自动推。
- **红石控制**：新手保持“忽略”。设成需要信号后，没有红石信号机器就会停。
- **安全**：控制其他玩家能否打开或修改机器。多人服务器自动化失效时也要检查权限。
- **警告**：缺电、输入不匹配、输出堵塞等状态会在这里提示。

## 先认准这两个标签

机器窗口边缘的两个小标签分别打开 **PB 升级** 和 **喂食器**。鼠标悬停标签即可查看名称；它们只在机械蜂箱界面中出现。

## 机械蜂箱的窗口

<ItemGrid>
  <ItemIcon id="minecraft:poppy" />
  <ItemIcon id="productivelib:upgrade_productivity" />
  <ItemIcon id="productivebees:honey_bucket" />
</ItemGrid>

- **喂食器**：放蜜蜂需要的花朵或花粉；所有工作位共用，而且不会每轮消耗。
- **PB 升级**：查看可安装升级、当前数量和实际效果。
- **输出翻页**：只改变屏幕上显示哪一页，机器内部所有槽位仍存在。
- **离心机优先**：相邻有可用离心机时，先走直连路径。
- **AE2 输出**：把产物直接送入已连接的 ME 网络。

## 离心机的窗口

- **PB 配方**：跳到 JEI 的资源蜜蜂离心配方。
- **输入返回**：回到刚才查看过的输入物品。
- **多流体槽**：查看每种流体分别占用的容量；流体堵塞时优先打开这里。
- **熔炼兼容**：让本机接受可熔炼输入。服务器总开关允许后，每台机器仍要单独开启。
- **AE2 输入**：设置离心机从网络主动拿什么、拿多少、至少留多少。

## 配置器和端口颜色

手持 Mekanism 配置器观察机器，可以看到不同内容类型的端口提示。颜色用来区分能量、物品与流体，但不同资源包可能让颜色看起来略有差异；最终以侧面配置窗口中的文字状态为准。

<GameScene zoom="5" interactive={true} background="transparent" fullWidth={true}>
  <Block id="productivebeesgenesis:mek_apiary" x="0" y="0" z="0" p:facing="south" p:active="false" />
  <Block id="productivebeesgenesis:mek_centrifuge" x="2" y="0" z="0" p:facing="south" p:active="false" />
  <IsometricCamera yaw="15" pitch="25" />
</GameScene>

## 窗口跑到屏幕外

喂食器、多流体、PB 升级和 AE2 输入窗口会记住上次位置。分辨率或 GUI 缩放改变后，如果窗口看不见，进入模组配置的**客户端设置 → 自定义窗口位置**并重置对应窗口；不必删除整个配置文件。
