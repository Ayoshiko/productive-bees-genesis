package com.ayoshiko.productivebeesgenesis.apiculture.persistence;

import com.ayoshiko.productivebeesgenesis.apiculture.persistence.read.*;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductKey;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.nbt.Tag;
import static com.ayoshiko.productivebeesgenesis.apiculture.persistence.CheckpointDecodeFrames.*;

/** 所属线程的预算化解码与交叉校验；文件 CRC 通过前不公开任何候选状态。 */
public final class CheckpointDecoder implements AutoCloseable {
	public enum State { READING, VALIDATING, COMPLETE, FAILED, CANCELLED }
	public record Progress(State state, long steps, long maxStepNanos, String failure) { }
	private final Thread owner = Thread.currentThread();
	private final CheckpointReadSession input;
	private final boolean directory;
	private final Consumer<ProductKey> validateKey;
	private final Consumer<com.ayoshiko.productivebeesgenesis.apiculture.feeding.FeedingItem> validateFeeding;
	private final ArrayDeque<Frame> stack = new ArrayDeque<>();
	private NbtReadBatch batch;
	private int cursor;
	private Object candidate;
	private Object result;
	private State state = State.READING;
	private String failure = "";
	private long steps, maxStepNanos;
	public CheckpointDecoder(CheckpointReadSession input, Consumer<ProductKey> validateKey) { this(input, validateKey, item -> { }); }
	public CheckpointDecoder(CheckpointReadSession input, Consumer<ProductKey> validateKey, Consumer<com.ayoshiko.productivebeesgenesis.apiculture.feeding.FeedingItem> validateFeeding) {
		this(input, validateKey, validateFeeding, false);
	}
	static CheckpointDecoder directory(CheckpointReadSession input) { return new CheckpointDecoder(input, key -> { }, item -> { }, true); }
	private CheckpointDecoder(CheckpointReadSession input, Consumer<ProductKey> validateKey, Consumer<com.ayoshiko.productivebeesgenesis.apiculture.feeding.FeedingItem> validateFeeding, boolean directory) {
		this.input = Objects.requireNonNull(input); this.validateKey = Objects.requireNonNull(validateKey); this.directory = directory;
		this.validateFeeding = Objects.requireNonNull(validateFeeding);
	}
	/** 时间是软预算；一次组件 codec／大数计算不可抢占，maxStepNanos 单独披露。 */
	public Progress step(int maxSteps, long budgetNanos) {
		check(); if (maxSteps <= 0 || budgetNanos <= 0) throw new IllegalArgumentException("Positive decode budget required");
		long started = System.nanoTime();
		try {
			// 终态只在工作段边界检查，避免为每个字段重复分配邮箱统计快照。
			var inputStatus = input.status();
			if (inputStatus.state() == CheckpointReadSession.State.FAILED) { fail(inputStatus.failure()); return progress(); }
			if (inputStatus.state() == CheckpointReadSession.State.CANCELLED) { close(); return progress(); }
			for (int count = 0; count < maxSteps && (count == 0 || System.nanoTime() - started < budgetNanos); count++) {
				if (state != State.READING && state != State.VALIDATING) break;
				long before = System.nanoTime();
				try { if (!oneStep()) break; }
				finally { maxStepNanos = Math.max(maxStepNanos, System.nanoTime() - before); }
				steps++;
			}
		} catch (RuntimeException error) { fail(error.toString()); }
		return progress();
	}
	private boolean oneStep() {
		if (state == State.VALIDATING) {
			if (candidate instanceof NetworkRestoreState network) {
				if (!network.validateStep()) return true;
				result = network.finish();
			} else if (candidate instanceof CheckpointSchema.DirectoryState directory) {
				if (!directory.validateStep()) return true;
				result = directory;
			} else result = candidate;
			candidate = null; state = State.COMPLETE; return true;
		}
		if (batch == null) { batch = input.poll(); cursor = 0; }
		if (batch != null) {
			accept(batch.events().get(cursor++));
			if (cursor == batch.events().size()) batch = null;
			return true;
		}
		if (input.status().drained()) {
			if (!stack.isEmpty() || candidate == null) throw new IllegalArgumentException("Incomplete checkpoint root");
			state = State.VALIDATING; return true;
		}
		return false;
	}
	private void accept(NbtReadEvent event) {
		if (event instanceof NbtReadEvent.Start start) { start(start); return; }
		if (stack.isEmpty()) throw new IllegalArgumentException("Data outside root");
		Frame frame = stack.peek();
		if (event instanceof NbtReadEvent.Scalar scalar) {
			if (frame instanceof Domain domain) domain.node().begin(scalar.name(), scalar.value().getId());
			else if (frame instanceof Raw raw) raw.begin(scalar.name(), scalar.value().getId());
			else throw new IllegalArgumentException("Scalar in record list");
			frame.add(scalar.name(), scalar.value());
		} else if (event instanceof NbtReadEvent.ArrayChunk chunk) {
			if (!(frame instanceof Raw raw)) throw new IllegalArgumentException("Unexpected array chunk");
			raw.chunk(chunk);
		} else if (event instanceof NbtReadEvent.End end) {
			if (frame.type() != end.type()) throw new IllegalArgumentException("Mismatched container end");
			Object value = frame.finish(); stack.pop();
			if (stack.isEmpty()) candidate = value; else stack.peek().add(frame.name(), value);
		}
	}
	private void start(NbtReadEvent.Start start) {
		if (stack.isEmpty()) {
			if (candidate != null || start.type() != Tag.TAG_COMPOUND || !"".equals(start.name())) throw new IllegalArgumentException("Invalid checkpoint root");
			stack.push(domain(start.name(), CheckpointSchema.Kind.ROOT)); return;
		}
		Frame parent = stack.peek();
		if (parent instanceof Raw raw) { raw.begin(start.name(), start.type()); stack.push(new Raw(start)); return; }
		if (parent instanceof Records records) {
			if (start.name() != null || start.type() != Tag.TAG_COMPOUND) throw new IllegalArgumentException("Expected record compound");
			stack.push(domain(null, records.element)); return;
		}
		var parentNode = ((Domain) parent).node();
		var field = parentNode.begin(start.name(), start.type());
		if (field.element() != null) {
			if (start.length() < 0 || start.elementType() != Tag.TAG_COMPOUND && (start.length() != 0 || start.elementType() != Tag.TAG_END)) throw new IllegalArgumentException("Invalid record list type");
			stack.push(new Records(start.name(), field.element(), start.length(), parentNode.list(start.name())));
		} else if (field.nested() != null && field.nested() != CheckpointSchema.Kind.RAW) {
			var child = domain(start.name(), field.nested());
			if (field.nested() == CheckpointSchema.Kind.WATERMARKS) child.node().watermarkTarget = parentNode;
			stack.push(child);
		} else {
			if (field.type() == Tag.TAG_INT_ARRAY && start.length() != 4) throw new IllegalArgumentException("Invalid UUID length");
			stack.push(new Raw(start));
		}
	}
	private Domain domain(String name, CheckpointSchema.Kind kind) { return new Domain(name, new CheckpointSchema.Node(kind, directory, validateKey, validateFeeding)); }
	public Progress progress() { check(); return new Progress(state, steps, maxStepNanos, failure); }
	public NetworkCheckpoint checkpoint() {
		check(); if (state != State.COMPLETE || directory) throw new IllegalStateException("No completed network checkpoint");
		return (NetworkCheckpoint) result;
	}
	CheckpointSchema.DirectoryState directoryState() {
		check(); if (state != State.COMPLETE || !directory) throw new IllegalStateException("No completed directory");
		return (CheckpointSchema.DirectoryState) result;
	}
	private void fail(String reason) { failure = reason; state = State.FAILED; discard(); }
	private void discard() { input.close(); stack.clear(); batch = null; candidate = null; result = null; }
	@Override public void close() { check(); if (state != State.FAILED) state = State.CANCELLED; discard(); }
	private void check() { if (Thread.currentThread() != owner) throw new IllegalStateException("Decode on the owning server thread"); }
}
