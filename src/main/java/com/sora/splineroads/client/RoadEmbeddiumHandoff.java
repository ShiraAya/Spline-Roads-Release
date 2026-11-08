package com.sora.splineroads.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.sora.splineroads.core.RoadTerrainMesh.Section;
import java.lang.ref.WeakReference;
import java.lang.reflect.*;
import java.util.*;
import net.minecraft.client.Minecraft;

/** Embeddium 0.3.x has its own GPU arena transaction. Keep optional types out of
 * Java descriptors and acknowledge its real installed GlBufferSegment, after
 * uploadMeshes returned. An output rejected before that transaction is freed by
 * Embeddium's original result owner; a deferred one transfers to the next batch.
 */
public final class RoadEmbeddiumHandoff {
  private static final Map<Object,Object> BUILT=Collections.synchronizedMap(new WeakHashMap<>());
  private static final Map<Object,List<Object>> DEFERRED=new WeakHashMap<>();
  private static final Map<Object,Long> WAIT_ORDER=new WeakHashMap<>();
  private static final RoadTerrainUploadBudget BUDGET=new RoadTerrainUploadBudget();
  private static volatile Backend backend;
  private static WeakReference<Object> collecting=new WeakReference<>(null);
  private static long commits,rejected,deferred;
  private static long waitSerial;
  private record Proof(Object storage,Object allocation,int index){}
  private RoadEmbeddiumHandoff(){}
  static void beginUploadFrame(){BUDGET.begin();}
  public static void begin(Object task,Object render,Object cancellation){
    RoadTerrainHandoff.abort(task);
    var api=api(render);
    if((boolean)invoke(api.cancelled,cancellation))return;
    RoadTerrainHandoff.beginOptional(task,render,position(api,render),cancellation);
  }
  public static Object built(Object output,Object task){
    var token=RoadTerrainHandoff.endOptional(task);
    if(output!=null&&token!=null)BUILT.put(output,token);else RoadTerrainHandoff.finishOptional(token);return output;
  }
  /** Empty world sections bypass the meshing task but still remove a real GPU
   * allocation. Capture their identity when the native empty result is made. */
  public static Object empty(Object output){
    var api=api(output);Object render=get(api.render,output);
    RoadTerrainHandoff.beginOptional(output,render,position(api,render));
    BUILT.put(output,RoadTerrainHandoff.endOptional(output));return output;
  }
  /** Also runs on destroy(), where Embeddium deletes every returned native buffer. */
  @SuppressWarnings({"rawtypes","unchecked"})
  public static ArrayList collect(ArrayList outputs,Object manager){
    api(manager);var retained=DEFERRED.remove(manager);if(retained!=null)outputs.addAll(retained);
    if(!outputs.isEmpty())collecting=new WeakReference<>(manager);return outputs;
  }
  /** The injected monitor spans validation, native upload and acknowledgment. */
  public static Object uploadLock(){return RoadTerrainHandoff.STATE;}
  /** Native renderer destruction frees entire arenas without invoking each
   * RenderSection.delete(). Retire those owners before the regions are emptied,
   * so retained VBO draw selections observe the disposal immediately. */
  public static void releaseRegions(Object manager){
    var api=api(manager);
    for(Object region:(Collection<?>)invoke(api.loadedRegions,manager))for(int i=0;i<api.regionSize;i++){
      Object owner=invoke(api.regionSection,region,i);if(owner!=null)RoadTerrainHandoff.release(owner);
    }
  }
  public static void beforeUpload(Collection<?> results){
    for(var it=results.iterator();it.hasNext();){Object output=it.next(),token=BUILT.get(output);
      if(token!=null&&!RoadTerrainHandoff.validOptional(token)){
        Object render=get(backend.render,output);retireCompletedJob(token,render);retry(token,render);
        BUILT.remove(output);RoadTerrainHandoff.finishOptional(token);it.remove();rejected++;
      }
    }
  }
  @SuppressWarnings({"rawtypes","unchecked"})
  public static List filter(List selected,ArrayList originals){
    RenderSystem.assertOnRenderThread();if(selected.isEmpty()&&originals.isEmpty())return selected;
    ArrayList accepted=null;ArrayList<Object> postponed=null;
    // A later transparency sort may supersede a budget-deferred full result in
    // the native frame filter. It cannot supply the missing road geometry;
    // request a new full build before the native owner frees that old result.
    if(originals.size()!=selected.size()){
      var chosen=Collections.newSetFromMap(new IdentityHashMap<Object,Boolean>());chosen.addAll(selected);
      for(Object output:originals)if(!chosen.contains(output)){
        Object token=BUILT.remove(output);
        retireCompletedJob(token,get(backend.render,output));
        if(token!=null&&RoadTerrainHandoff.validOptional(token)&&RoadTerrainHandoff.stagedOptional(token)
            &&!(boolean)invoke(backend.indexOnly,output))retry(token,get(backend.render,output));
        RoadTerrainHandoff.finishOptional(token);
      }
    }
    List ordered=selected;
    // The native list puts newly finished sections before prior deferred ones.
    // Keep per-section age across superseded builds so a continuous stream of
    // fresh road chunks cannot consume every frame before an older one uploads.
    if(!WAIT_ORDER.isEmpty()){
      ordered=new ArrayList(selected);
      ordered.sort(Comparator.comparingLong(output->WAIT_ORDER.getOrDefault(get(backend.render,output),Long.MAX_VALUE)));
    }
    for(int i=0;i<ordered.size();i++){Object output=ordered.get(i);
      var api=backend;Object token=BUILT.get(output),render=get(api.render,output);
      if(token!=null&&!RoadTerrainHandoff.validOptional(token)){
        rejected++;retireCompletedJob(token,render);retry(token,render);BUILT.remove(output);RoadTerrainHandoff.finishOptional(token);
        if(accepted==null)accepted=new ArrayList(ordered.subList(0,i));continue;
      }
      if(token!=null&&RoadTerrainHandoff.stagedOptional(token)&&!BUDGET.claim(bytes(api,output),false)){
        WAIT_ORDER.computeIfAbsent(render,key->++waitSerial);if(postponed==null)postponed=new ArrayList<>();postponed.add(output);deferred++;
        if(accepted==null)accepted=new ArrayList(ordered.subList(0,i));continue;
      }
      if(token!=null)WAIT_ORDER.remove(render);
      if(accepted!=null)accepted.add(output);
    }
    if(postponed!=null){
      var retained=Collections.newSetFromMap(new IdentityHashMap<Object,Boolean>());retained.addAll(postponed);originals.removeIf(retained::contains);
      DEFERRED.computeIfAbsent(Objects.requireNonNull(collecting.get()),k->new ArrayList<>()).addAll(postponed);
    }
    return accepted==null?ordered:accepted;
  }
  public static void uploaded(Collection<?> results){
    RenderSystem.assertOnRenderThread();var api=backend;
    for(Object output:results){
      if((boolean)invoke(api.indexOnly,output))continue;
      Object render=get(api.render,output),token=BUILT.remove(output);
      if(token==null){RoadTerrainHandoff.release(render);continue;}
      if(!RoadTerrainHandoff.ownersOptional(token)){
        RoadTerrainHandoff.commitOptional(token,null);RoadTerrainHandoff.finishOptional(token);commits++;continue;
      }
      Object region=invoke(api.region,render),storage=invoke(api.storage,region,api.solid);
      int index=(int)invoke(api.index,render);
      Object allocation=storage instanceof RoadEmbeddiumStorage access?access.splineroads$allocation(index):null;
      Object info=get(api.info,output);
      if(RoadTerrainHandoff.ownersOptional(token)&&(allocation==null||info==null||((int)get(api.infoFlags,info)&api.geometryMask)==0)){
        // A region can discard/supersede a result before acknowledgment while
        // fast travel unloads its sections. Never claim GPU ownership without
        // the allocation; retain fallback geometry and request a full rebuild.
        RoadTerrainHandoff.release(render);retry(token,render);RoadTerrainHandoff.finishOptional(token);rejected++;continue;
      }
      RoadTerrainHandoff.commitOptional(token,new Proof(storage,allocation,index));RoadTerrainHandoff.finishOptional(token);commits++;
    }
  }
  public static boolean alive(Object render,Section section,Object receipt){
    var api=backend;if(api==null||!api.renderClass.isInstance(render)||!(receipt instanceof Proof proof))return false;
    if((boolean)invoke(api.disposed,render)||!position(api,render).equals(section))return false;
    if(!(boolean)invoke(api.isBuilt,render)||((int)invoke(api.flags,render)&api.geometryMask)==0)return false;
    Object storage=invoke(api.storage,invoke(api.region,render),api.solid);
    return storage==proof.storage&&storage instanceof RoadEmbeddiumStorage access&&access.splineroads$allocation(proof.index)==proof.allocation;
  }
  private static void retry(Object token,Object render){
    var section=RoadTerrainHandoff.optionalSection(token);
    if(section!=null&&!(boolean)invoke(backend.disposed,render)&&Minecraft.getInstance().level!=null)
      Minecraft.getInstance().levelRenderer.setSectionDirty(section.x(),section.y(),section.z());
  }
  /** Do not cancel a current native build merely because its prior allocation is old. */
  static boolean refresh(Object render,Section section){
    var api=backend;if(api==null||render==null||!api.renderClass.isInstance(render)||(boolean)invoke(api.disposed,render))return false;
    if(invoke(api.job,render)!=null)return false;
    var mc=Minecraft.getInstance();if(mc.level==null)return false;
    mc.levelRenderer.setSectionDirty(section.x(),section.y(),section.z());return true;
  }
  /** Removed results do not reach the native metadata-completion loop. Its
   * finished job must stop blocking VisibleChunkCollector's rebuild list, but
   * a late result must never clear a newer job submitted for the same section. */
  private static void retireCompletedJob(Object token,Object render){
    Object job=RoadTerrainHandoff.optionalJob(token);
    if(job!=null&&invoke(backend.job,render)==job)invoke(backend.setJob,render,(Object)null);
  }
  private static int bytes(Backend api,Object output){
    long size=0;for(Object mesh:((Map<?,?>)get(api.meshes,output)).values()){
      var vertices=invoke(api.vertices,mesh);if(vertices!=null)size+=(int)invoke(api.length,vertices);
      var indices=invoke(api.indices,mesh);if(indices!=null)size+=(int)invoke(api.length,indices);
    }return (int)Math.min(Integer.MAX_VALUE,size);
  }
  private static Section position(Backend api,Object render){return new Section((int)invoke(api.x,render),(int)invoke(api.y,render),(int)invoke(api.z,render));}
  static String status(){int pending=DEFERRED.values().stream().mapToInt(List::size).sum();return backend==null?"":", Embeddium "+commits+" commits / "+rejected+" rejected, "+pending+" deferred, "+BUDGET.bytes()+" bytes/batch";}
  static boolean active(){return backend!=null;}
  static int pending(){return DEFERRED.values().stream().mapToInt(List::size).sum();}
  static int uploadedBytes(){return BUDGET.bytes();}
  static int uploadedCount(){return BUDGET.uploads();}
  private static Backend api(Object render){
    var value=backend;if(value!=null)return value;
    synchronized(RoadEmbeddiumHandoff.class){if(backend==null)backend=new Backend(render.getClass().getClassLoader());return backend;}
  }
  private static Object invoke(Method method,Object object,Object... args){try{return method.invoke(object,args);}catch(ReflectiveOperationException e){throw new IllegalStateException("SplineRoads Embeddium upload adapter: "+method,e);}}
  private static Object get(Field field,Object object){try{return field.get(object);}catch(ReflectiveOperationException e){throw new IllegalStateException("SplineRoads Embeddium output field: "+field,e);}}
  private static final class Backend {
    final Class<?> renderClass;
    final Method x,y,z,disposed,region,index,storage,indexOnly,cancelled,vertices,indices,length,isBuilt,flags,loadedRegions,regionSection,job,setJob;
    final Field render,meshes,info,infoFlags;
    final int geometryMask,regionSize;
    final Object solid;
    Backend(ClassLoader loader){try{
      String root="me.jellysquid.mods.sodium.client.";
      renderClass=Class.forName(root+"render.chunk.RenderSection",false,loader);
      x=renderClass.getMethod("getChunkX");y=renderClass.getMethod("getChunkY");z=renderClass.getMethod("getChunkZ");
      disposed=renderClass.getMethod("isDisposed");region=renderClass.getMethod("getRegion");index=renderClass.getMethod("getSectionIndex");
      isBuilt=renderClass.getMethod("isBuilt");flags=renderClass.getMethod("getFlags");
      var cancellation=Class.forName(root+"util.task.CancellationToken",false,loader);
      job=renderClass.getMethod("getBuildCancellationToken");setJob=renderClass.getMethod("setBuildCancellationToken",cancellation);
      geometryMask=1<<Class.forName(root+"render.chunk.RenderSectionFlags",false,loader).getField("HAS_BLOCK_GEOMETRY").getInt(null);
      var pass=Class.forName(root+"render.chunk.terrain.TerrainRenderPass",false,loader);
      solid=Class.forName(root+"render.chunk.terrain.DefaultTerrainRenderPasses",true,loader).getField("SOLID").get(null);
      var regionClass=Class.forName(root+"render.chunk.region.RenderRegion",false,loader);
      storage=regionClass.getMethod("getStorage",pass);regionSection=regionClass.getMethod("getSection",int.class);
      regionSize=regionClass.getField("REGION_SIZE").getInt(null);
      loadedRegions=Class.forName(root+"render.chunk.region.RenderRegionManager",false,loader).getMethod("getLoadedRegions");
      var output=Class.forName(root+"render.chunk.compile.ChunkBuildOutput",false,loader);
      render=output.getField("render");meshes=output.getField("meshes");info=output.getField("info");indexOnly=output.getMethod("isIndexOnlyUpload");
      infoFlags=Class.forName(root+"render.chunk.data.BuiltSectionInfo",false,loader).getField("flags");
      cancelled=cancellation.getMethod("isCancelled");
      var mesh=Class.forName(root+"render.chunk.data.BuiltSectionMeshParts",false,loader);
      vertices=mesh.getMethod("getVertexData");indices=mesh.getMethod("getIndexData");
      length=Class.forName(root+"util.NativeBuffer",false,loader).getMethod("getLength");
    }catch(ReflectiveOperationException e){throw new IllegalStateException("Unsupported Embeddium terrain upload API",e);}}
  }
}
