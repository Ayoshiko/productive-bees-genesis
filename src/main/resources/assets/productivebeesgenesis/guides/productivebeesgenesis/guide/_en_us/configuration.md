---
navigation:
  parent: index.md
  title: Configuration
  icon: "minecraft:comparator"
  position: 7
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
- **Balance profile**: use Basic for a normal pack, Paradox Infinite for unrestricted high-scale play, and Custom only when every limit is understood.
- **Myriad filter** excludes resources with a blacklist or keeps only selected resources with a whitelist.
- **Acquisition methods** separately control item conversion, breeding, fishing, and natural nest generation.

### Machine Settings

This section controls apiary and centrifuge processing, AE2 integration, upgrade limits, and external logistics.

| Goal | Section | First-play recommendation |
| --- | --- | --- |
| Power and operation time | Apiary/Centrifuge → Base Parameters | Keep defaults |
| Separate centrifuge fluids | Centrifuge → Base Parameters | Keep enabled |
| AE2 item/fluid output | Machine → AE2 Integration | Keep enabled with AE2 |
| Prefer Applied Flux power | Machine → AE2 Integration | Keep preferred with Applied Flux |
| PB upgrade limits | Machine → PB Upgrade Limits | Increase gradually |
| Push to adjacent containers | External Logistics | Enable for simple pipe lines |

AE2 input pulling has a server master switch, but each centrifuge still starts disabled until a player enables its AE2 Input window. This prevents a newly connected machine from draining the network unexpectedly.

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
