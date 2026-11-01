package com.ayoshiko.productivebeesgenesis.port;
import net.minecraft.world.item.Item;
import java.util.function.Supplier;
import cy.jdkdigital.productivebees.common.block.entity.AdvancedBeehiveBlockEntity;
import cy.jdkdigital.productivebees.common.block.entity.AdvancedBeehiveBlockEntityAbstract;
import net.minecraft.nbt.CompoundTag;
public final class ForgePbUpgrades {
 public static final Supplier<Item> UPGRADE_CHILD=()->net.minecraft.world.item.Items.AIR;
 public static final Supplier<Item> UPGRADE_GENE_SAMPLER=()->net.minecraft.world.item.Items.AIR;
 public static final Supplier<Item> UPGRADE_PRODUCTIVITY=()->cy.jdkdigital.productivebees.init.ModItems.UPGRADE_PRODUCTIVITY.get();
 public static final Supplier<Item> UPGRADE_PRODUCTIVITY_2=()->cy.jdkdigital.productivebees.init.ModItems.UPGRADE_PRODUCTIVITY_2.get();
 public static final Supplier<Item> UPGRADE_PRODUCTIVITY_3=()->cy.jdkdigital.productivebees.init.ModItems.UPGRADE_PRODUCTIVITY_3.get();
 public static final Supplier<Item> UPGRADE_PRODUCTIVITY_4=()->cy.jdkdigital.productivebees.init.ModItems.UPGRADE_PRODUCTIVITY_4.get();
 public static final Supplier<Item> UPGRADE_BLOCK=()->cy.jdkdigital.productivebees.init.ModItems.UPGRADE_COMB_BLOCK.get();
 public static final Supplier<Item> UPGRADE_STABILITY=()->com.ayoshiko.productivebeesgenesis.init.ModItems.STABILITY_UPGRADE.get();
 public static net.minecraftforge.items.IItemHandlerModifiable inventory(AdvancedBeehiveBlockEntity hive){var inventory=hive.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ITEM_HANDLER).orElse(null);return inventory instanceof net.minecraftforge.items.IItemHandlerModifiable modifiable ? modifiable : null;}
 public static java.util.List<CompoundTag> occupants(AdvancedBeehiveBlockEntityAbstract hive){return hive.getBeeList().stream().map(bee->{var tag=new CompoundTag();tag.put("bee",bee.nbt.copy());tag.putInt("ticks",bee.ticksInHive);tag.putInt("minimum",bee.minOccupationTicks);return tag;}).toList();}
}
