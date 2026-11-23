package com.sora.splineroads.core;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.RoadStructures.*;
import java.util.*;
/** Real geometry projection, including negative chunks and separated height layers. */
public final class FeedbackFix7Map {
 static void check(boolean b,String s){if(!b)throw new AssertionError(s);}
 static Mesh road(double y){var settings=new Settings(Mode.STRAIGHT,Style.H4_RAIL,Style.H4_RAIL.defaultWidth(),1,.4,90);return RoadRibbon.mesh(List.of(new Sample(new V(-32,y,-8),new V(0,0,1),0,6),new Sample(new V(16,y,-8),new V(0,0,1),48,6)),settings);}
 public static void main(String[] args)throws Exception{
  var p=new ArrayList<Part>();p.add(new Part(new V(-32,64,-8),new V(16,64,-8),2,.4,false,Material.GREEN));
  p.add(new Part(new V(-32,64,-3),new V(16,64,-3),2,.5,false,Material.WALK_STONE_BRICKS));
  p.add(new Part(new V(-32,64.5,-2.4),new V(16,64.5,-2.4),.4,.02,false,Material.TACTILE));
  p.add(new Part(new V(-8,64,-10),new V(-8,64,-10),.25,5,true,Material.DARK_STEEL));
  var source=new RoadMapTile.Source(UUID.randomUUID(),road(64),List.copyOf(p));
  var bridge=new RoadMapTile.Source(UUID.randomUUID(),road(80),List.of());
  var tile=new RoadMapTile(-1,-1,List.of(source,bridge));
  check(tile.at(-8,63,-10)==RoadMapTile.Material.ASPHALT,"asphalt at negative coordinate");
  check(tile.at(-8,64,-8)==RoadMapTile.Material.GREEN,"median");
  check(tile.at(-8,64,-4)==RoadMapTile.Material.STONE_BRICKS,"sidewalk");
  check(tile.at(-8,64,-3)==RoadMapTile.Material.YELLOW,"tactile");
  check(tile.at(-8,79,-10)==RoadMapTile.Material.ASPHALT,"upper bridge");
  check(tile.at(-8,68,-10)==null,"pole must not fill the sky");
  check(tile.at(-8,70,-8)==null,"gap between layers");
  check(tile.at(-8,60,-8)==null,"no road at arbitrary Y");
  var empty=new RoadMapTile(-1,-1,List.of());check(empty.size()==0,"deleted road");
  var triangle=RoadSlicing.attach(road(64),new RoadSlicing.Geometry(List.of(new RoadSlicing.Patch(0,48,List.of(new LaneDeck.Strip(new V(-16,64,-16),new V(-16,64,0),new V(0,64,-16),new V(0,64,-16),false,false)),true)),true));
  var clipped=new RoadMapTile(-1,-1,List.of(new RoadMapTile.Source(UUID.randomUUID(),triangle,List.of())));
  check(clipped.at(-14,63,-14)==RoadMapTile.Material.ASPHALT,"clipped triangle interior");check(clipped.at(-1,63,-1)==null,"clipped junction exterior must remain terrain");
  System.out.println("FIX7_MAP_PASS assertions=11 layeredPixels="+tile.size()+" clippedPixels="+clipped.size());
  var worker=new LatestPlanningTask<Integer>("fix7-progress",10);
  var phaseReady=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
  var complete=new java.util.concurrent.CountDownLatch(1);var result=new java.util.concurrent.atomic.AtomicInteger();
  worker.submit(()->{try(var scope=RoadPlanningBudget.open("nested",8)){RoadPlanningBudget.phase("检查净高");phaseReady.countDown();release.await();return 7;}},(value,error)->{if(error==null)result.set(value);complete.countDown();});
  check(phaseReady.await(5,java.util.concurrent.TimeUnit.SECONDS),"worker reached phase");check(worker.progress().startsWith("检查净高 · "),"nested phase reaches editor");
  worker.cancel();release.countDown();
  worker.submit(()->9,(value,error)->{if(error==null)result.set(value);complete.countDown();});
  check(complete.await(5,java.util.concurrent.TimeUnit.SECONDS)&&result.get()==9,"cancelled computation cannot publish over latest result");
  System.out.println("FIX7_PROGRESS_PASS assertions=3");
 }
}
