package com.sora.splineroads.world;
import com.sora.splineroads.core.SmartSigns;
import com.sora.splineroads.core.SmartSigns.*;
import com.sora.splineroads.core.RoadGeometry.V;
import net.minecraft.nbt.*;
import java.util.*;

public final class SmartSignCodec {
  public static CompoundTag key(Key k){var t=new CompoundTag();t.putUUID("Road",k.road());t.putInt("Slot",k.slot());t.putInt("Flow",k.flow());return t;}
  public static Key key(CompoundTag t){return new Key(t.getUUID("Road"),t.getInt("Slot"),t.getInt("Flow"));}
  public static CompoundTag text(Text v){var t=new CompoundTag();t.putString("Value",v.value());t.putInt("Size",v.size());t.putBoolean("Wrap",v.wrap());return t;}
  public static Text text(CompoundTag t){return new Text(t.getString("Value"),t.getInt("Size"),t.getBoolean("Wrap"));}
  private static CompoundTag words(Map<String,Text> map){var t=new CompoundTag();map.forEach((k,v)->t.put(k,text(v)));return t;}
  private static Map<String,Text> words(CompoundTag t){if(t.getAllKeys().size()>SmartSigns.MAX_FIELDS)throw new IllegalArgumentException("文字区域过多");var out=new HashMap<String,Text>();for(String k:t.getAllKeys())out.put(k,text(t.getCompound(k)));return out;}
  public static CompoundTag write(State s){var t=key(s.key());t.putInt("Version",SmartSigns.VERSION);t.putInt("LayoutVersion",2);t.putUUID("GantryId",s.key().id());t.putString("Mode",s.mode().name());t.putString("Entry",s.entry());if(s.target()!=null)t.putUUID("Target",s.target());t.putString("Diagnostic",s.diagnostic());t.putLong("Revision",s.revision());t.put("Text",words(s.text()));var hidden=new ListTag();s.hiddenHeaders().stream().sorted().forEach(v->hidden.add(StringTag.valueOf(v)));t.put("HiddenHeaders",hidden);var accessories=new ListTag();s.hiddenAccessories().stream().sorted().forEach(v->accessories.add(StringTag.valueOf(v)));t.put("HiddenAccessories",accessories);var closed=new ListTag();s.closedLanes().stream().sorted().forEach(v->closed.add(StringTag.valueOf(v)));t.put("ClosedLanes",closed);var history=new ListTag();s.drafts().forEach((k,v)->{var h=new CompoundTag();h.putString("Target",k);h.put("Text",words(v));history.add(h);});t.put("Drafts",history);
    var bs=new ListTag();for(var b:s.branches()){var n=new CompoundTag();n.putString("Id",b.id());n.putIntArray("Lanes",b.lanes().stream().mapToInt(Integer::intValue).toArray());n.putInt("Side",b.side());n.putBoolean("Dedicated",b.dedicated());var path=new ListTag();for(V p:b.path()){var v=new CompoundTag();v.putDouble("X",p.x());v.putDouble("Y",p.y());v.putDouble("Z",p.z());path.add(v);}n.put("Path",path);bs.add(n);}t.put("Branches",bs);if(s.support()!=null){var a=new CompoundTag();a.put("Foot",vector(s.support().foot()));a.put("Right",vector(s.support().right()));a.put("Front",vector(s.support().front()));a.putBoolean("Paired",s.support().paired());a.putInt("CantileverSide",s.support().cantileverSide());if(s.support().gantrySpan()!=0)a.putDouble("GantrySpan",s.support().gantrySpan());if(s.support().boardCenter()!=null)a.putDouble("BoardCenter",s.support().boardCenter());t.put("Support",a);}return t;
  }
  public static State read(CompoundTag t){if(t.getInt("Version")>SmartSigns.VERSION)throw new IllegalArgumentException("智能标牌数据版本过新");var bs=new ArrayList<Branch>();var list=t.getList("Branches",Tag.TAG_COMPOUND);if(list.size()>12)throw new IllegalArgumentException("分支过多");for(Tag v:list){var b=(CompoundTag)v;var path=new ArrayList<V>();var ps=b.getList("Path",Tag.TAG_COMPOUND);if(ps.size()>128)throw new IllegalArgumentException("结构示意过大");for(Tag p:ps){var n=(CompoundTag)p;path.add(new V(n.getDouble("X"),n.getDouble("Y"),n.getDouble("Z")));}bs.add(new Branch(b.getString("Id"),Arrays.stream(b.getIntArray("Lanes")).boxed().toList(),b.getInt("Side"),b.getBoolean("Dedicated"),path));}
    var drafts=new LinkedHashMap<String,Map<String,Text>>();var ds=t.getList("Drafts",Tag.TAG_COMPOUND);if(ds.size()>8)throw new IllegalArgumentException("草稿过多");for(Tag v:ds){var d=(CompoundTag)v;drafts.put(d.getString("Target"),words(d.getCompound("Text")));}
    var state=new State(key(t),Mode.valueOf(t.getString("Mode")),t.hasUUID("Target")?t.getUUID("Target"):null,t.getString("Diagnostic"),t.getLong("Revision"),bs,words(t.getCompound("Text")),drafts,t.contains("Entry")?t.getString("Entry"):Integer.toString(t.getInt("Flow")),t.contains("Support")?support(t.getCompound("Support")):null,hidden(t,"HiddenHeaders"),hidden(t,"HiddenAccessories"),hidden(t,"ClosedLanes"));
    return t.getInt("LayoutVersion")<2?SmartSigns.unifiedHeader(state):state;
  }
  private static Set<String> hidden(CompoundTag t,String key){var list=t.getList(key,Tag.TAG_STRING);if(list.size()>SmartSigns.MAX_FIELDS)throw new IllegalArgumentException("道路标识区状态过多");var out=new HashSet<String>();for(int i=0;i<list.size();i++)out.add(list.getString(i));return out;}
  private static CompoundTag vector(V p){var t=new CompoundTag();t.putDouble("X",p.x());t.putDouble("Y",p.y());t.putDouble("Z",p.z());return t;}
  private static V vector(CompoundTag t){return new V(t.getDouble("X"),t.getDouble("Y"),t.getDouble("Z"));}
  private static Support support(CompoundTag t){return new Support(vector(t.getCompound("Foot")),vector(t.getCompound("Right")),vector(t.getCompound("Front")),t.getBoolean("Paired"),t.getInt("CantileverSide"),t.getDouble("GantrySpan"),t.contains("BoardCenter")?t.getDouble("BoardCenter"):null);}
  private SmartSignCodec(){}
}
