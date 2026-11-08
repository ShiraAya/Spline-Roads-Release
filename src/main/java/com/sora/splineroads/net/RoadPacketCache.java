package com.sora.splineroads.net;

import com.sora.splineroads.core.SmartSigns;
import com.sora.splineroads.world.*;
import net.minecraft.nbt.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Immutable records/sign states only: no world, chunk or player access on this worker. */
public final class RoadPacketCache {
  public static final int MAX_JOBS=4,MAX_ENTRIES=128,MAX_BYTES=32*1024*1024;
  private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,
      new ArrayBlockingQueue<>(MAX_JOBS),r->{var t=new Thread(r,"SR road encoder");t.setDaemon(true);return t;});
  private record Key(String dimension,UUID id,boolean signs){}
  private static final class Entry {
    final Object version;final CompletableFuture<List<RoadWire.Fragment>> future;int bytes;
    Entry(Object version,CompletableFuture<List<RoadWire.Fragment>> future){this.version=version;this.future=future;}
  }
  private final LinkedHashMap<Key,Entry> entries=new LinkedHashMap<>(16,.75f,true);
  private final Set<EncodeJob> jobs=ConcurrentHashMap.newKeySet();
  private final java.util.concurrent.atomic.AtomicInteger outstanding=new java.util.concurrent.atomic.AtomicInteger();
  private int retainedBytes;
  private volatile long encodings,signEncodings;
  private volatile long generation;

  private final class EncodeJob extends FutureTask<List<RoadWire.Fragment>> {
    final CompletableFuture<List<RoadWire.Fragment>> result=new CompletableFuture<>();
    final long submittedGeneration;
    EncodeJob(long submittedGeneration,Supplier<CompoundTag> snapshot){super(()->{
      if(submittedGeneration!=generation||Thread.currentThread().isInterrupted())throw new CancellationException();
      var tag=snapshot.get();
      if(submittedGeneration!=generation||Thread.currentThread().isInterrupted())throw new CancellationException();
      return List.copyOf(RoadWire.split(tag));
    });this.submittedGeneration=submittedGeneration;}
    @Override protected void done(){
      jobs.remove(this);outstanding.decrementAndGet();
      if(submittedGeneration!=generation){result.cancel(false);return;}
      try{var fragments=get();if(submittedGeneration!=generation)result.cancel(false);else result.complete(fragments);}
      catch(CancellationException e){result.cancel(false);}
      catch(ExecutionException e){result.completeExceptionally(e.getCause());}
      catch(InterruptedException e){Thread.currentThread().interrupt();result.completeExceptionally(e);}
    }
  }
  /** Called on the server thread. Null means backpressure; retry in a later fair turn. */
  public CompletableFuture<List<RoadWire.Fragment>> request(String dimension,RoadRecord record){
    return request(new Key(dimension,record.id(),false),record,()->{
      var tag=new CompoundTag();tag.putString("Type","road");tag.putString("Dimension",dimension);tag.put("Road",record.save());return tag;
    });
  }
  /** The same immutable version is serialized once for every player watching this road. */
  public CompletableFuture<List<RoadWire.Fragment>> requestSigns(String dimension,UUID id,long version,List<SmartSigns.State> states){
    var snapshot=List.copyOf(states);
    return request(new Key(dimension,id,true),Long.valueOf(version),()->{
      signEncodings++;
      var tag=new CompoundTag();tag.putString("Type","smartSigns");tag.putString("Dimension",dimension);tag.putUUID("Id",id);
      var list=new ListTag();for(var state:snapshot)if(state.mode()!=SmartSigns.Mode.NORMAL)list.add(SmartSignCodec.write(state));tag.put("Signs",list);return tag;
    });
  }
  private CompletableFuture<List<RoadWire.Fragment>> request(Key key,Object version,Supplier<CompoundTag> snapshot){
    trim();var found=entries.get(key);
    if(found!=null&&(found.version==version||key.signs()&&found.version.equals(version)))return found.future;
    var future=requestTag(snapshot);if(future==null)return null;
    if(found!=null){retainedBytes-=found.bytes;entries.remove(key);}
    entries.put(key,new Entry(version,future));return future;
  }
  /** Server-owned immutable NBT snapshot. It must not be mutated after submission. */
  public CompletableFuture<List<RoadWire.Fragment>> requestTag(CompoundTag snapshot){return requestTag(()->snapshot);}
  /** Factory may capture immutable records or sign states, never a world or mutable index. */
  public CompletableFuture<List<RoadWire.Fragment>> requestTag(Supplier<CompoundTag> snapshot){
    if(outstanding.get()>=MAX_JOBS)return null;
    var job=new EncodeJob(generation,snapshot);outstanding.incrementAndGet();jobs.add(job);
    try{worker.execute(job);encodings++;return job.result;}
    catch(RejectedExecutionException e){job.cancel(false);return null;}
  }
  private void trim(){
    for(var entry:entries.values())if(entry.bytes==0&&entry.future.isDone()&&!entry.future.isCompletedExceptionally()){
      entry.bytes=entry.future.join().stream().mapToInt(f->f.bytes().length).sum();retainedBytes+=entry.bytes;
    }
    for(var it=entries.entrySet().iterator();it.hasNext()&&(retainedBytes>MAX_BYTES||entries.size()>=MAX_ENTRIES);){
      var entry=it.next().getValue();if(!entry.future.isDone())continue;retainedBytes-=entry.bytes;it.remove();
    }
  }
  public int retainedBytes(){trim();return retainedBytes;}
  public int jobs(){return outstanding.get();}
  public long encodings(){return encodings;}
  public long signEncodings(){return signEncodings;}
  public void clear(){
    // Invalidate the whole session before interrupting any active job: the
    // worker may visit queued jobs while this unordered cancellation loop runs.
    generation++;
    entries.clear();retainedBytes=0;
    for(var job:List.copyOf(jobs))job.cancel(true);
    worker.purge();
  }
}
