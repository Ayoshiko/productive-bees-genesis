package com.ayoshiko.productivebeesgenesis.api.resource;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.eventbus.api.Event;
import org.jetbrains.annotations.ApiStatus;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 每个服务器第一次查询第三方宿主时，在 NeoForge 游戏事件总线同步发布一次。
 * 可选集成监听器只在对应模组存在时登记；不得递归查宿主、跨线程或保存事件以后修改。
 * 注册失败拒绝发布全部本次注册，内置 PB／Genesis 宿主仍可使用。
 */
public final class RegisterResourceHostsEvent extends Event {
	private final Thread owner = Thread.currentThread();
	private final Map<BlockEntityType<?>, ResourceHostAdapter> entries = new LinkedHashMap<>();
	private boolean open = true;

	/** 第三方登记真实已注册类型；同类型重复登记不按监听器先后覆盖。 */
	public void register(BlockEntityType<?> type, ResourceHostAdapter adapter) {
		checkOpen();
		Objects.requireNonNull(type);
		Objects.requireNonNull(adapter);
		if (BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type) == null) throw new IllegalArgumentException("Unregistered host type");
		if (entries.putIfAbsent(type, adapter) != null) throw new IllegalArgumentException("Duplicate resource host type");
	}

	@ApiStatus.Internal
	public Map<BlockEntityType<?>, ResourceHostAdapter> finish() {
		checkOpen();
		open = false;
		return Map.copyOf(entries);
	}

	private void checkOpen() {
		if (!open || Thread.currentThread() != owner) throw new IllegalStateException("Resource hosts require the synchronous registration event");
	}
}
