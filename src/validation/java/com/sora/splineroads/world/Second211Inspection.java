package com.sora.splineroads.world;
import java.nio.file.*;import java.util.*;import net.minecraft.nbt.*;import com.sora.splineroads.core.*;import com.sora.splineroads.core.RoadGeometry.*;
public final class Second211Inspection {
 public static RoadData load()throws Exception{System.setProperty("inspection.world","work/feedback-211-nov15-second/fps-roads.dat");return Followup211Inspection.load();}
 public static void main(String[] args)throws Exception{
  var d=load();System.out.println("roads="+d.index.roads.size());
  for(var t:d.interchanges.values())System.out.println("GROUP "+t.getUUID("Id")+" "+t.getCompound("Options")+" points="+Arrays.toString(t.getLongArray("Points")));
  for(String id:List.of("3e39ef94-5b45-471e-b067-6188b26fd29a","a7dd524c-6876-479e-9d20-1a0f6116ce99")){
   var m=d.index.roads.get(UUID.fromString(id)).mesh;var neighbors=d.index.roads.values().stream().filter(r->!r.record.id().toString().equals(id)&&RoadIndex.overlapXZ(m,r.mesh,3)).map(r->r.mesh).toList();
   var method=RoadJunction.class.getDeclaredMethod("openGuide",Mesh.class,Sample.class,Sample.class,int.class,Mesh.class,Map.class,List.class);method.setAccessible(true);var boundaries=new IdentityHashMap<Mesh,RoadRailJoin>();
   var csv=new StringBuilder();for(var paint:RoadJunction.markings(m,neighbors,neighbors,true)){for(var v:paint.points())csv.append(v.x()).append(',').append(v.y()).append(',').append(v.z()).append(';');csv.append('\n');}Files.writeString(Path.of("work/feedback-211-nov15-second/paint-"+id+".csv"),csv);
   for(var item:LaneRampPaint.auxiliary(m,neighbors))System.out.println("AUX "+id+" side="+item.side()+" from="+item.from()+" to="+item.to());int near=0,fit=0,open=0;for(double x=1;x<m.length();x+=3){var a=RoadStructures.sample(m,x);var b=RoadStructures.sample(m,Math.min(m.length(),x+1.5));var mid=RoadStructures.sample(m,Math.min(m.length(),x+.75));for(int side:new int[]{-1,1})for(var ramp:neighbors)if(ramp.settings().style().ramp()){
    var q=RoadQueries.horizontal(ramp,mid.at(side*mid.halfWidth(),0));if(id.startsWith("a7dd")&&(x==238||x==241)&&side==1)System.out.println("MISSING d="+x+" q="+q+" dy="+(q.sample().center().y()-mid.center().y())+" dot="+q.sample().left().dot(mid.left())+" side="+q.sample().center().sub(mid.center()).dot(mid.left())+" fits="+LaneRampPaint.guideFits(m,a,b,side,ramp)+" open="+method.invoke(null,m,a,b,side,ramp,boundaries,neighbors));if(Math.abs(q.sample().center().y()-mid.center().y())>.12||q.horizontalDistance()>q.sample().halfWidth()+.12||Math.abs(q.sample().left().dot(mid.left()))<.90||q.sample().center().sub(mid.center()).dot(mid.left())*side<0)continue;
    if(id.startsWith("3e39")&&x>=919&&x<=937&&side==1){
     var qa=RoadQueries.project(ramp,a.at(side*a.halfWidth(),0));var qb=RoadQueries.project(ramp,b.at(side*b.halfWidth(),0));var ra=qa.sample();var rb=qb.sample();int inside=ra.left().dot(a.center().sub(ra.center()))<0?-1:1;double ia=RoadRailJoin.inset(ramp,ra,inside),ib=RoadRailJoin.inset(ramp,rb,inside);var first=ra.at(inside*(ra.halfWidth()-ia),0);var last=rb.at(inside*(rb.halfWidth()-ib),0);var outside=first.add(last).mul(.5).add(ra.left().add(rb.left()).horizontalUnit().mul(inside*.4));var bd=new RoadRailJoin(ramp,java.util.stream.Stream.concat(java.util.stream.Stream.of(m),neighbors.stream().filter(n->n!=ramp)).distinct().map(n->new RoadRailJoin.Neighbor(n,true)).toList());
     System.out.println("SPUI GAP d="+x+" link="+ramp.settings().options().lanePoints().link()+" type="+RoadProfile.layout(m,a).catalog().type()+" qa="+qa+" dy="+(qa.sample().center().y()-a.center().y())+" ia="+ia+" first="+first+" last="+last+" spans="+bd.exposed(first,last,outside,(ia+ib)/2)+" hostspan="+new RoadRailJoin(m,neighbors.stream().map(n->new RoadRailJoin.Neighbor(n,true)).toList()).exposed(a.at(side*(a.halfWidth()-RoadRailJoin.INSET),0),b.at(side*(b.halfWidth()-RoadRailJoin.INSET),0),a.at(side*(a.halfWidth()+1),0)));
    }
    near++;boolean f=LaneRampPaint.guideFits(m,a,b,side,ramp),o=(boolean)method.invoke(null,m,a,b,side,ramp,boundaries,neighbors);if(f)fit++;if(o)open++;System.out.println("GUIDE "+id+" d="+x+" side="+side+" fits="+f+" open="+o+" pos="+mid.center());
   }}System.out.println("GUIDE COUNT "+near+" fits="+fit+" open="+open);
  }
  var t=d.interchanges.values().stream().filter(ParallelSpui::active).findFirst().orElseThrow().copy();ParallelSpui.capture(d,t,null);
  for(var leg:Interchanges.plan(t).legs())if(leg.mesh().settings().style().ramp()){var m=leg.mesh();double grade=0;for(int i=1;i<m.samples().size();i++){var v=m.samples().get(i).center().sub(m.samples().get(i-1).center());grade=Math.max(grade,Math.abs(v.y())/v.horizontalLength());}System.out.println("SPUI length="+m.length()+" grade="+grade);}

 }
}
