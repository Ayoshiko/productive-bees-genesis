package com.ayoshiko.productivebeesgenesis.apiculture.port;

import com.google.gson.*;
import java.nio.file.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import static com.ayoshiko.productivebeesgenesis.apiculture.port.PortProbe.check;

/** 实际客户端按键／原生同步／集成服校验，共用服务端端口生产夹具。 */
@EventBusSubscriber(modid = "productivebeesgenesis", value = Dist.CLIENT)
public final class PortClientProbe {
    private static final JsonObject report = new JsonObject();
    private static int step;
    private static long started, next;
    private static boolean finished, busy;
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        if (!PortProbe.client() || finished || busy) return;
        var client = Minecraft.getInstance(); busy = true;
        try {
            if (started == 0) started = System.nanoTime();
            check(System.nanoTime() - started < 360_000_000_000L, "Port client timeout at " + step);
            check(PortProbe.failure == null, PortProbe.failure);
            if (System.currentTimeMillis() < next) return;
            next = System.currentTimeMillis() + 250;
            if (step == 0) {
                if (!(client.screen instanceof TitleScreen) || client.getOverlay() != null) return;
                client.options.pauseOnLostFocus = false; client.options.renderDistance().set(5);
                check(client.options.languageCode.equals("zh_cn"), "Client did not load Chinese");
                client.createWorldOpenFlows().createFreshLevel("ports-client", new LevelSettings("Port Probe", GameType.CREATIVE, false,
                        Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT), new WorldOptions(12102026L, false, false),
                        WorldPresets::createNormalWorldDimensions, client.screen);
                step = 1; return;
            }
            if (client.level == null || client.player == null || client.getOverlay() != null || !PortProbe.clientReady) return;
            if (step == 1) {
                open(client, PortProbe.SUPPLY); step = 2; return;
            }
            if (!(client.screen instanceof NetworkPortScreen screen) || !(client.player.containerMenu instanceof NetworkPortMenu menu)) return;
            if (step == 2) {
                if (menu.kind() != PortState.Kind.SUPPLY || menu.icon(0).isEmpty()) return;
                check(menu.slots.size() == 45, "Native display slot count differs");
                capture(client, "ports-supply");
                select(screen, menu, 0); click(screen, 30, 60, 1); step = 3; return;
            }
            if (step == 3) {
                if (!menu.icon(0).isEmpty() || client.player.getInventory().items.get(0).isEmpty()) return;
                click(screen, 174, 60, 0); step = 30; return;
            }
            if (step == 30) {
                if (menu.icon(8).isEmpty() || !client.player.getInventory().items.get(0).isEmpty()) return;
                click(screen, 174, 60, 1); step = 31; return;
            }
            if (step == 31) {
                if (!menu.icon(8).isEmpty() || client.player.getInventory().items.get(0).getCount() != 1) return;
                PortProbe.clientStage = 1; report.addProperty("realRecoveryClick", true); step = 4; return;
            }
            if (step == 4) {
                if (PortProbe.clientStage != 11) return;
                open(client, PortProbe.OUTPUT); step = 5; return;
            }
            if (step == 5) {
                if (menu.kind() != PortState.Kind.OUTPUT) return;
                select(screen, menu, 1); click(screen, 30, 60, 0); step = 6; return;
            }
            if (step == 6) {
                if (!ResourceLocation.parse("productivebees:iron").equals(menu.icon(0).get(cy.jdkdigital.productivebees.init.ModDataComponents.BEE_TYPE.get()))) return;
                select(screen, menu, 2); click(screen, 48, 60, 0); step = 7; return;
            }
            if (step == 7) {
                if (!ResourceLocation.parse("productivebees:gold").equals(menu.icon(1).get(cy.jdkdigital.productivebees.init.ModDataComponents.BEE_TYPE.get()))) return;
                select(screen, menu, 3); click(screen, 30, 84, 0); step = 8; return;
            }
            if (step == 8) {
                if (menu.fluid().isEmpty()) return;
                var toggle = screen.children().stream().filter(e -> e instanceof Button b && b.getMessage().getString().equals(NetworkPortMenu.text("off").getString())).findFirst().orElseThrow();
                ((Button) toggle).onPress(); step = 9; return;
            }
            if (step == 9) {
                if (!menu.enabled()) return;
                report.addProperty("componentIconsAndSamples", true);
                capture(client, "ports-output"); PortProbe.clientStage = 2; step = 10; return;
            }
            if (step == 10) {
                if (!PortProbe.clientVerified) return;
                for (var block : java.util.List.of(com.ayoshiko.productivebeesgenesis.apiculture.core.NetworkContent.SUPPLY_PORT.get(),
                        com.ayoshiko.productivebeesgenesis.apiculture.core.NetworkContent.INPUT_PORT.get(), com.ayoshiko.productivebeesgenesis.apiculture.core.NetworkContent.OUTPUT_PORT.get())) {
                    var model = client.getBlockRenderer().getBlockModel(block.defaultBlockState());
                    check(!model.getParticleIcon().contents().name().equals(MissingTextureAtlasSprite.getLocation()), "Port model missing");
                    check(!new ItemStack(block).getHoverName().getString().startsWith("block."), "Port name not translated");
                }
                screen.resize(client, 320, 240);
                check(screen.getGuiLeft() >= 0 && screen.getGuiTop() >= 0, "Compact port screen clipped");
                report.addProperty("modelsAndCompactLayout", true); step = 11; return;
            }
            if (step == 11) { capture(client, "ports-compact"); finish(client, null); }
        } catch (Exception error) { finish(client, error); }
        finally { busy = false; }
    }
    private static void open(Minecraft client, net.minecraft.core.BlockPos pos) {
        client.player.closeContainer();
        client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, new BlockHitResult(pos.getCenter(), Direction.UP, pos, false));
    }
    private static void select(NetworkPortScreen screen, NetworkPortMenu menu, int inventory) {
        var slot = menu.slots.subList(0, 36).stream().filter(s -> s.getContainerSlot() == inventory).findFirst().orElseThrow();
        click(screen, slot.x + 8, slot.y + 8, 0);
    }
    private static void click(NetworkPortScreen screen, int x, int y, int button) {
        check(screen.mouseClicked(screen.getGuiLeft() + x, screen.getGuiTop() + y, button), "Port click ignored");
    }
    private static void capture(Minecraft client, String name) throws Exception {
        Files.createDirectories(Path.of("results"));
        try (var shot = Screenshot.takeScreenshot(client.getMainRenderTarget())) { shot.writeToFile(Path.of("results/" + name + ".png")); }
    }
    private static void finish(Minecraft client, Exception failure) {
        if (finished) return; finished = true;
        try {
            if (client.level != null) { client.level.disconnect(); client.disconnect(new TitleScreen()); }
            report.addProperty("passed", failure == null && PortProbe.normalShutdown);
            if (failure != null) { report.addProperty("failure", failure.toString()); com.mojang.logging.LogUtils.getLogger().error("PORT_CLIENT_FAILED", failure); }
            Files.createDirectories(Path.of("results")); Files.writeString(Path.of("results/ports-client.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report));
        } catch (Exception error) { com.mojang.logging.LogUtils.getLogger().error("Port client shutdown failed", error); }
        client.stop();
    }
}
