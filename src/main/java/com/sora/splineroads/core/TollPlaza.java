package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.RoadProfile.*;
import java.util.List;

/** Physical A/B identities never change when the one-way traffic direction changes. */
public final class TollPlaza {
  public enum Adjust { NONE, AB, A, B }
  public record Direction(int aLanes,int bLanes,int etc,int manual) {
    public Direction {
      if(aLanes<1||aLanes>7||bLanes<1||bLanes>7)throw new IllegalArgumentException("收费站每端每方向须为 1–7 车道");
      if(etc<0||manual<0||etc+manual<1||etc+manual>7)throw new IllegalArgumentException("每方向 ETC 与人工通道合计须为 1–7");
    }
    public int units(){return etc+manual;}
    public int lanes(boolean a){return a?aLanes:bLanes;}
  }
  public record Config(boolean twoWay,boolean reverse,boolean leftTraffic,boolean highway,
      Direction forward,Direction backward,double laneWidth,double passageWidth,double clearHeight,Structure structure,String name,int chargedDirections) {
    public static final Config DEFAULT=new Config(false,false,false,false,new Direction(2,2,1,2),new Direction(2,2,1,2),4,5,6,Structure.AUTO);
    public Config(boolean twoWay,boolean reverse,boolean leftTraffic,boolean highway,Direction forward,Direction backward,double laneWidth,double passageWidth,double clearHeight,Structure structure,String name){this(twoWay,reverse,leftTraffic,highway,forward,backward,laneWidth,passageWidth,clearHeight,structure,name,twoWay?3:1);}
    public Config(boolean twoWay,boolean reverse,boolean leftTraffic,boolean highway,Direction forward,Direction backward,double laneWidth,double passageWidth,double clearHeight,Structure structure){this(twoWay,reverse,leftTraffic,highway,forward,backward,laneWidth,passageWidth,clearHeight,structure,"");}
    public Config {
      if(chargedDirections<1||chargedDirections>3||!twoWay&&chargedDirections!=1)throw new IllegalArgumentException("收费方向无效");
      name=name==null?"":name.strip();
      if(name.codePointCount(0,name.length())>40||name.codePoints().anyMatch(c->Character.isISOControl(c)||c==0x00a7))throw new IllegalArgumentException("收费站名称最多40个字符，不能包含换行或格式控制符");
      if(forward==null||backward==null||structure==null||!RoadGeometry.finite(laneWidth,passageWidth,clearHeight)
          ||laneWidth<2||laneWidth>5||passageWidth<4||passageWidth>7||clearHeight<6||clearHeight>10)
        throw new IllegalArgumentException("接口车道宽 2–5、收费通道净宽 4–7、最低净高 6–10 格");
      if(structure==Structure.TUNNEL)throw new IllegalArgumentException("本版收费站支持地面与高架，不支持封闭隧道站");
    }
    public Config named(String value){return new Config(twoWay,reverse,leftTraffic,highway,forward,backward,laneWidth,passageWidth,clearHeight,structure,value,chargedDirections);}
    public int units(){return forward.units()+(twoWay?backward.units():0);}
    public boolean charges(int direction){return (chargedDirections&(1<<direction))!=0;}
    public Config direction(boolean both,boolean reversed){return new Config(both,reversed,leftTraffic,highway,forward,backward,laneWidth,passageWidth,clearHeight,structure,name);}
  }
  public record Spec(Node a,Node b,Config config,Settings portA,Settings portB,List<Sample> path,boolean rigidCore,boolean curvedDeck) {
    public Spec(Node a,Node b,Config config,Settings portA,Settings portB,List<Sample> path,boolean rigidCore){this(a,b,config,portA,portB,path,rigidCore,false);}
    public Spec(Node a,Node b,Config config,Settings portA,Settings portB,List<Sample> path){this(a,b,config,portA,portB,path,true,true);}
    public Spec(Node a,Node b,Config config,Settings portA,Settings portB){this(a,b,config,portA,portB,List.of());}
    public Spec(Node a,Node b,Config config){this(a,b,config,null,null);}
    public Spec {if(a==null||b==null||config==null)throw new IllegalArgumentException("收费站端点缺失");path=List.copyOf(path);if(!path.isEmpty()&&(path.size()<2||path.size()>RoadLimits.MAX_SAMPLES))throw new IllegalArgumentException("收费站断面路径无效");}
  }
  public static Settings port(Spec s,boolean a){
    var bound=a?s.portA():s.portB();var c=s.config();
    var counts=new RoadLanes.Counts(c.forward().lanes(a),c.twoWay()?c.backward().lanes(a):0);
    // A disconnected station end remains editable. A previously bound section must
    // not freeze old lane counts/width when its outside road has been removed.
    if(bound==null||!RoadLanes.counts(bound).equals(counts)||bound.options().leftTraffic()!=c.leftTraffic()
        ||Math.abs(RoadProfile.layout(bound,bound.width()).laneWidth()-c.laneWidth())>1e-6)return port(c,a);
    return bound;
  }
  public static Settings port(Config c,boolean a) {
    var counts=new RoadLanes.Counts(c.forward().lanes(a),c.twoWay()?c.backward().lanes(a):0);
    var type=c.highway()?Type.HIGHWAY:Type.ORDINARY;
    var style=RoadLanes.carrier(type,counts,c.twoWay()?Median.RAIL:Median.NONE);
    var options=Options.DEFAULT.traffic(c.leftTraffic()).lanes(counts).cycleFinish(Options.CycleFinish.NONE)
        .sidewalk(RoadSidewalks.Config.DEFAULT.enabled(false));
    var result=new Settings(Mode.STRAIGHT,style,RoadProfile.width(style,options,c.laneWidth()),1,.35,90).options(options).structure(c.structure());
    var l=RoadProfile.layout(result,result.width());boolean reverse=!c.twoWay()&&c.reverse();double sign=reverse?-1:1;
    var port=new RoadTransitions.Port(reverse?-l.motorMax():l.motorMin(),reverse?-l.motorMin():l.motorMax(),l.median(),l.dividers().stream().map(d->d*sign).sorted().toList(),0,0,sign*l.medianCenter());
    return result.options(options.ends(RoadTransitions.Ends.NONE.port(port)));
  }
  private TollPlaza(){}
}
