package com.ayoshiko.productivebeesgenesis.mixin.beehive;
import com.ayoshiko.productivebeesgenesis.resource.*;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import cy.jdkdigital.productivebees.common.block.entity.AdvancedBeehiveBlockEntityAbstract;
import cy.jdkdigital.productivebees.common.block.entity.AdvancedBeehiveBlockEntityAbstract.Inhabitant;
import cy.jdkdigital.productivelib.event.BeeReleaseEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity.BeeReleaseStatus;
import net.minecraft.world.entity.Entity;
import net.minecraft.nbt.CompoundTag;
import net.minecraftforge.eventbus.api.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import java.util.List;
import java.util.function.Function;
@Mixin(value=AdvancedBeehiveBlockEntityAbstract.class,priority=900,remap=false)
public abstract class PbResourceProductionMixin implements PbResourceBridge {
 @Unique public boolean productivebeesgenesis$resourceBridgeReady(){return true;}
 @WrapMethod(method="tickBees",require=1)
 private static void genesis$tick(ServerLevel level,BlockPos pos,BlockState state,AdvancedBeehiveBlockEntityAbstract hive,Operation<Void> original){
  try(var scope=PbResourceProduction.openRoot(hive)){try{original.call(level,pos,state,hive);scope.complete();}catch(RuntimeException|Error error){scope.failed(error);throw error;}}
 }
 @WrapOperation(method="lambda$tickBees$1",at=@At(value="FIELD",target="Lcy/jdkdigital/productivebees/common/block/entity/AdvancedBeehiveBlockEntityAbstract$Inhabitant;ticksInHive:I",opcode=org.objectweb.asm.Opcodes.PUTFIELD),require=2)
 private static void genesis$pauseClock(Inhabitant bee,int ticks,Operation<Void> original,AdvancedBeehiveBlockEntityAbstract hive,ServerLevel level,BlockPos pos,BlockState state,cy.jdkdigital.productivebees.handler.bee.IInhabitantStorage storage){if(PbResourceProduction.shouldTick(hive,bee))original.call(bee,ticks);}
 @WrapMethod(method="simulateBee",require=1)
 private static void genesis$simulate(ServerLevel level,BlockPos pos,BlockState state,AdvancedBeehiveBlockEntityAbstract hive,Inhabitant bee,Operation<Void> original){
  try(var scope=PbResourceProduction.openRoot(hive)){try{if(PbResourceProduction.beforeCreate(hive,bee,true,BeeReleaseStatus.HONEY_DELIVERED))original.call(level,pos,state,hive,bee);scope.complete();}catch(RuntimeException|Error error){scope.failed(error);throw error;}}
 }
 @WrapMethod(method="releaseBee",require=1)
 private static boolean genesis$release(ServerLevel level,BlockPos pos,BlockState state,AdvancedBeehiveBlockEntityAbstract hive,CompoundTag data,List<Entity> released,BeeReleaseStatus status,Operation<Boolean> original){
  try(var scope=PbResourceProduction.openRoot(hive)){try{
   // Preflight at entity creation below, after weather and exit validation.
   boolean result=original.call(level,pos,state,hive,data,released,status);scope.complete();return result;
  }catch(RuntimeException|Error error){scope.failed(error);throw error;}}
 }
 @WrapOperation(method="simulateBee",at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/EntityType;loadEntityRecursive(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/world/level/Level;Ljava/util/function/Function;)Lnet/minecraft/world/entity/Entity;",remap=true),require=1)
 private static Entity genesis$simulationEntity(CompoundTag tag,Level level,Function<Entity,Entity> mapper,Operation<Entity> original,ServerLevel server,BlockPos pos,BlockState state,AdvancedBeehiveBlockEntityAbstract hive,Inhabitant inhabitant){
  Entity bee=original.call(tag,level,mapper);PbResourceProduction.entityCreated(hive,bee);return bee;
 }
 @WrapOperation(method="releaseBee",at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/EntityType;loadEntityRecursive(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/world/level/Level;Ljava/util/function/Function;)Lnet/minecraft/world/entity/Entity;",remap=true),require=1)
 private static Entity genesis$releaseEntity(CompoundTag tag,Level level,Function<Entity,Entity> mapper,Operation<Entity> original,ServerLevel server,BlockPos pos,BlockState state,AdvancedBeehiveBlockEntityAbstract hive,CompoundTag data,List<Entity> released,BeeReleaseStatus status){
  Inhabitant bee=new Inhabitant(data,0,0,pos,"");if(!PbResourceProduction.beforeCreate(hive,bee,false,status))return null;
  Entity entity=original.call(tag,level,mapper);PbResourceProduction.entityCreated(hive,entity);return entity;
 }
 @WrapOperation(method="beeReleasePostAction",at=@At(value="INVOKE",target="Lnet/minecraftforge/eventbus/api/IEventBus;post(Lnet/minecraftforge/eventbus/api/Event;)Z"),require=1)
 private boolean genesis$event(IEventBus bus,Event event,Operation<Boolean> original){
  boolean converted=event instanceof BeeReleaseEvent release&&PbResourceProduction.converted(release.getBee());
  boolean canceled=original.call(bus,event);if(event instanceof BeeReleaseEvent release)PbResourceProduction.afterEvent((AdvancedBeehiveBlockEntityAbstract)(Object)this,release,converted);return canceled;
 }
}
