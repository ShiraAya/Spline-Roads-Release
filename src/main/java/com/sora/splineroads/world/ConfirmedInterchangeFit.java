package com.sora.splineroads.world;
import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.InterchangePlanner.*;
import java.util.*;
import net.minecraft.nbt.*;

/** Accept only a preview's fixed endpoints within the original fitting domain.
 * This does not authorize writes: geometry, source partitions and the normal
 * world transaction must still validate before any marker or terrain changes. */
final class ConfirmedInterchangeFit {
  static boolean eligible(CompoundTag source){
    int n=source.getList("Nodes",Tag.TAG_COMPOUND).size();
    return n>=3&&n<=4&&!source.contains("Corridor")&&!ParallelSpui.present(source)
        &&!HostAxes.retiredInterchange(source)&&HostAxes.read(source).stream().allMatch(axis->axis.flat()&&!axis.curved());
  }
  static CompoundTag candidate(CompoundTag source,CompoundTag command){
    Node[] original=HostAxes.nodes(source);int n=original.length;
    if(!eligible(source))
      throw new IllegalArgumentException("此组合不支持固定端点复核，请重新打开预览");
    if(!command.getList("SourceNodes",Tag.TAG_COMPOUND).equals(source.getList("Nodes",Tag.TAG_COMPOUND))
        ||!command.contains("SourceAxesHash",Tag.TAG_INT)||command.getInt("SourceAxesHash")!=HostAxes.signature(source))
      throw new IllegalArgumentException("端点或主路曲线已变化，请重新打开立交预览");
    var tags=command.getList("FittedNodes",Tag.TAG_COMPOUND);
    if(tags.size()!=n)throw new IllegalArgumentException("预览端点数量不一致");
    Node[] target=tags.stream().map(v->RoadRecord.readNode((CompoundTag)v)).toArray(Node[]::new);
    Options options=Interchanges.read(source.getCompound("Options"));
    if(!options.adjustEndpoints()&&!Arrays.equals(original,target))throw new IllegalArgumentException("未允许调整端点");
    Node[] available=ContinuousRoads.effectiveLimits(source);
    V center=ContinuousFit.center(original,options);
    double maximum=options.preset()==Preset.DOUBLE_TRUMPET?2048:1024;
    for(int i=0;i<n;i++){
      var node=target[i];V p=node.position();
      if(!RoadGeometry.finite(p.x(),p.y(),p.z(),node.yaw(),node.grade()))throw new IllegalArgumentException("预览端点不是有限坐标");
      if(!onRay(original[i],node,center,maximum)&&!onRay(available[i],node,center,maximum))
        throw new IllegalArgumentException("预览端点超出主路方向或允许调整范围");
      if(!orientation(original[i],node)&&!orientation(available[i],node))
        throw new IllegalArgumentException("预览不能改变主路端点方向或坡度");
      int axis=i/2;boolean captured=ContinuousRoads.sources(source).stream().anyMatch(item->item.axis()==axis);
      // ContinuousFit may cut an occupied interval out of an existing source,
      // preserving its outside road. Without that source, allowShrink=false
      // must keep the original physical endpoint or extend it.
      if(!options.allowShrink()&&!captured&&p.sub(center).horizontalLength()+1e-6<original[i].position().sub(center).horizontalLength())
        throw new IllegalArgumentException("未允许缩短主路端点范围");
    }
    var a=RoadRecord.readSettings(source.getCompound("Main1"));var b=RoadRecord.readSettings(source.getCompound("Main2"));
    // These are exactly the vertical alternatives used by InterchangeFit and
    // ContinuousFit: authored heights, leveled originals, or leveled usable ends.
    if(!sameHeights(target,original)&&!sameHeights(target,available)
        &&!sameHeights(target,leveled(original,a,b,options))
        &&!sameHeights(target,leveled(available,a,b,options.shrink(false))))
      throw new IllegalArgumentException("预览端点高程超出允许的主路调整");
    var fixed=source.copy();fixed.put("Nodes",tags.copy());fixed.put("Options",Interchanges.write(options.adjust(false)));
    fixed.remove("HostAxes");fixed.remove("ResolvedFit");return fixed;
  }
  private static boolean orientation(Node reference,Node target){
    return Math.abs(target.yaw()-reference.yaw())<=1e-7
        &&(Math.abs(target.grade()-reference.grade())<=1e-7||Math.abs(target.grade())<=1e-7);
  }
  private static boolean onRay(Node source,Node target,V center,double maximum){
    V ray=source.position().sub(center),delta=target.position().sub(center);double length=ray.horizontalLength();
    if(length<1e-6)return false;V direction=ray.horizontalUnit();double at=delta.dot(direction);
    return at>0&&at<=Math.max(maximum,length)+1e-6&&Math.abs(delta.dot(direction.left()))<=1e-6;
  }
  private static boolean sameHeights(Node[] a,Node[] b){for(int i=0;i<a.length;i++)if(Math.abs(a[i].position().y()-b[i].position().y())>1e-7)return false;return true;}
  private static Node[] leveled(Node[] original,Settings a,Settings b,Options options){
    Node[] expanded=InterchangePlanner.expand(original,options.adjust(false));
    double ya=(expanded[0].position().y()+expanded[1].position().y())/2,yb=(expanded[2].position().y()+expanded[3].position().y())/2;
    boolean abUpper=Math.abs(ya-yb)<1e-6?options.upper()==1:ya>yb;
    double drop=options.allowShrink()&&!options.preset().three?options.maxLowering():0;
    double upper=Math.max(Math.min(ya,yb)+InterchangePlanner.minimumDifference(a,b,options.adjust(false)),Math.max(ya,yb)-drop);
    if(options.flatConnector())upper=Math.max(upper,options.connectorHeight());
    var result=original.clone();for(int i=0;i<original.length;i++){
      if((i<2)!=abUpper)continue;double y=upper;for(int j=i<2?0:2;j<Math.min(original.length,i<2?2:4);j++)y=Math.max(y,original[j].position().y()-drop);
      var p=original[i].position();result[i]=new Node(new V(p.x(),y,p.z()),original[i].yaw(),0);
    }return result;
  }
  private ConfirmedInterchangeFit(){}
}
