package com.sora.splineroads.world;

import com.sora.splineroads.core.RoadGeometry;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.RoadProfile;
import com.sora.splineroads.core.RoadPlanner;
import com.sora.splineroads.core.RoadStructures.Part;
import com.sora.splineroads.core.RoadTransitions;
import com.sora.splineroads.core.RoadFurniture;
import java.io.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.nbt.CompoundTag;

public record RoadRecord(
    UUID id,
    UUID owner,
    BlockPos a,
    BlockPos b,
    Node start,
    Node end,
    Settings settings,
    boolean automatic,
    int clearance,
    List<Part> structures,
    int endCaps,
    int buildVersion,
    UUID assembly,
    List<Sample> alignment,
    RoadFurniture.Phase furniturePhase, com.sora.splineroads.core.JunctionPlanner.Ref junction,
    com.sora.splineroads.core.TollPlaza.Spec toll, com.sora.splineroads.core.RoadSlicing.Fragment fragment) {
  public RoadRecord(UUID id,UUID owner,BlockPos a,BlockPos b,Node start,Node end,Settings settings,boolean automatic,int clearance,List<Part> structures,int endCaps,int buildVersion,UUID assembly,List<Sample> alignment,RoadFurniture.Phase phase,com.sora.splineroads.core.JunctionPlanner.Ref junction,com.sora.splineroads.core.TollPlaza.Spec toll){
    this(id,owner,a,b,start,end,settings,automatic,clearance,structures,endCaps,buildVersion,assembly,alignment,phase,junction,toll,null);
  }
  public RoadRecord fragment(com.sora.splineroads.core.RoadSlicing.Fragment value){return new RoadRecord(id,owner,a,b,start,end,settings,automatic,clearance,structures,endCaps,buildVersion,assembly,alignment,furniturePhase,junction,toll,value);}

  public RoadRecord(UUID id,UUID owner,BlockPos a,BlockPos b,Node start,Node end,Settings settings,boolean automatic,int clearance,List<Part> structures,int endCaps,int buildVersion,UUID assembly,List<Sample> alignment,RoadFurniture.Phase phase,com.sora.splineroads.core.JunctionPlanner.Ref junction){
    this(id,owner,a,b,start,end,settings,automatic,clearance,structures,endCaps,buildVersion,assembly,alignment,phase,junction,null);
  }
  public RoadRecord toll(com.sora.splineroads.core.TollPlaza.Spec spec){
    var plan=com.sora.splineroads.core.TollPlanner.plan(spec);
    return new RoadRecord(id,owner,a,b,spec.a(),spec.b(),plan.mesh().settings(),false,4,plan.structures(),0,41,id,List.of(),null,null,spec);
  }
  public com.sora.splineroads.core.TollPlanner.Plan tollPlan(){return toll==null?null:TOLL_PLANS.get(this,r->com.sora.splineroads.core.TollPlanner.plan(r.toll()));}
  private static final com.sora.splineroads.core.WeakIdentityCache<RoadRecord,com.sora.splineroads.core.TollPlanner.Plan> TOLL_PLANS = new com.sora.splineroads.core.WeakIdentityCache<>(24,150_000,p->p.mesh().samples().size()+p.structures().size()+p.paint().size());

  public RoadRecord(UUID id, UUID owner, BlockPos a, BlockPos b, Node start, Node end, Settings settings, boolean automatic, int clearance, List<Part> structures, int endCaps, int buildVersion, UUID assembly, List<Sample> alignment, RoadFurniture.Phase phase) {
    this(id,owner,a,b,start,end,settings,automatic,clearance,structures,endCaps,buildVersion,assembly,alignment,phase,null);
  }
  public RoadRecord junction(UUID group, com.sora.splineroads.core.JunctionPlanner.Ref ref) {
    return new RoadRecord(id,owner,a,b,start,end,settings,automatic,clearance,ref.get().structures(),0,18,group,List.of(),furniturePhase,ref);
  }
  public RoadRecord(UUID id, UUID owner, BlockPos a, BlockPos b, Node start, Node end,
      Settings settings, boolean automatic, int clearance, List<Part> structures,
      int endCaps, int buildVersion, UUID assembly, List<Sample> alignment) {
    this(id, owner, a, b, start, end, settings, automatic, clearance, structures,
        endCaps, buildVersion, assembly, alignment, null);
  }

  public RoadRecord furniturePhase(RoadFurniture.Phase phase) {
    if(Objects.equals(furniturePhase,phase))return this;
    return new RoadRecord(id, owner, a, b, start, end, settings, automatic, clearance,
        structures, endCaps, buildVersion, assembly, alignment, phase, junction,toll,fragment);
  }
  public RoadRecord(
      UUID id,
      UUID owner,
      BlockPos a,
      BlockPos b,
      Node start,
      Node end,
      Settings settings,
      boolean automatic,
      int clearance,
      List<Part> structures,
      int caps,
      int version) {
    this(
        id,
        owner,
        a,
        b,
        start,
        end,
        settings,
        automatic,
        clearance,
        structures,
        caps,
        version,
        null,
        List.of());
  }

  public RoadRecord alignment(UUID group, Mesh mesh) {
    return new RoadRecord(
        id,
        owner,
        a,
        b,
        start,
        end,
        settings,
        automatic,
        clearance,
        structures,
        endCaps,
        buildVersion,
        group,
        mesh.samples(), furniturePhase, junction,toll,fragment);
  }

  public RoadRecord(
      UUID id,
      UUID owner,
      BlockPos a,
      BlockPos b,
      Node start,
      Node end,
      Settings settings,
      boolean automatic,
      int clearance,
      List<Part> structures) {
    this(id, owner, a, b, start, end, settings, automatic, clearance, structures, 0, 17);
  }

  public RoadRecord caps(int mask) {
    return new RoadRecord(
        id,
        owner,
        a,
        b,
        start,
        end,
        settings,
        automatic,
        clearance,
        structures,
        mask,
        junction == null ? 17 : 18,
        assembly,
        alignment, furniturePhase, junction,toll,fragment);
  }

  public RoadRecord(
      UUID id,
      UUID owner,
      BlockPos a,
      BlockPos b,
      Node start,
      Node end,
      Settings settings,
      boolean automatic,
      int clearance) {
    this(id, owner, a, b, start, end, settings, automatic, clearance, List.of());
  }

  private static final int MAX_STRUCTURES=50_000;
  public RoadRecord {
    if(structures.size()>MAX_STRUCTURES)throw new IllegalArgumentException(com.sora.splineroads.core.RoadPartCompaction.budgetMessage(id,structures,MAX_STRUCTURES));
    structures = junction==null?List.copyOf(structures):com.sora.splineroads.core.JunctionSignals.refreshHeads(structures,junction.get().structures());
    if(structures.size()>MAX_STRUCTURES)throw new IllegalArgumentException(com.sora.splineroads.core.RoadPartCompaction.budgetMessage(id,structures,MAX_STRUCTURES));
    alignment = List.copyOf(alignment);
  }

  public RoadRecord structures(List<Part> parts) {
    parts=com.sora.splineroads.core.RoadPartCompaction.compact(parts);
    if(structures.equals(parts))return this;
    return new RoadRecord(
        id,
        owner,
        a,
        b,
        start,
        end,
        settings,
        automatic,
        clearance,
        parts,
        endCaps,
        buildVersion,
        assembly,
        alignment, furniturePhase, junction,toll,fragment);
  }

  public RoadRecord(
      UUID id, UUID owner, BlockPos a, BlockPos b, Node start, Node end, Settings settings) {
    this(id, owner, a, b, start, end, settings, false, 0);
  }

  public CompoundTag save() {
    CompoundTag t = new CompoundTag();
    t.putUUID("Id", id);
    t.putUUID("Owner", owner);
    t.putLong("A", a.asLong());
    t.putLong("B", b.asLong());
    t.put("Start", writeNode(start));
    t.put("End", writeNode(end));
    t.put("Settings", writeSettings(settings));
    t.putBoolean("Automatic", automatic);
    t.putInt("Clearance", clearance);
    t.putInt("EndCaps", endCaps);
    t.putInt("BuildVersion", buildVersion);
    t.putByteArray("StructuresPacked", packStructures());
    if (furniturePhase != null) {
      t.putDouble("FurnitureOrigin", furniturePhase.origin());
      t.putInt("FurnitureDirection", furniturePhase.direction());
    }
    if (junction != null) t.put("Junction", JunctionCodec.writeRef(junction));
    if(toll!=null)t.put("Toll",TollCodec.write(toll));
    if(fragment!=null)t.put("Slice",RoadSliceCodec.write(fragment));
    if (assembly != null) t.putUUID("Assembly", assembly);
    if (!alignment.isEmpty()) t.putByteArray("Alignment", packAlignment());
    return t;
  }

  /** Editing and host lookup only need the path, never the baked furniture. */
  public CompoundTag header() {
    return structures(List.of()).save();
  }

  private byte[] packStructures() {return packStructures(structures);}
  public static byte[] packStructures(List<Part> structures) {
    if(structures.size()>MAX_STRUCTURES)throw new IllegalArgumentException("设施数量 "+structures.size()+" 超过每路上限 "+MAX_STRUCTURES);
    try {
      var bytes = new ByteArrayOutputStream();
      var out = new DataOutputStream(bytes);
      out.writeInt(-32);out.writeInt(structures.size());
      for (Part p : structures) {
        writeVector(out, p.a());
        writeVector(out, p.b());
        out.writeDouble(p.width());
        out.writeDouble(p.height());
        out.writeByte(
            (p.pier() ? 1 : 0) | (p.frameA() != null ? 2 : 0) | (p.frameB() != null ? 4 : 0) | (p.heights()!=null?8:0));
        out.writeByte(p.material().ordinal());
        if (p.frameA() != null) writeVector(out, p.frameA());
        if (p.frameB() != null) writeVector(out, p.frameB());
        out.writeUTF(p.model());
        if(p.heights()!=null)for(int i=0;i<4;i++)out.writeDouble(p.heights().get(i));
      }
      out.flush();
      return bytes.toByteArray();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void writeVector(DataOutput out, V v) throws IOException {
    out.writeDouble(v.x());
    out.writeDouble(v.y());
    out.writeDouble(v.z());
  }

  private static V readVector(DataInput in) throws IOException {
    return new V(in.readDouble(), in.readDouble(), in.readDouble());
  }

  public static List<Part> unpackStructures(byte[] bytes) {
    try {
      var in = new DataInputStream(new ByteArrayInputStream(bytes));
      int count = in.readInt();boolean corners=count==-32,models=corners||count==-31;if(models)count=in.readInt();
      if (count < 0 || count > MAX_STRUCTURES || (long) count * 66 > bytes.length - 4)
        throw new IOException("invalid structure count");
      List<Part> parts = new ArrayList<>(count);
      var materials = com.sora.splineroads.core.RoadStructures.Material.values();
      for (int i = 0; i < count; i++) {
        V a = readVector(in), b = readVector(in);
        double w = in.readDouble(), h = in.readDouble();
        int flags = in.readUnsignedByte(), material = in.readUnsignedByte();
        if (flags > (corners?15:7) || material >= materials.length)
          throw new IOException("invalid structure type");
        parts.add(
            new Part(
                a,
                b,
                w,
                h,
                (flags & 1) != 0,
                materials[material],
                (flags & 2) != 0 ? readVector(in) : null,
                (flags & 4) != 0 ? readVector(in) : null,models?in.readUTF():"",(flags&8)!=0?new com.sora.splineroads.core.RoadStructures.Heights(in.readDouble(),in.readDouble(),in.readDouble(),in.readDouble()):null));
      }
      if (in.available() != 0) throw new IOException("trailing structure data");
      return parts;
    } catch (IOException e) {
      throw new IllegalArgumentException("道路设施数据损坏", e);
    }
  }

  public static RoadRecord load(CompoundTag t) {
    List<Part> parts = new ArrayList<>();
    for (Tag tag : t.getList("Structures", Tag.TAG_COMPOUND)) {
      CompoundTag q = (CompoundTag) tag;
      parts.add(
          new Part(
              readNode(q.getCompound("A")).position(),
              readNode(q.getCompound("B")).position(),
              q.getDouble("Width"),
              q.getDouble("Height"),
              q.getBoolean("Pier"),
              q.contains("Material")
                  ? com.sora.splineroads.core.RoadStructures.Material.valueOf(
                      q.getString("Material"))
                  : com.sora.splineroads.core.RoadStructures.Material.DEFAULT,
              q.contains("FrameA") ? readNode(q.getCompound("FrameA")).position() : null,
              q.contains("FrameB") ? readNode(q.getCompound("FrameB")).position() : null));
    }
    if (t.contains("StructuresPacked", Tag.TAG_BYTE_ARRAY))
      parts = unpackStructures(t.getByteArray("StructuresPacked"));
    return new RoadRecord(
        t.getUUID("Id"),
        t.getUUID("Owner"),
        BlockPos.of(t.getLong("A")),
        BlockPos.of(t.getLong("B")),
        readNode(t.getCompound("Start")),
        readNode(t.getCompound("End")),
        readSettings(t.getCompound("Settings")),
        t.getBoolean("Automatic"),
        Math.max(0, Math.min(t.getCompound("Settings").getString("Style").equals("RAILBED")?5:4, t.getInt("Clearance"))),
        parts,
        t.getInt("EndCaps"),
        t.getInt("BuildVersion"),
        t.hasUUID("Assembly") ? t.getUUID("Assembly") : null,
        t.contains("Alignment") ? unpackAlignment(t.getByteArray("Alignment")) : List.of(),
        t.contains("FurnitureOrigin")
            ? new RoadFurniture.Phase(t.getDouble("FurnitureOrigin"), t.getInt("FurnitureDirection"))
            : null,
        t.contains("Junction") ? JunctionCodec.readRef(t.getCompound("Junction")) : null,
        t.contains("Toll")?TollCodec.read(t.getCompound("Toll")):null,t.contains("Slice")?RoadSliceCodec.read(t.getCompound("Slice")):null);
  }

  public static CompoundTag writeNode(Node n) {
    CompoundTag t = new CompoundTag();
    t.putDouble("X", n.position().x());
    t.putDouble("Y", n.position().y());
    t.putDouble("Z", n.position().z());
    t.putDouble("Yaw", n.yaw());
    t.putDouble("Grade", n.grade());
    return t;
  }

  public static Node readNode(CompoundTag t) {
    return new Node(
        new V(t.getDouble("X"), t.getDouble("Y"), t.getDouble("Z")),
        t.getDouble("Yaw"),
        t.getDouble("Grade"));
  }

  public static CompoundTag writeSettings(Settings s) {
    CompoundTag t = new CompoundTag();
    t.putString("Mode", s.mode().name());
    t.putString("Style", s.style().name());
    t.putString("Structure", s.structure().name());
    t.putInt("TaperVersion", s.taperVersion());
    t.putString("RampTurn", s.rampTurn().name());
    t.putDouble("Width", s.width());
    t.putDouble("StartWidth", s.startWidth());
    t.putDouble("EndWidth", s.endWidth());
    t.putDouble("Thickness", s.thickness());
    t.putDouble("Tension", s.tension());
    t.putDouble("Angle", s.arcDegrees());
    var o = s.options();
    if(!o.levelCrossings().isEmpty())t.put("RailwayCrossings",RailwayCrossingCodec.write(o.levelCrossings()));
    if(o.railbed().active())t.put("RailbedProfile",RailbedCodec.profile(o.railbed()));
    if(o.lanes().explicit()){var lanes=new CompoundTag();lanes.putInt("Forward",o.lanes().forward());lanes.putInt("Reverse",o.lanes().reverse());t.put("DirectionalLanes",lanes);}
    ListTag lines=new ListTag();for(var line:o.laneLines()){var tag=new CompoundTag();tag.putString("Key",line.key());tag.putString("Pattern",line.pattern().name());tag.putDouble("Width",line.width());lines.add(tag);}t.put("LaneLines",lines);
    t.putBoolean("LeftTraffic", o.leftTraffic());
    t.putBoolean("CycleLane", o.cycle());t.putBoolean("CycleAsphalt",o.cycleAsphalt());
    t.putBoolean("CycleRail", o.cycleRail());
    t.putBoolean("Curb", o.curb());
    CompoundTag walk=new CompoundTag();walk.putBoolean("Enabled",o.sidewalk().enabled());walk.putString("Side",o.sidewalk().side().name());walk.putInt("Width",o.sidewalk().width());walk.putString("Material",o.sidewalk().material());walk.putBoolean("Smooth",o.sidewalk().smooth());walk.putBoolean("Tactile",o.sidewalk().tactile());t.put("Sidewalk",walk);
    var infra=o.infrastructure();CompoundTag facilities=new CompoundTag();
    facilities.putString("Bridge",infra.bridge().name());facilities.putDouble("Span",infra.span());facilities.putString("Tunnel",infra.tunnel().name());facilities.putDouble("Headroom",infra.headroom());facilities.putString("Gantry",infra.gantry().name());facilities.putDouble("Spacing",infra.spacing());facilities.putDouble("TunnelDepth",infra.tunnelDepth());facilities.putString("TunnelAdjustment",infra.adjustment().name());facilities.putInt("DipProfile",infra.efficientDip()?1:0);facilities.putDouble("BridgeRise",infra.bridgeRise());facilities.putDouble("MaxGrade",infra.maxGrade());facilities.putBoolean("AutoSpan",infra.autoSpan());facilities.putBoolean("FixedStations",infra.fixedStations());facilities.putString("MedianConversion",infra.medianConversion().name());
    ListTag edits=new ListTag();for(var edit:infra.gantryEdits()){
      CompoundTag e=new CompoundTag();e.putInt("Slot",edit.slot());e.putDouble("Offset",edit.offset());e.putDouble("Clearance",edit.clearance());e.putString("Kind",edit.kind().name());e.putBoolean("Reverse",edit.reverse());e.putBoolean("Independent",edit.independent());e.putDouble("Position",edit.position());e.putBoolean("Anchored",edit.anchored());e.putBoolean("Automatic",edit.automatic());edits.add(e);
    }facilities.put("GantryEdits",edits);t.put("Infrastructure",facilities);
    t.putString("OuterRail", o.outerRail().name());
    t.putBoolean("HideArrows",o.hideArrows());
    t.put("AttachedRoadPoints",AttachedPointCodec.write(o.attachments()));
    t.put("LanePointsV2",LanePointCodec.write(o.lanePoints()));
    {var decor=new CompoundTag();var c=o.streetscape();decor.putString("Separator",c.separator().name());decor.putString("Apron",c.apron().name());var tunnels=new ListTag();for(var span:c.tunnels()){var item=new CompoundTag();item.putDouble("From",span.from());item.putDouble("To",span.to());tunnels.add(item);}decor.put("Tunnels",tunnels);decor.putBoolean("Parking",c.parking());decor.putBoolean("Lamps",c.lamps());decor.putDouble("LampSpacing",c.lampSpacing());decor.putBoolean("WalkLamps",c.walkLamps());decor.putDouble("WalkLampSpacing",c.walkLampSpacing());decor.putString("Planting",c.planting().name());decor.putDouble("PlantingSpacing",c.plantingSpacing());var plants=c.plantings();decor.putDouble("MedianGreenWidth",plants.greenWidth());decor.putDouble("MedianRailGap",plants.railGap());decor.putString("MedianTrees",plants.medianTrees().name());decor.putDouble("MedianTreeSpacing",plants.medianSpacing());decor.putString("SeparatorTrees",plants.separatorTrees().name());decor.putDouble("SeparatorTreeSpacing",plants.separatorSpacing());var raised=new ListTag();for(var span:c.raisedSpans()){var item=new CompoundTag();item.putDouble("From",span.from());item.putDouble("To",span.to());raised.add(item);}decor.put("Raised",raised);var crossings=new ListTag();for(var span:c.crossings()){var item=new CompoundTag();item.putInt("Side",span.side());item.putDouble("Start",span.start());item.putDouble("From",span.from());item.putDouble("To",span.to());item.putDouble("End",span.end());item.putDouble("Delta",span.delta());crossings.add(item);}decor.put("CycleCrossings",crossings);var sideTag=new CompoundTag();for(int side:new int[]{-1,1})for(boolean cut:new boolean[]{false,true}){var spans=new ListTag();for(var span:cut?c.sides().cut(side):c.sides().raised(side)){var item=new CompoundTag();item.putDouble("From",span.from());item.putDouble("To",span.to());spans.add(item);}sideTag.put((side<0?"Negative":"Positive")+(cut?"Cut":"Raised"),spans);}decor.put("SideStructures",sideTag);t.put("Streetscape",decor);}
    t.putDouble("LiftPosition", o.liftPosition());
    t.putDouble("LiftHeight", o.liftHeight());
    t.putString("SourceSide", o.ports().source().name());
    t.putString("TargetSide", o.ports().target().name());
    t.putDouble("SourceOutset", o.ports().sourceOutset());
    t.putDouble("TargetOutset", o.ports().targetOutset());
    t.putString("SourceJoin", o.ports().sourceJoin().name());
    t.putString("TargetJoin", o.ports().targetJoin().name());
    var controls=new ListTag();for(var c:o.routing().controls()){var item=new CompoundTag();item.putUUID("Id",c.id());item.put("Position",writeNode(new Node(c.position(),0,0)));controls.add(item);}t.put("CurveControls",controls);
    t.putString("RouteKind", o.routing().kind().name());
    if(o.routing().through())t.putBoolean("CurveThrough",true);
    t.putDouble("LoopRadius", o.routing().radius());
    t.putDouble("ControlX", o.routing().offsetX());
    t.putDouble("ControlZ", o.routing().offsetZ());
    t.putBoolean("FitEdges", o.routing().fitEdges());
    t.putDouble("Transition", o.routing().transition());
    if(o.routing().fanArms()>0){t.putInt("FanArms",o.routing().fanArms());t.putDouble("FanStart",o.routing().fanStart());t.putDouble("FanEnd",o.routing().fanEnd());}
    if(o.ends().startHold()!=0||o.ends().endHold()!=0){t.putDouble("StartHold",o.ends().startHold());t.putDouble("EndHold",o.ends().endHold());}
    if(o.ends().paintPhase()!=0)t.putDouble("PaintPhase",o.ends().paintPhase());
    if (o.ends().start() != null) t.put("StartSection", writeSection(o.ends().start()));
    if (o.ends().end() != null) t.put("EndSection", writeSection(o.ends().end()));
    t.putBoolean("KeepEndSections", o.ends().persistent());
    if(o.ends().trimmedStart()!=0||o.ends().trimmedEnd()!=0){t.putDouble("TrimmedStart",o.ends().trimmedStart());t.putDouble("TrimmedEnd",o.ends().trimmedEnd());}
    if(o.ends().port()!=null){var p=o.ends().port();var c=new CompoundTag();c.putDouble("MotorMin",p.motorMin());c.putDouble("MotorMax",p.motorMax());c.putDouble("Median",p.median());c.putDouble("MedianCenter",p.medianCenter());c.putDouble("CurbLeft",p.curbLeft());c.putDouble("CurbRight",p.curbRight());var d=new ListTag();p.dividers().forEach(v->d.add(DoubleTag.valueOf(v)));c.put("Dividers",d);t.put("JunctionPort",c);}
    return t;
  }

  private static List<RoadProfile.Control> readControls(CompoundTag t){var list=t.getList("CurveControls",Tag.TAG_COMPOUND);if(list.size()>32)throw new IllegalArgumentException("曲线控制点超过32个");var out=new ArrayList<RoadProfile.Control>();for(var tag:list){var c=(CompoundTag)tag;out.add(new RoadProfile.Control(c.getUUID("Id"),readNode(c.getCompound("Position")).position()));}return List.copyOf(out);}
  public static Settings readSettings(CompoundTag t) {
    if (!t.contains("Mode")) return Settings.defaults();
    Settings s =
        new Settings(
            Mode.valueOf(t.getString("Mode")),
            Style.valueOf(t.getString("Style")),
            t.getDouble("Width"),
            t.getDouble("Thickness"),
            t.getDouble("Tension"),
            t.getDouble("Angle"),
            t.contains("StartWidth") ? t.getDouble("StartWidth") : t.getDouble("Width"),
            t.contains("EndWidth") ? t.getDouble("EndWidth") : t.getDouble("Width"),
            t.contains("Structure") ? Structure.valueOf(t.getString("Structure")) : Structure.AUTO,
            t.contains("TaperVersion") ? t.getInt("TaperVersion") : 1,
            t.contains("RampTurn") ? RampTurn.valueOf(t.getString("RampTurn")) : RampTurn.LEGACY);
    s =
        s.options(
            new RoadProfile.Options(
                t.getBoolean("LeftTraffic"),
                t.getBoolean("CycleLane"),
                t.getBoolean("CycleRail"),
                t.getBoolean("Curb"),
                t.contains("LiftPosition") ? t.getDouble("LiftPosition") : .5,
                t.getDouble("LiftHeight"),
                new RoadProfile.Ports(
                    t.contains("SourceSide")
                        ? RoadProfile.Side.valueOf(t.getString("SourceSide"))
                        : RoadProfile.Side.AUTO,
                    t.contains("TargetSide")
                        ? RoadProfile.Side.valueOf(t.getString("TargetSide"))
                        : RoadProfile.Side.AUTO,
                    t.getDouble("SourceOutset"),
                    t.getDouble("TargetOutset"),
                    t.contains("SourceJoin")
                        ? RoadProfile.JoinMode.valueOf(t.getString("SourceJoin"))
                        : RoadProfile.JoinMode.ADDED,
                    t.contains("TargetJoin")
                        ? RoadProfile.JoinMode.valueOf(t.getString("TargetJoin"))
                        : RoadProfile.JoinMode.ADDED),
                new RoadProfile.Routing(
                    t.contains("RouteKind")
                        ? RoadProfile.RouteKind.valueOf(t.getString("RouteKind"))
                        : RoadProfile.RouteKind.DIRECT,
                    t.contains("LoopRadius") ? t.getDouble("LoopRadius") : 24,
                    t.getDouble("ControlX"),
                    t.getDouble("ControlZ"),
                    t.getBoolean("FitEdges"),
                    t.contains("Transition") ? t.getDouble("Transition") : 36,
                    t.getInt("FanArms"),t.getDouble("FanStart"),t.getDouble("FanEnd"),readControls(t),t.getBoolean("CurveThrough")),
                t.contains("OuterRail")
                    ? RoadProfile.OuterRail.valueOf(t.getString("OuterRail"))
                    : RoadProfile.OuterRail.AUTO));
    s =
        s.options(
            s.options()
                .ends(
                    new com.sora.splineroads.core.RoadTransitions.Ends(
                        t.contains("StartSection")
                            ? readSection(t.getCompound("StartSection"))
                            : null,
                        t.contains("EndSection") ? readSection(t.getCompound("EndSection")) : null,
                        t.getBoolean("KeepEndSections"),t.getDouble("TrimmedStart"),t.getDouble("TrimmedEnd"),null)));
    s=s.options(s.options().ends(s.options().ends().hold(t.getDouble("StartHold"),t.getDouble("EndHold"))));
    if(t.contains("PaintPhase"))s=s.options(s.options().ends(s.options().ends().paintPhase(t.getDouble("PaintPhase"))));
    if(t.contains("JunctionPort")){var p=t.getCompound("JunctionPort");var d=new ArrayList<Double>();for(Tag v:p.getList("Dividers",Tag.TAG_DOUBLE))d.add(((DoubleTag)v).getAsDouble());s=s.options(s.options().ends(s.options().ends().port(new RoadTransitions.Port(p.getDouble("MotorMin"),p.getDouble("MotorMax"),p.getDouble("Median"),d,p.getDouble("CurbLeft"),p.getDouble("CurbRight"),p.getDouble("MedianCenter")))));}
    if(t.contains("Sidewalk")) {
      var walk=t.getCompound("Sidewalk");s=s.options(s.options().sidewalk(new com.sora.splineroads.core.RoadSidewalks.Config(walk.getBoolean("Enabled"),com.sora.splineroads.core.RoadSidewalks.Side.valueOf(walk.getString("Side")),walk.getInt("Width"),walk.getString("Material"),walk.getBoolean("Smooth"),!walk.contains("Tactile")||walk.getBoolean("Tactile"))));
    }
    if(t.contains("Infrastructure")){var i=t.getCompound("Infrastructure");s=s.options(s.options().infrastructure(new com.sora.splineroads.core.RoadInfrastructure.Config(com.sora.splineroads.core.RoadInfrastructure.Bridge.valueOf(i.getString("Bridge")),i.getDouble("Span"),com.sora.splineroads.core.RoadInfrastructure.Tunnel.valueOf(i.getString("Tunnel")),i.getDouble("Headroom"),com.sora.splineroads.core.RoadInfrastructure.Gantry.valueOf(i.getString("Gantry")),i.getDouble("Spacing"),i.getDouble("TunnelDepth"),i.getList("GantryEdits",Tag.TAG_COMPOUND).stream().map(tag->{var e=(CompoundTag)tag;return new com.sora.splineroads.core.RoadGantry.Edit(e.getInt("Slot"),e.getDouble("Offset"),e.getDouble("Clearance"),com.sora.splineroads.core.RoadInfrastructure.Gantry.valueOf(e.getString("Kind")),e.getBoolean("Reverse"),e.getBoolean("Independent"),e.getDouble("Position"),e.getBoolean("Anchored"),e.getBoolean("Automatic"));}).toList(),i.contains("TunnelAdjustment")?com.sora.splineroads.core.RoadTunnelFit.Adjustment.valueOf(i.getString("TunnelAdjustment")):com.sora.splineroads.core.RoadTunnelFit.Adjustment.OFF,i.getInt("DipProfile")>=1,i.getDouble("BridgeRise"),i.getDouble("MaxGrade"),i.getBoolean("AutoSpan"),i.getBoolean("FixedStations"),i.contains("MedianConversion")?com.sora.splineroads.core.RoadInfrastructure.MedianConversion.valueOf(i.getString("MedianConversion")):com.sora.splineroads.core.RoadInfrastructure.MedianConversion.BOTH)));}
    else s=s.options(s.options().infrastructure(s.options().infrastructure().grade(0).autoSpan(false)));
    var lines=new ArrayList<com.sora.splineroads.core.RoadLaneLines.Edit>();for(Tag tag:t.getList("LaneLines",Tag.TAG_COMPOUND)){var e=(CompoundTag)tag;lines.add(new com.sora.splineroads.core.RoadLaneLines.Edit(e.getString("Key"),com.sora.splineroads.core.RoadLaneLines.Pattern.valueOf(e.getString("Pattern")),e.getDouble("Width")));}s=s.options(s.options().laneLines(lines).hideArrows(t.getBoolean("HideArrows")).attachments(AttachedPointCodec.read(t.getCompound("AttachedRoadPoints"))).lanePoints(LanePointCodec.read(t.getCompound("LanePointsV2"))));
    if(t.contains("DirectionalLanes")){var lanes=t.getCompound("DirectionalLanes");s=s.options(s.options().lanes(new com.sora.splineroads.core.RoadLanes.Counts(lanes.getInt("Forward"),lanes.getInt("Reverse"))));}
    s.validate();
    if(s.options().cycle()&&t.getBoolean("CycleAsphalt"))s=s.options(s.options().cycleFinish(RoadProfile.Options.CycleFinish.ASPHALT));
    if(t.contains("Streetscape")){var c=t.getCompound("Streetscape");s=s.options(s.options().streetscape(new com.sora.splineroads.core.RoadStreetscape.Config(com.sora.splineroads.core.RoadStreetscape.Separator.valueOf(c.getString("Separator")),c.getBoolean("Parking"),c.getDouble("LampSpacing"),c.getBoolean("WalkLamps"),c.getDouble("WalkLampSpacing"),com.sora.splineroads.core.RoadStreetscape.Planting.valueOf(c.getString("Planting")),c.getDouble("PlantingSpacing"))));var spans=new ArrayList<com.sora.splineroads.core.RoadStreetscape.Span>();for(var item:c.getList("Raised",Tag.TAG_COMPOUND)){var r=(CompoundTag)item;spans.add(new com.sora.splineroads.core.RoadStreetscape.Span(r.getDouble("From"),r.getDouble("To")));}var crossings=new ArrayList<com.sora.splineroads.core.RoadsideBypass.Span>();for(var item:c.getList("CycleCrossings",Tag.TAG_COMPOUND)){var r=(CompoundTag)item;crossings.add(new com.sora.splineroads.core.RoadsideBypass.Span(r.getInt("Side"),r.getDouble("Start"),r.getDouble("From"),r.getDouble("To"),r.getDouble("End"),r.getDouble("Delta")));}s=s.options(s.options().streetscape(s.options().streetscape().raisedSpans(spans).crossings(crossings).apron(c.contains("Apron")?com.sora.splineroads.core.RoadStreetscape.Apron.valueOf(c.getString("Apron")):com.sora.splineroads.core.RoadStreetscape.Apron.ROAD).tunnels(c.getList("Tunnels",Tag.TAG_COMPOUND).stream().map(item->{var span=(CompoundTag)item;return new com.sora.splineroads.core.RoadStreetscape.Span(span.getDouble("From"),span.getDouble("To"));}).toList())));}
    if(t.getCompound("Streetscape").contains("Lamps"))s=s.options(s.options().streetscape(s.options().streetscape().lamps(t.getCompound("Streetscape").getBoolean("Lamps"))));
    if(t.getCompound("Streetscape").contains("SideStructures")){
      var c=t.getCompound("Streetscape").getCompound("SideStructures");
      java.util.function.Function<String,List<com.sora.splineroads.core.RoadStreetscape.Span>> read=key->c.getList(key,Tag.TAG_COMPOUND).stream().map(item->{var v=(CompoundTag)item;return new com.sora.splineroads.core.RoadStreetscape.Span(v.getDouble("From"),v.getDouble("To"));}).toList();
      s=s.options(s.options().streetscape(s.options().streetscape().sideStructures(new com.sora.splineroads.core.RoadStreetscape.SideStructures(read.apply("NegativeRaised"),read.apply("PositiveRaised"),read.apply("NegativeCut"),read.apply("PositiveCut")))));
    }
    if(t.contains("Streetscape")){var c=t.getCompound("Streetscape");var defaults=com.sora.splineroads.core.RoadPlantings.DEFAULT;
      var plants=new com.sora.splineroads.core.RoadPlantings(c.contains("MedianGreenWidth")?c.getDouble("MedianGreenWidth"):defaults.greenWidth(),c.contains("MedianRailGap")?c.getDouble("MedianRailGap"):0,
          c.contains("MedianTrees")?com.sora.splineroads.core.RoadStreetscape.Planting.valueOf(c.getString("MedianTrees")):defaults.medianTrees(),c.contains("MedianTreeSpacing")?c.getDouble("MedianTreeSpacing"):16,
          c.contains("SeparatorTrees")?com.sora.splineroads.core.RoadStreetscape.Planting.valueOf(c.getString("SeparatorTrees")):defaults.separatorTrees(),c.contains("SeparatorTreeSpacing")?c.getDouble("SeparatorTreeSpacing"):16);
      s=s.options(s.options().streetscape(s.options().streetscape().plantings(plants)));}

    if(t.contains("RailwayCrossings"))s=s.options(s.options().levelCrossings(RailwayCrossingCodec.read(t.getList("RailwayCrossings",Tag.TAG_COMPOUND))));
    if(t.contains("RailbedProfile"))s=s.options(s.options().railbed(RailbedCodec.profile(t.getCompound("RailbedProfile"))));
    return s;
  }

  private static CompoundTag writeSection(com.sora.splineroads.core.RoadTransitions.Section s) {
    var tag=writeSettings(s.settings(false));if(s.sidewalk()==null)tag.remove("Sidewalk");return tag;
  }

  private static com.sora.splineroads.core.RoadTransitions.Section readSection(CompoundTag t) {
    // Endpoint descriptions contain a plain cross-section, never nested transition descriptions.
    CompoundTag flat = t.copy();
    flat.remove("StartSection");
    flat.remove("EndSection");
    var settings=readSettings(flat);var section=com.sora.splineroads.core.RoadTransitions.Section.of(settings).port(settings.options().ends().port());return t.contains("Sidewalk")?section:new com.sora.splineroads.core.RoadTransitions.Section(section.style(),section.width(),section.cycle(),section.cycleRail(),section.curb(),section.outerRail(),null,section.cycleAsphalt(),section.port(),section.streetscape(),section.lanes());
  }

  /** Motor-road profile, including rail crossings, independent of cycle bypasses and lane cuts. */
  public Mesh terrainClassificationMesh(){return com.sora.splineroads.core.RailwayCrossings.shape(derivedStreetscape(settings.options().streetscape().raisedSpans(List.of()).crossings(List.of()).tunnels(List.of())).rawMesh());}
  public RoadRecord withLevelCrossings(List<com.sora.splineroads.core.RailwayCrossings.Surface> value){
    if(settings.options().levelCrossings().equals(value))return this;
    return new RoadRecord(id,owner,a,b,start,end,settings.options(settings.options().levelCrossings(value)),automatic,clearance,structures,endCaps,buildVersion,assembly,alignment,furniturePhase,junction,toll,fragment);
  }
  public RoadRecord derivedStreetscape(com.sora.splineroads.core.RoadStreetscape.Config config){
    if(settings.options().streetscape().equals(config))return this;
    return new RoadRecord(id,owner,a,b,start,end,settings.options(settings.options().streetscape(config)),automatic,clearance,structures,endCaps,buildVersion,assembly,alignment,furniturePhase,junction,toll,fragment);
  }
  public RoadRecord withLanePoints(com.sora.splineroads.core.LanePoints.Data value){
    if(settings.options().lanePoints().equals(value))return this;
    var old=settings.options().lanePoints();var nextFragment=fragment;if(fragment!=null&&(!old.cuts().equals(value.cuts())||!old.additions().equals(value.additions())||!old.bands().equals(value.bands())))nextFragment=fragment.geometry(new com.sora.splineroads.core.RoadSlicing.Geometry(List.of()));
    return new RoadRecord(id,owner,a,b,start,end,settings.options(settings.options().lanePoints(value)),automatic,clearance,structures,endCaps,buildVersion,assembly,alignment,furniturePhase,junction,toll,nextFragment);
  }
  public RoadRecord withAttachments(com.sora.splineroads.core.RoadAttachments.Data value){
    if(settings.options().attachments().equals(value))return this;
    return new RoadRecord(id,owner,a,b,start,end,settings.options(settings.options().attachments(value)),automatic,clearance,structures,endCaps,buildVersion,assembly,alignment,furniturePhase,junction,toll,fragment);
  }
  private static double sectionWidth(RoadTransitions.Section section,double ownWidth){return section==null?ownWidth:section.width();}
  private static boolean authoredWidthChanged(Settings before,Settings after){
    return before.width()!=after.width()||before.style()!=after.style()||!before.options().lanes().equals(after.options().lanes())||before.options().leftTraffic()!=after.options().leftTraffic()
      ||before.options().cycle()!=after.options().cycle()||before.options().curb()!=after.options().curb();
  }
  private static boolean widthChanged(Settings before,Settings after){
    return authoredWidthChanged(before,after)
      // A clipped curved cross-section can project 16 m as 15.9999993 m.
      // Only derived seams get this tolerance; authored width edits above stay exact.
      ||Math.abs(sectionWidth(before.options().ends().start(),before.width())-sectionWidth(after.options().ends().start(),after.width()))>1e-5
      ||Math.abs(sectionWidth(before.options().ends().end(),before.width())-sectionWidth(after.options().ends().end(),after.width()))>1e-5;
  }
  /** Test a donor before raw-section compatibility or automatic-junction routing.
   * Several valid lane removals can differ from the authored count by more than
   * one; their unchanged live port is still the same existing seam. */
  public boolean sameDerivedLaneEnd(Settings proposed,boolean first,Settings candidate){
    if(candidate==null)return false;var before=unchangedLaneMesh(proposed);
    return before!=null&&retainedLaneEnd(before,first,RoadTransitions.Section.of(candidate));
  }
  private Mesh unchangedLaneMesh(Settings proposed){
    if(authoredWidthChanged(settings,proposed)||junction!=null||toll!=null
        ||settings.options().lanePoints().link()!=null)return null;
    var lanes=settings.options().lanePoints();
    return lanes.cuts().stream().anyMatch(c->!c.temporary())||!lanes.additions().isEmpty()?caps(0).mesh():null;
  }
  /** Shared pure join for decoded server and preview requests. Only a donor
   * equal to this road's existing effective port bypasses raw-count comparison;
   * new roads and genuine endpoint/profile changes keep the ordinary checks. */
  public static Settings joinSections(Settings proposed,Settings start,Settings end,
      boolean fixedStart,boolean fixedEnd,RoadRecord previous){
    var before=previous==null?null:previous.unchangedLaneMesh(proposed);
    boolean keepStart=before!=null&&start!=null&&retainedLaneEnd(before,true,RoadTransitions.Section.of(start));
    boolean keepEnd=before!=null&&end!=null&&retainedLaneEnd(before,false,RoadTransitions.Section.of(end));
    var joined=RoadTransitions.join(proposed,keepStart?null:start,keepEnd?null:end);
    if(!proposed.style().ramp()&&RoadProfile.modern(proposed.style())&&(fixedStart||fixedEnd)){
      var ends=joined.options().ends();
      joined=RoadTransitions.ends(joined,fixedStart&&start!=null?RoadTransitions.Section.of(start):ends.start(),
          fixedEnd&&end!=null?RoadTransitions.Section.of(end):ends.end());
    }
    return previous==null?joined:previous.retainAuthoredLaneEnds(joined,keepStart,keepEnd);
  }
  private Settings retainAuthoredLaneEnds(Settings proposed,boolean keepStart,boolean keepEnd){
    if(!keepStart&&!keepEnd)return proposed;
    var old=settings.options().ends();var next=proposed.options().ends();
    return proposed.taper(keepStart?settings.startWidth():proposed.startWidth(),keepEnd?settings.endWidth():proposed.endWidth())
        .options(proposed.options().ends(new RoadTransitions.Ends(
            keepStart?old.start():next.start(),keepEnd?old.end():next.end(),next.persistent(),
            next.trimmedStart(),next.trimmedEnd(),next.port(),next.paintPhase(),next.startHold(),next.endHold())));
  }
  private static boolean retainedLaneEnd(Mesh built,boolean first,RoadTransitions.Section candidate){
    if(candidate==null||!com.sora.splineroads.core.RoadEndpointSections.changed(built,first))return false;
    var actual=com.sora.splineroads.core.RoadEndpointSections.section(built,first,false);
    var proposed=candidate.settings(built.settings().options().leftTraffic());
    if(!com.sora.splineroads.core.RoadLanes.counts(actual).equals(com.sora.splineroads.core.RoadLanes.counts(proposed))
        ||Math.abs(actual.width()-candidate.width())>1e-5)return false;
    var a=RoadProfile.layout(actual,actual.width());var b=candidate.layout(built.settings().options().leftTraffic());
    if(Math.abs(a.motorMin()-b.motorMin())>1e-5||Math.abs(a.motorMax()-b.motorMax())>1e-5
        ||Math.abs(a.median()-b.median())>1e-5||Math.abs(a.medianCenter()-b.medianCenter())>1e-5
        ||Math.abs(a.laneWidth()-b.laneWidth())>1e-5||a.dividers().size()!=b.dividers().size())return false;
    for(int i=0;i<a.dividers().size();i++)if(Math.abs(a.dividers().get(i)-b.dividers().get(i))>1e-5)return false;
    return true;
  }
  private static boolean sameNode(Node a,Node b){return a.position().distance(b.position())<1e-7&&Math.abs(Math.IEEEremainder(a.yaw()-b.yaw(),360))<1e-6&&Math.abs(a.grade()-b.grade())<1e-8;}
  /** Resolve an unchanged slice BEFORE fitting a new curve to its shortened ends.
   * Re-fitting can fail curvature checks even though the saved subpath is valid.
   * The same route/profile checks as retainAlignment decide whether reuse is legal;
   * width changes still validate the resulting sampled ribbon. */
  public RoadPlanner.Plan retainedPlan(Settings request,RoadPlanner.Hint a,RoadPlanner.Hint b,Node authoredStart,Node authoredEnd){
    if(fragment==null||alignment.isEmpty()||!sameNode(start,authoredStart)||!sameNode(end,authoredEnd))return null;
    boolean auto=automatic&&request.mode()==Mode.AUTO;
    var wanted=auto?RoadPlanner.mode(request,settings.mode(),request.arcDegrees()):request;
    var probe=new RoadRecord(id,owner,this.a,this.b,a.node(),b.node(),wanted,automatic,clearance);
    if(!sameSavedEndpoints(probe))return null;
    var candidate=new RoadRecord(id,owner,this.a,this.b,start,end,wanted,automatic,clearance);
    var kept=retainAlignment(candidate);
    if(kept.fragment()==null)return null;
    var mesh=kept.rawMesh();
    // Connection hints refer to the median axis, while stored samples are physical
    // pavement centres. Restore the axis only for the shared endpoint preflight.
    var samples=mesh.samples().stream().map(p->{var layout=RoadProfile.layout(mesh,p);return new Sample(p.at(layout.catalog().twoWay()?layout.medianCenter():0,0),p.left(),p.distance(),p.halfWidth());}).toList();
    var axis=new Mesh(samples,mesh.settings(),mesh.min(),mesh.max(),mesh.length(),mesh.closed());
    return new RoadPlanner.Plan(start,end,kept.settings(),axis,"保留已保存的道路曲线");
  }
  /** Furniture/width edits do not discard a saved sampled ordinary-road path.
   * A real endpoint, route, curve or vertical-profile edit still replans it. */
  /** AUTO stores its resolved curve mode. Re-solving a shorter split fragment can
   * pick another mode or heading even when the user only changed furniture/width.
   * Compare authored endpoint intent first; actual path/profile edits still use
   * the normal replan checks below. */
  public RoadRecord retainAlignment(RoadRecord proposal,Settings requested,Node authoredStart,Node authoredEnd){
    if(fragment!=null&&automatic&&requested.mode()==Mode.AUTO
        &&sameNode(start,authoredStart)&&sameNode(end,authoredEnd)
        &&requested.arcDegrees()==settings.arcDegrees()&&sameSavedEndpoints(proposal)){
      var ps=proposal.settings();
      var normalized=new Settings(settings.mode(),ps.style(),ps.width(),ps.thickness(),requested.tension(),settings.arcDegrees(),ps.startWidth(),ps.endWidth(),ps.structure(),ps.taperVersion(),ps.rampTurn(),ps.options());
      var candidate=new RoadRecord(proposal.id(),proposal.owner(),proposal.a(),proposal.b(),start,end,normalized,
          proposal.automatic(),proposal.clearance(),proposal.structures(),proposal.endCaps(),proposal.buildVersion(),proposal.assembly(),proposal.alignment(),proposal.furniturePhase(),proposal.junction(),proposal.toll(),proposal.fragment());
      var retained=retainAlignment(candidate);
      if(retained.fragment()!=null)return retained;
    }
    return retainAlignment(proposal);
  }
  /** A split marker and its effective lane port use different interpolation frames.
   * A live continuation may therefore return the original physical median instead
   * of the marker. Accept only one of those two saved positions; an authored node
   * move or a changed neighbour port still takes the normal replan path. */
  private boolean sameSavedEndpoints(RoadRecord proposal){
    boolean a=start.position().distance(proposal.start().position())<1e-7;
    boolean b=end.position().distance(proposal.end().position())<1e-7;
    if(a&&b)return true;
    var saved=caps(0).mesh();
    return (a||com.sora.splineroads.core.RoadMedianAnchor.position(saved,true).distance(proposal.start().position())<1e-7)
        &&(b||com.sora.splineroads.core.RoadMedianAnchor.position(saved,false).distance(proposal.end().position())<1e-7);
  }
  public RoadRecord retainAlignment(RoadRecord proposal){
    var s=proposal.settings();var a=settings.options();var b=s.options();
    if(alignment.isEmpty()||!sameNode(start,proposal.start())||!sameNode(end,proposal.end())
        ||settings.mode()!=s.mode()||settings.tension()!=s.tension()||settings.arcDegrees()!=s.arcDegrees()
        ||settings.rampTurn()!=s.rampTurn()||settings.structure()!=s.structure()
        ||!a.routing().equals(b.routing())||!a.ports().equals(b.ports())
        ||a.liftPosition()!=b.liftPosition()||a.liftHeight()!=b.liftHeight()
        ||a.infrastructure().tunnelDepth()!=b.infrastructure().tunnelDepth()
        ||a.infrastructure().bridgeRise()!=b.infrastructure().bridgeRise()
        ||a.infrastructure().efficientDip()!=b.infrastructure().efficientDip()
        ||a.infrastructure().maxGrade()!=b.infrastructure().maxGrade()
        ||a.infrastructure().adjustment()!=b.infrastructure().adjustment())return proposal;
    if(fragment!=null){
      // A neighbour slice advertises its EFFECTIVE port, including stable added
      // lanes. Reusing that as this slice's raw end applies the expansion twice
      // and discards its source-relative trim. Furniture edits retain both the
      // authored taper widths and saved ends; actual width/profile edits below
      // still use the requested seam and the ordinary continuation checks.
      if(!authoredWidthChanged(settings,s))s=s.taper(settings.startWidth(),settings.endWidth()).options(s.options().ends(settings.options().ends()));
      else{var ends=s.options().ends();var old=settings.options().ends();s=s.options(s.options().ends(new RoadTransitions.Ends(ends.start(),ends.end(),ends.persistent()).port(ends.port()).paintPhase(old.paintPhase()+old.trimmedStart())));}
    }
    var adjusted=settings(s);
    return new RoadRecord(proposal.id(),proposal.owner(),proposal.a(),proposal.b(),start,end,s,
        proposal.automatic(),proposal.clearance(),List.of(),proposal.endCaps(),proposal.buildVersion(),proposal.assembly(),adjusted.alignment(),furniturePhase,proposal.junction(),proposal.toll(),adjusted.fragment());
  }
  public RoadRecord settings(Settings value) {
    var slots=com.sora.splineroads.core.LaneAdditions.reserveAuthored(value.options().lanePoints(),com.sora.splineroads.core.RoadProfile.catalog(value).lanes());
    if(slots!=value.options().lanePoints())return settings(value.options(value.options().lanePoints(slots)));
    if(settings.equals(value))return this;
    List<Sample> adjusted = alignment;var retainedFragment=fragment;
    if (!alignment.isEmpty() && widthChanged(settings,value)) {
      if(fragment!=null)retainedFragment=fragment.geometry(new com.sora.splineroads.core.RoadSlicing.Geometry(List.of()));
      double length = alignment.get(alignment.size() - 1).distance();
      var before=fragment==null?null:com.sora.splineroads.core.RoadSlicing.mesh(alignment,settings);
      adjusted =
          alignment.stream()
              .map(
                  p ->
                      new Sample(
                          p.center().add(p.left().mul(fragment==null?0:medianShift(before,p,value,length))),
                          p.left(),
                          p.distance(),
                          RoadTransitions.width(value,p.distance()+value.options().ends().trimmedStart(),length+value.options().ends().trimmedStart()+value.options().ends().trimmedEnd()) / 2))
              .toList();
    }
    return new RoadRecord(
        id,
        owner,
        a,
        b,
        start,
        end,
        value,
        automatic,
        clearance,
        List.of(),
        endCaps,
        buildVersion,
        assembly,
        adjusted, furniturePhase, junction,toll,retainedFragment);
  }

  private double medianShift(Mesh before,Sample point,Settings next,double length){
    double width=RoadTransitions.width(next,point.distance()+next.options().ends().trimmedStart(),length+next.options().ends().trimmedStart()+next.options().ends().trimmedEnd());
    var mesh=new Mesh(before.samples(),next,before.min(),before.max(),length,false);var after=new Sample(point.center(),point.left(),point.distance(),width/2);
    return RoadProfile.layout(before,point).medianCenter()-RoadProfile.layout(mesh,after).medianCenter();
  }
  private byte[] packAlignment() {
    try {
      var bytes = new ByteArrayOutputStream();
      var out = new DataOutputStream(bytes);
      out.writeInt(fragment==null?alignment.size():-alignment.size());
      for (var p : alignment) {
        writeVector(out, p.center());
        out.writeDouble(p.left().x());
        out.writeDouble(p.left().z());
        out.writeDouble(p.halfWidth());if(fragment!=null)out.writeDouble(p.distance());
      }
      return bytes.toByteArray();
    } catch (IOException e) {
      throw new IllegalArgumentException("道路路径编码失败", e);
    }
  }

  private static List<Sample> unpackAlignment(byte[] bytes) {
    try {
      var in = new DataInputStream(new ByteArrayInputStream(bytes));
      int count = in.readInt();boolean stations=count<0;int n=Math.abs(count);
      if (n < 2
          || n > com.sora.splineroads.core.RoadLimits.MAX_SAMPLES
          || bytes.length != 4 + n * (stations?56:48)) throw new IOException("invalid alignment");
      List<Sample> samples = new ArrayList<>();
      double distance = 0;
      V previous = null;
      for (int i = 0; i < n; i++) {
        V p = readVector(in), left = new V(in.readDouble(), 0, in.readDouble());
        double half = in.readDouble();
        if (!RoadGeometry.finite(p.x(), p.y(), p.z(), left.x(), left.z(), half)
            || half < .25
            || half > 32
            || Math.abs(left.horizontalLength() - 1) > .001)
          throw new IOException("invalid path point");
        if(stations){double next=in.readDouble();if(!Double.isFinite(next)||next<0||previous!=null&&next<=distance)throw new IOException("invalid path station");distance=next;}
        else if (previous != null) distance += p.distance(previous);
        samples.add(new Sample(p, left, distance, half));
        previous = p;
      }
      return List.copyOf(samples);
    } catch (IOException e) {
      throw new IllegalArgumentException("道路路径数据损坏", e);
    }
  }

  // Immutable snapshots are the cache key, not UUIDs: preview/edit geometry cannot go stale.
  private static final com.sora.splineroads.core.WeakIdentityCache<RoadRecord,Mesh> RAW_MESHES =
      new com.sora.splineroads.core.WeakIdentityCache<>(2048,750_000,m->m.samples().size());
  private static final com.sora.splineroads.core.WeakIdentityCache<RoadRecord,Mesh> SHAPED_MESHES =
      new com.sora.splineroads.core.WeakIdentityCache<>(2048,750_000,m->m.samples().size());
  public static void clearMeshCaches(){com.sora.splineroads.core.RoadRaster.clearSavedRasters();com.sora.splineroads.core.RoadTunnelSpace.clearPreparedCache();RAW_MESHES.clear();SHAPED_MESHES.clear();TOLL_PLANS.clear();}
  public static String meshCacheStats(){return "raw="+RAW_MESHES.stats()+", shaped="+SHAPED_MESHES.stats();}
  public boolean legacyRailway(){return railway()&&!settings.options().railbed().active();}
  public boolean railway(){return settings.style()==Style.RAILBED;}
  public RoadGeometry.Mesh mesh() {if(railway())return rawMesh();return SHAPED_MESHES.get(this,r->{var mesh=com.sora.splineroads.core.RoadsideBypass.sampled(com.sora.splineroads.core.LaneSections.apply(r.rawMesh()));return com.sora.splineroads.core.RailwayCrossings.shape(r.fragment==null||!r.fragment.applied().points().equals(r.settings.options().attachments().points())?mesh:com.sora.splineroads.core.RoadSlicing.attach(mesh,r.fragment.geometry()));});}
  public RoadGeometry.Mesh rawMesh() {return RAW_MESHES.get(this,RoadRecord::buildRawMesh);}
  private RoadGeometry.Mesh buildRawMesh() {
    if(toll!=null)return tollPlan().mesh();
    if (junction != null) {
      var mesh=junction.get().mesh();
      if(junction.spec().oneSide()!=null)return mesh;
      if(junction.geometryVersion()>=47&&junction.get().arm()>=0){var section=mesh.settings().options(mesh.settings().options().streetscape(settings.options().streetscape()));
        // A refuge cuts a polygon out of one side of an approach. Its stored
        // sections remain ordered along the original road, not a new driving
        // centerline through the asymmetric cut's section midpoints.
        if(junction.geometryVersion()>=49&&com.sora.splineroads.core.JunctionSlips.enabled(junction.spec()))return new Mesh(mesh.samples(),section,mesh.min(),mesh.max(),mesh.length(),mesh.closed());
        return com.sora.splineroads.core.RoadRibbon.mesh(mesh.samples(),section);
      }
      return mesh;
    }
    if (!alignment.isEmpty()) {
      var mesh = fragment==null?com.sora.splineroads.core.RoadRibbon.mesh(alignment, settings):com.sora.splineroads.core.RoadSlicing.mesh(alignment,settings);
      if(railway())return mesh;
      if (mesh.first().center().distance(mesh.last().center()) < 1e-6)
        return new Mesh(
            mesh.samples(),
            mesh.settings(),
            mesh.min(),
            mesh.max(),
            mesh.length(),
            true,
            mesh.controlPoint());
      return fragment==null?com.sora.splineroads.core.RoadAttachments.deform(mesh,settings):com.sora.splineroads.core.RoadSlicing.deform(mesh,settings,fragment);
    }
    return RoadGeometry.endCaps(
        (assembly==null?com.sora.splineroads.core.RoadMedianAnchor.apply(RoadGeometry.build(start,end,settings)):RoadGeometry.build(start,end,settings)), endCaps, start.grade(), end.grade());
  }
}
