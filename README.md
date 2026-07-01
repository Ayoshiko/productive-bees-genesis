# Productive Bees Genesis

![Version](https://img.shields.io/badge/version-1.3.3-blue) ![MC Version](https://img.shields.io/badge/Minecraft-1.21.1-green) ![Loader](https://img.shields.io/badge/NeoForge-21.1.214-orange)

An addon for Productive Bees and Mekanism that adds Mekanism-style centrifuges capable of processing honeycombs and honeycomb blocks. Also adds the Myriad Creations Bee, whose honeycomb can transform into honeycombs from all other resource bees. The honeycomb transformation supports a detailed configurable filter list. Myriad Creations Bee datapack values can be customized in the config files.

目前模组仍处于开发阶段，更新频繁，仍存在大量bug，崩溃，不稳定和兼容性问题，感谢理解。

This mod is under active development – expect bugs, crashes, instability, and compatibility problems. Thanks for your patience!

For a full list of changes, see the [CHANGELOG](CHANGELOG.md).

The Myriad Creations Honeycomb uses the same cosmic starfield mask texture as the Sword of the Cosmos from Re:Avaritia.

## Languages

- [English](README.md)
- [中文](README_zh.md)

## Features

| Feature | Description |
| --- | --- |
| Myriad Creations Bee | 8-second rainbow gradient with rainbow particle effects; its honeycomb randomly produces honeycombs from other resource bees. |
| Starfield Honeycomb Texture | The Myriad Creations Honeycomb uses the same cosmic starfield mask texture as the Sword of the Cosmos from Re:Avaritia. |
| Mek Centrifuge | Mekanism-style centrifuge that processes Productive Bees honeycombs and honeycomb blocks. Also supports Energized Smelter recipes. |
| Factory Tiers | 17 tiers across Mekanism, Mekanism Extras, Evolved Mekanism, and Evolved Mekanism Extras. |
| Bee Filter UI | In-game bee blacklist/whitelist editor with search, sort, and collapse controls. |

### Mekanism Addon: Productive Bees Centrifuge

- **Mek Centrifuge**: Adds a Mekanism-style centrifuge that processes Productive Bees honeycombs and honeycomb blocks. Also supports Energized Smelter recipes.
- **Factory Upgrade Compatibility**: Compatible with factory upgrades from Mekanism Extras, Evolved Mekanism, and Evolved Mekanism Extras.
- **Output Safety**: Output slots merge identical stacks; the centrifuge pauses when full to avoid wasting energy.
- **Ejection Speed Optimization**: All Mek centrifuge types use optimized configurable ejection delay for faster output transfer to adjacent containers.

### Configuration System

- **Client Config**: Performance monitor toggle, bee filter UI settings.
- **Common Config**: Myriad Creations Bee attributes (appearance, pollination, PB attributes, basic attributes, breeding, environment).
- **Server Config**: Bee type filtering (blacklist/whitelist), Mek centrifuge parameters (including active/idle ejection delay).

### Bee Filter Configuration UI

- Full bee selection screen with search, sort, and group collapse.
- Filter list supports blacklist/whitelist modes.
- Visual distinction between added and unadded bees.
- Numeric indices for filter list entries.
- Dynamic product info display for special bees.
- Sort mode and collapse state persistence.

## Configuration

The mod provides an in-game configuration interface accessible through the mod menu. Players can modify:

- **Appearance**: Primary color, secondary color, particle color, glow color.
- **Pollination**: Flower item.
- **Productive Bees Attributes**: Weather tolerance, temper, behavior, endurance, productivity.
- **Basic Attributes**: Honeycomb creation, size, speed, attack damage.
- **Breeding**: Breeding item, breeding item count, self-breeding.
- **Environment**: Waterproof, fireproof.
- **Bee Filtering**: Blacklist/whitelist mode, filtered bee types list.
- **Mek Centrifuge**: Fluid tank capacity, ejection delay, processing parameters.

### Accessing Configuration

1. Open the Minecraft main menu.
2. Click "Mods".
3. Find "Productive Bees Genesis".
4. Click the "Config" button.

Configuration changes take effect after restarting the game or running `/reload`.

### Language Support

The configuration interface supports multiple languages (English/Chinese) and automatically adapts to your client's language settings.

## Required Dependencies

| Mod | Version | Description |
| --- | --- | --- |
| Minecraft | 1.21.1 | Game version. |
| NeoForge | 21.1.214+ | Mod loader. |
| Productive Bees | 1.21.1-13.13.5+ | Bee system and honeycomb mechanics. |
| Mekanism | 1.21.1-10.7.14.79+ | Required for Mek centrifuge features. |

## Compatible Mods

| Mod | Integration |
| --- | --- |
| Mekanism Extras | ME-tier factories. |
| Evolved Mekanism | EM-tier factories. |
| Evolved Mekanism Extras | EME-tier factories. |
| Mekanism Unleashed | Extended upgrade limits. |
| Iris | Shader compatibility for cosmic rendering. |
| JEI | Recipe viewing support. |

## Usage

1. Install NeoForge and the required dependencies.
2. Place the mod jar in your `mods` folder.
3. Launch the game and obtain the Myriad Creations Bee through the Productive Bees hive system.
4. Process Myriad Creations Honeycombs in the Mek Centrifuge or its factory variants.

## Architecture

### Package Structure

- (root): Main mod classes (`ProductiveBeesGenesis`, `ProductiveBeesGenesisClient`), comb event handlers (`AbstractCombEventHandler`, `MyriadCreationsEventHandler`), `RandomHoneycombSelector` (random comb allocation algorithms), `CombBlockCheckCache` (idle operation interception cache).
- `block/`: Custom blocks (centrifuge frames, decorative blocks).
- `client/gui/`: Mek centrifuge GUI helpers and factory GUI helpers.
- `client/jei/`: JEI recipe categories for PB centrifuge recipes.
- `client/model/`: Custom model loaders and geometry loaders.
- `client/render/cosmic/`: Cosmic shader system, baked models (`AbstractBakedModelCosmic`, `BakedModelCosmic`, `BakedModelHell`, `BakedModelHalo`), render queue, Iris compat, `AbstractMaskGeometryLoader` base class.
- `client/screen/`: GUI screens for configuration and Mek centrifuge — main screens (`FilterListScreen`, `BeeSelectionScreen`) with composition helpers (`FilterListDragHandler`, `FilterListClipboardHelper`, `BeeSelectionSorter`) and renderers (`FilterListRenderer`, `BeeSelectionRenderer`).
- `compat/`: Cross-mod compatibility helpers.
- `config/`: Configuration definitions split into `ClientConfig`/`CommonConfig`/`ServerConfig` with `ModConfig` as aggregation entry, bilingual support.
- `datagen/`: Data generation (block tags, recipes, loot tables).
- `init/`: DeferredRegister registrations (blocks, items, block entities, etc.).
- `item/`: Custom items (infinity sword replica, spawn eggs).
- `mek/`: Mekanism centrifuge blocks, tile entities, containers, recipe processing — `PbRecipeProcessor` coordinator delegating to `PbRecipeFinder`/`PbRecipeCompleter`/`MyriadCreationsHandler`, `FactoryPbContextDelegate` composition class for factories, `RecipeCacheManager`, isolated optional-dependency BlockTypes (`MekCentrifugeMEBlockType`, `MekCentrifugeEMEBlockType`).
- `menu/`: Container menu definitions.
- `mixin/`: Mixin classes (PB centrifuge, bee color, factory upgrade chain, Iris, recipe serializer fallbacks) with `CentrifugeMixinHelper` for DRY and `MixinConfigPlugin`/`IrisConfigPlugin` for conditional loading.
- `recipe/`: Custom recipe types.
- `screen/`: Server-side screen holders.
- `util/`: `BeeInfoHelper`, `RecipeCacheManager`, `PerformanceMonitor`, `BeeConfigApplier`, `BeeIngredientFallback`, `PBConstants`.

### Key Abstractions

- **`AbstractCombEventHandler`**: Base class for `MyriadCreationsEventHandler`, extracting common bee type cache, random comb generation, and centrifuge block logic. Delegates random comb allocation to `RandomHoneycombSelector` and idle interception to `CombBlockCheckCache`.
- **`RandomHoneycombSelector`**: Static utility for random comb allocation algorithms (Fisher-Yates shuffle, Stars-and-Bars distribution, even allocation), used by both event handlers and the Mekanism batch planner.
- **`CombBlockCheckCache`**: Idle operation interception cache preventing redundant block state checks when output is full.
- **`AbstractBakedModelCosmic`**: Base class for `BakedModelCosmic` and `BakedModelHell`, extracting the cosmic render pipeline (shader uniforms, mask sprites, Iris defer).
- **`AbstractMaskGeometryLoader`**: Base class for `GeometryLoaderCosmic` and `GeometryLoaderHell`, extracting common mask parsing and parent resolution logic.
- **`CentrifugeMixinHelper`**: Utility class extracting common logic from 6 centrifuge Mixin classes (canOperate check, canProcessRecipe check, completeRecipeProcessing append).
- **`BeeIngredientFallback`**: Utility class providing fallback serialization for 5 recipe Serializer Mixins, preventing NPE when BeeIngredientFactory is not ready.
- **`PBConstants`**: Common constants class unifying `MYRIADCREATIONS_TYPE` and other shared constants across the codebase.
- **`MekCentrifugeMEBlockType`/`MekCentrifugeEMEBlockType`**: Isolated BlockType definitions for optional ME/EME dependencies, loaded only when those mods are present to prevent `NoClassDefFoundError`.
- **`MixinConfigPlugin`**: Conditional Mixin loader — skips ME/EME-specific mixins when those mods are absent, preventing crashes.
- **`PbRecipeProcessor`**: PB recipe processing coordinator holding shared state arrays and delegating to specialized components — `PbRecipeFinder` (double-layer cached recipe lookup), `PbRecipeCompleter` (output aggregation and batch insertion), `MyriadCreationsHandler` (Myriad Creations special path).
- **`FactoryPbContextDelegate`**: Composition class eliminating ~293 lines of duplicated PB recipe context logic across the three factory tile entities.
- **`BeeSelectionSorter`**: Composition class extracted from `BeeSelectionScreen` handling bee type sorting/filtering logic with cached display items.
- **`FilterListDragHandler`/`FilterListClipboardHelper`/`FilterListBeeInfoCache`/`FilterListSelectionManager`**: Composition helpers extracted from `FilterListScreen` for drag/scroll interaction, clipboard import/export, bee info caching, and selection management respectively.

### Thread Safety

- Static fields use `volatile` for cross-thread visibility.
- Concurrent collections: `ConcurrentHashMap`, `CopyOnWriteArrayList`.
- Atomic counters: `AtomicInteger`, `AtomicLong`.
- Holder pattern for thread-safe lazy initialization.
- Per-instance caches instead of global static caches.
- `synchronized` blocks for compound operations on non-concurrent collections.
- Server stop event clears static caches and unregisters JMX MBeans to prevent memory leaks.

### Mixin Naming Convention

All Mixin methods and fields use the `productivebeesgenesis$` prefix (e.g., `productivebeesgenesis$onInit`, `productivebeesgenesis$getProductivityModifier`).

## Support

If you encounter issues or have feature suggestions, please contact us through:

- GitHub Issues: https://github.com/Ayoshiko/productive-bees-genesis/issues

## License

This project is licensed under the MIT License. See the [LICENSE](LICENSE) file for details.

## Acknowledgments

- Productive Bees development team
- Mekanism development team
- NeoForge development team
- Re:Avaritia (cosmic shader reference)
