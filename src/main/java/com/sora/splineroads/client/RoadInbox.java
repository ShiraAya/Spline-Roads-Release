package com.sora.splineroads.client;

import com.sora.splineroads.net.RoadWire;
import com.sora.splineroads.world.RoadIndex;
import com.sora.splineroads.world.RoadRecord;
import java.util.concurrent.*;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.nbt.CompoundTag;

/** Ordered background decoding. Only the client thread publishes roads into the live index. */
public final class RoadInbox {
  public record Event(CompoundTag tag, RoadIndex.Built built, String error,List<RoadIndex.Built> batch,RoadPreviewData preview) {
    public Event(CompoundTag tag,RoadIndex.Built built,String error){this(tag,built,error,List.of(),null);}
    public Event(CompoundTag tag,RoadIndex.Built built,String error,List<RoadIndex.Built> batch){this(tag,built,error,batch,null);}
  }
  private record Ready(long epoch,long sequence,UUID transfer,Event event) {}
  private record RoadKey(String dimension,UUID road) {}
  private final Map<RoadKey,Long> deleted=new HashMap<>();
  private final Map<UUID,Long> cancelled=new HashMap<>();
  private long sequence;
  private volatile long completedSequence;
  public static final int MAX_RAW_TASKS = 256;
  public static final long MAX_RAW_BYTES = 8L * 1024 * 1024;
  private final java.util.concurrent.atomic.AtomicLong rawBytes = new java.util.concurrent.atomic.AtomicLong();
  private final java.util.concurrent.atomic.AtomicBoolean recoveryNeeded = new java.util.concurrent.atomic.AtomicBoolean();
  private final Runnable recover;
  private final java.util.function.Supplier<String> currentDimension;
  private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0,
      TimeUnit.SECONDS, new ArrayBlockingQueue<>(MAX_RAW_TASKS), task -> {
        Thread t = new Thread(task, "SplineRoads network decoder"); t.setDaemon(true); return t;
      });
  private final ArrayBlockingQueue<Ready> ready = new ArrayBlockingQueue<>(64);
  private final RoadWire.Assembler controls = new RoadWire.Assembler();
  private volatile long epoch;
  private record Decoded(Event event,long bytes){}
  /** Replacing this holder releases an old session without needing another packet.
   * Only its decoder touches the decoded map; the assembler serializes cancellation. */
  private static final class Session {
    final RoadWire.Assembler assembler = new RoadWire.Assembler();
    final LinkedHashMap<String,Decoded> decoded = new LinkedHashMap<>(16,.75f,true);
    final LinkedHashMap<UUID,Long> firstSequences = new LinkedHashMap<>();
    long decodedBytes;
  }
  private volatile Session session = new Session();
  private volatile long decodeNanos,duplicateHits,receivedBytes,recoveryCount,rejectedParts,maxQueueNanos;
  private long lastRecoveryNanos;
  public RoadInbox() { this(() -> {}); }
  public RoadInbox(Runnable recover) { this(recover,()->""); }
  public RoadInbox(Runnable recover,java.util.function.Supplier<String> dimension) { this.recover = Objects.requireNonNull(recover);this.currentDimension=Objects.requireNonNull(dimension); }
  public String metrics(){return "inbox="+backlog()+" rawKiB="+(rawBytes.get()/1024)+" queueMaxMs="+(maxQueueNanos/1000000)+" reused="+duplicateHits+" decodeMs="+(decodeNanos/1000000)+" wireMiB="+(receivedBytes/1048576)+" resync="+recoveryCount+" rejected="+rejectedParts;}
  private static String digest(byte[] data){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(data));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}

  public int backlog(){return worker.getQueue().size()+ready.size()+worker.getActiveCount();}
  public long rawBacklogBytes() { return rawBytes.get(); }
  public int partialCount() { return session.assembler.pendingCount(); }
  public void close(){reset();worker.shutdownNow();}

  public void reset() {
    epoch++;
    session = new Session();
    var abandoned = new ArrayList<Runnable>(); worker.getQueue().drainTo(abandoned);
    for (var task : abandoned) if (task instanceof RawTask raw) raw.release();
    ready.clear(); controls.clear(); deleted.clear(); cancelled.clear();
    recoveryNeeded.set(false); lastRecoveryNanos = 0;
  }

  /** Controls stay independent of the bounded geometry queue. Delete and cancellation
   * barriers stop older decoded snapshots from resurrecting a road. */
  public void control(RoadWire.Fragment fragment,Consumer<Event> sink) {
    try {
      var tag=controls.accept(fragment,System.nanoTime());if(tag==null)return;
      String type=tag.getString("Type");
      String dimension=currentDimension.get();
      if(tag.contains("Dimension")&&dimension!=null&&!dimension.isEmpty()&&!dimension.equals(tag.getString("Dimension")))return;
      if (type.equals("syncReset")) { reset(); sink.accept(new Event(tag,null,null)); return; }
      if (type.equals("cancelTransfer")) {
        UUID transfer=tag.getUUID("Transfer");
        session.assembler.cancel(transfer, System.nanoTime());
        // Keep publication barriers until queued work has passed this control,
        // even if a stalled decoder outlives the assembler's expiry window.
        cancelled.put(transfer,sequence);
        if(cancelled.size()>1024){reset();recoveryNeeded.set(true);}
        return;
      }
      if(!type.equals("open")&&!type.equals("result")&&!type.equals("delete")&&!type.equals("batchBarrier"))throw new IllegalArgumentException("无效的工具界面消息");
      if(type.equals("delete"))deleted.put(new RoadKey(tag.getString("Dimension"),tag.getUUID("Id")),sequence);
      if(type.equals("batchBarrier"))for(var entry:tag.getList("Ids",net.minecraft.nbt.Tag.TAG_COMPOUND))deleted.put(new RoadKey(tag.getString("Dimension"),((CompoundTag)entry).getUUID("Id")),sequence);
      sink.accept(new Event(tag,null,null));
    }catch(IllegalArgumentException e){controls.clear();recoveryNeeded.set(true);sink.accept(new Event(null,null,"工具消息读取失败："+e.getMessage()));}
  }

  private final class RawTask implements Runnable {
    final RoadWire.Fragment fragment;
    final long generation, serial, queued=System.nanoTime();
    final Session state;
    private final java.util.concurrent.atomic.AtomicBoolean released = new java.util.concurrent.atomic.AtomicBoolean();
    RawTask(RoadWire.Fragment fragment, long generation, long serial, Session state) {
      this.fragment=fragment;this.generation=generation;this.serial=serial;this.state=state;
    }
    void release() { if (released.compareAndSet(false,true)) rawBytes.addAndGet(-fragment.bytes().length); }
    @Override public void run() { try { decode(this); } finally { release(); } }
  }

  public void submit(RoadWire.Fragment fragment) {
    long generation=epoch,serial=++sequence;
    long held;
    do {
      held=rawBytes.get();
      if(held+fragment.bytes().length>MAX_RAW_BYTES){rejectedParts++;recoveryNeeded.set(true);return;}
    } while(!rawBytes.compareAndSet(held,held+fragment.bytes().length));
    var task=new RawTask(fragment,generation,serial,session);
    try { worker.execute(task); }
    catch(RejectedExecutionException e){task.release();rejectedParts++;recoveryNeeded.set(true);}
  }

  private void decode(RawTask task) {
    long generation=task.generation, serial=task.serial;
    var state=task.state;var fragment=task.fragment;
    if(generation!=epoch||state!=session)return;
    maxQueueNanos=Math.max(maxQueueNanos,System.nanoTime()-task.queued);
    Event result;CompoundTag decodedTag=null;long first=serial;
    try {
      receivedBytes+=fragment.bytes().length;
      state.firstSequences.putIfAbsent(fragment.id(),serial);
      byte[] packed=state.assembler.acceptBytes(fragment,System.nanoTime());
      // Save the completing transfer before pruning it; assembler eviction,
      // cancellation and byte pressure may remove a different pending transfer.
      first=state.firstSequences.getOrDefault(fragment.id(),serial);
      state.firstSequences.keySet().retainAll(state.assembler.pendingIds());
      if(state.assembler.takeDiscarded()>0&&generation==epoch)recoveryNeeded.set(true);
      if(packed==null){completedSequence=serial;return;}
      String hash=digest(packed);var reused=state.decoded.get(hash);
      if(reused!=null){duplicateHits++;result=reused.event();}
      else {
        long started=System.nanoTime();CompoundTag tag=RoadWire.decode(packed);decodedTag=tag;RoadIndex.Built built=null;
        if(tag.getString("Type").equals("road")){
          built=new RoadIndex.Built(RoadRecord.load(tag.getCompound("Road")),true);built.prepareCollision();

        }
        var batch=new ArrayList<RoadIndex.Built>();if(tag.getString("Type").equals("roadBatch"))for(var entry:tag.getList("Roads",net.minecraft.nbt.Tag.TAG_COMPOUND)){var part=new RoadIndex.Built(RoadRecord.load((CompoundTag)entry),true);part.prepareCollision();batch.add(part);}
        var preview=RoadPreviewData.queued(tag)?RoadPreviewData.prepare(tag):null;
        decodeNanos+=System.nanoTime()-started;result=new Event(tag,built,null,List.copyOf(batch),preview);
        if(built!=null){long weight=4096L+packed.length*8L+built.mesh.samples().size()*384L+built.record.structures().size()*512L;
          if(weight<=64L*1048576){state.decoded.put(hash,new Decoded(result,weight));state.decodedBytes+=weight;
            while(state.decoded.size()>256||state.decodedBytes>64L*1048576){var it=state.decoded.entrySet().iterator();var oldest=it.next();state.decodedBytes-=oldest.getValue().bytes();it.remove();}}
        }
      }
    } catch(RuntimeException e) {
      state.assembler.clear();state.firstSequences.clear();
      if(decodedTag!=null&&RoadPreviewData.queued(decodedTag))
        result=new Event(RoadPreviewData.failed(decodedTag,"预览读取失败，请重新预览："+(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage())),null,null);
      else {
        if(generation==epoch)recoveryNeeded.set(true);
        result=new Event(null,null,"道路同步失败，将自动重新同步："+(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage()));
      }
    }
    try {
      while(generation==epoch)
        if(ready.offer(new Ready(generation,first,fragment.id(),result),25,TimeUnit.MILLISECONDS))break;
    }catch(InterruptedException e){Thread.currentThread().interrupt();}
    completedSequence=serial;
  }

  private boolean stale(Ready ready){
    if(session.assembler.cancelled(ready.transfer())||ready.sequence()<=cancelled.getOrDefault(ready.transfer(),-1L))return true;
    var tag=ready.event().tag();if(tag==null)return false;
    if(ready.event().preview()!=null)for(UUID id:ready.event().preview().dependencies())
      if(ready.sequence()<=deleted.getOrDefault(new RoadKey(tag.getString("Dimension"),id),-1L))return true;
    if(tag.getString("Type").equals("smartSigns"))tag.getList("Signs",net.minecraft.nbt.Tag.TAG_COMPOUND).removeIf(value->{
      var sign=(CompoundTag)value;return sign.hasUUID("Target")&&ready.sequence()<=deleted.getOrDefault(new RoadKey(tag.getString("Dimension"),sign.getUUID("Target")),-1L);
    });
    if(tag.getString("Type").equals("roadBatch"))for(var built:ready.event().batch())if(ready.sequence()<=deleted.getOrDefault(new RoadKey(tag.getString("Dimension"),built.record.id()),-1L)){recoveryNeeded.set(true);return true;}
    UUID road=ready.event().built()!=null?ready.event().built().record.id():tag.hasUUID("Id")?tag.getUUID("Id"):null;
    if(road==null)return false;
    return ready.sequence()<=deleted.getOrDefault(new RoadKey(tag.getString("Dimension"),road),-1L);
  }

  /** A small time budget keeps a burst of city data from occupying one entire frame. */
  public int drain(Consumer<Event> sink) {
    long deadline = System.nanoTime() + 2_000_000;
    int count = 0;
    while (count < 8 && System.nanoTime() < deadline) {
      Ready next = ready.poll(); if (next == null) break;
      if(next.epoch==epoch) {
        var event=next.event();
        if(!stale(next)){sink.accept(event);count++;}
        else if(event.preview()!=null){sink.accept(new Event(RoadPreviewData.failed(event.tag(),"预览所用道路已改变，请重新预览"),null,null));count++;}
      }
    }
    var active = session;
    active.assembler.expire(System.nanoTime());
    if(active.assembler.takeDiscarded()>0)recoveryNeeded.set(true);
    // Wait for pressure to subside, then retry until the ordered server reset ACK.
    // Both ends rate-limit this path; no edits/chunk movement are needed for recovery.
    long now=System.nanoTime();
    if(recoveryNeeded.get()&&backlog()<16&&now-lastRecoveryNanos>=2_000_000_000L){
      lastRecoveryNanos=now;recoveryCount++;recover.run();
    }
    // Keep barriers only while an older decode/publication can still arrive.
    // A completed fragment is not necessarily a completed message. Preserve
    // barriers across gaps between parts and while the sole decoder is preparing
    // geometry before ready.offer; Ready then retains the first fragment sequence.
    if(ready.isEmpty()&&worker.getActiveCount()==0&&worker.getQueue().isEmpty()&&active.assembler.pendingCount()==0){
      deleted.values().removeIf(cutoff->completedSequence>=cutoff);
      cancelled.values().removeIf(cutoff->completedSequence>=cutoff);
    }
    return count;
  }
}
