---
navigation:
  parent: upgrades/upgrades-index.md
  title: Filters
  icon: "minecraft:hopper"
  position: 2
---
# Filters

<ItemImage id="minecraft:hopper" scale="2" />

Filters are allow/deny lists used by Myriad Creations selection and AE2 centrifuge input pulling.

- **Disabled** ignores the list.
- **Blacklist** rejects listed entries.
- **Whitelist** accepts only listed entries; an empty whitelist accepts nothing.

Use localized search in the Myriad filter editor when possible. Drag entries to reorder, uncheck to suspend, and press Save after imports. Registry IDs are mainly for modpack authors.

## AE2 centrifuge input

<ItemGrid>
  <ItemIcon id="productivebees:configurable_honeycomb" components="productivebees:bee_type='productivebees:myriadcreations'" />
  <ItemIcon id="minecraft:raw_iron" />
  <ItemIcon id="minecraft:iron_ingot" />
  <ItemIcon id="ae2:item_storage_cell_1k" />
</ItemGrid>

In the centrifuge [AE2 Input window](upgrades/ae2-input.md):

1. Start with a whitelist (an empty whitelist blocks everything).
2. Mark one comb in a marker cell as a test.
3. Turn **precise mode** on: combs and comb blocks match separately. With it off they share one quota.
4. Keep **NBT matching** (`N:off`); a configurable comb's bee type lives in its data, and ignoring it picks the wrong recipe.
5. Set a small pull amount and a network reserve (a reserve higher than the network stock pulls nothing at all).
6. Only once processing and output return both work, add more entries.

Every button and click combination is documented in [AE2 Input Window](upgrades/ae2-input.md).

Advanced smelting filters support `&`, `|`, `^`, `!`, parentheses, tags, and `*` wildcards. For example, `#c:ores & !#c:ores/iron` excludes iron ores. Invalid expressions reject only that candidate.
