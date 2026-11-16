package com.sora.splineroads.world;
import java.nio.file.*;import java.util.*;import net.minecraft.nbt.*;import com.sora.splineroads.core.*;
public final class HostAxis211Validation {
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 public static void main(String[] args)throws Exception{
  var t=NbtIo.readCompressed(Path.of(args[0]).toFile());var before=t.copy();var ns=HostAxes.nodes(t);var original=Interchanges.plan(t);check(original.anchors().get(1).position().distance(ns[1].position())>.49,"captured original must reproduce half-block drift");
  var roads=ContinuousRoads.sources(t).stream().filter(s->s.axis()==0).map(ContinuousRoads.Source::road).toList();check(roads.size()==1,"native host count");var r=roads.get(0);int cases=0;
  for(int mask=0;mask<4;mask++)for(boolean reverse:new boolean[]{false,true}){var road=r.caps(mask);var axis=HostAxes.chain(List.of(road),reverse?road.b():road.a());check(axis.at(0).distance((reverse?road.end():road.start()).position())<1e-7,"authored start mask="+mask);check(axis.at(axis.length()).distance((reverse?road.start():road.end()).position())<1e-7,"authored end mask="+mask);cases++;}
  var axes=new ArrayList<>(HostAxes.read(t));axes.set(0,HostAxes.chain(roads,r.a()));HostAxes.write(t,axes);var fixed=Interchanges.plan(t);for(int i=0;i<ns.length;i++)check(fixed.anchors().get(i).equals(ns[i]),"unchanged fixed anchor "+i);check(before.equals(NbtIo.readCompressed(Path.of(args[0]).toFile())),"fixture mutated");
  System.setProperty("inspection.world",args[1]);var data=Followup211Inspection.load();var at=ns[2].position();
  var retained=data.index.roads.values().stream().filter(b->b.mesh.last().center().distance(at)<1e-7).findFirst().orElseThrow();
  var branch=fixed.legs().stream().filter(l->l.mesh().first().center().distance(at)<1e-7).findFirst().orElseThrow().mesh();
  check(RoadClearance.contacts(branch,retained.mesh).isEmpty(),"unchanged external seam intersects");
  var delta=ns[2].direction().mul(-.05);var shifted=RoadRibbon.mesh(branch.samples().stream().map(s->new com.sora.splineroads.core.RoadGeometry.Sample(s.center().add(delta),s.left(),s.distance(),s.halfWidth())).toList(),branch.settings());
  check(RoadClearance.contacts(shifted,retained.mesh).stream().anyMatch(RoadClearance.Contact::blocked),"real overlap escaped clearance");
  System.out.println("HOST_AXIS211 PASS cases="+cases+" exact_anchors="+ns.length+" original_half_block_drift=true exact_seam=true real_overlap_rejected=true legs="+fixed.legs().size());
 }
}
