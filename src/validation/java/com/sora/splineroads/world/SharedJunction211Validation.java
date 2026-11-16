package com.sora.splineroads.world;
import java.io.*;import java.util.*;import net.minecraft.nbt.*;import com.sora.splineroads.net.RoadWire;

/** Packet sharing must preserve every persisted field and retain wire budgets. */
public final class SharedJunction211Validation {
 static long size(Tag tag)throws Exception {var out=new OutputStream(){long n;public void write(int b){n++;}public void write(byte[] b,int o,int l){n+=l;}};tag.write(new DataOutputStream(out));return out.n;}
 static CompoundTag legacy(List<RoadRecord> records){var packet=new CompoundTag();var list=new ListTag();for(var r:records)list.add(r.save());packet.put("Roads",list);return packet;}
 static void same(List<RoadRecord> records,CompoundTag packet){var next=RoadSnapshotCodec.read(packet);if(next.size()!=records.size())throw new AssertionError("record count");for(int i=0;i<next.size();i++)if(!records.get(i).save().equals(next.get(i).save()))throw new AssertionError("snapshot changed "+records.get(i).id());}
 static void reject(Runnable operation){try{operation.run();throw new AssertionError("invalid index accepted");}catch(IllegalArgumentException expected){}}
 public static void main(String[] args)throws Exception {
  System.setProperty("inspection.world",args[0]);var data=Followup211Inspection.load();var groups=new LinkedHashMap<UUID,List<RoadRecord>>();
  for(var built:data.index.roads.values()){var r=built.record;groups.computeIfAbsent(r.assembly()==null?r.id():r.assembly(),k->new ArrayList<>()).add(r);}
  long oldBytes=0,newBytes=0;int count=0,shared=0,wire=0;CompoundTag sample=null;
  for(var records:groups.values()){
   var old=legacy(records);var next=new CompoundTag();RoadSnapshotCodec.write(next,records);same(records,next);same(records,old);
   long before=size(old),after=size(next);oldBytes+=before;newBytes+=after;count+=records.size();
   if(next.contains("JunctionSpecs")){shared++;sample=next;System.out.println("GROUP records="+records.size()+" specs="+next.getList("JunctionSpecs",Tag.TAG_COMPOUND).size()+" old_raw="+before+" shared_raw="+after);}
   if(after<RoadWire.MAX_RAW){var assembler=new RoadWire.Assembler();CompoundTag restored=null;for(var fragment:RoadWire.split(next))restored=assembler.accept(fragment,1);same(records,restored);wire++;}
  }
  if(shared==0||sample==null)throw new AssertionError("no shared fixtures");
  var invalid=sample.copy();invalid.getList("Roads",Tag.TAG_COMPOUND).getCompound(0).getCompound("Junction").putInt("SpecIndex",-1);reject(()->RoadSnapshotCodec.read(invalid));
  invalid.getList("Roads",Tag.TAG_COMPOUND).getCompound(0).getCompound("Junction").putInt("SpecIndex",Integer.MAX_VALUE);reject(()->RoadSnapshotCodec.read(invalid));
  invalid.getList("Roads",Tag.TAG_COMPOUND).getCompound(0).getCompound("Junction").putString("SpecIndex","0");reject(()->RoadSnapshotCodec.read(invalid));
  var oversized=new CompoundTag();oversized.putByteArray("TooLarge",new byte[RoadWire.MAX_RAW+1]);reject(()->RoadWire.split(oversized));
  System.out.println("PASS exact_saved_roads="+count+" shared_groups="+shared+" wire_roundtrips="+wire+" old_raw_bytes="+oldBytes+" shared_raw_bytes="+newBytes+" invalid_cases=4");
 }
}
