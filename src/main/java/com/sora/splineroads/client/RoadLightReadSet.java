package com.sora.splineroads.client;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.util.Arrays;

/** The exact inputs used by RoadLighting, retained without block-position objects.
 * Light-engine storage can change without changing a sampled value, and queued
 * light data can change before the values visible to the renderer do. */
final class RoadLightReadSet {
  static final int PACKED_MASK=0x00ffffff,OPAQUE=1<<24,READ_OPAQUE=1<<25,READ_PACKED=1<<26;
  static final int UNAVAILABLE_OPAQUE=1<<27,UNAVAILABLE_PACKED=1<<28;
  static final RoadLightReadSet EMPTY=new RoadLightReadSet(new long[0],new int[0],false);
  interface Access { int sample(long position,int requested); }
  private final long[] positions;
  private final int[] values;
  private final boolean inconsistent;
  private RoadLightReadSet(long[] positions,int[] values,boolean inconsistent){
    this.positions=positions;this.values=values;this.inconsistent=inconsistent;
  }
  int size(){return positions.length;}
  long bytes(){return positions.length==0?0:64L+12L*positions.length;}
  /** A bounded sweep of this immutable upload's actual inputs. Keeping the
   * cursor with its owner prevents a large stable region from restarting at
   * position zero or starving another region's opaque-only inputs. */
  Cursor cursor(){return new Cursor();}
  final class Cursor {
    private int index;
    private boolean changed=inconsistent;
    int advance(Access access,int allowance){
      int read=0;
      while(!changed&&index<positions.length&&read<Math.max(0,allowance)){
        changed=changedAt(index++,access);read++;
      }
      return read;
    }
    boolean complete(){return changed||index==positions.length;}
    boolean changed(){return changed;}
  }
  private boolean changedAt(int index,Access access){
    int requested=values[index]&(READ_OPAQUE|READ_PACKED);
    int actual=access.sample(positions[index],requested);
    int mask=((requested&READ_OPAQUE)!=0&&(actual&UNAVAILABLE_OPAQUE)==0?OPAQUE:0)
        |((requested&READ_PACKED)!=0&&(actual&UNAVAILABLE_PACKED)==0?PACKED_MASK:0);
    return (actual&mask)!=(values[index]&mask);
  }
  boolean changed(Access access){
    // A multi-frame upload may observe an opacity change while it is being
    // packed. Its published geometry must be refreshed even if the last read
    // happens to match the current world again.
    if(inconsistent)return true;
    for(int i=0;i<positions.length;i++){
      // A missing client chunk supplies default air/light, not a world change.
      // Check every available input independently: one distant missing probe
      // must not suppress a real torch or opacity update in this GPU region.
      if(changedAt(i,access))return true;
    }
    return false;
  }
  private int remembered(long position,int requested,int otherwise){
    int index=Arrays.binarySearch(positions,position);
    return index>=0&&(values[index]&requested)!=0?values[index]:otherwise;
  }
  static final class Builder {
    private final Long2IntOpenHashMap values=new Long2IntOpenHashMap();
    private final RoadLightReadSet previous;
    private boolean inconsistent;
    Builder(){this(EMPTY);}
    Builder(RoadLightReadSet previous){this.previous=previous;}
    private int remembered(long position,int requested,int otherwise){
      int present=values.get(position);
      return (present&requested)!=0?present:previous.remembered(position,requested,otherwise);
    }
    boolean opaque(long position,boolean value,boolean available){
      if(!available)value=(remembered(position,READ_OPAQUE,value?OPAQUE:0)&OPAQUE)!=0;
      opaque(position,value);return value;
    }
    int packed(long position,int value,boolean available){
      if(!available)value=remembered(position,READ_PACKED,value)&PACKED_MASK;
      packed(position,value);return value;
    }
    void opaque(long position,boolean opaque){
      int before=values.get(position),value=opaque?OPAQUE:0;
      if((before&READ_OPAQUE)!=0){
        if((before&OPAQUE)!=value){inconsistent=true;values.put(position,(before&~OPAQUE)|value);}return;
      }
      values.put(position,before|READ_OPAQUE|value);
    }
    void packed(long position,int packed){
      int before=values.get(position);
      if((before&READ_PACKED)!=0){
        if((before&PACKED_MASK)!=packed){inconsistent=true;values.put(position,(before&~PACKED_MASK)|packed);}return;
      }
      values.put(position,before|READ_PACKED|packed);
    }
    RoadLightReadSet build(){
      if(values.isEmpty())return EMPTY;
      var positions=values.keySet().toLongArray();Arrays.sort(positions);var samples=new int[positions.length];
      for(int i=0;i<positions.length;i++)samples[i]=values.get(positions[i]);
      return new RoadLightReadSet(positions,samples,inconsistent);
    }
  }
}
