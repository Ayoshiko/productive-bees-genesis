package com.ayoshiko.productivebeesgenesis.mixin.beehive;

import com.ayoshiko.productivebeesgenesis.resource.ResourceOutputAccess;
import com.hollingsworth.arsnouveau.api.source.ISourceTile;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(value={mekanism.common.tile.base.TileEntityMekanism.class,cy.jdkdigital.productivebees.common.block.entity.AdvancedBeehiveBlockEntityAbstract.class},remap=false)
public abstract class ResourceArsSourceMixin implements ISourceTile {
    @Unique private ISourceTile genesis$source() { var access=ResourceOutputAccess.find((BlockEntity)(Object)this);return access==null?null:(ISourceTile)access.sourceCapability(); }
    public int getTransferRate(){var source=genesis$source();return source==null?0:source.getTransferRate();}
    public boolean canAcceptSource(){return false;}
    public int getSource(){var source=genesis$source();return source==null?0:source.getSource();}
    public int getMaxSource(){var source=genesis$source();return source==null?0:source.getMaxSource();}
    public void setMaxSource(int maximum){}
    public int setSource(int amount){return getSource();}
    public int addSource(int amount){return 0;}
    public int removeSource(int amount){var source=genesis$source();return source==null?0:source.removeSource(amount);}
}
