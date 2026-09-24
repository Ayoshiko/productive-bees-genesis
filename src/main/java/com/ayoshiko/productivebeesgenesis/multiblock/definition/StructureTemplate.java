package com.ayoshiko.productivebeesgenesis.multiblock.definition;

import com.ayoshiko.productivebeesgenesis.multiblock.geometry.StructureGeometry;
import com.ayoshiko.productivebeesgenesis.multiblock.geometry.StructureSize.Region;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** 小型不可变规则表：四类区域加有限特例，构造不遍历矩形体积。 */
public record StructureTemplate(String variant, StructureGeometry geometry,
		Map<Region, StructureCell> regions, Map<BlockPos, StructureCell> features) {
	public static final int MAX_FEATURES = 64;
	public StructureTemplate {
		if (Objects.requireNonNull(variant).isBlank()) throw new IllegalArgumentException("Missing template variant");
		Objects.requireNonNull(geometry);
		regions = Map.copyOf(regions);
		if (regions.size() != Region.values().length) throw new IllegalArgumentException("Incomplete region rules");
		if (features.size() > MAX_FEATURES) throw new IllegalArgumentException("Too many template features");
		var frozen = new ConcurrentHashMap<BlockPos, StructureCell>();
		for (var entry : features.entrySet()) {
			if (!geometry.size().contains(entry.getKey())) throw new IllegalArgumentException("Feature outside template");
			frozen.put(entry.getKey().immutable(), Objects.requireNonNull(entry.getValue()));
		}
		features = Map.copyOf(frozen);
		for (var rule : regions.values()) if (uniqueRole(rule)) throw new IllegalArgumentException("Unique role in repeated region");
		int controllers = 0, cores = 0;
		for (var entry : features.entrySet()) {
			var roles = entry.getValue().roles();
			if (uniqueRole(entry.getValue()) && roles.size() != 1) throw new IllegalArgumentException("Optional unique role");
			if (roles.contains(StructureRole.CONTROLLER)) {
				controllers++;
				if (!entry.getKey().equals(geometry.controllerAnchor())) throw new IllegalArgumentException("Controller anchor mismatch");
			}
			if (roles.contains(StructureRole.CORE)) {
				cores++;
				if (!Vec3.atCenterOf(entry.getKey()).equals(geometry.coreCenter())) throw new IllegalArgumentException("Core visual anchor mismatch");
			}
		}
		if (controllers != 1 || cores != 1) throw new IllegalArgumentException("One controller and core required");
	}
	public StructureCell cellAt(BlockPos local) {
		var region = geometry.size().regionAt(local);
		return features.getOrDefault(local, regions.get(region));
	}
	private static boolean uniqueRole(StructureCell rule) {
		return rule.roles().contains(StructureRole.CONTROLLER) || rule.roles().contains(StructureRole.CORE);
	}
}
