package com.ayoshiko.productivebeesgenesis.client.resource;
import com.ayoshiko.productivebeesgenesis.resource.*;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.GuiElement;
import net.minecraft.client.gui.GuiGraphics;
/** A common display for the four native Mekanism chemical types. */
final class GuiResourceChemicalGauge extends GuiElement {
    ResourceKey resource; long amount,capacity;
    GuiResourceChemicalGauge(IGuiWrapper gui,int x,int y,int width,int height){super(gui,x,y,width,height);}
    @Override public void drawBackground(GuiGraphics graphics,int mx,int my,float partial){
        if(!visible||resource==null)return;
        var id=ResourceTypes.chemicalId(resource);
        var chemical=id==null?null:com.ayoshiko.productivebeesgenesis.port.ForgeChemicals.REGISTRY.get(id);
        graphics.fill(relativeX,relativeY,relativeX+width,relativeY+height,0xFF555555);
        graphics.fill(relativeX+1,relativeY+1,relativeX+width-1,relativeY+height-1,0xFF161616);
        if(chemical!=null && capacity>0){
            int filled=(int)Math.max(0,Math.min(height-2,Math.ceil((double)amount/capacity*(height-2))));
            graphics.fill(relativeX+2,relativeY+height-1-filled,relativeX+width-2,relativeY+height-1,0xFF000000|chemical.getTint());
        }
    }
}
