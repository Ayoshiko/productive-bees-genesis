# Productive Bees Genesis

![Version](https://img.shields.io/badge/version-1.8.1-blue?style=flat-square)
![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-green?style=flat-square)
![NeoForge](https://img.shields.io/badge/NeoForge-21.1.214+-orange?style=flat-square)
![License](https://img.shields.io/badge/license-MIT-blue?style=flat-square)
![Java](https://img.shields.io/badge/Java-21+-red?style=flat-square)

> An addon for **Productive Bees** and **Mekanism** that adds the *Myriad Creations Bee* — a rainbow-gradient bee whose honeycombs randomly transform into honeycombs of other resource bees — plus a full **Mekanism-style centrifuge** family with deep **Applied Energistics 2** integration.

**Languages**: [English](README.md) · [中文](README_zh.md)

> ⚠️ This mod is under active development — expect bugs, crashes, and compatibility issues. Feedback is welcome.

---

## Table of Contents

- [About](#about)
- [Features](#features)
  - [Myriad Creations Bee](#myriad-creations-bee)
  - [Mek Centrifuge](#mek-centrifuge)
  - [AE2 Integration](#ae2-integration)
  - [Bee Filter UI](#bee-filter-ui)
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
- Deep **AE2 integration** — centrifuges act as AE2 grid nodes, push outputs directly into ME networks, and optionally drain FE from ME networks to power themselves.
- A full **in-game bee filter UI** with search, sort, drag-and-drop, and clipboard import/export.

The Myriad Creations Honeycomb uses the same cosmic starfield mask texture as the **Sword of the Cosmos** from Re:Avaritia — see [Acknowledgments](#acknowledgments).

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
| [Jade](https://www.curseforge.com/minecraft/mc-mods/jade) | AE2 network status tooltip |
| [Iris Shaders](https://www.curseforge.com/minecraft/mc-mods/irisshaders) | Shader compatibility for cosmic rendering |
| [Just Enough Items](https://www.curseforge.com/minecraft/mc-mods/jei) | Recipe viewing + recipe hiding when bee disabled |

## Configuration

The mod ships a trilingual configuration system (English / Chinese / auto-detect) accessible through the in-game Mods → Config screen. Configuration is split into three files:

| File | Scope | Highlights |
| --- | --- | --- |
| `client.toml` | Client | Bee filter UI settings, port color visualization, rainbow effects |
| `common.toml` | Common | Myriad Creations Bee attributes (appearance, pollination, PB attributes, breeding, environment, acquisition, produce, advanced beehive) |
| `server.toml` | Server | Bee type filtering, MEK centrifuge parameters (basic / ejection / io_limit / ae2 sub-sections) |

### MEK Centrifuge Sub-sections

The 21 MEK centrifuge options are grouped for clarity:

- **basic** — energyPerTick, processingTime, fluidTankCapacity, fluidEjectRate, combBlockMultiplier
- **ejection** — ejectDelay, ejectDelayActive, ejectSkipUnchanged, ejectSkipTicks, ejectMaxSpeedMode, ejectMinInterval, ejectBusyThreshold, ejectBusyCooldown, ejectMaxPerTick, ejectBlockedThreshold, ejectBlockedCooldown
- **io_limit** — maxExtractPerTick
- **ae2** — aeOutputEnabled, aeEnergyInputEnabled, preferAppliedFluxOverAeEnergy (only registered when AE2 is loaded)

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
5. (Optional) Connect the centrifuge to an AE2 network via smart cables to enable direct ME output and ME energy input.

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
├── capability/         Rate-limited item handler, inventory dirty debouncer
├── client/             Client event handlers, JEI/Jade plugins, cosmic render, GUI screens
│   ├── jei/             JEI recipe category for PB centrifuge
│   ├── jade/            Jade plugin — AE2 status display
│   ├── render/cosmic/   Cosmic shader system, baked models, Iris compat
│   └── screen/          Configuration and Mek centrifuge GUIs + state
├── command/            (Reserved for future commands)
├── config/             ClientConfig / CommonConfig / ServerConfig, bilingual
├── datagen/            Block tags, recipes, loot tables, language provider
├── init/               DeferredRegister registrations
├── item/               Custom items (infinity sword replica)
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

## Acknowledgments

- **Productive Bees** development team — base bee and honeycomb system.
- **Mekanism** development team — machine and factory framework.
- **NeoForge** development team — modding platform.
- **Re:Avaritia** (Nova-Committee) — cosmic shader reference for the starfield texture.
- **Mek-Energistics** (beipuo) — AppliedFlux + AE2 network energy input integration pattern.
- **Applied Energistics 2** — `IN_WORLD_GRID_NODE_HOST` capability API.
- **AppliedFlux** — FE storage API in ME networks.
