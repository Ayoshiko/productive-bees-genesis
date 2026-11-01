package com.ayoshiko.productivebeesgenesis.client.resource;

import com.ayoshiko.productivebeesgenesis.resource.*;
import mekanism.api.MekanismAPI;
import mekanism.api.chemical.*;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.button.MekanismButton;
import mekanism.client.gui.element.gauge.GaugeType;
import mekanism.client.gui.element.text.GuiTextField;
import mekanism.client.gui.element.window.GuiWindow;
import mekanism.common.inventory.container.SelectedWindowData;
import mekanism.common.inventory.container.tile.MekanismTileContainer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import com.ayoshiko.productivebeesgenesis.network.ForgeNetwork;
import java.util.*;

/** 全机共享资源目录；搜索与展示只操作快照，输出动作仍校验完整身份和源代际。 */
public final class GuiResourceWindow extends GuiWindow {
    public static final int WIDTH = 336, HEIGHT = 248;
    private final MekanismTileContainer<?> menu;
    private final AbstractContainerScreen<?> screen;
    private final UUID session = UUID.randomUUID();
    private ResourceInventoryDelta.Receiver inventory = new ResourceInventoryDelta.Receiver();
    private ResourceProductionDelta.Receiver production = new ResourceProductionDelta.Receiver();
    private ResourceProductionSnapshot productionSnapshot = ResourceProductionSnapshot.EMPTY;
    private boolean closed;
    private List<ResourceStack> balances = List.of(), visible = List.of();
    private List<ResourceStack> displayRows = List.of();
    private Map<ResourceKey, ResourceProductionSnapshot.Entry> productionRows = Map.of();
    private final Map<ResourceKey, String> rateLabels = new java.util.HashMap<>();
    private final Map<ResourceKey, Component> rateTooltips = new java.util.HashMap<>();
    private boolean productionView = true;
    private ResourceKey selected;
    private final GuiResourceChemicalGauge gauge;
    private final GuiResourceInset detail, statusHolder;
    private final GuiTextField search;
    private final GuiResourceGrid grid;
    private final MekanismButton toggle, eject, filterButton, sortButton, recipientButton, viewButton;
    private ResourceMenuSync.Reply reply;
    private long sequence, accepted = -1, nextQuery, receivedAt;
    private int filter, sort;

    public GuiResourceWindow(IGuiWrapper gui, int x, int y, MekanismTileContainer<?> menu,
            AbstractContainerScreen<?> screen, SelectedWindowData data) {
        super(gui, x, y, WIDTH, HEIGHT, data);
        this.menu = menu; this.screen = screen; interactionStrategy = InteractionStrategy.ALL;
        recipientButton = addChild(new GuiResourceButton(gui, relativeX + 175, relativeY + 3, 68, 18,
                tr("emc.recipient_short"), (element, mx, my) -> { selectRecipient(); return true; }));
        statusHolder = addChild(new GuiResourceInset(gui, relativeX + 12, relativeY + 50, WIDTH - 24, 14));
        viewButton = addChild(new GuiResourceButton(gui, relativeX + 238, relativeY + 28, 80, 18,
                tr("view.production"), (element, mx, my) -> { productionView = !productionView; rebuild(); return true; }));
        search = addChild(new GuiTextField(gui, relativeX + 34, relativeY + 70, 112, 18));
        search.setBackground(mekanism.client.gui.element.text.BackgroundType.NONE).setOffset(0, 0).setResponder(text -> rebuild());
        search.setMaxLength(64); search.setTextColor(0xFFFFFF);
        search.setTooltip(Tooltip.create(tr("search_hint")));
        var clear = addChild(new GuiResourceButton(gui, relativeX + 151, relativeY + 73, 13, 12,
                Component.empty(), (element, mx, my) -> { search.setText(""); return true; }) {
            @Override public void renderForeground(GuiGraphics graphics, int mx, int my) {
                ResourceGuiTextures.sprite(graphics, relativeX - 1, relativeY - 1, 160, 0, 16, 16);
            }
        });
        clear.setTooltip(Tooltip.create(tr("clear_search")));
        filterButton = addChild(new GuiResourceButton(gui, relativeX + 173, relativeY + 70, 70, 18,
                tr("filter.all"), (element, mx, my) -> { filter = (filter + 1) % 3; rebuild(); return true; }));
        sortButton = addChild(new GuiResourceButton(gui, relativeX + 247, relativeY + 70, 72, 18,
                tr("sort.name"), (element, mx, my) -> { sort = (sort + 1) % 3; rebuild(); return true; }));
        grid = addChild(new GuiResourceGrid(gui, relativeX + 17, relativeY + 94, () -> visible, () -> selected,
                key -> { selected = key; refresh(); }, this::emptyMessage,
                value -> productionView ? rateCompact(value.key()) : ResourceVisuals.compactAmount(value.key(), value.exactAmount()),
                value -> productionView ? productionTooltip(value.key()) : ResourceVisuals.quantity(value.key(), value.exactAmount(), 1)));
        detail = addChild(new GuiResourceInset(gui, relativeX + 17, relativeY + 174, 220, 66));
        gauge = addChild(new GuiResourceChemicalGauge(gui,relativeX+70,relativeY+183,18,44));
        toggle = addChild(new GuiResourceButton(gui, relativeX + 245, relativeY + 183, 68, 20,
                Component.literal("ME"), (element, mx, my) -> { toggleOutput(); return true; }));
        eject = addChild(new GuiResourceButton(gui, relativeX + 245, relativeY + 211, 68, 20,
                tr("eject"), (element, mx, my) -> { if (selected != null) action(selected, false); return true; }));
        eject.setTooltip(Tooltip.create(tr("eject_hint")));
        ResourceMachinePanel.opened(this); refresh();
    }
    private static Component tr(String key) { return Component.translatable("resource.productivebeesgenesis." + key); }
    private ResourceStack selectedBalance() {
        for (var value : balances) if (value.key().equals(selected)) return value;
        return null;
    }
    private String rateCompact(ResourceKey key) {
        return rateLabels.getOrDefault(key, "0/s");
    }
    private Component emptyMessage() {
        if (!displayRows.isEmpty()) return tr("no_matches");
        return tr(productionView ? "production.empty" : "empty");
    }
    private String formatRate(ResourceKey key) {
        var row = productionRows.get(key);
        if (row == null || reply == null) return "0/s";
        var whole = row.perSecond(productionSnapshot.windowMillis());
        return (whole.compareTo(java.math.BigInteger.valueOf(1000)) < 0 ? row.rate(productionSnapshot.windowMillis())
                : ResourceVisuals.compactAmount(key, whole)) + "/s";
    }
    private Component productionTooltip(ResourceKey key) {
        return rateTooltips.getOrDefault(key, tr("loading"));
    }
    private Component formatProductionTooltip(ResourceKey key) {
        var row = productionRows.get(key);
        if (row == null || reply == null) return tr("production.rate").copy().append(": 0/s");
        return tr("production.rate").copy().append(": ").append(row.rate(productionSnapshot.windowMillis())).append(" ")
                .append(ResourceVisuals.unit(key))
                .append("/s\n").append(tr("production.state." + row.state().name().toLowerCase(Locale.ROOT)))
                .append("\n").append(tr("production.window"));
    }
    private ResourceMenuSync.Request request() {
        return new ResourceMenuSync.Request(menu.containerId, menu.getTileEntity().getBlockPos(), session, ++sequence, 0,
                inventory.revision(), production.revision());
    }
    private void action(ResourceKey key, boolean toggle) {
        if (reply == null || reply.source() == null || Minecraft.getInstance().screen != screen) return;
        ForgeNetwork.sendToServer(new ResourceMenuSync.Action(request(), reply.source(), key, toggle));
        nextQuery = net.minecraft.Util.getMillis() + 500;
    }
    private void toggleOutput() {
        if (ResourceTypes.EMC.equals(selected)) selectRecipient();
        else action(null, true);
    }
    private void selectRecipient() {
        if (!recipientSelectionAvailable()) return;
        if (reply == null || reply.source() == null || !reply.emcRecipient().selectable()
                || Minecraft.getInstance().screen != screen) return;
        ForgeNetwork.sendToServer(new ResourceMenuSync.SelectEmcRecipient(request(), reply.source(), reply.emcRecipient().player()));
        nextQuery = net.minecraft.Util.getMillis() + 500;
    }
    private static boolean recipientSelectionAvailable() {
        return net.minecraftforge.fml.ModList.get().isLoaded("projecte")
                && net.minecraftforge.fml.ModList.get().isLoaded("ftbteams");
    }
    void accept(ResourceMenuSync.Reply value) {
        var request = value.request();
        if (closed || Minecraft.getInstance().screen != screen || screen.getMenu() != menu || !request.session().equals(session)
                || request.menu() != menu.containerId || !request.pos().equals(menu.getTileEntity().getBlockPos())
                || request.sequence() <= accepted || request.sequence() > sequence) return;
        try {
            boolean changed = value.inventory().reset() || !value.inventory().entries().isEmpty();
            if (changed) balances = inventory.apply(value.inventory());
            reply = value; accepted = request.sequence(); receivedAt = net.minecraft.Util.getMillis();
            var nextProduction = new LinkedHashMap<ResourceKey, ResourceProductionSnapshot.Entry>();
            productionSnapshot = production.apply(value.production());
            for (var row : productionSnapshot.entries()) nextProduction.put(row.key(), row);
            productionRows = Map.copyOf(nextProduction);
            rebuild(false);
        } catch (IllegalArgumentException invalid) {
            inventory = new ResourceInventoryDelta.Receiver(); production = new ResourceProductionDelta.Receiver();
            productionRows = Map.of(); reply = null; nextQuery = 0; rebuild(false);
        }
    }
    private void rebuild() {
        rebuild(true);
    }
    private void rebuild(boolean resetScroll) {
        if (search == null) return;
        String query = search.getText().strip().toLowerCase(Locale.ROOT);
        var rows = new ArrayList<ResourceStack>();
        var all = new LinkedHashMap<ResourceKey, ResourceStack>();
        for (var value : balances) all.put(value.key(), value);
        if (productionView) {
            for (var value : productionRows.values()) all.putIfAbsent(value.key(), new ResourceStack(value.key(), 1));
        }
        displayRows = List.copyOf(all.values());
        rateLabels.clear();
        rateTooltips.clear();
        for (var value : displayRows) {
            rateLabels.put(value.key(), formatRate(value.key()));
            rateTooltips.put(value.key(), formatProductionTooltip(value.key()));
        }
        for (var value : displayRows) {
            boolean chemical = ResourceTypes.chemicalId(value.key()) != null;
            if (filter == 1 && !chemical || filter == 2 && chemical) continue;
            if (!query.isEmpty() && !(ResourceVisuals.name(value.key()).getString() + " " + ResourceVisuals.id(value.key())
                    + " " + value.key()).toLowerCase(Locale.ROOT).contains(query)) continue;
            rows.add(value);
        }
        Comparator<ResourceStack> names = Comparator.comparing((ResourceStack row) -> ResourceVisuals.name(row.key()).getString())
                .thenComparing(row -> row.key().toString());
        Comparator<ResourceStack> comparator = sort == 1 ? Comparator.comparing((ResourceStack row) -> productionView
                && productionRows.containsKey(row.key()) ? productionRows.get(row.key()).produced() : row.exactAmount()).reversed().thenComparing(names)
                : sort == 2 ? Comparator.comparing((ResourceStack row) -> row.key().kind().toString()).thenComparing(names) : names;
        rows.sort(comparator); visible = List.copyOf(rows);
        if (resetScroll && grid != null) grid.resetScroll();
        if (grid != null) grid.refreshTooltips();
        if (selected == null && !visible.isEmpty()) selected = visible.get(0).key();
        // 资源清空后保留选择：连续产出不会跳到另一种，弹空时也不会误选相邻格。
        if (filterButton != null) filterButton.setMessage(tr("filter." + new String[]{"all", "chemical", "other"}[filter]));
        if (sortButton != null) sortButton.setMessage(tr(sort == 1 && productionView ? "sort.rate"
                : "sort." + new String[]{"name", "amount", "type"}[sort]));
        if (viewButton != null) viewButton.setMessage(tr(productionView ? "view.production" : "view.stored"));
        refresh();
    }
    private void refresh() {
        if (gauge == null) return;
        var balance = selectedBalance(); var id = selected == null ? null : ResourceTypes.chemicalId(selected);
        gauge.visible = id != null && com.ayoshiko.productivebeesgenesis.port.ForgeChemicals.REGISTRY.containsKey(id);
        if (gauge.visible) {
            gauge.resource=selected;gauge.amount=balance==null?0:balance.amount();gauge.capacity=Math.max(1,reply==null?1:reply.capacity());
        }
        boolean valid = reply != null && reply.source() != null && reply.status().accountReadable()
                && reply.status().state() != ResourceOutputStatus.State.PAUSED;
        var currentRecipient = reply == null ? ResourceMenuSync.EmcRecipient.NONE : reply.emcRecipient();
        recipientButton.visible = currentRecipient.player() != null
                && recipientSelectionAvailable();
        recipientButton.active = valid && currentRecipient.selectable();
        recipientButton.setTooltip(Tooltip.create(tr("emc.recipient").copy().append(": ").append(currentRecipient.name())
                .append("\n").append(tr("emc.status." + currentRecipient.state()))
                .append("\n").append(tr("emc.recipient_hint"))));
        eject.active = valid && balance != null;
        toggle.active = valid;
        toggle.visible = !ResourceTypes.EMC.equals(selected) || recipientSelectionAvailable();
        toggle.setMessage(tr("me_short." + (reply != null && reply.status().meEnabled() ? "on" : "off")));
        if (ResourceTypes.EMC.equals(selected)) {
            var recipient = reply == null ? ResourceMenuSync.EmcRecipient.NONE : reply.emcRecipient();
            String name = recipient.name().isEmpty() ? "?" : recipient.name();
            String shortName = Minecraft.getInstance().font.plainSubstrByWidth(name, 50);
            toggle.setMessage(Component.literal(shortName));
            toggle.active = valid && recipient.selectable();
            toggle.setTooltip(Tooltip.create(tr("emc.recipient").copy().append(": ").append(name)
                    .append("\n").append(tr("emc.recipient_hint"))));
        } else {
            toggle.setTooltip(Tooltip.create(tr("me_short." + (reply != null && reply.status().meEnabled() ? "on" : "off"))));
        }
        detail.setTooltip(selected == null ? Tooltip.create(tr("select_resource")) : Tooltip.create(ResourceVisuals.name(selected).copy()
                .append("\n").append(productionTooltip(selected))
                .append("\n").append(ResourceVisuals.quantity(selected, balance == null ? java.math.BigInteger.ZERO : balance.exactAmount(), 1))
                .append("\n").append(tr("capacity")).append(": ")
                .append(ResourceVisuals.quantity(selected, reply == null ? 0 : reply.capacity(), 1))
                .append("\n").append(selected.toString())));
        statusHolder.setTooltip(Tooltip.create(status()));
    }
    private Component status() {
        if (reply != null && ResourceTypes.EMC.equals(selected) && reply.emcRecipient().player() != null
                && reply.status().state() != ResourceOutputStatus.State.PAUSED
                && reply.status().state() != ResourceOutputStatus.State.PARTIAL) {
            return tr("emc.status." + reply.emcRecipient().state());
        }
        return tr(reply == null ? "loading" : "status." + reply.status().state().name().toLowerCase(Locale.ROOT));
    }
    @Override public void tick() {
        super.tick();
        if (closed || Minecraft.getInstance().screen != screen || screen.getMenu() != menu) return;
        long now = net.minecraft.Util.getMillis();
        if (now >= nextQuery) { ForgeNetwork.sendToServer(request()); nextQuery = now + 500; }
        if (reply != null && now - receivedAt > 1500) { reply = null; rebuild(false); }
    }
    @Override public void close() {
        if (!closed) {
            closed = true;
            ForgeNetwork.sendToServer(new ResourceMenuSync.CloseWindow(request()));
            ResourceMachinePanel.closed(this);
        }
        super.close();
    }
    @Override public void renderForeground(GuiGraphics graphics, int mx, int my) {
        super.renderForeground(graphics, mx, my);
        var font = Minecraft.getInstance().font;
        ResourceGuiTextures.sprite(graphics, relativeX + 36, relativeY + 3, 112, 0, 16, 16);
        graphics.drawString(font, tr("panel"), relativeX + 56, relativeY + 7, ResourceGuiTextures.TEXT, false);
        graphics.drawString(font, tr("scope"), relativeX + 17, relativeY + 27, ResourceGuiTextures.TEXT, false);
        graphics.drawString(font, tr("production.purpose"), relativeX + 17, relativeY + 38, ResourceGuiTextures.TEXT, false);
        graphics.fill(relativeX + 17, relativeY + 55, relativeX + 21, relativeY + 59,
                reply != null && reply.status().state() == ResourceOutputStatus.State.READY ? 0xFF7BBB85 : 0xFFEBC56C);
        var showing = Component.translatable("resource.productivebeesgenesis.showing", visible.size(), displayRows.size());
        int countX = relativeX + 319 - font.width(showing);
        int statusWidth = Math.max(0, countX - (relativeX + 26) - 6);
        String statusText = status().getString();
        if (font.width(statusText) > statusWidth)
            statusText = font.plainSubstrByWidth(statusText, Math.max(0, statusWidth - font.width("…"))) + "…";
        graphics.drawString(font, statusText, relativeX + 26, relativeY + 53, 0xFFF1F1F1, false);
        ResourceGuiTextures.sprite(graphics, relativeX + 19, relativeY + 73, 144, 0, 16, 16);
        if (search.getText().isEmpty() && !search.isFocused())
            graphics.drawString(font, tr("search"), relativeX + 34, relativeY + 75, 0xFFE2E9E9, false);
        var balance = selectedBalance();
        if (selected != null) {
            int left = relativeX + (gauge.visible ? 96 : 74), y = relativeY + 181;
            int textWidth = relativeX + 232 - left;
            var nameLines = font.split(ResourceVisuals.name(selected), textWidth);
            for (int i = 0; i < Math.min(2, nameLines.size()); i++)
                graphics.drawString(font, nameLines.get(i), left, y + i * 10, ResourceGuiTextures.TEXT, false);
            graphics.pose().pushPose();
            graphics.pose().translate(relativeX + 27, relativeY + 190, 0);
            graphics.pose().scale(2, 2, 1);
            ResourceVisuals.draw(graphics, selected);
            graphics.pose().popPose();
            if (productionView) {
                var row = productionRows.get(selected);
                String rate = reply == null ? tr("loading").getString()
                        : tr("production.rate").getString() + ": " + rateCompact(selected).replace("/s", "")
                                + " " + ResourceVisuals.unit(selected).getString() + "/s";
                graphics.drawString(font, font.plainSubstrByWidth(rate, textWidth), left, relativeY + 204, ResourceGuiTextures.TEXT, false);
                var state = row == null ? tr("production.state.recent")
                        : tr("production.state." + row.state().name().toLowerCase(Locale.ROOT));
                graphics.drawString(font, font.plainSubstrByWidth(state.getString(), textWidth), left, relativeY + 218, ResourceGuiTextures.MUTED, false);
            } else {
            graphics.drawString(font, tr("stored"), left, relativeY + 204, ResourceGuiTextures.MUTED, false);
            int qy = relativeY + 218;
            for (var line : font.split(ResourceVisuals.quantity(selected, balance == null ? java.math.BigInteger.ZERO : balance.exactAmount(), 1), textWidth)) {
                graphics.drawString(font, line, left, qy, ResourceGuiTextures.TEXT, false); qy += 10;
            }
            }
        } else {
            int y = relativeY + 199;
            for (var line : font.split(tr("select_resource"), 154)) { graphics.drawString(font, line, relativeX + 75, y, ResourceGuiTextures.MUTED, false); y += 10; }
        }
        graphics.drawString(font, showing, countX, relativeY + 53, 0xFFE0E0E0, false);
        if (reply != null) {
            var count = Component.translatable("resource.productivebeesgenesis.processes", reply.processes());
            graphics.drawString(font, count, relativeX + WIDTH - 12 - font.width(count), relativeY + 7, ResourceGuiTextures.MUTED, false);
        }
    }
    @Override public void renderBackgroundOverlay(GuiGraphics graphics, int mx, int my) {
        super.renderBackgroundOverlay(graphics, mx, my);
        graphics.setColor(1, 1, 1, 1);
        graphics.blit(ResourceGuiTextures.WINDOW, relativeX, relativeY, 0, 0, WIDTH, HEIGHT, WIDTH, HEIGHT);
    }
    @Override protected int getTitlePadStart() { return 14 + 12; }
}
