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
	public static final List<BlockPos> POSITIONS = List.of(new BlockPos(8,128,8), new BlockPos(28,128,8), new BlockPos(8,128,28), new BlockPos(28,128,28));
	public static final List<Direction> FACINGS = List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST);
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
				for (int i=0; i<4; i++) fixtures.add(MachineProbeFixture.place(level, CombinedApiaryDefinition.DEFINITION.candidates().get(i%3), POSITIONS.get(i), FACINGS.get(i), player.getUUID()));
				for (var fixture : fixtures) {
					var size = fixture.template().geometry().size();
					for (long i=0; i<size.volume(); i++) {
						var local = size.positionAt(i);
						if (local.getY() == 3 && fixture.template().cellAt(local).roles().contains(StructureRole.GLASS)) {
							level.setBlockAndUpdate(fixture.world(local), MachineContent.block(StructureRole.GLASS).defaultBlockState());
						}
					}
				}
				player.setGameMode(GameType.SPECTATOR); player.setNoGravity(true);
				player.connection.teleport(1024.5, 140, 1024.5, 0, 0);
				return;
			}
			if (++fixtureTicks == 100) {
				for (int i=0; i<fixtures.size(); i++) {
					var fixture = fixtures.get(i);
					var transform = fixture.template().geometry().at(fixture.pos(), fixture.facing());
					for (var prop : com.ayoshiko.productivebeesgenesis.multiblock.visual.CombinedApiaryScene.props(i%3)) {
						var lightPos = BlockPos.containing(transform.toWorldPoint(prop.center()));
						com.mojang.logging.LogUtils.getLogger().info("SCENE_SERVER_LIGHT {} {} sky={} lightWork={}",
								lightPos, prop.kind(), level.getBrightness(net.minecraft.world.level.LightLayer.SKY, lightPos), level.getLightEngine().hasLightWork());
					}
				}
			}
			if (!initialViewReady) {
				if (fixtureTicks < 120 || level.getLightEngine().hasLightWork()
						|| fixtures.stream().anyMatch(fixture -> !fixture.core().formed())) return;
				initialViewReady = true; camera(player, 0); done = 0;
				return;
			}
			int command = requested.getAndSet(-1); if (command < 0) return;
			if (command < 4) camera(player, command);
			else if (command >= 20 && command <= 23) oblique(player, command - 20);
			else switch (command) {
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
