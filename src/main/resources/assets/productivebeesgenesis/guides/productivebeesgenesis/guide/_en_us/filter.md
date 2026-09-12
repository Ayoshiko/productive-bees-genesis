---
navigation:
  parent: index.md
  title: Filters
  icon: "minecraft:hopper"
  position: 6
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

Start with a whitelist containing one comb, exact matching enabled, a small pull amount, and a network reserve. Increase the list only after processing and output return both work.

Advanced smelting filters support `&`, `|`, `^`, `!`, parentheses, tags, and `*` wildcards. For example, `#c:ores & !#c:ores/iron` excludes iron ores. Invalid expressions reject only that candidate.
