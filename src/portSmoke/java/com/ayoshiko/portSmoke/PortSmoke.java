package com.ayoshiko.portSmoke;
import com.ayoshiko.productivebeesgenesis.api.resource.*;
import com.ayoshiko.productivebeesgenesis.init.ModBlocks;
import com.ayoshiko.productivebeesgenesis.port.*;
import com.ayoshiko.productivebeesgenesis.resource.*;
import com.google.gson.*;
import io.netty.buffer.Unpooled;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.*;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import java.nio.file.*;
import java.util.*;

/** Separate development mod: none of these classes or fixtures enter the release JAR. */
@Mod("genesis_port_smoke")
public final class PortSmoke {
 public PortSmoke(){MinecraftForge.EVENT_BUS.addListener(this::started);}
 private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
 private void started(ServerStartedEvent event){
  var report=new JsonObject();var checks=new JsonArray();report.add("checks",checks);
  var server=event.getServer();var level=server.overworld();BlockPos pos=new BlockPos(0,110,0);
  try {
   check(!ResourceRuntime.recipes(server).forBee(new ResourceLocation("productivebees:iron")).isEmpty(),"Resource recipe was not loaded from the 1.20 datapack directory");checks.add("native resource recipe registration and datapack loading");
   check(server.getAdvancements().getAdvancement(new ResourceLocation("productivebeesgenesis:recipes/gene_type_only_upgrade"))!=null,"Upgrade advancement did not load");checks.add("native upgrade recipes and advancements");
   var document=com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerReader.snapshot(server,new ResourceLocation("productivebees:iron")).document();
   com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerCompiler.validate(document,server);
   var exported=com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerCompiler.files(Map.of("productivebees:iron",document.toString()));
   check(exported.keySet().stream().noneMatch(path->path.contains("/recipe/"))&&exported.keySet().stream().anyMatch(path->path.contains("/recipes/")),"Designer exported the wrong recipe directory");checks.add("existing 1.20 bee authoring, validation and datapack export");
   var id=new ResourceLocation("genesis_port_smoke:network");
   var source=JsonParser.parseString("{\"bee\":\"productivebees:iron\",\"outputs\":[{\"kind\":\"energy\",\"energy_type\":\"FE\",\"amount\":\"64\"}]}").getAsJsonObject();
   var recipe=ResourceProductionRecipe.SERIALIZER.fromJson(id,source);
   var buffer=new FriendlyByteBuf(Unpooled.buffer());
   try{ResourceProductionRecipe.SERIALIZER.toNetwork(buffer,recipe);var decoded=ResourceProductionRecipe.SERIALIZER.fromNetwork(id,buffer);check(recipe.equals(decoded)&&decoded.getId().equals(id)&&buffer.readableBytes()==0,"Recipe network round-trip lost identity or data");}finally{buffer.release();}checks.add("native FriendlyByteBuf recipe round trip");
   for(int chance:new int[]{-1,0,1,49,99}){var json=JsonParser.parseString("{\"type\":\"productivebees:advanced_beehive\",\"results\":[{\"chance\":"+chance+"}]}").getAsJsonObject();var nativeAgain=ForgeJsonRecipes.toForge(ForgeJsonRecipes.toPortable(json));check(nativeAgain.getAsJsonArray("results").get(0).getAsJsonObject().get("chance").getAsInt()==chance,"PB inclusive chance changed");}checks.add("PB 12 inclusive probability conversion");
   var stack=new ItemStack(Items.DIAMOND,7);stack.getOrCreateTag().putString("port_test","preserved");stack.getOrCreateTag().putInt("typed_integer",1);stack.getOrCreateTag().putLong("typed_long",1L);var copy=ForgeItemStackCodec.read(ForgeItemStackCodec.write(stack));check(ItemStack.matches(stack,copy),"Stack codec lost count or NBT");checks.add("item count and native NBT round trip");
   var inventory=new net.minecraftforge.items.ItemStackHandler(3);inventory.setStackInSlot(0,new ItemStack(Items.GOLD_INGOT,3));
   var plan=PbLocalOutputPlan.prepare(inventory,()->new int[]{1,2},List.of(new ItemStack(Items.DIAMOND,65)));
   check(plan!=null&&inventory.getStackInSlot(1).isEmpty(),"Output planning mutated the inventory");plan.commit(()->{});
   check(inventory.getStackInSlot(0).getCount()==3&&inventory.getStackInSlot(1).getCount()+inventory.getStackInSlot(2).getCount()==65,"Local output commit lost items");checks.add("local mixed output planning and commit");
   level.getChunkAt(pos);level.setBlockAndUpdate(pos,ModBlocks.MEK_APIARY.get().defaultBlockState());var host=level.getBlockEntity(pos);
   ((mekanism.common.lib.security.ISecurityTile)host).setOwnerUUID(UUID.randomUUID());var state=ResourceHiveHost.ensureMechanical(host);check(state!=null,"Mechanical resource host was not bound");
   var reservation=ResourceHostApi.reservePrepared(host,new ResourceLocation("productivebees:iron"),1,1,0,sample->new CompoundTag());check(reservation.isPresent(),"Resource reservation was rejected");try(var transaction=reservation.orElseThrow()){transaction.execute(()->{});}
   check(state.account(host).available().getOrDefault(ResourceTypes.FE,0L)==64,"Production did not credit the account exactly once");
   state.setNativeOutput(host,ResourceNativeType.ENERGY,ResourceSideConfig.ALL_SIDES,false);
   var energy=host.getCapability(ForgeCapabilities.ENERGY,Direction.NORTH).orElseThrow(()->new AssertionError("Native FE output missing"));
   check(energy.getEnergyStored()==64,"Native capability returned the work battery instead of resource FE");check(energy.extractEnergy(13,true)==13&&energy.getEnergyStored()==64,"Simulation debited FE");check(energy.extractEnergy(13,false)==13&&energy.getEnergyStored()==51,"Native FE extraction lost or duplicated credit");checks.add("resource reservation, account credit and Forge FE capability");
   if(ModList.get().isLoaded("useless_mod")){
    var manager=com.sorrowmist.useless.content.recipe.AlloyFurnaceRecipeManager.getInstance();
    var adapter=manager.getRegisteredAdapters().stream().filter(a->manager.getAdapterSourceId(a).equals("productivebeesgenesis")).findFirst().orElseThrow(()->new AssertionError("Local Useless adapter was not registered"));
    var bridge=(com.ayoshiko.productivebeesgenesis.mek.ae2.UselessResourceRecipes.Centrifuging)adapter;
    check(bridge.getGeneratedRecipes(level).size()>0,"Local Useless CoreRecipeHolder source enumeration failed");checks.add("local UselessMod and UselessCore adapter registration");
    var nativeKey=appeng.api.stacks.AEItemKey.of(Items.DIAMOND);var key=ResourceKey.aeKey(nativeKey.getType().getId(),nativeKey.getId(),nativeKey.toTag());
    var conversion=new ResourceCentrifugeRecipe(new ResourceLocation("productivebees:iron"),List.of(new ResourceProductionRecipe.Output(key,4,1)),20,false);
    var converted=bridge.convertAll(new com.sorrowmist.uselesscore.api.recipe.CoreRecipeHolder<>(new ResourceLocation("genesis_port_smoke:useless"),conversion),level);
    check(converted.size()==2&&converted.stream().allMatch(result->result.keyOutputs().stream().anyMatch(output->output.what().equals(nativeKey)&&output.amount()>0)),"Local Useless native ME outputs could not be built");checks.add("local Useless comb/block recipe construction with native ME outputs");
   }
   report.addProperty("passed",true);System.out.println("PBG_PORT_SMOKE_PASSED "+checks);
  }catch(Throwable error){report.addProperty("passed",false);report.addProperty("failure",error.toString());error.printStackTrace();System.out.println("PBG_PORT_SMOKE_FAILED "+error);}
  finally{try{level.removeBlock(pos,false);Files.writeString(Path.of("port-smoke-result.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report));}catch(Exception e){e.printStackTrace();}server.halt(false);}
 }
}
