---
navigation:
  parent: index.md
  title: Screens and Controls
  icon: "mekanism:configurator"
  position: 5
---
# Screens and Controls

<ItemImage id="mekanism:configurator" scale="2" />

Both machines use familiar Mekanism controls. You do not need to learn every tab at once: identify power, inputs, outputs, and warnings first, then configure automation after the machine works.

## Controls shared by both machines

- **Energy bar** shows stored FE and capacity. A constant zero means power is not reaching the machine.
- **Progress bar** shows the current operation; hover it to see what is running or missing.
- **Side Configuration** decides which face accepts or outputs items, fluids, and energy.
- **Auto-Eject** makes the machine push to an adjacent pipe or container. Marking a face as output alone may not push.
- **Redstone Control** should stay on **Ignored** while learning.
- **Security** controls who may open or change the machine on multiplayer servers.
- **Warnings** report missing power, invalid inputs, and blocked outputs.

## Recognize the two apiary tabs

The two small tabs on the machine window edge open **PB Upgrades** and the **Feeder**. Hover a tab to see its label; these tabs appear on the apiary screen only.

## Apiary controls

- **Feeder** stores reusable flowers or pollen for every process.
- **PB Upgrades** manages Productive Bees upgrades and server limits.
- Output arrows change the visible page; automation still sees all slots.
- **Centrifuge Priority** prefers direct transfer to an adjacent centrifuge.
- AE2 output sends items or fluids to a connected ME network.

## Centrifuge controls

- **PB Recipe** opens the matching JEI category.
- **Input Return** revisits the last input.
- **Multi-fluid** pages through fluid-specific storage.
- **Smelting Compatibility** enables valid smelting inputs per machine.
- **AE2 Input** controls pulling mode, exact matching, amounts, and network reserve.

## Configurator and port colors

Hold the Mekanism Configurator while looking at a machine to reveal port hints. Colors distinguish energy, item, and fluid faces, but resource packs may change how they look. Treat the written input/output state in Side Configuration as the final reference.

<GameScene zoom="5" interactive={true} background="transparent" fullWidth={true}>
  <Block id="productivebeesgenesis:mek_apiary" x="0" y="0" z="0" p:facing="south" p:active="false" />
  <Block id="productivebeesgenesis:mek_centrifuge" x="2" y="0" z="0" p:facing="south" p:active="false" />
  <IsometricCamera yaw="15" pitch="25" />
</GameScene>

If a movable window is off-screen after changing resolution or GUI scale, reset it under **Client Settings → Custom Window Positions** instead of deleting every config file.
