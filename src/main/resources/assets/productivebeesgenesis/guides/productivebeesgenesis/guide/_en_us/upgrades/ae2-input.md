---
navigation:
  parent: upgrades/upgrades-index.md
  title: AE2 Input Window
  icon: "ae2:interface"
  position: 3
item_ids:
  - ae2:interface
  - ae2:pattern_provider
  - ae2:item_storage_cell_1k
---
# AE2 Input Window

![AE2 input pull configuration](../assets/images/gui_ae2_input.png)

The **AE2 Input window** lets the Mekanism Centrifuge **pull materials out of an ME network on its own**. It has more buttons than any other window in this addon, so it gets a page of its own.

> Requirements: AE2 installed; the machine attached to a **powered ME network with a free channel and storage space**; and the server-side **global AE2 input pull** option enabled.

## Opening it

1. Open the centrifuge main screen.
2. Click the **side configuration** tab at the top left.
3. Switch to the **Items** page (this button only appears there).
4. Click the **`I`** button in the second cell of the left column (tooltip: "AE2 input config").

The window opens as a floating layer over the machine screen, titled **"AE2 Input Pull Configuration"**.

**Closing**: click the **×** at the top left, or press **Esc** — Esc closes the topmost window, but a **pinned** window will not close until you unpin it.

## Window layout

The window is arranged top to bottom: a **title bar** (close button, pin, draggable title), a full-width **control row** (seven function buttons on the left, paging and clearing on the right), the **filter cells** in the middle, and a narrow **status panel** down the right side.

The window is a fixed 260×200 with **2 groups of 9 cells = 18 cells per page**. Each cell stacks three elements, counted from the bottom up:

- **Gear** (16×16): this cell's pull amount and stock policy.
- **Marker slot** (18×18): the item template you want to pull. No real item is consumed.
- **Network output slot** (18×18): direct access to that item's stock in the ME network.

## Control row: the seven buttons on the left

All of them are **grey with a single label** and send one request to the server per click.

### `I` — pull master switch

- The label toggles between **`In:on`** and **`In:off`**.
- Tooltip: **Pull toggle: enable/disable AE2 input pulling**
- When off, the centrifuge pulls nothing regardless of the filter list.

### `N` — NBT ignore

- The label toggles between **`N:on`** and **`N:off`**.
- Tooltip: **NBT ignore: ignore/match item NBT data**
- **`N:off` (match)**: items with different NBT counts as different entries. Configurable combs store their bee type in data, so **keep matching** for resource bees.
- **`N:on` (ignore)**: only the item itself matters, not its data. Use it only when you are certain the data does not change the recipe.

### `F` — filter mode

- The label cycles through **`Fl:off` / `Fl:wh` / `Fl:bl`**.
- Tooltip: **Filter mode: cycle whitelist/blacklist/disabled**

| Mode | Label | Meaning |
| --- | --- | --- |
| Disabled | `Fl:off` | The list is ignored; every other condition still applies |
| Whitelist | `Fl:wh` | **Only** listed entries may be pulled |
| Blacklist | `Fl:bl` | Listed entries are **not** pulled; everything else is |

> **Empty-list trap**: with a whitelist an empty list means **nothing can be pulled**; with a blacklist an empty list means **everything can**. Check this first when a filter appears to do nothing.

### `P` — precise mode

- The label toggles between **`Pr:on`** and **`Pr:off`**.
- Tooltip: **Precise mode: when on, combs and comb blocks are distinguished (only marked items are pulled); when off, filtering is shared (combs and comb blocks are pulled together)**
- **`Pr:on`**: combs and comb blocks are separate; only the form you marked is pulled.
- **`Pr:off`**: combs and comb blocks **share one quota**, matched by bee type — marking a comb also pulls the corresponding comb block.

> Note: **the per-cell gear, stock reserve, unlimited pull, and the network output row only render for exact-fingerprint entries.** If you only see empty grey cells with no gear, that cell has no successful fingerprint entry — just drag the item in again.

### `⚙` — global gear

- Tooltip: **Click: set the pull amount for all direct entries / Shift-click: toggle full unlimited pull for every filtered comb**
- **Left-click**: opens "Set all pull amounts" and changes every entry at once.
- **Shift-left-click**: toggles **unlimited all** (the "Unlimited all" line on the panel turns green).
- The icon is a dim gear normally and brightens on hover or when the feature is on; with unlimited-all enabled a green **∞** appears on it.
- This button needs **no marker item** to be usable.

### `Stock` — global stock mode

- Tooltip: **Left-click: set the default reserve / Right-click: toggle global stock mode / Green = on, grey = off; direct entries can override it individually**
- **Left-click**: opens the reserve editor for the global default.
- **Right-click**: toggles **global stock mode**. The small bar at the bottom of the button is **green when on, grey when off**.
- When on, every entry without its own reserve falls back to this global reserve.

### `T` — tag filter

- Tooltip: **Tag filter: filter AE2 input candidates by tag expression; the F mode only controls the white/black list of marked items. Supports `&|^!()` and `*` wildcards, and item ids can be written directly**
- Left-click opens the tag expression window, covered later on this page.

## Control row: paging and clearing on the right

- **`◀` Previous page**: previous page of cells. Paging **wraps**: going back from page 1 jumps to the last page.
- **`C` Clear all filter entries**: clears every entry, unlimited flag and reserve at once, and returns to page 1. **There is no confirmation — click with care**.
- **`▶` Next page**: next page of cells.

> Cells use **fixed positions**: cell M of page N always maps to the same index. Paging never reorders entries, so you can deliberately park different kinds in fixed cells.

## Using each cell

### Marker slot (middle row)

| Action | Effect |
| --- | --- |
| **Left-click** an empty cell while carrying an item | Marks that item into the cell |
| **Right-click** an empty cell while carrying an item | Same — either button marks |
| **Empty-handed left- or right-click** on a filled cell | Removes that entry |
| **Drag an item in from JEI** | Marks it into the cell under the cursor |
| Hover | Shows the item name for that cell |

**What can be marked**: comb-type items (including configurable combs and comb blocks). In addition, ordinary items that **have a Mekanism smelting recipe on the client** can be marked too (used together with smelting compatibility).

**What a grey cell means**: if this cell's item has **no recipe on this centrifuge** (for example a comb that belongs to another mod's machine, such as a chemical oxidizer, dissolver, or electrolyzer), the icon gains a **translucent dark-grey overlay** and hovering appends red text:

> **§cThis centrifuge has no recipe for it§r**
> This kind of comb has to be processed by another mod's machine (chemical oxidizer, dissolver, electrolyzer, and so on).
> This machine skips it on purpose, so this entry will never take effect — pulling it in only wastes performance.

Seeing this means **your configuration is correct and the machine simply cannot process it** — switch machines or entries instead of retuning parameters.

### Gear (top row)

Gears only appear when the entry is an **exact fingerprint entry**. Tooltip (verbatim):

> **Actual stock mode: on/off**
> **Unlimited pull: on/off**
> **Left-click: set the pull amount**
> **Right-click: toggle this entry's mode**
> **Shift+left-click: toggle unlimited pull**
> **Shift+right-click: set this entry's reserve**
> **Pull:X Stock:Y Reserve:Z**

| Click | Effect |
| --- | --- |
| **Left-click** | Opens this entry's "set pull amount" window |
| **Right-click** | Toggles this entry's **stock mode** (whether the reserve applies) |
| **Shift + left-click** | Toggles this entry's **unlimited pull** |
| **Shift + right-click** | Opens this entry's "set stock reserve" window |

The gear's look follows its state:

- Dim normally; **bright** on hover or when the entry has stock mode or unlimited pull on.
- With **unlimited pull** a **green ∞** is drawn on it.
- With **stock mode** a **small yellow bar** appears below it.

### Network output slot (bottom row)

This cell proxies that item's stock in the ME network. Tooltip (verbatim):

> **Network output: X**
> **Left-click: take to cursor / insert all**
> **Right-click: take half / insert 1**
> **Shift+left-click: take to inventory**

The amount is drawn on the cell in compact form (K/M/G/T). Use this row to borrow items from the network for testing, or to push surplus back in.

> While the window is open and exact entries exist, the client **requests a stock refresh from the server every 20 ticks (about once a second)** to update these numbers. On very large networks this has a small cost.

## The info panel on the right

The dark panel lists one state per line, top to bottom:

| Line | Values | Meaning |
| --- | --- | --- |
| Mode | Disabled / Whitelist / Blacklist | Current filter mode |
| Input: on / Input: off | — | Pull master switch |
| NBT: ignore / NBT: match | — | Whether NBT participates in matching |
| Precise: on / Precise: off | — | Precise mode state |
| Page 1/2 | — | Current page / total pages (the total respects the configured minimum page count) |
| Entries N | — | Number of active entries |
| Rate: N/t | — | Configured pull rate per tick |
| Interval: Nt | — | Configured pull interval |
| Cooldown: Nt | — | **Current adaptive cooldown**, see below |
| Unlimited: N | — | Number of entries with unlimited pull enabled |
| Unlimited all: on / off | **green** when on | The global gear toggle |
| Global stock: on/off, reserve: N | **green** when on | Global stock mode and global reserve |
| Tag filter: on / off | **green** when on, **red when the expression is invalid** | Whether the tag expressions are active |

### Why "Cooldown" changes

Pulling uses an **adaptive cooldown** rather than hammering the network every tick:

- A successful pull returns the cooldown to **5 ticks** (or **1 tick** while unlimited entries are active).
- A pull that cannot meet its quota (the network is replenishing slowly) backs off to **10 ticks** to avoid useless scans.
- A pull that gets nothing lengthens the cooldown by **1 tick at a time up to 40** in unlimited mode; in normal mode it stays at **5 ticks** or above.

So "Cooldown: 40t" usually means **there is nothing pullable in the network**, not that the machine is broken. Check whether the comb exists and whether the reserve is set too high.

## Pull amount and stock reserve

### Setting the pull amount (`⚙` left-click / gear left-click)

![Set pull amount](../assets/images/gui_ae2_input_amount.png)

The window title is **Set Pull Amount** (**Set All Pull Amounts** from the global gear), with the subtitle **Pull amount per operation (0-N)**.

| Element | Action |
| --- | --- |
| 8 step buttons | Top row `+1 +10 +100 +1000`, bottom row `-1 -10 -100 -1000` |
| **Hold Shift or Ctrl** | Steps become `+1 +16 +32 +64` / `-1 -16 -32 -64` (binary steps, better for large values) |
| Text field | Type a number directly; **expressions** are supported with `+ - * / ^ ( ) .` and spaces |
| Scroll wheel over the field | ±1 per notch |
| Item icon on the left | The item this entry refers to (hidden in global mode) |
| **Set** button / Enter | Apply and close |

**Invalid input turns the text red**; hover it to see why:

- Invalid amount expression
- Amount must be an integer
- Amount cannot be less than 0
- Amount cannot exceed N

The valid range is **0 to the server maximum**. `0` means "pull nothing" while keeping the entry.

### Setting the stock reserve (`Stock` left-click / Shift+right-click a gear)

![Set stock reserve](../assets/images/gui_ae2_input_reserve.png)

The title is **Set Stock Reserve** (**Set All Stock Reserves** in global mode) with the subtitle **Stock reserve (0-N)**.

**What a reserve means**: the network keeps **at least this many** of the item, so the centrifuge never drains it dry. The pullable amount is `network stock − reserve`.

- Reserve 64 with 100 in the network → at most 36 can be pulled.
- Reserve 64 with only 30 in the network → **nothing can be pulled**.
- This is one of the most common reasons "the machine is not taking materials": **the reserve is higher than the network stock**.

Keep at least a few, so the last emergency stack or crafting template is never taken.

## Tag expression window (`T`)

![AE2 input tag filter](../assets/images/gui_ae2_input_filter.png)

Titled **AE2 Input Tag Filter**. It adds a tag/item-id sieve on top of **all input candidates**, layered on top of the whitelist/blacklist above.

### Window contents

- **Whitelist tag expression / Blacklist tag expression**: two input lines. Which one is in effect, and in what role, depends on the target switch.
- **Legend**: `&` AND, `|` OR, `^` XOR, `!` NOT; `()` for precedence, `*` wildcard; a literal is a tag id or an item id; **empty = unrestricted**.
- **Tag sample slot**: click items from the inventory / JEI into it, or **empty-hand click** to take the main-hand item; empty-hand click again to clear.
- **Search box**: filters the tag list below as you type (**display only; the expression is unchanged**).
- **Tag list**: tags on the sample item. **Single-click a row to add / remove it on the current target side**; **wheel to scroll**.
- **Target switch**: `Target: whitelist expression` ↔ `Target: blacklist expression`, click to switch.
- **Join switch**: `Match any tag (joined with |)` ↔ `Match all tags (joined with &)`, click to switch.
- **Save**: writes the expression and takes effect.

- With an empty sample slot the list says "Put an item in the slot to list its tags"; an item with no tags says "This item has no tags"; an unmatched search says "No matching tags".

### Expression syntax

- `&` both, `|` either, `^` exactly one, `!` exclude.
- `()` groups, `*` matches any run of characters.
- You can write **item ids** (`minecraft:iron_ore`) or **tag ids** (`#c:ores`) directly.
- Example: `#c:ores & !#c:ores/iron` means "is an ore tag, but not iron ore".
- **An empty expression means unrestricted.**

### When an expression is wrong

A bad expression **only disables that side**; other entries keep working, and the "Tag filter" line on the panel turns **red**. Possible messages:

| Message | Meaning |
| --- | --- |
| Expression too long | 512 characters maximum |
| Expression too complex | 64 nodes maximum |
| Parentheses nested too deeply | 16 levels maximum |
| Unclosed parenthesis | A missing `)` |
| Expression ended unexpectedly | An operator with nothing after it |
| Unrecognised symbol | An illegal character |
| Invalid tag/item id | Bad id format, or too many wildcards |

> **The `F` filter mode only governs the white/black list of marked items; the `T` tag expressions constrain all input candidates.** They act on different scopes — do not confuse the two.

## Full operation reference

| Location | Action | Effect |
| --- | --- | --- |
| Anywhere | **Esc** | Closes the topmost window (no effect on a pinned window) |
| Title bar | Drag | Moves the window; the position is remembered |
| Title bar pin | Left-click | Pin / unpin |
| Control `I` | Left-click | Pull master switch |
| Control `N` | Left-click | NBT ignore / match |
| Control `F` | Left-click | Cycle filter mode |
| Control `P` | Left-click | Toggle precise mode |
| Control `⚙` | Left-click / Shift+left-click | Set all pull amounts / toggle unlimited all |
| Control `Stock` | Left-click / right-click | Set the global reserve / toggle global stock mode |
| Control `T` | Left-click | Open the tag expression window |
| Control `◀` `C` `▶` | Left-click | Previous page / clear all / next page |
| Marker slot | Left- or right-click with an item | Mark the entry |
| Marker slot | Empty-handed left- or right-click | Remove the entry |
| Marker slot | JEI drag | Mark the entry |
| Gear | Left-click | Set this entry's pull amount |
| Gear | Right-click | Toggle this entry's stock mode |
| Gear | Shift+left-click | Toggle this entry's unlimited pull |
| Gear | Shift+right-click | Set this entry's reserve |
| Network output slot | Left-click / right-click / Shift+left-click | Take to cursor (insert all) / take half (insert 1) / take to inventory |
| Amount window | Step buttons / Shift+step / wheel / Enter | Adjust the amount (Shift or Ctrl uses 16/32/64 steps) / ±1 / apply |

## Pitfalls and common problems

### The button is missing

| Symptom | Cause |
| --- | --- |
| No `I` button in side configuration | AE2 is not installed (the button is never created), or you are not on the **Items** page |
| The `I` button is grey with "Enable global AE2 input pulling in the config to use this feature" | The server master switch is off |
| Not even the `A` output button exists | Again, no AE2 |

### Configured but nothing is pulled

Check in this order:

1. **Is `I` showing `In:off`?** The master switch is off.
2. **Does the network actually contain that comb?** Look in a terminal.
3. **Is the reserve higher than the network stock?** The most easily missed cause, described above.
4. **Filter mode and list** — an empty whitelist, or a blacklist hiding what you want.
5. **Precise mode versus the marked form** — you marked a comb block but only combs are produced (or the reverse).
6. **Is the cell grey?** The machine has no recipe for that comb and skips it on purpose.
7. **Is the cooldown stuck at 40t?** That means nothing has been pulled for a while; fix the causes above first.

### Pulling too much and flooding the machine

- **Lower the pull amount** (1–4 is a good starting range), or leave the default.
- **Unlimited pull** tries to keep the machine topped up — **use it carefully on large networks**; the Shift-left-click "unlimited all" is even more aggressive.
- When the network starts stuttering, lower the pull amount or lengthen the pull interval before adding parallelism.

### Network-side notes

- The machine must sit on a network with **power, a free channel, and storage space**; when the network drops, the machine pauses safely and keeps its contents.
- The machine also **pushes products** to the network, so leave room on the output side, otherwise products fall back to local output slots and may clog.
- A configurable comb's bee type determines its recipe, so **generally do not turn on `N:on` (ignore NBT)**.

### The window disappeared

After a resolution or GUI scale change the window can end up off screen: mod config → **Client Settings → Custom Window Positions** → reset `window_ae_input`. See [Common Screens and Controls](../gui.md).

## Related pages

- Centrifuge main screen and the position of the `I` button: [Mekanism Centrifuge](../machines/centrifuge.md)
- Blacklist / whitelist basics and the Myriad Creations filter: [Filters](../filter.md)
- One-shot setup advice and getting started with AE2: [Upgrade and Automation Details](automation.md)
- Server options such as rate, interval, and minimum pages: [Configuration](../configuration.md)
