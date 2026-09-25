package com.ayoshiko.productivebeesgenesis.multiblock.world;

import com.ayoshiko.productivebeesgenesis.init.ModCreativeTabs;
import com.ayoshiko.productivebeesgenesis.multiblock.definition.StructureRole;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.IQuadTransformer;

/** 真实客户端区块同步、静态模型、重载及正常退出验证；不进入发布 JAR。 */
@EventBusSubscriber(modid = "productivebeesgenesis", value = Dist.CLIENT)
public final class MachineVisualClientProbe {
	private static final JsonObject report = new JsonObject();
	private static int step, settled, direction;
	private static long started;
	private static boolean advancing, finished;
	private static CompletableFuture<Void> reload;
	@SubscribeEvent public static void tick(ClientTickEvent.Post event) {
		if (!Boolean.getBoolean("pbg.machineClient.enabled") || advancing || finished) return;
		var client = Minecraft.getInstance(); advancing = true;
		try {
			if (started == 0) started = System.nanoTime();
			check(!(client.screen instanceof AccessibilityOnboardingScreen), "Unexpected first-run onboarding");
			check(System.nanoTime()-started < 300_000_000_000L, "Visual client timeout at step " + step);
			check(MachineVisualFixture.failure == null, MachineVisualFixture.failure);
			if (step == 0) {
				if (!(client.screen instanceof TitleScreen) || client.getOverlay() != null) return;
				check(!client.options.onboardAccessibility && client.options.languageCode.equals("zh_cn")
						&& client.getLanguageManager().getSelected().equals("zh_cn"), "Test client did not load Simplified Chinese defaults");
				report.addProperty("simplifiedChineseWithoutOnboarding", true);
				client.options.pauseOnLostFocus = false; client.options.hideGui = true;
				client.options.renderDistance().set(6); client.options.fov().set(60); client.options.gamma().set(1.0);
				client.options.save();
				step = 1;
				client.createWorldOpenFlows().createFreshLevel("m06a-client", new LevelSettings("Machine Visual Probe", GameType.CREATIVE, false,
						Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT), new WorldOptions(25092026L, false, false),
						WorldPresets::createNormalWorldDimensions, client.screen);
				return;
			}
			if (client.level == null || client.player == null || client.getOverlay() != null) return;
			switch (step) {
				case 1 -> {
					if (!waitFor(allReady(client) && MachineVisualFixture.done == direction)) return;
					capture(client, "ready-" + MachineVisualFixture.FACINGS.get(direction).getName());
					if (++direction < 4) { MachineVisualFixture.request(direction); return; }
					validateModels(client); report.addProperty("fourDirectionsAndInitialChunkStates", true);
					CreativeModeTabs.tryRebuildTabContents(client.level.enabledFeatures(), true, client.level.registryAccess());
					check(MachineContent.registeredBlocks().stream().allMatch(block -> ModCreativeTabs.MEK_CENTRIFUGE_TAB.get().contains(new ItemStack(block))), "Structure items missing from creative tab");
					report.addProperty("allElevenCreativeItems", true);
					MachineVisualFixture.request(0); step = 2;
				}
				case 2 -> { if (waitFor(MachineVisualFixture.done == 0)) { MachineVisualFixture.request(10); step = 3; } }
				case 3 -> {
					if (!waitFor(state(client, 0) == MachineVisualState.UNFORMED)) return;
					capture(client, "unformed"); report.addProperty("unformedUpdate", true);
					MachineVisualFixture.request(11); step = 4;
				}
				case 4 -> {
					if (!waitFor(state(client, 0) == MachineVisualState.CHECKING)) return;
					capture(client, "checking"); report.addProperty("checkingUpdate", true);
					MachineVisualFixture.request(12); step = 5;
				}
				case 5 -> {
					if (!waitFor(allReady(client))) return;
					capture(client, "rebuilt"); report.addProperty("reformedUpdate", true);
					MachineVisualFixture.request(13); step = 6;
				}
				case 6 -> {
					if (!waitFor(state(client, 0) == MachineVisualState.FAULT && state(client, 1) == MachineVisualState.FAULT)) return;
					capture(client, "fault"); report.addProperty("duplicateFaultUpdate", true);
					MachineVisualFixture.request(14); step = 7;
				}
				case 7 -> {
					if (!waitFor(allReady(client))) return;
					reload = client.reloadResourcePacks(); step = 8;
				}
				case 8 -> {
					if (!reload.isDone() || !waitFor(allReady(client))) return;
					reload.join(); validateModels(client); capture(client, "resource-reloaded");
					report.addProperty("resourceReloadKeepsModelsAndStates", true);
					finish(client, null);
				}
			}
		} catch (Exception failure) { finish(client, failure); }
		finally { advancing = false; }
	}
	private static MachineVisualState state(Minecraft client, int index) {
		var state = client.level.getBlockState(MachineVisualFixture.POSITIONS.get(index));
		if (!state.is(MachineContent.block(StructureRole.CONTROLLER)) || !state.hasProperty(MachineControllerBlock.STATUS)) return null;
		check(state.getValue(MachinePartBlock.FACING) == MachineVisualFixture.FACINGS.get(index), "Facing did not synchronize");
		var status = state.getValue(MachineControllerBlock.STATUS);
		check(state.getValue(MachinePartBlock.FORMED) == (status == MachineVisualState.READY), "Coarse state and formed flag diverged");
		return status;
	}
	private static boolean allReady(Minecraft client) { for (int i=0;i<4;i++) if (state(client,i) != MachineVisualState.READY) return false; return true; }
	private static boolean waitFor(boolean condition) { if (!condition) { settled=0; return false; } if (++settled < 25) return false; settled=0; return true; }
	private static void validateModels(Minecraft client) {
		var random = RandomSource.create(25092026); var missing = client.getModelManager().getMissingModel(); int combinations=0;
		for (var status : MachineVisualState.values()) for (var facing : Direction.Plane.HORIZONTAL) for (boolean formed : new boolean[]{false,true}) {
			var state = MachineContent.block(StructureRole.CONTROLLER).defaultBlockState().setValue(MachineControllerBlock.STATUS,status).setValue(MachinePartBlock.FACING,facing).setValue(MachinePartBlock.FORMED,formed);
			var model = client.getBlockRenderer().getBlockModel(state); check(model != missing, "Missing controller model");
			var quads = new ArrayList<>(model.getQuads(state,null,random,ModelData.EMPTY,null));
			for (var side : Direction.values()) quads.addAll(model.getQuads(state,side,random,ModelData.EMPTY,null));
			check(!quads.isEmpty() && quads.stream().noneMatch(q -> q.getSprite().contents().name().equals(MissingTextureAtlasSprite.getLocation())), "Missing model texture");
			String color = switch(status) { case UNFORMED -> "gray"; case CHECKING -> "yellow"; case READY -> "lime"; case WAITING -> "light_blue"; case FAULT -> "red"; };
			var signal = ResourceLocation.withDefaultNamespace("block/"+color+"_concrete");
			check(quads.stream().anyMatch(q -> q.getDirection()==facing && q.getSprite().contents().name().equals(signal)), "Status glyph missing or facing incorrectly");
			for (var quad : quads) if (quad.getSprite().contents().name().equals(signal)) {
				for (int vertex=0;vertex<4;vertex++) check(quad.getVertices()[vertex*IQuadTransformer.STRIDE+IQuadTransformer.UV2] == LightTexture.FULL_BRIGHT, "Status glyph lost surface brightness");
			}
			check(state.getLightEmission(client.level, MachineVisualFixture.POSITIONS.getFirst()) == 0, "Visual indicator must not emit world light");
			combinations++;
		}
		var glass = MachineContent.block(StructureRole.GLASS).defaultBlockState();
		check(client.getBlockRenderer().getBlockModel(glass).getRenderTypes(glass,random,ModelData.EMPTY).contains(RenderType.translucent()), "Glass not in translucent layer");
		report.addProperty("fortyStaticStateModelsAndGlyphDirections", combinations==40);
		report.addProperty("transparentWindowLayer", true);
		report.addProperty("emissiveGlyphsWithoutWorldLight", true);
	}
	private static void capture(Minecraft client, String name) throws Exception {
		Files.createDirectories(Path.of("results"));
		try(var shot=Screenshot.takeScreenshot(client.getMainRenderTarget())) { shot.writeToFile(Path.of("results/"+name+".png")); }
	}
	private static void finish(Minecraft client, Exception failure) {
		if (finished) return; finished=true;
		try {
			if(client.level!=null) { client.level.disconnect(); client.disconnect(new TitleScreen()); }
			boolean closed = client.getSingleplayerServer()==null && client.level==null;
			report.addProperty("normalIntegratedShutdown",closed); report.addProperty("passed",failure==null && closed);
			report.addProperty("ae2Loaded",ModList.get().isLoaded("ae2")); report.addProperty("completedStep",step);
			if(failure!=null) { report.addProperty("failure",failure.toString()); com.mojang.logging.LogUtils.getLogger().error("MACHINE_VISUAL_CLIENT_FAILED",failure); }
			Files.createDirectories(Path.of("results")); Files.writeString(Path.of("results/machine-client.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report));
		} catch(Exception error) { com.mojang.logging.LogUtils.getLogger().error("Cannot finish visual client probe",error); }
		client.stop();
	}
	private static void check(boolean valid,String message) { if(!valid)throw new IllegalStateException(message); }
	private MachineVisualClientProbe() { }
}
