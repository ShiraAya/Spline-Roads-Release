package com.sora.splineroads.world;

import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;

/** Splits only intersections involving the road currently being created or edited. */
public final class AutoCrossings {
  public record Hit(UUID road,double proposed,double other,V center) {}
  record Cut(double station,V center) {}
  record Split(List<RoadRecord> roads,List<RoadData.NodeMove> nodes,Map<UUID,List<RoadRecord>> pieces) {}
  static boolean eligible(RoadRecord r){return AutoJunctions.ordinary(r)&&!r.mesh().closed()&&!RoadTunnelFit.enabled(r.settings());}
  private static V median(Mesh m,Sample s){return s.at(RoadProfile.layout(m,s).medianCenter(),0);}
  private static double cross(V a,V b){return a.x()*b.z()-a.z()*b.x();}
  private record Segment(V start,V delta,double length,RoadBoundsIndex.Bounds bounds){}
  private static List<Segment> segments(Mesh mesh){
    var points=mesh.samples().stream().map(s->median(mesh,s)).toList();var result=new ArrayList<Segment>();
    for(int i=1;i<points.size();i++){var p=points.get(i-1);var q=points.get(i);var delta=q.sub(p);double length=delta.horizontalLength();
      // The exact test accepts parameters 1e-7 beyond either endpoint.
      double margin=length*1e-7+1e-7;
      result.add(new Segment(p,delta,length,new RoadBoundsIndex.Bounds(Math.min(p.x(),q.x())-margin,Math.min(p.z(),q.z())-margin,Math.max(p.x(),q.x())+margin,Math.max(p.z(),q.z())+margin)));
    }return result;
  }
  public static List<Hit> hits(RoadRecord proposed,Collection<RoadRecord> roads){
    if(!eligible(proposed))return List.of();var result=new ArrayList<Hit>();var a=proposed.mesh();
    var as=segments(a);
    for(var road:roads){if(road.id().equals(proposed.id())||!eligible(road))continue;var b=road.mesh();if(!RoadIndex.overlapXZ(a,b,0))continue;
      var bs=segments(b);var index=new RoadBoundsIndex(bs.stream().map(Segment::bounds).toList(),16);
      for(int i=1;i<a.samples().size();i++){RoadPlanningBudget.check();var a0=a.samples().get(i-1);var a1=a.samples().get(i);var sa=as.get(i-1);V p=sa.start(),u=sa.delta();
        for(int slot:index.query(sa.bounds(),0)){int j=slot+1;var b0=b.samples().get(j-1);var b1=b.samples().get(j);var sb=bs.get(slot);V q=sb.start(),v=sb.delta();double det=cross(u,v);
          if(Math.abs(det)<1e-8*sa.length()*sb.length())continue;
          double t=cross(q.sub(p),v)/det,s=cross(q.sub(p),u)/det;if(t< -1e-7||t>1+1e-7||s< -1e-7||s>1+1e-7)continue;
          t=Math.max(0,Math.min(1,t));s=Math.max(0,Math.min(1,s));V x=p.add(u.mul(t)),y=q.add(v.mul(s));if(Math.abs(x.y()-y.y())>.02)continue;
          double ad=a0.distance()+(a1.distance()-a0.distance())*t,bd=b0.distance()+(b1.distance()-b0.distance())*s;
          // Shared endpoints already belong to the normal junction path.
          if((ad<.01||ad>a.length()-.01)&&(bd<.01||bd>b.length()-.01))continue;
          V center=x.add(y).mul(.5);
          // Free ends include a short pavement cap beyond their node. A shared
          // node is already handled by AutoJunctions, even when that cap crosses.
          if(sharedEnd(proposed,road,center))continue;
          if(result.stream().noneMatch(h->h.road().equals(road.id())&&h.center().distance(center)<.1))result.add(new Hit(road.id(),ad,bd,center));
        }
      }
    }return List.copyOf(result);
  }
  private static boolean sharedEnd(RoadRecord a,RoadRecord b,V p){for(boolean first:new boolean[]{true,false}){var pos=first?a.a():a.b();if((pos.equals(b.a())||pos.equals(b.b()))&&p.distance((first?a.start():a.end()).position())<2)return true;}return false;}
  static List<RoadRecord> candidates(CompoundTag t){var out=new ArrayList<RoadRecord>();for(Tag entry:t.getList("CrossingCandidates",Tag.TAG_COMPOUND))out.add(RoadRecord.load(((CompoundTag)entry).getCompound("Road")));return out;}
  static void enrich(CompoundTag command,RoadData data,BlockPos a,BlockPos b,UUID id){enrich(command,data,a,b,id,true);}
  static void enrich(CompoundTag command,RoadData data,BlockPos a,BlockPos b,UUID id,boolean structures){
    var list=new ListTag();double reach=RoadLimits.MAX_ENDPOINT_DISTANCE;
    var available=new LinkedHashMap<>(AutoJunctions.logical(data));data.index.roads.values().stream().map(built->built.record).filter(RoadRecord::railway).forEach(r->available.put(r.id(),r));
    for(var r:available.values()){if(r.id().equals(id)||!eligible(r)&&!r.railway())continue;var m=r.mesh();
      if(m.max().x()<Math.min(a.getX(),b.getX())-reach||m.min().x()>Math.max(a.getX(),b.getX())+reach||m.max().z()<Math.min(a.getZ(),b.getZ())-reach||m.min().z()>Math.max(a.getZ(),b.getZ())+reach)continue;
      var entry=new CompoundTag();entry.put("Road",r.header());entry.put("Graph",r.railway()?new CompoundTag():AutoJunctions.context(data,Set.of(r.a(),r.b()),structures));list.add(entry);
    }command.put("CrossingCandidates",list);
  }
  static CompoundTag graph(CompoundTag command,List<Hit> hits){
    var result=command.getCompound("JunctionGraph").copy();
    for(Tag entry:command.getList("CrossingCandidates",Tag.TAG_COMPOUND)){var t=(CompoundTag)entry;if(hits.stream().anyMatch(h->h.road().equals(t.getCompound("Road").getUUID("Id"))))merge(result,t.getCompound("Graph"));}
    var active=new LinkedHashSet<Long>();for(long p:result.getLongArray("ActiveNodes"))active.add(p);for(var h:hits)active.add(at(h.center()).asLong());result.putLongArray("ActiveNodes",active.stream().mapToLong(Long::longValue).toArray());return result;
  }
  private static void merge(CompoundTag into,CompoundTag from){
    for(String key:List.of("Streets","Centers","RemoteTrims")){var entries=new LinkedHashMap<UUID,CompoundTag>();for(Tag v:into.getList(key,Tag.TAG_COMPOUND)){var t=(CompoundTag)v;entries.put(t.getUUID("Id"),t);}for(Tag v:from.getList(key,Tag.TAG_COMPOUND)){var t=(CompoundTag)v;entries.put(t.getUUID("Id"),t.copy());}var list=new ListTag();entries.values().forEach(list::add);into.put(key,list);}
    var continuations=into.getList("SlipNext",Tag.TAG_COMPOUND);
    for(Tag edge:from.getList("SlipNext",Tag.TAG_COMPOUND))if(!continuations.contains(edge))continuations.add(edge.copy());
    into.put("SlipNext",continuations);
    var active=new LinkedHashSet<Long>();for(long p:into.getLongArray("ActiveNodes"))active.add(p);for(long p:from.getLongArray("ActiveNodes"))active.add(p);into.putLongArray("ActiveNodes",active.stream().mapToLong(Long::longValue).toArray());
    // A previously remote end is now being rebuilt, so its old trim must not survive.
    for(Tag v:into.getList("RemoteTrims",Tag.TAG_COMPOUND)){var trim=(CompoundTag)v;for(Tag q:into.getList("Streets",Tag.TAG_COMPOUND)){var r=(CompoundTag)q;if(!r.getUUID("Id").equals(trim.getUUID("Id")))continue;if(active.contains(r.getLong("A")))trim.remove("Start");if(active.contains(r.getLong("B")))trim.remove("End");}}
  }
  static Split split(List<RoadRecord> roads,RoadRecord proposal,List<Hit> hits,List<CompoundTag> centers){
    if(hits.isEmpty())return new Split(roads,List.of(),Map.of());var cuts=new LinkedHashMap<UUID,List<Cut>>();var nodes=new LinkedHashMap<BlockPos,RoadData.NodeMove>();
    for(var hit:hits){cuts.computeIfAbsent(proposal.id(),k->new ArrayList<>()).add(new Cut(hit.proposed(),hit.center()));cuts.computeIfAbsent(hit.road(),k->new ArrayList<>()).add(new Cut(hit.other(),hit.center()));
      BlockPos pos=at(hit.center());Node node=new Node(hit.center(),0,0);var tag=new CompoundTag();tag.putUUID("Owner",proposal.owner());tag.put("Node",RoadRecord.writeNode(node));tag.putBoolean("HeightExplicit",true);nodes.put(pos,new RoadData.NodeMove(null,pos,node,tag));
      if(centers.stream().noneMatch(t->t.getLong("CenterPos")==pos.asLong()))centers.add(AutoJunctions.config(pos,proposal.owner()));
    }
    var output=new ArrayList<RoadRecord>();var pieces=new LinkedHashMap<UUID,List<RoadRecord>>();
    for(var road:roads){var points=cuts.get(road.id());if(points==null){output.add(road);continue;}var unique=new TreeMap<Double,V>();for(var c:points)if(c.station()>.01&&c.station()<road.mesh().length()-.01&&unique.keySet().stream().noneMatch(d->Math.abs(d-c.station())<.1))unique.put(c.station(),c.center());
      if(unique.isEmpty()){output.add(road);continue;}var segments=new ArrayList<RoadRecord>();double from=0;BlockPos a=road.a();Node start=road.start();
      unique.put(road.mesh().length(),road.end().position());int part=0;
      for(var entry:unique.entrySet()){double to=entry.getKey();boolean last=to==road.mesh().length();V p=entry.getValue();Node end=last?road.end():new Node(p,RoadSplitGeometry.node(road.mesh(),to).yaw(),0);BlockPos b=last?road.b():at(p);UUID id=part++==0?road.id():UUID.nameUUIDFromBytes((road.id()+":cross:"+a.asLong()).getBytes(StandardCharsets.UTF_8));
        var piece=RoadSplitGeometry.junctionRange(road,from,to,id,a,b,start,end,RoadSlicing.Connection.JUNCTION);segments.add(piece);from=to;a=b;start=end;
      }output.addAll(segments);pieces.put(road.id(),List.copyOf(segments));
      // Existing far-junction lane/phase references move with the far segment.
      for(var c:centers)if(c.getLong("CenterPos")==road.b().asLong()){var arms=c.getList("ArmRoads",Tag.TAG_STRING);for(int i=0;i<arms.size();i++)if(arms.getString(i).equals(road.id().toString()))arms.set(i,StringTag.valueOf(segments.get(segments.size()-1).id().toString()));}
    }return new Split(List.copyOf(output),List.copyOf(nodes.values()),Map.copyOf(pieces));
  }
  static RoadRecord remap(RoadRecord road,Map<UUID,List<RoadRecord>> pieces){var next=road;for(var entry:pieces.entrySet())for(var piece:entry.getValue())if(!piece.id().equals(entry.getKey())){var points=new HashSet<UUID>();for(var p:piece.settings().options().lanePoints().points())points.add(p.id());next=RoadSplitter.remapLinks(next,entry.getKey(),piece.id(),points);}return next;}
  private static BlockPos at(V p){return BlockPos.containing(p.x(),p.y(),p.z());}
  private AutoCrossings(){}
}
