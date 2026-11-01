package com.ayoshiko.productivebeesgenesis.resource;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** 当前世界的资源账户集合；有界、服务器线程独占，损坏数据整体隔离并保留原文。 */
public final class ResourceAccounts {
	/** 初版内部容量门；满载只拒绝新建，不驱逐已有资产或凭证。 */
	public static final int MAX_ACCOUNTS = 512;
	private final Thread ownerThread = Thread.currentThread();
	private final Map<UUID, ResourceAccount> accounts = new LinkedHashMap<>();
	private final Runnable changed;
	private CompoundTag preservedData;
	private String persistenceFailure = "";
	private boolean closed;
	private long revision;

	/** 创建空集合；changed 只允许标记保存 dirty，不执行资产交接或外部回调。 */
	public ResourceAccounts(Runnable changed) {
		this.changed = Objects.requireNonNull(changed);
	}

	/** 当前变更代际用于异步快照完成确认。 */
	public long revision() { checkThread(); return revision; }
	/** 返回加载或写盘故障；故障不会把已有余额清空。 */
	public String persistenceFailure() { checkThread(); return persistenceFailure; }
	/** 是否保留不可解释的原始存档；该状态不允许保存覆盖磁盘。 */
	public boolean hasInvalidData() { checkThread(); return preservedData != null; }

	/** 建立唯一位置绑定；调用入口负责验证实际方块、所有者和距离。 */
	public ResourceAccount create(UUID owner, ResourceAccount.Binding binding) {
		checkThread();
		Objects.requireNonNull(owner);
		Objects.requireNonNull(binding);
		if (accounts.size() >= MAX_ACCOUNTS) throw new IllegalStateException("Resource account limit reached");
		checkBindingFree(binding, null);
		UUID id;
		do {
			id = UUID.randomUUID();
		} while (accounts.containsKey(id));
		var account = new ResourceAccount(this, id, owner, binding);
		beforeChange();
		accounts.put(id, account);
		return account;
	}

	/** 仅删除无绑定、无资产、无未决请求和无暂停原文的账户；旧对象也立即失效。 */
    public boolean retireEmpty(ResourceAccount.Reference reference) {
        checkThread();
        var account = accounts.get(reference.sourceId());
        if (account == null || !account.reference().equals(reference)) throw new IllegalStateException("Stale account retirement");
        if (account.binding != null || !account.available.isEmpty() || account.production != null || account.transfer != null
                || !account.centrifugeWork.isEmpty() || !account.suspendedLanes.isEmpty()
                || !account.suspendedTransfers.isEmpty() || !account.suspension.isEmpty()) return false;
        beforeChange();
        accounts.remove(account.id);
        return true;
    }

    void checkLive(ResourceAccount account) {
        checkThread();
        if (accounts.get(account.id) != account) throw new IllegalStateException("Retired resource account");
    }

    public int size() { checkThread(); return accounts.size(); }

	/** 有界的管理入口快照；不在 tick 中扫描历史账户。 */
	public java.util.List<ResourceAccount> snapshot() {
		checkThread();
		return java.util.List.copyOf(accounts.values());
	}

	/** 用身份查找已存在账户；不根据残留的旧物品引用自动重建空账户。 */
	public ResourceAccount find(UUID id) {
		checkThread();
		return accounts.get(id);
	}

	/** 验证代际和实际绑定后取得账户；能力持有者仍须在每次操作时验证宿主有效。 */
	public ResourceAccount bound(ResourceAccount.Reference reference, ResourceAccount.Binding binding) {
		checkThread();
		var account = accounts.get(reference.sourceId());
		if (account == null || !account.reference().equals(reference) || !Objects.equals(account.binding, binding)
				|| binding == null) {
			throw new IllegalStateException("Unknown or stale resource binding");
		}
		return account;
	}

	void checkBindingFree(ResourceAccount.Binding binding, UUID except) {
		checkThread();
		for (var account : accounts.values()) {
			if (!account.id.equals(except) && account.binding != null
					&& binding.dimension().equals(account.binding.dimension())
					&& binding.packedPosition() == account.binding.packedPosition()) {
				throw new IllegalStateException("A resource source already owns this binding");
			}
		}
	}

	void checkThread() {
		if (Thread.currentThread() != ownerThread || closed) {
			throw new IllegalStateException("Resource account accessed outside its owning server lifecycle");
		}
	}

	void beforeChange() {
		checkThread();
		if (preservedData != null || !persistenceFailure.isEmpty()) {
			throw new IllegalStateException("Resource accounts are isolated: " + persistenceFailure);
		}
		long next = Math.incrementExact(revision);
		changed.run();
		revision = next;
	}

	void failPersistence(String reason) {
		checkThread();
		persistenceFailure = Objects.requireNonNull(reason);
	}

	void confirmPersistence() {
		checkThread();
		if (preservedData == null) persistenceFailure = "";
	}

	/** 返回独立快照；编码过程不依赖任意资源桥接模组。 */
	public CompoundTag save() {
		checkThread();
		if (preservedData != null) return preservedData.copy();
		var root = new CompoundTag();
		root.putInt("version", 1);
		root.putLong("revision", revision);
		var entries = new ListTag();
		accounts.values().forEach(account -> entries.add(ResourceAccountData.save(account)));
		root.put("accounts", entries);
		return root;
	}

	/** 加载完整账户；任何未知版本、重复身份、损坏条目均保留原始 root 并禁止资产变更。 */
	public static ResourceAccounts load(CompoundTag root, Runnable changed) {
		var result = new ResourceAccounts(changed);
		try {
			ResourceAccountData.keys(root, Set.of("version", "revision", "accounts"));
			if (!root.contains("version", Tag.TAG_INT) || root.getInt("version") != 1
					|| !(root.get("accounts") instanceof ListTag list) || list.size() > MAX_ACCOUNTS
					|| (!list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND)) {
				throw new IllegalArgumentException("Invalid resource account store");
			}
			result.revision = ResourceAccountData.number(root, "revision");
			if (result.revision < 0) throw new IllegalArgumentException("Invalid account revision");
			for (Tag entry : list) {
				var account = ResourceAccountData.load(result, (CompoundTag) entry);
				if (account.binding != null) result.checkBindingFree(account.binding, null);
				if (result.accounts.putIfAbsent(account.id, account) != null) {
					throw new IllegalArgumentException("Duplicate resource source identity");
				}
			}
		} catch (RuntimeException failure) {
			result.accounts.clear();
			result.preservedData = root.copy();
			result.persistenceFailure = failure.toString();
		}
		return result;
	}

	/** 关闭后拒绝旧引用；不删除账户或世界存档。 */
	public void close() {
		checkThread();
		closed = true;
	}
}
