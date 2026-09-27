package com.ayoshiko.productivebeesgenesis.multiblock.world;

import com.ayoshiko.productivebeesgenesis.multiblock.definition.CombinedApiaryDefinition;
import com.ayoshiko.productivebeesgenesis.multiblock.runtime.MachineDirectory;
import net.minecraft.server.level.ServerLevel;

/** 只投影已提交绑定；每次转换最多访问旧／新模板的七个部件，不扫描外壳或加载区块。 */
final class MachinePartVisuals {
	private MachineDirectory.Binding published;
	void sync(ServerLevel level, MachineDirectory.Binding next) {
		if (published == next) return;
		var previous = published; published = next;
		project(level, previous, false); project(level, next, true);
	}
	private static void project(ServerLevel level, MachineDirectory.Binding binding, boolean formed) {
		if (binding == null) return;
		var template = CombinedApiaryDefinition.DEFINITION.candidates().stream()
				.filter(candidate -> candidate.variant().equals(binding.variant())).findFirst().orElseThrow();
		var transform = template.geometry().at(binding.handle().controller(), binding.handle().facing());
		for (var local : template.features().keySet()) {
			var pos = transform.toWorld(local);
			if (pos.equals(binding.handle().controller())) continue;
			var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
			if (chunk != null && chunk.getBlockEntity(pos) instanceof MachinePartEntity part && part.references(binding)) part.publishFormed(formed);
		}
	}
}
