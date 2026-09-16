---
navigation:
  parent: getting_started/index.md
  title: Myriad Creations Bee
  icon: "productivebees:spawn_egg_configurable_bee"
  icon_components:
    "minecraft:entity_data":
      id: "productivebees:configurable_bee"
      type: "productivebees:myriadcreations"
  position: 2
item_ids:
  - productivebees:spawn_egg_configurable_bee
  - productivebees:configurable_honeycomb
  - productivebees:configurable_comb
---
# Myriad Creations Bee

<ItemImage id="productivebees:spawn_egg_configurable_bee" components="minecraft:entity_data={id:'productivebees:configurable_bee',type:'productivebees:myriadcreations'}" scale="2" />

Myriad Creations selects an allowed registered resource-bee type for each output, then resolves that bee's real advanced-beehive product. Most bees use a typed configurable comb; bees with independent comb items, such as Ghostly, Milky, and Powdery, keep their own real comb.

<ItemGrid>
  <ItemIcon id="productivebees:configurable_honeycomb" components="productivebees:bee_type='productivebees:myriadcreations'" />
  <ItemIcon id="productivebees:configurable_comb" components="productivebees:bee_type='productivebees:myriadcreations'" />
  <ItemIcon id="productivebees:spawn_egg_configurable_bee" components="minecraft:entity_data={id:'productivebees:configurable_bee',type:'productivebees:myriadcreations'}" />
</ItemGrid>

## Survival acquisition

By default, item conversion and breeding are enabled, while fishing and nest spawning are disabled. The example conversion turns a vanilla bee with a stick; modpacks can replace the item, chance, parents, nest, and biome rules. Check **Gameplay Settings → Bee Acquisition** instead of assuming that every pack uses the defaults.

<ItemGrid>
  <ItemIcon id="minecraft:bee_spawn_egg" />
  <ItemIcon id="minecraft:stick" />
  <ItemIcon id="productivebees:honey_treat" />
</ItemGrid>

## Resource filter

- **Disabled** allows every otherwise valid candidate.
- **Blacklist** excludes listed bees.
- **Whitelist** allows only listed bees; an empty whitelist allows nothing.

Use the localized search-and-select editor when possible. Registry IDs such as `modid:bee_name` are mainly useful for modpack authors importing lists.

## Typed combs and blocks

<ItemImage id="productivebees:configurable_honeycomb" components="productivebees:bee_type='productivebees:myriadcreations'" scale="2" />

For configurable combs, normal machine and network transport preserves bee-type data. A blank configurable honeycomb without that data may look similar but cannot select the correct recipe. Independent comb items identify themselves and are never replaced with a blank configurable comb. The Comb Block upgrade uses Productive Bees' matching real block where one exists, and centrifuges process it directly.

Rainbow, particle, glow, and color settings are client visuals. Production rules and the candidate list remain server-authoritative.
