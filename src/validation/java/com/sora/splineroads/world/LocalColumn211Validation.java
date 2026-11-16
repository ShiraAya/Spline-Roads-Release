package com.sora.splineroads.world;
import java.util.*;
import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import net.minecraft.core.BlockPos;

/** Local sidewalk obstruction queries must match complete deck rasters exactly. */
public final class LocalColumn211Validation {
 public static void main(String[] args)throws Exception {
  System.setProperty("inspection.world",args[0]);var data=Followup211Inspection.load();
  var sorted=new ArrayList<>(data.index.roads.values());sorted.sort(Comparator.comparingDouble((RoadIndex.Built b)->b.mesh.length()).reversed());
  var selected=new LinkedHashSet<RoadIndex.Built>();selected.addAll(sorted.subList(0,Math.min(10,sorted.size())));
  for(var b:sorted)if(b.record.junction()!=null&&selected.stream().filter(x->x.record.junction()!=null).count()<4)selected.add(b);
  for(var b:sorted)if(b.hasTunnel()&&selected.stream().filter(RoadIndex.Built::hasTunnel).count()<4)selected.add(b);
  long checks=0,columns=0;
  for(var b:selected){var positions=new LinkedHashSet<BlockPos>();
   for(int i=0;i<=48;i++){var s=RoadStructures.sample(b.mesh,b.mesh.length()*i/48);for(double offset:new double[]{-s.halfWidth()-1,-s.halfWidth(),-s.halfWidth()+.01,0,s.halfWidth()-.01,s.halfWidth(),s.halfWidth()+1}){var p=s.at(offset,0);positions.add(BlockPos.containing(p.x(),p.y(),p.z()));}}
   var local=new HashMap<BlockPos,RoadIndex.Built.Column>();for(var p:positions)local.put(p,b.column(p));
   if(b.rasterized())throw new AssertionError("local column unexpectedly rasterized the whole road");
   for(var p:positions){var actual=local.get(p);var expected=b.columns.get(BlockPos.asLong(p.getX(),0,p.getZ()));columns++;
    if(!Objects.equals(expected,actual))throw new AssertionError("column mismatch "+b.record.id()+" "+p+" expected="+expected+" actual="+actual);
    for(int y=p.getY()-4;y<=p.getY()+12;y++){boolean a=actual!=null&&y+1>actual.minTop()+1e-6&&y<actual.maxTop()+4.25;boolean e=expected!=null&&y+1>expected.minTop()+1e-6&&y<expected.maxTop()+4.25;if(a!=e)throw new AssertionError("sidewalk obstruction differs");checks++;}
   }b.releaseEditRaster();System.out.println("ROAD PASS "+b.record.id()+" columns="+positions.size());
  }
  if(selected.size()<10)throw new AssertionError("insufficient fixtures");System.out.println("PASS roads="+selected.size()+" columns="+columns+" obstruction_checks="+checks);
 }
}
