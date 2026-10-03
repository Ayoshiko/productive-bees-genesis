package com.ayoshiko.productivebeesgenesis.domainprobe;

import com.ayoshiko.productivebeesgenesis.apiculture.core.NetworkCoreBlockEntity;
import com.ayoshiko.productivebeesgenesis.apiculture.compat.NativeUpgradeCounts;
import mekanism.api.Upgrade;
import net.minecraft.server.level.ServerPlayer;
import static com.ayoshiko.productivebeesgenesis.domainprobe.DomainProbeServer.require;

/** 客户端只发布阶段请求；服务器线程执行测试位移并独立核对最终资产。 */
final class ClientMemberProxyFixture {
	static volatile int positionRequest, positionApplied;
	static volatile boolean done, verified;
	static volatile boolean terminalsRequested, terminalsReady, terminalsDone, terminalsVerified;
	static void tick(NetworkCoreBlockEntity core, ServerPlayer player) {
		if (positionRequest != positionApplied) {
			player.teleportTo(positionRequest == 1 ? 17.5 : 8.5, 102, 8.5); positionApplied = positionRequest;
		}
		if (terminalsRequested && !terminalsReady) {
			DedicatedTerminalChecks.verify(core, player); terminalsReady = true;
		}
		if (terminalsDone && !terminalsVerified) {
			require(player.getInventory().getItem(6).getCount() == 2, "Dedicated terminal upgrade conservation");
			for (var record : core.ownership().readyAuthority().checkpoint().ownedMachines().activeValues())
				require(NativeUpgradeCounts.read(record.assets().copy().getCompound("upgrades")).getOrDefault(Upgrade.SPEED, 0) == 0, "Dedicated terminal retained duplicate upgrade");
			terminalsVerified = true;
		}
		if (!done || verified) return;
		require(player.getInventory().getItem(6).getCount() == 2 && player.getInventory().getItem(7).getCount() == 2
				&& player.getInventory().getItem(0).getCount() == 64, "Proxy UI changed inventory conservation");
		var data = core.ownership().readyAuthority(); require(data != null, "Proxy UI lost network authority");
		for (var record : data.checkpoint().ownedMachines().activeValues())
			require(NativeUpgradeCounts.read(record.assets().copy().getCompound("upgrades")).getOrDefault(Upgrade.SPEED, 0) == 0, "Proxy UI left duplicate upgrades");
		require(player.serverLevel().getBlockState(new net.minecraft.core.BlockPos(9, 101, 8)).isAir()
				&& player.serverLevel().getBlockState(new net.minecraft.core.BlockPos(10, 101, 8)).isAir(), "Proxy click placed the held block");
		verified = true;
	}
	private ClientMemberProxyFixture() { }
}
