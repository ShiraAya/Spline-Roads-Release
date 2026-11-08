package com.sora.splineroads.core;

/** User settings, independent of the imported MTR track and its identity. */
public record RailbedOptions(boolean leftRail, boolean rightRail, boolean catenary,
    double poleSpacing, double wireHeight, int singleSide, RoadInfrastructure.Bridge bridge,
    RoadInfrastructure.Tunnel tunnel, Fence fence) {
  public enum Fence { SOLID("混凝土护栏"), MESH("铁路护栏网"); public final String label; Fence(String label){this.label=label;} }
  public RailbedOptions(boolean left,boolean right,boolean catenary,double spacing,double height,int side){this(left,right,catenary,spacing,height,side,RoadInfrastructure.Bridge.STANDARD,RoadInfrastructure.Tunnel.BOX,Fence.SOLID);}
  public RailbedOptions flags(boolean left,boolean right,boolean wire){return new RailbedOptions(left,right,wire,poleSpacing,wireHeight,singleSide,bridge,tunnel,fence);}
  public RailbedOptions wire(double spacing,double height,int side){return new RailbedOptions(leftRail,rightRail,catenary,spacing,height,side,bridge,tunnel,fence);}
  public RailbedOptions styles(RoadInfrastructure.Bridge bridge,RoadInfrastructure.Tunnel tunnel,Fence fence){return new RailbedOptions(leftRail,rightRail,catenary,poleSpacing,wireHeight,singleSide,bridge,tunnel,fence);}
  public static final RailbedOptions DEFAULT = new RailbedOptions(true,true,false,24,6.5,1);
  public RailbedOptions {
    if(bridge==null||tunnel==null||fence==null||!java.util.Set.of(RoadInfrastructure.Bridge.STANDARD,RoadInfrastructure.Bridge.ARCH,RoadInfrastructure.Bridge.CABLE,RoadInfrastructure.Bridge.SUSPENSION).contains(bridge)||tunnel==RoadInfrastructure.Tunnel.ARCH)throw new IllegalArgumentException("无效的铁路桥隧样式");
    if(!Double.isFinite(poleSpacing)||poleSpacing<8||poleSpacing>64)
      throw new IllegalArgumentException("接触网间距需为 8–64 米");
    if(!Double.isFinite(wireHeight)||wireHeight<6.1||wireHeight>12)
      throw new IllegalArgumentException("接触网高度需为 6.1–12 米");
    if(singleSide!=-1&&singleSide!=1)throw new IllegalArgumentException("单轨接触网侧别无效");
  }
  public boolean rail(int side){return side>0?leftRail:rightRail;}
}
