package com.ayoshiko.productivebeesgenesis.resource.souls;
import com.ayoshiko.productivebeesgenesis.resource.*;
import com.ayoshiko.productivebeesgenesis.port.RegisterCapabilitiesEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.*;
import net.minecraft.world.level.block.entity.BlockEntity;
/** 1.20.1 Souls has a block network, not the 1.21 soul capability. */
public final class ResourceSoulIntegration {
 private static BlockEntity target(ServerLevel level,BlockPos pos){
  BlockEntity tile=level.getBlockEntity(pos);if(tile==null)return null;
  try {
   if(tile.getClass().getName().equals("com.buuz135.industrialforegoingsouls.block.tile.SoulLaserBaseBlockEntity"))return tile;
   Object network=tile.getClass().getMethod("getNetwork").invoke(tile);
   for(Object endpoint:(Iterable<?>)network.getClass().getMethod("getSoulLaserDrills").invoke(network)){
    Object world=endpoint.getClass().getMethod("getLevel").invoke(endpoint);
    Object position=endpoint.getClass().getMethod("getPos").invoke(endpoint);
    BlockEntity candidate=((net.minecraft.world.level.Level)world).getBlockEntity((BlockPos)position);
    if(candidate!=null && stored(candidate)<capacity())return candidate;
   }
  }catch(ReflectiveOperationException e){return null;}
  return null;
 }
 private static int stored(BlockEntity tile){try{return (int)tile.getClass().getMethod("getSoulAmount").invoke(tile);}catch(ReflectiveOperationException e){throw new IllegalStateException(e);}}
 private static int capacity(){try{return Class.forName("com.buuz135.industrialforegoingsouls.config.ConfigSoulLaserBase").getField("SOUL_STORAGE_AMOUNT").getInt(null);}catch(ReflectiveOperationException e){throw new IllegalStateException(e);}}
 public static boolean canReceive(ServerLevel level,BlockPos pos,Direction side){var tile=target(level,pos);return tile!=null && stored(tile)<capacity();}
 public static ResourceNativeOutput.Receiver receiver(ServerLevel level,BlockPos pos,Direction side){
  var tile=target(level,pos);if(tile==null)return null;
  return new ResourceNativeOutput.Receiver(){
   public long simulate(long maximum){return Math.max(0,Math.min(maximum,(long)capacity()-stored(tile)));}
   public long execute(long amount){try {var field=tile.getClass().getDeclaredField("soulAmount");field.setAccessible(true);int before=stored(tile);field.setInt(tile,Math.addExact(before,Math.toIntExact(amount)));tile.setChanged();return amount;}catch(ReflectiveOperationException e){throw new IllegalStateException(e);}}
   public void validate(){if(tile.isRemoved()||tile.getLevel().getBlockEntity(tile.getBlockPos())!=tile)throw new IllegalStateException("Soul receiver changed");}
  };
 }
 public static void register(RegisterCapabilitiesEvent event){}
}
