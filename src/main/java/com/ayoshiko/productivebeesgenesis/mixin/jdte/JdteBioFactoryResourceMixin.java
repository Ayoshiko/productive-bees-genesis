package com.ayoshiko.productivebeesgenesis.mixin.jdte;
import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.api.resource.ResourceHostApi;
import com.ayoshiko.productivebeesgenesis.resource.*;
import com.ayoshiko.productivebeesgenesis.port.ForgeJdteAccess;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import cy.jdkdigital.productivebees.common.entity.bee.ConfigurableBee;
import cy.jdkdigital.productivebees.util.BeeAttributes;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.animal.Bee;
import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.fluids.capability.IFluidHandler;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Pseudo @Mixin(targets="com.jdte.common.blockentities.BioFactoryBE",remap=false)
public abstract class JdteBioFactoryResourceMixin implements JdteResourceIntegration.Host {
 @Shadow private Bee cachedBee;
 @Shadow private ItemStack cachedSpecimen;
 @Shadow private int progress;
 @Shadow private void clearRecipeCache(){throw new AssertionError();}
 @Unique private long genesis$revision=Long.MIN_VALUE,genesis$failed=Long.MIN_VALUE;
 public boolean productivebeesgenesis$hasLiveResourceWork(){return progress>0 || !((ItemStackHandler)ForgeJdteAccess.call(this,"getMachineHandler")).getStackInSlot(0).isEmpty();}
 public boolean productivebeesgenesis$canAccessResources(ServerPlayer player){Object owner=ForgeJdteAccess.field(this,"placedByUUID");return owner==null||owner.equals(player.getUUID())||player.hasPermissions(2);}
 @Inject(method="resolveRecipe",at=@At("HEAD"),require=1)
 private void genesis$reload(CallbackInfoReturnable<Boolean> ci){long revision=ProductiveBeesGenesis.RECIPE_VERSION.get();if(revision!=genesis$revision){genesis$revision=revision;clearRecipeCache();}}
 @WrapMethod(method="completeCycle",require=1)
 private void genesis$complete(Operation<Void> original){
  BlockEntity host=(BlockEntity)(Object)this;
  if(!(host.getLevel() instanceof ServerLevel level)||!(cachedBee instanceof ConfigurableBee bee)||ForgeJdteAccess.field(this,"cachedRecipe")!=null){original.call();return;}
  ResourceLocation type=new ResourceLocation(bee.getBeeType());var spec=ResourceRuntime.pbCatalog(level).get(type);
  if(spec==null){original.call();return;}
  long revision=ProductiveBeesGenesis.RECIPE_VERSION.get();if(genesis$failed==revision)return;
  try {
   var state=ResourceHiveHost.existing(host);if(state==null||state.reference()==null||!spec.validForJdte())return;
   if(progress<ForgeJdteAccess.number(this,"getCurrentProcessTicks")||!(boolean)ForgeJdteAccess.call(this,"canRun")||!(boolean)ForgeJdteAccess.call(this,"isActiveRedstone"))return;
   ItemStackHandler inventory=(ItemStackHandler)ForgeJdteAccess.call(this,"getMachineHandler");if(!ItemStack.matches(cachedSpecimen,inventory.getStackInSlot(0)))return;
   Class<?> upgrades=ForgeJdteAccess.type("com.jdte.common.upgrades.UpgradeHelper");boolean creative=(boolean)ForgeJdteAccess.call(upgrades,"hasCreativeUpgrade",this);
   int energy=creative?0:ForgeJdteAccess.number(upgrades,"adjustEnergyCost",this,(int)ForgeJdteAccess.config("bioFactoryEnergyPerCycle"));
   IFluidHandler time=(IFluidHandler)ForgeJdteAccess.call(this,"getTimeFluidTank"),life=(IFluidHandler)ForgeJdteAccess.call(this,"getLifeFluidTank");
   int timeCost=creative?0:ForgeJdteAccess.number(this,"getEffectiveTimeFluidCost"),lifeCost=creative?0:ForgeJdteAccess.number(this,"getEffectiveLifeFluidCost");
   IFluidHandler process=(IFluidHandler)ForgeJdteAccess.call(this,"getProcessFluidTank");int processCost=creative?0:ForgeJdteAccess.number(this,"getProcessFluidCost");
   if(process.getFluidInTank(0).getAmount()<processCost)return;
   boolean lifeBoost=creative||life.getFluidInTank(0).getAmount()>=lifeCost;
   if(time.getFluidInTank(0).getAmount()<timeCost)timeCost=0;if(!lifeBoost)lifeCost=0;
   if(!creative&&!(boolean)ForgeJdteAccess.call(this,"hasEnoughPower",energy))return;
   double modifier=((Number)ForgeJdteAccess.call(this,"getProductivityMultiplier")).doubleValue()*(lifeBoost?ForgeJdteAccess.config("bioFactoryLifeYieldMultiplier"):1);
   int gene=bee.getAttributeValue(BeeAttributes.PRODUCTIVITY);int paidProgress=progress;ItemStack specimen=cachedSpecimen.copy();
   java.util.function.Supplier<int[]> slots=()->java.util.stream.IntStream.range(2,2+ForgeJdteAccess.number(this,"getActiveOutputSlots")).toArray();
   PbMixedProduction mixed=spec.mixed()?new PbMixedProduction(spec.nativeItems(),modifier,gene,false,false):null;
   PbLocalOutputPlan[] output={null};CompoundTag context=new CompoundTag();context.putString("integration","jdte:bio_factory/forge-0.5.8");context.putInt("energy",energy);context.putInt("time",timeCost);context.putInt("life",lifeCost);context.putInt("process",processCost);context.put("specimen",specimen.save(new CompoundTag()));
   var reservation=ResourceHostApi.reservePrepared(host,type,1,modifier,gene,sample->{if(mixed!=null){output[0]=PbLocalOutputPlan.prepare(inventory,slots,mixed.sample(sample.rolls(),level.random.nextLong()));if(output[0]==null)return null;context.put("local_output",output[0].context(level.registryAccess()));}return context;});
   if(reservation.isEmpty())return;int debitTime=timeCost,debitLife=lifeCost;
   Runnable validate=()->{state.account(host);if(progress!=paidProgress||!ItemStack.matches(specimen,cachedSpecimen)||!ItemStack.matches(specimen,inventory.getStackInSlot(0))||ProductiveBeesGenesis.RECIPE_VERSION.get()!=revision)throw new IllegalStateException("JDTE resource cycle changed");};
   try(var transaction=reservation.get()){transaction.execute(()->{validate.run();if(energy>0&&ForgeJdteAccess.number(this,"extractEnergy",energy,false)!=energy)throw new IllegalStateException("Incomplete JDTE energy debit");validate.run();if(debitTime>0&&time.drain(debitTime,IFluidHandler.FluidAction.EXECUTE).getAmount()!=debitTime)throw new IllegalStateException("Incomplete time fluid debit");if(debitLife>0&&life.drain(debitLife,IFluidHandler.FluidAction.EXECUTE).getAmount()!=debitLife)throw new IllegalStateException("Incomplete life fluid debit");if(processCost>0&&process.drain(processCost,IFluidHandler.FluidAction.EXECUTE).getAmount()!=processCost)throw new IllegalStateException("Incomplete process fluid debit");if(output[0]!=null)output[0].commit(validate);progress=0;host.setChanged();ForgeJdteAccess.call(this,"markDirtyClient");});}
  }catch(RuntimeException error){genesis$failed=revision;ProductiveBeesGenesis.LOGGER.error("JDTE resource cycle paused at {}",host.getBlockPos(),error);}
 }
}
