package com.sora.splineroads.client;
import com.sora.splineroads.SplineRoads;
import com.sora.splineroads.core.*;
import com.sora.splineroads.core.SmartSigns.*;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.world.*;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import java.util.*;
import java.util.function.Predicate;

/** Subscription-owned authoritative state plus independently bounded geometry/GL caches. */
public final class SmartSignClient {
  private static int frameCandidates,frameDrawn,frameCulled;private static double frameCpuMs;
  private static final int MAX_RESIDENT=512,MAX_COLD=512,MAX_BYTES=32*1024*1024;
  private static final LinkedHashMap<Key,State> states=new LinkedHashMap<>();
  private static final LinkedHashMap<Key,Baked> cache=new LinkedHashMap<>(32,.75f,true);
  private static final RenderType MATERIAL=RenderType.entitySolid(ResourceLocation.fromNamespaceAndPath("minecraft","textures/block/white_concrete.png"));
  private static final RenderType PLATE_MATERIAL=RenderType.text(ResourceLocation.fromNamespaceAndPath("minecraft","textures/block/white_concrete.png"));
  private static volatile boolean resourcesDirty;
  public static void resourceReloaded(){resourcesDirty=true;}
  private static State preview;private static RoadRecord previewRoad;private static Baked previewMesh;
  public static long rebuilds,textLayouts;private static int bytes;private static long stateBytes;
  private static final Map<Key,Integer> stateWeights=new HashMap<>();
  private static Set<Key> visibleKeys=Set.of();
  // Hot data is expanded once, cold data is compressed. A 64 MiB hot tier
  // preserves the existing 300 large-sign workset without per-frame inflation.
  private static final int HOT_CPU_BYTES=64*1024*1024,COLD_CPU_BYTES=16*1024*1024;
  private static final int CPU_BYTES=HOT_CPU_BYTES+COLD_CPU_BYTES,GPU_BYTES=MAX_BYTES/2;
  private record Weight(State state,RoadRecord road,int bytes){}
  private static final Map<Key,Weight> hotWeights=new HashMap<>();
  private static final Set<Key> coldCompacted=new HashSet<>();
  private static boolean pendingUploads;
  private static int pendingUploadBytes;
  static boolean pendingUploads(){return pendingUploads;}
  static int pendingUploadBytes(){return pendingUploadBytes;}
  private record Candidate(State state,RoadRecord road,V anchor,net.minecraft.world.phys.AABB bounds,boolean valid){}
  private static final Map<Key,Candidate> candidatesByKey=new HashMap<>();
  private static final SignCandidateIndex<Key> candidateIndex=new SignCandidateIndex<>();
  private static final Map<UUID,Set<Key>> stateKeysByRoad=new HashMap<>();
  private static final Set<Key> dirtyCandidates=new LinkedHashSet<>();
  private static int frameCandidateVisits;
  private static long anchorBuilds;private static int gpuBytes,residentPlates,streamedPlates;
  private static Candidate candidate(State state,RoadIndex.Built road){
    var old=candidatesByKey.get(state.key());if(old!=null&&old.state()==state&&old.road()==road.record)return old;
    // Road and sign packets may arrive separately after a split, edit or removal.
    // Cache invalid snapshots too, but retry as soon as either source changes.
    Candidate result=new Candidate(state,road.record,null,null,false);
    if(SmartSigns.valid(road.mesh,state))try{
      var anchor=old!=null&&old.valid()&&old.road()==road.record&&sameGeometry(old.state(),state)?old.anchor():SmartSigns.anchor(road.mesh,state);anchorBuilds++;
      result=new Candidate(state,road.record,anchor,coarseBounds(road.mesh,state,anchor),true);
    }catch(IllegalArgumentException staleMount){
      // A surviving slot can still have an offset invalidated by a shorter road.
    }
    if(!result.valid())evict(state.key());
    candidatesByKey.put(state.key(),result);return result;
  }
  private static void refreshCandidates(){
    // Road notifications precede INDEX.put. Resolve queued keys here, after
    // packet application, and keep cold anchors indexed so waking a road needs
    // no city-wide rebuild. State remains owned by the server subscription.
    for(var key:dirtyCandidates){
      var state=states.get(key);var road=ClientRoads.INDEX.roads.get(key.road());
      if(state==null||road==null||state.target()==null||!state.diagnostic().isEmpty()){
        candidatesByKey.remove(key);candidateIndex.remove(key);continue;
      }
      var value=candidate(state,road);
      if(value.valid())candidateIndex.put(key,value.anchor().x(),value.anchor().z());
      else candidateIndex.remove(key);
    }
    dirtyCandidates.clear();
  }
  private static List<Candidate> visibleCandidates(V eye,Predicate<net.minecraft.world.phys.AABB> visible){
    refreshCandidates();var result=new ArrayList<Candidate>();frameCandidateVisits=0;
    for(var key:candidateIndex.near(eye.x(),eye.z())){
      frameCandidateVisits++;var value=candidatesByKey.get(key);
      if(value==null||!ClientRoads.active(key.road())||value.anchor().distance(eye)>144
          ||!visible.test(value.bounds()))continue;
      result.add(value);
    }
    return result;
  }
  static void candidatesChanged(UUID road){
    for(var key:stateKeysByRoad.getOrDefault(road,Set.of())){
      candidatesByKey.remove(key);candidateIndex.remove(key);dirtyCandidates.add(key);
    }
  }
  private record Failed(State state,RoadRecord road){}
  private static final Map<Key,Failed> failed=new HashMap<>();
  private static long bakeAttempts,bakeFailures,bakeNanos;private static int frameBakes;private static long bakeDeadline;
  private static net.minecraft.world.phys.AABB coarseBounds(Mesh mesh,State s){
    return coarseBounds(mesh,s,SmartSigns.anchor(mesh,s));
  }
  private static net.minecraft.world.phys.AABB coarseBounds(Mesh mesh,State s,V at){
    double radius=SmartSigns.freestanding(s.mode())?Math.max(5,s.branches().size()*2.4):mesh.settings().width()/2+4;
    if(s.mode()==SmartSigns.Mode.IC_JCT){long columns=Math.max(s.branches().stream().filter(b->b.side()<0).count(),s.branches().stream().filter(b->b.side()>0).count());radius=Math.max(radius,mesh.settings().width()/2+6+Math.max(0,columns-1)*1.95);}
    return new net.minecraft.world.phys.AABB(at.x()-radius,at.y()-1,at.z()-radius,at.x()+radius,at.y()+26,at.z()+radius);
  }
  private record Line(Component text,int width,double x,double y,double scale,int color){}
  private record Baked(State state,RoadRecord road,Layout layout,List<Layout> views,RoadSignalBatch batch,RoadSignalBatch plate,List<Line> lines,net.minecraft.world.phys.AABB bounds,int geometryBytes,int bytes){}
  private static void release(Baked b){if(b!=null){b.batch().close();b.plate().close();}}
  private static void clearCache(){cache.values().forEach(SmartSignClient::release);cache.clear();failed.clear();candidatesByKey.clear();candidateIndex.clear();dirtyCandidates.addAll(states.keySet());hotWeights.clear();coldCompacted.clear();bytes=0;}
  static void visibilityChanged(){clearCache();release(previewMesh);previewMesh=null;resourcesDirty=true;visibleKeys=Set.of();frameCandidates=frameDrawn=frameBakes=frameCulled=frameCandidateVisits=gpuBytes=residentPlates=streamedPlates=pendingUploadBytes=0;pendingUploads=false;frameCpuMs=0;}
  public static void clear(){clearCache();states.clear();stateKeysByRoad.clear();dirtyCandidates.clear();stateWeights.clear();cache.clear();bytes=0;stateBytes=0;pendingUploadBytes=0;pendingUploads=false;preview(null,null);SmartTextScreen.clear();}
  public static void roadChanged(UUID road){hotWeights.keySet().removeIf(k->k.road().equals(road));candidatesByKey.keySet().removeIf(k->k.road().equals(road));candidatesChanged(road);failed.keySet().removeIf(k->k.road().equals(road));for(var key:new ArrayList<>(cache.keySet()))if(key.road().equals(road))evict(key);if(preview!=null&&preview.key().road().equals(road))SmartTextScreen.clear();}
  /** An authoritative deletion also invalidates signs on other roads that target it. */
  public static void remove(UUID road){remove(road,true);}
  /** Local source eviction ends only this host's subscription-owned state. A
   * target may be outside loaded chunks while its host and sign remain live. */
  public static void evictSource(UUID road){remove(road,false);}
  private static void remove(UUID road,boolean deleted){
    for(var key:new ArrayList<>(states.keySet()))
      if(key.road().equals(road)||deleted&&Objects.equals(states.get(key).target(),road))removeState(key);
    for(var key:new ArrayList<>(cache.keySet()))if(key.road().equals(road))evict(key);
    if(preview!=null&&(preview.key().road().equals(road)||deleted&&Objects.equals(preview.target(),road)))SmartTextScreen.clear();
  }
  public static void receive(CompoundTag tag){UUID road=tag.getUUID("Id");var next=new HashMap<Key,State>();for(Tag v:tag.getList("Signs",Tag.TAG_COMPOUND)){var s=SmartSignCodec.read((CompoundTag)v);if(s.key().road().equals(road))next.put(s.key(),s);}
    for(var k:new ArrayList<>(states.keySet()))if(k.road().equals(road)&&!next.containsKey(k))removeState(k);next.forEach((key,s)->{var old=states.get(key);if(old==null||s.revision()>=old.revision()){stateBytes-=stateWeights.getOrDefault(key,0);int weight=weight(s);stateWeights.put(key,weight);stateBytes+=weight;states.put(key,s);stateKeysByRoad.computeIfAbsent(road,k->new HashSet<>()).add(key);dirtyCandidates.add(key);if(old!=null&&!old.equals(s))hotWeights.remove(key);if(old!=null&&!sameGeometry(s,old))evict(key);}});
    // State belongs to the server subscription. Local cache pressure must not
    // delete the only copy while the server still considers its revision sent.
    if(preview!=null&&preview.key().road().equals(road)){var n=next.get(preview.key());if(n==null||!Objects.equals(n.target(),preview.target())||n.revision()>=preview.revision()){SmartTextScreen.clear();}}
  }
  private static int weight(State s){int n=256+s.branches().stream().mapToInt(b->128+b.path().size()*40+b.lanes().size()*16).sum();for(var e:s.text().entrySet())n+=96+2*(e.getKey().length()+e.getValue().value().length());for(var h:s.drafts().values())for(var e:h.entrySet())n+=96+2*(e.getKey().length()+e.getValue().value().length());return n;}
  private static void removeState(Key key){hotWeights.remove(key);candidatesByKey.remove(key);candidateIndex.remove(key);dirtyCandidates.remove(key);var roadKeys=stateKeysByRoad.get(key.road());if(roadKeys!=null){roadKeys.remove(key);if(roadKeys.isEmpty())stateKeysByRoad.remove(key.road());}failed.remove(key);states.remove(key);stateBytes-=stateWeights.getOrDefault(key,0);stateWeights.remove(key);evict(key);}
  private static void evict(Key key){coldCompacted.remove(key);var b=cache.remove(key);if(b!=null){bytes-=b.bytes();release(b);}}
  public static void preview(State state,RoadRecord road){var previous=previewMesh;release(previous);preview=state;previewRoad=road;previewMesh=state==null||!RoadSignVisibility.enabled()?null:bake(state,road,previous==null?cache.get(state.key()):previous);}
  private static List<String> wrap(String value,int limit){var font=Minecraft.getInstance().font;var out=new ArrayList<String>();var line=new StringBuilder();for(int cp:value.codePoints().toArray()){if(cp=='\n'){out.add(line.toString());line.setLength(0);continue;}String c=new String(Character.toChars(cp));if(line.length()>0&&font.width(line+c)>limit){out.add(line.toString());line.setLength(0);}line.append(c);}out.add(line.toString());return out;}
  private static List<Line> text(Layout layout,State s){var out=new ArrayList<Line>();var font=Minecraft.getInstance().font;for(var f:layout.fields()){var word=f.editable()?s.text().get(f.id()):new Text(f.literal(),2,false);if(word==null||word.value().isEmpty())continue;if(!word.wrap()&&word.value().contains("\n"))throw new IllegalArgumentException("显式换行需要启用允许换行");int allowed=word.wrap()?f.lines():1;double scale=f.height()/10*new double[]{.66,.84,1}[word.size()];List<String> rows;
      while(true){int max=(int)Math.floor(f.width()/scale);rows=word.wrap()?wrap(word.value(),max):List.of(word.value());boolean fit=rows.size()<=allowed&&rows.size()*10*scale<=f.height()+1e-7&&rows.stream().allMatch(line->font.width(line)<=max);if(fit)break;if(scale<=.012001)throw new IllegalArgumentException("当前字体下文字超出区域，请缩短或启用换行");scale=Math.max(.012,scale*.92);}
      for(int i=0;i<rows.size();i++){var line=rows.get(i);out.add(new Line(Component.literal(line),font.width(line),f.x(),f.y()+(rows.size()-1)*5*scale-i*10*scale,scale,f.color()));}
    }return List.copyOf(out);}
  public static void validate(Mesh mesh,State state){text(SmartSigns.layout(mesh,state),state);}
  private static boolean sameGeometry(State a,State b){return a.key().equals(b.key())&&a.mode()==b.mode()&&a.entry().equals(b.entry())&&Objects.equals(a.target(),b.target())&&a.diagnostic().equals(b.diagnostic())&&a.branches().equals(b.branches())&&a.hiddenHeaders().equals(b.hiddenHeaders())&&a.hiddenAccessories().equals(b.hiddenAccessories())&&a.closedLanes().equals(b.closedLanes())&&Objects.equals(a.support(),b.support());}
  private static boolean plateFace(RoadSurface.Face f,Layout layout){double lo=Double.POSITIVE_INFINITY,hi=Double.NEGATIVE_INFINITY;for(var v:f.points()){double d=v.sub(layout.origin()).dot(layout.front());lo=Math.min(lo,d);hi=Math.max(hi,d);}return lo>=SmartSigns.FACE-1e-6&&hi-lo<1e-6;}
  private static Layout textView(Layout l){return new Layout(l.origin(),l.right(),l.front(),l.boards(),l.fields(),List.of());}
  private static Baked bake(State s,RoadRecord road,Baked old){
    boolean reuse=old!=null&&old.road()==road&&sameGeometry(old.state(),s);
    var layout=reuse?old.layout():SmartSigns.layout(road.mesh(),s);var lines=text(layout,s);textLayouts++;if(!reuse)rebuilds++;
    List<Layout> views;RoadSignalBatch body,plate;int geometryBytes;net.minecraft.world.phys.AABB bounds;
    if(reuse){views=old.views();body=old.batch();plate=old.plate();geometryBytes=old.geometryBytes();bounds=old.bounds();}
    else{
      var fullViews=SmartSigns.views(layout,s.mode());var bodyFaces=new ArrayList<RoadSurface.Face>();var plateFaces=new ArrayList<RoadSurface.Face>();
      for(var view:fullViews)for(var face:SmartSignFaces.compose(view)){if(fullViews.stream().anyMatch(v->plateFace(face,v)))plateFaces.add(face);else bodyFaces.add(face);}
      body=new RoadSignalBatch(bodyFaces);plate=new RoadSignalBatch(plateFaces);views=fullViews.stream().map(SmartSignClient::textView).toList();
      // The polygons have been baked; text/edit picking only retain frames and fields.
      layout=textView(layout);double radius=layout.boards().stream().mapToDouble(v->Math.abs(v.x())+v.width()/2).max().orElse(1)+1;var o=layout.origin();
      bounds=new net.minecraft.world.phys.AABB(o.x()-radius,o.y()-(s.support()==null?0:5.2),o.z()-radius,o.x()+radius,o.y()+layout.top(),o.z()+radius);
      geometryBytes=384+body.retainedBytes()+plate.retainedBytes()+views.stream().mapToInt(v->160+v.boards().size()*64+v.fields().size()*128).sum();
    }
    int textBytes=lines.stream().mapToInt(l->192+l.text().getString().length()*2).sum();
    return new Baked(s,road,layout,views,body,plate,lines,bounds,geometryBytes,geometryBytes+textBytes);
  }

  private static Baked measured(Baked b){
    int geometry=384+b.batch().retainedBytes()+b.plate().retainedBytes()+b.views().stream().mapToInt(v->160+v.boards().size()*64+v.fields().size()*128).sum();
    return new Baked(b.state(),b.road(),b.layout(),b.views(),b.batch(),b.plate(),b.lines(),b.bounds(),geometry,geometry+b.bytes()-b.geometryBytes());
  }
  private static Baked compact(Baked b){b.batch().compact();b.plate().compact();return measured(b);}
  private static Baked warm(Baked b){if(!b.batch().compacted()&&!b.plate().compacted())return b;b.batch().warm();b.plate().warm();return measured(b);}
  private static void trimCold(){
    int coldBytes=0,coldCount=0,compacted=0;long deadline=System.nanoTime()+500_000;
    for(var entry:cache.entrySet())if(!visibleKeys.contains(entry.getKey())){
      var before=entry.getValue();var after=before;
      if(!coldCompacted.contains(entry.getKey())&&compacted<2&&(compacted==0||System.nanoTime()<deadline)){after=compact(before);coldCompacted.add(entry.getKey());entry.setValue(after);bytes+=after.bytes()-before.bytes();compacted++;}
      coldBytes+=after.bytes();coldCount++;
    }
    for(var it=cache.entrySet().iterator();it.hasNext()&&(coldCount>MAX_COLD||coldBytes>COLD_CPU_BYTES||bytes>CPU_BYTES);){var e=it.next();if(visibleKeys.contains(e.getKey()))continue;coldCount--;coldBytes-=e.getValue().bytes();bytes-=e.getValue().bytes();coldCompacted.remove(e.getKey());release(e.getValue());it.remove();}
  }
  /** Stable nearest-first admission: no admitted object can evict another one. */
  private static List<Candidate> admitted(List<Candidate> sorted){
    var result=new ArrayList<Candidate>();int hotBytes=0;
    for(var c:sorted){var w=hotWeights.get(c.state().key());int size=w!=null&&w.state().equals(c.state())&&w.road()==c.road()?w.bytes():0;
      if(size>HOT_CPU_BYTES-hotBytes)continue;result.add(c);hotBytes+=size;
    }return result;
  }
  private static Baked baked(State s,RoadRecord road){
    var old=cache.get(s.key());if(old!=null&&old.state().equals(s)&&old.road()==road){
      if(visibleKeys.contains(s.key())){coldCompacted.remove(s.key());var value=warm(old);cache.put(s.key(),value);bytes+=value.bytes()-old.bytes();return value;}return old;
    }
    var failure=failed.get(s.key());if(failure!=null&&failure.state().equals(s)&&failure.road()==road)return null;
    long start=System.nanoTime();bakeAttempts++;Baked value;
    try{value=bake(s,road,old);value=warm(value);}catch(IllegalArgumentException e){bakeFailures++;failed.put(s.key(),new Failed(s,road));return null;}
    finally{bakeNanos+=System.nanoTime()-start;}
    hotWeights.put(s.key(),new Weight(s,road,value.bytes()));
    coldCompacted.remove(s.key());
    if(old!=null){cache.remove(s.key());bytes-=old.bytes();if(old.batch()!=value.batch())release(old);}
    while(!cache.isEmpty()&&bytes+value.bytes()>CPU_BYTES){
      Key victim=null;for(var key:cache.keySet())if(!visibleKeys.contains(key)){victim=key;break;}
      if(victim==null)break;evict(victim);
    }
    if(value.bytes()>HOT_CPU_BYTES||bytes+value.bytes()>CPU_BYTES){release(value);return null;}
    cache.put(s.key(),value);bytes+=value.bytes();failed.remove(s.key());return value;
  }

  public static void render(RenderLevelStageEvent event){if(!RoadSignVisibility.enabled()){frameCandidates=frameDrawn=frameBakes=frameCulled=frameCandidateVisits=gpuBytes=residentPlates=streamedPlates=pendingUploadBytes=0;pendingUploads=false;frameCpuMs=0;return;}long frameStart=System.nanoTime();var mc=Minecraft.getInstance();if(mc.level==null)return;if(resourcesDirty){resourcesDirty=false;clearCache();release(previewMesh);TollNameRenderer.clear();if(preview!=null){try{previewMesh=bake(preview,previewRoad,null);}catch(IllegalArgumentException ex){previewMesh=null;}}}var pos=event.getCamera().getPosition();V eye=new V(pos.x,pos.y,pos.z);var buffers=mc.renderBuffers().bufferSource();var used=new HashSet<RenderType>();var pose=event.getPoseStack();boolean editor=mc.player!=null&&(mc.player.getMainHandItem().is(SplineRoads.TEXT_EDITOR.get())||mc.player.getOffhandItem().is(SplineRoads.TEXT_EDITOR.get()));var visible=editor?new ArrayList<Baked>():null;
    var candidates=visibleCandidates(eye,event.getFrustum()::isVisible);var keys=new HashSet<Key>();
    frameCandidates=candidates.size();frameBakes=0;bakeDeadline=System.nanoTime()+2_000_000L;
    var drawn=new ArrayList<Baked>();var lights=new ArrayList<Integer>();
    candidates.sort(Comparator.comparingDouble((Candidate c)->c.anchor().distance(eye)).thenComparing(c->c.state().key().road()).thenComparingInt(c->c.state().key().slot()).thenComparingInt(c->c.state().key().flow()));
    var admitted=admitted(candidates);keys.clear();for(var candidate:admitted)keys.add(candidate.state().key());visibleKeys=keys;frameCulled=frameCandidates-admitted.size();int hotBytes=0;
    for(var candidate:admitted){var s=candidate.state();var road=candidate.road();
      var failure=failed.get(s.key());if(failure!=null&&failure.state().equals(s)&&failure.road()==road)continue;
      var cached=cache.get(s.key());boolean needsBake=cached==null||!cached.state().equals(s)||cached.road()!=road;
      if(needsBake&&frameBakes>0&&(frameBakes>=2||System.nanoTime()>=bakeDeadline))continue;
      if(needsBake)frameBakes++;
      Baked b=preview!=null&&preview.key().equals(s.key())?previewMesh:baked(s,road);if(b==null||b.layout().boards().isEmpty())continue;if(b.bytes()>HOT_CPU_BYTES-hotBytes){keys.remove(s.key());frameCulled++;continue;}hotBytes+=b.bytes();var o=b.layout().origin();var bounds=b.bounds();if(!event.getFrustum().isVisible(bounds))continue;if(editor)visible.add(b);
      int light=LevelRenderer.getLightColor(mc.level,BlockPos.containing(o.x()+b.layout().front().x(),o.y()+2,o.z()+b.layout().front().z()));drawn.add(b);lights.add(light);
    }
    frameDrawn=drawn.size();trimCold();pendingUploads=false;pendingUploadBytes=0;
    // Batch body submission with the original transformed/quantized normals.
    // Static artwork shares shader state without re-packing its many vertices.
    buffers.endBatch();
    for(int i=0;i<drawn.size();i++)drawn.get(i).batch().emit(pose,buffers,eye,t->MATERIAL,used,lights.get(i));
    buffers.endBatch(MATERIAL);used.remove(MATERIAL);
    // Only resident VBOs have a count cap. Every hot CPU object, including the
    // 513th small sign, keeps its expanded data and can stream without rebaking.
    var resident=new ArrayList<RoadSignalBatch>();var residentLight=new ArrayList<Integer>();var streaming=new ArrayList<Integer>();gpuBytes=0;int admittedGpuBytes=0,admittedGpuCount=0;
    for(int i=0;i<drawn.size();i++){var plate=drawn.get(i).plate();if(admittedGpuCount<MAX_RESIDENT&&admittedGpuBytes+plate.gpuBytes()<=GPU_BYTES){admittedGpuCount++;admittedGpuBytes+=plate.gpuBytes();if(plate.preparePlate(lights.get(i))){gpuBytes+=plate.gpuBytes();resident.add(plate);residentLight.add(plate.residentLight());}else streaming.add(i);if(!plate.currentLight(lights.get(i))){pendingUploads=true;pendingUploadBytes=Math.max(pendingUploadBytes,plate.gpuBytes());}}else{plate.close();streaming.add(i);}}
    for(var b:cache.values())if(!visibleKeys.contains(b.state().key()))b.plate().close();
    RoadSignalBatch.drawPlates(resident,residentLight,pose,eye,PLATE_MATERIAL,event.getProjectionMatrix());
    for(int i:streaming)drawn.get(i).plate().emitPlate(pose,buffers,eye,PLATE_MATERIAL,used,lights.get(i));
    if(!streaming.isEmpty())buffers.endBatch(PLATE_MATERIAL);residentPlates=resident.size();streamedPlates=streaming.size();
    for(int i=0;i<drawn.size();i++){var b=drawn.get(i);int light=lights.get(i);var o=b.layout().origin();
      if(o.distance(eye)>80)continue;
      for(var view:b.views()){if(eye.sub(view.origin()).dot(view.front())<=0)continue;
      for(var line:b.lines()){V at=view.at(line.x(),line.y(),SmartSigns.TEXT);pose.pushPose();pose.translate(at.x()-eye.x(),at.y()-eye.y(),at.z()-eye.z());pose.mulPose(Axis.YP.rotation((float)Math.atan2(-view.right().z(),view.right().x())));pose.scale((float)line.scale(),(float)-line.scale(),(float)line.scale());mc.font.drawInBatch(line.text(),-line.width()/2f,-4.5f,line.color(),false,pose.last().pose(),buffers,Font.DisplayMode.NORMAL,0,light);pose.popPose();}}
    }
    used.forEach(buffers::endBatch);
    if(editor&&mc.player!=null){var look=mc.player.getLookAngle();V ray=new V(look.x,look.y,look.z);Baked best=null;Layout selected=null;Field field=null;double nearest=96;
      for(var b:visible)for(var l:b.views()){double denominator=ray.dot(l.front());if(denominator>=-1e-5)continue;double d=l.origin().add(l.front().mul(SmartSigns.TEXT)).sub(eye).dot(l.front())/denominator;if(d<0||d>nearest)continue;V hit=eye.add(ray.mul(d)).sub(l.origin());double x=hit.dot(l.right()),y=hit.y();for(var f:l.fields())if(f.editable()&&Math.abs(x-f.x())<=f.width()/2&&Math.abs(y-f.y())<=f.height()/2){best=b;selected=l;field=f;nearest=d;}}
      if(best!=null){var f=field;var l=selected;var consumer=buffers.getBuffer(RenderType.lines());pose.pushPose();pose.translate(-eye.x(),-eye.y(),-eye.z());V[] corners={l.at(f.x()-f.width()/2,f.y()-f.height()/2,SmartSigns.TEXT+.005),l.at(f.x()+f.width()/2,f.y()-f.height()/2,SmartSigns.TEXT+.005),l.at(f.x()+f.width()/2,f.y()+f.height()/2,SmartSigns.TEXT+.005),l.at(f.x()-f.width()/2,f.y()+f.height()/2,SmartSigns.TEXT+.005)};for(int i=0;i<4;i++){V a=corners[i],b=corners[(i+1)%4],n=b.sub(a).mul(1/a.distance(b));for(V v:List.of(a,b))consumer.vertex(pose.last().pose(),(float)v.x(),(float)v.y(),(float)v.z()).color(255,219,85,255).normal(pose.last().normal(),(float)n.x(),(float)n.y(),(float)n.z()).endVertex();}pose.popPose();buffers.endBatch(RenderType.lines());}
    }
    double elapsed=(System.nanoTime()-frameStart)/1e6;frameCpuMs=frameCpuMs==0?elapsed:frameCpuMs*.9+elapsed*.1;
  }
  public static String metrics(){return "sign states="+states.size()+" cache="+cache.size()+" bytes="+(bytes+stateBytes)+" rebuilds="+rebuilds+" textLayouts="+textLayouts+" attempts="+bakeAttempts+" failed="+bakeFailures+" bakeMs="+(bakeNanos/1000000)+" anchors="+anchorBuilds+" gpuBytes="+gpuBytes+" gpuCount="+residentPlates+" streamed="+streamedPlates+" candidates="+frameCandidates+" drawn="+frameDrawn+" capacityCulled="+frameCulled+" candidateVisits="+frameCandidateVisits+" indexed="+candidateIndex.size()+" hotLimitMiB=64 coldLimitMiB=16 inflates="+RoadSignalBatch.inflations()+" enabled="+RoadSignVisibility.enabled()+" cpuMs="+String.format(Locale.ROOT,"%.2f",frameCpuMs);}
  private SmartSignClient(){}
}
