---
navigation:
  parent: machines/machines-index.md
  title: Mekanism Apiary
  icon: "productivebeesgenesis:mek_apiary"
  position: 1
item_ids:
  - productivebeesgenesis:mek_apiary
  - productivebeesgenesis:basic_mek_apiary_factory
  - productivebeesgenesis:advanced_mek_apiary_factory
  - productivebeesgenesis:elite_mek_apiary_factory
  - productivebeesgenesis:ultimate_mek_apiary_factory
  - productivebeesgenesis:overclocked_mek_apiary_factory
  - productivebeesgenesis:quantum_mek_apiary_factory
  - productivebeesgenesis:dense_mek_apiary_factory
  - productivebeesgenesis:multiversal_mek_apiary_factory
  - productivebeesgenesis:creative_mek_apiary_factory
  - productivebeesgenesis:absolute_extra_mek_apiary_factory
  - productivebeesgenesis:supreme_extra_mek_apiary_factory
  - productivebeesgenesis:cosmic_extra_mek_apiary_factory
  - productivebeesgenesis:infinite_extra_mek_apiary_factory
  - productivebeesgenesis:absolute_overclocked_emextra_mek_apiary_factory
  - productivebeesgenesis:supreme_quantum_emextra_mek_apiary_factory
  - productivebeesgenesis:cosmic_dense_emextra_mek_apiary_factory
  - productivebeesgenesis:infinite_multiversal_emextra_mek_apiary_factory
---
# Mekanism Apiary

<GameScene zoom="5" interactive={true} background="transparent" fullWidth={true}>
  <Block id="productivebeesgenesis:mek_apiary" x="0" y="0" z="0" p:facing="south" p:active="false" />
  <Block id="productivebeesgenesis:mek_apiary" x="2" y="0" z="0" p:facing="south" p:active="true" />
  <IsometricCamera yaw="0" pitch="25" />
</GameScene>

Drag the scene to inspect the other faces; the initial view faces the front. The apiary is best understood as "keeping bees and flowers inside a machine": no entity hive in the world, and no waiting for bees to fly around.

<RecipeFor id="productivebeesgenesis:mek_apiary" fallbackText="This modpack replaced or disabled the apiary recipe — search JEI for the Mechanical Apiary." />

## From placement to production

1. Connect FE and confirm the energy bar has power.
2. Insert a bee or a bee cage into a bee slot.
3. Open the **Feeder** window and add the flower or pollen that bee needs. JEI or the slot tooltip shows the requirement.
4. Wait for the progress bar to fill, then collect combs and byproducts from the output area.
5. If there are fluid byproducts, drain them from a face configured as fluid output.

## Tiers and slot counts

| Machine | Bee slots | Output |
| --- | --- | --- |
| Mekanism Apiary (basic) | 3 (3×1) | 9 slots, single page |
| Basic factory | 5 | Two pages |
| Advanced factory | 10 | Two pages |
| Elite factory | 15 | Two pages |
| Ultimate factory | 20 | Two pages |
| Mekanism: Extras Absolute / Supreme / Cosmic / Infinite | 26 / 30 / 36 / 42 | Two pages |
| Evolved Mekanism Overclocked / Quantum / Dense / Multiversal / Creative | 26 / 30 / 36 / 42 / 45 | Two pages |
| Evolved Mekanism: Extras Absolute Overclocked / Supreme Quantum / Cosmic Dense / Infinite Multiversal | 45 / 51 / 55 / 60 | Two pages |

**Every bee slot is an independent workstation**: it evaluates its own bee, flower, recipe, weather, and behaviour conditions, so one idle bee never blocks the others.

<ItemGrid>
  <ItemIcon id="productivebeesgenesis:mek_apiary" />
  <ItemIcon id="productivebeesgenesis:basic_mek_apiary_factory" />
  <ItemIcon id="productivebeesgenesis:advanced_mek_apiary_factory" />
  <ItemIcon id="productivebeesgenesis:elite_mek_apiary_factory" />
  <ItemIcon id="productivebeesgenesis:ultimate_mek_apiary_factory" />
</ItemGrid>

<GameScene zoom="4" interactive={true} background="transparent" fullWidth={true}>
  <Block id="productivebeesgenesis:mek_apiary" x="0" y="0" z="0" p:facing="south" p:active="false" />
  <Block id="productivebeesgenesis:basic_mek_apiary_factory" x="1" y="0" z="0" p:facing="south" p:active="false" />
  <Block id="productivebeesgenesis:advanced_mek_apiary_factory" x="2" y="0" z="0" p:facing="south" p:active="false" />
  <Block id="productivebeesgenesis:elite_mek_apiary_factory" x="3" y="0" z="0" p:facing="south" p:active="false" />
  <Block id="productivebeesgenesis:ultimate_mek_apiary_factory" x="4" y="0" z="0" p:facing="south" p:active="false" />
  <IsometricCamera yaw="0" pitch="25" />
</GameScene>

Each tier upgrades from the previous one and keeps the bees and installed upgrades inside. Do not look at machine speed alone: the faster combs pile up, the more the centrifuge, chests, and network must keep up.

## Main screen

![Apiary main screen](../assets/images/gui_apiary.png)

### Tab strips

The left strip holds this machine's own entries; the right strip holds the generic ones:

- **Side configuration** (top left, block/grid icon): decides what each of the six faces accepts or emits. See [Common Screens and Controls](../gui.md).
- **Feeder** (dark teal, flower icon): opens the Feeder window, described below.
- **Sorting** (grey, **factory tiers only**): toggles automatic output sorting. The tab itself shows **On / Off**.
- **PB Upgrades** (orange): opens this addon's PB upgrade window — see [PB Upgrade Window](../upgrades/pb-upgrades.md).
- **MEK Upgrades** (up-arrow icon): opens Mekanism's own upgrade window, where you install **Speed** (shorter cycle, more power draw), **Energy** (larger internal buffer), and **Muffling** (less noise) upgrades; with Mekanism: Extras installed a **Creative** upgrade also appears.
- **Warning** (yellow exclamation mark): lights up when something is wrong; click it to see what is missing.
- **Redstone control / Security**: see [Common Screens and Controls](../gui.md).
- **FE**: the energy information page.

> **PB Upgrades and MEK Upgrades are two different windows** holding two different sets of items, but both can be installed at once and their effects stack. Hover a tab when in doubt.

### Bee slots and cage slots

- **Cage input slot** (red border, left of the bee slots): insert an empty or filled bee cage to move bees in and out of the machine.
- **Bee slots** (one or more rows in the middle): one bee per slot. Bee items and cages both work.
- **Cage output slot** (blue border, right of the bee slots): collect filled cages.
- Bee slots evaluate their conditions separately — **one idle bee never drags the others down**.

### Bee visuals and tooltips

- A bee model is rendered straight inside the slot, next to a **status lamp** whose colour differs between working and idle.
- **Left-clicking a bee slot selects it**; the selected slot gets a highlight border so an action can target that single bee.
- **Hovering a bee slot** shows detailed information:
  - By default: name, adult/child, health, progress (`Progress: current/total ticks (percent)`), state, and whether it has nectar.
  - **Hold Shift** to add: flower requirement, productivity, endurance, temper, behaviour, and weather tolerance. The last line always says "Hold Shift for more information".
- With many slots the apiary switches to a **compact mode**: shorter rows, and bee names are no longer drawn under the slots — only the model and status lamp remain.

### Output area and paging

- The output area sits below the bee slots and holds combs and byproducts.
- Factory tiers split it into **two pages**, with **`◀ 1/2 ▶`** centred underneath:
  - `◀` and `▶` switch pages; their tooltips read "Previous output page" and "Next output page".
  - **Paging only changes what you see.** It never hides real slots — pipes and AE2 still see and take every product.
- When the output is full the machine **pauses safely**; progress stops instead of silently voiding input.

### Energy bar and fluid tank

- The vertical strip on the right is the energy bar; the small tank on the left is the fluid tank (honey and similar).
- Fluid only leaves if the corresponding face is set to output on the **Fluids** page of side configuration, and auto-eject is enabled when you want the machine to push.

## Feeder window

![Feeder window](../assets/images/gui_feeder.png)

Click the **Feeder** tab on the left. This window decides what flowers every bee in the machine can reach.

### Buttons in the title bar

From left to right:

- **Close** (the × at the window's top left): closes the window; the items inside are kept.
- **Pin** (the small pin right of the ×): pins the window so it no longer follows the main screen.
- **`◀` / `▶`** (small grey buttons, tooltip "Previous page / Next page"): paging, **only shown when the feeder has more than 30 slots**.
- **`Conv`** (small grey button whose letter turns **green** when on; tooltip "Item conversion: on / off"): toggles item conversion.
- **`Lock`** (small grey button whose letter turns **orange** while the mode is active; tooltip "Per-slot disable mode: on / off"): toggles the per-slot disable editing mode.

**`转` — item conversion toggle** (tooltip verbatim, translated):

> Conversion: ON — bees convert convertible items inside the feeder into products and consume them (for example the Ender Dragon bee: obsidian → crying obsidian)
>
> Conversion: OFF (default) — items in the feeder act as flowers only and are never consumed; turning this on is what makes bees run item/block conversion recipes

The default is **off**: feeder contents are a work condition and stay put. Turn it on only for bees that trade items for products.

**`禁` — per-slot disable mode** (tooltip verbatim, translated):

> **Per-slot disable mode: ON**
> Left-clicking a slot disables/restores the bee output tied to that slot's item
> In this mode left-click no longer picks items up; click this button again to exit
> Shift-click this button: disable all / enable all at once
>
> **Per-slot disable mode: OFF**
> Click to enter the mode, then left-click a slot to disable/restore it
> Outside the mode, holding Alt and left-clicking does the same; empty slots do nothing
> Shift-click this button: disable all / enable all at once

Key points:

- Outside the mode, **Alt + left-click** on a slot does exactly what left-click does inside the mode — the shortcut.
- **Shift-clicking `禁`** is a batch action: if any slot is still active everything is disabled, otherwise everything is re-enabled.
- A disabled slot gets a translucent dark-grey overlay, and its tooltip gains "This slot is disabled" / "This item does not take part in bee production (Alt + left-click to restore)".
- Disabling only removes that slot from production; **the item stays inside** and can be restored at any time.
- The mode is a client-side "click interpretation" switch. It changes no server data and leaves nothing behind after you close the window.

### The slot grid

- The left grid holds the flowers or pollen that serve as the bees' work condition.
- **All bee slots share this one feeder**, so you do not need a flower next to every bee.
- The grid grows with tier; **above 30 slots it pages**, 30 slots per page:
  - Besides `◀` and `▶`, **scrolling the mouse wheel inside the window** pages too.
  - Paging only changes the view. Slot indices stay aligned and disabled flags travel with their items.
- Normal interactions (pick up, split stacks, wheel) keep working, except while the disable edit mode is active.

### The stats panel on the right

| Line | Meaning |
| --- | --- |
| Stats | Panel title |
| `Slots: used / total` | How many slots are filled, and the total |
| `Status: Active` / `Status: Idle` | "Active" (green) when at least one slot is effective; "Idle" (grey) when everything is disabled or empty |
| `Page 1/2` | Only shown when paging is needed |
| `Flowers:` | Lists the flower names currently inserted |
| `Flowers (N disabled):` | The header gains the disabled count when slots are disabled |
| `+N more` | Summarises the remainder when more than six flower types are present |

### The bottom hint line

The line at the very bottom reflects the current state and is the most direct answer to "what happens if I click now":

- Default: **Items are not consumed**
- With conversion on: **Conversion ON: ingredients will be consumed**
- In disable edit mode: **Click a slot to toggle disable** (highest priority)

## PB upgrades on the apiary side

![PB upgrade window](../assets/images/gui_pb_upgrades.png)

Click the orange **PB Upgrades** tab on the right. The window layout, how every button behaves, and the **full apiary-versus-centrifuge comparison table** live on [PB Upgrade Window](../upgrades/pb-upgrades.md).

Three things to remember for the apiary:

1. The apiary accepts **Productivity α/β/γ/Ω, Time, Time+, Gene Sampler, Comb Block, Byproduct Destruction, and Essence Conversion**;
2. It **rejects Stability and Raw Ore Smelting** — those are centrifuge-only and will refuse to install;
3. The Simulation upgrade is **built in** for apiaries: it takes no slot and shows as "Simulation ✓" in the supported list.

## Direct centrifuge links and where products go

Everything about product routing is controlled by the small buttons on the **Items** page of side configuration (appearance and positions: [Common Screens and Controls](../gui.md)):

| Setting | Where products go |
| --- | --- |
| All off | Local output area, waiting for a pipe or for you |
| Only `O` direct container output | Written into adjacent containers set to "Item Output"; overflow falls back to the output slots |
| Only `A` AE output | Sent to the ME network |
| `A` + `M` direct AE output | Fresh products **skip** the local output slots and go straight to the network |
| `D` centrifuge direct link | Products go straight into an adjacent centrifuge, bypassing output slots |
| `P` centrifuge priority | Processable products go to a centrifuge first; when it is full they buffer inside the apiary, and only overflow goes to AE |

The simplest automation: **place the apiary against a centrifuge and enable `P` (centrifuge priority)**. Fresh combs try the centrifuge first, and if it is full nothing is lost — they wait in a safe buffer and retry.

Recommended first line (energy cube → apiary → centrifuge → barrel):

<GameScene zoom="4" interactive={true} background="transparent" fullWidth={true} padding="5">
  <ImportStructure src="../assets/assemblies/first_line.snbt" />
  <IsometricCamera yaw="30" pitch="28" />
</GameScene>

## When it stalls

1. **No progress**: check whether the energy bar is 0, then hover the bee slots to see which condition is missing (flower, weather, time of day, nectar).
2. **Bees present but nothing produced**: open the Feeder window and confirm that flower is present **and not disabled** (no grey overlay).
3. **Progress completes but nothing appears**: the output area or fluid tank is full; the machine waits for space.
4. **Only some bees idle**: hover each slot to see what each one lacks instead of dismantling the machine.
5. **An upgrade will not install**: check that it targets the right machine (Stability and Raw Ore Smelting are centrifuge-only) or that the limit is reached.
6. **Products pile up in the output area**: inspect the item output faces and auto-eject, or switch to `D` / `P` direct transfer.
