package com.ayoshiko.productivebeesgenesis.port;

import mekanism.api.MekanismAPI;
import mekanism.api.chemical.*;
import mekanism.api.chemical.gas.*;
import mekanism.api.chemical.infuse.*;
import mekanism.api.chemical.pigment.*;
import mekanism.api.chemical.slurry.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.core.*;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.registries.IForgeRegistry;
import java.util.*;

/** The four 1.20 chemical registries share portable resource identities. */
@SuppressWarnings({"rawtypes","unchecked"})
public final class ForgeChemicals {
    public static final Registry REGISTRY=new Registry();
    public static List<IForgeRegistry<? extends Chemical<?>>> registries() { return List.of(MekanismAPI.gasRegistry(),MekanismAPI.infuseTypeRegistry(),MekanismAPI.pigmentRegistry(),MekanismAPI.slurryRegistry()); }
    public static final class Registry implements Iterable<Chemical> {
        public Chemical get(ResourceLocation id) {
            Chemical result=null;
            for(var registry:registries()) if(registry.containsKey(id)) {
                Chemical value=registry.getValue(id);
                if(value==null || value.isEmptyType())continue;
                if(result!=null)throw new IllegalArgumentException("Ambiguous 1.20 chemical identity: "+id);
                result=value;
            }
            return result;
        }
        public boolean containsKey(ResourceLocation id) { try { return get(id)!=null; } catch(IllegalArgumentException ambiguous) {return false;} }
        public ResourceLocation getKey(Chemical chemical) { return chemical.getRegistryName(); }
        public Iterator<Chemical> iterator() { var values=new ArrayList<Chemical>();for(var registry:registries())for(var value:registry) if(containsKey(value.getRegistryName()))values.add(value);return values.iterator(); }
        public boolean tagged(Chemical chemical,ResourceLocation tag) { return chemical.getTags().anyMatch(value->((net.minecraft.tags.TagKey<?>)value).location().equals(tag)); }
        public boolean hasTag(ResourceLocation tag) { for(var chemical:this) if(tagged(chemical,tag))return true;return false; }
    }
    public static int kind(Chemical chemical) { return chemical instanceof Gas?0:chemical instanceof InfuseType?1:chemical instanceof Pigment?2:3; }
    public static ChemicalStack stack(Chemical chemical,long amount) {
        if(chemical instanceof Gas gas)return new GasStack(gas,amount);
        if(chemical instanceof InfuseType infusion)return new InfusionStack(infusion,amount);
        if(chemical instanceof Pigment pigment)return new PigmentStack(pigment,amount);
        if(chemical instanceof Slurry slurry)return new SlurryStack(slurry,amount);
        throw new IllegalArgumentException("Unknown chemical type");
    }
    public static ChemicalStack empty(int kind) { return switch(kind){case 0->GasStack.EMPTY;case 1->InfusionStack.EMPTY;case 2->PigmentStack.EMPTY;default->SlurryStack.EMPTY;}; }
    public static Capability capability(int kind) { return switch(kind){case 0->mekanism.common.capabilities.Capabilities.GAS_HANDLER;case 1->mekanism.common.capabilities.Capabilities.INFUSION_HANDLER;case 2->mekanism.common.capabilities.Capabilities.PIGMENT_HANDLER;default->mekanism.common.capabilities.Capabilities.SLURRY_HANDLER;}; }
    public static IChemicalHandler receiver(Level level,BlockPos pos,Direction side,Chemical chemical) { return (IChemicalHandler)ForgeBlockCapabilities.get(level,capability(kind(chemical)),pos,side); }
    public static IChemicalHandler item(net.minecraft.world.item.ItemStack stack,int kind) { return (IChemicalHandler)stack.getCapability(capability(kind)).orElse(null); }
}
