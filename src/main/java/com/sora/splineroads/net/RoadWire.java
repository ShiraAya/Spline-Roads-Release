package com.sora.splineroads.net;

import java.io.*;
import java.util.*;
import java.util.zip.*;
import net.minecraft.nbt.*;

/** Bounded, compressed, fragmented server messages; vanilla NBT limits stay unchanged. */
public final class RoadWire {
  public static final int PART_BYTES = 32768, MAX_PARTS = 128, MAX_RAW = 16 * 1024 * 1024;

  public record Fragment(UUID id, int index, int count, byte[] bytes) {
    public Fragment {
      if (id == null
          || count < 1
          || count > MAX_PARTS
          || index < 0
          || index >= count
          || bytes == null
          || bytes.length < 1
          || bytes.length > PART_BYTES) throw new IllegalArgumentException("无效的道路数据分包");
    }
  }

  public static List<Fragment> split(CompoundTag tag) {
    try {
      var raw = new ByteArrayOutputStream();
      NbtIo.write(tag, new DataOutputStream(new LimitedOutput(raw,MAX_RAW,"道路编辑数据过大，请减少同一端点附近的道路数量")));
      var packed = new ByteArrayOutputStream();
      try (var gzip = new GZIPOutputStream(new LimitedOutput(packed,(long)PART_BYTES*MAX_PARTS,"道路同步数据过大"))) {
        raw.writeTo(gzip);
      }
      byte[] bytes = packed.toByteArray();
      int count = (bytes.length + PART_BYTES - 1) / PART_BYTES;
      if (count > MAX_PARTS) throw new IllegalArgumentException("道路同步数据过大");
      UUID id = UUID.randomUUID();
      List<Fragment> parts = new ArrayList<>(count);
      for (int i = 0; i < count; i++)
        parts.add(
            new Fragment(
                id,
                i,
                count,
                Arrays.copyOfRange(
                    bytes, i * PART_BYTES, Math.min(bytes.length, (i + 1) * PART_BYTES))));
      return parts;
    } catch (IOException e) {
      throw new IllegalArgumentException("道路数据编码失败", e);
    }
  }

  /** Enforce the limit while allocating, not after an arbitrarily large NBT has
   * already filled the byte array. Encoder cancellation is checked per 8 KiB. */
  private static final class LimitedOutput extends OutputStream {
    private final OutputStream out;private final long limit;private final String message;
    private long written,checked;
    LimitedOutput(OutputStream out,long limit,String message){this.out=out;this.limit=limit;this.message=message;}
    private void reserve(int count){
      if(count<0||written+count>limit)throw new IllegalArgumentException(message);
      if(written==0||written+count-checked>=8192){
        if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("道路编码已取消");checked=written+count;
      }
      written+=count;
    }
    @Override public void write(int value)throws IOException{reserve(1);out.write(value);}
    @Override public void write(byte[] data,int offset,int length)throws IOException{Objects.checkFromIndexSize(offset,length,data.length);reserve(length);out.write(data,offset,length);}
    @Override public void flush()throws IOException{out.flush();}
  }

  /** Cached payload bytes may be reused; a retry is always a fresh transport identity. */
  public static List<Fragment> reidentify(List<Fragment> parts) {
    UUID id = UUID.randomUUID();
    return parts.stream().map(p -> new Fragment(id, p.index(), p.count(), p.bytes())).toList();
  }

  private static final class Pending {
    final long created;
    final byte[][] parts;
    int received, bytes;
    Pending(int count, long now) { parts = new byte[count][]; created = now; }
  }

  public static final class Assembler {
    public static final int MAX_PENDING = 16, MAX_BYTES = 16 * 1024 * 1024;
    private static final long EXPIRY = 30_000_000_000L;
    private final Map<UUID, Pending> pending = new LinkedHashMap<>();
    private final Map<UUID, Long> cancelled = new LinkedHashMap<>();
    private int bytes, discarded;

    public synchronized void clear() { pending.clear(); cancelled.clear(); bytes = discarded = 0; }
    public synchronized int retainedBytes() { return bytes; }
    public synchronized int pendingCount() { return pending.size(); }
    public synchronized Set<UUID> pendingIds() { return Set.copyOf(pending.keySet()); }
    public synchronized boolean cancelled(UUID id) { return cancelled.containsKey(id); }
    public synchronized int takeDiscarded() { int n = discarded; discarded = 0; return n; }
    public synchronized void cancel(UUID id, long now) {
      Pending old = pending.remove(id);
      if (old != null) bytes -= old.bytes;
      cancelled.put(id, now);
      while (cancelled.size() > 1024) cancelled.remove(cancelled.keySet().iterator().next());
    }
    public synchronized void expire(long now) {
      for (var it = pending.values().iterator(); it.hasNext();) {
        var old = it.next();
        if (now - old.created > EXPIRY) { bytes -= old.bytes; it.remove(); discarded++; }
      }
      cancelled.values().removeIf(t -> now - t > EXPIRY);
    }
    private void evict(UUID keep) {
      for (var it = pending.entrySet().iterator(); it.hasNext();) {
        var entry = it.next();
        if (entry.getKey().equals(keep)) continue;
        bytes -= entry.getValue().bytes; it.remove(); discarded++; return;
      }
      throw new IllegalArgumentException("道路分包超出组装预算");
    }
    public CompoundTag accept(Fragment part, long now) {
      byte[] packed = acceptBytes(part, now);
      return packed == null ? null : decode(packed);
    }
    public synchronized byte[] acceptBytes(Fragment part, long now) {
      expire(now);
      if (cancelled.containsKey(part.id())) return null;
      // Retain the arriving first fragment; eviction is reported to the inbox so
      // it can request the latest authoritative snapshots instead of losing a road.
      if (!pending.containsKey(part.id()) && pending.size() >= MAX_PENDING) evict(null);
      Pending p = pending.computeIfAbsent(part.id(), id -> new Pending(part.count(), now));
      if (p.parts.length != part.count()) throw new IllegalArgumentException("道路分包数量不一致");
      if (p.parts[part.index()] == null) {
        while ((long) bytes + part.bytes().length > MAX_BYTES) evict(part.id());
        p.parts[part.index()] = part.bytes();
        p.received++; p.bytes += part.bytes().length; bytes += part.bytes().length;
      } else if (!Arrays.equals(p.parts[part.index()], part.bytes())) {
        throw new IllegalArgumentException("道路重复分包内容不一致");
      }
      if (p.received != p.parts.length) return null;
      pending.remove(part.id()); bytes -= p.bytes;
      byte[] packed = new byte[p.bytes];
      int offset = 0;
      for (byte[] block : p.parts) { System.arraycopy(block, 0, packed, offset, block.length); offset += block.length; }
      return packed;
    }
  }
  public static CompoundTag decode(byte[] packed){
      try {
        var raw = new ByteArrayOutputStream();
        try (var gzip = new GZIPInputStream(new ByteArrayInputStream(packed))) {
          byte[] block = new byte[8192];
          for (int n; (n = gzip.read(block)) != -1; ) {
            if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("道路解码已取消");
            if (raw.size() + n > MAX_RAW) throw new IOException("inflated road data too large");
            raw.write(block, 0, n);
          }
        }
        return NbtIo.read(
            new DataInputStream(new ByteArrayInputStream(raw.toByteArray())),
            new NbtAccounter(32L * 1024 * 1024));
      } catch (java.util.concurrent.CancellationException e) {
        throw e;
      } catch (IOException | RuntimeException e) {
        throw new IllegalArgumentException("道路同步数据损坏", e);
      }
  }

  private RoadWire() {}
}
