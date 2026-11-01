package com.ayoshiko.productivebeesgenesis.resource.ars;
import com.ayoshiko.productivebeesgenesis.resource.*;
import com.ayoshiko.productivebeesgenesis.port.RegisterCapabilitiesEvent;
import com.hollingsworth.arsnouveau.api.source.ISourceTile;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.*;
public final class ResourceArsIntegration {
 public static boolean canReceive(ServerLevel level,BlockPos pos,Direction side){var target=level.getBlockEntity(pos);return target instanceof ISourceTile source && source.canAcceptSource() && source.getSource()<source.getMaxSource();}
 public static ResourceNativeOutput.Receiver receiver(ServerLevel level,BlockPos pos,Direction side){
  var target=level.getBlockEntity(pos);if(!(target instanceof ISourceTile source)||!source.canAcceptSource())return null;
  return new ResourceNativeOutput.Receiver(){
   public long simulate(long maximum){return Math.max(0,Math.min(maximum,Math.min(source.getTransferRate(),(long)source.getMaxSource()-source.getSource())));}
   public long execute(long amount){int before=source.getSource();source.addSource(Math.toIntExact(amount));target.setChanged();return (long)source.getSource()-before;}
   public void validate(){if(level.getBlockEntity(pos)!=target || !source.canAcceptSource())throw new IllegalStateException("Source receiver changed");}
  };
 }
 public static void register(RegisterCapabilitiesEvent event){}
}
