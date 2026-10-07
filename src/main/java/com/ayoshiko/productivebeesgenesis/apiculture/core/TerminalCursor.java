package com.ayoshiko.productivebeesgenesis.apiculture.core;

import com.ayoshiko.productivebeesgenesis.apiculture.persistence.StrictNbt;
import java.util.Set;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import net.neoforged.neoforge.attachment.IAttachmentSerializer;

/** 鼠标物品由玩家存档保管；菜单只投影，关闭后无空间的余量不掉落、不进入共享合成账户。 */
public final class TerminalCursor {
	public static final IAttachmentSerializer<Tag, TerminalCursor> SERIALIZER = new IAttachmentSerializer<>() {
		@Override public TerminalCursor read(IAttachmentHolder holder, Tag original, HolderLookup.Provider registries) {
			var result = new TerminalCursor();
			try {
				if (!(original instanceof CompoundTag tag)) throw new IllegalArgumentException("Invalid terminal cursor tag");
				int schema = StrictNbt.integer(tag, "schema");
				if (schema != 1 && schema != 2 || !tag.getAllKeys().equals(schema == 1 ? Set.of("schema", "item") : Set.of("schema", "item", "pending", "request")))
					throw new IllegalArgumentException("Unsupported terminal cursor");
				var item = StrictNbt.compound(tag, "item");
				result.stack = item.isEmpty() ? ItemStack.EMPTY : ItemStack.parse(registries, item).orElseThrow();
				if (!result.stack.saveOptional(registries).equals(item)) throw new IllegalArgumentException("Lossy terminal cursor");
				if (schema == 2) {
					var pending = StrictNbt.compound(tag, "pending");
					result.pending = pending.isEmpty() ? ItemStack.EMPTY : ItemStack.parse(registries, pending).orElseThrow();
					if (!result.pending.saveOptional(registries).equals(pending) || result.pending.getCount() > 64) throw new IllegalArgumentException("Invalid retained receipt");
					var request = StrictNbt.compound(tag, "request");
					if (!request.isEmpty()) {
						if (!request.getAllKeys().equals(Set.of("item", "insert", "source"))) throw new IllegalArgumentException("Invalid cursor request");
						var raw = StrictNbt.compound(request, "item"); var wanted = ItemStack.parse(registries, raw).orElseThrow();
						if (!wanted.save(registries).equals(raw)) throw new IllegalArgumentException("Lossy cursor request");
						result.request = new TerminalCursorExchange.Request(wanted, StrictNbt.bool(request, "insert"), StrictNbt.string(request, "source"));
					}
					if (result.request != null && !result.pending.isEmpty()) throw new IllegalArgumentException("Conflicting cursor receipts");
				}
			} catch (RuntimeException failure) {
				result.invalid = original.copy();
				com.mojang.logging.LogUtils.getLogger().error("Terminal cursor retained in player data after decode failure", failure);
			}
			return result;
		}
		@Override public Tag write(TerminalCursor value, HolderLookup.Provider registries) {
			if (value.invalid != null) return value.invalid.copy();
			var tag = new CompoundTag(); boolean extended = value.request != null || !value.pending.isEmpty();
			tag.putInt("schema", extended ? 2 : 1); tag.put("item", value.stack.saveOptional(registries));
			if (extended) {
				tag.put("pending", value.pending.saveOptional(registries)); var request = new CompoundTag();
				if (value.request != null) { request.put("item", value.request.item().save(registries)); request.putBoolean("insert", value.request.insert()); request.putString("source", value.request.source()); }
				tag.put("request", request);
			}
			return tag;
		}
	};
	private ItemStack stack = ItemStack.EMPTY;
	private Tag invalid;
	ItemStack pending = ItemStack.EMPTY;
	TerminalCursorExchange.Request request;
	public boolean available() { return invalid == null; }
	public ItemStack item() { return stack.copy(); }
	void set(ItemStack value) {
		if (!available()) throw new IllegalStateException("Terminal cursor is quarantined");
		stack = value.copy();
	}
	static TerminalCursor get(ServerPlayer player) {
		if (!player.server.isSameThread()) throw new IllegalStateException("Terminal cursor belongs to the server thread");
		return player.getData(NetworkContent.TERMINAL_CURSOR);
	}
	static void restore(ServerPlayer player, AbstractContainerMenu menu) {
		var cursor = get(player);
		if (cursor.available()) { menu.setCarried(cursor.item()); TerminalCursorExchange.recover(player, menu, false); }
	}
	static void close(ServerPlayer player, AbstractContainerMenu menu) {
		var cursor = get(player);
		if (cursor.available()) {
			TerminalCursorExchange.recover(player, menu, false);
			cursor.set(menu.getCarried());
			if (player.isAlive()) {
				var before = TerminalCraftingPlan.copy(player.getInventory().items);
				var after = TerminalCraftingPlan.copy(before);
				var rest = TerminalCraftingPlan.insert(after, cursor.item());
				// 有限接收完成后再发布；这里没有外部库存或丢弃回调。
				cursor.set(rest);
				for (int i = 0; i < 36; i++) if (!ItemStack.matches(before.get(i), after.get(i))) player.getInventory().items.set(i, after.get(i));
				player.getInventory().setChanged();
			}
		}
		menu.setCarried(ItemStack.EMPTY);
	}
}
