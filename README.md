# Productive Bees Genesis

![Version](https://img.shields.io/badge/version-2.0.0-blue?style=flat-square)
![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-green?style=flat-square)
![NeoForge](https://img.shields.io/badge/NeoForge-21.1.214+-orange?style=flat-square)
![License](https://img.shields.io/badge/license-MIT-blue?style=flat-square)
![Java](https://img.shields.io/badge/Java-21+-red?style=flat-square)

> An addon for **Productive Bees** and **Mekanism** that adds the *Myriad Creations Bee* — a rainbow-gradient bee whose honeycombs randomly transform into honeycombs of other resource bees — a full **Mekanism-style centrifuge** family with deep **Applied Energistics 2** integration, and the all-new **MEK Apiary** — an industrialized bee production system built on Mekanism's electric machine framework.

**Languages**: [English](README.md) · [中文](README_zh.md)

> ⚠️ This mod is under active development — expect bugs, crashes, and compatibility issues. Feedback is welcome.

---

## Table of Contents

- [About](#about)
- [Features](#features)
  - [Myriad Creations Bee](#myriad-creations-bee)
  - [Mek Centrifuge](#mek-centrifuge)
  - [MEK Apiary](#mek-apiary)
  - [Direct Centrifuge Connection](#direct-centrifuge-connection)
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

**Productive Bees Genesis** is a NeoForge addon that bridges **Productive Bees** and **Mekanism**. It introduces:

- The **Myriad Creations Bee** — a special bee whose honeycomb randomly produces honeycombs from other resource bees via a configurable filter.
- The **MEK Centrifuge** — a Mekanism-style machine that processes Productive Bees honeycombs and honeycomb blocks, with **17 factory tiers** spanning four Mekanism-family mods.
- The **MEK Apiary** — an industrialized bee production system that migrates the vanilla Productive Bees hive logic onto Mekanism's electric machine framework (`TileEntityElectricMachine`), reusing Mekanism's energy, side configuration, upgrades, safety, and GUI infrastructure. Ships with the same **17 factory tiers** plus a base variant.
- Deep **AE2 integration** — centrifuges and apiaries act as AE2 grid nodes, push outputs directly into ME networks, and optionally drain FE from ME networks to power themselves.
- A full **in-game bee filter UI** with search, sort, drag-and-drop, and clipboard import/export.
- **KubeJS** script hooks for dynamic bee recipe registration at runtime.

The Myriad Creations Honeycomb uses a cosmic starfield rendering technique inspired by the **Sword of the Cosmos** from Re:Avaritia (shader code derived from Re:Avaritia's MIT-licensed source; textures are original) — see [Acknowledgments](#acknowledgments).

For the full list of changes, see the [CHANGELOG](CHANGELOG.md).

## Features

### Myriad Creations Bee

| Capability | Description |
| --- | --- |
| Rainbow gradient | 8-second color cycle with soft, low-saturation colors matching the "creation of all things" theme |
| Rainbow particles | Optional particle effects (`particleEffectEnabled`, `particleCount`) |
| Glow effect | Optional glow halo (`glowEnabled`, `glowColor`) |
| Random honeycomb | Produces a random honeycomb from any resource bee in the pack |
| Filter list | Blacklist/whitelist mode with in-game editor |
| Fully configurable | All PB datapack attributes (colors, pollination, breeding, environment, etc.) |
| Acquisition | Fishing, breeding, nest spawning, or bee conversion — all configurable |
| Disable switch | `myriadCreationsEnabled = false` keeps only MEK centrifuge features |

### Mek Centrifuge

- **MEK Centrifuge**: Mekanism-style centrifuge processing Productive Bees honeycombs and honeycomb blocks. Also supports Energized Smelter recipes.
- **17 factory tiers** across Mekanism, Mekanism Extras, Evolved Mekanism, and Evolved Mekanism Extras:
  - **Mekanism**: Basic / Advanced / Elite / Ultimate
  - **Mekanism Extras**: Absolute / Supreme / Cosmic / Infinite
  - **Evolved Mekanism**: Overclocked / Quantum / Dense / Multiversal / Creative
  - **Evolved Mekanism Extras**: Absolute Overclocked / Supreme Quantum / Cosmic Dense / Infinite Multiversal
- **Output safety**: Identical stacks are merged; the centrifuge pauses when full to avoid wasting energy.
- **Ejection optimization**: All variants use optimized configurable ejection delay (active/idle/blocked/busy states).
- **Fluid auto-eject**: Default 16384 mB/tick, overriding Mekanism's 1024 mB/tick.

### MEK Apiary

An electrified bee production system built on the **Mekanism** universal machine framework. By extending `TileEntityElectricMachine`, the apiary fully reuses Mekanism's energy storage, side configuration, upgrade cards, safety interlocks, and GUI framework — migrating Productive Bees' vanilla hive logic into a MEK industrialized pipeline.

#### Apiary Tiers (17 factory tiers + 1 base variant)

| Tier family | Tier names | Bee slots | Output slots | Fluid tank capacity |
| --- | --- | --- | --- | --- |
| Base | MEK Apiary | 3 | 9 | 256,000 mB |
| Mekanism (4) | Basic / Advanced / Elite / Ultimate | 5–20 | 9–18 | 256K–1024K mB |
| Mekanism Extras (4) | Absolute / Supreme / Cosmic / Infinite | 26–42 | 21–30 | 1280K–2048K mB |
| Evolved Mekanism (5) | Overclocked / Quantum / Dense / Multiversal / Creative | 26–45 | 21–33 | 1280K–2304K mB |
| Evolved Mekanism Extras (4) | Absolute Overclocked / Supreme Quantum / Cosmic Dense / Infinite Multiversal | 45–60 | 33–42 | 2304K–3072K mB |

#### Core Features

- **Slot layout**: bee slots / output slots / cage slots (bidirectional transfer) / energy slot / honey fluid tank / PB upgrade slots.
- **Feeder system**: dedicated window managing flower items. Base variant uses a fixed 3×3 = 9-slot grid; factory variants use a dynamic layout scaling with bee slot count.
- **PB upgrade cards (9 types)**:
  - Productivity α / β / γ / Ω — production multipliers 1.2 / 1.5 / 2.0 / 2.6.
  - Time I / II — per-tier processing time reduction of −15% / −30%.
  - Gene Sampling, Honeycomb Block, Simulation Upgrade.
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

### AE2 Integration

Centrifuges act as **AE2 grid nodes** via the standard `IN_WORLD_GRID_NODE_HOST` capability:

- Connect directly with AE2 smart cables and adjacent centrifuges.
- Auto-discovered by addon-mod cables (ExtendedAE, AdvancedAE, ae2cs, ae2lt, Glodium, AppliedFlux).
- **Direct ME output** (`aeOutputEnabled`): push output slot items into the ME network, bypassing external logistics.
- **ME energy input** (`aeEnergyInputEnabled`): drain FE stored in the ME network to power the centrifuge. Supports 5-tier energy priority:
  1. Local FE cache
  2. External direct supply (Mekanism configComponent + EnergyInventorySlot)
  3. ME network stored FE (AppliedFlux)
  4. Other energy (handled by Mekanism parent)
  5. AE2 native network energy (converted to FE)
- **Jade tooltip**: Shows AE2 network status (Offline / Booting / Missing Channel / Online).
- Node lifecycle is decoupled from `aeOutputEnabled` — closing output push does not disconnect the machine, allowing ME energy input to continue.

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
| [Applied Energistics 2](https://github.com/AppliedEnergistics/Applied-Energistics-2) | Cable connection + ME network output + ME energy input |
| [AppliedFlux](https://www.curseforge.com/minecraft/mc-mods/appliedflux) | ME-network-stored FE as energy source for centrifuges |
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
| `client.toml` | Client | Bee filter UI settings, port color visualization, rainbow effects |
| `common.toml` | Common | Myriad Creations Bee attributes (appearance, pollination, PB attributes, breeding, environment, acquisition, produce, advanced beehive) |
| `server.toml` | Server | Bee type filtering, MEK centrifuge parameters (basic / ejection / io_limit / ae2 sub-sections), MEK apiary parameters (basic / ejection / stack_multiplier / ae2 / pb_upgrade / window_positions sub-sections) |

### MEK Centrifuge Sub-sections

The 21 MEK centrifuge options are grouped for clarity:

- **basic** — energyPerTick, processingTime, fluidTankCapacity, fluidEjectRate, combBlockMultiplier
- **ejection** — ejectDelay, ejectDelayActive, ejectSkipUnchanged, ejectSkipTicks, ejectMaxSpeedMode, ejectMinInterval, ejectBusyThreshold, ejectBusyCooldown, ejectMaxPerTick, ejectBlockedThreshold, ejectBlockedCooldown
- **io_limit** — maxExtractPerTick
- **ae2** — aeOutputEnabled, aeEnergyInputEnabled, preferAppliedFluxOverAeEnergy (only registered when AE2 is loaded)

### MEK Apiary Sub-sections

The MEK apiary options mirror the centrifuge's structure and add apiary-specific tuning:

- **basic** — `energyPerTick` (per-bee, per-tick energy cost, default 50 FE), `processingTime` (base processing time, default 1200 ticks), `fluidTankCapacity` (base variant fluid tank capacity, default 256,000 mB).
- **ejection** — `ejection.*` (eject delay / speed / blocked cooldown, mirroring the centrifuge's ejection sub-section).
- **stack_multiplier** — `stack_multiplier.*` (per-tier output slot stack multiplier across the 17 factory tiers).
- **ae2** — `ae2.*` (AE2 output toggle + AppliedFlux priority switch, only registered when AE2 is loaded).
- **pb_upgrade** — `pb_upgrade.*` (PB upgrade card stack limits — productivity / time / gene sampling / honeycomb block / simulation).
- **window_positions** — `window_positions.*` (persisted positions for the four customizable GUI tabs: Sorting / Feeder / PB Upgrades / Multi-fluid tank).

### Accessing Configuration

1. Open the Minecraft main menu.
2. Click **Mods**.
3. Find **Productive Bees Genesis**.
4. Click **Config**.

Changes take effect after restarting the game or running `/reload`.

## Usage

1. Install NeoForge and the required dependencies.
2. Place the mod jar in your `mods` folder.
3. Launch the game and obtain the **Myriad Creations Bee** through any configured acquisition method (fishing, breeding, nest spawning, or bee conversion).
4. Process Myriad Creations Honeycombs in the **MEK Centrifuge** or any of its factory variants.
5. (Optional) Place a **MEK Apiary** adjacent to a centrifuge to build a "produce → process" pipeline — bees produce honeycombs in the apiary, the apiary pushes them directly into the centrifuge's input slot, and the centrifuge outputs the bee products. Insert PB upgrade cards into the apiary to scale productivity and processing speed.
6. (Optional) Connect the centrifuge and/or apiary to an AE2 network via smart cables to enable direct ME output and ME energy input.
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
│   └── screen/          Configuration and Mek centrifuge GUIs + state
├── command/            (Reserved for future commands)
├── compat/             Optional mod integrations
│   ├── kubejs/          KubeJS plugin — MyriadBeeEvents.REGISTER + recipe serializers
│   └── emextras/        Evolved Mekanism Extras block / block-entity registration
├── config/             ClientConfig / CommonConfig / ServerConfig, bilingual
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
- **`TileEntityMekApiary`** / **`TileEntityMekApiaryFactory`**: MEK apiary tile entities extending Mekanism's `TileEntityElectricMachine`; compose `ApiarySlotManager`, `ApiaryPbUpgradeHandler`, `ApiaryDirectEjectHandler`, `ApiaryCageHandler`, `BeeProduceProcessor`, and `ApiaryAe2HostAdapter` to migrate PB hive logic into the MEK industrial pipeline.
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

This mod is suitable for all ages (content theme: beekeeping and industrial automation, no violence/blood/adult content). For players in mainland China, please observe reasonable play time — moderation is good, excess harms.

### Community Guidelines

We are committed to providing a friendly and inclusive community. Harassment, discrimination, personal attacks, or hate speech of any kind will not be tolerated. Please report violations via [GitHub Issues](https://github.com/Ayoshiko/productive-bees-genesis/issues).

## Acknowledgments

- **Productive Bees** development team — base bee and honeycomb system.
- **Mekanism** development team — machine and factory framework.
- **NeoForge** development team — modding platform.
- **Re:Avaritia** (Nova-Committee) — cosmic shader reference for the starfield texture.
- **Mek-Energistics** (beipuo) — AppliedFlux + AE2 network energy input integration pattern.
- **Applied Energistics 2** — `IN_WORLD_GRID_NODE_HOST` capability API.
- **AppliedFlux** — FE storage API in ME networks.
- **KubeJS** — runtime script hook and event group API.
- **Jade** — block tooltip component plugin API.
