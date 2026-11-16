package com.sora.splineroads.world;

import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.nbt.CompoundTag;

/** Reproducible sketch fixture. Invoked only by the opt-in isolated benchmark. */
public final class Performance211Scene {
  public record Step(String name, Runnable action) {}
  private final ServerLevel level;
  private final RoadData data;
  private final double ground;
  private final Map<String,UUID> roads=new LinkedHashMap<>();
  private final List<Step> steps=new ArrayList<>();
  private final Map<String,CompoundTag> designs=new HashMap<>();
  public Performance211Scene(ServerLevel level) {
    this.level=level; data=RoadData.get(level);level.getChunkAt(BlockPos.ZERO);
    // Redstone Ready 1.20.1 surface is Y=56. A resumed run must not mistake an
    // already-built upper deck at the origin for the natural terrain surface.
    ground=56.125;
    // Sketch coordinates are labels. Nonuniform spacing below reserves room for the facilities.
    road("west-north",470,40,470,430,0,0,true,3);
    road("west-middle",470,430,470,880,0,0,true,3);
    road("west-south",470,880,470,1350,0,0,true,3);
    road("main-west",130,430,470,430,0,0,true,5);
    road("main-east",470,430,860,430,0,0,true,5);
    road("main-centre",860,430,1185,430,0,0,true,3);
    road("main-east-centre",1185,430,1480,430,0,0,true,3);
    road("main-east-end",1480,430,1690,400,0,0,true,3);
    for(String key:List.of("west-north","west-middle","main-east"))steps.add(new Step("subdivide-"+key,()->splitClear(roads.get(key),.5,RoadSlicing.Connection.CONTINUOUS)));
    road("north-west",380,90,1195,165,0,0,true,3);
    road("north-middle",1195,165,1495,165,0,0,true,3);
    road("north-east",1495,165,1840,150,0,0,true,3);
    road("s1-north",1195,165,1185,430,0,0,true,3,1170,245,1225,335);
    road("s1-middle",1185,430,1190,700,0,0,true,3,1220,535,1180,620);
    road("s1-south",1190,700,990,1100,0,0,false,3,1160,820,1010,1020);
    road("s1-tail",990,1100,990,1500,0,0,false,3,970,1210,980,1350);
    road("s2-north",1495,165,1480,430,0,0,true,3,1470,240,1510,320);
    road("s2-middle",1480,430,1500,690,0,0,true,3,1470,480,1510,550,1480,630);
    road("s3-north",1840,150,1775,320,0,0,true,3,1870,200,1810,270);
    road("s3",1775,320,1775,500,0,0,true,3);
    road("s3-south",1775,500,1815,700,0,0,true,3,1805,590);
    steps.add(new Step("create-attached-junction",()->{var r=data.index.roads.get(roads.get("s3"));var p=AttachedPointTool.create(level,null,r,RoadQueries.horizontal(r.mesh,point(1775,400,0)).sample().center());var t=OneSidedJunctions.create(level,null,r.record.id(),p.id(),pos(1690,400,0),true,settings(true,3,false,false));OneSidedJunctions.build(level,null,t);}));
    road("diagonal-west",470,880,620,855,0,0,false,3);
    road("tunnel",620,855,1320,720,0,0,false,3,760,825,860,805,1070,765,1200,745);
    road("diagonal-east",1320,720,1500,690,0,0,false,3);
    road("viaduct-south",100,1140,990,1170,18,18,false,3);
    road("viaduct-link",990,1170,1530,1170,18,18,false,3);
    road("viaduct-ground-approach",1500,690,1530,780,0,0,true,3);
    road("viaduct-climb",1530,780,1530,1030,0,24,false,3);
    road("viaduct-east",1530,1030,1530,1450,24,24,false,3);
    ramp("tunnel-entry","s1-middle",.65,1,"tunnel",.62,-1);
    ramp("tunnel-exit","tunnel",.5,1,"s1-south",.38,1);
    ramp("viaduct-east-entry","s1-south",.80,1,"viaduct-link",.42,1);
    ramp("viaduct-west-entry","s1-tail",.65,-1,"viaduct-south",.85,-1);
    road("upper-main",130,430,860,430,18,18,false,3);
    steps.add(new Step("edit-ground-spui-junction-right-slips",()->rightSlips(470,430)));
    steps.add(new Step("create-elevated-access",()->{
      var r=data.index.roads.get(roads.get("main-centre"));var p=ElevatedPointTool.create(level,null,r,point(1030,430,0));var t=ElevatedAccessRoads.payload(level,null,r.record.id(),p.id(),pos(860,430,18),false,null);ElevatedAccessRoads.build(level,null,ElevatedAccessRoads.preview(level,null,t));}));
    interchange("parallel-spui",InterchangePlanner.Preset.SPUI,new double[][]{{130,430,18},{860,430,18},{470,40,0},{470,880,0}},false);
    steps.add(new Step("prepare-turbine-host-boundary",this::prepareTurbine));
    steps.add(new Step("prepare-turbine-cross-boundary",()->{if(designs.containsKey("turbine-cross-boundary"))return;var at=splitClear(roads.get("west-south"),.15,RoadSlicing.Connection.CONTINUOUS);var tag=new CompoundTag();tag.putLong("Start",at.asLong());designs.put("turbine-cross-boundary",tag);}));
    interchange("turbine-above",InterchangePlanner.Preset.TURBINE,new double[][]{{100,1140,18},{990,1170,18},{470,880,0},{470,1350,0}},true);
    steps.add(new Step("prepare-trumpet-host-boundary",this::prepareTrumpet));
    interchange("trumpet-a",InterchangePlanner.Preset.TRUMPET,new double[][]{{1530,1030,24},{1530,1450,24},{990,1170,18}},false);
    steps.add(new Step("edit-road",()->{var r=data.editableRoad(roads.get("north-east"));data.connect(level,null,r.a(),r.b(),r.settings().options(r.settings().options().sidewalk(r.settings().options().sidewalk().material("minecraft:smooth_stone"))),r.id());}));
    steps.add(new Step("edit-junction-right-slips",()->rightSlips(1480,430)));
    steps.add(new Step("edit-curve-lamp-spacing",()->{var r=data.editableRoad(roads.get("s1-south"));data.connect(level,null,r.a(),r.b(),r.settings().options(r.settings().options().streetscape(r.settings().options().streetscape().lampSpacing(96))),r.id());}));
    steps.add(new Step("complex-curve-junction-cut",()->splitClear(roads.get("s1-south"),.55,RoadSlicing.Connection.JUNCTION)));
  }
  public List<Step> steps(){return List.copyOf(steps);}
  public CompoundTag checkpoint(int next){var t=new CompoundTag();t.putInt("Next",next);var rs=new CompoundTag();roads.forEach(rs::putUUID);t.put("Roads",rs);var ds=new CompoundTag();designs.forEach((k,v)->ds.put(k,v.copy()));t.put("Designs",ds);return t;}
  public int restore(CompoundTag t){var rs=t.getCompound("Roads");for(var k:rs.getAllKeys())roads.put(k,rs.getUUID(k));var ds=t.getCompound("Designs");for(var k:ds.getAllKeys())designs.put(k,ds.getCompound(k));return t.getInt("Next");}
  public double ground(){return ground;}
  private BlockPos splitClear(UUID id,double fraction,RoadSlicing.Connection mode){
    var r=data.index.roads.get(id).record;var logical=AutoJunctions.logical(data).get(id);double desired=r.mesh().length()*fraction;
    IllegalArgumentException last=null;
    // Pick a nearby unobstructed section just as the tool's user would. The
    // precise furniture phase can differ between independently generated worlds.
    for(int n=0;n<=80;n++){
      double at=desired+(n==0?0:(n%2==1?1:-1)*((n+1)/2)*.5);
      RoadSplitter.Plan plan;
      try{var pair=RoadSplitGeometry.split(r,at,mode,UUID.randomUUID());
        if(logical!=null)RoadSplitGeometry.split(logical,RoadQueries.horizontal(logical.mesh(),pair.node().position()).sample().distance(),mode,pair.second().id());
        plan=RoadSplitter.plan(level,null,r,at,mode);
      }catch(IllegalArgumentException blocked){last=blocked;continue;}
      System.out.println("PERF211 SPLIT_STATION fraction="+fraction+" station="+at+" offset="+(at-desired));
      RoadSplitter.commit(level,null,plan);return plan.pair().pos();
    }
    throw new IllegalArgumentException("No clear section within 20 blocks of requested split",last);
  }
  private void prepareTurbine(){
    V start=point(100,1140,18),end=point(990,1170,18),boundary=start.add(end.sub(start).mul(.80));
    if(designs.containsKey("turbine-boundary"))return;
    var chain=Interchanges.mainChain(data,BlockPos.containing(start.x(),start.y(),start.z()),BlockPos.containing(end.x(),end.y(),end.z()));
    var cut=chain.stream().min(Comparator.comparingDouble(r->RoadQueries.horizontal(r.mesh(),boundary).horizontalDistance())).orElseThrow();
    double station=RoadQueries.horizontal(cut.mesh(),boundary).sample().distance();var at=splitClear(cut.id(),station/cut.mesh().length(),RoadSlicing.Connection.CONTINUOUS);
    var tag=new CompoundTag();tag.putLong("End",at.asLong());designs.put("turbine-boundary",tag);
  }
  private void prepareTrumpet(){
    if(designs.containsKey("trumpet-boundary"))return;
    var old=data.index.roads.get(roads.get("viaduct-east"));
    if(old==null||!old.record.a().equals(pos(1530,1030,24)))throw new AssertionError("Trumpet host fixture changed");
    var at=splitClear(roads.get("viaduct-link"),.55,RoadSlicing.Connection.CONTINUOUS);var tag=new CompoundTag();tag.putLong("Start",at.asLong());designs.put("trumpet-boundary",tag);
  }
  private void rightSlips(double x,double z){var tag=AutoJunctions.center(data,pos(x,z,0));if(tag==null)throw new AssertionError("missing junction");var cmd=AutoJunctions.payload(level,null,tag.getUUID("Id"));var spec=JunctionCodec.read(cmd.getCompound("Spec"));cmd.put("Spec",JunctionCodec.write(spec.arms(spec.arms().stream().map(a->a.slip(true)).toList())));AutoJunctions.edit(level,null,cmd);}
  private static double remap(double v,double[] from,double[] to){int i=0;while(i<from.length-2&&v>from[i+1])i++;return to[i]+(v-from[i])/(from[i+1]-from[i])*(to[i+1]-to[i]);}
  private V point(double x,double z,double h){return new V(remap(x,new double[]{100,470,860,1030,1185,1480,1775,1840},new double[]{-700,0,650,1000,1350,2050,2750,2900})+.5,ground+h,remap(z,new double[]{40,90,165,430,690,880,1140,1170,1350,1450,1500},new double[]{-800,-700,-550,0,600,1000,1750,1800,2300,2550,2700})+.5);}
  private BlockPos pos(double x,double z,double h){return BlockPos.containing(point(x,z,h).x(),point(x,z,h).y(),point(x,z,h).z());}
  private Settings settings(boolean slow,int lanes,boolean elevated,boolean curve){
    var style=RoadProfile.choose(RoadProfile.Type.ORDINARY,6,true,elevated?RoadProfile.Median.RAIL:RoadProfile.Median.GREEN,false);
    var s=new Settings(curve?Mode.CURVE:Mode.AUTO,style,32,1,.4,90).structure(elevated?Structure.BRIDGE:Structure.GROUND);
    s=s.options(s.options().cycleFinish(slow?RoadProfile.Options.CycleFinish.ASPHALT:RoadProfile.Options.CycleFinish.NONE).sidewalk(RoadSidewalks.Config.DEFAULT.enabled(!elevated).width(10)).route(s.options().routing().fit(false)));
    return RoadLanes.configure(s,RoadProfile.Type.ORDINARY,new RoadLanes.Counts(lanes,lanes),4);
  }
  private void interchange(String name,InterchangePlanner.Preset type,double[][] anchors,boolean above){
    steps.add(new Step("calculate-"+name,()->{long[] ps=Arrays.stream(anchors).mapToLong(a->pos(a[0],a[1],a[2]).asLong()).toArray();if(type==InterchangePlanner.Preset.TURBINE){ps[1]=designs.get("turbine-boundary").getLong("End");ps[2]=designs.get("turbine-cross-boundary").getLong("Start");}if(type==InterchangePlanner.Preset.TRUMPET)ps[2]=designs.get("trumpet-boundary").getLong("Start");if(type==InterchangePlanner.Preset.SPUI){var c=pos(470,430,0);var arms=AutoJunctions.logical(data).values().stream().filter(r->r.a().equals(c)||r.b().equals(c)).filter(r->Math.abs(r.end().position().sub(r.start().position()).horizontalUnit().z())>.9).sorted(Comparator.comparingDouble(r->r.start().position().z()+r.end().position().z())).toList();if(arms.size()!=2)throw new AssertionError("SPUI cross street arms "+arms.size());for(int i=0;i<2;i++)ps[i+2]=(arms.get(i).a().equals(c)?arms.get(i).b():arms.get(i).a()).asLong();}var t=Interchanges.payload(level,null,ps,null);t.put("Options",Interchanges.write(new InterchangePlanner.Options(type,false,1,type==InterchangePlanner.Preset.TRUMPET?32:64,type==InterchangePlanner.Preset.TRUMPET?20:32,5,1,5,false).above(above)));var plan=Interchanges.plan(t);designs.put(name,Interchanges.command(t,plan,"interchangeBuild"));}));
    steps.add(new Step("create-"+name,()->{var before=new HashSet<>(data.interchanges.keySet());Interchanges.build(level,null,designs.get(name));var id=data.interchanges.keySet().stream().filter(k->!before.contains(k)).findFirst().orElseThrow();designs.put(name,data.interchanges.get(id).copy());}));
    steps.add(new Step("calculate-edit-"+name,()->{var old=designs.get(name);var t=Interchanges.payload(level,null,old.getLongArray("Points"),old.getUUID("Id"));var plan=Interchanges.plan(t);designs.put(name,Interchanges.command(t,plan,"interchangeBuild"));}));
    steps.add(new Step("edit-"+name,()->{
      Interchanges.build(level,null,designs.get(name));
    }));
  }
  private LanePoints.Ref lanePoint(String name,double fraction,int sign){
    var r=data.index.roads.get(roads.get(name));double at=r.mesh.length()*fraction;int slot=-1;double outside=-1;
    var sample=RoadStructures.sample(r.mesh,at);double median=RoadProfile.layout(r.mesh,sample).medianCenter();
    for(int i=0;i<RoadProfile.catalog(r.mesh.settings()).lanes();i++){var lane=LanePoints.lane(r.mesh,at,i);double offset=Math.abs(lane.position().sub(sample.center()).dot(sample.left())-median);if(lane.sign()==sign&&offset>outside){slot=i;outside=offset;}}
    if(slot<0)throw new AssertionError("missing traffic direction");var p=LanePointTool.create(level,null,r.record.id(),LanePoints.lane(r.mesh,at,slot).position());return LanePoints.Ref.lane(r.record.id(),p.id());
  }
  private void ramp(String name,String from,double stationA,int signA,String to,double stationB,int signB){
    steps.add(new Step("points-"+name,()->{var t=new CompoundTag();t.put("From",LanePointCodec.ref(lanePoint(from,stationA,signA)));t.put("To",LanePointCodec.ref(lanePoint(to,stationB,signB)));designs.put(name,t);}));
    steps.add(new Step("calculate-"+name,()->{var t=designs.get(name);var previous=t.contains("From")?null:LaneTopology.metadata(RoadRecord.load(t)).link();var options=new LanePoints.Options(LanePoints.Path.AUTO,from.equals("tunnel")?LanePoints.Departure.EXTRA:LanePoints.Departure.BRANCH,to.equals("tunnel")?LanePoints.Arrival.EXTRA:LanePoints.Arrival.MERGE,32,32,name.startsWith("viaduct")?LanePoints.Elevation.OVER:LanePoints.Elevation.AUTO,LanePoints.Landing.EXACT,false,name.startsWith("tunnel"));var link=new LanePoints.Link(previous==null?LanePointCodec.ref(t.getCompound("From")):previous.from(),previous==null?LanePointCodec.ref(t.getCompound("To")):previous.to(),options,null);var r=LaneRamps.generate(data,LaneTopology.records(data),UUID.randomUUID(),new UUID(0,0),link);designs.put(name,r.save());}));
    steps.add(new Step("create-"+name,()->{var r=RoadRecord.load(designs.get(name));LaneRamps.build(data,level,null,r);roads.put(name,r.id());}));
    steps.add(new Step("edit-"+name,()->{var r=data.index.roads.get(roads.get(name)).record;var t=LaneRamps.payload(r,true);t.put("Settings",RoadRecord.writeSettings(r.settings().options(r.settings().options().outerRail(RoadProfile.OuterRail.ON))));LaneRamps.editRoad(level,null,t);}));
  }
  private void road(String name,double ax,double az,double bx,double bz,double ah,double bh,boolean slow,int lanes,double... controls){
    steps.add(new Step("create-"+name,()->{
      V a=point(ax,az,ah),b=point(bx,bz,bh);double yaw=RoadPlanner.yaw(b.sub(a));
      var pa=pos(ax,az,ah);var pb=pos(bx,bz,bh);
      if(!(level.getBlockEntity(pa) instanceof NodeEntity))pa=Curve201GameTests.marker(level,new Node(a,yaw,0));
      if(!(level.getBlockEntity(pb) instanceof NodeEntity))pb=Curve201GameTests.marker(level,new Node(b,yaw,0));
      var s=settings(slow,lanes,ah>0||bh>0,controls.length>0);
      if(name.equals("upper-main"))s=s.options(s.options().infrastructure(s.options().infrastructure().bridge(RoadInfrastructure.Bridge.OVERPASS)));
      if(controls.length>0){var cs=new ArrayList<RoadProfile.Control>();for(int i=0;i<controls.length;i+=2){double h=ah+(bh-ah)*(i+2)/(controls.length+2);if(name.equals("tunnel"))h=(i==0||i==controls.length-2)?-10:-24;if(name.equals("viaduct-east"))h=i==0?14:48;cs.add(new RoadProfile.Control(UUID.nameUUIDFromBytes((name+i).getBytes(java.nio.charset.StandardCharsets.UTF_8)),point(controls[i],controls[i+1],h)));}s=s.structure(name.equals("tunnel")?Structure.AUTO:s.structure());s=s.options(s.options().route(s.options().routing().controls(cs)));}
      var r=data.connect(level,null,pa,pb,s,null,false,false,name.equals("diagonal-east"));roads.put(name,r.id());
    }));
  }
}

