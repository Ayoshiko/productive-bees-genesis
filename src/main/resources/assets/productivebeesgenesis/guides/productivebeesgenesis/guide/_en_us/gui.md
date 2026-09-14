---
navigation:
  parent: getting_started/index.md
  title: Common Screens and Controls
  icon: "mekanism:configurator"
  position: 3
---
# Common Screens and Controls

<ItemImage id="mekanism:configurator" scale="2" />

This page covers only what **every machine shares**: the window frame, the energy bar, the progress bar, redstone control, security, side configuration, and auto-eject.

The Mekanism Apiary and the Mekanism Centrifuge each have their own slots, tabs, and pop-up windows; those live on [Mekanism Apiary](machines/apiary.md) and [Mekanism Centrifuge](machines/centrifuge.md) instead of being piled up here.

## The five regions of a machine window

1. **Title bar**: centred at the top, showing the machine name. On its left are the close button and a **pin** (keeps the window in place); dragging the title bar moves the window.
2. **Left tab strip**: small tabs on the left edge, top to bottom. This is where most of a machine's own input/output entries live.
3. **Right tab strip**: upgrades, redstone, and security. A **warning** tab appears at the top when something is wrong.
4. **Centre**: energy bar, fluid tank, progress bar, input and output slots. Different per machine.
5. **Bottom**: your inventory, labelled "Inventory". Shift-click moves whole stacks quickly.

> Tab icons are not always self-explanatory, so **hover a tab to read its name**. Every slot, button, and progress bar has a tooltip too — hover first when something is unfamiliar.

## Energy bar and the FE tab

- The **energy bar** is a vertical strip; the pointer height shows how much FE is stored. Hover it for the exact "stored / capacity" numbers.
- Stuck at 0: no power, or the powered face is not configured as an energy input.
- Jumping up and down: unstable supply. The machine keeps stopping and starting, so throughput suffers.
- The **FE tab** opens the energy page: stored FE, capacity, and FE/t. With Applied Flux installed it also shows network power status; see [Upgrade and Automation Details](upgrades/automation.md).

## Progress bar

The progress bar (arrow or strip) shows how far the current operation has come. Hover it to see what is being processed and what is missing:

- **Input mismatch**: this machine has no recipe for the inserted item.
- **Output full**: products have nowhere to go, so the machine pauses instead of voiding input.
- **Not enough energy**: supply cannot keep up.

## Redstone control

The **redstone control** tab decides when the machine runs. Click to advance through four modes:

| Mode | Meaning |
| --- | --- |
| Redstone: Ignored | Default. Runs with or without a signal — keep this for a first playthrough |
| Redstone: High | Runs only while it receives a redstone signal |
| Redstone: Low | Runs only while it receives **no** signal (inverted) |
| Redstone: Pulse | Runs once per redstone pulse |

Leaving this on High or Low and forgetting to wire redstone is the most common cause of "the machine has power but does nothing".

## Security

The **security** tab controls whether other players may open or modify this machine. Irrelevant in single player; on a server, check it first when automation stops working or others cannot access your machines.

## Side configuration

**Side configuration** is the machine's wiring manual: it decides what each of the six faces may accept or emit. If a pipe is connected and nothing moves, check here first.

### How to open it

The **topmost tab on the left** (block/grid icon) is **side configuration**. Clicking it opens a 156×135 side configuration window.

### What is in the window

- **Transmission type tabs** (top row): switch which kind of content you are configuring — **items / fluids / energy / heat / chemicals**. A machine only shows the kinds it supports.
- **Six face buttons** in the middle, laid out as an unfolded cube: top, front, left, right, bottom, back. Each button shows that face's current state as text.
- **Info screen** (a narrow strip above the middle): shows the currently selected transmission type.
- **Auto-eject button** (14×14, top right): see below.
- **Clear button** (14×14, bottom right): resets all six faces of the current type to "None". Shift-click clears **every** type.

> **Each transmission type keeps its own set of six faces.** Changing item sides does not affect fluids, and changing fluids does not affect energy, so check the faces again after switching tabs.

### The four face states

Each face has four states. **Left-click advances to the next state; Shift-left-click steps back.**

| State | Meaning |
| --- | --- |
| **None** | The face is sealed — nothing may pass. Use it to keep pipes out |
| **Input** | External content may enter the machine through this face |
| **Output** | The machine may emit content through this face |
| **Input/Output** | Both directions allowed; good for a two-way ME network connection |

Button colours are only a hint (resource packs may change them). **The text on the button is what actually counts.**

> **Important: setting a face to "Output" does not make the machine push anything.** The face state only answers "is movement allowed". Whether the machine actively sends is decided by **auto-eject**, by a pipe pulling, or by this addon's own direct-transfer buttons.

### Auto-eject

The **14×14 button in the top right** of the side configuration window is Mekanism's **auto-eject** toggle. It applies to the currently selected transmission type only:

- When on, the machine actively pushes that kind of content toward adjacent containers or pipes that are set to "Output".
- When off, an "Output" face can still only be drained by a pipe or by you.
- The eject rate and "excess percentage" belong to Mekanism's own config, not this addon's.

## Buttons this addon injects into side configuration

To avoid tab-hunting, this addon puts frequently used switches straight into the side configuration window. They all look the same:

> **A 14×14 grey square with a single letter. The letter turns green when the feature is on and stays dark grey when it is off.** Hover for the full description.

They sit in two fixed columns: **left column at x=120, right column at x=136**. Almost all of them appear on the **Items** page only; `A` (AE output) appears on both the Items and Fluids pages.

### Mekanism Apiary

- **`A` AE output** (left, top): toggles pushing products to the ME network. The Items page handles items, the Fluids page handles fluids. Hidden when AE2 is absent.
- **`M` Direct AE output** (left, middle): fresh products try the ME network first and skip the local output slots. Only clickable on a page where `A` is already on.
- **`O` Direct container output** (left, bottom): fresh products are simulated first, then written into adjacent containers set to "Item Output"; anything that does not fit falls back to the output slots. Gated by a server master switch.
- **Auto-eject** (right, top, Mekanism native): see above.
- **`D` Centrifuge direct link** (right, middle): sends products straight into an adjacent centrifuge, bypassing output slots. Use the item output faces to choose which centrifuges (several allowed).
- **`P` Centrifuge priority** (right, bottom): fresh combs go to a centrifuge first; when it is full they are buffered inside the apiary and only the overflow goes to AE. Hidden when AE2 is absent.

### Mekanism Centrifuge

- **`A` AE output** (left, top): same as the apiary.
- **`I` AE2 input config** (left, 2nd): opens the [AE2 Input Window](upgrades/ae2-input.md) so the centrifuge can pull from the ME network. Items page only, hidden when AE2 is absent.
- **`F` Smelting compatibility** (left, 3rd): lets this centrifuge also process smeltable inputs. The server master switch must be on first.
- **`A` Direct AE output** (left, 4th): fresh products skip the local cache and go straight to the network; anything the network rejects falls back to local output.
- **`O` Direct container output** (left, 5th): same as the apiary.
- **Auto-eject** (right, top, Mekanism native): see above.

> `A` / `I` / `M` / `P` only exist when AE2 is installed. Without AE2 they are never created, so a missing button does not mean a broken feature.

## A window wandered off screen

The **Feeder, Multi-Fluid Tanks, PB Upgrade, and AE2 Input** windows remember their last position and pinned state. If one disappears after changing resolution or GUI scale:

1. Open the mod config (Mods → Productive Bees Genesis → Config).
2. Go to **Client Settings → Custom Window Positions**.
3. Reset that window's coordinates (`window_feeder` / `window_pb_upgrade` / `window_ae_input` / `window_multi_fluid_tanks`).

There is no need to delete the whole config file.

## Quick reference

| Action | Result |
| --- | --- |
| Left-click drag an item | Drop the whole stack |
| Right-click drag an item | Drop a single item |
| Shift-left-click an item | Move the whole stack (machine ↔ inventory) |
| Shift-left-click a side button | Step the state backwards |
| Left-click a tab | Open or close its window |
| Drag the title bar | Move the window |
| Click the pin | Keep the window from following the main screen |
| Hover anything | Show its name and status |

<GameScene zoom="5" interactive={true} background="transparent" fullWidth={true}>
  <Block id="productivebeesgenesis:mek_apiary" x="0" y="0" z="0" p:facing="south" p:active="false" />
  <Block id="productivebeesgenesis:mek_centrifuge" x="2" y="0" z="0" p:facing="south" p:active="false" />
  <IsometricCamera yaw="15" pitch="25" />
</GameScene>

Both machines follow Mekanism conventions, so once this page makes sense, other Mekanism machines will too.
