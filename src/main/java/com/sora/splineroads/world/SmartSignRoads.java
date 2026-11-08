package com.sora.splineroads.world;
import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.SmartSigns.*;
import com.sora.splineroads.core.SmartSigns.Mode;
import com.sora.splineroads.net.RoadNetwork;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;
import java.util.*;

/** Saved metadata only. No chunk acquisition, rasterisation, road solving or asynchronous work. */
public final class SmartSignRoads {
  public static final int MAX_VISITS=Math.max(16,Math.min(1024,Integer.getInteger("splineroads.signSearchSegments",256)));public static final double MAX_DISTANCE=Math.max(128,Math.min(16384,Integer.getInteger("splineroads.signSearchDistance",4096)));
  private final RoadData data;
  private final Map<Key,State> states=new LinkedHashMap<>();
  private final Map<UUID,Set<Key>> keysByRoad=new HashMap<>();
  private final Map<Long,Set<Key>> supportChunks=new HashMap<>();
  private final Map<UUID,Long> versions=new HashMap<>();
  private record Presence(RoadIndex.Built road,boolean value){}
  private final Map<Key,Presence> presentCache=new HashMap<>();
  public long workSteps;
  private final Map<UUID,RoadIndex.Built> known=new HashMap<>();
  private final Map<String,Set<UUID>> ports=new HashMap<>();
  private final Map<UUID,Set<UUID>> groups=new HashMap<>(),hosts=new HashMap<>();
  private final Map<UUID,List<Exit>> exits=new HashMap<>();
  private final Map<UUID,Set<Key>> dependencies=new HashMap<>();
  private final Map<Key,Set<UUID>> searched=new HashMap<>();
  private final Set<UUID> dirty=new LinkedHashSet<>(),publish=new LinkedHashSet<>();
  public long searches,visited,layoutChanges,textEdits;
  public SmartSignRoads(RoadData d){data=d;}
  private final Set<UUID> sightDirty=new LinkedHashSet<>();
  private RoadSignSightlines sightWork;
  private boolean applyingSight;
  private int maintenanceFamily;
  private long sightWaves,sightInvalidations,sightQueries,sightPartVisits,sightKeyBuilds,sightCommits;
  public void changed(UUID id){
    dirty.add(id);
    if(!applyingSight){sightDirty.add(id);if(sightWork!=null)sightWork.changed(id);}
    // Hide old physical mounts immediately, including signs owned by dependent roads.
    // Their text/drafts stay intact while the final topology is reconciled.
    var keys=new HashSet<>(keysByRoad.getOrDefault(id,Set.of()));keys.addAll(dependencies.getOrDefault(id,Set.of()));
    for(var key:keys){var s=states.get(key);if(s!=null&&SmartSigns.freestanding(s.mode())&&s.support()!=null)
      put(s.layout(s.mode(),s.target(),s.branches(),"标牌关联正在更新",s.entry()).supported(null));}
  }
  private boolean compatible(RoadIndex.Built b,State s){
    if(b==null)return false;
    if(s.mode()==Mode.JUNCTION_ADVANCE||s.mode()==Mode.ROAD_NAME){var j=b.record.junction();return j!=null&&j.get().arm()>=0&&j.spec().arms().get(j.get().arm()).incoming()>0;}
    if(s.mode()==Mode.ROADSIDE&&elevated(b))return s.key().slot()==0&&s.key().flow()==1;
    if(s.mode()==Mode.ROADSIDE)return s.key().flow()==1&&(s.key().slot()==0&&(LaneTopology.metadata(b.record).link()!=null||b.record.assembly()!=null&&b.record.settings().style().ramp())
        ||s.key().slot()==1&&b.record.assembly()!=null&&b.record.settings().style().ramp());
    return main(b)&&RoadGantry.exists(b.mesh,s.key().slot());
  }
  record Snapshot(List<State> states,Map<UUID,Long> versions,Set<UUID> sightDirty){
    Snapshot(List<State> states,Map<UUID,Long> versions){this(states,versions,Set.of());}
  }
  Snapshot snapshot(){return new Snapshot(List.copyOf(states.values()),Map.copyOf(versions),Set.copyOf(sightDirty));}
  void restore(Snapshot saved){
    states.clear();keysByRoad.clear();supportChunks.clear();versions.clear();versions.putAll(saved.versions());presentCache.clear();
    known.clear();ports.clear();groups.clear();hosts.clear();exits.clear();dependencies.clear();searched.clear();dirty.clear();publish.clear();sightDirty.clear();work=null;
    sightDirty.addAll(saved.sightDirty());
    // A transaction runs on the same server thread. Its rollback must recheck
    // captured geometry while retaining cleanup for lamps committed beforehand.
    if(sightWork!=null){sightWork.invalidate();sightDirty.addAll(sightWork.requested());}
    for(var state:saved.states())addState(state);dirty.addAll(data.index.roads.keySet());
  }
  /** Rekey the saved state before topology work can retire the old slot. */
  void splitKeys(UUID source,Map<Integer,UUID> owners){
    var saved=new ArrayList<>(road(source));
    for(var state:saved){UUID target=owners.get(state.key().slot());if(target==null)throw new IllegalArgumentException("分割后找不到原标牌的唯一所属龙门架");if(target.equals(source))continue;
      removeState(state.key());addState(state.rekey(new Key(target,state.key().slot(),state.key().flow())));versions.merge(target,1L,Long::sum);publish.add(target);
    }
    versions.merge(source,1L,Long::sum);publish.add(source);
  }
  private void retire(Key key){removeState(key);versions.merge(key.road(),1L,Long::sum);publish.add(key.road());data.setDirty();}
  private static String port(V p){return Math.round(p.x()*1000)+":"+Math.round(p.y()*1000)+":"+Math.round(p.z()*1000);}
  private boolean elevated(RoadIndex.Built b){return b!=null&&b.record.id().equals(b.record.assembly())&&ElevatedAccessRoads.saved(data,b.record.id());}
  private boolean main(RoadIndex.Built b){return b!=null&&!elevated(b)&&b.record.toll()==null&&b.record.junction()==null&&!b.record.settings().style().ramp()&&LaneTopology.metadata(b.record).link()==null;}
  private static void remove(Map<String,Set<UUID>> map,String key,UUID id){var ids=map.get(key);if(ids!=null){ids.remove(id);if(ids.isEmpty())map.remove(key);}}
  private record Exit(UUID id,UUID host,int flow,double station,List<Integer> lanes,int side,boolean dedicated,UUID assembly,List<V> route){}
  private record Position(UUID road,int flow,double station){}
  private record Found(Exit exit,double distance,String reason,Set<UUID> visited){}
  private Work work;
  public boolean pending(){return work!=null||!dirty.isEmpty();}
  /** Tests/explicit offline callers only. Live interactions use peek and bounded tick work. */
  public void refresh(){while(pending())advance(Long.MAX_VALUE,4096);}
  public int advance(long budgetNanos,int maxSteps){
    long started=System.nanoTime();int done=0;
    while(done<maxSteps&&(done==0||System.nanoTime()-started<budgetNanos)){
      if(work==null){if(dirty.isEmpty())break;work=new Work(new LinkedHashSet<>(dirty));dirty.clear();}
      if(!work.step())work=null;done++;workSteps++;
    }return done;
  }
  private static Set<Long> mountChunks(Support mount){if(mount==null)return Set.of();var out=new HashSet<Long>();for(int x=(int)Math.floor(mount.foot().x()-6)>>4;x<=(int)Math.floor(mount.foot().x()+6)>>4;x++)for(int z=(int)Math.floor(mount.foot().z()-6)>>4;z<=(int)Math.floor(mount.foot().z()+6)>>4;z++)out.add(net.minecraft.world.level.ChunkPos.asLong(x,z));return out;}
  private void unindexSupport(State state){if(state!=null)for(long chunk:mountChunks(state.support())){var keys=supportChunks.get(chunk);if(keys!=null){keys.remove(state.key());if(keys.isEmpty())supportChunks.remove(chunk);}}}
  public List<State> nearSupports(V eye){var keys=new LinkedHashSet<Key>();for(int x=(int)Math.floor(eye.x()-96)>>4;x<=(int)Math.floor(eye.x()+96)>>4;x++)for(int z=(int)Math.floor(eye.z()-96)>>4;z<=(int)Math.floor(eye.z()+96)>>4;z++)keys.addAll(supportChunks.getOrDefault(net.minecraft.world.level.ChunkPos.asLong(x,z),Set.of()));return keys.stream().map(states::get).filter(Objects::nonNull).toList();}
  private void addState(State state){var old=states.get(state.key());if(old==null||!Objects.equals(old.support(),state.support())){unindexSupport(old);for(long chunk:mountChunks(state.support()))supportChunks.computeIfAbsent(chunk,k->new LinkedHashSet<>()).add(state.key());}states.put(state.key(),state);keysByRoad.computeIfAbsent(state.key().road(),id->new LinkedHashSet<>()).add(state.key());}
  private void removeState(Key key){unindexSupport(states.get(key));states.remove(key);forget(key);presentCache.remove(key);var keys=keysByRoad.get(key.road());if(keys!=null){keys.remove(key);if(keys.isEmpty())keysByRoad.remove(key.road());}}
  private final class Work {
    final Set<UUID> changed,rampDirty=new LinkedHashSet<>(),groupDirty=new LinkedHashSet<>(),oldHosts=new LinkedHashSet<>();
    final Set<Key> affected=new LinkedHashSet<>();
    final Map<Key,Found> anyQueries=new HashMap<>(),assemblyQueries=new HashMap<>();
    final Map<UUID,Closest> owners=new HashMap<>();
    final Map<UUID,Map<String,InterchangeSignForks.Fork>> forks=new HashMap<>();
    Iterator<UUID> roads,ramps,upstream,cleanup;Iterator<Map.Entry<UUID,List<Exit>>> oldExits;Iterator<Key> keys;int phase;
    Work(Set<UUID> ids){changed=ids;roads=ids.iterator();}
    boolean step(){
      if(phase==0){if(roads.hasNext()){index(roads.next());return true;}for(UUID g:groupDirty)rampDirty.addAll(groups.getOrDefault(g,Set.of()));oldExits=exits.entrySet().iterator();phase=1;return true;}
      if(phase==1){if(oldExits.hasNext()){var e=oldExits.next();e.getValue().removeIf(v->{boolean hit=rampDirty.contains(v.id())||changed.contains(v.host());if(hit)oldHosts.add(v.host());return hit;});if(e.getValue().isEmpty())oldExits.remove();return true;}ramps=rampDirty.iterator();phase=2;return true;}
      if(phase==2){if(ramps.hasNext()){var b=known.get(ramps.next());if(elevated(b)){elevatedSign(b);return true;}if(b!=null&&!main(b)){var derived=derive(b);for(var e:derived){exits.computeIfAbsent(e.host(),k->new ArrayList<>()).add(e);oldHosts.add(e.host());}roadside(b,derived);}return true;}var hosts=new LinkedHashSet<>(oldHosts);for(UUID id:changed)if(main(known.get(id)))hosts.add(id);upstream=hosts.iterator();phase=3;return true;}
      if(phase==3){if(upstream.hasNext()){UUID host=upstream.next();affected.addAll(dependencies.getOrDefault(host,Set.of()));upstreamKeys(host,affected);return true;}keys=affected.iterator();phase=4;return true;}
      if(phase==4){if(keys.hasNext()){Key k=keys.next();if(states.containsKey(k)){
          var old=states.get(k);var built=known.get(k.road());
          if(old.mode()==Mode.JUNCTION_ADVANCE||old.mode()==Mode.ROAD_NAME){
            if(built!=null&&built.record.junction()!=null&&built.record.junction().get().arm()>=0&&built.record.junction().spec().arms().get(built.record.junction().get().arm()).signEnabled(k.slot())){var deps=new HashSet<UUID>();deps.add(k.road());var next=JunctionSignRoads.derive(data,built,old,k.slot(),deps);if(blocked(built,next,deps))next=next.layout(next.mode(),next.target(),next.branches(),"标牌安装空间与其他道路净空冲突",next.entry());depend(k,deps);put(next);}
          }else if(!SmartSigns.freestanding(old.mode()))reconcile(k);
        }return true;}cleanup=changed.iterator();phase=5;return true;}
      if(cleanup.hasNext()){UUID id=cleanup.next();var b=known.get(id);for(Key k:new ArrayList<>(keysByRoad.getOrDefault(id,Set.of())))if(!compatible(b,states.get(k)))retire(k);return true;}
      return false;
    }
    void index(UUID id){
      affected.addAll(dependencies.getOrDefault(id,Set.of()));
      for(var key:dependencies.getOrDefault(id,Set.of())){var s=states.get(key);if(s!=null&&s.mode()==Mode.ROADSIDE)rampDirty.add(key.road());}
      var old=known.get(id);var now=data.index.roads.get(id);
      for(var b:Arrays.asList(old,now))if(b!=null){for(long chunk:b.chunks)for(Key key:supportChunks.getOrDefault(chunk,Set.of()))rampDirty.add(key.road());if(b.record.assembly()!=null)groupDirty.add(b.record.assembly());rampDirty.addAll(hosts.getOrDefault(id,Set.of()));if(LaneTopology.metadata(b.record).link()!=null)rampDirty.add(id);
        for(V p:List.of(b.mesh.first().center(),b.mesh.last().center()))for(UUID adjacent:ports.getOrDefault(port(p),Set.of()))affected.addAll(dependencies.getOrDefault(adjacent,Set.of()));}
      for(Key key:keysByRoad.getOrDefault(id,Set.of()))presentCache.remove(key);
      if(old!=null){remove(ports,port(old.mesh.first().center()),id);remove(ports,port(old.mesh.last().center()),id);if(old.record.assembly()!=null)groups.getOrDefault(old.record.assembly(),new HashSet<>()).remove(id);var link=LaneTopology.metadata(old.record).link();if(link!=null&&link.from().road()!=null)hosts.getOrDefault(link.from().road(),new HashSet<>()).remove(id);}
      if(now==null){known.remove(id);for(Key k:new ArrayList<>(keysByRoad.getOrDefault(id,Set.of())))removeState(k);versions.remove(id);publish.add(id);return;}
      for(var key:new ArrayList<>(keysByRoad.getOrDefault(id,Set.of())))if(!compatible(now,states.get(key)))retire(key);
      known.put(id,now);for(V p:List.of(now.mesh.first().center(),now.mesh.last().center()))ports.computeIfAbsent(port(p),k->new HashSet<>()).add(id);
      if(now.record.assembly()!=null)groups.computeIfAbsent(now.record.assembly(),k->new HashSet<>()).add(id);
      var link=LaneTopology.metadata(now.record).link();if(link!=null&&link.from().road()!=null)hosts.computeIfAbsent(link.from().road(),k->new HashSet<>()).add(id);
      if(now.record.junction()!=null&&now.record.junction().get().arm()>=0){
        int arm=now.record.junction().get().arm();boolean incoming=now.record.junction().spec().arms().get(arm).incoming()>0;
        for(int slot=0;slot<2;slot++){var key=new Key(id,slot,1);var oldState=states.getOrDefault(key,State.empty(key));
          if(incoming&&now.record.junction().spec().arms().get(arm).signEnabled(slot)){var deps=new HashSet<UUID>();deps.add(id);var next=JunctionSignRoads.derive(data,now,oldState,slot,deps);
            if(blocked(now,next,deps))next=next.layout(next.mode(),next.target(),next.branches(),"标牌安装空间与其他道路净空冲突",next.entry());
            depend(key,deps);put(next);
          }else if(SmartSigns.freestanding(oldState.mode()))put(oldState.supported(null));
        }
      }
      if(main(now))for(int slot:RoadGantry.slots(now.mesh)){
        var station=RoadGantry.station(now.mesh,slot);if(station.kind()==RoadInfrastructure.Gantry.OFF)continue;
        var section=LaneSections.live(now.mesh,station.distance());for(int flow:new int[]{1,-1})if(section.count(flow)>0){var key=new Key(id,slot,flow);if(!states.containsKey(key))addState(State.empty(key));affected.add(key);}
      }
    }
  }
  private void forget(Key k){var old=searched.remove(k);if(old!=null)for(UUID id:old){var set=dependencies.get(id);if(set!=null){set.remove(k);if(set.isEmpty())dependencies.remove(id);}}}
  private void depend(Key k,Set<UUID> ids){forget(k);searched.put(k,Set.copyOf(ids));for(UUID id:ids)dependencies.computeIfAbsent(id,v->new HashSet<>()).add(k);}
  private void upstreamKeys(UUID id,Set<Key> out){for(int flow:new int[]{-1,1}){var b=known.get(id);if(!main(b))continue;var p=new Position(id,flow,flow>0?b.mesh.length():0);double distance=0;var seen=new HashSet<UUID>();while(p!=null&&seen.size()<MAX_VISITS&&distance<=MAX_DISTANCE&&seen.add(p.road())){var at=known.get(p.road());for(int slot:RoadGantry.slots(at.mesh))out.add(new Key(p.road(),slot,p.flow()));distance+=at.mesh.length();try{p=continuation(p,false);}catch(IllegalArgumentException e){break;}}}}
  private Position continuation(Position p,boolean forward){var b=known.get(p.road());if(!main(b))return null;boolean end=(p.flow()>0)==forward;var edge=end?b.mesh.last():b.mesh.first();V heading=edge.left().left().mul(-p.flow());var options=new ArrayList<Position>();
    for(UUID id:ports.getOrDefault(port(edge.center()),Set.of())){var other=known.get(id);if(id.equals(p.road())||!main(other)||other.record.settings().options().leftTraffic()!=b.record.settings().options().leftTraffic())continue;
      for(boolean first:new boolean[]{true,false}){var at=first?other.mesh.first():other.mesh.last();if(at.center().distance(edge.center())>1e-4)continue;int flow=(first==forward)?1:-1;if(at.left().left().mul(-flow).dot(heading)<.95||LaneSections.live(other.mesh,at.distance()).count(flow)==0)continue;options.add(new Position(id,flow,at.distance()));}}
    if(options.size()>1)throw new IllegalArgumentException("主线续接存在歧义，请检查连接关系");return options.isEmpty()?null:options.get(0);
  }
  private Found forward(Key k,boolean assemblyOnly){
    if(work!=null&&work.phase>=4)return (assemblyOnly?work.assemblyQueries:work.anyQueries).computeIfAbsent(k,key->findForward(key,assemblyOnly));
    return findForward(k,assemblyOnly);
  }
  private Found findForward(Key k,boolean assemblyOnly){searches++;var b=known.get(k.road());var seen=new LinkedHashSet<UUID>();if(!main(b)||!RoadGantry.exists(b.mesh,k.slot()))return new Found(null,0,"龙门架锚点失效",seen);var p=new Position(k.road(),k.flow(),RoadGantry.station(b.mesh,k.slot()).distance());double distance=0;
    try{while(p!=null&&seen.size()<MAX_VISITS&&distance<=MAX_DISTANCE&&seen.add(p.road())){visited++;var at=known.get(p.road());var current=p;var found=exits.getOrDefault(p.road(),List.of()).stream().filter(e->e.flow()==current.flow()&&(e.station()-current.station())*current.flow()>1e-5&&(!assemblyOnly||generated(e.assembly()))).sorted(Comparator.comparingDouble((Exit e)->(e.station()-current.station())*current.flow()).thenComparing(Exit::id)).toList();
      if(!found.isEmpty()){var e=found.get(0);if(found.size()>1&&Math.abs(found.get(1).station()-e.station())<.01&&(e.assembly()==null||!Objects.equals(found.get(1).assembly(),e.assembly())))return new Found(null,distance,"同站位有多个出口，无法唯一确定目标",seen);return new Found(e,distance+(e.station()-p.station())*p.flow(),"",seen);}
      distance+=p.flow()>0?at.mesh.length()-p.station():p.station();p=continuation(p,true);
    }}catch(IllegalArgumentException e){return new Found(null,distance,e.getMessage(),seen);}
    return new Found(null,distance,p!=null?"搜索达到 "+(int)MAX_DISTANCE+" 格／"+MAX_VISITS+" 段上限":assemblyOnly?"未找到前方连通立交；自由匝道缺少完整立交关联":"未找到前方分流出口",seen);
  }
  private record Closest(Key key,Set<UUID> dependencies){}
  private Key nearest(Exit exit,Set<UUID> deps){
    UUID id=target(exit);if(work!=null&&work.owners.containsKey(id)){var found=work.owners.get(id);deps.addAll(found.dependencies());return found.key();}
    var read=new HashSet<UUID>();Key key=findNearest(exit,read);deps.addAll(read);if(work!=null)work.owners.put(id,new Closest(key,Set.copyOf(read)));return key;
  }
  private Key findNearest(Exit exit,Set<UUID> deps){Position p=new Position(exit.host(),exit.flow(),exit.station());double distance=0;var seen=new HashSet<UUID>();
    while(p!=null&&distance<=MAX_DISTANCE&&seen.size()<MAX_VISITS&&seen.add(p.road())){deps.add(p.road());var b=known.get(p.road());final var pos=p;var candidates=new ArrayList<Key>();for(int slot:RoadGantry.slots(b.mesh)){var key=new Key(p.road(),slot,p.flow());var st=RoadGantry.station(b.mesh,slot);if((p.station()-st.distance())*p.flow()>1e-5&&isPresent(key,b,st)&&(states.getOrDefault(key,State.empty(key)).mode()==Mode.NORMAL||states.getOrDefault(key,State.empty(key)).mode()==Mode.EXIT_AUTO||states.getOrDefault(key,State.empty(key)).mode()==Mode.EXIT_SIGN))candidates.add(key);}
      candidates.sort(Comparator.comparingDouble(k->(pos.station()-RoadGantry.station(b.mesh,k.slot()).distance())*pos.flow()));
      for(Key k:candidates){var first=forward(k,false);deps.addAll(first.visited());if(first.exit()!=null&&target(first.exit()).equals(target(exit)))return k;}
      distance+=p.flow()>0?p.station():b.mesh.length()-p.station();p=continuation(p,false);
    }return null;
  }
  public static boolean present(RoadIndex.Built b,RoadGantry.Station st){if(st.kind()==RoadInfrastructure.Gantry.OFF)return false;V center=st.sample().center();double beam=RoadGantry.lowerBeamY(st);return b.record.structures().stream().anyMatch(p->!p.pier()&&(p.material()==RoadStructures.Material.STEEL||p.material()==RoadStructures.Material.GANTRY_FRAME)&&p.a().sub(p.b()).horizontalLength()>st.sample().halfWidth()*1.8&&Math.abs(p.a().y()-beam)<.2&&Math.abs(p.a().add(p.b()).mul(.5).sub(center).dot(st.sample().left().left()))<.2);}
  private boolean isPresent(Key k,RoadIndex.Built b,RoadGantry.Station st){
    if(st.kind()==RoadInfrastructure.Gantry.OFF)return false;
    var cached=presentCache.get(k);if(cached!=null&&cached.road()==b)return cached.value();
    boolean result=present(b,st);presentCache.put(k,new Presence(b,result));return result;
  }
  private void reconcile(Key k){var old=states.get(k);var b=known.get(k.road());if(!main(b)||!RoadGantry.exists(b.mesh,k.slot()))return;State next;Mode inactive=old.mode()==Mode.EXIT_AUTO?Mode.NORMAL:old.mode();var deps=new HashSet<UUID>();deps.add(k.road());
    try{var station=RoadGantry.station(b.mesh,k.slot());
      if(old.mode()==Mode.EMPTY)next=old.layout(Mode.EMPTY,null,List.of(),"");
      else if(!isPresent(k,b,station))next=old.layout(inactive,null,List.of(),"此架位未建成或安装空间受阻");
      else if(old.mode()==Mode.SPEED_LIMIT||old.mode()==Mode.LANE_INDICATOR){var lanes=LaneSections.live(b.mesh,station.distance()).lanes().stream().filter(l->l.sign()==k.flow()).map(l->new Branch("lane:"+l.index(),List.of(l.index()),0,true,List.<V>of())).toList();next=old.layout(old.mode(),new Key(source(k.road()),k.slot(),k.flow()).id(),lanes,lanes.isEmpty()?"该方向没有可服务的车道":"");}
      else {var lookup=forward(k,old.mode()==Mode.IC_JCT);deps.addAll(lookup.visited());
        if(lookup.exit()==null)next=old.layout(inactive,null,List.of(),lookup.reason());
        else if(old.mode()==Mode.IC_JCT){var e=lookup.exit();var branches=diagram(e,k,deps);next=old.layout(Mode.IC_JCT,e.assembly(),branches,branches.size()<2?"缺少完整且可达的立交关联":"",source(e.host())+":"+e.flow());}
        else if(old.mode()==Mode.EXIT_SIGN){var e=lookup.exit();next=old.layout(Mode.EXIT_SIGN,target(e),exitBranches(e,k),"");}
        else {var e=lookup.exit();Key closest=nearest(e,deps);next=k.equals(closest)?old.layout(Mode.EXIT_AUTO,target(e),exitBranches(e,k),""):old.layout(inactive,null,List.of(),closest==null?"未找到可用的上游龙门架":"由更近的上游龙门架预告");}
      }
    }catch(IllegalArgumentException ex){next=old.layout(inactive,null,List.of(),ex.getMessage());}
    if(next.target()!=null&&next.diagnostic().isEmpty()&&blocked(b,next,deps))next=old.layout(inactive,null,List.of(),"标牌安装空间与其他道路净空冲突");
    depend(k,deps);put(next);
  }
  private boolean blocked(RoadIndex.Built built,State next,Set<UUID> deps){
      var layout=SmartSigns.layout(built.mesh,next);boolean blocked=false;
      var nearby=new HashSet<UUID>();
      var mounts=SmartSigns.mountParts(built.mesh,next);
      for(var part:mounts){var base=part.base();
        for(int x=(int)Math.floor(base.stream().mapToDouble(V::x).min().orElseThrow()-.1)>>4;x<=(int)Math.floor(base.stream().mapToDouble(V::x).max().orElseThrow()+.1)>>4;x++)
          for(int z=(int)Math.floor(base.stream().mapToDouble(V::z).min().orElseThrow()-.1)>>4;z<=(int)Math.floor(base.stream().mapToDouble(V::z).max().orElseThrow()+.1)>>4;z++)nearby.addAll(data.index.inChunk(net.minecraft.world.level.ChunkPos.asLong(x,z)));
      }
      for(var board:layout.boards()){
        V a=layout.at(board.x()-board.width()/2,0,.1),z=layout.at(board.x()+board.width()/2,0,.45);
        for(int x=(int)Math.floor(Math.min(a.x(),z.x())-.5)>>4;x<=(int)Math.floor(Math.max(a.x(),z.x())+.5)>>4;x++)
          for(int y=(int)Math.floor(Math.min(a.z(),z.z())-.5)>>4;y<=(int)Math.floor(Math.max(a.z(),z.z())+.5)>>4;y++)nearby.addAll(data.index.inChunk(net.minecraft.world.level.ChunkPos.asLong(x,y)));
      }
      for(UUID id:nearby){var other=data.index.roads.get(id);if(other==null)continue;deps.add(id);
        if(!mounts.isEmpty()){var solids=new RoadSolidOverlap.Index(other.record.structures());for(var part:mounts)if(solids.intersects(part)&&other.record.structures().stream().anyMatch(p->!ForkSignPlacement.mountingContact(part,p,next.support())&&RoadSolidOverlap.intersects(part,p)))return true;}
        if(id.equals(built.record.id()))continue;
        for(var board:layout.boards())for(double x=board.x()-board.width()/2;x<=board.x()+board.width()/2+.01&&!blocked;x+=.7)for(double depth:new double[]{.1,.45}){
          V point=layout.at(x,board.bottom(),depth);var q=RoadQueries.horizontal(other.mesh,point);double deck=q.sample().center().y();
          if(point.y()>deck+4.5||point.y()+board.height()<deck-other.mesh.settings().thickness())continue;
          if(RoadQueries.contains(other.mesh,new V(point.x(),deck,point.z()),.05,.01))blocked=true;
        }
        if(blocked)break;
      }
      return blocked;
  }
  private void put(State next){var old=states.get(next.key());addState(next);if(!Objects.equals(old,next)){layoutChanges++;versions.merge(next.key().road(),1L,Long::sum);data.setDirty();publish.add(next.key().road());}}
  private UUID source(UUID id){var r=known.get(id);return r==null||r.record.fragment()==null?id:r.record.fragment().source();}
  private UUID target(Exit e){var host=known.get(e.host());double origin=host==null||host.record.fragment()==null?0:host.record.fragment().origin();return e.assembly()==null?e.id():UUID.nameUUIDFromBytes((e.assembly()+":"+source(e.host())+":"+e.flow()+":"+Math.round((origin+e.station())*10)).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
  private List<Branch> exitBranches(Exit e,Key k){
    var bundle=exits.getOrDefault(e.host(),List.of()).stream().filter(v->target(v).equals(target(e))).toList();
    if(bundle.stream().map(Exit::side).distinct().count()>1)throw new IllegalArgumentException("同位分流涉及两侧，请使用 IC/JCT 结构预告");
    var branchLanes=new HashSet<Integer>();for(var exit:bundle)branchLanes.addAll(exit.lanes());
    var b=known.get(k.road());var live=LaneSections.live(b.mesh,RoadGantry.station(b.mesh,k.slot()).distance()).lanes().stream().filter(l->l.sign()==k.flow()).map(LanePoints.Lane::index).toList();var targetRoad=known.get(e.host());var targetLanes=LaneSections.live(targetRoad.mesh,e.station()).lanes().stream().filter(l->l.sign()==e.flow()).sorted(Comparator.comparingDouble(l->l.position().sub(RoadStructures.sample(targetRoad.mesh,e.station()).center()).dot(RoadStructures.sample(targetRoad.mesh,e.station()).left())*e.flow())).toList();
    var sourceLanes=LaneSections.live(b.mesh,RoadGantry.station(b.mesh,k.slot()).distance()).lanes().stream().filter(l->l.sign()==k.flow()).sorted(Comparator.comparingDouble(l->l.position().sub(RoadGantry.station(b.mesh,k.slot()).sample().center()).dot(RoadGantry.station(b.mesh,k.slot()).sample().left())*k.flow())).toList();
    // Track ranks from the exit-side edge. An added/departed lane between the
    // gantry and the exit does not make every remaining destination ambiguous.
    var lane=SmartSigns.exitLaneMapping(sourceLanes.stream().map(LanePoints.Lane::index).toList(),targetLanes.stream().map(LanePoints.Lane::index).toList(),branchLanes,e.side());
    if(lane.isEmpty())throw new IllegalArgumentException("出口车道与本架服务车道无法关联");var through=new ArrayList<>(live);through.removeAll(lane);var out=new ArrayList<Branch>();if(!through.isEmpty())out.add(new Branch("through",through,0,true,List.of()));out.add(new Branch(target(e).toString(),lane,e.side(),e.dedicated(),List.of()));return out;}
  private List<Exit> derive(RoadIndex.Built ramp){var r=ramp.record;var link=LaneTopology.metadata(r).link();if(link!=null){var host=known.get(link.from().road());if(host==null||host.record.junction()!=null||host.record.toll()!=null)return List.of();try{var point=LaneTopology.point(host.record,link.from().point());var lane=LanePoints.lane(host.mesh,point);double station=lane.station();for(var cut:LaneTopology.metadata(host.record).cuts())if(cut.connection().equals(r.id())&&!cut.arrival())station=cut.begin();int side=departureSide(host.mesh,ramp.mesh,station,lane.sign(),lane.index());return List.of(new Exit(r.id(),host.record.id(),lane.sign(),station,List.of(lane.index()),side,link.options().departure()==LanePoints.Departure.DETACH||link.options().departure()==LanePoints.Departure.TEMPORARY,r.assembly(),path(ramp.mesh)));}catch(IllegalArgumentException e){return List.of();}}
    if(r.assembly()==null||!r.settings().style().ramp())return List.of();
    var descriptor=data.interchanges.get(r.assembly());
    var topology=new HashMap<UUID,CompoundTag>();
    if(descriptor!=null)for(Tag value:descriptor.getList("SignTopology",Tag.TAG_COMPOUND)){var leg=(CompoundTag)value;if(leg.hasUUID("Road"))topology.put(leg.getUUID("Road"),leg);}
    var source=topology.get(r.id());
    // In a hybrid/cloverleaf a weaving collector goes straight along one axis.
    // Its taper can change lateral offset, so geometry alone classifies it as
    // another exit beside the loop which actually turns off its downstream end.
    if(isWeavingCollector(descriptor,source))return List.of();
    if(source!=null&&source.getInt("From")<0&&(descriptor==null||!descriptor.contains("Corridor"))
        &&!(source.getInt("To")>=0&&descriptor!=null&&descriptor.getCompound("Options").getString("Preset").equals("DOUBLE_TRUMPET"))){
      // The four outgoing ring ramps start on the closed ring, not a main road.
      if(source.getInt("To")<0||descriptor==null||!descriptor.getCompound("Options").getString("Preset").equals("ROUNDABOUT"))return List.of();
      var matches=new ArrayList<Exit>();
      for(UUID id:groups.getOrDefault(r.assembly(),Set.of())){var ring=known.get(id);if(ring==null||!ring.mesh.closed())continue;
        var q=RoadQueries.horizontal(ring.mesh,ramp.mesh.first().center());
        if(q.sample().center().distance(ramp.mesh.first().center())>.4)continue;
        var lanes=LaneSections.live(ring.mesh,q.sample().distance()).lanes();
        var lane=lanes.stream().filter(l->l.direction().dot(ramp.mesh.first().left().left().mul(-1))>.9).findFirst().orElse(null);if(lane==null)continue;
        matches.add(new Exit(r.id(),id,lane.sign(),q.sample().distance(),List.of(lane.index()),departureSide(ring.mesh,ramp.mesh,q.sample().distance(),lane.sign(),lane.index()),false,r.assembly(),path(ramp.mesh)));
      }
      return matches.size()==1?matches:List.of();
    }
    // A split after a saved shared feeder belongs to that one physical mouth.
    // Collector-to-loop departures have a real destination and remain independent.
    if(source!=null&&(descriptor==null||!descriptor.contains("Corridor")))for(var item:topology.entrySet()){var parent=item.getValue();var built=known.get(item.getKey());
      if(built!=null&&!item.getKey().equals(r.id())&&parent.getInt("To")==-1&&parent.getInt("From")==source.getInt("From")
          &&built.mesh.last().center().distance(ramp.mesh.first().center())<.35&&built.mesh.last().left().dot(ramp.mesh.first().left())>.9)return List.of();}
    var matches=new ArrayList<Exit>();
    for(UUID id:groups.getOrDefault(r.assembly(),Set.of())){
      var host=known.get(id);if(!main(host))continue;var q=RoadQueries.horizontal(host.mesh,ramp.mesh.first().center());double d=q.sample().distance();
      if(!ForkSignPlacement.onPath(host.mesh,ramp.mesh.first().center(),.35))continue;
      var mainLeg=topology.get(id);boolean sameApproach=descriptor!=null&&descriptor.contains("Corridor")||source!=null&&mainLeg!=null&&(source.getInt("From")==mainLeg.getInt("From")||source.getInt("From")==mainLeg.getInt("To"));
      // Ordinary-road collectors lie outside the lane-centre envelope while
      // sharing their inner pavement edge with the host. Saved approach + actual
      // touching pavement is required; unrelated nearby ramps cannot claim a host.
      double reach=q.sample().halfWidth()+.1+(sameApproach?ramp.mesh.first().halfWidth():0);
      if(Math.abs(q.lateral())>reach||Math.abs(q.sample().center().y()-ramp.mesh.first().center().y())>.3)continue;
      var end=RoadQueries.horizontal(host.mesh,ramp.mesh.last().center());
      boolean parallel=Math.abs(end.lateral()-q.lateral())<.01&&Math.abs(end.sample().center().y()-ramp.mesh.last().center().y())<.3
          &&ramp.mesh.first().left().dot(ramp.mesh.last().left())>.9999
          &&ramp.mesh.last().center().sub(ramp.mesh.first().center()).horizontalUnit().dot(ramp.mesh.first().left().left().mul(-1))>.9999;
      if(parallel)continue; // the loop at its end is the actual exit
      var lanes=LaneSections.live(host.mesh,d).lanes();var lane=lanes.stream().filter(l->l.direction().dot(ramp.mesh.first().left().left().mul(-1))>.90).min(Comparator.comparingDouble(l->l.position().distance(ramp.mesh.first().center()))).orElse(null);if(lane==null)continue;
      var connected=lanes.stream().filter(l->l.sign()==lane.sign()&&l.position().sub(ramp.mesh.first().center()).horizontalLength()<=ramp.mesh.first().halfWidth()+l.width()*.45).map(LanePoints.Lane::index).toList();
      // Generated auxiliary tapers begin on the shoulder, before their lane
      // overlaps a mainline lane centre. The saved approach identifies the
      // shared source lane; proximity alone must not invent this connection.
      // ContinuousRoads retains an existing road's A/B orientation, which
      // can oppose the planner leg's From/To. Membership comes from saved
      // approaches; travel direction comes from the tangent check above.
      if(connected.isEmpty()&&sameApproach)connected=List.of(lane.index());
      matches.add(new Exit(r.id(),id,lane.sign(),d,connected,departureSide(host.mesh,ramp.mesh,d,lane.sign(),lane.index()),false,r.assembly(),path(ramp.mesh)));
    }
    return matches.size()==1?matches:List.of();
  }
  private static boolean isWeavingCollector(CompoundTag descriptor,CompoundTag leg){
    if(descriptor==null||leg==null||!leg.getBoolean("Ramp"))return false;
    var preset=descriptor.getCompound("Options").getString("Preset");
    int from=leg.getInt("From"),to=leg.getInt("To");
    return (preset.equals("HYBRID")||preset.equals("CLOVERLEAF"))&&from>=0&&from<4&&to==(from+2)%4;
  }
  private boolean generated(UUID id){if(id==null)return false;var descriptor=data.interchanges.get(id);if(descriptor==null||descriptor.contains("Corridor"))return false;int n=descriptor.getList("Nodes",Tag.TAG_COMPOUND).size();return n>=3&&n<=6;}
  private boolean throughBeyond(RoadIndex.Built host,LanePoints.Link link){
    var point=LaneTopology.point(host.record,link.from().point());var lane=LanePoints.lane(host.mesh,point);
    double remaining=lane.sign()>0?host.mesh.length()-lane.station():lane.station();
    if(remaining>.05){
      double station=lane.station()+lane.sign()*Math.min(.5,remaining);
      return LaneSections.live(host.mesh,station).lanes().stream().anyMatch(l->l.index()==lane.index()&&l.sign()==lane.sign());
    }
    // A marker at a segment boundary can still have a real, direction-compatible
    // continuation. Do not erase that destination merely because a record ends.
    try{return continuation(new Position(host.record.id(),lane.sign(),lane.station()),true)!=null;}
    catch(IllegalArgumentException ambiguous){return false;}
  }
  /** One sign before the split. Two destinations share the same support when
   * the outside through lane was added for this direction. */
  private void elevatedSign(RoadIndex.Built connector){
    UUID id=connector.record.id();
    var receipt=data.interchanges.get(id);
    var host=data.index.roads.get(receipt.getUUID("Main"));
    var key=new Key(id,0,1);
    var old=peek(key);
    if(host==null){retire(key);return;}
    var access=LaneTopology.metadata(host.record).access().stream().filter(a->a.connection().equals(id)).findFirst().orElse(null);
    if(access==null){retire(key);return;}
    var exit=access.cuts().stream().filter(c->!c.arrival()).findFirst().orElse(null);
    if(exit==null){retire(key);return;} // An exit-only elevated road cannot be entered here.
    var branches=ElevatedSigns.destinations(host.mesh,access,exit.sign());
    var mount=ElevatedSigns.saved(host.mesh,access,host.record.structures());
    var deps=new HashSet<UUID>();deps.add(id);deps.add(host.record.id());
    for(var tag:receipt.getList("Hosts",Tag.TAG_INT_ARRAY)){
      UUID member=net.minecraft.nbt.NbtUtils.loadUUID(tag);
      if(!deps.add(member)||mount!=null)continue;
      var next=known.get(member);if(next==null)continue;
      var local=LaneTopology.metadata(next.record).access().stream().filter(a->a.connection().equals(id)).findFirst().orElse(null);
      if(local!=null)mount=ElevatedSigns.saved(next.mesh,local,next.record.structures());
    }
    var state=old.layout(Mode.ROADSIDE,id,branches,mount==null?"请编辑并保存出入口以设置路牌基座":"").supported(mount);
    if(mount!=null&&blocked(connector,state,deps))state=state.layout(Mode.ROADSIDE,id,branches,"高架入口标牌与其他道路空间冲突").supported(null);
    if(old.branches().isEmpty()){
      state=state.edit("elevated/main",new Text("高架",1,true));
      if(branches.size()==2)state=state.edit("surface/main",new Text("地面辅路",1,true));
    }
    put(state);depend(key,deps);
  }
  private void roadside(RoadIndex.Built ramp,List<Exit> derived){
    var key=new Key(ramp.record.id(),0,1);var old=states.getOrDefault(key,State.empty(key));
    var shared=sharedForkChildren(ramp);
    // Slot zero retains the existing internal fork and all its hand-written text.
    // The mainline departure is a separate physical mouth with its own saved sign.
    if(shared.size()==2&&derived.size()==1)sharedDeparture(ramp,derived.get(0));
    else {var departure=new Key(ramp.record.id(),1,1);if(states.containsKey(departure)&&states.get(departure).mode()==Mode.ROADSIDE)retire(departure);}
    var descriptor=data.interchanges.get(ramp.record.assembly());
    if(descriptor!=null&&!descriptor.contains("Corridor")&&descriptor.getList("Nodes",Tag.TAG_COMPOUND).size()>=5
        &&multiForks(ramp.record.assembly()).containsKey(ramp.record.id().toString())){multiRoadside(ramp,old);return;}
    if(derived.size()!=1){if(old.mode()==Mode.ROADSIDE){put(old.layout(Mode.ROADSIDE,null,List.of(),"分流关联已失效").supported(null));}return;}
    var e=derived.get(0);var host=known.get(e.host());if(host==null)return;
    var link=LaneTopology.metadata(ramp.record).link();
    // BRANCH preserves a through movement only where that lane really continues.
    // An endpoint departure without a connected successor has just the ramp.
    boolean paired=link!=null&&link.options().departure()==LanePoints.Departure.BRANCH&&throughBeyond(host,link);
    var terminal=terminalFork(ramp);
    if(terminal.size()==2&&!terminal.get(0).record.id().equals(ramp.record.id())){
      if(old.mode()==Mode.ROADSIDE)put(old.layout(Mode.ROADSIDE,ramp.record.id(),List.of(),"已合并到同一分叉路牌").supported(null));
      return;
    }
    var split=terminal.size()==2?terminal:shared;
    var rails=java.util.stream.Stream.concat(ramp.record.structures().stream(),host.record.structures().stream()).toList();
    Support mount=split.size()==2?sharedForkMount(ramp,split,terminal.size()==2?ramp.mesh.first():ramp.mesh.last()):link!=null
        ?ForkSignPlacement.findConnected(ramp.mesh,host.mesh,rails,new ForkSignPlacement.Mouth(ramp.record.id(),link.options().departure(),e.lanes().get(0),e.flow(),e.station()),paired)
        :ForkSignPlacement.find(ramp.mesh,host.mesh,rails,e.flow(),paired);
    // A generated exit can have a parallel feeder before its actual fork leg.
    if(mount==null&&split.size()!=2&&generated(e.assembly())){
      var queue=new ArrayDeque<UUID>();var seen=new HashSet<UUID>();queue.add(ramp.record.id());
      while(mount==null&&!queue.isEmpty()&&seen.size()<16){var id=queue.remove();if(!seen.add(id))continue;var current=known.get(id);if(current==null)continue;
        if(!id.equals(ramp.record.id()))mount=ForkSignPlacement.find(current.mesh,host.mesh,current.record.structures(),e.flow(),false);
        for(UUID next:new TreeSet<>(groups.getOrDefault(e.assembly(),Set.of()))){var n=known.get(next);if(n!=null&&!main(n)&&!seen.contains(next)&&current.mesh.last().center().distance(n.mesh.first().center())<.35)queue.add(next);}
      }
    }
    var preferred=split.size()==2?null:preferredNose(ramp,host,e,link,paired);
    var nose=preferred!=null?preferred:mount;mount=outsideRoof(mount,ramp,host);

    var branches=new ArrayList<Branch>();
    if(paired)branches.add(new Branch("through",List.of(),0,true,List.of()));
    var movements=generated(e.assembly())?roadsideMovements(e):List.<Branch>of();
    if(terminal.size()==2){
      var combined=new TreeMap<String,Branch>();
      for(var child:terminal)for(var exit:derive(child))for(var branch:roadsideMovements(exit))combined.put(branch.id(),branch);
      movements=combined.values().stream().sorted(Comparator.comparingInt(Branch::side).thenComparing(Branch::id)).toList();
    }
    if(movements.size()>1)branches.addAll(movements);
    else branches.add(new Branch("ramp",List.of(),e.side(),true,List.of()));
    if(mount!=null&&branches.size()>1)mount=new Support(mount.foot(),mount.right(),mount.front(),true);
    var next=old.layout(Mode.ROADSIDE,ramp.record.id(),branches,mount==null?"未找到可安装的分流鼻端护栏":"").supported(mount);
    var deps=new HashSet<UUID>();deps.add(e.host());deps.add(ramp.record.id());for(var child:split)deps.add(child.record.id());
    if(mount!=null&&blocked(ramp,next,deps))next=old.layout(Mode.ROADSIDE,ramp.record.id(),branches,"标牌安装空间与其他道路净空冲突").supported(mount);
    if(preferredBlocked(ramp,host,preferred,mount,next,deps))next=next.layout(Mode.ROADSIDE,ramp.record.id(),branches,"原鼻端净空不足，需提前设置告示牌");
    // A cycle underpass can remove the entire nose seat. The saved exit is
    // still real, so try an actual upstream rail even without a surviving nose.
    if((split.size()!=2||terminal.size()==2)&&!next.diagnostic().isEmpty())next=approachFallback(ramp,host,e,nose,next,deps);
    // Migrate only the former single-direction layout. Later intentional text
    // deletion must not resurrect archived labels on refresh or world reload.
    if(terminal.size()==2&&(old.branches().isEmpty()||old.branches().stream().anyMatch(b->b.id().equals("ramp"))))next=terminalText(next,terminal);
    put(next);depend(key,deps);
  }
  private static Support outsideRoof(Support mount,RoadIndex.Built... roads){
    if(mount==null)return null;
    for(var road:roads){
      var q=RoadQueries.horizontal(road.mesh,mount.foot());double d=q.sample().distance();
      if(q.horizontalDistance()>q.sample().halfWidth()+RoadAutoTunnels.LINING+1)continue;
      if(road.mesh.settings().structure()==Structure.TUNNEL||RoadAutoTunnels.regions(road.record.structures()).stream().anyMatch(r->d>=r.from()-.1&&d<=r.to()+.1))return null;
    }
    return mount;
  }
  /** Obstacle-clipped rails must not redefine the physical exit as a later
   * surviving rail. Recover the nominal boundary with the existing road rail
   * planner solely for clearance testing; never install on an invented seat. */
  private Support preferredNose(RoadIndex.Built ramp,RoadIndex.Built host,Exit exit,LanePoints.Link link,boolean paired){
    var rails=new ArrayList<RoadStructures.Part>();
    for(var owner:List.of(ramp,host)){
      var join=new RoadRailJoin(owner.mesh,known.values().stream().filter(r->!r.record.id().equals(owner.record.id())&&RoadIndex.overlapXZ(owner.mesh,r.mesh,4))
          .map(r->new RoadRailJoin.Neighbor(r.mesh,RoadSurface.higherPriority(r.record.id(),r.mesh,owner.record.id(),owner.mesh))).toList());
      rails.addAll(RoadStructures.roadsideSupports(owner.mesh,new RoadStructures.Ground(){
        public double top(double x,double z,double y){return Double.NaN;}
        public boolean joined(V p){return false;}public boolean blocked(RoadStructures.Part p){return false;}
        public boolean unionRails(){return true;}
        public List<RoadRailJoin.Span> railSpans(V a,V b,V offset,double inset){return join.exposed(a,b,offset,inset);}
      }));
      rails.addAll(owner.record.structures().stream().filter(p->p.material()==RoadStructures.Material.GREEN||p.material()==RoadStructures.Material.SOIL||p.material()==RoadStructures.Material.CONCRETE).toList());
    }
    return link==null?ForkSignPlacement.find(ramp.mesh,host.mesh,rails,exit.flow(),paired):ForkSignPlacement.findConnected(ramp.mesh,host.mesh,rails,
        new ForkSignPlacement.Mouth(ramp.record.id(),link.options().departure(),exit.lanes().get(0),exit.flow(),exit.station()),paired);
  }
  private boolean preferredBlocked(RoadIndex.Built ramp,RoadIndex.Built host,Support preferred,Support actual,State state,Set<UUID> deps){
    if(preferred==null)return false;
    // A surviving lower/side rail is another seat too, even when its centreline
    // projection is slightly earlier. It must not bypass the blocked nose.
    if(actual!=null&&actual.foot().distance(preferred.foot())<.15)return false;
    var probe=state.layout(state.mode(),state.target(),state.branches(),"").supported(preferred);
    return outsideRoof(preferred,ramp,host)==null||blocked(ramp,probe,deps);
  }
  private void sharedDeparture(RoadIndex.Built ramp,Exit exit){
    var key=new Key(ramp.record.id(),1,1);var old=states.getOrDefault(key,State.empty(key));
    var host=known.get(exit.host());if(host==null)return;
    var rails=java.util.stream.Stream.concat(ramp.record.structures().stream(),host.record.structures().stream()).toList();
    var mount=ForkSignPlacement.find(ramp.mesh,host.mesh,rails,exit.flow(),false);
    var preferred=preferredNose(ramp,host,exit,null,false);
    var nose=preferred!=null?preferred:mount;mount=outsideRoof(mount,ramp,host);
    var branches=List.of(new Branch("ramp",List.of(),exit.side(),true,List.of()));
    var next=old.layout(Mode.ROADSIDE,ramp.record.id(),branches,mount==null?"未找到主线出口鼻端护栏":"").supported(mount);
    var deps=new HashSet<UUID>();deps.add(exit.host());deps.add(ramp.record.id());
    if(mount!=null&&blocked(ramp,next,deps))next=next.layout(Mode.ROADSIDE,ramp.record.id(),branches,"标牌安装空间与其他道路净空冲突");
    if(preferredBlocked(ramp,host,preferred,mount,next,deps))next=next.layout(Mode.ROADSIDE,ramp.record.id(),branches,"原鼻端净空不足，需提前设置告示牌");
    if(!next.diagnostic().isEmpty())next=approachFallback(ramp,host,exit,nose,next,deps);
    put(next);depend(key,deps);
  }
  /** Preserve the same sign identity and text while moving a blocked mainline
   * exit to the approach rail. Internal fork signs keep their own physical fork. */
  private State approachFallback(RoadIndex.Built ramp,RoadIndex.Built host,Exit exit,Support nose,State state,Set<UUID> deps){
    if(!main(host))return state;
    // The saved exit station is the upstream beginning of the taper. The rail
    // beside the open auxiliary lane before the nose may belong to the ramp.
    double split=nose==null?ForkSignPlacement.separator(ramp.mesh,host.mesh):RoadQueries.horizontal(ramp.mesh,nose.foot()).sample().distance();
    if(Double.isFinite(split)&&split>=2&&split<=256){
      for(var mount:ForkSignPlacement.approach(ramp.mesh,ramp.record.structures(),split,1,exit.side(),state.branches().size()>1)){
        var at=RoadQueries.horizontal(host.mesh,mount.foot());
        if((at.sample().distance()-exit.station())*exit.flow()<-.1||at.sample().left().mul(exit.flow()).dot(mount.right())<.8
            ||outsideRoof(mount,ramp,host)==null)continue;
        var candidate=state.layout(state.mode(),state.target(),state.branches(),"").supported(mount);
        if(approachClear(ramp,candidate,deps))return candidate;
      }
    }
    for(var mount:ForkSignPlacement.approach(host.mesh,host.record.structures(),exit.station(),exit.flow(),exit.side(),state.branches().size()>1)){
      if(outsideRoof(mount,host)==null)continue;
      var candidate=state.layout(state.mode(),state.target(),state.branches(),"").supported(mount);
      if(approachClear(ramp,candidate,deps))return candidate;
    }
    return state;
  }
  private boolean approachClear(RoadIndex.Built ramp,State state,Set<UUID> deps){
    var layout=SmartSigns.layout(ramp.mesh,state);var parts=new ArrayList<RoadStructures.Part>();
    var steel=RoadStructures.Material.STEEL;
    V foot=state.support().foot().add(layout.front().mul(-.08));
    parts.add(new RoadStructures.Part(foot,foot,.30,7.87,true,steel));
    for(var board:layout.boards())parts.add(new RoadStructures.Part(
        layout.at(board.x()-board.width()/2,board.bottom(),.05),layout.at(board.x()+board.width()/2,board.bottom(),.05),.8,board.height(),false,steel));
    double lo=Math.min(0,layout.boards().stream().mapToDouble(b->b.x()-b.width()/2+.25).min().orElse(0));
    double hi=Math.max(0,layout.boards().stream().mapToDouble(b->b.x()+b.width()/2-.25).max().orElse(0));
    for(double y:new double[]{1.1,2.1})parts.add(new RoadStructures.Part(layout.at(lo,y-.085,-.22),layout.at(hi,y-.085,-.22),.17,.17,false,steel));
    var nearby=new TreeSet<UUID>();
    for(var part:parts){var bounds=part.base();
      for(int x=(int)Math.floor(bounds.stream().mapToDouble(V::x).min().orElseThrow()-.5)>>4;x<=(int)Math.floor(bounds.stream().mapToDouble(V::x).max().orElseThrow()+.5)>>4;x++)
        for(int z=(int)Math.floor(bounds.stream().mapToDouble(V::z).min().orElseThrow()-.5)>>4;z<=(int)Math.floor(bounds.stream().mapToDouble(V::z).max().orElseThrow()+.5)>>4;z++)nearby.addAll(data.index.inChunk(net.minecraft.world.level.ChunkPos.asLong(x,z)));
    }
    for(UUID id:nearby){var road=data.index.roads.get(id);if(road==null)continue;deps.add(id);
      var solids=new RoadSolidOverlap.Index(road.record.structures());var motor=LaneDeck.motorOnly(road.mesh);
      for(var part:parts)if(RoadClearance.structureInvades(part,motor,4.5)||RoadClearance.structureInvades(part,road.mesh,0)
          ||solids.intersects(part)&&road.record.structures().stream().anyMatch(p->!ForkSignPlacement.mountingContact(part,p,state.support())&&RoadSolidOverlap.intersects(part,p)))return false;
      for(var cycle:RoadsideBypass.travel(road.mesh))for(var part:parts)if(RoadClearance.structureInvades(part,cycle,RoadsideBypass.HEADROOM))return false;
    }
    return true;
  }
  /** The C throat's two coincident departures share a single pole in their own gore. */
  private List<RoadIndex.Built> terminalFork(RoadIndex.Built root){
    var descriptor=data.interchanges.get(root.record.assembly());
    if(descriptor==null||descriptor.contains("Corridor"))return List.of();
    boolean doubleTrumpet=descriptor.getCompound("Options").getString("Preset").equals("DOUBLE_TRUMPET");
    if(!doubleTrumpet&&descriptor.getList("Nodes",Tag.TAG_COMPOUND).size()!=3)return List.of();
    var topology=descriptor.getList("SignTopology",Tag.TAG_COMPOUND);
    var source=topology.stream().map(t->(CompoundTag)t).filter(t->t.hasUUID("Road")&&t.getUUID("Road").equals(root.record.id())).findFirst().orElse(null);
    if(source==null||source.getInt("From")!=(doubleTrumpet?-1:1))return List.of();
    return new TreeSet<>(groups.getOrDefault(root.record.assembly(),Set.of())).stream().map(known::get)
        .filter(r->r!=null&&!main(r)&&r.mesh.first().center().distance(root.mesh.first().center())<.35
            &&r.mesh.first().left().dot(root.mesh.first().left())>.9).toList();
  }
  /** Preserve hand-written labels from both former poles when their semantic destinations merge. */
  private State terminalText(State next,List<RoadIndex.Built> children){
    var valid=SmartSigns.fieldIds(next.branches());
    for(var child:children){
      var prior=states.get(new Key(child.record.id(),0,1));if(prior==null)continue;
      var exits=derive(child);if(exits.size()!=1)continue;
      var movements=roadsideMovements(exits.get(0));if(movements.size()!=1)continue;
      String destination=movements.get(0).id();
      var words=new HashMap<>(prior.drafts().getOrDefault(prior.targetKey(),Map.of()));words.putAll(prior.text());
      for(var entry:words.entrySet()){
        String key=entry.getKey();if(key.startsWith("ramp/"))key=destination+key.substring(4);
        if(valid.contains(key)&&!next.text().containsKey(key))next=next.edit(key,entry.getValue());
      }
    }return next;
  }
  private Map<String,InterchangeSignForks.Fork> multiForks(UUID assembly){
    if(work!=null&&work.forks.containsKey(assembly))return work.forks.get(assembly);
    var legs=new ArrayList<InterchangePlanner.Leg>();
    for(UUID id:new TreeSet<>(groups.getOrDefault(assembly,Set.of()))){var r=known.get(id);if(r!=null&&!main(r))legs.add(new InterchangePlanner.Leg(id.toString(),0,0,r.mesh));}
    var forks=InterchangeSignForks.find(legs);if(work!=null)work.forks.put(assembly,forks);return forks;
  }
  /** Only internal binary departure forks get poles; arrivals and mainline peel-offs do not. */
  private void multiRoadside(RoadIndex.Built ramp,State old){
    var fork=multiForks(ramp.record.assembly()).get(ramp.record.id().toString());
    if(fork==null){if(old.mode()==Mode.ROADSIDE)put(old.layout(Mode.ROADSIDE,ramp.record.id(),List.of(),"此位置不是集散道分叉").supported(null));return;}
    var sibling=known.get(UUID.fromString(fork.sibling()));if(sibling==null)return;
    Support mount=ForkSignPlacement.findBetween(ramp.mesh,sibling.mesh,ramp.record.structures(),1,true);
    if(mount==null)mount=ForkSignPlacement.findBetween(sibling.mesh,ramp.mesh,sibling.record.structures(),1,true);
    mount=outsideRoof(mount,ramp,sibling);
    if(mount!=null)mount=new Support(mount.foot(),fork.right(),fork.right().left(),true);
    var branches=List.of(new Branch("fork:left",List.of(),-1,true,List.of()),new Branch("fork:right",List.of(),1,true,List.of()));
    var next=old.layout(Mode.ROADSIDE,ramp.record.id(),branches,mount==null?"未找到分叉鼻端护栏":"").supported(mount);
    var deps=new HashSet<UUID>();deps.add(ramp.record.id());deps.add(sibling.record.id());
    if(mount!=null&&blocked(ramp,next,deps))next=next.layout(Mode.ROADSIDE,ramp.record.id(),branches,"标牌安装空间与其他道路净空冲突");
    put(next);depend(old.key(),deps);
  }
  /** Find the internal fork downstream of a shared mainline departure. */
  private List<RoadIndex.Built> sharedForkChildren(RoadIndex.Built root){
    var descriptor=data.interchanges.get(root.record.assembly());
    if(descriptor==null||!Set.of("TURBINE","STACK","HYBRID").contains(descriptor.getCompound("Options").getString("Preset")))return List.of();
    return new TreeSet<>(groups.getOrDefault(root.record.assembly(),Set.of())).stream().map(known::get)
        .filter(r->r!=null&&r!=root&&!main(r)&&root.mesh.last().center().distance(r.mesh.first().center())<.35
            &&root.mesh.last().left().dot(r.mesh.first().left())>.9).toList();
  }
  private static Support sharedForkMount(RoadIndex.Built root,List<RoadIndex.Built> children,Sample fork){
    Support best=null;double nearest=Double.POSITIVE_INFINITY;
    for(int i=0;i<2;i++){var child=children.get(i);var sibling=children.get(1-i);
      var support=ForkSignPlacement.findBetween(child.mesh,sibling.mesh,child.record.structures(),1,true);if(support==null)continue;
      double d=support.foot().sub(fork.center()).horizontalLength();
      if(d<nearest){V right=fork.left();best=new Support(support.foot(),right,right.left(),true);nearest=d;}
    }
    return best;
  }
  /** Follow only the roads reachable from this physical mouth. A shared feeder
   * may split into two destinations, whereas another downstream exit is unrelated. */
  private List<Branch> roadsideMovements(Exit entry){
    var host=known.get(entry.host());if(host==null)return List.of();
    var at=RoadStructures.sample(host.mesh,entry.station());V right=at.left().mul(entry.flow());
    var queue=new ArrayDeque<UUID>();var seen=new HashSet<UUID>();var result=new TreeMap<String,Branch>();
    var group=new TreeSet<>(groups.getOrDefault(entry.assembly(),Set.of()));queue.add(entry.id());
    while(!queue.isEmpty()&&seen.size()<128){var id=queue.remove();if(!seen.add(id))continue;var current=known.get(id);if(current==null)continue;
      var tail=current.mesh.last();V heading=tail.left().left().mul(-1);boolean reached=false;
      for(UUID target:group){var road=known.get(target);if(!main(road))continue;var q=RoadQueries.horizontal(road.mesh,tail.center());
        if(!joinsMainline(current,road))continue;
        double direction=q.sample().left().left().mul(-1).dot(heading);if(Math.abs(direction)<.9)continue;
        int flow=direction>0?1:-1;if(target.equals(entry.host())&&flow==entry.flow())continue;
        V destination=flow>0?road.mesh.last().center():road.mesh.first().center();int side=(int)Math.signum(destination.sub(at.center()).dot(right));
        if(side==0)continue;String key="out:"+target+":"+flow;result.put(key,new Branch(key,List.of(),side,true,List.of()));reached=true;
      }
      if(reached)continue;
      for(UUID next:group){var road=known.get(next);if(road==null||main(road)||seen.contains(next)||next.equals(id))continue;
        if(tail.center().distance(road.mesh.first().center())<.35&&tail.left().dot(road.mesh.first().left())>.9)queue.add(next);
      }
    }
    return result.values().stream().sorted(Comparator.comparingInt(Branch::side).thenComparing(Branch::id)).toList();
  }
  /** Saved destination membership plus touching pavement admits a collector
   * landing outside the mainline centre envelope. Tangent is checked by callers. */
  private boolean joinsMainline(RoadIndex.Built arrival,RoadIndex.Built host){
    var tail=arrival.mesh.last();var q=RoadQueries.horizontal(host.mesh,tail.center());
    if(!ForkSignPlacement.onPath(host.mesh,tail.center(),.35))return false;
    if(Math.abs(q.sample().center().y()-tail.center().y())>.3)return false;
    double reach=q.sample().halfWidth()+.1;
    if(arrival.record.assembly()!=null&&Objects.equals(arrival.record.assembly(),host.record.assembly())){
      var descriptor=data.interchanges.get(arrival.record.assembly());CompoundTag leg=null,mainLeg=null;
      if(descriptor!=null)for(Tag value:descriptor.getList("SignTopology",Tag.TAG_COMPOUND)){var t=(CompoundTag)value;if(!t.hasUUID("Road"))continue;if(t.getUUID("Road").equals(arrival.record.id()))leg=t;if(t.getUUID("Road").equals(host.record.id()))mainLeg=t;}
      if(leg!=null&&mainLeg!=null&&leg.getInt("To")>=0&&(leg.getInt("To")==mainLeg.getInt("From")||leg.getInt("To")==mainLeg.getInt("To")))reach+=tail.halfWidth();
    }
    return Math.abs(q.lateral())<=reach;
  }
  private static int departureSide(Mesh host,Mesh ramp,double station,int flow,int lane){var s=RoadStructures.sample(host,station);V right=s.left().mul(flow),origin=LanePoints.lane(host,station,lane).position();for(var p:ramp.samples()){var q=RoadQueries.horizontal(host,p.center());double lateral=p.center().sub(q.sample().center()).dot(q.sample().left())*flow;double initial=origin.sub(s.center()).dot(right);if(Math.abs(lateral-initial)>.2)return lateral>initial?1:-1;}return origin.sub(s.center()).dot(right)>=0?1:-1;}
  private static List<V> path(Mesh m){var out=new ArrayList<V>();int step=Math.max(1,(m.samples().size()+62)/63);for(int i=0;i<m.samples().size();i+=step)out.add(m.samples().get(i).center());if(!out.get(out.size()-1).equals(m.last().center()))out.add(m.last().center());return List.copyOf(out);}
  private List<Branch> diagram(Exit entry,Key k,Set<UUID> deps){var host=known.get(entry.host());var group=new TreeSet<>(groups.getOrDefault(entry.assembly(),Set.of()));deps.addAll(group);var s=RoadStructures.sample(host.mesh,entry.station());V forward=s.left().left().mul(-entry.flow()),right=s.left().mul(entry.flow());var paths=new LinkedHashMap<String,List<V>>();var mainEnd=entry.flow()>0?host.mesh.last().center():host.mesh.first().center();
    var descriptor=data.interchanges.get(entry.assembly());boolean through=descriptor==null;
    if(descriptor!=null)for(Tag value:descriptor.getList("Nodes",Tag.TAG_COMPOUND)){var node=RoadRecord.readNode((CompoundTag)value);if(node.position().distance(mainEnd)<.2)through=true;}
    if(through)paths.put("through",List.of(s.center(),mainEnd));
    boolean multi=descriptor!=null&&descriptor.getList("Nodes",Tag.TAG_COMPOUND).size()>=5;
    if(multi){
      var legs=new ArrayList<InterchangePlanner.Leg>();int from=-1;
      for(Tag value:descriptor.getList("SignTopology",Tag.TAG_COMPOUND)){var tag=(CompoundTag)value;if(!tag.hasUUID("Road"))continue;var part=known.get(tag.getUUID("Road"));if(part==null)continue;legs.add(new InterchangePlanner.Leg(tag.getUUID("Road").toString(),tag.getInt("From"),tag.getInt("To"),part.mesh));if(tag.getUUID("Road").equals(entry.id()))from=tag.getInt("From");}
      var anchors=new ArrayList<Node>();for(Tag value:descriptor.getList("Nodes",Tag.TAG_COMPOUND))anchors.add(RoadRecord.readNode((CompoundTag)value));
      if(from>=0)paths.putAll(InterchangeSignPaths.routes(legs,known.get(entry.id()).mesh.first().center(),from,anchors));
    }else{
    for(UUID id:group){var ramp=known.get(id);if(ramp==null||main(ramp))continue;for(var e:exits.getOrDefault(entry.host(),List.of()))if(e.id().equals(id)&&e.flow()==entry.flow()&&Objects.equals(e.assembly(),entry.assembly())){
      var queue=new ArrayDeque<List<UUID>>();queue.add(List.of(id));int visits=0;
      while(!queue.isEmpty()&&visits++<128&&paths.size()<10){
        var route=queue.removeFirst();var tail=known.get(route.get(route.size()-1));
        boolean reaches=false;V destination=null;String destinationKey=null;
        for(UUID target:group){var road=known.get(target);if(!main(road))continue;var q=RoadQueries.horizontal(road.mesh,tail.mesh.last().center());
          if(joinsMainline(tail,road)
              &&LaneSections.live(road.mesh,q.sample().distance()).lanes().stream().anyMatch(l->l.direction().dot(tail.mesh.last().left().left().mul(-1))>.90)){
            reaches=true;double sign=q.sample().left().dot(tail.mesh.last().left());destination=sign>0?road.mesh.last().center():road.mesh.first().center();
            destinationKey=road.record.id().equals(entry.host())&&(sign>0?1:-1)==entry.flow()?"through":"out:"+road.record.id()+":"+(sign>0?1:-1);
          }
        }
        // A collector endpoint is both a valid mainline exit and the start of
        // another loop. Record the FIRST reached destination, not a full circuit.
        if(reaches&&!"through".equals(destinationKey)){
          var points=new ArrayList<V>();points.add(s.center());for(UUID part:route)points.addAll(path(known.get(part).mesh));if(destination!=null)points.add(destination);paths.putIfAbsent(destinationKey,points);continue;
        }
        for(UUID other:group){var next=known.get(other);if(next==null||route.contains(other)||main(next))continue;
          if(tail.mesh.last().center().distance(next.mesh.first().center())<.35&&tail.mesh.last().left().dot(next.mesh.first().left())>.9){var chain=new ArrayList<>(route);chain.add(other);queue.add(chain);}
        }
      }
    }}
    for(UUID ringId:group){var ring=known.get(ringId);if(ring==null||!ring.mesh.closed())continue;
      for(var source:exits.getOrDefault(entry.host(),List.of()))if(source.flow()==entry.flow()&&Objects.equals(source.assembly(),entry.assembly())){
        var incoming=known.get(source.id());if(incoming==null)continue;var join=RoadQueries.horizontal(ring.mesh,incoming.mesh.last().center());if(join.sample().center().distance(incoming.mesh.last().center())>.4)continue;
        for(UUID outId:group){var outgoing=known.get(outId);if(outgoing==null||main(outgoing)||outgoing==ring||outgoing==incoming)continue;var leave=RoadQueries.horizontal(ring.mesh,outgoing.mesh.first().center());if(leave.sample().center().distance(outgoing.mesh.first().center())>.4)continue;
          RoadIndex.Built target=null;int flow=0;
          for(UUID targetId:group){var road=known.get(targetId);if(!main(road)||targetId.equals(entry.host())||!joinsMainline(outgoing,road))continue;
            var q=RoadQueries.horizontal(road.mesh,outgoing.mesh.last().center());double dot=q.sample().left().dot(outgoing.mesh.last().left());if(Math.abs(dot)<.9)continue;target=road;flow=dot>0?1:-1;break;}
          if(target==null)continue;var route=new ArrayList<V>();route.add(s.center());route.addAll(path(incoming.mesh));double from=join.sample().distance(),to=leave.sample().distance();if(to<from)to+=ring.mesh.length();for(double d=from;d<to;d+=Math.max(1,(to-from)/40))route.add(RoadStructures.sample(ring.mesh,d%ring.mesh.length()).center());route.addAll(path(outgoing.mesh));route.add(flow>0?target.mesh.last().center():target.mesh.first().center());paths.put(source.id()+":"+outId,route);
        }
      }
    }
    }
    double maxX=1,maxZ=1,minZ=0;for(var path:paths.values())for(V p:path){V d=p.sub(s.center());maxX=Math.max(maxX,Math.abs(d.dot(right)));maxZ=Math.max(maxZ,d.dot(forward));minZ=Math.min(minZ,d.dot(forward));}var out=new ArrayList<Branch>();
    for(var item:paths.entrySet()){var normalized=new ArrayList<V>();var p=item.getValue();int step=Math.max(1,(p.size()+94)/95);for(int i=0;i<p.size();i+=step){V d=p.get(i).sub(s.center());var v=new V(d.dot(right)/maxX,d.y(),(d.dot(forward)-minZ)/(maxZ-minZ));if(normalized.isEmpty()||normalized.get(normalized.size()-1).distance(v)>1e-7)normalized.add(v);}V end=p.get(p.size()-1).sub(s.center());var finalPoint=new V(end.dot(right)/maxX,end.y(),(end.dot(forward)-minZ)/(maxZ-minZ));if(normalized.isEmpty()||normalized.get(normalized.size()-1).distance(finalPoint)>1e-7)normalized.add(finalPoint);out.add(new Branch(item.getKey(),List.of(),(int)Math.signum(end.dot(right)),true,normalized));}out.sort(Comparator.comparingDouble((Branch b)->b.path().get(b.path().size()-1).x()).thenComparing(Branch::id));return SmartSignTemplates.compile(descriptor==null?"":descriptor.getCompound("Options").getString("Preset"),out,host.mesh.settings().options().leftTraffic());
  }
  public State state(Key key){refresh();return states.getOrDefault(key,State.empty(key));}
  public State peek(Key key){return states.getOrDefault(key,State.empty(key));}
  public long version(UUID id){return versions.getOrDefault(id,0L);}
  public List<State> road(UUID id){return keysByRoad.getOrDefault(id,Set.of()).stream().map(states::get).filter(Objects::nonNull).toList();}
  private void requireReady(){if(pending())throw new IllegalArgumentException("标牌索引正在更新，请稍后重试；其他道路工具可正常使用");}
  public void assemblyChanged(UUID id){for(UUID road:groups.getOrDefault(id,Set.of()))changed(road);var tag=data.interchanges.get(id);if(tag!=null)for(Tag value:tag.getList("SignTopology",Tag.TAG_COMPOUND)){var leg=(CompoundTag)value;if(leg.hasUUID("Road"))changed(leg.getUUID("Road"));}}
  public CompoundTag packet(ServerLevel level,UUID id){var t=new CompoundTag();t.putString("Type","smartSigns");t.putString("Dimension",level.dimension().location().toString());t.putUUID("Id",id);var list=new ListTag();for(var s:road(id))if(s.mode()!=Mode.NORMAL)list.add(SmartSignCodec.write(s));t.put("Signs",list);return t;}
  public void tick(ServerLevel level){tick(level,System.nanoTime()+2_000_000L);}
  /** Rotate topology, physical sightlines and publication. A busy topology queue
   * cannot indefinitely postpone physical cleanup, and overlapping dirty roads
   * share one resumable neighborhood/fixture scan. */
  public void tick(ServerLevel level,long deadline){
    if(!pending()&&!sightlinesPending()&&publish.isEmpty())return;
    int start=maintenanceFamily,visited=0;
    for(int turn=0;turn<3&&System.nanoTime()<deadline;turn++){
      int family=maintenanceFamily;maintenanceFamily=(family+1)%3;visited++;
      long now=System.nanoTime(),partDeadline=now+Math.max(1,(deadline-now)/(3-turn));
      if(family==0)advance(partDeadline-now,32);
      else if(family==1)advanceSightlines(level,partDeadline,1024);
      else {
        var it=publish.iterator();for(int n=0;n<16&&it.hasNext()&&System.nanoTime()<partDeadline;n++){
          UUID id=it.next();it.remove();RoadNetwork.broadcastSmart(level,id);
        }
      }
    }
    if(visited==3)maintenanceFamily=(start+1)%3;
  }
  public boolean sightlinesPending(){return sightWork!=null||!sightDirty.isEmpty();}
  void advanceSightlines(ServerLevel level,long deadline,int maxSteps){
    if(sightWork==null){
      if(sightDirty.isEmpty())return;
      sightWork=new RoadSignSightlines(data.index,sightDirty,(road,removed)->{
        applyingSight=true;
        try{return data.removeSightlineLamps(level,road,removed);}
        finally{applyingSight=false;}
      },chunk->{var at=new net.minecraft.world.level.ChunkPos(chunk);if(level.hasChunk(at.x,at.z))data.repairChunk(level,at);});
    }
    sightWork.advance(deadline,maxSteps);
    if(sightWork.done()){
      var cost=sightWork.cost();sightWaves++;sightQueries+=cost.chunkQueries();sightPartVisits+=cost.partVisits();sightKeyBuilds+=cost.keyBuilds();sightCommits+=cost.commits();
      if(sightWork.invalidated())sightInvalidations++;else sightDirty.removeAll(sightWork.requested());
      sightWork=null;
    }
  }
  public String maintenanceMetrics(){
    return "sightWaves="+sightWaves+" sightInvalidations="+sightInvalidations+" sightChunkQueries="+sightQueries
        +" sightPartVisits="+sightPartVisits+" sightKeyBuilds="+sightKeyBuilds+" sightCommits="+sightCommits
        +" sightPending="+sightDirty.size()+" sightActive="+(sightWork!=null);
  }
  public void validateMode(Key key,Mode mode,long revision){requireReady();var built=data.index.roads.get(key.road());if(!main(built)||LaneSections.live(built.mesh,RoadGantry.station(built.mesh,key.slot()).distance()).count(key.flow())==0)throw new IllegalArgumentException("该方向没有可服务的车道");if(!SmartSigns.STYLES.contains(mode)&&mode!=Mode.NORMAL&&mode!=Mode.EXIT_AUTO)throw new IllegalArgumentException("无效的龙门架样式");if(peek(key).revision()!=revision)throw new IllegalArgumentException("标牌已被更新，请重新打开");}
  public void indicators(Key key,Set<String> closed){put(peek(key).indicators(closed));}
  public void applyMode(Key key,Mode mode){applyMode(key,mode,true);}
  public void applyModes(List<Key> keys,Mode mode){for(var key:keys)applyMode(key,mode,keys.size()==1);}
  private void applyMode(Key key,Mode mode,boolean exclusive){var old=peek(key);if(old.mode()!=mode){boolean sameExit=(old.mode()==Mode.EXIT_AUTO&&mode==Mode.EXIT_SIGN);put(old.layout(mode,sameExit?old.target():null,sameExit?old.branches():List.of(),sameExit?old.diagnostic():"",old.entry()));}
    if(exclusive&&mode==Mode.SPEED_LIMIT){var other=new Key(key.road(),key.slot(),-key.flow());var opposite=peek(other);if(opposite.mode()==Mode.SPEED_LIMIT)put(opposite.layout(Mode.EMPTY,null,List.of(),""));}
    changed(key.road());
  }
  public void mode(Key key,Mode mode,long revision){validateMode(key,mode,revision);applyMode(key,mode);}
  public void edit(ServerPlayer player,CompoundTag command){
    requireReady();var key=SmartSignCodec.key(command);var b=known.get(key.road());
    if(b==null)throw new IllegalArgumentException("龙门架已删除");RoadData.requireOwner(player,b.record.owner());var old=state(key);
    if(command.getLong("Revision")!=old.revision()||!Objects.equals(command.hasUUID("Target")?command.getUUID("Target"):null,old.target()))throw new IllegalArgumentException("标牌目标或内容已改变，请重新打开");
    if(old.target()==null||!old.diagnostic().isEmpty())throw new IllegalArgumentException("标牌待识别，暂不能提交内容");
    var anchor=SmartSigns.anchor(b.mesh,old);if(player.position().distanceToSqr(new net.minecraft.world.phys.Vec3(anchor.x(),anchor.y(),anchor.z()))>128*128)throw new IllegalArgumentException("离标牌太远");
    State next;
    if(old.mode()==Mode.LANE_INDICATOR){
      if(command.getBoolean("Restore")||!command.contains("Closed",Tag.TAG_BYTE))throw new IllegalArgumentException("请选择当前车道通行或禁行");
      next=old.indicator(command.getString("Field"),command.getBoolean("Closed"));
    }else{
      next=command.getBoolean("Restore")?old.restore():old.edit(command.getString("Field"),SmartSignCodec.text(command.getCompound("Value")));
      if(!command.getBoolean("Restore")&&command.contains("HeaderVisible")){String field=command.getString("Field");int slash=field.lastIndexOf('/');if(slash<1)throw new IllegalArgumentException("无效的标牌分支");next=next.header(field.substring(0,slash),command.getBoolean("HeaderVisible"));}
      if(!command.getBoolean("Restore"))for(String component:List.of("exit","distance")){String flag=component.equals("exit")?"ExitVisible":"DistanceVisible";if(command.contains(flag)){String field=command.getString("Field");int slash=field.lastIndexOf('/');if(slash<1)throw new IllegalArgumentException("无效的标牌分支");next=next.accessory(field.substring(0,slash),component,command.getBoolean(flag));}}
    }
    validateText(b.mesh,next);put(next);textEdits++;
  }
  private static void validateText(Mesh mesh,State s){
    for(var f:SmartSigns.layout(mesh,s).fields()){
      var word=s.text().get(f.id());if(word==null)continue;int allowed=word.wrap()?f.lines():1,rows=0;double minimum=.105;
      for(String line:word.value().split("\n",-1)){double units=line.codePoints().mapToDouble(c->c<128?.60:1).sum();rows+=Math.max(1,(int)Math.ceil(units*minimum/f.width()));}
      if(rows>allowed||minimum*rows>f.height()||(!word.wrap()&&word.value().contains("\n")))throw new IllegalArgumentException("该区域文字过长或换行过多，请缩短文字或启用换行");
    }
  }
  public ListTag save(){var list=new ListTag();states.values().stream().filter(s->(s.mode()!=Mode.NORMAL&&s.mode()!=Mode.EXIT_AUTO)||!s.text().isEmpty()||!s.drafts().isEmpty()||!s.hiddenHeaders().isEmpty()||!s.hiddenAccessories().isEmpty()).forEach(s->list.add(SmartSignCodec.write(s)));return list;}
  public void load(ListTag list){if(list.size()>65536)throw new IllegalArgumentException("智能标牌记录过多");for(Tag v:list){var s=SmartSignCodec.read((CompoundTag)v);
      if(!data.index.roads.containsKey(s.key().road())){data.setDirty();continue;}
      if(SmartSigns.freestanding(s.mode()))s=s.layout(s.mode(),s.target(),s.branches(),"标牌关联正在更新",s.entry()).supported(null);
      addState(s);}dirty.addAll(data.index.roads.keySet());}
}
