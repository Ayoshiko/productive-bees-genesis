package com.ayoshiko.productivebeesgenesis.resource.ars;
import com.ayoshiko.productivebeesgenesis.resource.ResourceScalarOutput;
import com.hollingsworth.arsnouveau.api.source.ISourceTile;
public final class ResourceSourceOutput implements ISourceTile {
 private final ResourceScalarOutput access;
 public ResourceSourceOutput(ResourceScalarOutput access){this.access=access;}
 public int getTransferRate(){return access.canExtract()?Integer.MAX_VALUE:0;}
 public boolean canAcceptSource(){return false;}
 public int getSource(){return (int)Math.min(Integer.MAX_VALUE,access.stored());}
 public int getMaxSource(){return (int)Math.min(Integer.MAX_VALUE,access.capacity());}
 public void setMaxSource(int maximum){}
 public int setSource(int amount){return getSource();}
 public int addSource(int amount){return 0;}
 public int removeSource(int amount){return (int)access.extract(amount,false);}
}
