package com.ayoshiko.productivebeesgenesis.mixin.client;
import com.ayoshiko.productivebeesgenesis.client.resource.GuiResourceSideConfigTab;
import com.ayoshiko.productivebeesgenesis.resource.ResourceSidePersistence;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.GuiMekanism;
import mekanism.client.gui.element.window.*;
import mekanism.common.inventory.container.*;
import mekanism.common.inventory.container.tile.MekanismTileContainer;
import mekanism.common.tile.base.TileEntityMekanism;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(value=GuiSideConfiguration.class,remap=false)
public abstract class ResourceSideConfigurationMixin extends GuiWindow {
 protected ResourceSideConfigurationMixin(IGuiWrapper gui,int x,int y,int width,int height,SelectedWindowData data){super(gui,x,y,width,height,data);}
 @Inject(method="<init>",at=@At("TAIL"))
 private void genesis$resourceSides(IGuiWrapper gui,int x,int y,TileEntityMekanism tile,CallbackInfo ci){
  if(!ResourceSidePersistence.machine(tile)||!(gui instanceof GuiMekanism<?> screen)||!(screen.getMenu() instanceof MekanismTileContainer<?> menu))return;
  var tab=new GuiResourceSideConfigTab[1];tab[0]=new GuiResourceSideConfigTab(gui,menu,screen,relativeX+width,relativeY+2,false,()->tab[0]);addChild(tab[0]);
 }
}
