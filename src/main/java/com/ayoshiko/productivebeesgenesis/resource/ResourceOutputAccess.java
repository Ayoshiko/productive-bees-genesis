package com.ayoshiko.productivebeesgenesis.resource;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** 已有机器的资源能力视图；只保留宿主与代际，不注册方块、不复制资源库存。 */
public final class ResourceOutputAccess {
    private final BlockEntity host;
    private final ResourceAccount.Reference source;
    private boolean closed;
    private Object meOutput;
    private long lastTick = Long.MIN_VALUE;
	private ResourceChemicalOutput chemical;
    private final ResourceChemicalOutput[] chemicalViews=new ResourceChemicalOutput[4];
    private final Object[] chemicalHandlers=new Object[4];
	private ResourceEnergyOutput energy;
    private ResourceStrictEnergyOutput strictEnergy;
	private ResourceScalarOutput sourceAccess;
	private Object sourceOutput;
	private ResourceScalarOutput soulAccess;
	private Object soulOutput;
	private ResourceScalarOutput emcAccess;
	private Object emcOutput;
	private ResourceScalarOutput experienceAccess;
	private Object experienceOutput;
	private ResourceScalarOutput highVoltageAccess;
	private ResourceScalarOutput extremeVoltageAccess;
	private Object lightningOutput;
	private Object airOutput;
    private ResourceNativeOutput nativeOutput;
    private final java.util.Map<net.minecraft.core.Direction, ResourceOutputAccess> sided = new java.util.EnumMap<>(net.minecraft.core.Direction.class);
    private net.minecraft.core.Direction outputSide;

    public ResourceOutputAccess sided(net.minecraft.core.Direction side) {
        if (side == null) return this;
        return sided.computeIfAbsent(side, direction -> {
            var result = new ResourceOutputAccess(host, source);
            result.outputSide = direction;
            return result;
        });
    }
    public boolean outputAllowed(ResourceNativeType type) {
        var state = ResourceHiveHost.existing(host);
        return live() && state != null && state.nativeOutput(host, type, outputSide);
    }
    public void configurationChanged() {
        for (var view : sided.values()) view.invalidate();
        sided.clear();
        if (nativeOutput != null) nativeOutput.changed();
        if (airOutput != null) ((com.ayoshiko.productivebeesgenesis.resource.air.ResourceAirOutput) airOutput).changed();
    }
    public mekanism.api.energy.IStrictEnergyHandler strictEnergy() {
        if (strictEnergy == null) strictEnergy = new ResourceStrictEnergyOutput(this);
        return strictEnergy;
    }

    private ResourceOutputAccess(BlockEntity host, ResourceAccount.Reference source) { this.host = host; this.source = source; }

    public static ResourceOutputAccess find(BlockEntity host) {
        if (host == null || !(host.getLevel() instanceof ServerLevel level) || !level.getServer().isSameThread()) return null;
        var state = ResourceHiveHost.existing(host);
        if (state == null || state.reference() == null || host.isRemoved()
                || !level.getChunkSource().hasChunk(host.getBlockPos().getX() >> 4, host.getBlockPos().getZ() >> 4)
                || level.getBlockEntity(host.getBlockPos()) != host) return null;
        state.migrateNativeEnergy(host);
        if (state.outputAccess == null || state.outputAccess.closed || !state.outputAccess.source.equals(state.reference())) {
            if (state.outputAccess != null) state.outputAccess.invalidate();
            state.outputAccess = new ResourceOutputAccess(host, state.reference());
            ResourceRuntime.trackOutput(level.getServer(), state.outputAccess);
            com.ayoshiko.productivebeesgenesis.port.ForgeBlockCapabilities.invalidate(host);
        }
        return state.outputAccess;
    }

    private boolean live() {
        if (closed || !(host.getLevel() instanceof ServerLevel level) || !level.getServer().isSameThread()
                || host.isRemoved() || !level.getChunkSource().hasChunk(host.getBlockPos().getX() >> 4, host.getBlockPos().getZ() >> 4)
                || level.getBlockEntity(host.getBlockPos()) != host) return false;
        var state = ResourceHiveHost.existing(host);
        return state != null && source.equals(state.reference());
    }
    ResourceAccount account() {
        if (!live()) return null;
        try { return ResourceHiveHost.existing(host).account(host); }
        catch (IllegalStateException unavailable) { return null; }
    }
    ResourceAccount.Reference reference() { return source; }
    public BlockEntity sourceHost() { return account() == null ? null : host; }
    public Level getLevel() { return host.getLevel(); }
    public BlockPos getBlockPos() { return host.getBlockPos(); }
    public BlockState getBlockState() { return host.getBlockState(); }
    long capacity() { return live() ? ResourceHiveHost.existing(host).capacity() : 0; }
    public boolean meOutputEnabled() { return live() && ResourceHiveHost.existing(host).meOutputEnabled(); }
    public void setChanged() { host.setChanged(); }
    public void invalidateCapabilities() { com.ayoshiko.productivebeesgenesis.port.ForgeBlockCapabilities.invalidate(host); }
    public CompoundTag meNodeData() { return ResourceHiveHost.existing(host).meNodeData.copy(); }
    public void rememberMeNode(CompoundTag tag) {
        var state = ResourceHiveHost.existing(host);
        if (state != null && source.equals(state.reference())) { state.meNodeData = tag.copy(); host.setChanged(); }
    }
    public Object meNodeHost() {
        if (host instanceof com.ayoshiko.productivebeesgenesis.mek.ae2.IAe2OutputHostBase) return null;
        if (!live() || !com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2IntegrationLoader.isAe2Loaded()) return null;
        if (meOutput == null) meOutput = new com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2ResourceOutput(this);
        return meOutput;
    }
    CompoundTag savedMeNode() {
        var tag = new CompoundTag();
        if (meOutput != null) ((com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2ResourceOutput) meOutput).save(tag);
        else tag.merge(meNodeData());
        return tag;
    }
    boolean tick() {
        if (!live()) { invalidate(); return false; }
        long tick = host.getLevel().getGameTime();
        if (tick == lastTick) return true;
        lastTick = tick;
        if (nativeOutput == null) nativeOutput = new ResourceNativeOutput(this);
        nativeOutput.tick();
        if (meNodeHost() != null) ((com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2ResourceOutput) meOutput).tick();
        if (net.minecraftforge.fml.ModList.get().isLoaded("projecte") && emcCapability() != null)
            ((com.ayoshiko.productivebeesgenesis.resource.emc.ResourceEmcOutput) emcOutput).tick();
        if (net.minecraftforge.fml.ModList.get().isLoaded("pneumaticcraft")) {
            if (airOutput == null) airOutput = new com.ayoshiko.productivebeesgenesis.resource.air.ResourceAirOutput(this);
            ((com.ayoshiko.productivebeesgenesis.resource.air.ResourceAirOutput) airOutput).tick();
        }
        return true;
    }
    void invalidate() {
        if (closed) return;
        closed = true;
        if (nativeOutput != null) nativeOutput.invalidate();
        for (var view : sided.values()) view.invalidate();
        sided.clear();
        clearNativeOutputs();
        if (meOutput != null) ((com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2ResourceOutput) meOutput).close();
        meOutput = null;
    }
    Object chemical(int kind) {
        if(chemicalHandlers[kind]==null){chemicalViews[kind]=new ResourceChemicalOutput(this,kind);chemicalHandlers[kind]=chemicalViews[kind].nativeHandler();}
        return chemicalHandlers[kind];
    }

	net.minecraftforge.energy.IEnergyStorage energy() {
		if (energy == null) energy = new ResourceEnergyOutput(this);
		return energy;
	}

	/** 可选魔源能力的无外部类型入口；依赖缺失时保留账户，返回空能力。 */
	public Object sourceCapability() {
		if (!net.minecraftforge.fml.ModList.get().isLoaded("ars_nouveau")) return null;
		if (sourceOutput == null) {
			sourceAccess = new ResourceScalarOutput(this, ResourceTypes.SOURCE);
			sourceOutput = new com.ayoshiko.productivebeesgenesis.resource.ars.ResourceSourceOutput(sourceAccess);
		}
		return sourceOutput;
	}

	/** 可选灵魂能力的隔离入口；旧视图随来源或接口变更一起撤销。 */
	public Object soulCapability() {
		if (!net.minecraftforge.fml.ModList.get().isLoaded("industrialforegoingsouls")) return null;
		if (soulOutput == null) {
			soulAccess = new ResourceScalarOutput(this, ResourceTypes.SOUL);
			soulOutput = new com.ayoshiko.productivebeesgenesis.resource.souls.ResourceSoulOutput(soulAccess);
		}
		return soulOutput;
	}

	/** ProjectE 能力隔离入口；原生能力和玩家入账共用唯一资源账户。 */
	public Object emcCapability() {
		if (!net.minecraftforge.fml.ModList.get().isLoaded("projecte")) return null;
		if (emcOutput == null) {
			emcAccess = new ResourceScalarOutput(this, ResourceTypes.EMC);
			emcOutput = new com.ayoshiko.productivebeesgenesis.resource.emc.ResourceEmcOutput(this, emcAccess);
		}
		return ((com.ayoshiko.productivebeesgenesis.resource.emc.ResourceEmcOutput) emcOutput).capability();
	}

	/** 可选经验能力只代理同一账户，不拥有可反序列化的独立库存。 */
	public Object experienceCapability() {
		if (!false /* ExperienceLib has no 1.20.1 release */) return null;
		if (experienceOutput == null) {
			experienceAccess = new ResourceScalarOutput(this, ResourceTypes.EXPERIENCE);
		}
		return experienceOutput;
	}

	/** 两种电压分别绑定视图；依赖缺失时不加载 AE2LT 能力类型。 */
	public Object lightningCapability() {
		if (!net.minecraftforge.fml.ModList.get().isLoaded("ae2lt")) return null;
		if (lightningOutput == null) {
			highVoltageAccess = new ResourceScalarOutput(this, ResourceTypes.HIGH_VOLTAGE);
			extremeVoltageAccess = new ResourceScalarOutput(this, ResourceTypes.EXTREME_HIGH_VOLTAGE);
			lightningOutput = new com.ayoshiko.productivebeesgenesis.resource.lightning.ResourceLightningOutput(
					highVoltageAccess, extremeVoltageAccess);
		}
		return lightningOutput;
	}

	private void clearNativeOutputs() {
		if (airOutput != null) ((com.ayoshiko.productivebeesgenesis.resource.air.ResourceAirOutput) airOutput).invalidate();
		airOutput = null;
		if (chemical != null) chemical.invalidate();
        for(int kind=0;kind<4;kind++){if(chemicalViews[kind]!=null)chemicalViews[kind].invalidate();chemicalViews[kind]=null;chemicalHandlers[kind]=null;}
		if (energy != null) energy.invalidate();
        if (strictEnergy != null) strictEnergy.invalidate();
        strictEnergy = null;
		if (sourceAccess != null) sourceAccess.invalidate();
		if (soulAccess != null) soulAccess.invalidate();
		if (emcAccess != null) emcAccess.invalidate();
		if (experienceAccess != null) experienceAccess.invalidate();
		if (highVoltageAccess != null) highVoltageAccess.invalidate();
		if (extremeVoltageAccess != null) extremeVoltageAccess.invalidate();
		experienceAccess = null;
		experienceOutput = null;
		highVoltageAccess = null;
		extremeVoltageAccess = null;
		lightningOutput = null;
		if (emcOutput != null) ((com.ayoshiko.productivebeesgenesis.resource.emc.ResourceEmcOutput) emcOutput).invalidate();
		emcAccess = null;
		emcOutput = null;
		soulAccess = null;
		soulOutput = null;
		sourceAccess = null;
		sourceOutput = null;
		chemical = null;
		energy = null;
	}


}
