package com.ayoshiko.productivebeesgenesis.domainprobe;

import appeng.api.networking.*;
import com.ayoshiko.productivebeesgenesis.apiculture.bridge.*;
import com.ayoshiko.productivebeesgenesis.apiculture.compat.ae2.MeBridgeNode;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Direction;
import static com.ayoshiko.productivebeesgenesis.domainprobe.DomainProbeServer.require;

/** 用真实 AE2 节点越过无控制器网络的八通道限制，不替换生产节点的状态。 */
final class MeBridgeAeFixture {
	private static final List<IManagedGridNode> fillers = new ArrayList<>();
	static void overload(MeBridgeBlockEntity bridge) {
		var root = ((MeBridgeNode) bridge.link()).getGridNode(Direction.UP); require(root != null, "No actual bridge node");
		for (int i = 0; i < 9; i++) {
			var node = GridHelper.createManagedNode(new Object(), (owner, ignored) -> {}).setFlags(GridFlags.REQUIRE_CHANNEL, GridFlags.PREFERRED).setIdlePowerUsage(0);
			node.create(bridge.getLevel(), null); fillers.add(node); GridHelper.createConnection(root, node.getNode());
		}
	}
	static void clear() { for (var node : fillers) node.destroy(); fillers.clear(); }
	static void closed(MeBridgeLink old) { require(old instanceof MeBridgeNode node && node.getGridNode(Direction.UP) == null, "Old bridge capability remained usable"); }
}
