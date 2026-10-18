package com.ayoshiko.productivebeesgenesis.resource;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary;
import com.ayoshiko.productivebeesgenesis.inventory.TieredInputSlot;
import com.ayoshiko.productivebeesgenesis.mek.PbRecipeContext;
import mekanism.api.inventory.IInventorySlot;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import cy.jdkdigital.productivebees.common.block.entity.AdvancedBeehiveBlockEntity;
import cy.jdkdigital.productivebees.common.block.entity.AdvancedBeehiveBlockEntityAbstract;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.common.util.INBTSerializable;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import net.neoforged.neoforge.registries.RegisterEvent;

import java.util.UUID;
import java.math.BigInteger;

/** PB 与机械蜂箱共用的小型账户引用；资源本身永远保留在世界账户。 */
@EventBusSubscriber(modid = ProductiveBeesGenesis.MOD_ID)
public final class ResourceHiveHost implements INBTSerializable<CompoundTag> {
	/** 每资源身份的基础额度；具体单位由资源种类决定，机械等级使用输出槽倍率。 */
	public static final long DEFAULT_CAPACITY = 1_000_000;
	/** 常驻附件类型，不依赖 AE2 或任何化学品桥接。 */
	public static final AttachmentType<ResourceHiveHost> TYPE = AttachmentType.serializable(ResourceHiveHost::new).build();
	private ResourceAccount.Reference reference;
	private ResourceAccount.Binding accountBinding;
	private BlockEntityType<?> bindingType;
	private UUID outputPort;
	private long outputPosition;
	private long capacity = DEFAULT_CAPACITY;
	private boolean mechanicalCapacityResolved;
	private CompoundTag preservedInvalid;
	private String status = "";
	private boolean meOutputEnabled = true;
	private UUID emcRecipient;
	private boolean meOutputMigrated = true;
	private String meStatus = "offline";
	private long nextBindAttempt;
	private final java.util.List<CompoundTag> unresolvedReferences = new java.util.ArrayList<>();
	private ResourceAccount lastRecoveryAccount;
	ResourceCentrifugeProcessor centrifugeProcessor;
	private ResourceProductionMonitor productionMonitor;
	private ResourceSideConfig nativeConfig = new ResourceSideConfig();
    private Tag invalidNativeConfig;
	public ResourceSideConfig nativeConfig() { return nativeConfig; }
    public boolean nativeConfigValid() { return preservedInvalid == null && invalidNativeConfig == null; }
    public Tag nativeConfigData() { return invalidNativeConfig == null ? nativeConfig.save() : invalidNativeConfig.copy(); }
    public void readNativeConfig(BlockEntity host, Tag data) {
        try {
            if (!(data instanceof CompoundTag tag)) throw new IllegalArgumentException("Invalid native resource side root");
            nativeConfig = ResourceSideConfig.load(tag);
            invalidNativeConfig = null;
        } catch (IllegalArgumentException invalid) {
            invalidNativeConfig = data.copy();
        }
        migrateNativeEnergy(host);
        if (outputAccess != null) outputAccess.configurationChanged();
        host.invalidateCapabilities();
    }
	public boolean nativeOutput(BlockEntity host, ResourceNativeType type, net.minecraft.core.Direction side) {
		if (type == ResourceNativeType.ENERGY && ResourceMekEnergyConfig.manages(host)) {
			return nativeConfigValid() && ResourceMekEnergyConfig.output(host, side);
		}
		return nativeConfigValid() && nativeConfig.output(host, type, side);
	}
	void migrateNativeEnergy(BlockEntity host) {
		if (!nativeConfigValid() || !nativeConfig.energyMigrationPending() || !ResourceMekEnergyConfig.manages(host)) return;
		ResourceMekEnergyConfig.set(host, nativeConfig.mask(ResourceNativeType.ENERGY),
				nativeConfig.eject(ResourceNativeType.ENERGY));
		nativeConfig.energyMigrationComplete();
		host.setChanged();
	}
	public boolean nativeEject(BlockEntity host, ResourceNativeType type) {
		if (!nativeConfigValid()) return false;
		if (type == ResourceNativeType.ENERGY && ResourceMekEnergyConfig.manages(host)) return ResourceMekEnergyConfig.eject(host);
		return nativeConfig.eject(type);
	}
	public int nativeMask(BlockEntity host, ResourceNativeType type) {
		if (!nativeConfigValid()) return 0;
		if (type == ResourceNativeType.ENERGY && ResourceMekEnergyConfig.manages(host)) return ResourceMekEnergyConfig.mask(host);
		return nativeConfig.mask(type);
	}
	public void setNativeOutput(BlockEntity host, ResourceNativeType type, int mask, boolean eject) {
		if (!nativeConfigValid()) throw new IllegalStateException("Invalid native resource configuration");
		if (!type.available()) throw new IllegalArgumentException("Native resource dependency missing");
		if (type == ResourceNativeType.ENERGY && ResourceMekEnergyConfig.manages(host)) {
			ResourceMekEnergyConfig.set(host, mask, eject);
		}
		if (nativeConfig.set(type, mask, eject)) {
			if (outputAccess != null) outputAccess.configurationChanged();
			host.invalidateCapabilities();
			changed(host);
		}
	}
	public ResourceProductionMonitor productionMonitor() {
		if (productionMonitor == null) productionMonitor = new ResourceProductionMonitor();
		return productionMonitor;
	}
	ResourceOutputAccess outputAccess;
	CompoundTag meNodeData = new CompoundTag();

	/** 在注册期登记引用附件。 */
	@SubscribeEvent
	public static void register(RegisterEvent event) {
		event.register(NeoForgeRegistries.Keys.ATTACHMENT_TYPES,
				ResourceLocation.parse("productivebeesgenesis:resource_host"), () -> TYPE);
	}

	/** 只允许本次接入的真实宿主类型，不能把任意方块变成资源源。 */
	public static boolean supports(BlockEntity host) {
		return builtIn(host) || com.ayoshiko.productivebeesgenesis.api.resource.ResourceHostApi.adapter(host) != null;
	}

	private static boolean builtIn(BlockEntity host) {
		return host instanceof AdvancedBeehiveBlockEntity || host instanceof TileEntityMekApiary
				|| host instanceof cy.jdkdigital.productivebees.common.block.entity.CentrifugeBlockEntity || isMekCentrifuge(host);
	}

	public static boolean isMekCentrifuge(BlockEntity host) {
		return host instanceof com.ayoshiko.productivebeesgenesis.mek.IMekCentrifugeTile;
	}

	/** 机械宿主继续服从 Mekanism 原有安全配置。 */
	public static boolean canAccess(net.minecraft.server.level.ServerPlayer player, BlockEntity host) {
		if (builtIn(host)) return !(host instanceof TileEntityMekApiary || isMekCentrifuge(host))
				|| mekanism.api.security.IBlockSecurityUtils.INSTANCE.canAccess(player, host.getLevel(), host.getBlockPos(), host);
		var adapter = com.ayoshiko.productivebeesgenesis.api.resource.ResourceHostApi.adapter(host);
		return adapter != null && adapter.canAccess(player, host);
	}

	/** 每资源身份沿用宿主输出槽的等级倍率，不按物品最大堆叠数换算资源单位。 */
	public static long capacityFor(BlockEntity host) {
		IInventorySlot output = null;
		if (host instanceof TileEntityMekApiary apiary && !apiary.getOutputSlots().isEmpty()) {
			output = apiary.getOutputSlots().getFirst();
		} else if (isMekCentrifuge(host) && host instanceof PbRecipeContext centrifuge) {
			output = centrifuge.secondaryOutputSlot(0);
		}
		if (builtIn(host)) {
			int multiplier = output instanceof TieredInputSlot tiered
					? Math.max(1, tiered.productivebeesgenesis$getCachedMultiplier()) : 1;
			return Math.multiplyExact(DEFAULT_CAPACITY, multiplier);
		}
		var adapter = com.ayoshiko.productivebeesgenesis.api.resource.ResourceHostApi.adapter(host);
		if (adapter == null) throw new IllegalStateException("Unregistered resource host");
		long value = adapter.capacity(host);
		if (value < 1) throw new IllegalArgumentException("Invalid resource host capacity");
		return value;
	}

	public static boolean hasBees(BlockEntity host) {
		if (host instanceof AdvancedBeehiveBlockEntity pb) return pb.getOccupantCount() != 0;
		if (host instanceof TileEntityMekApiary apiary) {
			for (var slot : apiary.getBeeSlots()) if (!slot.isEmpty()) return true;
		}
		// 恢复确认会清除旧工作记录；输入仍在时不能让同一份蜜脾重新加工。
		if (isMekCentrifuge(host) && host instanceof com.ayoshiko.productivebeesgenesis.mek.PbRecipeContext machine) {
			for (int lane = 0; lane < machine.processes(); lane++) if (!machine.inputSlot(lane).isEmpty()) return true;
		}
		if (host instanceof cy.jdkdigital.productivebees.common.block.entity.CentrifugeBlockEntity centrifuge) {
			return !centrifuge.getItemHandler().getStackInSlot(1).isEmpty();
		}
		var adapter = builtIn(host) ? null : com.ayoshiko.productivebeesgenesis.api.resource.ResourceHostApi.adapter(host);
		return adapter != null && adapter.hasLiveWork(host);
	}

	public static boolean ready(BlockEntity host) {
		if (!builtIn(host)) {
			var adapter = com.ayoshiko.productivebeesgenesis.api.resource.ResourceHostApi.adapter(host);
			return adapter != null && adapter.ready(host);
		}
		// PB 原版仅保留旧账户识别与恢复，不再启用扩展资源生产／新绑定。
		return host instanceof TileEntityMekApiary || isMekCentrifuge(host);
	}

	private static void changed(BlockEntity host) {
		if (host instanceof AdvancedBeehiveBlockEntityAbstract pb) pb.setNonSuperChanged();
		else host.setChanged();
	}

	/** 读取已存在附件；普通蜂箱不因此分配附件或创建账户。 */
	public static ResourceHiveHost existing(BlockEntity host) {
		return host.getExistingDataOrNull(TYPE);
	}

	/** 只在实际资源配方命中后调用；沿用机器安全拥有者，不要求额外放置接口。 */
	public static ResourceHiveHost ensureMechanical(BlockEntity host) {
		if (!(host instanceof TileEntityMekApiary || isMekCentrifuge(host))
				|| !(host.getLevel() instanceof ServerLevel level) || !level.getServer().isSameThread()
				|| host.isRemoved() || level.getBlockEntity(host.getBlockPos()) != host) return null;
		var state = host.getData(TYPE);
		if (state.preservedInvalid != null) return state;
		if (state.reference != null) {
			state.recoverMechanicalReference(host, level);
			return state;
		}
		if (!(host instanceof mekanism.common.lib.security.ISecurityTile security) || security.getOwnerUUID() == null) {
			state.status("missing_owner");
			return state;
		}
		long tick = level.getGameTime();
		if (tick < state.nextBindAttempt) return state;
		state.nextBindAttempt = tick + 20;
		try {
			bind(host, security.getOwnerUUID(), capacityFor(host));
			state.status("");
		} catch (IllegalStateException denied) {
			// 额度或保存数据不允许新建时保持输入；不创建匿名账户或覆盖旧身份。
			state.status("binding_unavailable");
		}
		return state;
	}

	/** 旧账户不猜零余额、不重建同一 UUID；只给实际机器的后续生产分配独立身份。 */
	private void recoverMechanicalReference(BlockEntity host, ServerLevel level) {
		if (!(host instanceof mekanism.common.lib.security.ISecurityTile security) || security.getOwnerUUID() == null) return;
		var accounts = ResourceRuntime.accounts(level.getServer()).accounts();
		var old = accounts.find(reference.sourceId());
		var binding = binding(host);
		if (old != null && old.reference().equals(reference) && binding.equals(old.binding())) return;
		long tick = level.getGameTime();
		if (tick < nextBindAttempt && old == lastRecoveryAccount) return;
		lastRecoveryAccount = old;
		nextBindAttempt = tick + 40;
		if (accounts.hasInvalidData() || !accounts.persistenceFailure().isEmpty() || unresolvedReferences.size() >= 4) return;
		// 现存账户的其它绑定、代际与托管状态必须显式核验；这里只修复账户确实缺失的引用。
		if (old != null) return;
		var previous = reference;
		var evidence = new CompoundTag();
		evidence.putUUID("source", previous.sourceId());
		evidence.putLong("generation", previous.generation());
		evidence.putString("reason", "missing_account");
		try {
			var replacement = accounts.create(security.getOwnerUUID(), binding);
			unresolvedReferences.add(evidence);
			reference = replacement.reference();
			productionMonitor = null;
			accountBinding = binding;
			bindingType = host.getType();
			outputPort = null;
			emcRecipient = null;
			centrifugeProcessor = null;
			if (outputAccess != null) outputAccess.invalidate();
			outputAccess = null;
			status = "reference_recovered";
			changed(host);
			ResourceOutputAccess.find(host);
			ProductiveBeesGenesis.LOGGER.warn("资源引用已隔离并恢复后续生产，位置 {}，旧引用 {}，新引用 {}；未迁移或补发旧账户资产",
					host.getBlockPos(), previous, reference);
		} catch (IllegalStateException unavailable) {
			status = "binding_unavailable";
		}
	}

	public boolean meOutputEnabled() { return meOutputMigrated && meOutputEnabled; }
	/** 旧开发存档的 ME 开关在接口上；源区块先加载时先保管，读到接口后再迁移。 */
	public void migrateMeOutput(BlockEntity host) {
		if (meOutputMigrated || preservedInvalid != null) return;
		// 未发布旧存档只在接口上存开关；不猜测，迁移为关闭后由机器窗口显式开启。
		setMeOutputEnabled(host, false);
	}
	public String meStatus() { return meStatus; }
	public void meStatus(String value) { meStatus = value; }
	public void setMeOutputEnabled(BlockEntity host, boolean enabled) {
		account(host);
		if (!meOutputMigrated || meOutputEnabled != enabled) {
			meOutputEnabled = enabled; meOutputMigrated = true; changed(host);
		}
	}

	/** 受信任的服务器入口绑定；玩家身份、权限和距离须由实际交互入口先验证。 */
	public static ResourceAccount.Reference bind(BlockEntity host, UUID owner, long capacity) {
		if (capacity < 1 || !(host.getLevel() instanceof ServerLevel level) || host.isRemoved()
				|| level.getBlockEntity(host.getBlockPos()) != host
				|| !ready(host)) {
			throw new IllegalStateException("PB resource host is not ready");
		}
		var state = host.getData(TYPE);
		if (state.preservedInvalid != null || state.reference != null) {
			throw new IllegalStateException("PB resource host is already bound or has invalid data");
		}
		var account = ResourceRuntime.accounts(level.getServer()).accounts().create(owner, binding(host));
		state.reference = account.reference();
		state.productionMonitor = null;
		state.emcRecipient = null;
		state.capacity = capacity;
		changed(host);
		ResourceOutputAccess.find(host);
		return state.reference;
	}

	/** 把托管账户认领到实际蜂箱；仅引用迁移，不在方块或物品中复制资源。 */
	public static ResourceAccount.Reference claim(BlockEntity host, UUID owner,
			ResourceAccount.Reference source) {
		if (!(host.getLevel() instanceof ServerLevel level) || host.isRemoved()
				|| level.getBlockEntity(host.getBlockPos()) != host
				|| !ready(host)) {
			throw new IllegalStateException("PB resource host is not ready");
		}
		var accounts = ResourceRuntime.accounts(level.getServer()).accounts();
		var account = accounts.find(source.sourceId());
		if (account == null) throw new IllegalStateException("Unknown resource account");
		var state = host.getData(TYPE);
		if (state.preservedInvalid != null || (state.reference != null
				&& (!state.reference.sourceId().equals(source.sourceId())
				|| state.reference.generation() >= source.generation()))) {
			throw new IllegalStateException("Target hive already has a resource reference");
		}
		long nextCapacity = capacityFor(host);
		for (var entry : account.availableExact().entrySet()) {
            if (!ResourceTypes.EMC.equals(entry.getKey()) && entry.getValue().compareTo(BigInteger.valueOf(nextCapacity)) > 0)
                throw new IllegalStateException("Target resource buffer is too small");
        }
		state.reference = account.claim(owner, source, binding(host));
		state.productionMonitor = null;
		state.emcRecipient = null;
		state.outputPort = null;
		state.capacity = nextCapacity;
		state.status = "";
		changed(host);
		ResourceOutputAccess.find(host);
		return state.reference;
	}

	/** 主动解绑不发放携带库存的物品；所有者从世界账户列表重新认领。 */
	public ResourceAccount.Reference detach(BlockEntity host, UUID owner,
			ResourceAccount.Reference expected) {
		if (!java.util.Objects.equals(reference, expected)) throw new IllegalStateException("Stale resource reference");
		var detached = account(host).detach(owner, expected);
		reference = null;
		outputPort = null;
		productionMonitor = null;
		emcRecipient = null;
		status = "";
		changed(host);
		return detached;
	}

	/** 升级票据只存在于服务器内存，不放入方块物品或配置卡，不携带资源库存。 */
	private boolean upgradePrepared;

	public static final class Upgrade {
		private final BlockEntity previous;
		private final ResourceAccount.Reference original;
		private final CompoundTag snapshot;
		private boolean applied;

		private Upgrade(BlockEntity previous, ResourceHiveHost state) {
			this.previous = previous;
			this.original = state.reference;
			this.snapshot = state.serializeNBT(previous.getLevel().registryAccess());
		}

		/** 必须已经替换同位置的旧蜂箱；一次性认领下一代账户后刷新已加载的唯一接口。 */
		public void apply(BlockEntity target) {
			if (applied || !ready(target) || isMekCentrifuge(previous) != isMekCentrifuge(target)
					|| previous == target || !previous.isRemoved()
					|| previous.getLevel() != target.getLevel()
					|| !previous.getBlockPos().equals(target.getBlockPos())
					|| !(target.getLevel() instanceof ServerLevel level) || target.isRemoved()
					|| level.getBlockEntity(target.getBlockPos()) != target) {
				throw new IllegalStateException("Invalid or already used resource upgrade");
			}
            if (original == null) {
                var current = existing(target);
                if (current != null && (current.preservedInvalid != null || current.reference != null)) {
                    throw new IllegalStateException("Resource upgrade target is already bound");
                }
                var next = new ResourceHiveHost();
                next.deserializeNBT(level.registryAccess(), snapshot);
                target.setData(TYPE, next);
                applied = true;
                changed(target);
                return;
            }
			var account = ResourceRuntime.accounts(level.getServer()).accounts().find(original.sourceId());
			if (account == null || account.binding() != null
					|| account.reference().generation() != Math.incrementExact(original.generation())) {
				throw new IllegalStateException("Resource upgrade source was not retired exactly once");
			}
			var current = existing(target);
			if (current != null && (current.preservedInvalid != null
					|| (current.reference != null && !current.reference.equals(original)))) {
				throw new IllegalStateException("Resource upgrade target is already bound");
			}
			var next = new ResourceHiveHost();
			next.deserializeNBT(level.registryAccess(), snapshot);
			next.reference = account.claim(account.owner(), account.reference(), binding(target));
			applied = true;
			target.setData(TYPE, next);
			changed(target);
			ResourceOutputAccess.find(target);
		}
	}

	/** 安装器在取升级数据之前使用的只读拒绝门；不清库存、不解绑、不创建账户。 */
	public static boolean canUpgrade(BlockEntity host) {
		var state = existing(host);
		if (state == null) return true;
		if (state.preservedInvalid != null) return false;
		if (state.reference == null) return true;
		if (!(host.getLevel() instanceof ServerLevel level) || host.isRemoved()
				|| level.getBlockEntity(host.getBlockPos()) != host) return false;
		var account = ResourceRuntime.accounts(level.getServer()).accounts().find(state.reference.sourceId());
		return account != null && account.reference().equals(state.reference)
				&& java.util.Objects.equals(account.binding(), binding(host)) && account.canExtract(state.reference);
	}

	/** 只准备引用；升级失败、尚未替换方块时不解绑原账户。 */
	public static Upgrade prepareUpgrade(BlockEntity host) {
		var state = existing(host);
		if (state == null) return null;
		if (state.preservedInvalid != null) throw new IllegalStateException("Invalid resource host data");
		if (state.reference == null) return new Upgrade(host, state);
		if (!state.account(host).canExtract(state.reference)) {
			throw new IllegalStateException("Resolve unfinished resource requests before upgrading");
		}
		state.upgradePrepared = true;
		return new Upgrade(host, state);
	}

	/** 仅由 LevelChunk 的真实移除／替换入口调用；正常卸载不进入这里。 */
	public static boolean sameLiveReference(BlockEntity previous, BlockEntity replacement) {
		if (previous == replacement || replacement == null || replacement.isRemoved()
				|| previous.getLevel() != replacement.getLevel() || previous.getType() != replacement.getType()
				|| !previous.getBlockPos().equals(replacement.getBlockPos())) return false;
		var before = existing(previous);
		var after = existing(replacement);
		return before != null && after != null && before.preservedInvalid == null && after.preservedInvalid == null
				&& before.reference != null && before.reference.equals(after.reference);
	}

	public static void removed(net.minecraft.world.level.block.entity.BlockEntity entity) {
		if (!supports(entity) || !(entity.getLevel() instanceof ServerLevel level)) return;
		var hive = entity;
		var state = existing(hive);
		if (state != null) state.productionMonitor = null;
		if (state == null || state.reference == null || state.preservedInvalid != null) return;
		var accounts = ResourceRuntime.accounts(level.getServer()).accounts();
		var account = accounts.find(state.reference.sourceId());
		if (account == null || !account.reference().equals(state.reference)
				|| !java.util.Objects.equals(account.binding(), binding(hive))) return;
		account.retire(state.reference, binding(hive));
		if (!state.upgradePrepared) accounts.retireEmpty(account.reference());
	}

	/** 按真实方块实体与代际验证引用；卸载旧对象不再可用。 */
	public ResourceAccount account(BlockEntity host) {
		if (preservedInvalid != null || reference == null || !(host.getLevel() instanceof ServerLevel level)
				|| host.isRemoved() || level.getBlockEntity(host.getBlockPos()) != host) {
			throw new IllegalStateException("Invalid PB resource host reference");
		}
		var dimension = level.dimension().location();
		long position = host.getBlockPos().asLong();
		var type = host.getType();
		if (accountBinding == null || bindingType != type || accountBinding.packedPosition() != position
				|| !accountBinding.dimension().equals(dimension)) {
			accountBinding = binding(host);
			bindingType = type;
		}
		var account = ResourceRuntime.accounts(level.getServer()).accounts().bound(reference, accountBinding);
		if (!mechanicalCapacityResolved && (host instanceof TileEntityMekApiary || isMekCentrifuge(host))) {
			// 旧开发默认额度可向当前等级扩容；显式小额度和已有余额均不截断。
			if (capacity >= DEFAULT_CAPACITY) {
				long upgraded = Math.max(capacity, capacityFor(host));
				if (upgraded != capacity) {
					capacity = upgraded;
					changed(host);
				}
			}
			mechanicalCapacityResolved = true;
		}
		return account;
	}

	static ResourceAccount.Binding binding(BlockEntity host) {
		return new ResourceAccount.Binding(host.getLevel().dimension().location(), host.getBlockPos().asLong(),
				BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(host.getType()));
	}

	public PbResourceCatalog catalog(ServerLevel level) {
		return ResourceRuntime.pbCatalog(level);
	}

	public boolean fits(ResourceAccount account, java.util.Map<ResourceKey, Long> maximum) {
        return fitsExact(account, ResourceExactAmounts.copy(maximum));
    }

    public boolean fitsExact(ResourceAccount account, java.util.Map<ResourceKey, BigInteger> maximum) {
        var capacityUsed = account.reservedCapacity();
        for (var entry : maximum.entrySet()) {
            if (ResourceTypes.EMC.equals(entry.getKey())) continue;
            BigInteger occupied = capacityUsed.getOrDefault(entry.getKey(), BigInteger.ZERO).add(entry.getValue());
            if (occupied.compareTo(BigInteger.valueOf(capacity)) > 0) return false;
        }
        return account.canReserve(reference, maximum);
	}

	/** 唯一产物接口的选择只保存小引用；接口每次提取还需核验双方实际实体。 */
	void selectOutput(BlockEntity host, UUID port, net.minecraft.core.BlockPos pos) {
		account(host);
		if (java.util.Objects.equals(outputPort, port) && outputPosition == pos.asLong()) return;
		outputPort = port;
		outputPosition = pos.asLong();
		changed(host);
	}

	boolean isOutput(UUID port, net.minecraft.core.BlockPos pos) {
		return preservedInvalid == null && java.util.Objects.equals(outputPort, port) && outputPosition == pos.asLong();
	}

	public long capacity() { return capacity; }

	/** 空值沿用资源账户拥有者；只保存收款身份，不复制玩家余额。 */
	public UUID emcRecipient(BlockEntity host) {
		return emcRecipient == null ? account(host).owner() : emcRecipient;
	}

	public void setEmcRecipient(BlockEntity host, UUID player) {
		var owner = account(host).owner();
		UUID next = owner.equals(java.util.Objects.requireNonNull(player)) ? null : player;
		if (java.util.Objects.equals(next, emcRecipient)) return;
		emcRecipient = next;
		changed(host);
	}

	/** 可用于诊断的账户引用；无效附件不猜测身份。 */
	public ResourceAccount.Reference reference() { return reference; }
	/** 返回小型状态，不包含库存或原始资源 payload。 */
	public String status() { return status; }
	public void status(String value) { status = value; }

	@Override
	public CompoundTag serializeNBT(HolderLookup.Provider provider) {
		if (preservedInvalid != null) return preservedInvalid.copy();
		if (outputAccess != null) meNodeData = outputAccess.savedMeNode();
		var tag = new CompoundTag();
		tag.putInt("version", 1);
		if (invalidNativeConfig != null || !nativeConfig.isDefault()) tag.put("native_output", nativeConfigData());
		if (reference != null) {
			tag.putUUID("source", reference.sourceId());
			tag.putLong("generation", reference.generation());
			tag.putLong("capacity", capacity);
			if (meOutputMigrated) tag.putBoolean("me_output", meOutputEnabled);
			if (emcRecipient != null) tag.putUUID("emc_recipient", emcRecipient);
			if (!meNodeData.isEmpty()) tag.put("me_node", meNodeData.copy());
			if (outputPort != null) {
				tag.putUUID("output_port", outputPort);
				tag.putLong("output_position", outputPosition);
			}
		}
		if (!unresolvedReferences.isEmpty()) {
			var list = new net.minecraft.nbt.ListTag();
			unresolvedReferences.forEach(entry -> list.add(entry.copy()));
			tag.put("unresolved_references", list);
		}
		return tag;
	}

	@Override
	public void deserializeNBT(HolderLookup.Provider provider, CompoundTag tag) {
		productionMonitor = null;
		reference = null;
		nativeConfig = new ResourceSideConfig();
        invalidNativeConfig = null;
		accountBinding = null;
		bindingType = null;
		mechanicalCapacityResolved = false;
		outputPort = null;
		preservedInvalid = null;
		centrifugeProcessor = null;
		if (outputAccess != null) outputAccess.invalidate();
		outputAccess = null;
		meNodeData = new CompoundTag();
		status = "";
		meOutputEnabled = true;
		emcRecipient = null;
		meOutputMigrated = true;
		meStatus = "offline";
		nextBindAttempt = 0;
		lastRecoveryAccount = null;
		unresolvedReferences.clear();
		try {
			ResourceAccountData.keys(tag, java.util.Set.of("version", "source", "generation", "capacity", "output_port", "output_position", "me_output", "me_node", "emc_recipient", "unresolved_references", "native_output"));
			if (!tag.contains("source") && !java.util.Set.of("version", "native_output").containsAll(tag.getAllKeys())) {
				throw new IllegalArgumentException("Incomplete resource host reference");
			}
			if (!tag.contains("version", Tag.TAG_INT) || tag.getInt("version") != 1) {
				throw new IllegalArgumentException("Unknown resource host version");
			}
			if (tag.contains("native_output")) {
                try {
                    if (!(tag.get("native_output") instanceof CompoundTag nativeTag)) throw new IllegalArgumentException("Invalid native output tag");
                    nativeConfig = ResourceSideConfig.load(nativeTag);
                } catch (IllegalArgumentException invalid) {
                    invalidNativeConfig = tag.get("native_output").copy();
                }
			}
			if (tag.contains("source")) {
				if (tag.contains("unresolved_references")) {
					if (!(tag.get("unresolved_references") instanceof net.minecraft.nbt.ListTag entries)
							|| entries.size() > 4 || !entries.isEmpty() && entries.getElementType() != Tag.TAG_COMPOUND) {
						throw new IllegalArgumentException("Invalid unresolved resource references");
					}
					for (var entry : entries) {
						var evidence = (CompoundTag) entry;
						ResourceAccountData.keys(evidence, java.util.Set.of("source", "generation", "reason"));
						if (!evidence.hasUUID("source") || !evidence.contains("generation", Tag.TAG_LONG)
								|| evidence.getLong("generation") < 1 || !evidence.contains("reason", Tag.TAG_STRING)
								|| !java.util.Set.of("missing_account", "foreign_binding").contains(evidence.getString("reason"))) {
							throw new IllegalArgumentException("Invalid unresolved resource identity");
						}
						unresolvedReferences.add(evidence.copy());
					}
				}
				if (!tag.hasUUID("source") || !tag.contains("generation", Tag.TAG_LONG)
						|| !tag.contains("capacity", Tag.TAG_LONG) || tag.getLong("capacity") < 1) {
					throw new IllegalArgumentException("Invalid resource host reference");
				}
				reference = new ResourceAccount.Reference(tag.getUUID("source"), tag.getLong("generation"));
				capacity = tag.getLong("capacity");
				if (tag.contains("emc_recipient")) {
					if (!tag.hasUUID("emc_recipient")) throw new IllegalArgumentException("Invalid EMC recipient");
					emcRecipient = tag.getUUID("emc_recipient");
				}
				if (tag.contains("me_node")) {
					if (!tag.contains("me_node", Tag.TAG_COMPOUND) || tag.getCompound("me_node").sizeInBytes() > 8192)
						throw new IllegalArgumentException("Invalid resource ME node");
					meNodeData = tag.getCompound("me_node").copy();
				}
				if (tag.contains("me_output")) {
					if (!tag.contains("me_output", Tag.TAG_BYTE)) throw new IllegalArgumentException("Invalid resource ME switch");
					meOutputEnabled = tag.getBoolean("me_output");
				}
				if (tag.contains("output_port") || tag.contains("output_position")) {
					if (!tag.hasUUID("output_port") || !tag.contains("output_position", Tag.TAG_LONG)) {
						throw new IllegalArgumentException("Invalid resource output reference");
					}
					outputPort = tag.getUUID("output_port");
					outputPosition = tag.getLong("output_position");
					meOutputMigrated = tag.contains("me_output");
				}
			}
		} catch (RuntimeException failure) {
			preservedInvalid = tag.copy();
			status = "invalid_host_data";
		}
	}
}
