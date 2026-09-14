---
navigation:
  parent: machines/machines-index.md
  title: Mekanism Centrifuge
  icon: "productivebeesgenesis:mek_centrifuge"
  position: 2
item_ids:
  - productivebeesgenesis:mek_centrifuge
  - productivebeesgenesis:basic_mek_centrifuge_factory
  - productivebeesgenesis:advanced_mek_centrifuge_factory
  - productivebeesgenesis:elite_mek_centrifuge_factory
  - productivebeesgenesis:ultimate_mek_centrifuge_factory
  - productivebeesgenesis:overclocked_mek_centrifuge_factory
  - productivebeesgenesis:quantum_mek_centrifuge_factory
  - productivebeesgenesis:dense_mek_centrifuge_factory
  - productivebeesgenesis:multiversal_mek_centrifuge_factory
  - productivebeesgenesis:creative_mek_centrifuge_factory
  - productivebeesgenesis:absolute_extra_mek_centrifuge_factory
  - productivebeesgenesis:supreme_extra_mek_centrifuge_factory
  - productivebeesgenesis:cosmic_extra_mek_centrifuge_factory
  - productivebeesgenesis:infinite_extra_mek_centrifuge_factory
  - productivebeesgenesis:absolute_overclocked_emextra_mek_centrifuge_factory
  - productivebeesgenesis:supreme_quantum_emextra_mek_centrifuge_factory
  - productivebeesgenesis:cosmic_dense_emextra_mek_centrifuge_factory
  - productivebeesgenesis:infinite_multiversal_emextra_mek_centrifuge_factory
---
# Mekanism Centrifuge

<GameScene zoom="5" interactive={true} background="transparent" fullWidth={true}>
  <Block id="productivebeesgenesis:mek_centrifuge" x="0" y="0" z="0" p:facing="south" p:active="false" />
  <Block id="productivebeesgenesis:mek_centrifuge" x="2" y="0" z="0" p:facing="south" p:active="true" />
  <IsometricCamera yaw="0" pitch="25" />
</GameScene>

Drag the scene to inspect the other faces; the initial view faces the front. The centrifuge is a "comb splitter": it reads the bee type recorded on a comb and produces ores, materials, and honey-like fluids through Productive Bees centrifuge recipes.

<RecipeFor id="productivebeesgenesis:mek_centrifuge" fallbackText="This modpack replaced or disabled the centrifuge recipe — search JEI for the Mekanism Centrifuge." />

## First run

1. Supply FE and set the powered face as an energy input.
2. Insert a comb or comb block carrying the correct bee type.
3. Wait for the progress bar to finish, then collect items and fluids.
4. To push fluids out automatically, enable **auto-eject** in side configuration; an "Output" face alone does not push anything.

## Parallelism and tiers

A plain centrifuge processes one operation at a time; factories run several in parallel:

| Factory tier | Parallel operations |
| --- | --- |
| Basic | 3 |
| Advanced | 5 |
| Elite | 7 |
| Ultimate | 9 |
| Mekanism: Extras Absolute / Supreme / Cosmic / Infinite | 11 / 13 / 15 / 17 |
| Evolved Mekanism Overclocked / Quantum / Dense / Multiversal / Creative | 11 / 13 / 15 / 17 / 19 |
| Evolved Mekanism: Extras Absolute Overclocked / Supreme Quantum / Cosmic Dense / Infinite Multiversal | 12 / 14 / 16 / 18 |

The machine **only completes a recipe after confirming every product has somewhere to go**, so a full chest pauses it safely instead of voiding output.

<ItemGrid>
  <ItemIcon id="productivebeesgenesis:mek_centrifuge" />
  <ItemIcon id="productivebeesgenesis:basic_mek_centrifuge_factory" />
  <ItemIcon id="productivebeesgenesis:advanced_mek_centrifuge_factory" />
  <ItemIcon id="productivebeesgenesis:elite_mek_centrifuge_factory" />
  <ItemIcon id="productivebeesgenesis:ultimate_mek_centrifuge_factory" />
</ItemGrid>

<GameScene zoom="4" interactive={true} background="transparent" fullWidth={true}>
  <Block id="productivebeesgenesis:mek_centrifuge" x="0" y="0" z="0" p:facing="south" p:active="false" />
  <Block id="productivebeesgenesis:basic_mek_centrifuge_factory" x="1" y="0" z="0" p:facing="south" p:active="false" />
  <Block id="productivebeesgenesis:advanced_mek_centrifuge_factory" x="2" y="0" z="0" p:facing="south" p:active="false" />
  <Block id="productivebeesgenesis:elite_mek_centrifuge_factory" x="3" y="0" z="0" p:facing="south" p:active="false" />
  <Block id="productivebeesgenesis:ultimate_mek_centrifuge_factory" x="4" y="0" z="0" p:facing="south" p:active="false" />
  <IsometricCamera yaw="0" pitch="25" />
</GameScene>

## Main screen

![Centrifuge main screen](../assets/images/gui_centrifuge.png)

### Slots and readouts

- **Input slot** (red border, upper left): insert combs or comb blocks. The machine reads the bee type and picks the matching recipe automatically.
- **Main output slot plus two secondary output slots** (blue borders, stacked on the right): ores, ingots, and other items land here.
- **Fluid tank** (small tank at the lower left): some recipes also produce honey-like fluids. Store them here and drain with a pipe or bucket.
- **Progress bar** (between the input and output slots): shows the current cycle; hover it to see what is missing.
- **Energy bar**: the vertical strip on the right, as on every machine.

### Tab strips

- **Side configuration** (top left): per-face input/output settings — see [Common Screens and Controls](../gui.md).
- **Multi-Fluid Tanks** (grey; **only on centrifuge factories running in multi-fluid mode**): opens the Multi-Fluid Tanks window, covered later on this page.
- **Sorting** (grey, factory tiers only): toggles automatic output sorting; the tab shows **On / Off**.
- **PB Upgrades** (orange): this addon's PB upgrade window — see [PB Upgrade Window](../upgrades/pb-upgrades.md).
- **MEK Upgrades** (up-arrow icon): Mekanism's own upgrade window — **Speed, Energy, Muffling**, plus **Stack** (2^N parallelism) and **Creative** when Mekanism: Extras is installed.
- **Warning / Redstone control / Security / FE**: see [Common Screens and Controls](../gui.md).

### Buttons unique to this machine

- **PB Recipe** (the progress bar itself): with JEI installed, jumps straight to the centrifuge recipe page for the current comb.
- **`R` Input return** (right-aligned below the output slots, a 14×14 grey square that turns green on hover; tooltip "Return items from the input slot"): sends unprocessed input items back.
- **`F` Smelting compatibility** (side configuration, left column, 3rd cell; tooltip "Power Furnace Recipe Compatibility: on / off"): lets this centrifuge also process smeltable inputs, for example raw ore straight to ingots.
- **`A` AE output** (side configuration, top left; tooltip "Output to AE"): toggles pushing items / fluids to the ME network.
- **`I` AE2 input config** (side configuration, left column, 2nd cell): opens the [AE2 Input Window](../upgrades/ae2-input.md) so the centrifuge can pull from the network.
- **`A` Direct AE output** (side configuration, left column, 4th cell; tooltip "Direct AE output: on / off"): fresh products skip the local cache and go straight to the network; rejects fall back to local output.
- **`O` Direct container output** (side configuration, left column, 5th cell; tooltip "Direct output to MEK item output faces: on / off"): fresh products are simulated first, then written into adjacent containers set to "Item Output"; the overflow falls back to the output slots.

#### What the `R` input-return button can report

- **Input slot is empty** — there is nothing in the centrifuge input slot to return.
- **AE2 connected and the network accepts** — returned N items to AE2, M waiting to retry; K left in the input slot.
- **AE2 connected but the network refuses** — the AE2 network is online but has no room or does not accept these items; input unchanged.
- **No AE2, an item output face is set, and a container is adjacent** — sent N items to the adjacent container along the Mekanism item output direction, K left in the input slot.
- **No AE2 and no item output face configured** — no Mekanism item output direction is configured; input unchanged.
- **No AE2 and no container on the output face** — the configured item output direction has no adjacent container that accepts items.
- **No AE2 and the adjacent container is full** — the adjacent container is full or does not accept these items; input unchanged.

In other words, **`R` returns the input slot rather than clearing it** — anything that cannot be returned stays put and is never deleted.

### What factory tiers look like

![Ultimate centrifuge factory](../assets/images/gui_centrifuge_ultimate.png)

A factory packs a whole row of parallel slots into one block:

- **One slot group per process**: a red input slot plus three blue output slots (main plus two secondary), repeated side by side.
- **Each process has its own progress bar**; one blocked lane never stalls the others.
- **A single shared fluid tank**, positioned next to the output slots or near the inventory depending on tier.
- Factories also gain the **Sorting** tab, and the `R` button moves into the gap between the output area and the inventory, right-aligned.
- Higher tiers add parallelism, so **power supply and output logistics must keep up** or products will back up.

## Multi-Fluid Tanks window

Only when the centrifuge runs in **multi-fluid mode** does the grey **Multi-Fluid Tanks** tab appear on the left.

The window is a **single horizontal row** of gauges, one per fluid tank:

- Each gauge shows that fluid's **stored amount and capacity**; hover for exact values.
- **The primary tank is not in this window**: it only lists the additional tanks (index 0 is skipped) — the primary one is drawn on the main screen.
- If the machine allocates a new fluid tank at runtime, the window **grows an extra gauge automatically**; no need to reopen it.
- The window has a close button and a **pin**; its position is remembered. If it wanders off screen, reset it as described in [Common Screens and Controls](../gui.md).

**Why it clogs**: different bees can produce different fluids. Multi-fluid mode assigns a separate tank to each new fluid to avoid mixing, but **once every available tank is full, a new fluid still cannot enter** and the machine stops. Open this window, find the full gauge, and drain it rather than inserting more combs.

## About comb types

Myriad Creations combs and Productive Bees configurable combs identify their recipe through the bee type stored on them. Normal production and machine transport preserve it; a blank comb created by a command, script, or another mod has no type information, so the centrifuge cannot know which bee to process it as.

## PB upgrades on the centrifuge side

![PB upgrade window](../assets/images/gui_pb_upgrades.png)

Click the orange **PB Upgrades** tab on the right. The window layout, how every button behaves, and the **full apiary-versus-centrifuge comparison table** live on [PB Upgrade Window](../upgrades/pb-upgrades.md).

Three things to remember for the centrifuge:

1. It accepts **Productivity α/β/γ/Ω, Time, Time+, Stability, Byproduct Destruction, Essence Conversion, and Raw Ore Smelting**;
2. **Stability is centrifuge-only** (each level improves the chance of non-guaranteed byproducts); the apiary refuses it;
3. It does **not** accept the Gene Sampler or Comb Block upgrades — those are apiary-only.

## AE2 input: letting the centrifuge fetch its own materials

The centrifuge is the only machine in this addon that can **actively pull from an ME network**. The entry point is the `I` button in the left column of the side configuration window.

The full reference — window layout, every button, amounts and reserves, tag expressions, the click table, and pitfalls — is its own page: [AE2 Input Window](../upgrades/ae2-input.md).

Shortest path to a working setup:

1. Confirm the ME network has power, a free channel, and storage space.
2. Mark exactly one comb in a filter cell.
3. **Set a small pull amount** (1–4) so the input slots are not flooded.
4. Set a **network reserve** so the last emergency stack is never drained.
5. Watch a few cycles, then add more entries.

## When it stalls

1. **No progress**: is the energy bar at 0, is the input really a valid comb (not a honey bottle), and is redstone control left on a signal mode?
2. **Progress finishes but nothing appears**: the output slots or the fluid tank are full. On factories, check **every** process lane.
3. **Only one lane is stuck**: that lane's input slot is empty or its output slots are full; the others keep running.
4. **Fluid will not leave**: no fluid output face, no auto-eject, or in multi-fluid mode the matching tank is full.
5. **Raw ore is not becoming ingots**: `F` smelting compatibility is off, the Raw Ore Smelting upgrade is missing, or the server master switch is off.
6. **AE2 input does nothing**: the pull toggle inside the `I` window is off, no entries are configured, the filter mode is disabled, or the network simply does not contain that comb.
