# Productive Bees Genesis

![Version](https://img.shields.io/badge/version-2.0.9-hotfix-blue?style=flat-square)
![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-green?style=flat-square)
![NeoForge](https://img.shields.io/badge/NeoForge-21.1.214+-orange?style=flat-square)
![License](https://img.shields.io/badge/license-MIT-blue?style=flat-square)
![Java](https://img.shields.io/badge/Java-21+-red?style=flat-square)

> An addon for **Productive Bees** and **Mekanism** that adds the **Myriad Creations Bee** (rainbow-gradient, honeycombs randomly transform into other resource bees' honeycombs), the **MEK Centrifuge**, and the **MEK Apiary** — machines that preserve Mekanism's native processing logic, GUI style, side configuration, and upgrade system, while deeply integrating **Applied Energistics 2** for output pushback, honeycomb pulling, and network-powered energy. The machines also implement Resource Bees' upgrade components via Mekanism's upgrade installation system, offering an efficient new way to raise bees and process honeycombs.

**Languages**: [English](README.md) · [中文](README_zh.md)

> ⚠️ This mod is under active development — expect bugs, crashes, and compatibility issues. Feedback is welcome.

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
- Deep **AE2 integration** — both centrifuges and apiaries can join AE network connections, supporting AE default channel transmission. Outputs can be pushed directly into the ME network, with AE output toggles for items and fluids in the Mekanism config screen. Supports using FE energy stored in the linked ME network, while also retaining AE network native energy. Energy priority is configurable. The centrifuge can also pull honeycombs from the linked ME network for processing, with pulling behavior configurable.
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

- **Multi-recipe support**: Processes Productive Bees honeycombs and honeycomb blocks, and also supports Energized Smelter recipes.
- **Factory parallel processing**: Base variant processes a single recipe; factory variants provide multi-process parallel processing (2–18 processes) scaling with tier.
- **Output safety**: Identical output stacks are auto-merged; processing pauses when output slots are full to avoid wasting energy.
- **Ejection optimization**: All variants use a configurable four-state ejection policy (active/idle/blocked/busy) with fine-grained controls like skip-unchanged-ticks, max-speed mode, and busy cooldown.
- **Fluid auto-eject**: Default 16384 mB/tick, overriding Mekanism's native 1024 mB/tick.
- **Multi-fluid tank mode**: Configurable dynamic slot allocation per fluid type, preventing different honey types from mixing.
- **AE2 integration**: Acts as an AE2 grid node — direct output push, honeycomb pulling, and ME-network energy input (5-tier priority).
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

### MEK Apiary

An electrified bee production system built on the **Mekanism** universal machine framework. By extending Mekanism's electric machine base class, the apiary fully reuses Mekanism's energy storage, side configuration, upgrade cards, safety interlocks, and GUI framework — migrating Productive Bees' vanilla hive logic into a MEK industrialized pipeline.

#### Apiary Tiers (17 factory tiers + 1 base variant)

| Tier family | Tier names | Bee slots | Output slots | Fluid tank capacity |
| --- | --- | --- | --- | --- |
| Base | MEK Apiary | 3 | 9 | 256,000 mB |
| Mekanism (4 tiers) | Basic / Advanced / Elite / Ultimate | 5–20 | 9–18 | 256K–1024K mB |
| Mekanism Extras (4 tiers) | Absolute / Supreme / Cosmic / Infinite | 26–42 | 21–30 | 1280K–2048K mB |
| Evolved Mekanism (5 tiers) | Overclocked / Quantum / Dense / Multiversal / Creative | 26–45 | 21–33 | 1280K–2304K mB |
| Evolved Mekanism Extras (4 tiers) | Absolute Overclocked / Supreme Quantum / Cosmic Dense / Infinite Multiversal | 45–60 | 33–42 | 2304K–3072K mB |

#### Core Features

- **Slot layout**: bee slots / output slots / cage slots (bidirectional transfer) / energy slot / honey fluid tank / PB upgrade slots.
- **Feeder system**: dedicated window managing flower items. Base variant uses a fixed 3×3 = 9-slot grid; factory variants use a dynamic layout scaling with bee slot count.
- **PB upgrade system**: 9 self-developed upgrade cards applicable to both centrifuge and apiary — see [PB Upgrade System](#pb-upgrade-system) section.
- **AE2 integration**: outputs (items / fluids / energy) push into the ME network; AppliedFlux priority switching supported.
- **Direct ejection**: when a centrifuge is adjacent to the apiary, the apiary bypasses the MEK Ejector throttling and pushes honeycombs straight into the centrifuge's input slot — see [Direct Centrifuge Connection](#direct-centrifuge-connection).
- **Jade tooltip**: shows bee count, production progress, and AE2 network status.
- **GUI tabs**: Sorting / Feeder / PB Upgrades / Multi-fluid tank — four customizable tabs with persisted window positions.

### Direct Centrifuge Connection

The apiary and the centrifuge form an industrialized **"produce → process"** pipeline:

1. Bees produce honeycombs according to PB recipes; honey is injected into the apiary's fluid tank.
2. When an apiary's adjacent block is a centrifuge, the apiary **bypasses the MEK Ejector throttling** and transfers honeycombs directly into the centrifuge's input slot.
3. The centrifuge processes the honeycombs and outputs the bee products.

This short-circuit eliminates the throughput bottleneck introduced by the MEK Ejector's per-tick limits and busy/block cooldowns, allowing full-rate industrial production when machines are placed back-to-back.

### PB Upgrade System

The mod's self-developed **PB Upgrade System** is independent from Mekanism's native upgrade system. It uses Mekanism's upgrade card installation mechanism, but all effect calculations are handled by this mod. The system applies to both the **MEK Centrifuge** (6 types) and the **MEK Apiary** (all 9 types), and upgrades can coexist.

#### Upgrade Types

| Upgrade type | Identifier | Effect | Default limit | Applies to |
| --- | --- | --- | --- | --- |
| Productivity α | PRODUCTIVITY | Output multiplier +1.2× (per card) | 8 | Centrifuge + Apiary |
| Productivity β | PRODUCTIVITY_2 | Output multiplier +1.5× (per card) | 8 | Centrifuge + Apiary |
| Productivity γ | PRODUCTIVITY_3 | Output multiplier +2.0× (per card) | 8 | Centrifuge + Apiary |
| Productivity Ω | PRODUCTIVITY_4 | Output multiplier +2.6× (per card) | 8 | Centrifuge + Apiary |
| Time I | TIME | Processing time reduction (1× weight per card) | 8 | Centrifuge + Apiary |
| Time II | TIME_2 | Processing time reduction (2× weight per card) | 8 | Centrifuge + Apiary |
| Gene Sampler | GENE_SAMPLER | Gene sampling upgrade | 4 | Apiary only |
| Honeycomb Block | BLOCK | Honeycomb block processing upgrade | 1 | Apiary only |
| Simulation | SIMULATION | Simulation upgrade | 8 | Apiary only |

#### Effect Calculation

Unlike Productive Bees' native additive model, this mod's PB upgrades use a **weighted additive** model — more cards mean stronger effects:

**Productivity upgrades** (shared by centrifuge and apiary):
- Total output multiplier = `1 + Σ(factor × count)`, where factors are 1.2 / 1.5 / 2.0 / 2.6
- Example: 4× Productivity α + 2× Productivity γ = `1 + (1.2×4 + 2.0×2) = 1 + 8.8 = 9.8×` output
- The apiary applies an extra 1.22 compensation factor (to balance output pacing between apiary and centrifuge); the centrifuge has no compensation factor

**Time upgrades** (shared by centrifuge and apiary):
- Time multiplier = `1 / (1 + 0.15 × (Time I count + Time II count × 2))`
- Time II contributes 2× weight per card, making it stronger
- Example: 2× Time I + 1× Time II = `1 / (1 + 0.15 × (2 + 1×2)) = 1 / 1.6 = 0.625`, i.e. processing time reduced to 62.5%

#### Per-Type Install Limits

Different upgrade types have different default install limits; all limits are adjustable in the server config:
- **Productivity / Time upgrades**: default 8 cards (shared by centrifuge and apiary, coexist, configurable)
- **Simulation upgrade**: default 8 cards (apiary only)
- **Gene Sampler upgrade**: default 4 cards (apiary only)
- **Honeycomb Block upgrade**: fixed 1 card (apiary only)

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
- **ME energy input**: drain FE stored in the ME network to power the machine. Supports 5-tier energy priority:
  1. Local FE cache
  2. External direct supply (Mekanism config component + energy slot)
  3. ME network stored FE (AppliedFlux)
  4. Other energy (handled by Mekanism parent)
  5. AE2 native network energy (converted to FE)
- **Honeycomb pulling** (centrifuge only): the centrifuge can actively pull honeycombs from the connected ME network for processing, with pulling behavior configurable.
- **Jade tooltip**: Shows AE2 network status (Offline / Booting / Missing Channel / Online).
- Node lifecycle is decoupled from the output toggle — closing output push does not disconnect the machine, allowing ME energy input to continue.

### Bee Filter UI

- In-game blacklist/whitelist editor with search, sort, and collapse.
- Drag-and-drop reorder, clipboard import/export (JSON array).
- Visual distinction between added and unadded bees.
- Numeric indices, dynamic product info for special bees.
- Sort mode and collapse state persist across sessions.

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

## Required Dependencies

| Mod | Version | Purpose |
| --- | --- | --- |
| [Minecraft](https://www.minecraft.net/) | 1.21.1 | Game version |
| [NeoForge](https://neoforged.net/) | 21.1.214+ | Mod loader |
| [Productive Bees](https://www.curseforge.com/minecraft/mc-mods/productive-bees) | 1.21.1-13.13.5+ | Bee system and honeycomb mechanics |
| [Mekanism](https://www.curseforge.com/minecraft/mc-mods/mekanism) | 1.21.1-10.7.14.79+ | Required for MEK centrifuge features |

## Compatible Mods

These mods are **optional**. When present, the corresponding features activate automatically.

| Mod | Integration |
| --- | --- |
| [Mekanism Extras](https://www.curseforge.com/minecraft/mc-mods/mekanism-extras) | ME-tier factories (Absolute / Supreme / Cosmic / Infinite) |
| [Evolved Mekanism](https://www.curseforge.com/minecraft/mc-mods/evolved-mekanism) | EM-tier factories (Overclocked / Quantum / Dense / Multiversal / Creative) |
| [Evolved Mekanism Extras](https://www.curseforge.com/minecraft/mc-mods/evolved-mekanism-extras) | EME-tier factories (Absolute Overclocked / Supreme Quantum / Cosmic Dense / Infinite Multiversal) |
| [Mekanism Unleashed](https://www.curseforge.com/minecraft/mc-mods/mekanism-unleashed) | Extended upgrade limits |
| [Mekanism Empowered](https://www.curseforge.com/minecraft/mc-mods/mekanism-empowered) | Empowered Speed / Empowered Energy / IO Capacity / Auto Inserter / Fast Item Insert / Fast Item Eject upgrades (centrifuge 6 types, apiary 5 types) |
| [Applied Energistics 2](https://github.com/AppliedEnergistics/Applied-Energistics-2) | Cable connection + ME network output + ME energy input |
| [AppliedFlux](https://www.curseforge.com/minecraft/mc-mods/appliedflux) | ME-network-stored FE as energy source for machines |
| [ExtendedAE](https://www.curseforge.com/minecraft/mc-mods/ex-pattern-provider) | Auto-discovered cable connection |
| [AdvancedAE](https://www.curseforge.com/minecraft/mc-mods/advanced-ae) | Auto-discovered cable connection |
| [Jade](https://www.curseforge.com/minecraft/mc-mods/jade) | AE2 network status tooltip + MEK apiary bee count / production progress |
| [Iris Shaders](https://www.curseforge.com/minecraft/mc-mods/irisshaders) | Shader compatibility for cosmic rendering |
| [Just Enough Items](https://www.curseforge.com/minecraft/mc-mods/jei) | Recipe viewing + recipe hiding when bee disabled |
| [KubeJS](https://www.curseforge.com/minecraft/mc-mods/kubejs) | Runtime bee recipe registration via `MyriadBeeEvents.REGISTER` |

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

Server config is the mod's core, managing machine behavior, split into two main modules:

#### MEK Centrifuge Configuration

Centrifuge config is grouped by function, with 21 options total:

**Basic parameters**:
- Energy per tick: energy cost per processing slot per tick (FE).
- Processing time: base processing time per operation (tick).
- Fluid tank capacity: base fluid tank capacity (mB); factory variants scale with parallelism.
- Fluid ejection rate: fluid ejection rate per tick for factory centrifuges (mB), range 1-10240, default 256.
- Honeycomb block multiplier: output multiplier when centrifuging honeycomb blocks.
- Multi-fluid tank mode: dynamically allocates independent slots per fluid type when enabled.

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
- Enable AE2 input pulling: centrifuge actively pulls input items from ME network.
- Max items per pull: 1-16384; too high may increase CPU overhead.
- Pull trigger interval: game ticks; higher values reduce CPU overhead but slow response.
- AE2 input min pages: AE2 input filter window minimum pages (1-16).
- AE energy priority: prefer AppliedFlux or AE2 native energy.

**Output slot stack multiplier**: per-tier output slot stack multiplier across 17 factory tiers, default 1× to 4096×.

**Input slot stack multiplier**: honeycomb processing multiplier; input slot default is 1/4 of output slot.

**PB upgrade limits**: max install count for productivity, time, gene sampling, honeycomb block upgrades.

**ME upgrade limits**: max stack upgrade count, 2^N parallel, only applies to this mod's centrifuge factories.

**Fluid tank multiplier**: per-tier fluid tank capacity multiplier.

#### MEK Apiary Configuration

Apiary config mirrors the centrifuge structure and adds apiary-specific tuning:

**Basic parameters**:
- Apiary energy per tick: energy cost per processing slot per tick (FE), default 50.
- Apiary processing time: base processing time (tick), default 1200.
- Apiary fluid tank capacity: base variant fluid tank capacity (mB), default 256,000; factory variants are hardcoded per tier.

**Ejection policy**: mirrors the centrifuge's ejection policy, with "apiary" prefix.

**Output slot stack multiplier**: per-tier output slot stack multiplier across 17 factory tiers:
- Basic apiary (3 processes, 9 output slots) default 1×
- Advanced apiary (5 processes, 12 output slots) default 2×
- Elite apiary (7 processes, 15 output slots) default 4×
- Ultimate apiary (9 processes, 18 output slots) default 8×
- Mekanism Extras Absolute apiary (11 processes, 21 output slots) default 16×
- Mekanism Extras Supreme apiary (13 processes, 24 output slots) default 32×
- Mekanism Extras Cosmic apiary (15 processes, 27 output slots) default 64×
- Mekanism Extras Infinite apiary (17 processes, 30 output slots) default 128×
- Evolved Mekanism Overclocked apiary (11 processes, 21 output slots) default 16×
- Evolved Mekanism Quantum apiary (13 processes, 24 output slots) default 32×
- Evolved Mekanism Dense apiary (15 processes, 27 output slots) default 64×
- Evolved Mekanism Multiversal apiary (17 processes, 30 output slots) default 128×
- Evolved Mekanism Creative apiary (19 processes, 33 output slots) default 256×
- Evolved Mekanism Extras Absolute Overclocked apiary (12 processes, 33 output slots) default 256×
- Evolved Mekanism Extras Supreme Quantum apiary (14 processes, 36 output slots) default 512×
- Evolved Mekanism Extras Cosmic Dense apiary (16 processes, 39 output slots) default 1024×
- Evolved Mekanism Extras Infinite Multiversal apiary (18 processes, 42 output slots) default 4096×

**AE2 integration** (only registered when AE2 is loaded): AE2 output toggle + AppliedFlux priority switch, mirroring the centrifuge's AE2 integration.

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
7. (Optional) Use a **KubeJS** server script listening on `MyriadBeeEvents.REGISTER` to dynamically register custom bee recipes at runtime.

## Building

```bash
git clone https://github.com/Ayoshiko/productive-bees-genesis.git
cd productive-bees-genesis
./gradlew build
```

The built jar will be at `build/libs/productivebeesgenesis-<version>.jar`.

> Requires **Java 21** and internet access to download Mekanism, Productive Bees, and AE2 dependencies from Cursemaven / Modrinth Maven.

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
├── command/            (Reserved for future commands)
├── compat/             Optional mod integrations
│   ├── kubejs/          KubeJS plugin — MyriadBeeEvents.REGISTER + recipe serializers
│   └── emextras/        Evolved Mekanism Extras block / block-entity registration
├── config/             Client / Common / Server config, bilingual
├── datagen/            Block tags, recipes, loot tables, language provider
├── init/               DeferredRegister registrations
├── item/               Custom items
├── mek/                Mekanism centrifuge blocks, tile entities, recipe processing
│   └── ae2/            AE2 integration (output pusher, grid node manager, energy injector)
├── menu/               Container menu definitions
├── mixin/              Mixin classes with MixinConfigPlugin conditional loader
│   ├── accessor/       Accessor mixins
│   ├── beehive/        Beehive inventory debounce and cache mixins
│   ├── client/         Client-side mixins (bee color, cosmic item renderer)
│   ├── iris/           Iris shader compat with IrisConfigPlugin
│   ├── mek/            Mekanism centrifuge / factory / ejector mixins
│   └── recipe/         Recipe serializer fallback mixins
├── network/            Network payloads (filter config sync)
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
- **`Ae2GridNodeManager`** / **`Ae2OutputPusher`** / **`Ae2EnergyInjector`**: AE2 node lifecycle, output push, and ME-network energy injection (5-tier priority).
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

This mod **does not collect, store, or transmit any player data**. There is no telemetry, analytics, crash reporting, or external network communication. All network packets are strictly client-server game state synchronization within Minecraft's native channel. Player UUIDs are used only as in-memory rate-limiter keys and are never persisted or transmitted externally.

### Age Rating & Health Reminder

This mod is suitable for all ages (content theme: beekeeping and industrial automation, no violence/blood/adult content).

### Community Guidelines

We are committed to providing a friendly and inclusive community. Harassment, discrimination, personal attacks, or hate speech of any kind will not be tolerated. Please report violations via [GitHub Issues](https://github.com/Ayoshiko/productive-bees-genesis/issues).

## Acknowledgments

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
