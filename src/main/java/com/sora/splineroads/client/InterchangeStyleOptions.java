package com.sora.splineroads.client;

import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.RoadProfile.*;
import java.util.*;
import java.util.function.UnaryOperator;

/** The road-amenity pages edit the selected host axis, never a global road draft. */
final class InterchangeStyleOptions {
  static final String[] TITLES={"基本选项","人行道","桥梁 / 跨线桥","地下通道","龙门架 / 隔音板","道路 / 人行道路灯","绿化 / 包边","隔离带植树"};
  static final List<RoadInfrastructure.Bridge> BRIDGES=List.of(RoadInfrastructure.Bridge.STANDARD,RoadInfrastructure.Bridge.BEAM,RoadInfrastructure.Bridge.OVERPASS,RoadInfrastructure.Bridge.VIADUCT);
  private final InterchangeScreen screen;
  private final int x,y,w,row;
  private InterchangeStyleOptions(InterchangeScreen screen,int x,int y,int w,int row){this.screen=screen;this.x=x;this.y=y;this.w=w;this.row=row;}
  private Settings settings(){return screen.mainSettings();}
  private void options(UnaryOperator<Options> edit){screen.changeExtras(edit.apply(settings().options()));}
  private void infrastructure(UnaryOperator<RoadInfrastructure.Config> edit){options(o->o.infrastructure(edit.apply(o.infrastructure())));}
  private void street(UnaryOperator<RoadStreetscape.Config> edit){options(o->o.streetscape(edit.apply(o.streetscape())));}
  private void walk(UnaryOperator<RoadSidewalks.Config> edit){options(o->o.sidewalk(edit.apply(o.sidewalk())));}
  private void button(String text,int slot,Runnable action,boolean active){screen.styleButton(text,x,y+slot*row,w,action).active=active;}
  private void field(String label,String key,double value,int slot){screen.styleField(label,key,value,x,y+slot*row,w);}
  static void build(InterchangeScreen screen,int page,int x,int y,int w,int available){
    new InterchangeStyleOptions(screen,x,y,w,Math.min(27,Math.max(23,available/6))).build(page);
  }
  private void build(int page){
    var s=settings();var o=s.options();var c=RoadProfile.catalog(s);var infra=o.infrastructure();var street=o.streetscape();var walk=o.sidewalk();
    boolean ordinary=c.type()==Type.ORDINARY,walkAllowed=ordinary&&s.structure()!=Structure.TUNNEL;
    switch(page){
      case 0 -> {
        button(screen.leftTraffic()?"☑ 靠左行驶":"☐ 靠左行驶（默认靠右）",0,screen::toggleTraffic,true);
        button(RoadProfile.medianName(c.median())+" ▸",1,()->{
          var now=settings();var profile=RoadProfile.catalog(now);var choices=profile.type()==Type.HIGHWAY?List.of(Median.RAIL,Median.GREEN):List.of(Median.DOUBLE_YELLOW,Median.DASHED_YELLOW,Median.RAIL,Median.GREEN);
          screen.changeStyle(RoadLanes.carrier(profile.type(),RoadLanes.counts(now),choices.get((choices.indexOf(profile.median())+1)%choices.size())));
        },c.twoWay());
        button(o.cycleFinish().label+" ▸",2,()->options(v->v.cycleFinish(Options.CycleFinish.values()[(v.cycleFinish().ordinal()+1)%(s.structure()==Structure.TUNNEL?Options.CycleFinish.values().length-1:Options.CycleFinish.values().length)])),ordinary);
        button("非机动车分隔："+street.separator().label+" ▸",3,()->street(v->v.separator(RoadStreetscape.Separator.values()[(v.separator().ordinal()+1)%3])),ordinary&&o.cycle()&&!street.parking()&&s.structure()!=Structure.TUNNEL);
        button((o.curb()?"☑":"☐")+" 最外侧马路牙子",4,()->options(v->v.extras(v.cycle(),v.cycleRail(),!v.curb())),ordinary);
        button("高度与线形：由立交布局控制",5,()->{},false);
      }
      case 1 -> {
        button((walk.enabled()?"☑":"☐")+" 智能人行道",0,()->walk(v->v.enabled(!v.enabled())),walkAllowed);
        button("铺设位置："+walk.side().label+" ▸",1,()->walk(v->v.side(RoadSidewalks.Side.values()[(v.side().ordinal()+1)%3])),walkAllowed);
        screen.styleButton("−",x,y+2*row,26,()->walk(v->v.width(Math.max(1,v.width()-1)))).active=walkAllowed&&walk.width()>1;
        screen.styleButton("宽度 "+walk.width()+" 格",x+30,y+2*row,w-60,()->{}).active=false;
        screen.styleButton("+",x+w-26,y+2*row,26,()->walk(v->v.width(Math.min(15,v.width()+1)))).active=walkAllowed&&walk.width()<15;
        button("材质："+RoadSidewalks.Finish.of(walk.material()).label+" ▸",3,()->walk(v->v.material(RoadSidewalks.Finish.values()[(RoadSidewalks.Finish.of(v.material()).ordinal()+1)%RoadSidewalks.Finish.values().length].id)),walkAllowed);
        button("盲道："+(walk.tactile()?"开启":"关闭"),4,()->walk(v->v.tactile(!v.tactile())),walkAllowed&&walk.enabled()&&walk.width()>=2);
      }
      case 2 -> {
        button(s.structure()==Structure.BRIDGE?"☑ 启用桥梁":"启用桥梁",0,()->screen.mainSettings(settings().structure(s.structure()==Structure.BRIDGE?Structure.AUTO:Structure.BRIDGE)),true);
        button("桥型："+infra.bridge().label+" ▸",1,()->{infrastructure(v->v.bridge(BRIDGES.get((BRIDGES.indexOf(v.bridge())+1)%BRIDGES.size())));screen.mainSettings(settings().structure(Structure.BRIDGE));},true);
        field("桥墩跨度 / 格","bridgeSpan",infra.span(),2);
        button("主路高度：由端点调整页控制",4,()->{},false);
      }
      case 3 -> {
        button(s.structure()==Structure.TUNNEL?"☑ 启用隧道":"启用隧道",0,()->screen.mainSettings(settings().structure(s.structure()==Structure.TUNNEL?Structure.AUTO:Structure.TUNNEL)),true);
        button(infra.tunnel().label+" ▸",1,()->{infrastructure(v->v.tunnel(RoadInfrastructure.Tunnel.values()[(v.tunnel().ordinal()+1)%2]));screen.mainSettings(settings().structure(Structure.TUNNEL));},true);
        field("边缘净高 / 格","tunnelHeight",infra.headroom(),2);
        button("主路高度：由端点调整页控制",4,()->{},false);
      }
      case 4 -> {
        button("龙门架："+(infra.gantry()==RoadInfrastructure.Gantry.OFF?"关闭":"开启"),0,()->infrastructure(v->v.gantry(v.gantry()==RoadInfrastructure.Gantry.OFF?RoadInfrastructure.Gantry.FRAME:RoadInfrastructure.Gantry.OFF)),true);
        field("布设间距 / 格","gantrySpacing",infra.spacing(),1);
        button("高架外侧："+o.outerRail().label+" ▸",3,()->options(v->v.outerRail(OuterRail.values()[(v.outerRail().ordinal()+1)%OuterRail.values().length])),true);
      }
      case 5 -> {
        field("道路路灯间距 / 8–96 格","lampSpacing",street.lampSpacing(),0);
        button("人行道路灯："+(street.walkLamps()?"开启":"关闭"),2,()->street(v->v.walkLamps(!v.walkLamps())),RoadStreetscape.walkLampAllowed(o,s.structure(),s.style())&&ordinary);
        field("人行道灯距 / 8–96 格","walkLampSpacing",street.walkLampSpacing(),3);
      }
      case 6 -> {
        button("人行道绿化："+street.planting().label+" ▸",0,()->street(v->v.planting(RoadStreetscape.Planting.values()[(v.planting().ordinal()+1)%RoadStreetscape.Planting.values().length])),walkAllowed&&walk.enabled());
        field("绿化间距 / 8–64 格","plantingSpacing",street.plantingSpacing(),1);
        button("道路包边："+street.apron().label+" ▸",3,()->street(v->v.apron(RoadStreetscape.Apron.values()[(v.apron().ordinal()+1)%3])),true);
      }
      case 7 -> {
        var plants=street.plantings();
        button("中央："+plants.medianTrees().label+" ▸",0,()->street(v->v.plantings(v.plantings().medianTrees(RoadStreetscape.Planting.values()[(v.plantings().medianTrees().ordinal()+1)%5]))),c.twoWay()&&c.median()==Median.GREEN&&plants.greenWidth()>=1.5);
        field("植树间距 / 8–64 格","medianTreeSpacing",plants.medianSpacing(),1);
        button("非机动车隔离："+plants.separatorTrees().label+" ▸",3,()->street(v->v.plantings(v.plantings().separatorTrees(RoadStreetscape.Planting.values()[(v.plantings().separatorTrees().ordinal()+1)%5]))),ordinary&&o.cycle()&&!street.parking()&&street.separator()==RoadStreetscape.Separator.GREEN);
        field("植树间距 / 8–64 格","separatorTreeSpacing",plants.separatorSpacing(),4);
      }
    }
  }
  static Options capture(Options o,java.util.function.BiFunction<String,Double,Double> number){
    var i=o.infrastructure();i=i.span(number.apply("bridgeSpan",i.span())).headroom(number.apply("tunnelHeight",i.headroom())).spacing(number.apply("gantrySpacing",i.spacing()));
    var c=o.streetscape();var p=c.plantings();p=p.railGap(number.apply("railGap",p.railGap())).greenWidth(number.apply("greenWidth",p.greenWidth())).medianSpacing(number.apply("medianTreeSpacing",p.medianSpacing())).separatorSpacing(number.apply("separatorTreeSpacing",p.separatorSpacing()));
    c=c.plantings(p).lampSpacing(number.apply("lampSpacing",c.lampSpacing())).walkLampSpacing(number.apply("walkLampSpacing",c.walkLampSpacing())).plantingSpacing(number.apply("plantingSpacing",c.plantingSpacing()));
    return o.infrastructure(i).streetscape(c);
  }
}
