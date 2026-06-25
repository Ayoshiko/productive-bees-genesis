# Productive Bees Genesis

A Productive Bees addon featuring the **Myriad Creations Bee**, deep Mekanism centrifuge integration with optimized ejection across all factory tiers, and cosmic starfield rendering.

The Myriad Creations Honeycomb and Honeycomb Block reuse the Infinity Creation Honeycomb's starfield texture via a BakedModel wrapper, keeping all original functionality (centrifuge processing, random comb transformation) intact.

## Languages

- [English](README.md)
- [中文](README_zh.md)

## Features

| Feature | Description |
| --- | --- |
| Myriad Creations Bee | 8-second rainbow color cycle (Mixin override of PB's default 1.25s) with particle effects; produces random resource honeycombs. |
| Starfield Honeycomb Texture | Myriad Creations Honeycomb and Honeycomb Block reuse Infinity Creation Honeycomb's cosmic texture while preserving original mechanics. |
| Mek Centrifuge | Custom Mekanism machine that processes Productive Bees honeycombs using SMELTING recipe type. |
| Factory Tiers | 17 tiers across Mekanism, Mekanism Extras (ME), and Evolved Mekanism Extras (EME). |
| Smart Recipe Processing | SMELTING recipes take priority; PB CentrifugeRecipe processed independently with linear output scaling. |
| Output Safety | Output slots merge identical stacks; machine pauses when full to avoid wasting energy. |
| Ejection Optimization | All Mek centrifuge types use configurable ejection delay (default 1-2 ticks vs vanilla 10 ticks). |
| Cosmic Rendering | Custom shaders for starfield/nebula effects with Iris compatibility and deferred render queue. |
| Bee Filter UI | In-game bee blacklist/whitelist editor with search, sort, and collapse controls. |
| Performance | LRU recipe cache, cached energy/operation values, pre-allocated render matrices, thread-safe collections. |

## Configuration

| Option | Default | Description |
| --- | --- | --- |
| `ejectDelay` | 2 | Ejection delay (ticks) when output slots are empty. |
| `ejectDelayActive` | 1 | Ejection delay (ticks) when output slots still contain items. Automatically clamped to be <= `ejectDelay`. |
| `beeFilterMode` | Blacklist | Whether the bee filter acts as a blacklist or whitelist. |
| `beeFilterList` | Empty | List of bee types excluded/included by the filter. |

## Required Dependencies

| Mod | Version | Description |
| --- | --- | --- |
| Minecraft | 1.21.1 | Game version. |
| NeoForge | 21.1.214+ | Mod loader. |
| Productive Bees | 1.21.1-13.13.5+ | Base bee system and honeycomb mechanics. |
| Mekanism | 1.21.1-10.7.14.79+ | Required for Mek centrifuge features. |

## Compatible Mods

| Mod | Integration |
| --- | --- |
| Mekanism Extras | Higher-tier factory tiers (ME). |
| Evolved Mekanism Extras | Higher-tier factory tiers (EME). |
| Mekanism Unleashed | Extended upgrade limits. |
| Iris | Shader compatibility for cosmic rendering. |
| JEI | Recipe viewing support. |

## Usage

1. Install NeoForge and the required dependencies.
2. Place the mod jar in your `mods` folder.
3. Launch the game and obtain the Myriad Creations Bee through Productive Bees' hive upgrade system or modpack configuration.
4. Process Myriad Creations Honeycombs in the Mek Centrifuge or its factory variants.

## Build

```bash
./gradlew.bat build
```

## Support

Report issues or suggestions on [GitHub Issues](https://github.com/Ayoshiko/productive-bees-genesis/issues).

## License

This project is licensed under the [MIT License](LICENSE).

## Acknowledgments

- Productive Bees mod team
- Mekanism development team
- NeoForge development team
- Re:Avaritia (cosmic shader reference)
