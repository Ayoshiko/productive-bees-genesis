# Productive Bees Genesis

[![CurseForge](https://img.shields.io/badge/CurseForge-Download-orange?style=flat-square&logo=curseforge)](https://www.curseforge.com/minecraft/mc-mods/productive-bees-genesis)
![Version](https://img.shields.io/badge/version-1.2.1-blue?style=flat-square)
![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-green?style=flat-square)
![NeoForge](https://img.shields.io/badge/NeoForge-21.1.214+-orange?style=flat-square)
![License](https://img.shields.io/badge/license-MIT-blue?style=flat-square)
![Java](https://img.shields.io/badge/Java-21+-red?style=flat-square)

> An addon for **Productive Bees** and **Mekanism** that adds the **Myriad Creations Bee** (rainbow-gradient, honeycombs randomly transform into other resource bees' honeycombs), the **MEK Centrifuge**, and the **MEK Apiary** — machines that preserve Mekanism's native processing logic, GUI style, side configuration, and upgrade system, while deeply integrating **Applied Energistics 2** for output pushback, honeycomb pulling, and network-powered energy. The machines also implement Resource Bees' upgrade components via Mekanism's upgrade installation system, offering an efficient new way to raise bees and process honeycombs.

**Languages**: [English](README.md) · [中文](README_zh.md)

**Version 1.2.1: editor usability, Pinyin search and cross-pack import improvements.** This local update includes the changes since 1.2.0 and has not been published to external platforms. See the [CHANGELOG](CHANGELOG.md#121---2026-10-29).

**Download**: [CurseForge](https://www.curseforge.com/minecraft/mc-mods/productive-bees-genesis) · [GitHub Releases](https://github.com/Ayoshiko/productive-bees-genesis/releases)

> Please report bugs and compatibility issues through [GitHub Issues](https://github.com/Ayoshiko/productive-bees-genesis/issues).

**Translations and contributions:** everyone can submit a pull request through a [fork](https://github.com/Ayoshiko/productive-bees-genesis/fork). Create a branch in your fork, add or edit the translation under `src/main/resources/assets/productivebeesgenesis/lang/` (for example `fr_fr.json`), and use **Contribute → Open pull request** with `Ayoshiko/productive-bees-genesis` as the base repository and `main-neo/1.21.1` as the base branch. Write access to this repository is needed only to push here directly; it is not required for a pull request from your fork. If GitHub blocks this flow, please share the exact error and the compare-page URL in an issue.

You can also [open a blank issue](https://github.com/Ayoshiko/productive-bees-genesis/issues/new) and attach the translation JSON. If GitHub does not accept the JSON attachment, put it in a ZIP file.

---

## Table of Contents

- [About](#about)
- [Features](#features)
  - [Myriad Creations Bee](#myriad-creations-bee)
  - [MEK Centrifuge](#mek-centrifuge)
  - [MEK Apiary](#mek-apiary)
  - [Direct Centrifuge Connection](#direct-centrifuge-connection)
  - [PB Upgrade System](#pb-upgrade-system)
  - [AE2 Integration](#ae2-integration)
  - [Bee Filter UI](#bee-filter-ui)
  - [KubeJS Integration](#kubejs-integration)
- [Required Dependencies](#required-dependencies)
- [Compatible Mods](#compatible-mods)
- [Configuration](#configuration)
- [Usage](#usage)
- [Building](#building)
- [Architecture](#architecture)
- [Support](#support)
- [License](#license)
- [Acknowledgments](#acknowledgments)

## About

**Productive Bees Genesis** is a NeoForge addon that bridges **Productive Bees** and **Mekanism**. It provides:

- **Myriad Creations Bee** — a special bee whose honeycomb randomly produces honeycombs from other resource bees via a configurable filter.
- **MEK Centrifuge** — a Mekanism-style centrifuge that processes Productive Bees honeycombs and honeycomb blocks, and also supports Energized Smelter recipes.
- **MEK Apiary** — an electrified apiary with built-in simulation, housing multiple bees working simultaneously. Its internal feeder slots hold various flowers bees need for pollination without consuming them.
- Deep **AE2 integration** — both centrifuges and apiaries can join AE network connections, supporting AE default channel transmission. Outputs can be pushed directly into the ME network, with AE output toggles for items and fluids in the Mekanism config screen. Supports using FE energy stored in the linked ME network, while also retaining AE network native energy. Energy priority is configurable, energy extraction keeps a 5% network reserve so machines can never brown out the ME network, and AE2 native energy extraction can be disabled per machine when Applied Flux is installed. The centrifuge can also pull honeycombs from the linked ME network for processing, with pulling behavior configurable.
- Preserves Mekanism's native GUI, side configuration, auto-ejection config, upgrade buttons, safety system, redstone control, and config-tool interaction.
- Jade support for displaying machine AE online status, internal items, fluids, energy, etc.
- Fully configurable Myriad Creations Bee honeycomb conversion filter UI — supports blacklist/whitelist, search, sort, drag-and-drop, clipboard import/export.
- **KubeJS** support for dynamic bee recipe registration at runtime.

For the full list of changes, see the [CHANGELOG](CHANGELOG.md).

## Features

### Myriad Creations Bee

| Capability | Description |
| --- | --- |
| Rainbow gradient | 8-second color cycle with soft, low-saturation colors |
| Rainbow particles | Optional particle effects |
| Glow effect | Optional glow halo |
| Random honeycomb | Produces a random honeycomb from any resource bee in the pack |
| Filter list | Blacklist/whitelist mode with in-game editor |
| Fully configurable | All PB datapack attributes (colors, pollination, breeding, environment, etc.) |
| Acquisition | Fishing, breeding, nest spawning, or bee conversion — all configurable |
| Disable switch | When disabled, only MEK centrifuge and apiary features remain |

### MEK Centrifuge

An industrialized Mekanism-style centrifuge that extends Mekanism's native electric machine base class, fully preserving its GUI style, side configuration, upgrade system, safety interlocks, and redstone control, while deeply optimizing the honeycomb and honeycomb block processing pipeline.

#### Core Capabilities

- **Multi-recipe support**: Processes Productive Bees honeycombs and honeycomb blocks, and also supports Energized Smelter recipes — smelter-recipe compatibility is a per-tile toggle in the machine GUI (off by default), gated by a global server-config switch.
- **Factory parallel processing**: Base variant processes a single recipe; factory variants provide multi-process parallel processing (2–18 processes) scaling with tier.
- **Output safety**: Identical output stacks are auto-merged; processing pauses when output slots are full to avoid wasting energy.
- **Ejection optimization**: All variants use a configurable four-state ejection policy (active/idle/blocked/busy) with fine-grained controls like skip-unchanged-ticks, max-speed mode, and busy cooldown.
- **Fluid auto-eject**: Default 16384 mB/tick, overriding Mekanism's native 1024 mB/tick.
- **Multi-fluid tank mode**: Configurable dynamic slot allocation per fluid type, preventing different honey types from mixing.
- **AE2 integration**: Acts as an AE2 grid node — direct item/fluid output, comb and SMELTING input pulling, and ME-network energy input (5-tier priority).
- **Jade tooltip**: Shows AE2 network status, internal items/fluids/energy, etc.

#### Centrifuge Tiers (17 factory tiers + 1 base variant)

| Tier family | Tier names | Parallel processes | Input slots | Output slots |
| --- | --- | --- | --- | --- |
| Base | MEK Centrifuge | 1 | 1 | 3 |
| Mekanism (4 tiers) | Basic / Advanced / Elite / Ultimate | 3 / 5 / 7 / 9 | 3 / 5 / 7 / 9 | 9 / 15 / 21 / 27 |
| Mekanism Extras (4 tiers) | Absolute / Supreme / Cosmic / Infinite | 11 / 13 / 15 / 17 | 11 / 13 / 15 / 17 | 33 / 39 / 45 / 51 |
| Evolved Mekanism (5 tiers) | Overclocked / Quantum / Dense / Multiversal / Creative | 11 / 13 / 15 / 17 / 19 | 11 / 13 / 15 / 17 / 19 | 33 / 39 / 45 / 51 / 57 |
| Evolved Mekanism Extras (4 tiers) | Absolute Overclocked / Supreme Quantum / Cosmic Dense / Infinite Multiversal | 12 / 14 / 16 / 18 | 12 / 14 / 16 / 18 | 36 / 42 / 48 / 54 |

#### MEK Expansion Mod Support

Covers four Mekanism-family addon mods, with factory tiers mapped one-to-one:
- **Mekanism**: Basic / Advanced / Elite / Ultimate
- **Mekanism Extras**: Absolute / Supreme / Cosmic / Infinite
- **Evolved Mekanism**: Overclocked / Quantum / Dense / Multiversal / Creative
- **Evolved Mekanism Extras**: Absolute Overclocked / Supreme Quantum / Cosmic Dense / Infinite Multiversal

#### Upgrade Support

The centrifuge supports three independent upgrade systems that can coexist:
- **Mekanism native upgrades**: Speed (SPEED), Energy (ENERGY), Muffling (MUFFLING)
- **Mekanism Extras upgrades**: Stack (STACK, 2^N parallel), Creative (CREATIVE, infinite energy) — only when MEKExtras is installed
- **Mekanism Empowered upgrades**: Empowered Speed, Empowered Energy, IO Capacity, Auto Inserter, Fast Item Insert, Fast Item Eject — only when MekanismEmpowered is installed
- **PB Upgrade System**: see [PB Upgrade System](#pb-upgrade-system) section

The apiary supports the same upgrade families, with one difference: **MEKExtras CREATIVE is accepted by every apiary tier** (infinite energy, produces every tick — the STACK upgrade stays excluded), in addition to the Mekanism / Mekanism Empowered upgrade cards and the PB upgrade cards below.

### MEK Apiary

An electrified bee production system built on the **Mekanism** universal machine framework. By extending Mekanism's electric machine base class, the apiary fully reuses Mekanism's energy storage, side configuration, upgrade cards, safety interlocks, and GUI framework — migrating Productive Bees' vanilla hive logic into a MEK industrialized pipeline.

#### Apiary Tiers (17 factory tiers + 1 base variant)

| Tier family | Tier names | Bee slots | Output slots | Fluid tank capacity |
| --- | --- | --- | --- | --- |
| Base | MEK Apiary | 3 | 18 (9 per page, 2 pages) | 256,000 mB |
| Mekanism (4 tiers) | Basic / Advanced / Elite / Ultimate | 5–20 | 30–60 (2 pages) | 256K–1024K mB |
| Mekanism Extras (4 tiers) | Absolute / Supreme / Cosmic / Infinite | 26–42 | 60–84 (2 pages) | 1280K–2048K mB |
| Evolved Mekanism (5 tiers) | Overclocked / Quantum / Dense / Multiversal / Creative | 26–45 | 60–90 (2 pages) | 1280K–2304K mB |
| Evolved Mekanism Extras (4 tiers) | Absolute Overclocked / Supreme Quantum / Cosmic Dense / Infinite Multiversal | 45–60 | 78–102 (2 pages) | 2304K–3072K mB |

#### Apiary Output Paging and Throughput

Every apiary level uses two output pages. A bee can contribute up to six distinct item/component combinations, so the theoretical distinct-output bound is `6 × bee slots` (18 for the base apiary and up to 360 for the 60-bee tier). One page remains a centered 3-row matrix; the second page doubles physical capacity without widening the GUI. Cosmic Dense and Infinite Multiversal keep their existing 3×13 and 3×14 page widths.

The page is a client/container view only. AE2, Mekanism ejectors, adjacent centrifuges, and external item handlers always enumerate the complete physical slot list in stable index order. Output distribution first merges equal item/component keys, then targets indexed matching slots and a reusable empty-slot queue. If extraction is slower than production, leftovers are aggregated by the same key in a persistent FIFO buffer (512 groups) and retried with backoff, preserving output instead of dropping it during normal mixed-bee bursts.

#### Core Features

- **Slot layout**: bee slots / output slots / cage slots (bidirectional transfer) / energy slot / honey fluid tank / PB upgrade slots.
- **Feeder system**: dedicated window managing flower items. Base variant uses a fixed 3×3 = 9-slot grid; factory variants use a dynamic layout scaling with bee slot count.
- **PB upgrade system**: self-developed upgrade cards — productivity (α/β/γ/Ω), time (I/II), gene sampler, honeycomb block, byproduct destruction, plus the built-in simulation upgrade; see [PB Upgrade System](#pb-upgrade-system) section.
- **Sampling bees without native products**: adult Kamikaz bees and other bees without a comb recipe can still complete gene sampling in a mechanical apiary. Valid flowers, production costs and output capacity still apply; sampling alone does not create fallback honey.
- **AE2 integration**: items and fluids can push into the ME network, while machine energy can be refilled from AppliedFlux-stored FE or AE2 native power with configurable priority.
- **Direct ejection**: when a centrifuge is adjacent to the apiary, the apiary bypasses the MEK Ejector throttling and pushes honeycombs straight into the centrifuge's input slot — see [Direct Centrifuge Connection](#direct-centrifuge-connection).
- **Centrifuge priority**: a per-tile toggle that keeps honeycombs out of the AE network and routes them to adjacent centrifuges — produced honeycombs take a fast path straight into centrifuge input slots without passing through the apiary's output slots, and buffered honeycombs are never FIFO-evicted (overflow returns to the AE network first, FIFO only as the last resort).
- **Jade tooltip**: shows bee count, production progress, and AE2 network status.
- **Production diagnostics**: bee slots and Jade distinguish pending honey, generated outputs, a previous bee's paid cycles, resource accounts, full buffers and unresolved outcomes. Operators can use `/pbgoutput <X> <Y> <Z> status` to inspect paid cycles, frozen outputs, honey, buffers and each slot. Unknown deliveries remain in custody without automatic compensation. Resource ME initialization failures pause the affected resource output while ordinary output continues.
- **GUI tabs**: Sorting / Feeder / PB Upgrades / Multi-fluid tank — four customizable tabs with persisted window positions.
- **Performance**: throttled GUI energy-bar sync (every 5 game ticks instead of every tick), batched energy deduction under tick acceleration, and cached per-slot NBT serialization reduce repeated work. Full bee slots skip spawn-egg entity creation and filled-cage NBT copies; empty cages can still remove bees, and loading resumes as soon as a slot becomes available.

### Direct Centrifuge Connection

The apiary and the centrifuge form an industrialized **"produce → process"** pipeline:

1. Bees produce honeycombs according to PB recipes; honey is injected into the apiary's fluid tank.
2. When an apiary's adjacent block is a centrifuge, the apiary **bypasses the MEK Ejector throttling** and transfers honeycombs directly into the centrifuge's input slot — with centrifuge priority enabled, fresh honeycomb output is written straight into centrifuge inputs without even passing through the apiary's output slots.
3. The centrifuge processes the honeycombs and outputs the bee products.

This short-circuit eliminates the throughput bottleneck introduced by the MEK Ejector's per-tick limits and busy/block cooldowns, allowing full-rate industrial production when machines are placed back-to-back. Blocked centrifuge inputs back off over wall-clock time (250ms→2s) while fresh output stays zero-delay, and the retry behavior stays correct under tick-acceleration mods.

### PB Upgrade System

The mod's self-developed **PB Upgrade System** is independent from Mekanism's native upgrade system. It uses Mekanism's upgrade card installation mechanism, but all effect calculations are handled by this mod. The system applies to both the **MEK Centrifuge** (productivity, time, stability, byproduct destruction) and the **MEK Apiary** (productivity, time, gene sampler, honeycomb block, byproduct destruction, plus the built-in simulation upgrade), and upgrades can coexist.

#### Balance Presets

A server-side **global balance profile** governs how the upgrade system behaves. In the in-game config screen, clicking the profile entry opens a selector list showing every profile with its description:

| Profile | Tier mixing | Centrifuge productivity | PB upgrade cap | Stack upgrade cap |
| --- | --- | --- | --- | --- |
| **Basic** (default) | Productivity α/β/γ/Ω mutually exclusive; Time I/II mutually exclusive | Adds parallelism only (no per-batch output amplification) | 4 | 8 |
| **Paradox Infinity** | Tiers can be mixed freely | Adds output and parallelism | 8+ | 16+ |
| **Custom** | Uses the individual balance rules below | Configurable | Configurable | Configurable |

- Named profiles clamp the configured caps: Basic takes the smaller of the configured value and 4 (stack: 8); Paradox Infinity takes the larger of the configured value and 8 (stack: 16).
- Switching profiles never removes or normalizes upgrades already installed in machines — the tier-exclusivity rules only gate new installs.
- Switching from Basic or Paradox Infinity to Custom inherits the currently effective values; legacy configs from before the balance system are migrated to Custom with the previous behavior preserved.
- Basic stays close to vanilla Mekanism and Productive Bees pacing; Paradox Infinity unlocks the full power of the Myriad Creations Bee.

#### Upgrade Types

| Upgrade type | Identifier | Effect | Default limit (Basic) | Applies to |
| --- | --- | --- | --- | --- |
| Productivity α | PRODUCTIVITY | Output multiplier +1.2× (per card) | 4 (8+ in Paradox Infinity) | Centrifuge + Apiary |
| Productivity β | PRODUCTIVITY_2 | Output multiplier +1.5× (per card) | 4 (8+ in Paradox Infinity) | Centrifuge + Apiary |
| Productivity γ | PRODUCTIVITY_3 | Output multiplier +2.0× (per card) | 4 (8+ in Paradox Infinity) | Centrifuge + Apiary |
| Productivity Ω | PRODUCTIVITY_4 | Output multiplier +2.6× (per card), also enables the honeycomb-block effect | 4 (8+ in Paradox Infinity) | Centrifuge + Apiary |
| Time I | TIME | Processing time reduction (1× weight per card) | 4 (8+ in Paradox Infinity) | Centrifuge + Apiary |
| Time II | TIME_2 | Processing time reduction (2× weight per card) | 4 (8+ in Paradox Infinity) | Centrifuge + Apiary |
| Gene Sampler | GENE_SAMPLER | Gene sampling upgrade | 4 (configurable up to 20) | Apiary only |
| Honeycomb Block | BLOCK | Converts comb output into honeycomb-block form | 1 | Apiary only |
| Stability | STABILITY | +15% chance per card for non-guaranteed centrifuge outputs | 7 | Centrifuge only |
| Byproduct Destruction | USELESS_BYPRODUCT | Discards honey and the optional pollen-ball byproduct | 1 | Centrifuge + Apiary |
| Simulation | SIMULATION | Built-in simulation production (does not occupy a slot) | — | Apiary only |

> All bonus values shown in the upgrade GUI tooltips (productivity factors, time bonus, stability chance) are read at runtime from the Productive Bees config file, so they always reflect your actual PB configuration.
>
> Under the default Basic preset, centrifuge productivity upgrades only add parallelism without amplifying per-batch output, and mixing productivity or time tiers on one machine is blocked — switch to Paradox Infinity or Custom to unlock those.

#### Effect Calculation

Unlike Productive Bees' native additive model, this mod's PB upgrades use a **weighted additive** model — more cards mean stronger effects:

**Productivity upgrades** (shared by centrifuge and apiary):
- Total output multiplier = `1 + Σ(factor × count)`, where factors are 1.2 / 1.5 / 2.0 / 2.6
- Example: 4× Productivity α + 2× Productivity γ = `1 + (1.2×4 + 2.0×2) = 1 + 8.8 = 9.8×` output (mixing tiers like this requires tier mixing to be allowed — Paradox Infinity, or Custom with exclusivity off)
- The apiary applies an extra 1.22 compensation factor (to balance output pacing between apiary and centrifuge); the centrifuge has no compensation factor
- On the centrifuge, the per-batch output amplification only applies when the balance profile enables it (Paradox Infinity, or Custom with "centrifuge productivity affects output" on); under Basic, centrifuge productivity upgrades add parallelism only

**Time upgrades** (shared by centrifuge and apiary):
- Time multiplier = `1 / (1 + 0.15 × (Time I count + Time II count × 2))`
- Time II contributes 2× weight per card, making it stronger
- Example: 2× Time I + 1× Time II = `1 / (1 + 0.15 × (2 + 1×2)) = 1 / 1.6 = 0.625`, i.e. processing time reduced to 62.5% (again, mixing Time I and Time II requires tier mixing to be allowed)

#### Per-Type Install Limits

Different upgrade types have different default install limits; all limits are adjustable in the server config and further clamped by the [balance profile](#balance-presets):
- **Productivity / Time upgrades**: 4 cards under the default Basic preset (8+ under Paradox Infinity; configurable up to 64 under Custom) — shared by centrifuge and apiary
- **Simulation upgrade**: default 8 cards (apiary only)
- **Gene Sampler upgrade**: default 4 cards, configurable up to 20 (apiary only)
- **Honeycomb Block upgrade**: fixed 1 card (apiary only)
- **Stability upgrade**: default 7 cards (centrifuge only)
- **Byproduct Destruction upgrade**: fixed 1 card (functional, centrifuge + apiary)

#### Relationship with Mekanism Native Upgrades

The PB Upgrade System is fully independent from Mekanism native upgrades (SPEED / ENERGY / MUFFLING) and can be installed simultaneously:
- **Mekanism native upgrades**: effects handled by Mekanism itself (speed boost, energy capacity, muffling)
- **PB Upgrade System**: effects handled by this mod (output multiplier, processing time, gene sampling, etc.)
- The two systems do not interfere; players can freely combine them

### AE2 Integration

Both centrifuges and apiaries can act as **AE2 grid nodes** connecting to the ME network:

- Connect directly with AE2 smart cables and adjacent machines.
- Auto-discovered by addon-mod cables (ExtendedAE, AdvancedAE, ae2cs, ae2lt, Glodium, AppliedFlux).
- **Direct ME output**: push output slot items into the ME network, bypassing external logistics. Toggleable in the Mekanism config screen.
- **Direct fluid output**: honey and centrifuge fluids use the same network-aware batching, per-key backoff and bounded insert-cost budgets as item output.
- **ME energy input**: drain FE stored in the ME network to power the machine — the injection target fills the internal energy container (no more near-empty energy bars), and a single extraction takes at most 95% of the network stock so several machines filling up at once can never power down the shared network. Supports 5-tier energy priority:
  1. Local FE cache
  2. External direct supply (Mekanism config component + energy slot)
  3. ME network stored FE (AppliedFlux)
  4. Other energy (handled by Mekanism parent)
  5. AE2 native network energy (converted to FE)
- **AE2 native energy toggle** (`aeNativeEnergyInputEnabled`, shown only when Applied Flux is installed): disable it to draw energy only from AppliedFlux-stored FE, preventing excessive AE drain from powering down the ME network when FE runs low.
- **Input pulling** (centrifuge only): actively pulls fuzzy-matched combs, precise comb/block entries, or ordinary Mekanism SMELTING inputs from the connected ME network.
- **Pull filters**: disabled / blacklist / whitelist modes, precise matching, NBT-ignore mode, per-entry pull amounts, per-entry or global unlimited pulling, and machine-wide or per-entry network reserve floors. Exact whitelists are cursor-rotated so large lists do not starve later entries.
- **Removal persistence**: mining and replacing a centrifuge preserves its AE2 pull filters and toggles, as do wrench removal, crafting upgrades and factory installers. Placement by the original owner can safely reclaim the same detached resource account. Conflicting references and unknown requests stay paused; unstarted invalid resource inputs are retained for return when the network recovers.
- **SMELTING tag gate**: ordinary smelting candidates can be filtered with a separate whitelist/blacklist expression supporting item ids, tags, `&|^!()` operators, and `*` wildcards.
- **Transaction safety**: extracted leftovers use a bounded, persistent per-machine pending buffer instead of world drops. Item output uses a persistent reserve-commit-confirm ledger, so accepted network inserts are only finalized after the source slot is safely decremented.
- **Slow-network recovery**: machines keep their place while waiting for a shared network budget, including across pull cooldowns. Input returns share the input budget so output traffic cannot consume their recovery opportunities; waiting alone does not increase the failure backoff. Known leftovers can enter valid local slots while disconnected. Pull amounts, configured intervals and actual-rejection backoff remain unchanged. Centrifuge inventory listings reuse item keys while validating the full components.
- **Jade tooltip**: Shows AE2 network status (Offline / Booting / Missing Channel / Online).
- Node lifecycle is decoupled from the output toggle — closing output push does not disconnect the machine, allowing ME energy input to continue.

### Bee Filter UI

- In-game blacklist/whitelist editor with search, sort, and collapse.
- Drag-and-drop reorder, clipboard import/export (JSON array).
- Visual distinction between added and unadded bees.
- Numeric indices, dynamic product info for special bees.
- Sort mode and collapse state persist across sessions.

### Resource Bee Editor

Get the BeeBee Support Handbook in Creative mode and right-click to open the Resource Bee Editor. Create or edit a bee, its appearance, attributes, flowers, acquisition and multiple outputs. The handbook is usable only in Creative mode; applying on a dedicated server requires OP level 2 or above. Creative non-OP players can edit, save and export locally. Inventory and hotbar remain visible; dragging a material copies its complete components without consuming items.

Drag the title bar to move the editor, drag its edges or corners to resize, and use the pin to lock it. Resize cursors appear near an edge. Movement is bounded by the screen and bottom inventory; JEI adapts to the actual occupied areas. The designer adjusts its internal density at 3×/4× GUI scale, and bottom buttons grow with the window. The inventory stays outside with matching light/dark textures; fitting pages and common tools remain visible, with remaining actions under More. Ctrl+0 resets the layout, F2 switches the theme, Ctrl+F focuses search, and Ctrl+S saves the current draft. Ctrl+Z/Y and Edit History restore session snapshots with specific field changes; local drafts have their own library. Revision History records successful applications to the current world. Saving a draft or exporting locally does not create a published revision.

Acquisition and production first show recipe summaries; click an entry or Edit to open its full form. Item fields fit their labels, with directly editable amounts and percentage chances. Fixed quantities update both bounds and ranges keep separate bounds. Fluids show a localized name, icon and quantity, with editable amounts and processing times. Machine rows wrap their numeric controls when needed, and acquisition buttons arrange according to localized title widths. Adjacent short summaries share the remaining row space: result bees with chances, and fluids with amounts/times. Narrow windows wrap fields, and resizing retains focus on the same field. Search bees, materials, AE resources, machines, biomes, drafts and files with Chinese full Pinyin or initials; tone marks and lv/lü/lu are accepted. Name, ID, @mod, #tag and multi-term matching remain available, with page-number navigation. Add localized bee names beyond Chinese and English. The JSON editor provides syntax colors, line numbers, formatting and undo, plus Ctrl+F Find, Ctrl+H Replace, F3/Shift+F3 matches, Ctrl+G Go to Line, case sensitivity and Replace All. Tab/Shift+Tab indent, Ctrl+D duplicates a line/selection, Ctrl+Shift+K deletes lines and Ctrl+Shift+F formats. Line/column/selection status, match and bracket highlights, optional bracket/indent assistance and delayed syntax validation help navigate errors. Read-only pages allow search and copying but cannot be replaced. Checks and export places validation first, followed by world application, local datapacks, portable collections and KubeJS export; generated files and secondary actions expand separately. AE resource pickers share one row, with full identity and components in an advanced group.

Under Acquisition → Machine recipes, choose an MI, MBD2 or MMR recipe type or machine discovered by the server, then edit inputs, bee egg outputs, amounts, probabilities and host costs. MI uses EU and duration; MBD2 keeps separate capabilities and per-tick settings; MMR keeps material amounts separate from canvas positions. Inputs, outputs and costs are grouped; source details, MBD2 content options and MMR canvas/position settings expand when needed. Missing hosts, machine definitions or incompatible versions block application while preserving drafts. The editor adds recipes to existing machines; the pack must provide their definitions.

Import machine recipes by dropping a JS or native recipe JSON file. The original is archived and only supported data calls are parsed; dynamic expressions and unknown calls show specific diagnostics. The source digest is checked again before import to detect external edits. Export machine script creates a separate KubeJS script and project copy locally, preserving the original script. Install the exported script in the target instance's `kubejs/server_scripts`, then reload.

Combined production flows configure matching outputs together and distinguish direct hive production from bee-specific comb processing. Individual recipes retain their original IDs and act separately; each entry describes its actual host and input. The preview supports horizontal and vertical dragging with yaw, pitch and sensitivity sliders, and refreshes entity dimensions after size changes. Configured languages appear first, and gene choices prefer PB's existing translations. Important status and errors stay in a fixed bar inside the panel.

Machine catalogs and editors prefer in-game names provided by the host while retaining the mod, version and ID. Change machine, keep settings preserves original parameters within the same host. Cross-host conversion retains representable items, fluids, amounts, probabilities and duration. Unknown fields, incompatible units and per-tick behavior block conversion while keeping the original recipe.

Export KubeJS author bundle creates a ZIP containing bee definitions and the project index in `kubejs/data`, localized names in `kubejs/assets`, and recipe scripts in `kubejs/server_scripts/bee_designer`. Extract it into the target pack root and restart the game; later recipe edits can be reloaded. The target instance still needs the required mods and machine definitions. Install either the datapack export or the KubeJS bundle for the same bee to avoid duplicates. The bundle can also be imported back into the designer.

Open settings from the title bar or Ctrl+, for modern dark, modern light, AE-style light/dark and separate custom dark/light palettes, density, internal scale, backdrop, animations, reduced motion, preview sensitivity, tooltip delay and automatic drafts. Edit colors or import/export theme JSON; invalid input retains the previous palette. Preferences are stored in the instance's `bee-designer/preferences.json`. Invalid settings retain the original file; closing settings without edits does not replace it with defaults. Shortcuts and dedicated-server installation instructions are included.

New preferences default to modern light and existing themes are retained. Page transitions move only the content slightly while the window, title and inventory remain steady; switches animate locally. Close, settings, pin and theme icons share a size, and the title-bar X handles pending edits before saving and exiting. Forms and material grids scroll continuously, JSON and material scrollbars drag directly, and read-only JSON can be formatted. PB nests and hives appear first only when choosing a nest-spawning ingredient. Ctrl+Z restores deleted biome selections; items show one complete tooltip. MI recipes with EU=0 can migrate to MMR while retaining representable materials, amounts, probabilities and duration.

Appearance groups colors/particles and size/rendering, with common groups expanded by default and optional folding. Size, nectar size, speed, attack and breeding amounts use compact numeric rows with range help in tooltips. Choose PB's normal or upside-down pose directly; translucency and glow layers remain in ordinary appearance. Unknown existing poses are retained, and selecting the current option leaves the draft unchanged. Secondary and third colors inherit the primary color when absent; particles default to black, matching PB. Myriad uses the same pose control. Choose existing PB models and matching textures in Appearance; preview rotation follows the mouse. Special models require GeckoLib, and a shape does not grant solitary or farmer AI. Publish → Visual overview edits appearance, trait switches, acquisition recipes, machine materials and output quantities or probabilities directly. Each section or machine has an Edit action for its complete form. Combined centrifuge duration updates both ordinary and extended recipes and survives reopening its production editor. Diagnostics scroll to and highlight the actual field; changing pages clears the previous marker. World bee eggs and entities receive names on apply and login, and existing world bees refresh after model changes.

The current bee autosaves locally every 60 seconds by default, with a configurable interval of 15 seconds to 10 minutes or an off switch. Browsing an existing bee or switching pages does not create a draft; actual edits, new projects and manual saving do. Continuous edits are saved at the interval; unconfirmed production nodes and raw JSON input can be restored and are cleared only after confirmation or explicit discard. Autosave does not apply to the world. The bee library highlights and lists unapplied drafts first; click to resume them. Drafts show localized names, bee eggs and edit dates with newest, oldest, name or ID sorting and search. Applying clears the marker, and editing again restores it.

**Portable bee collections:** enable Select bees in the library, select cards, and export the selection; local drafts can also be selected together. Portable ZIP collections transfer full projects between modpacks without a default shared directory. Enter the source modpack name and optionally its version, authors, HTTPS release link, custom changes and license notes. Existing attribution and resource origins remain in the project, the collection manifest and a readable CREDITS file. A local CurseForge or Modrinth manifest can fill declared fields; missing links or authors are not guessed. Collections do not include other mods’ textures, models, machine definitions or scripts, and do not apply to the world. Import shows attribution and separately identifies world-ID, local-draft and PB name-key conflicts. A different local draft is archived before replacement; preservation failure stops import. Name-key conflicts require a new copied identity. Copies update their own bee_exists conditions while keeping external references, and new imports drop disabled-recipe lists from the source pack. Imported content stays local until validation and Apply. Datapack and KubeJS formats also support multiple bees and reject conflicting recipes or name keys.

**Small windows and personalization:** navigation uses actual localized title widths, showing fitting pages directly and placing the rest under More; settings can prefer menus. The library combines search, New, Select and fitting draft/import actions, while very short windows offer search in the footer to preserve bee cards. Fields pair only when both labels and controls fit; short labels use less space, genes and ordinary enums use dropdowns, booleans use switches, workflow modes retain segmented controls, and narrow fields stack. Choose one column or prefer two, collapse secondary groups and custom color editing, or hide library help. Diagnostics expand their target and use a subtle row tint and the control’s own soft border. Import, copy and cancel remain in the footer. AE-style pixel materials are independent of palette, including AE dark and custom colors; switching light/dark preserves the family, and custom themes show a text contrast estimate. Feedback offers subtle presses or ripples, directional slide or local reveal, reduced motion and no animation. The skippable interactive first-use tour can be reopened in settings.

Copy bees from either the bee library or local drafts. The editor chooses a new ID that avoids registered bees, local drafts, PB name keys, and managed recipe IDs. Copies preserve traits, complete components, and unknown fields as an independent draft; the world changes only on apply. Catalog cards show their actual source: PB built-in, Genesis built-in, modpack content, another mod, or editor-created. Edited overrides and unapplied drafts have separate markers.

Delete an existing configurable bee through three confirmation steps, ending with its complete ID. Confirmation stages a local deletion draft; Apply to World retires new work and managed acquisition recipes. A previous local draft is retained in the recycle bin. World entities, housed bees, eggs, cages, combs, and products in inventories or AE2 keep their identities, counts, and components. Player inventories are not scanned or cleared. Existing combs remain processable, and paid work and pending outputs stay with their original machines for settlement. World deletion records persist with the save; restore the same bee from the recycle bin and apply again to reactivate it. Local archived drafts can also be restored without overwriting a new draft with the same ID; copy a retained draft to resolve a conflict. Keep the compatibility definitions and the mods providing the original items installed.

PB definitions and Myriad settings allow declared attack values from 0 to 2147483647. New Myriad configurations default to BeeBee's `2.147483647E9`; existing configuration values are retained. PB 13.14 parses JSON attack through float, which rounds BeeBee's internal value. Minecraft 1.21.1 caps its native attack attribute at 2048, and PB separately limits extra damage to 1000 in hardcore mode. The formal non-hardcore attack scenario measured both Myriad and BeeBee at 2048 damage; the declared value is not damage per hit. Invalid input identifies the field and range. Correct and retry a failed save without getting stuck on the close confirmation.

Project and machine-source imports include Browse files: select a local drive, enter or leave folders, search JSON/ZIP files (plus JS for machine sources), and select a file to fill its import path. Directory reads use a separate bounded background queue and can be canceled; errors retain edits. This browser does not write server paths. Configurable behavior includes nesting tags, block or entity-tag flowers, post-pollination processing, particles and existing traits. Entity flowers validate entity type tags. FarmerBee and SolitaryBee retain their independent hardcoded AI without an upstream data-editing entry.

Choose responsive or smooth page motion with 0–24 pixels of travel and 80–400 ms duration. The window, title and inventory remain steady; buttons, navigation and switches provide local press, ripple and sliding feedback. Preview and draft monitoring rebuild snapshots only after a declaration changes. Material catalogs build incrementally and retain at most eight completed queries; measured benefits apply to the tested workload.

The Bee Editor and all its pages use independent smooth text rendering with compact, renamed subsets derived from Noto Sans, Noto Sans SC and JetBrains Mono. The bundled fonts cover the current translations, 3,755 common first-level GB2312 Han characters, common Western alphabets and symbols; JSON uses the monospaced subset with Chinese fallback. Other characters first use available system SansSerif/Monospaced outlines, then Minecraft's native glyphs. The font files add about 1.05 MB compressed to the JAR; horizontal shaping and mark positioning are retained while unused vertical and decorative features are omitted. Settings offer Auto clear text, Distance field text and the current game font. Auto rasterizes glyphs at their device size and uses distance fields for continuous scaling. Width, wrapping, carets and selection share one layout; cursor movement, selection, length limits, pasting and deletion preserve grapheme clusters and surrogate pairs. Replacing a selection at the JSON length limit uses its freed space. Font-mode changes and resource reloads invalidate catalog width measurements and release old glyph caches. ModernUI and LDLib2 are not required, no runtime font download is performed, and the complete font licenses ship with the mod. Rare characters and emoji depend on the available system and game glyph coverage.

Modern themes separate window, card and control radii and shadows, group related fields into continuous cards, and give headings a distinct type size. Solid or soft glass panels keep the text area readable; the AE pixel theme remains available. Status badges retain unapplied-state information, ordinary successful saves use brief feedback, and errors, uncertain publishing and recovery stay visible. Material drop targets distinguish adding from replacing, while destructive actions, selected slots and focused inputs use their corresponding colors.

Each library card's More menu provides copy, delete and single-bee export; Myriad Creations opens its dedicated configuration page. Shift selects an anchored range, Ctrl-click toggles a bee, dragging draws a selection rectangle, Ctrl+A selects visible bees and Esc cancels. Ranges load missing catalog pages before committing, with a limit of 128 bees; selection does not open projects or create drafts. Opening a collection reads the current instance's available CurseForge or Modrinth manifest once and fills only blank fields. Optional links, permission and notes are collapsed initially.

The file browser keeps the path, search, drive and current-instance shortcuts visible and uses full-width file rows. Click to select, double-click or use the bottom button to open; Ctrl+L edits the path, Enter opens it and Alt+Up goes to its parent. AE2 themes use terminal gray-violet surfaces, pixel bevels, recessed slots and toolbar buttons. Installed AE2 resources provide the original button and icon states, with a drawn fallback when absent. Modern themes use rounded controls and brief selection feedback. Gentle, Responsive and Still presets combine with separate menu and selection animation switches; changing a flower type moves its indicator without moving the whole form.

PB fields now include third color, color cycling, size with nectar, the glow layer, attack effects and damage immunity. Breeding food accepts an item, #item tag or PB default, and block-tag flowers support inverse matching. Ordinary bees use PB's actual color and glow fields while preserving unknown source fields. PB item and block conversions are available under individual production recipes, including inputs, results, chance, pollination and actual block-state properties. Existing multi-bee conversions are read-only; copying creates a recipe owned by the new bee, and retiring one owner preserves the others. PB 13.14.0 bee_nbt_changer.matches always returns false, so NBT modification is not offered as a working template. PB Bottler bee-egg recipes support item/fluid inputs and complete bee outputs. Incubation editing remains unavailable because this version's serializer reuses one field for both materials and cannot round-trip them independently. Special global serializers and independent FarmerBee/SolitaryBee AI retain their support limits. PB does not implement passiveEffects in this version's definition reader.

The guided tour opens the library, appearance, attributes, acquisition, production, validation, export and generated-file pages. A thin rounded focus ring, numbered badge and short curved arrow identify each control. You can edit during the tour; finish or return through each subpage's own controls to keep changes. Alt+Left/Right changes steps and Esc exits. The tour does not create projects or apply world changes and respects reduced motion. Generated-file previews have previous/next actions and a quick file list.

Myriad's dedicated settings include damage immunities, attack effects, third color, nectar size, glow layers, render transforms and block/tag/fluid flowers. Clear the immunity list explicitly; a blank third color follows the primary color and nectar size 0 follows the base size. Acquisition opens a recipe-only project for additional breeding, conversion, nest spawning, fishing, bottling, crafting and supported machine recipes. These recipes can be validated, applied and exported, and survive Myriad's configuration processor during reload. Portable imports retain the destination pack's Myriad attributes; full Myriad settings remain managed by its dedicated page.

**Myriad comb conversion filter:** use Rules, Add bees and Preview to choose no filtering, exclusion or allow-only behavior, then select from actual server candidates. The draft shows permitted comb and comb-block type counts and identifies absent products, missing or retired bees and unavailable dependencies. Rules apply by bee ID to both forms; order is for organization and does not change random weights. Pinyin/@mod search, Shift ranges, dragging, selection counts, bulk removal, clipboard import/export, ID sorting and Ctrl+Z are supported. Missing entries remain until explicitly removed. Wide windows use two columns and narrow windows combine common tools. Save applies the server configuration. External changes offer Merge my changes for review and saving, or Load server version; failures and missing acknowledgements retain edits for reconciliation.

Subsequent bee reads in the same resource generation reuse a reverse recipe index. Item/block Pinyin and tags are generated only when required by search, and reload/shutdown invalidates or releases snapshots. Complete resource reloads pause new resource work and reads. A failed reload remains paused until a later successful reload, with explicit state reporting to avoid using a stale catalog.

The essence upgrade's automatic-crafting whitelist now accepts PB's wither-skull chip, `productivebees:wither_skull_chip`: exactly nine chips craft one `minecraft:wither_skeleton_skull`. Full component matching and remainders are preserved; insufficient space leaves the original items intact. Only the exact 9:1 recipe is admitted by this exception, without broadly enabling other block outputs or mixed recipes.

Removing or replacing bees keeps their completed paid cycles without permanently occupying the new bee slots. Fixed recipes settle automatically. Dynamic wood, stone, dye or amber products retain their original bee data in a separate store of at most 128 groups; restore the original feeder flowers to settle them. `/pbgoutput <x> <y> <z> status` identifies retained bees and cycle counts. Full stores, full outputs and unknown outcomes pause explicitly while preserving assets. Ordinary bees with no extended account no longer report an unbound attachment as a resource-storage failure.

Output headers offer Remove output and up/down controls. Right-click an output item, or focus it and press Delete, to remove its entire record. Drag the item or header handle onto another output in the same list to reorder; quantities, chance and complete components move together. Releasing outside a valid target or pressing Esc cancels the drag. These controls are shared by production editing, existing recipes and the visual overview; session history can undo committed changes.

The recycle bin can permanently delete a local archived draft after confirmation, including an unreadable archive. Discarding a pending deletion or cleanup removes its local draft and ends that edit without applying to the world. Clearing a retired world's recycle record requires three confirmations and Apply to World; it removes the restoration entry while retaining the bee identity and recipes needed by existing inventory items and combs. The bee stays disabled, and player inventories are preserved.

Open **Settings → File Locations**, or use File Locations from drafts, the recycle bin or publishing. Every folder shows its full path with Copy path and Open folder. Paths are relative to the **current game instance**, which may be a launcher-specific modpack folder:

| File type | Location within the current game instance |
|---|---|
| Bee drafts | `bee-designer/drafts/<namespace>/<bee>.json` |
| Local recycle bin | `bee-designer/drafts-archive/<UUID>.json` |
| Local datapack and name-pack exports | `bee-designer/productivebeesgenesis/bee_designer/exports/` |
| KubeJS author bundles | `bee-designer/kubejs-exports/` |
| Portable bee collections | `bee-designer/collections/` |
| Machine scripts and project copies | `bee-designer/machine-exports/` |
| Retained imported machine sources | `bee-designer/machine-sources/` |
| Exported custom themes | `bee-designer/themes/` |
| Editor preferences | `bee-designer/preferences.json` |

The most recent export has its own folder shortcut. In singleplayer, File Locations also opens the current world's export, datapack and revision folders. World Export saves to `<world>/productivebeesgenesis/bee_designer/exports/`; Apply installs datapacks under `<world>/datapacks/`. On a dedicated server, those files are on the server computer. Use **Local Export** or **KubeJS Export** to obtain packaging files directly on your computer, or ask the administrator to retrieve a world export.

### KubeJS Integration

The mod ships **KubeJS** script hooks for dynamic bee recipe registration at runtime, eliminating the need for static datapack authoring during pack development.

- Listen to the `MyriadBeeEvents.REGISTER` event from server scripts (`server_scripts`).
- Exposed helpers on the event object:
  - `addBreeding(...)` — register a breeding combination (parent1 + parent2 → offspring).
  - `addFishing(...)` — register a fishing acquisition (biome list or tag + chance).
  - `addConversion(...)` — register a bee conversion (item + source bee → target bee).
  - `addSpawning(...)` — register a nest spawning rule (nest type + biome tag).
  - `addCentrifuge(...)` — register a centrifuge recipe (optional custom fluid + processing time).
  - `addBeeProduce(...)` — register an advanced beehive production output.
  - `addMekData(...)` — register a Mekanism `mek_data` shaped crafting recipe.
- Recipes registered through KubeJS are injected into the vanilla `RecipeManager` JSON map during the `beforeRecipeLoading` phase and coexist with datapack recipes; they are picked up by the in-game recipe caches and the JEI viewer.

#### Extended resource bees

Create bees that produce items, combs, FE, chemicals, Source and other resources. Appearance, flowers, breeding food and acquisition use ordinary PB JSON. Genesis adds resource output recipes and flowers that match container contents.

**Extended bees support PB breeding, item conversion, nest spawning and fishing.** Reference their bee IDs in the same PB recipes. Use **Genesis mechanical apiaries, centrifuges and their factories** for extended outputs; PB's own machines handle ordinary PB outputs.

##### Download and install

For Minecraft **1.21.1**, NeoForge, Productive Bees **13.14** and Genesis **1.1.0**. Examples are optional: **they are excluded from the mod JAR and are not registered by installing the mod alone**.

| Download | Use |
|---|---|
| [Complete example bundle](https://github.com/Ayoshiko/productive-bees-genesis/releases/download/resource-bee-examples-1.1.0/resource-bees-all.zip) | Recommended: editable sources, installable packs, complete KubeJS script, annotated reference and a per-bee file index |
| [Runnable datapack](https://github.com/Ayoshiko/productive-bees-genesis/releases/download/resource-bee-examples-1.1.0/resource-bees-datapack.zip) | Put in your world's `datapacks` folder; no extraction needed |
| [English and Chinese names](https://github.com/Ayoshiko/productive-bees-genesis/releases/download/resource-bee-examples-1.1.0/resource-bees-resourcepack.zip) | Put in `resourcepacks` and enable under Options → Resource Packs |
| [Annotated reference](https://github.com/Ayoshiko/productive-bees-genesis/releases/download/resource-bee-examples-1.1.0/resource-bees-reference.zip) | Field-by-field explanations for reading |

**Annotated `.jsonc` files contain `//` comments and cannot run as game JSON. Renaming them is not enough.** Edit the plain `.json` files while referring to annotated copies. `INSTALL.txt` explains installation; `EXAMPLES.json` lists each bee's definition, flowers, acquisition materials and recipes.

Install the datapack on the server and enable the name pack on each client. Reopen the world after adding bees; use `/reload` for existing recipes and `F3+T` for names. Examples requiring missing optional mods are skipped.

##### Try a copper energy bee

This example follows the modpack's existing empty cage + honey treat + copper ingot recipe:

1. Craft the copper energy bee egg. **Use an empty cage**; the recipe does not protect a bee inside it.
2. Put the bee in a powered Genesis mechanical apiary and place a copper ingot in its feeding slot.
3. It produces FE directly, without centrifuging. The flower item is reusable.
4. Open Mekanism side configuration, select **ENERGY**, set an output face and attach an energy cable. Produced FE is separate from operating power.
5. Use JEI to inspect acquisition, flowers and outputs.

All 32 examples have convenient egg recipes. All 28 extended bees also have breeding and conversion recipes. Adjust materials, yields and available acquisition methods to your pack.

##### Make your own bee

In `example:copper_energy`, `example` is the namespace and `copper_energy` is the bee name. To use `mypack:amber_energy`, update file paths, every bee reference and the name keys together.

**1. Bee definition** — [copper_energy.json](examples/resource-bees/datapack/data/example/productivebees/copper_energy.json), inside `data/example/productivebees/`:

~~~json
{
  "primaryColor": "#C97852",
  "secondaryColor": "#82452F",
  "flowerItem": "minecraft:copper_ingot",
  "breedingItem": "minecraft:copper_ingot",
  "breedingItemCount": 1,
  "selfbreed": true,
  "createComb": false
}
~~~

Customize colors, the flower and breeding food. `flowerItem` belongs in the feeding slot; `breedingItem` is fed during breeding. They may differ. `selfbreed: true` allows same-type breeding; crossbreeding needs a recipe.

**2. Resource output** — [copper_energy_production.json](examples/resource-bees/datapack/data/example/recipe/copper_energy_production.json), inside `data/example/recipe/`. Use singular `recipe`:

~~~json
{
  "type": "productivebeesgenesis:resource_production",
  "bee": "example:copper_energy",
  "outputs": [
    { "kind": "energy", "energy_type": "FE", "amount": "1000", "chance": 1 }
  ]
}
~~~

Customize `bee`, the resource and its amount. `amount` is a quoted positive integer; `chance` is a number from 0 to 1. `1` is guaranteed and `0.25` means 25%. These are base amounts; genes and upgrades still apply.

**3. Acquisition** — Keep the methods you want:

| Method | Example | Fields to customize |
|---|---|---|
| Egg crafting | [acquisition/copper_energy.json](examples/resource-bees/datapack/data/example/recipe/acquisition/copper_energy.json) | `ingredients` and the egg component's `type` |
| Crossbreeding | [bee_breeding/copper_energy.json](examples/resource-bees/datapack/data/example/recipe/bee_breeding/copper_energy.json) | `parent1`, `parent2`, `offspring` |
| Item conversion | [bee_conversion/copper_energy.json](examples/resource-bees/datapack/data/example/recipe/bee_conversion/copper_energy.json) | `source`, `result`, `item`, `chance` |
| Nest spawning | [bee_spawning/copper_energy.json](examples/resource-bees/datapack/data/example/recipe/bee_spawning/copper_energy.json) | Nest `ingredient`, bee `results`, `biomes` |
| Fishing | [bee_fishing/container_water.json](examples/resource-bees/datapack/data/example/recipe/bee_fishing/container_water.json) | `bee`, `biomes`, `chance` |

For example, breed PB copper and redstone bees to obtain an extended bee:

~~~json
{
  "type": "productivebees:bee_breeding",
  "parent1": "productivebees:copper",
  "parent2": "productivebees:redstone",
  "offspring": "example:copper_energy"
}
~~~

Feed each parent its own breeding food. For conversion, use the specified item on the source bee. Conversion chance and production chance belong to separate recipes. Nest spawning and fishing use PB's usual mechanics.

Downloaded recipes include `neoforge:conditions`. Update bee IDs there when renaming, and `modid` when changing optional mods. If you reference an example parent, include its definition and acquisition recipes, or replace it with a bee already in your pack.

**4. Names** — Edit `assets/productivebees/lang/en_us.json` and `zh_cn.json` in the resource pack:

~~~json
{
  "entity.productivebees.copper_energy_bee": "Copper Energy Bee"
}
~~~

PB always uses `entity.productivebees.` followed by the bee path and `_bee`. For `mypack:amber_energy`, use `entity.productivebees.amber_energy_bee`; do not replace the key's `productivebees` namespace.

##### Full ordinary PB examples

[pb_copper.json](examples/resource-bees/datapack/data/example/productivebees/pb_copper.json) includes the supplied PB template's colors, initial genes, size, speed, attack, breeding and environmental settings. Separate bees demonstrate mutually exclusive flower choices.

| Customization | Fields and examples |
|---|---|
| Body, stripes, pollen | `primaryColor`, `secondaryColor`, `particleColor`, using `#RRGGBB` |
| Choose one PB flower | `pb_copper`: copper-block `flowerBlock`; `pb_redstone`: redstone `flowerItem`; `pb_water`: water `flowerFluid`; `pb_tag`: custom `flowerTag` |
| Initial genes | Weather tolerance, temper, behavior, endurance and productivity in `attributes`; see the annotated values |
| Size, speed, attack | `size`, `speed`, `attack` |
| Breeding food and same-type breeding | `breedingItem`, `breedingItemCount`, `selfbreed` |
| Water and fire protection | `waterproof`, `fireproof` |
| Custom comb | `createComb`; a hive output recipe is still required |
| Optional mod requirements | Bee `conditions`, recipe `neoforge:conditions` |

The ordinary PB route is bee → comb → items/fluids. All four `pb_` bees include `productivebees:advanced_beehive` and `productivebees:centrifuge` recipes. Copper demonstrates breeding, redstone conversion, the tag bee nest spawning, and water fishing plus a fluid output. **These same PB settings work on extended bees**, such as a nocturnal Source bee with custom breeding food.

`flowerTag` takes a tag ID without `#`; see both `tags/block/resource_flowers.json` and `tags/item/resource_flowers.json`. Choose only one of `flowerBlock`, `flowerItem`, `flowerFluid` and `flowerTag`.

##### Produce combs, then centrifuge resources

See [hydrogen.json](examples/resource-bees/datapack/data/example/productivebees/hydrogen.json), [hydrogen_honeycomb.json](examples/resource-bees/datapack/data/example/recipe/hydrogen_honeycomb.json) and [hydrogen_centrifuging.json](examples/resource-bees/datapack/data/example/recipe/hydrogen_centrifuging.json):

1. Set `createComb: true`. The hydrogen example uses an iron ingot as its flower.
2. Add a PB hive recipe producing `productivebees:configurable_honeycomb` with the `productivebees:bee_type` component set to `example:hydrogen`.
3. Add a Genesis resource centrifuging recipe:

~~~json
{
  "type": "productivebeesgenesis:resource_centrifuging",
  "bee": "example:hydrogen",
  "processing_time": 20,
  "outputs": [
    { "kind": "chemical", "id": "mekanism:hydrogen", "amount": "100", "chance": 1 }
  ]
}
~~~

`processing_time` is measured in ticks: 20 ticks is about one second, and the default is 200. Ordinary PB centrifuging uses `processingTime` instead. `createComb` enables the comb appearance; it does not create an output recipe.

`energy` produces FE directly, while `energy_comb` produces FE through combs. Adding both routes to one bee produces resources at both stages.

##### Choose an extended resource

Each example has a definition, acquisition and output recipes. Names ending in `_comb` use combs; hydrogen itself uses the comb route.

| Example | Flower | `kind` and resource fields | Required mod |
|---|---|---|---|
| `energy` / `copper_energy` | Redstone / copper ingot | `energy`, `energy_type: "FE"` | Genesis |
| `hydrogen` | Iron ingot | `chemical`, `id: "mekanism:hydrogen"` | Mekanism |
| `source` | Source gem | `source` | Ars Nouveau |
| `soul` | Soul sand | `soul` | Industrial Foregoing Souls |
| `air` | Pressure gauge | `air` | PneumaticCraft |
| `emc` | Alchemical coal | `emc` | ProjectE |
| `experience` | Bottle o' Enchanting | `experience`, measured in XP points | ExperienceLib |
| `lightning` / `lightning_ehv` | Lightning Collapse Matrix | `lightning`, `tier: "high_voltage"` / `"extreme_high_voltage"` | AE2 Lightning Tech |
| `ae_key` | Diamond | `ae_key`, `type`, `id`, complete `data` | AE2 and the resource provider |

The `ae_key` example produces one diamond with `type: "ae2:i"`. Other AE2 resources require their provider's actual key data and accepting storage; changing a display name does not define a resource.

`mixed` produces ordinary items and FE by keeping a PB hive recipe and a Genesis direct-output recipe. `mixed_essence` supplies small item outputs for the essence conversion upgrade; `mixed_tag` uses a flower tag. `combined` centrifuges items and FE together: set `include_pb_outputs: true` and keep the matching PB centrifuge recipe.

##### Use a filled container as a flower

For Genesis mechanical apiaries and factories, replace other flower fields with `flowerResource`:

~~~json
{
  "flowerResource": {
    "kind": "chemical",
    "id": "mekanism:hydrogen",
    "amount": "1000"
  }
}
~~~

This field fragment goes into a full bee definition. It requires at least 1000 mB of hydrogen in one tank, such as a filled Mekanism chemical tank. **Pollination consumes neither the container nor its contents.** Empty or wrong-resource tanks do not qualify.

- `container_water`: at least 1000 mB water, such as a water bucket; demonstrates water as a flower for FE.
- `container_hydrogen`: at least 1000 mB hydrogen.
- `container_energy`: at least 1000 FE in a charged energy container.
- `container_ae_key`: at least 16 diamonds in a real ME item storage cell; produces diamonds. Checking this flower does not require the apiary to be connected to ME.

`amount` is the minimum, defaulting to `"1"`. Counts from multiple containers or feeding slots are not added together. All four container examples include PB breeding and conversion recipes.

##### Register recipes with KubeJS

Copy the complete bundle's `kubejs` folder into your modpack's KubeJS directory:

- `data` contains bee definitions and flower tags.
- `assets` supplies English and Chinese names.
- `server_scripts/genesis_resource_bees.js` registers all acquisition and output recipes with `event.custom(...)`, matching the datapack.

**Choose either the datapack installation or the complete script.** Repository scripts for individual resource types are alternatives; do not install them alongside the complete script. PB attributes and `flowerResource` stay in bee JSON. When replacing a recipe, keep its ID so the old and new versions do not both run.

##### Export resources in game

In Mekanism side configuration, the **chemical tab is on the left, below ENERGY**. Open the **right-side extended resource tab**, then select a square Source, soul, air, experience or lightning icon. Only installed types appear. Choose output faces and enable auto-eject, or use supported pipes to pull.

FE uses the native ENERGY page: input charges operating power, output exports produced FE, and input/output supports both on one face. Air needs a connected, sealed pneumatic network.

ME output requires AE2, power, a channel, compatible storage and a resource bridge: Applied Mekanistics for chemicals, Applied Flux for FE, Ars Énergistique for Source, Soulplied Energistics for souls, Applied Pneumatics for air, Applied Experienced for XP, and AE2 Lightning Tech for lightning. EMC credits the machine owner's ProjectE account; with FTB Teams, the resource window can select an online teammate as recipient.

The resource panel shows production and pending output. Its icons cannot be taken into your inventory. If output stops, check faces, adjacent container space or ME storage.

##### Troubleshooting

- **Missing bee:** check required mods, datapack enablement and paths, then reopen the world.
- **Bee does not work:** inspect its JEI flower, use an enabled feeding slot and power the mechanical apiary.
- **Breeding/conversion fails:** check parent/source IDs, breeding food or conversion item, including IDs in loading conditions.
- **Combs but no resources:** use a Genesis mechanical centrifuge with a matching resource recipe.
- **Untranslated name:** enable the name pack, check `entity.productivebees.<bee_name>_bee`, then press `F3+T`.
- **Recipe error:** use plain JSON, correct commas and quotes, singular `recipe`, and quoted Genesis `amount`.

## Required Dependencies

| Mod | Version | Purpose |
| --- | --- | --- |
| [Minecraft](https://www.minecraft.net/) | 1.21.1 | Game version |
| [NeoForge](https://neoforged.net/) | 21.1.214+ | Mod loader |
| [Productive Bees](https://www.curseforge.com/minecraft/mc-mods/productive-bees) | 1.21.1-13.13.5+ (13.14.0 tested) | Bee system and honeycomb mechanics |
| [Mekanism](https://www.curseforge.com/minecraft/mc-mods/mekanism) | 1.21.1-10.7.14.79+ | Required for MEK centrifuge features |

> Productive Bees Genesis 1.0.10 supports Productive Bees 13.13.5 and 13.14.0. Versions 1.0.9 and earlier are not compatible with Productive Bees 13.14.0.

## Compatible Mods

These mods are **optional**. When present, the corresponding features activate automatically.

| Mod | Integration |
| --- | --- |
| [Mekanism Extras](https://www.curseforge.com/minecraft/mc-mods/mekanism-extras) | ME-tier factories (Absolute / Supreme / Cosmic / Infinite) |
| [Evolved Mekanism](https://www.curseforge.com/minecraft/mc-mods/evolved-mekanism) | EM-tier factories (Overclocked / Quantum / Dense / Multiversal / Creative) |
| [Evolved Mekanism Extras](https://www.curseforge.com/minecraft/mc-mods/evolved-mekanism-extras) | EME-tier factories (Absolute Overclocked / Supreme Quantum / Cosmic Dense / Infinite Multiversal) |
| [Mekanism Unleashed](https://www.curseforge.com/minecraft/mc-mods/mekanism-unleashed) | Extended upgrade limits |
| [Mekanism Empowered](https://www.curseforge.com/minecraft/mc-mods/mekanism-empowered) | Empowered Speed / Empowered Energy / IO Capacity / Auto Inserter / Fast Item Insert / Fast Item Eject upgrades (centrifuge 6 types, apiary 5 types) |
| [Applied Energistics 2](https://github.com/AppliedEnergistics/Applied-Energistics-2) | Cable connection, item/fluid output, centrifuge input pulling, and ME energy input |
| [AppliedFlux](https://www.curseforge.com/minecraft/mc-mods/appliedflux) | ME-network-stored FE as energy source for machines |
| Ars Nouveau 5.12.1+ | Source production and native relay output |
| Ars Énergistique 2.1.1-beta+ | ME Source storage output with AE2 and Ars Nouveau |
| [ExtendedAE](https://www.curseforge.com/minecraft/mc-mods/ex-pattern-provider) | Auto-discovered cable connection |
| [AdvancedAE](https://www.curseforge.com/minecraft/mc-mods/advanced-ae) | Auto-discovered cable connection |
| [Jade](https://www.curseforge.com/minecraft/mc-mods/jade) | AE2 network status tooltip + MEK apiary bee count / production progress |
| [Iris Shaders](https://www.curseforge.com/minecraft/mc-mods/irisshaders) | Shader compatibility for cosmic rendering |
| [Just Enough Items](https://www.curseforge.com/minecraft/mc-mods/jei) | Recipe viewing + recipe hiding when bee disabled |
| [KubeJS](https://www.curseforge.com/minecraft/mc-mods/kubejs) | Runtime bee recipe registration via `MyriadBeeEvents.REGISTER` |
| [Super Factory Manager](https://www.curseforge.com/minecraft/mc-mods/super-factory-manager) | Item, fluid, and energy automation through NeoForge capabilities |
| Just Dire Things Extras | Coalesced 256x/1024x machine acceleration without repeating the full pipeline per virtual tick |
| Building Gadgets 2 | Client-side block-entity render load-order compatibility |
| Mek Energistics | Guards installer and target resolution for this mod's AE-connected machines |

## Configuration

The mod ships a trilingual configuration system (English / Chinese / auto-detect) accessible through the in-game Mods → Config screen. Configuration is split into three files:

| File | Scope | Highlights |
| --- | --- | --- |
| Client config | Client | Bee filter UI, port color visualization, rainbow effects, custom window positions |
| Common config | Common | Myriad Creations Bee attributes (appearance, pollination, breeding, environment, acquisition, produce, advanced beehive) |
| Server config | Server | Bee type filtering, MEK centrifuge parameters, MEK apiary parameters |

### Client Configuration

Client config manages UI display and visual effects:

- **Bee Filter UI**: display options, collapse state, sort mode.
- **Port color visualization**: machine side config port colors.
- **Rainbow effects**: Myriad Creations Bee rainbow gradient, particle effects, glow toggle.
- **Custom window positions**: persisted positions for PB upgrade, AE input config, feeder windows; can be pinned independently of the main UI.

### Common Configuration

Common config manages Myriad Creations Bee core attributes, grouped into:

- **Appearance**: rainbow gradient cycle, particle effects (toggle, count), glow (toggle, color).
- **Pollination**: pollination chance, flower range.
- **Breeding**: breeding parents config, breeding toggle.
- **Environment**: active biomes, time range.
- **Acquisition**: fishing (toggle, chance, biomes), breeding, nest spawning (nest type, biomes), bee conversion (source bee, conversion item, chance).
- **Produce**: Myriad Creations honeycomb output config, honeycomb block conversion multiplier.
- **Advanced beehive**: simulation cooldown, NBT save interval, and other performance tuning.

### Server Configuration

Server config is the mod's core, managing machine behavior, split into three main parts:

#### Balance Rules

- **Global balance profile**: Basic / Paradox Infinity / Custom — see [Balance Presets](#balance-presets). Clicking the entry in the in-game config screen opens a selector list with each profile's description.
- **Productivity tiers exclusive** (Custom only): forbid mixing productivity α/β/γ/Ω on one machine.
- **Time tiers exclusive** (Custom only): forbid mixing Time I and Time II on one machine.
- **Centrifuge productivity affects output** (Custom only): when on, centrifuge productivity upgrades also amplify per-batch output; when off, they only add parallelism. PB's native parallelism is always preserved regardless.

#### MEK Centrifuge Configuration

Centrifuge config is grouped by function, with 21 options total:

**Basic parameters**:
- Energy per tick: energy cost per processing slot per tick (FE).
- Processing time: base processing time per operation (tick).
- Fluid tank capacity: base fluid tank capacity (mB); factory variants scale with parallelism.
- Fluid ejection rate: fluid ejection rate per tick for factory centrifuges (mB), range 1-10240, default 256.
- Honeycomb block multiplier: output multiplier when centrifuging honeycomb blocks.
- Multi-fluid tank mode: dynamically allocates independent slots per fluid type when enabled.
- Smelting compatibility master switch: allows centrifuges to process Energized Smelter (SMELTING) recipes, default on; when off, the per-tile GUI toggle is unusable. Each centrifuge's smelter compatibility is controlled by its own GUI toggle, default off.

**Ejection policy**:
- Eject delay: output slot auto-eject delay (tick), recommended 2 (0.1s).
- Active eject delay: eject delay when output slot still has items, default 1 (0.05s).
- Skip eject when output unchanged: reduces CPU overhead under high-speed acceleration.
- Skip ticks when unchanged: consecutive ticks to skip when output unchanged, default 1.
- Max speed ejection mode: skips throttling logic, keeps only blocked cooldown; requires sufficient target container space.
- Min eject interval: minimum interval between calls when output continuously changes, default 0 (disabled).
- Busy eject threshold: consecutive times output total fails to decrease before entering long cooldown, default 5.
- Busy eject cooldown: ticks to skip when busy but not blocked, default 40 (2s).
- Max ejects per tick: max eject calls per tick, default 64.
- Blocked eject threshold: consecutive times no item is ejected before entering cooldown.
- Blocked eject cooldown: ticks to skip during blocked cooldown, 0 to disable.

**IO limit**:
- Max extract per tick: max items external pipes/AE2 can extract per tick, 0 for unlimited.

**AE2 integration** (only registered when AE2 is loaded):
- Enable AE2 direct output: push output slot items to AE2 network, default on.
- Enable AE2 fluid output: push honey fluid to AE2 network, default on.
- Enable AE energy input: extract FE from ME network into local energy container, default on.
- Enable AE2 native energy input (only shown when Applied Flux is installed): when off, energy is drawn only from AppliedFlux-stored FE, protecting the ME network from brownouts.
- Enable AE2 input pulling: centrifuge actively pulls input items from ME network.
- Max items per pull: 1-16384; too high may increase CPU overhead.
- Pull trigger interval: game ticks; higher values reduce CPU overhead but slow response.
- AE2 input min pages: AE2 input filter window minimum pages (1-16).
- AE energy priority: prefer AppliedFlux or AE2 native energy.

**Output slot stack multiplier**: per-tier output slot stack multiplier across 17 factory tiers, default 1× to 4096×.

**Input slot stack multiplier**: honeycomb processing multiplier; input slot default is 1/4 of output slot.

**PB upgrade limits**: max install count for productivity, time, gene sampling, honeycomb block, stability upgrades — productivity/time limits are further clamped by the balance profile (4 under Basic, 8+ under Paradox Infinity).

**ME upgrade limits**: max stack upgrade count, 2^N parallel, only applies to this mod's centrifuge factories — clamped by the balance profile (8 under Basic, 16+ under Paradox Infinity).

**Fluid tank multiplier**: per-tier fluid tank capacity multiplier.

#### MEK Apiary Configuration

Apiary config mirrors the centrifuge structure and adds apiary-specific tuning:

**Basic parameters**:
- Apiary energy per tick: energy cost per processing slot per tick (FE), default 50.
- Apiary processing time: base processing time (tick), default 1200.
- Apiary fluid tank capacity: base variant fluid tank capacity (mB), default 256,000; factory variants are hardcoded per tier.

**Ejection policy**: mirrors the centrifuge's ejection policy, with "apiary" prefix.

**Output slot stack multiplier**: per-tier output slot stack multiplier across 17 factory tiers:
- Basic apiary (5 bee slots, 30 output slots, 15 per page) default 1×
- Advanced apiary (10 bee slots, 30 output slots, 15 per page) default 2×
- Elite apiary (15 bee slots, 30 output slots, 15 per page) default 4×
- Ultimate apiary (20 bee slots, 60 output slots, 30 per page) default 8×
- Mekanism Extras Absolute / Supreme / Cosmic / Infinite apiaries: 26 / 30 / 36 / 42 bee slots and 78 / 60 / 72 / 84 output slots, default 16× / 32× / 64× / 128×
- Evolved Mekanism Overclocked / Quantum / Dense / Multiversal / Creative apiaries: 26 / 30 / 36 / 42 / 45 bee slots and 78 / 60 / 72 / 84 / 90 output slots, default 16× / 32× / 64× / 128× / 256×
- Evolved Mekanism Extras Absolute Overclocked / Supreme Quantum / Cosmic Dense / Infinite Multiversal apiaries: 45 / 51 / 55 / 60 bee slots and 90 / 102 / 78 / 84 output slots, default 256× / 512× / 1024× / 4096×

**AE2 integration** (only registered when AE2 is loaded): AE2 output toggle + AppliedFlux priority switch + the AE2 native energy input toggle, mirroring the centrifuge's AE2 integration.

**PB upgrade limits**: PB upgrade card stack limits — productivity / time / gene sampling / honeycomb block / simulation.

#### Myriad Creations Bee Filter Configuration

- Filter mode: Disabled / Blocklist / Whitelist.
- Filtered bee types: list of filtered bee types (format: modID:beeType).

### Accessing Configuration

1. Open the Minecraft main menu.
2. Click **Mods**.
3. Find **Productive Bees Genesis**.
4. Click **Config**.

Changes take effect after restarting the game or running `/reload`.

## Usage

1. Install NeoForge and the required dependencies.
2. Place the mod jar in your `mods` folder.
3. Launch the game and obtain the **Myriad Creations Bee** through any configured acquisition method (fishing, breeding, nest spawning, or bee conversion). Raise and process Myriad Creations honeycombs and honeycomb blocks.
4. Process honeycombs or honeycomb blocks in the **MEK Centrifuge** or any of its factory variants.
5. Place bees in the **MEK Apiary** or any of its factory variants, put bee foraging materials in the feeder slots, and bees will produce honeycombs.
6. Connect the centrifuge/apiary to an AE2 network via smart cables to enable direct ME output and FE energy input.
7. Tune the **balance profile** (Basic / Paradox Infinity / Custom) in the server config to control upgrade tier mixing, centrifuge productivity behavior, and upgrade caps.
8. (Optional) Use a **KubeJS** server script listening on `MyriadBeeEvents.REGISTER` to dynamically register custom bee recipes at runtime.

Progress advancements guide the early route: obtaining the MEK centrifuge, processing your first items, the byproduct-destruction upgrade recipe, and the ultimate centrifuge.

## Building

```bash
git clone https://github.com/Ayoshiko/productive-bees-genesis.git
cd productive-bees-genesis
./gradlew build
```

The local build is `build/libs/productivebeesgenesis-1.2.1.jar`.

`./gradlew runClient --no-daemon` automatically loads the resource-bee examples through the development-only `devExamples` source set. Bees such as `example:energy`, `example:hydrogen` and `example:mixed` are available for testing; optional resource types still require their owning mods. The examples are not bundled in release jars, and `verifyReleaseArtifact` checks that boundary.

> Requires **Java 21** and internet access to download Mekanism, Productive Bees, and AE2 dependencies from Cursemaven / Modrinth Maven.
>
> **Development-only local jars (`libs/`)**: the repository does not ship the binary jars under `libs/`. Before compiling from a fresh clone, manually place:
> `productivebees-1.21.1-13.14.0.jar`, `productivelib-1.21.1-0.2.0.jar`, `Mekanism-1.21.1-10.7.19.85.jar`,
> `mekanism_extras-1.21.1-1.4.0.jar`, `Evolved Mekanism-1.21.1-1.2.1-fix3.jar`, `EvolvedMekanismExtras-1.21.1-1.2.1.jar`,
> `jdte-0.5.9-alpha1.jar`, `sponge-mixin.jar`, `jei-1.21.1-neoforge-19.36.0.360.jar`,
> `appliedenergistics2-1.21.1-19.2.17.jar`, `AppliedFlux-1.21-2.1.5-neoforge.jar`, `Jade-1.21.1-NeoForge-15.10.5.jar`,
> `kubejs-neoforge-2101.7.2-build.368.jar`, `rhino-2101.2.7-build.85.jar`.

## Architecture

### Package Layout

```
com.ayoshiko.productivebeesgenesis/
├── (root)              Main mod classes, comb event handlers, random comb selector
├── apiary/             MEK apiary system — blocks, tile entities, slot/feeder/upgrade
│                       handlers, GUI tabs, bee entity rendering, PB recipe context
│   └── client/         Apiary GUIs (sorting / feeder / PB upgrade tabs), bee renderer,
│                       bee name / tooltip overlays
├── capability/         Rate-limited item handler, inventory dirty debouncer
├── client/             Client event handlers, JEI/Jade plugins, cosmic render, GUI screens
│   ├── jei/             JEI recipe category for PB centrifuge
│   ├── jade/            Jade plugin — AE2 status + apiary bee/progress display
│   ├── render/cosmic/   Cosmic shader system, baked models, Iris compat
│   └── screen/          Configuration and MEK centrifuge GUIs + state
├── command/            OP-only runtime developer diagnostics command
├── compat/             Optional mod integrations
│   ├── kubejs/          KubeJS plugin — MyriadBeeEvents.REGISTER + recipe serializers
│   ├── mekanism_extras/ Mekanism Extras blocks, factories, menus and delegates
│   ├── emextras/        Evolved Mekanism Extras block / block-entity registration
│   └── mekenergistics/  Mek Energistics installer/target guards
├── config/             Client / Common / Server config, bilingual
├── datagen/            Block tags, recipes, loot tables, language provider
├── init/               DeferredRegister registrations
├── item/               Custom items
├── mek/                Mekanism centrifuge blocks, tile entities, recipe processing
│   └── ae2/            AE2 grid, input filters/puller, pending ownership, output ledger,
│                       item/fluid pushers, external storage and energy injection
├── menu/               Container menu definitions
├── mixin/              Mixin classes with MixinConfigPlugin conditional loader
│   ├── accessor/       Accessor mixins
│   ├── ae2/            Optional AE2 host adapters for every machine family
│   ├── beehive/        Beehive inventory debounce and cache mixins
│   ├── buildinggadgets/ Building Gadgets block-entity render compatibility
│   ├── client/         Client-side mixins (bee color, cosmic item renderer)
│   ├── iris/           Iris shader compat with IrisConfigPlugin
│   ├── jdte/           Coalesced acceleration adapters
│   ├── mek/            Mekanism centrifuge / factory / ejector mixins
│   ├── mekenergistics/ Mek Energistics compatibility guards
│   └── recipe/         Recipe serializer fallback mixins
├── network/            Validated/rate-limited GUI actions and filter/state synchronization
└── util/               BeeInfoHelper, RecipeCacheManager, CentrifugeRecipeIndex, etc.
```

### Key Abstractions

- **`AbstractCombEventHandler`** / **`MyriadCreationsEventHandler`**: Random honeycomb allocation via `RandomHoneycombSelector` (Fisher-Yates, Stars-and-Bars, even allocation).
- **`PbRecipeProcessor`**: PB recipe coordinator delegating to `PbRecipeFinder` (double-layer cache), `PbRecipeCompleter` (batch insertion), and `MyriadCreationsHandler`.
- **`FactoryPbContextDelegate`**: Composition class eliminating ~293 lines of duplicated PB recipe context logic across factory tile entities.
- **`TileEntityMekApiary`** / **`TileEntityMekApiaryFactory`**: MEK apiary tile entities extending Mekanism's electric machine base class; compose slot manager, PB upgrade handler, direct eject handler, cage handler, bee produce processor, and AE2 host adapter to migrate PB hive logic into the MEK industrial pipeline.
- **`ApiaryDirectEjectHandler`**: Short-circuit ejector that bypasses the MEK Ejector throttling when an adjacent centrifuge is detected.
- **`ApiaryTierMultiplierResolver`** + per-family delegates (`MEDelegate`, …): resolve per-tier bee slot count, output slot count, fluid tank capacity, and stack multiplier across the 17 factory tiers.
- **`MyriadBeeRegisterEventJS`** / **`MyriadBeeEvents`**: KubeJS event group + event object exposing `addBreeding` / `addFishing` / `addConversion` / `addSpawning` / `addCentrifuge` / `addBeeProduce` / `addMekData` recipe builders.
- **`AbstractBakedModelCosmic`**: Cosmic render pipeline (shader uniforms, mask sprites, Iris defer) for `BakedModelCosmic` / `BakedModelHell` / `BakedModelHalo`.
- **`Ae2GridNodeManager`** / **`Ae2InputPuller`** / **`Ae2InputFilter`**: AE2 node lifecycle plus fair, bounded comb/SMELTING discovery, filtering, reserve floors and pending-item ownership.
- **`Ae2OutputPusher`** / **`Ae2OutputLedger`** / **`Ae2FluidPusher`**: transactional item output and batched multi-fluid output with per-key backoff and insert-cost budgets.
- **`Ae2EnergyInjector`**: ME-network energy injection with 5-tier priority and a network reserve.
- **`MixinConfigPlugin`**: Conditional Mixin loader — skips ME/EME-specific mixins when those mods are absent.

### Thread Safety

- Static fields use `volatile` for cross-thread visibility.
- Concurrent collections: `ConcurrentHashMap`, `CopyOnWriteArrayList`.
- Atomic counters: `AtomicInteger`, `AtomicLong`.
- Holder pattern for thread-safe lazy initialization.
- `synchronized` blocks for compound operations.
- Server stop event clears static caches and unregisters JMX MBeans.

### Mixin Naming Convention

All Mixin methods and fields use the `productivebeesgenesis$` prefix (e.g., `productivebeesgenesis$onInit`).

## Support

If you encounter issues or have feature suggestions, please file them at [GitHub Issues](https://github.com/Ayoshiko/productive-bees-genesis/issues).

## License

This project is licensed under the **MIT License**. See the [LICENSE](LICENSE) file for details.

Third-party assets and code references are listed in [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md).

## Privacy & Community

### Privacy Statement

This mod has no telemetry, analytics, crash reporting or external data upload. World saves retain resource-account owner and EMC recipient UUIDs for ownership, permissions and credits. Resource windows synchronize the necessary recipient identity and gameplay state over Minecraft's native channel. Rate limiters keep player identity keys in memory. These data serve local gameplay and are not uploaded to external services.

### Age Rating & Health Reminder

This mod is suitable for all ages (content theme: beekeeping and industrial automation, no violence/blood/adult content).

### Community Guidelines

We are committed to providing a friendly and inclusive community. Harassment, discrimination, personal attacks, or hate speech of any kind will not be tolerated. Please report violations via [GitHub Issues](https://github.com/Ayoshiko/productive-bees-genesis/issues).

## Acknowledgments

- **gummyc_type2**, author of **beebeeblock-the-skyhive** — provided the original BeeBee Support Handbook book texture; this mod recolors it yellow.
- **Productive Bees** development team
- **Mekanism** development team
- **NeoForge** development team
- **Re:Avaritia** (Nova-Committee) — cosmic shader reference for the starfield texture
- **Applied Energistics 2** development team
- **Mek-Energistics** (beipuo) — AE2 integration reference
- **UselessMod** development team — partial optimization reference
- **AppliedFlux** — FE storage API in ME networks
- **KubeJS** — runtime script hook and event group API
- **Jade** — block tooltip component plugin API
