package com.ayoshiko.productivebeesgenesis.domainprobe;

import com.ayoshiko.productivebeesgenesis.apiculture.core.*;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.*;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.init.ModBlocks;
import com.ayoshiko.productivebeesgenesis.mek.PbRecipeContext;
import java.util.UUID;
import mekanism.common.tile.base.TileEntityMekanism;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import static com.ayoshiko.productivebeesgenesis.domainprobe.DomainProbeServer.require;

/** 客户端探针的独立服务端断言；跨线程仅发布阶段与失败文本。 */
@EventBusSubscriber(modid = "productivebeesgenesis")
public final class ClientOwnershipFixture {
	static volatile int stage;
	static volatile String failure;
	static final long ENERGY_CAPACITY = (1L << 40) + 77, ENERGY_STORED = 3_000_000_009L;
	private static final BlockPos POS = new BlockPos(8, 100, 8);
	private static NetworkCoreBlockEntity core;
	private static AssetImage[] originals;
	@SubscribeEvent public static void tick(ServerTickEvent.Post event) {
		if (!Boolean.getBoolean("pbg.client.enabled") || stage == 4 || failure != null) return;
		var server = event.getServer();
		if (server.getPlayerList().getPlayers().isEmpty()) return;
		ServerPlayer player = server.getPlayerList().getPlayers().getFirst(); var level = server.overworld();
		try {
			if (core == null) {
				ModConfig.SERVER.beeNetwork.enabled.set(true);
				ModConfig.SERVER.beeNetwork.energyCapacity.set(ENERGY_CAPACITY);
				level.setChunkForced(0, 0, true);
				level.setBlockAndUpdate(POS, NetworkContent.CORE.get().defaultBlockState());
				core = (NetworkCoreBlockEntity) level.getBlockEntity(POS); core.initializeOwner(player.getUUID());
				originals = new AssetImage[2];
				for (int i = 0; i < 2; i++) {
					var pos = POS.east(i + 1);
					level.setBlockAndUpdate(pos, (i == 0 ? ModBlocks.MEK_APIARY.get() : ModBlocks.MEK_CENTRIFUGE.get()).defaultBlockState());
					var tile = (TileEntityMekanism) level.getBlockEntity(pos); tile.setOwnerUUID(player.getUUID());
					if (tile instanceof com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary hive) hive.setFeederConversionEnabled(false);
					((PbRecipeContext) tile).primaryOutputSlot(0).setStack(new ItemStack(Items.DIAMOND, 13 + i));
					originals[i] = new BlockEntityOwnershipEndpoint(tile).capture();
				}
				player.teleportTo(8.5, 102, 8.5); player.setNoGravity(true);
				return;
			}
				require(core.ownership().status() != CoreOwnershipController.Status.RECOVERY, core.ownership().failure());
			if (stage == 2) ClientTerminalFixture.tick(core, player);
			if (stage == 0) {
				if (core.topology() == null || !core.topology().valid()) return;
				require(core.topology().members().size() == 2, "Client fixture topology differs");
				core.openTerminal(player);
				var menu = (NetworkCoreMenu) player.containerMenu;
				var stranger = net.neoforged.neoforge.common.util.FakePlayerFactory.get(level, new com.mojang.authlib.GameProfile(UUID.randomUUID(), "P2Stranger"));
				stranger.containerMenu = menu;
				require(!menu.clickMenuButton(stranger, 1), "Foreign player could join");
				require(!menu.clickMenuButton(stranger, 3), "Foreign player could start production");
				stranger.containerMenu = stranger.inventoryMenu;
				player.setPos(40, 102, 40);
				require(!menu.clickMenuButton(player, 1), "Distant player could join");
				player.setPos(8.5, 102, 8.5);
				require(!menu.clickMenuButton(player, 99), "Unknown menu command accepted");
				stage = 1; return;
			}
			if (stage == 1 && !core.ownership().busy() && core.ownership().status() == CoreOwnershipController.Status.MANAGED) {
				var energy = level.getCapability(net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.BLOCK, POS, net.minecraft.core.Direction.UP);
				require(energy != null, "Client fixture missing core FE");
				for (int i = 0; i < 3; i++) require(energy.receiveEnergy(1_000_000_003, false) == 1_000_000_003, "Client fixture FE input failed");
				for (int i = 0; i < 2; i++) {
					var tile = (TileEntityMekanism) level.getBlockEntity(POS.east(i + 1));
					require(MemberBinding.isolated(tile) && new MachineAssetStore(tile).empty(), "Client join did not seal physical source");
				}
				stage = 2;
			}
			if (stage == 2 && !core.ownership().busy() && core.ownership().status() == CoreOwnershipController.Status.STANDALONE) {
				for (int i = 0; i < 2; i++) {
					var tile = (TileEntityMekanism) level.getBlockEntity(POS.east(i + 1));
					require(!MemberBinding.isolated(tile) && originals[i].equals(new BlockEntityOwnershipEndpoint(tile).capture()), "Client return changed assets");
				}
				stage = 3;
			}
			if (stage == 3 && player.containerMenu == player.inventoryMenu) {
				var stale = core.createMenu(99, player.getInventory(), player);
				require(!stale.clickMenuButton(player, 1), "Closed menu could join");
				stage = 4;
			}
		} catch (Exception error) { failure = error.toString(); com.mojang.logging.LogUtils.getLogger().error("P2_CLIENT_SERVER_FAILED", error); }
	}
	private ClientOwnershipFixture() { }
}
