package com.sora.splineroads.world;

import java.util.*;
import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.RoadStructures.*;

/** Compare the optimized voxel rejection with the original triangle clipping. */
public final class StructureTop211Validation {
  @SuppressWarnings("unchecked") static Map<Long,List<Part>> bins(RoadRaster.Local local)throws Exception {
    var f=RoadRaster.Local.class.getDeclaredField("furniture");f.setAccessible(true);return (Map<Long,List<Part>>)f.get(local);
  }
  static double reference(Map<Long,List<Part>> bins,RoadRaster.Cell cell){
    var square=List.of(new V(cell.x(),0,cell.z()),new V(cell.x()+1,0,cell.z()),new V(cell.x()+1,0,cell.z()+1),new V(cell.x(),0,cell.z()+1));
    double top=Double.POSITIVE_INFINITY;long key=((long)Math.floorDiv(cell.x(),4)<<32)^(Math.floorDiv(cell.z(),4)&0xffffffffL);
    for(var part:bins.getOrDefault(key,List.of())){
      if(!part.material().name().startsWith("WALK_"))continue;var face=part.top();
      for(int i=1;i<face.size()-1;i++){
        var clipped=RoadSurface.intersect(List.of(face.get(0),face.get(i),face.get(i+1)),square);
        if(clipped.isEmpty())continue;double low=clipped.stream().mapToDouble(V::y).min().orElseThrow();
        if(low>cell.y()+1e-7&&low<cell.y()+1-1e-7)top=Math.min(top,low);
      }
    }return top;
  }
  static long compare(RoadRaster.Local local,List<Part> parts,Random random,int count)throws Exception {
    var bins=bins(local);var walk=parts.stream().filter(p->p.material().name().startsWith("WALK_")).toList();if(walk.isEmpty())return 0;
    for(int i=0;i<count;i++){
      var p=walk.get(random.nextInt(walk.size()));var c=p.a().add(p.b()).mul(.5);
      var cell=new RoadRaster.Cell((int)Math.floor(c.x()+random.nextDouble()*4-2),(int)Math.floor(c.y())+random.nextInt(7)-3,(int)Math.floor(c.z()+random.nextDouble()*4-2));
      double old=reference(bins,cell),next=local.minimumStructureTop(cell,part->part.material().name().startsWith("WALK_"));
      if(Double.doubleToLongBits(old)!=Double.doubleToLongBits(next))throw new AssertionError("top differs "+cell+" old="+old+" new="+next);
      if(i%10==0){
        var full=local.structureColumn(cell.x(),cell.z(),RoadsideBypass::walkway);
        var bounded=local.structureColumn(cell.x(),cell.z(),cell.y()-3,cell.y()+1,RoadsideBypass::walkway);
        var selected=new HashMap<RoadRaster.Cell,List<RoadRaster.Box>>();
        full.forEach((key,boxes)->{if(key.y()>=cell.y()-3&&key.y()<cell.y()+1)selected.put(key,boxes);});
        if(!selected.equals(bounded))throw new AssertionError("bounded column differs "+cell);
        boolean oldHeadroom=full.entrySet().stream().anyMatch(e->e.getValue().stream().anyMatch(b->cell.y()+1>e.getKey().y()+b.y1()+1e-7&&cell.y()<e.getKey().y()+b.y1()+2.1));
        boolean newHeadroom=bounded.entrySet().stream().anyMatch(e->e.getValue().stream().anyMatch(b->cell.y()+1>e.getKey().y()+b.y1()+1e-7&&cell.y()<e.getKey().y()+b.y1()+2.1));
        if(oldHeadroom!=newHeadroom)throw new AssertionError("headroom differs "+cell);
      }
    }return count;
  }
  public static void main(String[] args)throws Exception {
    System.setProperty("inspection.world",args[0]);var data=Followup211Inspection.load();var random=new Random(2111116);long checks=0;int saved=0;
    for(var b:data.index.roads.values().stream().sorted(Comparator.comparingInt((RoadIndex.Built b)->b.record.structures().size()).reversed()).limit(12).toList()){
      checks+=compare(b.prepareCollision(),b.record.structures(),random,1500);saved++;
    }
    var mesh=RoadGeometry.build(new Node(new V(0,56,0),0,0),new Node(new V(0,56,40),0,0),new Settings(Mode.STRAIGHT,Style.O2_YELLOW,9,1,.4,90));
    double[] thresholds={0,1e-7,-1e-7,1e-6,-1e-6,.125,.5,.9999999,1.0000001};
    for(int scene=0;scene<160;scene++){
      var parts=new ArrayList<Part>();
      for(int i=0;i<30;i++){double x=random.nextDouble()*24-12,z=random.nextDouble()*24-12,y=55+thresholds[i%thresholds.length];
        var p=new Part(new V(x,y,z),new V(x+random.nextDouble()*5,y+(scene%2==0?0:random.nextDouble()*2-1),z+random.nextDouble()*5),.1+random.nextDouble()*7,.125,false,Material.WALK_STONE_BRICKS);
        if(scene%3==0)p=p.frames(new V(2,.2,0),new V(1,-.3,1));parts.add(p);
      }
      checks+=compare(new RoadRaster.Local(mesh,parts),parts,random,300);
    }
    System.out.println("PASS exact_top_checks="+checks+" exact_bounded_columns_and_headroom="+(checks/10)+" saved_roads="+saved+" synthetic_scenes=160");
  }
}
