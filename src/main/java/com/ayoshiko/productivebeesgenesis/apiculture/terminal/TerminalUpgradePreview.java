package com.ayoshiko.productivebeesgenesis.apiculture.terminal;

import com.ayoshiko.productivebeesgenesis.apiculture.capacity.UpgradeCapacity;
import java.util.Objects;
import net.minecraft.network.FriendlyByteBuf;

/** 一次有限模拟的展示结果；不是预留，也不能作为随后交换的授权。 */
public record TerminalUpgradePreview(int row, int choice, int inventorySlot, int requested, boolean installing,
		TerminalReply.Status status, int movable, UpgradeCapacity before, UpgradeCapacity after) {
	public TerminalUpgradePreview {
		Objects.requireNonNull(status);
		if (row < 0 || row >= NetworkSelectionSession.PAGE_SIZE || choice < 0 || choice >= 16 || inventorySlot < 0
				|| inventorySlot >= 36 || requested < 1 || requested > 64 || movable < 0 || movable > requested
				|| (status == TerminalReply.Status.MOVED ? movable == 0 || before == null || after == null : movable != 0 || before != null || after != null))
			throw new IllegalArgumentException("Invalid upgrade preview");
	}
	static TerminalUpgradePreview read(FriendlyByteBuf b) {
		int row = b.readUnsignedByte(), choice = b.readUnsignedByte(), slot = b.readUnsignedByte(), requested = b.readUnsignedByte();
		boolean installing = b.readBoolean(); var status = b.readEnum(TerminalReply.Status.class); int moved = b.readUnsignedByte();
		return new TerminalUpgradePreview(row, choice, slot, requested, installing, status, moved,
				status == TerminalReply.Status.MOVED ? capacity(b) : null, status == TerminalReply.Status.MOVED ? capacity(b) : null);
	}
	void write(FriendlyByteBuf b) {
		b.writeByte(row); b.writeByte(choice); b.writeByte(inventorySlot); b.writeByte(requested); b.writeBoolean(installing);
		b.writeEnum(status); b.writeByte(movable);
		if (status == TerminalReply.Status.MOVED) { capacity(b, before); capacity(b, after); }
	}
	private static UpgradeCapacity capacity(FriendlyByteBuf b) {
		return new UpgradeCapacity(b.readFloat(), b.readLong(), b.readLong(), b.readInt(), b.readFloat(), b.readFloat(), b.readBoolean(), b.readBoolean());
	}
	private static void capacity(FriendlyByteBuf b, UpgradeCapacity c) {
		b.writeFloat(c.timeFactor()); b.writeLong(c.energyPerTick()); b.writeLong(c.energyCapacity()); b.writeInt(c.parallel());
		b.writeFloat(c.productivity()); b.writeFloat(c.stability()); b.writeBoolean(c.combBlock()); b.writeBoolean(c.discardByproducts());
	}
}
