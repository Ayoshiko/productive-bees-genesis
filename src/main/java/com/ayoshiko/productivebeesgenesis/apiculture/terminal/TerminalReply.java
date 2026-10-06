package com.ayoshiko.productivebeesgenesis.apiculture.terminal;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** 每个已接纳请求只回复一次，包体最多 64 KiB，无分片队列，图标组件 NBT 每项最多 512 字节。 */
public record TerminalReply(int containerId, UUID session, long sequence, Status status,
		int moved, int interruptedTicks, TerminalView view, List<UpgradeResult> upgrades, TerminalUpgradePreview preview) implements CustomPacketPayload {
	public enum Status { OK, MOVED, STALE, INVALID, UNAVAILABLE, NO_SPACE, EMPTY_OR_RESERVED, DRAIN_FIRST,
		OCCUPIED, EMPTY, UNSUPPORTED_CAGE, UNSUPPORTED_BEE, UNSUPPORTED_CONTAINER, LIMIT, UNSUPPORTED, ENERGY_CAPACITY, CONFLICT, BATCH_COMPLETE, MISSING_INGREDIENTS }
	public record UpgradeResult(int row, String label, Status status, int moved) {
		public UpgradeResult {
			Objects.requireNonNull(label); Objects.requireNonNull(status);
			if (row < 0 || row >= NetworkSelectionSession.PAGE_SIZE || label.length() > TerminalView.TEXT_LIMIT
					|| moved < 0 || moved > 64 || status == Status.BATCH_COMPLETE || (status == Status.MOVED) != (moved > 0))
				throw new IllegalArgumentException("Invalid per-member upgrade result");
		}
	}
	public TerminalReply(int containerId, UUID session, long sequence, Status status, int moved, int interruptedTicks, TerminalView view) {
		this(containerId, session, sequence, status, moved, interruptedTicks, view, List.of(), null);
	}
	public static final int MAX_BYTES = 64 * 1024;
	public static final Type<TerminalReply> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("productivebeesgenesis", "network_terminal_reply"));
	public static final StreamCodec<FriendlyByteBuf, TerminalReply> STREAM_CODEC = new StreamCodec<>() {
		@Override public TerminalReply decode(FriendlyByteBuf b) {
			if (b.readableBytes() > MAX_BYTES) throw new IllegalArgumentException("Oversized terminal reply");
			int container = b.readInt(); UUID session = b.readUUID(); long sequence = b.readLong();
			Status status = b.readEnum(Status.class); int moved = b.readInt(), interrupted = b.readInt();
			TerminalView view = b.readBoolean() ? readView(b) : null;
			int count = boundedSize(b, NetworkSelectionSession.PAGE_SIZE); var upgrades = new ArrayList<UpgradeResult>(count);
			for (int i = 0; i < count; i++) upgrades.add(new UpgradeResult(b.readUnsignedByte(), b.readUtf(TerminalView.TEXT_LIMIT), b.readEnum(Status.class), b.readUnsignedByte()));
			var preview = b.readBoolean() ? TerminalUpgradePreview.read(b) : null;
			if (b.isReadable()) throw new IllegalArgumentException("Trailing terminal reply bytes");
			return new TerminalReply(container, session, sequence, status, moved, interrupted, view, upgrades, preview);
		}
		@Override public void encode(FriendlyByteBuf b, TerminalReply r) {
			int start = b.writerIndex();
			b.writeInt(r.containerId); b.writeUUID(r.session); b.writeLong(r.sequence); b.writeEnum(r.status);
			b.writeInt(r.moved); b.writeInt(r.interruptedTicks); b.writeBoolean(r.view != null);
			if (r.view != null) writeView(b, r.view);
			b.writeByte(r.upgrades.size());
			for (var upgrade : r.upgrades) { b.writeByte(upgrade.row()); b.writeUtf(upgrade.label(), TerminalView.TEXT_LIMIT); b.writeEnum(upgrade.status()); b.writeByte(upgrade.moved()); }
			b.writeBoolean(r.preview != null); if (r.preview != null) r.preview.write(b);
			if (b.writerIndex() - start > MAX_BYTES) throw new IllegalArgumentException("Oversized terminal reply");
		}
	};
	public TerminalReply {
		Objects.requireNonNull(session); Objects.requireNonNull(status);
		if (containerId < 0 || sequence <= 0 || moved < 0 || moved > 1000 || interruptedTicks < 0) throw new IllegalArgumentException("Invalid terminal reply");
		upgrades = List.copyOf(upgrades);
		if (upgrades.size() > NetworkSelectionSession.PAGE_SIZE || upgrades.stream().map(UpgradeResult::row).distinct().count() != upgrades.size()
				|| (status == Status.BATCH_COMPLETE) != !upgrades.isEmpty()
				|| !upgrades.isEmpty() && (view != null || preview != null || interruptedTicks != 0 || moved != upgrades.stream().mapToInt(UpgradeResult::moved).sum())
				|| preview != null && (status != Status.OK || moved != 0 || interruptedTicks != 0 || view != null))
			throw new IllegalArgumentException("Invalid upgrade reply");
	}
	@Override public Type<TerminalReply> type() { return TYPE; }
	static TerminalView readView(FriendlyByteBuf b) {
		var kind = b.readEnum(NetworkSelectionSession.Kind.class); long generation = b.readLong(); boolean next = b.readBoolean();
		int size = boundedSize(b, kind == NetworkSelectionSession.Kind.PRODUCTS ? NetworkSelectionSession.PRODUCT_PAGE_SIZE : NetworkSelectionSession.PAGE_SIZE);
		var rows = new ArrayList<TerminalView.Row>(size);
		for (int i = 0; i < size; i++) {
			String label = b.readUtf(TerminalView.TEXT_LIMIT); boolean fluid = b.readBoolean();
			String detail = b.readUtf(TerminalView.TEXT_LIMIT);
			String icon = b.readUtf(com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductIconPreview.MAX_TEXT);
			String owned = b.readUtf(TerminalView.AMOUNT_LIMIT), available = b.readUtf(TerminalView.AMOUNT_LIMIT); boolean exact = b.readBoolean();
			int count = boundedSize(b, 3); var bees = new ArrayList<TerminalView.Bee>(count);
			for (int j = 0; j < count; j++) bees.add(new TerminalView.Bee(b.readUnsignedByte(), b.readBoolean(),
					b.readUtf(TerminalView.TEXT_LIMIT), b.readInt(), b.readInt(), b.readBoolean(),
					b.readUtf(TerminalView.TEXT_LIMIT), b.readUnsignedByte(), b.readBoolean(), b.readBoolean() ? TerminalBeeGenes.read(b) : null,
					b.readBoolean() ? b.readUUID() : null, b.readBoolean()));
			int upgradesCount = boundedSize(b, TerminalView.MAX_UPGRADES); var upgrades = new ArrayList<TerminalView.Upgrade>(upgradesCount);
			for (int j = 0; j < upgradesCount; j++) upgrades.add(new TerminalView.Upgrade(b.readUnsignedByte(), b.readUtf(TerminalView.TEXT_LIMIT), b.readInt(), b.readInt(), b.readBoolean()));
			var location = b.readBoolean() ? new TerminalView.Location(b.readUtf(TerminalView.TEXT_LIMIT),
					b.readUtf(TerminalView.TEXT_LIMIT), b.readInt(), b.readInt(), b.readInt()) : null;
			var apiary = b.readBoolean() ? new TerminalView.Apiary(b.readInt(), b.readFloat()) : null;
			rows.add(new TerminalView.Row(label, fluid, owned, available, exact, bees, detail, icon, upgrades, location, apiary));
		}
		return new TerminalView(kind, generation, next, rows);
	}
	private static int boundedSize(FriendlyByteBuf b, int max) {
		int size = b.readUnsignedByte(); if (size > max) throw new IllegalArgumentException("Oversized terminal list"); return size;
	}
	static void writeView(FriendlyByteBuf b, TerminalView view) {
		b.writeEnum(view.kind()); b.writeLong(view.generation()); b.writeBoolean(view.hasNext()); b.writeByte(view.rows().size());
		for (var row : view.rows()) {
			b.writeUtf(row.label(), TerminalView.TEXT_LIMIT); b.writeBoolean(row.fluid());
			b.writeUtf(row.detail(), TerminalView.TEXT_LIMIT);
			b.writeUtf(row.icon(), com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductIconPreview.MAX_TEXT);
			b.writeUtf(row.owned(), TerminalView.AMOUNT_LIMIT); b.writeUtf(row.available(), TerminalView.AMOUNT_LIMIT);
			b.writeBoolean(row.exact()); b.writeByte(row.bees().size());
			for (var bee : row.bees()) {
				b.writeByte(bee.slot()); b.writeBoolean(bee.occupied()); b.writeUtf(bee.type(), TerminalView.TEXT_LIMIT);
				b.writeInt(bee.progress()); b.writeInt(bee.cycleTicks()); b.writeBoolean(bee.pending());
				b.writeUtf(bee.feedingItem(), TerminalView.TEXT_LIMIT); b.writeByte(bee.feedingCount()); b.writeBoolean(bee.feedingDisabled());
				b.writeBoolean(bee.genes() != null); if (bee.genes() != null) bee.genes().write(b);
				b.writeBoolean(bee.identity() != null); if (bee.identity() != null) b.writeUUID(bee.identity()); b.writeBoolean(bee.enabled());
			}
			b.writeByte(row.upgrades().size());
			for (var upgrade : row.upgrades()) {
				b.writeByte(upgrade.choice()); b.writeUtf(upgrade.item(), TerminalView.TEXT_LIMIT);
				b.writeInt(upgrade.installed()); b.writeInt(upgrade.limit()); b.writeBoolean(upgrade.installable());
			}
			b.writeBoolean(row.location() != null);
			if (row.location() != null) {
				var p = row.location(); b.writeUtf(p.machine(), TerminalView.TEXT_LIMIT); b.writeUtf(p.dimension(), TerminalView.TEXT_LIMIT);
				b.writeInt(p.x()); b.writeInt(p.y()); b.writeInt(p.z());
			}
			b.writeBoolean(row.apiary() != null);
			if (row.apiary() != null) { b.writeInt(row.apiary().cycleTicks()); b.writeFloat(row.apiary().productivity()); }
		}
	}
}
