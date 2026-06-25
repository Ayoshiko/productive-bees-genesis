# Productive Bees Genesis

A Productive Bees addon mod that adds the Myriad Creations Bee, capable of producing any resource honeycomb from bees available in the modpack, with deep Mekanism centrifuge integration and cosmic rendering effects. The Myriad Creations Honeycomb and Honeycomb Block now feature a starfield texture effect (mapped from the Infinity Creation Honeycomb via a BakedModel wrapper).

## 🌐 Languages
- [English](README.md)
- [中文](README_zh.md)

## Features

### Bees

- **Myriad Creations Bee**: Smooth rainbow color gradient (8-second cycle via Mixin override of PB's 1.25s default) with colorful particle effects. Produces random honeycombs from all other resource bees.
- **Starfield Honeycomb Texture**: The Myriad Creations Honeycomb and Honeycomb Block now use a starfield texture effect (mapped from the `infinitycreation_comb` texture via a BakedModel wrapper), while retaining all original functionality (centrifuge processing, random comb transformation).

### Mekanism Centrifuge Integration

- **Mek Centrifuge**: Custom Mekanism machine that processes Productive Bees honeycombs using SMELTING recipe type with ENERGIZED_SMELTER sound
- **Factory Versions**: 17 factory tiers across Mekanism, Mekanism Extras (ME), and Evolved Mekanism Extras (EME):
  - Mekanism: Basic, Advanced, Elite, Ultimate
  - ME: Overclocked, Quantum, Dense, Multiversal, Creative, Supreme, Absolute, Infinite
  - EME: Absolute Overclocked, Supreme Quantum, Cosmic Dense, Infinite Multiversal
- **Smart Recipe Processing**: SMELTING recipes take priority; PB CentrifugeRecipe processed independently with linear output scaling
- **Output Safety**: Output slots merge identical stacks; centrifuge pauses when full to prevent energy waste
- **Ejection Speed Optimization**: All Mek centrifuge types (basic machine, Mekanism factories, ME factories, EME factories) use optimized ejection delay (configurable, default 1-2 ticks vs vanilla 10 ticks) for faster output transfer to adjacent containers

### Cosmic Rendering System

- Custom cosmic/hell shaders for starfield and nebula effects
- Iris shader compatibility via Mixin (forces cosmic shaders through Iris skip list)
- Halo rendering with blend/depth state management
- Deferred rendering queue for Iris shader pack compatibility
- Thread-safe render queue with size limits and exception-safe cleanup
- **Starfield Texture Reuse**: The Myriad Creations Honeycomb and Honeycomb Block reuse the Infinity Creation Honeycomb's starfield rendering effect via a BakedModel wrapper, applying the cosmic visual to the production chain

### Configuration System

- **Client Config**: Performance monitor toggle, bee filter UI settings
- **Common Config**: Myriad Creations Bee attributes (appearance, pollination, PB attributes, basic attributes, breeding, environment)
- **Server Config**: Bee type filtering (blacklist/whitelist), Mek centrifuge parameters (including ejection delay for active/empty states)

### Bee Filter Configuration UI

- Full bee selection screen with search, sort, and group collapse
- Filter list with blacklist/whitelist modes
- Visual distinction between added and unadded bees
- Numeric indices for filter list entries
- Dynamic product info display for special bees
- Sort mode and collapse state persistence

### Performance Optimizations

- LRU recipe cache with "no recipe" result caching to avoid repeated full scans
- Recipe version tracking via `TagsUpdatedEvent` — caches auto-invalidate on recipe reload
- Per-handler block check cache (replaces global static cache) for multi-machine scenarios
- Type-specific recipe queries instead of full recipe manager scans
- Cached `energyPerTick`/`operationsPerTick` in `PbRecipeProcessor` (avoids per-tick `Math.pow`)
- Static `BakedQuad` caching in `BakedModelHalo` with double-checked locking (avoids per-frame baking)
- Reflection `Method` caching in `IrisCompat` via Holder pattern (avoids per-frame reflection)
- Bee display name and product info caching in `FilterListScreen` (avoids per-frame recipe traversal)
- Pre-allocated `Matrix4f` in `CosmicRenderQueue` (avoids per-frame allocation)
- Thread-safe collections (ConcurrentHashMap, CopyOnWriteArrayList, AtomicInteger)
- Exception-safe tick processing with automatic state reset

## Installation

### Requirements

- Minecraft 1.21.1
- NeoForge 21.1.214 or higher
- Productive Bees 1.21.1-13.13.5 or higher
- Mekanism 1.21.1-10.7.14.79 or higher (required for centrifuge features)
- Optional: Mekanism Extras, Evolved Mekanism Extras (for extended factory tiers)
- Optional: Mekanism Unleashed (extended upgrade limits)
- Optional: Iris (for shader compatibility)
- Optional: RenderBlender (cosmic rendering effects, client-side only)
- Optional: JEI (recipe viewing)

### Installation Steps

1. Ensure NeoForge loader is installed
2. Download the latest version of "Productive Bees Genesis" mod
3. Place the mod file into your Minecraft client's `mods` folder
4. Launch the game

## Obtaining

### Myriad Creations Bee

The Myriad Creations Bee is a rare bee that can be obtained through:

- Using Productive Bees' hive upgrade system
- Configuring specific spawn conditions in your modpack

### Honeycomb Production

- **Myriad Creations Bee**: Randomly produces honeycombs from other registered resource bees

## Configuration

The mod provides an in-game configuration interface accessible through the mod menu. Players can modify:

- **Appearance**: Primary color, secondary color, particle color, glow color
- **Pollination**: Flower item
- **Productive Bees Attributes**: Weather tolerance, temper, behavior, endurance, productivity
- **Basic Attributes**: Honeycomb creation, size, speed, attack damage
- **Breeding**: Breeding item, breeding item count, self-breeding
- **Environment**: Waterproof, fireproof
- **Bee Filtering**: Blacklist/whitelist mode, filtered bee types list
- **Mek Centrifuge**: Fluid tank capacity, ejection delay (active/empty states), processing parameters

### Accessing Configuration

1. Open Minecraft's main menu
2. Click "Mods"
3. Find "Productive Bees Genesis"
4. Click the "Config" button

Configuration changes take effect after restarting the game or running `/reload`.

### Language Support

The configuration interface supports multiple languages (English/Chinese) and automatically adapts to your client's language settings.

## Compatibility

- Minecraft Version: 1.21.1
- Loader: NeoForge 21.1.214+
- Required Dependency: Productive Bees 1.21.1-13.13.5+
- Required Dependency: Mekanism 1.21.1-10.7.14.79+
- Optional: Mekanism Extras (ME factories)
- Optional: Evolved Mekanism Extras (EME factories)
- Optional: Mekanism Unleashed (extended upgrade limits)
- Optional: Iris (shader compatibility)
- Optional: RenderBlender (cosmic rendering, client only)
- Optional: JEI (recipe viewing)

## Architecture

### Package Structure

- `block/`: Custom blocks (centrifuge frames, decorative blocks)
- `client/gui/`: Mek centrifuge GUI helpers and factory GUI helpers
- `client/jei/`: JEI recipe categories for PB centrifuge recipes
- `client/model/`: Custom model loaders and geometry loaders
- `client/render/cosmic/`: Cosmic shader system, baked models (`AbstractBakedModelCosmic`, `BakedModelCosmic`, `BakedModelHell`, `BakedModelHalo`), render queue, Iris compat
- `client/screen/`: GUI screens for configuration and Mek centrifuge (`FilterListScreen`, `FilterListRenderer`)
- `compat/`: Cross-mod compatibility helpers
- `config/`: ModConfig definitions (CLIENT/COMMON/SERVER) with bilingual support
- `datagen/`: Data generation (block tags, recipes, loot tables)
- `init/`: DeferredRegister registrations (blocks, items, block entities, etc.)
- `item/`: Custom items (infinity sword replica, spawn eggs)
- `mek/`: Mekanism centrifuge blocks, tile entities, containers, recipe processing (`PbRecipeProcessor`, `RecipeCacheManager`)
- `menu/`: Container menu definitions
- `mixin/`: Mixin classes (PB centrifuge, bee color, factory upgrade chain, Iris) with `CentrifugeMixinHelper` for DRY and `MixinConfigPlugin` for conditional loading
- `network/`: Network packet definitions
- `recipe/`: Custom recipe types
- `screen/`: Server-side screen holders
- `util/`: `BeeInfoHelper`, `RecipeCacheManager`, `PerformanceMonitor`, `BeeConfigApplier`

### Key Abstractions

- **`AbstractCombEventHandler`**: Base class for `MyriadCreationsEventHandler`, extracting common bee type cache, random comb generation, and centrifuge block logic
- **`AbstractBakedModelCosmic`**: Base class for `BakedModelCosmic` and `BakedModelHell`, extracting cosmic render pipeline (shader uniforms, mask sprites, Iris defer)
- **`CentrifugeMixinHelper`**: Utility class extracting common logic from 6 centrifuge Mixin classes (canOperate check, canProcessRecipe check, completeRecipeProcessing append)
- **`MixinConfigPlugin`**: Conditional Mixin loader — skips ME/EME-specific mixins when those mods are absent, preventing crashes
- **`PbRecipeProcessor`**: PB recipe processing helper with cached `energyPerTick`/`operationsPerTick` and recipe version tracking

### Thread Safety

- Static fields use `volatile` for cross-thread visibility
- Concurrent collections: `ConcurrentHashMap`, `CopyOnWriteArrayList`
- Atomic counters: `AtomicInteger`, `AtomicLong`
- Holder pattern for thread-safe lazy initialization
- Per-instance caches instead of global static caches
- `synchronized` blocks for compound operations on non-concurrent collections
- Server stop event clears static caches to prevent memory leaks

### Mixin Naming Convention

All Mixin methods and fields use the `productivebeesgenesis$` prefix (e.g., `productivebeesgenesis$onInit`, `productivebeesgenesis$getProductivityModifier`).

## Support

If you encounter issues or have feature suggestions, please contact us through:

- GitHub Issues: https://github.com/Ayoshiko/productive-bees-genesis/issues

## License

This project is licensed under the MIT License. See the [LICENSE](LICENSE) file for details.

## Acknowledgments

- Productive Bees mod team
- Mekanism development team
- NeoForge development team
- Re:Avaritia (cosmic shader reference)
