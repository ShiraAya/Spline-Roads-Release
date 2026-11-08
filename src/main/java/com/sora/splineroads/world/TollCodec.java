package com.sora.splineroads.world;
import com.sora.splineroads.core.TollPlaza.*;
import com.sora.splineroads.core.RoadGeometry.Structure;
import com.sora.splineroads.core.RoadGeometry.*;
import net.minecraft.nbt.*;
import java.util.*;

/** Strict versioned authored parameters; generated collision geometry is owned by RoadRecord. */
public final class TollCodec {
  /** Standalone mode charges all selected directions; inline stations retain their explicit mask. */
  public static void standaloneMode(CompoundTag t,boolean twoWay){t.putBoolean("TwoWay",twoWay);t.putInt("ChargedDirections",twoWay?3:1);}
  public static CompoundTag write(Spec spec){var t=config(spec.config());t.putInt("Version",spec.config().twoWay()&&spec.config().chargedDirections()!=3?7:spec.path().isEmpty()?3:spec.curvedDeck()?6:spec.rigidCore()?5:4);if(spec.portA()!=null)t.put("PortA",RoadRecord.writeSettings(spec.portA()));if(spec.portB()!=null)t.put("PortB",RoadRecord.writeSettings(spec.portB()));t.put("A",RoadRecord.writeNode(spec.a()));t.put("B",RoadRecord.writeNode(spec.b()));if(!spec.path().isEmpty()){var path=new ListTag();for(var s:spec.path()){var p=new ListTag();for(double v:new double[]{s.center().x(),s.center().y(),s.center().z(),s.left().x(),s.left().z(),s.distance(),s.halfWidth()})p.add(DoubleTag.valueOf(v));path.add(p);}t.put("Path",path);}return t;}
  public static Spec read(CompoundTag t){if(t.getInt("Version")<1||t.getInt("Version")>7)throw new IllegalArgumentException("收费站数据版本无效");var path=new ArrayList<Sample>();var list=t.getList("Path",Tag.TAG_LIST);if(list.size()>com.sora.splineroads.core.RoadLimits.MAX_SAMPLES)throw new IllegalArgumentException("收费站路径过大");for(var v:list){var p=(ListTag)v;if(p.size()!=7)throw new IllegalArgumentException("收费站路径数据无效");path.add(new Sample(new V(p.getDouble(0),p.getDouble(1),p.getDouble(2)),new V(p.getDouble(3),0,p.getDouble(4)),p.getDouble(5),p.getDouble(6)));}return new Spec(RoadRecord.readNode(t.getCompound("A")),RoadRecord.readNode(t.getCompound("B")),config(t),t.contains("PortA")?RoadRecord.readSettings(t.getCompound("PortA")):null,t.contains("PortB")?RoadRecord.readSettings(t.getCompound("PortB")):null,path,path.isEmpty()||t.getInt("Version")>=5,path.isEmpty()||t.getInt("Version")>=6);}
  public static CompoundTag config(Config c){var t=new CompoundTag();t.putBoolean("TwoWay",c.twoWay());t.putBoolean("Reverse",c.reverse());t.putBoolean("LeftTraffic",c.leftTraffic());t.putBoolean("Highway",c.highway());t.put("Forward",direction(c.forward()));t.put("Backward",direction(c.backward()));t.putDouble("LaneWidth",c.laneWidth());t.putDouble("PassageWidth",c.passageWidth());t.putDouble("ClearHeight",c.clearHeight());t.putString("Structure",c.structure().name());t.putString("Name",c.name());t.putInt("ChargedDirections",c.chargedDirections());return t;}
  public static Config config(CompoundTag t){return new Config(t.getBoolean("TwoWay"),t.getBoolean("Reverse"),t.getBoolean("LeftTraffic"),t.getBoolean("Highway"),direction(t.getCompound("Forward")),direction(t.getCompound("Backward")),t.getDouble("LaneWidth"),t.getDouble("PassageWidth"),t.getDouble("ClearHeight"),Structure.valueOf(t.getString("Structure")),t.getString("Name"),t.contains("ChargedDirections")?t.getInt("ChargedDirections"):t.getBoolean("TwoWay")?3:1);}
  private static CompoundTag direction(Direction d){var t=new CompoundTag();t.putInt("A",d.aLanes());t.putInt("B",d.bLanes());t.putInt("ETC",d.etc());t.putInt("Manual",d.manual());return t;}
  private static Direction direction(CompoundTag t){return new Direction(t.getInt("A"),t.getInt("B"),t.getInt("ETC"),t.getInt("Manual"));}
  private TollCodec(){}
}
