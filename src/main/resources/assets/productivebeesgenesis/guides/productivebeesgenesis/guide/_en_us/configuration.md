---
navigation:
  parent: reference-index.md
  title: Configuration
  icon: "minecraft:comparator"
  position: 1
---
# Configuration

<ItemImage id="minecraft:comparator" scale="2" />

Keep defaults for a first playthrough. They already support bees, apiaries, centrifuges, and AE2 integration. Change settings only when your modpack balance, server performance, or visual preferences require it.

## Open the screen

Select **Productive Bees Genesis** in the mod list and choose **Configure**. The screen groups settings by purpose, so regular players do not need to edit TOML files or memorize raw key names.

Production, power use, capacities, and bee rules are controlled by the server in multiplayer. Client settings only affect your own rainbow animation, particles, glow, port hints, and movable-window positions.

## Four useful sections

### Gameplay Settings

This section controls the Myriad Creations Bee, balance profile, acquisition methods, resource filter, attributes, and accelerated-work budget.

- **Myriad master switch** disables only that bee; machines and upgrades remain available.
- **Balance profile**: use Basic for a normal pack, Paradox Infinity for unrestricted high-scale play, and Custom only when every limit is understood.
- **Produce from Every Apiary Flower**: lumber, quarry and dye bees produce from every distinct valid feeder source each cycle. Off in Basic and on in Paradox Infinity. Switch to Custom and save to edit independently; the previous preset value is inherited. Duplicate slots and stack counts add no rolls.
- **Myriad filter** excludes resources with a blacklist or keeps only selected resources with a whitelist.
- **Acquisition methods** separately control item conversion, breeding, fishing, and natural nest generation.
- **Acceleration**: process 2048 virtual ticks per real tick by default, configurable up to 4096. The bank buffers 8192 by default, up to 32768. Buffer capacity does not raise processing speed; high MSPT still reduces the processing budget. Existing settings retain their values.

### Machine Settings

This section controls apiary and centrifuge processing, AE2 integration, upgrade limits, and external logistics.

- **Power and operation time:** Machine Settings → Apiary or Centrifuge → Base Parameters. Keep defaults first.
- **Separate fluid slots:** Centrifuge → Base Parameters. Keep enabled.
- **AE2 output and Applied Flux power:** Machine → AE2 Integration.
- **PB upgrade counts:** Machine → PB Upgrade Limits. Increase gradually.
- **Adjacent-container output:** External Logistics, useful for simple pipe lines.

AE2 input pulling has a server master switch, but each centrifuge still starts disabled until a player enables its AE2 Input window. This prevents a newly connected machine from draining the network unexpectedly.

Apiary **gene treat restocking** has its own button below each machine's treat slot and starts disabled. Insert a template treat first; only items with identical components are extracted from the apiary's online ME network. Centrifuge input and apiary product-output toggles are independent. See [Mekanism Apiary](machines/apiary.md).

### Capacity Matrices

These arrays scale input, output, and fluid capacities by factory tier. The configuration screen documents their order. Leave them alone unless you are balancing a high-scale pack: values that are too small cause jams, while extreme values increase save and synchronization costs.

### Client Settings

These options change only rendering and window placement. Disabling particles, glow, or rainbow effects does not reduce server production.

## Safe editing

1. Stop the world or server normally.
2. Back up the world and configuration.
3. Change one related group at a time and record the previous values.
4. Re-enter the world and test power, speed, and outputs with one machine.
5. Restore that group if behavior is unexpected instead of changing many unrelated settings.

World-specific server settings are split into gameplay, machine, and capacity files under the world's `serverconfig` directory. Migration handles the legacy single server file; modpack maintainers should distribute the current generated files rather than copying the old file over them.

## Raw key names

Raw keys are needed only for pack distribution, scripted replacement, or migration. Regular players should use the translated labels and tooltips. If direct editing is unavoidable, follow the comments in the generated files and never save a partially edited file while the server is running.

## Custom bees and extended resources

PB JSON defines colors, flowers, breeding food and acquisition. Genesis adds FE, chemicals, Source and other output recipes. **Extended bees also support PB breeding, conversion, nest spawning and fishing.**

### Download and try

Get the [complete example bundle](https://github.com/Ayoshiko/productive-bees-genesis/releases/download/resource-bee-examples-1.1.0/resource-bees-all.zip). It contains runnable files, a complete KubeJS script and annotated references. Example bees are excluded from the mod JAR.

1. Put resource-bees-datapack.zip in the world's datapacks folder.
2. Put resource-bees-resourcepack.zip in resourcepacks and enable it.
3. Reopen the world and inspect eggs and bee uses in JEI.

**Annotated .jsonc files are for reading and cannot run as game JSON.** Edit the plain datapack files. INSTALL.txt explains installation; EXAMPLES.json lists each bee's related files.

### Start with copper energy

Craft the egg with an empty cage, a honey treat and a copper ingot. Use empty cages only; crafting does not protect bees inside.

Put the bee in a powered Genesis mechanical apiary with a copper ingot in its feeding slot. It produces FE directly, with no centrifuge. The flower item is reusable.

All 32 examples have egg recipes. All 28 extended bees also have breeding and conversion recipes. Adjust costs and yields to your pack.

### What to customize

- **Bee:** data/example/productivebees contains colors, flowerItem and breedingItem. selfbreed controls same-type breeding.
- **Acquisition:** acquisition, bee_breeding, bee_conversion, bee_spawning and bee_fishing under data/example/recipe cover crafting, breeding, conversion, nests and fishing.
- **Outputs:** resource_production produces resources directly; resource_centrifuging processes combs. amount is a quoted positive integer; chance is a number from 0 to 1.
- **Names:** edit both languages in assets/productivebees/lang. example:copper_energy uses entity.productivebees.copper_energy_bee.

Rename files, bee references, loading conditions and name keys together. Use singular recipe. Copy full JSON from the [README tutorial](https://github.com/Ayoshiko/productive-bees-genesis/blob/main-neo/1.21.1/README.md#extended-resource-bees).

### Breeding and conversion

For breeding, edit parent1, parent2 and offspring. Feed each parent its own breeding food. For conversion, edit source, result, item and chance, then use that item on the source bee.

Copper energy has a nest example; the water-container bee has river fishing. They use ordinary PB recipe types with an extended bee as the result.

Include example parents and their acquisition files, or choose bees already in your pack. Update modid conditions when changing optional mods.

### Ordinary PB settings

pb_copper demonstrates colors, pollen, initial genes, size, speed, attack, breeding food, water and fire protection. The annotated version explains each field.

Four examples cover flowers: pb_copper uses a copper block, pb_redstone redstone, pb_water water, and pb_tag a custom tag. Choose one flowerBlock, flowerItem, flowerFluid or flowerTag. **Extended bees accept these settings too.**

Ordinary PB examples include hive comb production and centrifuging into items or fluids. createComb enables a custom comb appearance; add output recipes separately.

### Combs into resources

hydrogen uses an iron-ingot flower. Keep the bee definition, hydrogen_honeycomb and hydrogen_centrifuging: the apiary produces combs, and a Genesis centrifuge produces hydrogen.

Customize kind, resource id, amount and processing_time. 20 ticks is about one second. The comb's productivebees:bee_type component must match the bee ID.

energy produces FE directly; energy_comb produces combs first. mixed combines items and FE. combined uses include_pb_outputs with a matching PB centrifuge recipe for both kinds of output.

### Other resource flowers

- source: Source gem; soul: soul sand.
- air: pressure gauge; emc: alchemical coal.
- experience: Bottle o' Enchanting; lightning: Lightning Collapse Matrix.
- ae_key: diamond, demonstrating a generic AE2 key output.

Choose the examples you need. Bees and recipes for missing optional mods are skipped. Full fields are in the bundle and README.

### Filled containers as flowers

Replace other flower fields with flowerResource. Set kind, resource id and minimum amount. container_hydrogen requires 1000 mB hydrogen; container_water uses a water bucket; container_energy requires 1000 FE.

container_ae_key requires at least 16 diamonds in a real ME item storage cell. **Pollination consumes neither container nor contents.** Empty, wrong-resource or insufficient containers fail. Multiple containers are not added together.

### KubeJS alternative

Copy the bundle's kubejs folder: data holds bees and tags, assets holds names, and server_scripts/genesis_resource_bees.js registers every acquisition and output recipe.

Choose either the datapack or complete script, not both. /reload updates existing recipes; reopen the world for new bees. Keep annotated references out of data.

### Output faces

The chemical tab is on the left, below ENERGY in Mekanism side configuration. Use the right-side extended tab and square icons for other resources, then choose output faces and auto-eject.

FE uses native ENERGY settings, separating operating power from produced FE. ME requires AE2, a resource bridge, power, a channel and accepting storage. EMC credits the owner's ProjectE account.

The resource panel shows production and pending stock; its icons cannot be taken. Use Genesis mechanical apiaries, centrifuges and factories for extended output.

### Troubleshooting

Missing bees: check dependencies, paths and datapack enablement. No pollination: check flowers, slot toggles and power. No breeding or conversion: check parents/source, food and loading conditions.

Combs without resources: check the Genesis centrifuge and recipe. Untranslated names: enable the resource pack and press F3+T. JSON errors: check comments, commas, quotes and quoted amount.
