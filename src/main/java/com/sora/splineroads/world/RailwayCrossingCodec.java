package com.sora.splineroads.world;
import com.sora.splineroads.core.RailwayCrossings;
import com.sora.splineroads.core.RoadGeometry.V;
import java.util.*;
import net.minecraft.nbt.*;
final class RailwayCrossingCodec{
 static ListTag write(List<RailwayCrossings.Surface> values){var out=new ListTag();for(var p:values){var t=new CompoundTag();t.putDouble("From",p.from());t.putDouble("To",p.to());t.putDouble("X",p.origin().x());t.putDouble("Y",p.origin().y());t.putDouble("Z",p.origin().z());t.putDouble("SlopeX",p.slopeX());t.putDouble("SlopeZ",p.slopeZ());t.putDouble("Transition",p.transition());out.add(t);}return out;}
 static List<RailwayCrossings.Surface> read(ListTag tags){if(tags.size()>64)throw new IllegalArgumentException("Too many railway crossings");var out=new ArrayList<RailwayCrossings.Surface>();for(var item:tags){var t=(CompoundTag)item;out.add(new RailwayCrossings.Surface(t.getDouble("From"),t.getDouble("To"),new V(t.getDouble("X"),t.getDouble("Y"),t.getDouble("Z")),t.getDouble("SlopeX"),t.getDouble("SlopeZ"),t.contains("Transition")?t.getDouble("Transition"):4));}return List.copyOf(out);}
 private RailwayCrossingCodec(){}
}
