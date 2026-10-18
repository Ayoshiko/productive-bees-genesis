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

## Pack authors: create an extended resource bee

Start with hydrogen: apiary → comb → extended resource centrifuging → hydrogen. Then change the resource, colors and flower. JSON or KubeJS is enough; Java is optional.

**The supported resource set is not limited to the examples below.** Hydrogen, FE, Source, souls, air, EMC, XP and lightning are built-in adapters. Custom resources from other mods can use the same generic ae_key output registration if their AEKey is registered with AE2 and supports a complete encode/decode round trip.

Install the mod providing the key and ME storage that accepts it. A new resource without an AEKey needs a resource type and transfer adapter; writing its name alone does not create support.

Genesis apiaries, centrifuges and factory tiers retain resources awaiting export through native capabilities or the machine's ME connection. Icons cannot be dragged into an inventory. No extra output block is needed.

### 1. Get the examples from GitHub

Open the [GitHub example directory](https://github.com/Ayoshiko/productive-bees-genesis/tree/main-neo/1.21.1/examples/resource-bees) and download files matching your mod version. Example bees are not bundled in the release JAR.

The folders separate data from client assets, following the PB template structure:

- **datapack** contains bee definitions, recipes and flower tags. Install it in the world's datapacks folder. pack.mcmeta and data must be directly inside the pack.
- **resourcepack** contains Chinese and English bee names. Install it in the game's resourcepacks folder and enable it under Options → Resource Packs. pack.mcmeta and assets go at its root.
- **kubejs/server_scripts** contains optional recipe scripts.
- **api** contains Java output registration and machine integration examples.

For Minecraft 1.21.1, the data pack format is 48 and resource pack format is 34. Servers need the data pack; each client needs the enabled resource pack. Server-side assets do not install client translations.

Development runClient loads both data and names automatically. The packageResourceBeeExamples build task creates two installable ZIPs; see the [README walkthrough](https://github.com/Ayoshiko/productive-bees-genesis/blob/main-neo/1.21.1/README.md#extended-resource-bees).

### 2. Files for the hydrogen bee

**Bee definition:** [hydrogen.json](https://github.com/Ayoshiko/productive-bees-genesis/blob/main-neo/1.21.1/examples/resource-bees/datapack/data/example/productivebees/hydrogen.json).

Its folders are data → example → productivebees. example is the namespace; the filename makes the bee ID example:hydrogen. primaryColor and secondaryColor control colors. flowerBlock is minecraft:iron_block.

**Hive recipe:** [hydrogen_honeycomb.json](https://github.com/Ayoshiko/productive-bees-genesis/blob/main-neo/1.21.1/examples/resource-bees/datapack/data/example/recipe/hydrogen_honeycomb.json).

Its folders are data → example → recipe. The type is productivebees:advanced_beehive. Both ingredient and the output's productivebees:bee_type component refer to example:hydrogen.

**Extended resource centrifuging:** [hydrogen_centrifuging.json](https://github.com/Ayoshiko/productive-bees-genesis/blob/main-neo/1.21.1/examples/resource-bees/datapack/data/example/recipe/hydrogen_centrifuging.json).

The type is productivebeesgenesis:resource_centrifuging and bee is example:hydrogen. In outputs, kind is chemical, id is mekanism:hydrogen, amount is the string "100", and chance is the number 1.0. processing_time is 20.

_comment is a legal JSON explanation field; // comments are not valid JSON. Use the singular recipe folder. Re-enter the world after adding a bee; /reload applies edits to existing recipes.

### 3. Add names together with the bee

Open the [Chinese names](https://github.com/Ayoshiko/productive-bees-genesis/blob/main-neo/1.21.1/examples/resource-bees/resourcepack/assets/productivebees/lang/zh_cn.json) and [English names](https://github.com/Ayoshiko/productive-bees-genesis/blob/main-neo/1.21.1/examples/resource-bees/resourcepack/assets/productivebees/lang/en_us.json).

The folders are assets → productivebees → lang. Hydrogen uses **entity.productivebees.hydrogen_bee**, with the English value "Hydrogen Bee".

PB uses the fixed productivebees translation prefix, followed by the bee ID's path and _bee. It does not use the bee's example namespace. For mypack:my_hydrogen, use entity.productivebees.my_hydrogen_bee.

When creating a bee, update its filename, all recipe references and both language keys together. Use distinct bee paths even across namespaces to avoid PB name collisions. Eggs, combs, JEI and apiary previews reuse the bee name.

If a name starts with entity.productivebees, check that the client resource pack is enabled and the key is correct. F3+T reloads client assets; /reload only reloads data.

### 4. Run the hydrogen production line

Examples add no natural spawning. Use the matching egg for creative tests; add PB breeding or conversion recipes for survival acquisition.

1. Put the hydrogen bee in a powered Genesis apiary, with an iron block in the flower slot.
2. Move its comb to a powered Genesis mechanical centrifuge.
3. The base yield is 100 mB per comb or four portions, 400 mB, per comb block. Chance-based portions roll independently.
4. Open the Mekanism side-configuration window and choose the Chemicals page beside Items, Fluids and ENERGY. Select output faces and enable auto-eject into an adjacent tank, or disable auto-eject and extract with chemical pipes. ME export uses AE2 and Applied Mekanistics with a cable directly on the centrifuge.
5. Supply ME power, a channel and chemical-compatible storage, with the machine's AE output switch enabled.

Extended resources defaults to Production and can switch to Pending stock. Rates use actual successful production over five seconds, including upgrades and acceleration. Observed resources remain visible with no stock or after combs return to ME; rates reach zero when production stops. Only an open window synchronizes changes; Jade receives no rates. Chemical gauges show pending stock. Eject attempts ME or adjacent storage and retains rejected output. Exported stock appears in ME.

For output without ME, open the machine's Mekanism side-configuration window. Resource pages sit beside Items, Fluids and ENERGY and appear only when their supporting mod is loaded. Chemicals use Mekanism's native chemical-side icon; other pages show centered representative items from their mods. Select a resource and mark its output faces blue. Auto-eject sends output to compatible adjacent containers or machines. With auto-eject off, external pipes can still extract through supported native capabilities. Chemicals, Source, souls, experience and lightning use their respective APIs; air actively supplies pneumatic networks under safe pressure. FE product output faces supply cables, while other faces continue charging the working battery.

Existing machines retain six-sided extraction for chemicals and other native resources, with auto-eject off; air supply defaults on, and FE product faces default off. Settings survive saving, tier installers, dismantling/mining machine items and crafting upgrades. Shift-right-click a compatible machine with a Mekanism configuration card to copy; right-click to paste. Cards copy faces and auto-eject settings without resource accounts, balances or EMC recipients. Multi-machine crafting keeps the first settings in crafting-grid order.

**Genesis extended resource outputs do not yet support PB's own hives or centrifuges. Use Genesis mechanical apiaries / centrifuges.** Ordinary PB outputs still follow PB recipes. Related bee cages, spawn eggs, combs / comb blocks and mechanical apiary bee slots display this notice without Shift.

Report extension issues to [Genesis issues](https://github.com/Ayoshiko/productive-bees-genesis/issues). Please do not report this adaptation limitation to PB upstream.

### 5. Choose another route

- **Direct FE:** use [energy.json](https://github.com/Ayoshiko/productive-bees-genesis/blob/main-neo/1.21.1/examples/resource-bees/datapack/data/example/productivebees/energy.json) and [energy_production.json](https://github.com/Ayoshiko/productive-bees-genesis/blob/main-neo/1.21.1/examples/resource-bees/datapack/data/example/recipe/energy_production.json). Use a copper-block flower, resource_production, kind energy and energy_type FE. Output FE is separate from operating power; ME export needs Applied Flux.
- **Combs followed by FE:** use the separate energy_comb bee and its hive and centrifuging recipes.
- **Direct EHV lightning:** see [lightning_ehv_production.json](https://github.com/Ayoshiko/productive-bees-genesis/blob/main-neo/1.21.1/examples/resource-bees/datapack/data/example/recipe/lightning_ehv_production.json). Use kind lightning and tier extreme_high_voltage. HV uses high_voltage. Voltages never convert.
- **Items with resources:** mixed keeps a PB hive recipe and a direct resource recipe. mixed_essence demonstrates conversion; mixed_tag demonstrates tagged flowers.

createComb enables comb appearance; it does not create a production recipe. Giving one bee both direct and comb routes produces resources at both stages.

### 6. Common fields and built-in adapters

- **bee, ingredient, bee_type:** complete bee IDs; update every reference when renaming.
- **amount:** a positive integer string such as "1000". Other resources: maximum 9223372036854775807. EMC: up to 4096 decimal digits. No decimals or scientific notation.
- **chance:** a number from 0 to 1. 1.0 always produces; 0.25 means 25% per portion.
- **processing_time:** base centrifuging time; 20 ticks equal one second.
- **flowerBlock, flowerItem:** the block or item placed in the flower slot. Ordinary static flowers are reused.
- **flowerTag:** a tag ID without #; see mixed_tag.
- **include_pb_outputs:** false by default. true also runs the matching ordinary PB centrifuge recipe.

These built-in adapters are examples, **not a resource whitelist**:

- chemical: Mekanism; ME bridge: Applied Mekanistics.
- energy: FE; ME bridge: Applied Flux.
- source: Ars Nouveau; ME bridge: Ars Énergistique.
- soul: Industrial Foregoing Souls; ME bridge: Soulplied Energistics.
- air: PneumaticCraft; ME bridge: Applied Pneumatics.
- emc: ProjectE, automatic player account credit; no EMC-ME addon required.
- experience: ExperienceLib; ME bridge: Applied Experienced.
- lightning: AE2 Lightning Tech, with storage for the matching voltage.
- ae_key: other valid registered AE2 keys, using their provider's storage and complete identity format.

All ME routes need AE2 and suitable storage. 1000 raw air units equal 1 L; bar measures pressure, not output quantity. Seal pneumatic leaks. XP and Source do not directly change player values.

EMC defaults to the machine owner's ProjectE account and synchronizes transmutation screens without ME storage. The Recipient button appears only with ProjectE and FTB Teams. The owner or an operator can click Recipient in the resource window to cycle online teammates, even with no pending inventory. Offline, removed or unavailable recipients leave EMC in the machine until eligible again. Unknown writes are quarantined; notification retries never add EMC again.

### Container flowers: match the contents

Genesis mechanical apiaries and their factories can use filled resource items as reusable flowers. An ordinary item flower checks the item ID; a container flower also checks its contents, rejecting empty containers, the wrong resource and insufficient amounts.

JEI uses an AE2 cell to show the actual resource and minimum. A compatible display cell contains the resource when its bridge is available. Gas buckets, Mekanism chemical tanks and other capable containers match their contents without consumption or pooling.

When an extended resource has no usable output or its pending allowance is full, mechanical centrifuges move the original combs into persistent return custody, send them back when ME accepts them and pause new pulls of that input. Output recovery resumes pulling automatically. Paid cycles retain their recipe, seed and complete input identity; unknown consumption or acceptance remains quarantined without replay.

Add **flowerResource** to the bee JSON. Set kind and the string amount, which is a minimum and defaults to "1". Remove flowerBlock, flowerItem, flowerTag and flowerFluid from that definition. Inverse flowers and post-pollination callbacks are unsupported; conflicting or malformed definitions cannot work.

- **Chemicals:** kind chemical, an id such as mekanism:hydrogen, and amount such as "1000". Use a bucket, tank or other item exposing Mekanism's chemical capability. See the [hydrogen container bee](https://github.com/Ayoshiko/productive-bees-genesis/blob/main-neo/1.21.1/examples/resource-bees/datapack/data/example/productivebees/container_hydrogen.json).
- **Fluids:** kind fluid, an id such as minecraft:water, and amount such as "1000". NeoForge fluid-capability items and traditional buckets are accepted. See the [water container bee](https://github.com/Ayoshiko/productive-bees-genesis/blob/main-neo/1.21.1/examples/resource-bees/datapack/data/example/productivebees/container_water.json).
- **Energy:** kind energy and energy_type FE. Standard FE items and Mekanism energy items using the current conversion are supported. See the [energy container bee](https://github.com/Ayoshiko/productive-bees-genesis/blob/main-neo/1.21.1/examples/resource-bees/datapack/data/example/productivebees/container_energy.json).
- **Other AEKey resources:** kind ae_key with the actual type, id and complete data. Insert a real ME storage cell containing that resource. Checking the cell does not require a network connection. See the [ME cell flower bee](https://github.com/Ayoshiko/productive-bees-genesis/blob/main-neo/1.21.1/examples/resource-bees/datapack/data/example/productivebees/container_ae_key.json).

Fluid and chemical IDs may also be resource tags prefixed with #. Existing flowerFluid definitions remain valid and now accept fluid-capability items beyond buckets; their minimum is any positive matching fluid quantity.

**Pollination does not extract resources or consume containers.** Check one item, without multiplying by stack count or combining feeder slots. A native container needs one internal tank meeting the minimum, with at most 64 tanks inspected. ME cells use the available amount of one complete key. Bee genes and upgrades do not increase the flower requirement.

Emptying a container or changing its contents stops pollination by the next real server tick. Disabled slots stop matching immediately. Resource packs change appearance, not resource identity.

Wrapped resource icons, display templates and remote-storage cards are not real local inventory. Other mods' native items must expose a supported capability; resources with registered AEKeys can use their real storage cells. New flowerResource definitions currently work only in Genesis mechanical hosts, not native PB or JDTE machines.

Add both language keys when creating a bee. KubeJS recipes are unchanged; flowerResource stays in the data-pack bee JSON.

### 7. KubeJS and combined centrifuging

Open the [script examples](https://github.com/Ayoshiko/productive-bees-genesis/tree/main-neo/1.21.1/examples/resource-bees/kubejs/server_scripts). Keep bee definitions in the data pack. Copy selected scripts into kubejs/server_scripts, remove the recipe JSON they replace and run /reload. Choose one registration method per recipe to avoid same-ID overrides.

Alternatively, follow the PB author's template: copy the pack's data into kubejs/data and its assets into kubejs/assets, retaining namespace folders. Every client needs those assets. Do not also enable a duplicate data pack.

hydrogen.js replaces two hydrogen recipes; energy.js demonstrates the direct and comb routes. Quantities remain strings.

combined uses include_pb_outputs true with a matching PB centrifuge recipe. One input and payment cover both outputs; a missing ordinary recipe pauses processing. Essence Conversion precedes Raw Ore Smelting. Output beyond three slots remains held in the machine.

Existing productivity, comb-block, gene-sampling and upgrade rules still apply. Pure resource bees do not gain default honey without ordinary output.

### 8. Generic output registration and other machines

[ae_key_production.json](https://github.com/Ayoshiko/productive-bees-genesis/blob/main-neo/1.21.1/examples/resource-bees/datapack/data/example/recipe/ae_key_production.json) demonstrates diamonds with AE2's built-in ae2:i key. For another mod's resource, supply its actual registered type, id, complete data and string amount.

Complete identity is limited to 1024 bytes and must round-trip. Missing-content placeholders are rejected. Generic keys export to ME; they do not automatically provide the resource mod's native capability. Wrapped items are display values or templates, never inventory credits.

The [Java example](https://github.com/Ayoshiko/productive-bees-genesis/blob/main-neo/1.21.1/examples/resource-bees/api/ResourceBeeApiExample.java) registers outputs with RegisterResourceRecipesEvent. Custom resources with valid AEKeys can use generic registration too. Other machines integrate through RegisterResourceHostsEvent and ResourceHostApi. Same-ID data recipes override Java defaults.

Machine integrations reserve, debit and commit on the server thread. transfer returns actual acceptance. Unknown external results pause instead of being retried as zero. The repository README contains the compile command.

Useless 2.4.6 uses its existing native recipe API without an extra patch. Use a PB centrifuge as the alloy-furnace mold and matching combs / comb blocks as input; its own parallelism, catalysts, fees and output rules apply. AE2, a key bridge and storage are required. Only deterministic independent resource recipes are converted, excluding random and PB-combined recipes. Official JDTE 0.6.4.3 still lacks a bee-resource recipe hook. The local API v3 patch supplies only recipe results; the Bio Factory owns fees, its existing pending-cycle buffer and AE output upgrade delivery, without Genesis accounts or logistics. Rejected outputs remain pending and unknown acceptance pauses retry. The PR is planned after the Genesis release.

### 9. Troubleshoot in order

1. **Missing bee or JEI recipe:** check folders, IDs and JSON; re-enter after adding a bee.
2. **Raw translation key:** enable client names, verify the key and reload with F3+T.
3. **Waiting for flowers:** check slot, switch and flower. Hydrogen uses iron; FE and lightning use copper.
4. **No ME output:** check machine output, channels, power, key provider and storage.
5. **Local storage full:** repair export; inputs or paid output stay held.
6. **Paused for review:** preserve the save and reconcile both ends before operator recovery; do not delete accounts or repeatedly grant output.

Operators inspect resource accounts with /pbgresources and ordinary output with /pbgoutput. retry-data rereads retained data after dependencies return; confirm-production-settled grants no compensation. Normal saves and upgrades retain assets.

Legacy .session markers no longer pause accounts. Unknown production stays in its original lane and unknown output in its resource; healthy work continues. Missing-resource combs are excluded from Myriad and machine inputs; existing combs move to return custody until export resumes. EMC uses Project Expansion's short format when installed, while its integer value remains exact. Real save failures and corrupt data still need review. Separate stores do not commit atomically.
