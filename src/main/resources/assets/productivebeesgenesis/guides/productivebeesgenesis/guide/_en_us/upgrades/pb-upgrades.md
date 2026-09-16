---
navigation:
  parent: upgrades/upgrades-index.md
  title: PB Upgrade Window
  icon: "productivebeesgenesis:essence_conversion_upgrade"
  position: 2
item_ids:
  - productivelib:upgrade_productivity
  - productivelib:upgrade_time
  - productivelib:upgrade_gene_sampler
  - productivelib:upgrade_block
  - productivelib:upgrade_stability
  - productivebeesgenesis:gene_type_only_upgrade
  - productivebeesgenesis:gene_full_purity_upgrade
---
# PB Upgrade Window

![PB upgrade window](../assets/images/gui_pb_upgrades.png)

The **PB Upgrade** window installs Productive Bees upgrades into Mekanism machines. The apiary and the centrifuge share the same window, but **they do not accept the same upgrades**, so this page covers the window itself and then the two machines separately.

## Two separate upgrade systems

Every machine actually has **two** upgrade entry points holding two different sets of items:

| | PB Upgrades (this page) | MEK Upgrades |
| --- | --- | --- |
| Tab colour | Orange | Mekanism default |
| Icon | This addon's upgrade icon | Up arrow |
| Contents | Productive Bees and Productive Bees Genesis upgrades | Vanilla Mekanism upgrades |
| Typical items | Productivity α/β/γ/Ω, Time, Time+, Stability, Gene Sampler, both gene plugins, Comb Block, Raw Ore Smelting, Essence Conversion, Byproduct Destruction | Speed, Energy, Muffling; plus Stack and Creative with addons |
| Typical effects | More output per cycle, shorter cycles, changed product form | Shorter cycles, larger energy buffer, less noise |

Both can be installed **at the same time and their effects stack**; neither consumes the other's slots.

## Opening it

Click the orange **PB Upgrades** tab on the right of the machine window (its tooltip reads "Productive Bees upgrades"). The window opens centred over the machine screen with the title "Productive Bees upgrades".

## Window layout

- **Title bar**: at the top — close button and pin on the left, "Productive Bees upgrades" in the middle; drag it to move the window.
- **Upper left: installed upgrade list**: only installed upgrades, one row per type, with a scrollbar when they do not fit.
- **Upper right: info screen**: name, count and description of the selected upgrade.
- **Lower half**: supported upgrade types on the left; install progress bar, Uninstall button and input/output slots on the right.

### Title bar

- The **×** at the top left closes the window. Installed upgrades stay installed.
- The **pin** keeps the window from following the main screen.
- Drag the title bar to move the window; the position is remembered (see "A window wandered off screen" in [Common Screens and Controls](../gui.md)).

### Upper left: installed upgrade list

This column lists **only installed** upgrades, one row per type:

- Each row shows the upgrade's item icon on the left and its name on the right.
- The row background uses **that upgrade type's colour**, so tiers are easy to tell apart.
- **Hovering a row** shows "upgrade name (in its colour) + effect description".
- **Left-clicking a row selects it**: the row switches to the selected style and the **Uninstall button becomes available**. With nothing selected the button is greyed out and does nothing.
- **Clicking empty space in the list deselects.**
- When there are more upgrades than fit, a **scrollbar appears on the right**: scroll the mouse wheel, or **drag the 4×4 handle**.
- If the selected type drops to 0 installed, the selection clears automatically and the info screen returns to "Nothing selected".

### Upper right: info screen

With a row selected this shows three things:

1. **The upgrade name** (in its type colour, wrapping if long)
2. **`installed / limit`**, for example `3 / 8`
3. **The effect description**, for example "Increases comb output"

With nothing selected it shows **Nothing selected**.

### Lower right, upper cell: install progress bar

Put the upgrade item into the **input slot** (the cell above the Uninstall button) and a short **install animation** plays here. When it completes the item leaves the input slot and the installed count increases by one.

### Lower right: Uninstall button

- Labelled **Uninstall**; its tooltip reads **"Uninstall the selected upgrade (Shift-click to uninstall all)"**.
- **Left-click removes one**: one item of the selected type is ejected into the **output slot** below.
- **Shift-left-click removes all** of that type at once.
- With nothing selected the button is greyed out and inert.
- Uninstalled items appear in the **output slot** directly below the button — remember to take them out.

> Uninstalling is **instantaneous** with no animation. If nothing seems to happen, check the output slot rather than clicking again.

### Lower left: supported list

The **Supported:** label is followed by a row of **12×12 icons** showing **which upgrades this machine accepts**.

- Hovering an icon shows "upgrade name (type colour) + effect description".
- **A grey overlay means unsupported**: hovering turns the name into a red **"XXX (unsupported)"** while still showing the description.
- This row is the authoritative answer to "can this machine take it?" The two machines differ; see the table below.
- Apiaries also show **Simulation ✓** here — it is **built in**, needs no item, and takes no slot.

## Apiary vs centrifuge: what each accepts

This is the easiest thing to get wrong. The same window accepts different sets on each machine:

**Works on both machines**

- **Productivity α / β / γ / ω**: increases comb output per cycle (PB default factor 1.2 / 1.5 / 2.0 / 2.6); ω **also enables comb-block mode**.
- **Time / Time+**: cuts cycle time by **15%** / **30%** per level, raising power draw.
- **Byproduct Destruction**: the apiary discards honey-type byproducts and pollen puffs; the centrifuge discards PB honey fluid and beeswax. **Main products are untouched**.
- **Essence Conversion**: compacts low-tier products that have a single-ingredient reverse recipe.

**Apiary only**

- **Gene Sampler**: samples bee genes.
- **Gene Type Filter**: makes the sampler output only bee type (TYPE) genes; maximum one per apiary.
- **Full Purity Gene**: makes every sampled gene 100% pure; maximum one per apiary.
- **Comb Block**: turns comb output into comb blocks to save output slots.
- **Simulation**: ships with apiaries; takes no slot and needs no item.

Both gene plugins require a **Gene Sampler** to do anything and may be installed together. The combination outputs only bee type genes, all at 100% purity. Each plugin has its own limit of one and does not consume the sampler's install limit.

<RecipeFor id="productivebeesgenesis:gene_type_only_upgrade" fallbackText="This modpack replaced or disabled the Gene Type Filter recipe. Search JEI." />

<RecipeFor id="productivebeesgenesis:gene_full_purity_upgrade" fallbackText="This modpack replaced or disabled the Full Purity Gene recipe. Search JEI." />

**Centrifuge only**

- **Stability**: **+15%** chance for non-guaranteed byproducts per level.
- **Raw Ore Smelting**: smelts centrifuge raw-ore output straight into ingots.

Install one on the wrong machine and its icon is simply **grey** in the supported list; dropping the item into the input slot will not install it.

## How many of each you can install

Each type has its own **limit**, set by **server configuration**, and **the apiary and the centrifuge have separate settings**:

| Upgrade | Default limit | Config option |
| --- | --- | --- |
| Productivity α / β / γ / Ω | 8 (each tier counted separately) | `Productivity Upgrade Limit` |
| Time, Time+ | 8 (each counted separately) | `Time Upgrade Limit` |
| Stability (centrifuge only) | 7 | `Stability Upgrade Limit` |
| Gene Sampler (apiary only) | 4 | `Gene Sampler Upgrade Limit` |
| Gene Type Filter, Full Purity Gene (apiary only) | 1 each | — |
| Comb Block (apiary only) | 1 | `Comb Block Upgrade Limit` |
| Byproduct Destruction / Essence Conversion / Raw Ore Smelting | 1 (functional, one is enough) | — |
| Simulation | built into apiaries | — |

The options live in two groups: **Machine Settings → Mekanism Apiary → PB Upgrade Limits** and **Machine Settings → Mekanism Centrifuge → PB Upgrade Limits**. Be clear about which machine you are editing.

Default limits are also **affected by the global balance profile**, described next.

## Balance profiles: Basic / Paradox Infinity / Custom

Under **mod config → Gameplay Settings → Balance Rules** you can switch the global balance profile (click "Global balance profile" to open the list):

- **Basic**: closer to stock Mekanism and Productive Bees. Productivity tiers α/β/γ/Ω are **mutually exclusive**, as are Time I and Time II; centrifuge productivity upgrades **only add parallelism, not output**; apiaries are affected by bee **behaviour and weather-tolerance genes**; resource-bee upgrade limit defaults to **4** and the centrifuge stack upgrade limit to **8**.
- **Paradox Infinity**: all productivity tiers **can coexist**, and so can Time I and Time II; centrifuge productivity upgrades **raise both output and parallelism**; apiaries keep the old behaviour and are **not** affected by behaviour or weather-tolerance genes; resource-bee upgrade limit defaults to **8** and the centrifuge stack upgrade limit to **16**.
- **Custom**: every individual rule and numeric limit is yours to fill in. Switching from Basic or Paradox Infinity **inherits the settings that were actually in effect**.

> **Switching profiles never removes upgrades already installed in a machine.** It can, however, **block installing new upgrades that break the new rules** — for example, after switching from Paradox Infinity to Basic you can no longer mix productivity tiers in one machine, while what is already installed stays as it is.

The four independent Custom rules (tooltips quoted):

- **Productivity tiers exclusive**: prevents productivity α, β, γ, and Ω from coexisting in one machine.
- **Speed tiers exclusive**: prevents Time I and Time II from coexisting in one machine.
- **Centrifuge productivity adds output**: when on, productivity upgrades add output *and* parallelism; when off, only the original Productive Bees parallelism remains.
- **Bee genes affect apiary work**: when on, a bee whose behaviour or weather tolerance does not match the current day/night or weather pauses production.

## Common questions

- **An upgrade does nothing when inserted**: check whether its icon is **grey** in the Supported list. Grey means this machine does not accept it.
- **Count is stuck at `8 / 8`**: the limit is reached. Uninstall some, or raise the limit in the server config.
- **The Uninstall button will not click**: nothing is selected. Click a row in the installed list first.
- **Items vanished after uninstalling**: look in the **output slot below the Uninstall button**.
- **A second productivity tier will not install**: the current profile is Basic, where productivity tiers are mutually exclusive. Switch to Paradox Infinity or use Custom.
- **Apiary bees suddenly stop producing**: under Basic, behaviour and weather-tolerance genes matter. Check the day/night cycle and weather, or switch to Paradox Infinity.
- **Do upgrades survive closing the window?**: yes. They live in the machine, independent of the window.
- **Are upgrades lost when upgrading to a factory?**: no. Factory upgrades keep the bees and the installed upgrades.

## Related pages

- Full apiary interface: [Mekanism Apiary](../machines/apiary.md)
- Full centrifuge interface: [Mekanism Centrifuge](../machines/centrifuge.md)
- Details of the three addon upgrades (Raw Ore Smelting, Essence Conversion, Byproduct Destruction): [Upgrade and Automation Details](automation.md)
- Config entries for limits and balance: [Configuration](../configuration.md)
