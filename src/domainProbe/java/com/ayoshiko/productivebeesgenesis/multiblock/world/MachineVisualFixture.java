package com.ayoshiko.productivebeesgenesis.multiblock.world;

import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.multiblock.definition.CombinedApiaryDefinition;
import com.ayoshiko.productivebeesgenesis.multiblock.definition.StructureRole;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** 仅交换有限测试命令；客户端从真实区块包读取展示状态，不读取服务器 BE。 */
@EventBusSubscriber(modid = "productivebeesgenesis")
public final class MachineVisualFixture {
	public static final List<BlockPos> POSITIONS = java.util.stream.IntStream.range(0, 24)
			.mapToObj(i -> new BlockPos(8 + (i % 6) * 18, 128, 8 + (i / 6) * 18)).toList();
	public static final List<Direction> FACINGS = java.util.stream.IntStream.range(0, 24)
			.mapToObj(i -> List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST).get(i % 4)).toList();
	public static int variant(int index) { return index / 4; }
	private static final List<MachineProbeFixture> fixtures = new ArrayList<>();
	private static final AtomicInteger requested = new AtomicInteger(-1);
	public static volatile int done = -1;
	public static volatile String failure;
	private static CompoundTag secondOriginal;
	private static int normalBudget;
	private static int fixtureTicks;
	private static boolean initialViewReady;
	public static void request(int command) {
		if (!requested.compareAndSet(-1, command)) throw new IllegalStateException("Visual command still pending");
	}
	@SubscribeEvent public static void tick(ServerTickEvent.Post event) {
		if (!Boolean.getBoolean("pbg.machineClient.enabled") || failure != null) return;
		var server = event.getServer(); if (server.getPlayerList().getPlayers().isEmpty()) return;
		var player = server.getPlayerList().getPlayers().getFirst(); var level = server.overworld();
		try {
			if (fixtures.isEmpty()) {
				normalBudget = ModConfig.SERVER.beeNetwork.totalSteps.get();
				level.setDayTime(6000); level.setWeatherParameters(0, 12000, false, false);
				level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
				for (int i=0; i<(Boolean.getBoolean("pbg.machineClient.menuOnly") ? 1 : POSITIONS.size()); i++) fixtures.add(MachineProbeFixture.place(level, CombinedApiaryDefinition.DEFINITION.candidates().get(variant(i)), POSITIONS.get(i), FACINGS.get(i), player.getUUID()));
				for (var fixture : fixtures) {
					var size = fixture.template().geometry().size();
					for (long i=0; i<size.volume(); i++) {
						var local = size.positionAt(i);
						if (local.getY() >= 2 && local.getY() < size.height() - 1 && fixture.template().cellAt(local).roles().contains(StructureRole.GLASS)) {
							level.setBlockAndUpdate(fixture.world(local), MachineContent.block(StructureRole.GLASS).defaultBlockState());
						}
					}
				}
				player.setGameMode(GameType.SPECTATOR); player.setNoGravity(true);
				player.connection.teleport(1024.5, 140, 1024.5, 0, 0);
				return;
			}
			fixtureTicks++;
			if (!initialViewReady) {
				if (fixtureTicks < 120 || level.getLightEngine().hasLightWork()
						|| fixtures.stream().anyMatch(fixture -> !fixture.core().formed())) return;
				initialViewReady = true; camera(player, 0); done = 0;
				return;
			}
			int command = requested.getAndSet(-1); if (command < 0) return;
			if (command == 200 && com.ayoshiko.productivebeesgenesis.apiculture.runtime.RuntimeProductPolicies.peek(level) == null) { requested.set(command); return; }
			if (command >= 100 && command < 100 + POSITIONS.size()) camera(player, command - 100);
			else if (command < 4) camera(player, command);
			else if (command >= 20 && command <= 23) oblique(player, command - 20);
			else switch (command) {
				case 200 -> {
					var core = fixtures.getFirst().core(); player.setGameMode(GameType.SURVIVAL);
					player.connection.teleport(core.getBlockPos().getX() + 0.5, core.getBlockPos().getY(), core.getBlockPos().getZ() - 2, 0, 0);
					var bee = new CompoundTag(); bee.putString("entity", "productivebees:configurable_bee"); bee.putString("type", "productivebees:iron"); bee.putUUID("UUID", java.util.UUID.randomUUID());
					var cage = new net.minecraft.world.item.ItemStack(cy.jdkdigital.productivebees.init.ModItems.STURDY_BEE_CAGE.get());
					cage.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(bee));
					player.getInventory().items.set(0, cage); player.getInventory().items.set(1, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_BLOCK)); player.getInventory().selected = 0;
					player.getInventory().items.set(2, MachineUpgradeProfiles.unit(0));
					var state = core.getBlockState(); ((MachinePartBlock) state.getBlock()).useWithoutItem(state, level, core.getBlockPos(), player,
							new net.minecraft.world.phys.BlockHitResult(core.getBlockPos().getCenter(), Direction.NORTH, core.getBlockPos(), false));
					if (!(player.containerMenu instanceof MachineMenu)) throw new IllegalStateException("Controller did not open machine menu");
				}
				case 201 -> {
					var fixture = fixtures.getFirst();
					var local = fixture.template().features().entrySet().stream().filter(e -> e.getValue().roles().contains(StructureRole.INTERFACE)).findFirst().orElseThrow().getKey();
					var pos = fixture.world(local); var state = level.getBlockState(pos); var face = state.getValue(MachinePartBlock.FACING);
					player.connection.teleport(pos.getX() + 0.5 + face.getStepX() * 2, pos.getY(), pos.getZ() + 0.5 + face.getStepZ() * 2, 0, 0);
					int alpha = com.ayoshiko.productivebeesgenesis.multiblock.production.MachineUpgrades.slot(true, com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeType.PRODUCTIVITY);
					int stability = com.ayoshiko.productivebeesgenesis.multiblock.production.MachineUpgrades.slot(false, com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeType.STABILITY);
					player.getInventory().items.set(3, MachineUpgradeProfiles.unit(alpha).copyWithCount(2));
					player.getInventory().items.set(4, MachineUpgradeProfiles.unit(stability));
					((MachinePartBlock) state.getBlock()).useWithoutItem(state, level, pos, player, new net.minecraft.world.phys.BlockHitResult(pos.getCenter(), face, pos, false));
					if (!(player.containerMenu instanceof MachineMenu)) throw new IllegalStateException("Interface did not open shared machine menu");
				}
				case 10 -> level.removeBlock(fixtures.getFirst().world(BlockPos.ZERO), false);
				case 11 -> {
					ModConfig.SERVER.beeNetwork.totalSteps.set(1);
					level.setBlockAndUpdate(fixtures.getFirst().world(BlockPos.ZERO), MachineContent.block(StructureRole.FRAME).defaultBlockState());
				}
				case 12 -> ModConfig.SERVER.beeNetwork.totalSteps.set(normalBudget);
				case 13 -> {
					secondOriginal = fixtures.get(1).core().saveWithFullMetadata(level.registryAccess());
					fixtures.get(1).core().loadWithComponents(fixtures.getFirst().core().saveWithFullMetadata(level.registryAccess()), level.registryAccess());
				}
				case 14 -> fixtures.get(1).core().loadWithComponents(secondOriginal, level.registryAccess());
				case 15 -> player.connection.teleport(1024.5, 140, 1024.5, 0, 0);
				case 16 -> camera(player, 0);
				default -> throw new IllegalArgumentException("Unknown visual command");
			}
			done = command;
		} catch (Exception error) {
			failure = error.toString(); com.mojang.logging.LogUtils.getLogger().error("MACHINE_VISUAL_FIXTURE_FAILED", error);
		}
	}
	private static void camera(ServerPlayer player, int index) {
		var pos = POSITIONS.get(index); var facing = FACINGS.get(index);
		player.connection.teleport(pos.getX()+0.5+facing.getStepX()*9, pos.getY()+0.1, pos.getZ()+0.5+facing.getStepZ()*9, facing.toYRot()+180, 4);
	}
	private static void oblique(ServerPlayer player, int index) {
		var fixture = fixtures.get(index); var geometry = fixture.template().geometry();
		var transform = geometry.at(fixture.pos(), fixture.facing());
		var eye = transform.toWorldPoint(new net.minecraft.world.phys.Vec3(10, 5.5, -6));
		var target = transform.toWorldPoint(geometry.coreCenter().add(0, 0.25, 0));
		var delta = target.subtract(eye);
		float yaw = (float) Math.toDegrees(Math.atan2(-delta.x, delta.z));
		float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z)));
		player.connection.teleport(eye.x, eye.y - player.getEyeHeight(), eye.z, yaw, pitch);
	}
	@SubscribeEvent public static void stopped(ServerStoppedEvent event) {
		if (Boolean.getBoolean("pbg.machineClient.enabled")) { fixtures.clear(); secondOriginal = null; requested.set(-1); done = -1; }
	}
	private MachineVisualFixture() { }
}
