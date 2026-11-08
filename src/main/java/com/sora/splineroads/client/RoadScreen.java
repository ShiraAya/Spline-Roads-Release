package com.sora.splineroads.client;
import com.sora.splineroads.core.RoadLanes;
import com.sora.splineroads.core.LaneRampAlignment;
import com.sora.splineroads.core.RoadTransitions;

import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.RoadPlanner;
import com.sora.splineroads.core.RoadInfrastructure;
import com.sora.splineroads.core.RoadProfile;
import com.sora.splineroads.core.RoadProfile.*;
import com.sora.splineroads.net.RoadNetwork;
import com.sora.splineroads.world.RoadData;
import com.sora.splineroads.world.RoadRecord;
import java.util.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

public final class RoadScreen extends Screen {
  private final CompoundTag payload;
  private final boolean node;
  private Options options = Options.DEFAULT;
  private boolean extraPage, liftPage, mapPage;
  private int controlIndex;
  private int optionsPage;


  private boolean laneRoad(){return payload.getString("Kind").equals("laneRampRoad");}
  private boolean sideRoad(){return payload.getString("Kind").equals("sideRoad");}
  private boolean sectionRoad(){return sideRoad()||payload.getString("Kind").equals("armRoad");}

  private boolean optionsOnly() {
    return !node && width < 512 && extraPage;
  }

  private final Map<String, EditBox> fields = new LinkedHashMap<>();
  private Mode mode;
  private Style style;
  private Structure structure;
  private double roadWidth, thickness, tension, arc;
  private int left, top, debounce;
  private String status = "";
  private boolean pending, advanced;
  private boolean savedPreviewAllowed=true;
  private Button save, pathButton, styleButton, delete, previewButton, failureButton;
  private String serverFailure="";
  private List<Mesh> failureRoads=List.of();
  private boolean returningFromFailureDetails;
  private static final Mode[] MODES = {Mode.AUTO, Mode.STRAIGHT, Mode.CURVE, Mode.ARC};
  private static final String[] STYLES = {"双向两车道", "双向四车道", "单向两车道", "无标线路面", "单车道匝道", "双车道匝道"};

  public RoadScreen(CompoundTag payload) {
    super(Component.literal("Spline Roads · 智能铺路"));
    this.payload = com.sora.splineroads.net.RoadDraft.baseline(payload);
    node = payload.getString("Kind").equals("node");

    advanced = payload.getBoolean("Advanced");
    extraPage = payload.getBoolean("ExtraPage");
    liftPage = (payload.getBoolean("LiftPage"));
    mapPage=payload.getBoolean("MapPage");controlIndex=payload.getInt("ControlIndex");

    optionsPage = Math.max(0,Math.min(advanced?8:7,payload.getInt("OptionsPage")));

    if (!node) {
      var s = RoadProfile.editable(RoadRecord.readSettings(payload.getCompound("Settings")));
      mode = s.mode();
      options = s.options();

      style = s.style();
      if(RoadProfile.catalog(style).type()==Type.HIGHWAY)this.payload.putBoolean("ForceJunction",false);

      // Opening a width/furniture editor must not silently change the vertical
      // planner: doing so discards the retained path of a planar-crossing slice.
      structure = s.structure();
      roadWidth = s.width();
      thickness = s.thickness();
      tension = s.tension();
      arc = s.arcDegrees();
    }
  }

  @Override
  protected void init() {
    fields.clear();
    pathButton = null;
    left = 12;
    top = Math.max(2, (height - 238) / 2);

    int x = left + 12, y = top + 40;
    if (node) {
      Node n = RoadRecord.readNode(payload.getCompound("Node"));
      BlockPos p = BlockPos.of(payload.getLong("Pos"));
      field("X 偏移", "x", n.position().x() - p.getX() - .5, x, y);
      field("Y 偏移", "y", n.position().y() - p.getY(), x + 136, y);
      field("Z 偏移", "z", n.position().z() - p.getZ() - .5, x, y + 42);
      field("朝向 / 度", "yaw", n.yaw(), x + 136, y + 42);
      field("坡度 / %", "grade", n.grade() * 100, x, y + 84);
      addRenderableWidget(
          Button.builder(
                  Component.literal("一键贴地"),
                  b -> {
                    fields.get("y").setValue("0");
                    changed();
                  })
              .bounds(x + 136, y + 96, 128, 18)
              .build());
    } else if (!optionsOnly()) {
      if(ManualPlannerClient.hasMap())addRenderableWidget(Button.builder(Component.literal("规划地图"),b->{if(pending||refresh())ManualPlannerClient.mapPreview(this);}).bounds(left+283,top+10,56,18).build());
      option(mapPage?"设置":"俯视图",left+145,top+10,52,()->{mapPage=!mapPage;if(width<512)extraPage=mapPage;});
      addRenderableWidget(
          Button.builder(
                  Component.literal(advanced ? "收起微调" : "展开微调"),
                  b -> {
                    if (capture()) {
                      advanced = !advanced;
                      optionsPage=Math.min(optionsPage,advanced?8:7);
                      payload.putBoolean("Advanced", advanced);
                      payload.putBoolean("ExtraPage", extraPage);
                      payload.putBoolean("LiftPage", liftPage);

                      rebuildWidgets();
                    }
                  })
              .bounds(left + 204, top + 10, 72, 18)
              .build());
      addRenderableWidget(
          Button.builder(
                  Component.literal(typeName()),
                  b -> {
                    if ((!capture())) return;
                    if(laneRoad())selectStyle(RoadProfile.highway(style)?Style.C1_RAMP:Style.C1_HIGHWAY_RAMP,true);
                    else applyLaneConfiguration(RoadProfile.highway(style)?Type.ORDINARY:Type.HIGHWAY,RoadLanes.counts(style,options),true);
                  })
              .bounds(x, y, advanced?98:64, 20)
              .build());
      styleButton =
          addRenderableWidget(
              Button.builder(
                      Component.literal(styleName()),
                      b -> {
                        if (!capture()) return;
                        openLaneConfiguration();
                      })
                  .bounds(x + (advanced?102:172), y, advanced?162:92, 20)
                  .build());
      pathButton =
          addRenderableWidget(
              Button.builder(
                      Component.literal("智能选线"),
                      b -> {
                        if (!capture()) return;


                        if(!options.routing().controls().isEmpty())return;
                        int i = Arrays.asList(MODES).indexOf(mode);
                        mode = MODES[(i + 1) % MODES.length];
                        changed();
                        refresh();
                      })
                  .bounds(x + (advanced?0:68), y + (advanced?24:0), advanced?264:100, 20)
                  .build());
      if (advanced) {
        field("单车道宽 / 格", "width", laneWidth(), x, y + 49);
        field("厚度 / 格", "thickness", thickness, x + 136, y + 49);
        field("曲线强度", "tension", tension, x, y + 81);
        field("圆弧角度 / 度", "angle", arc, x + 136, y + 81);
      } else {
        addRenderableWidget(
            Button.builder(
                    Component.literal(payload.getBoolean("LevelEnds") ? "两端贴地：开" : "两端贴地"),
                    b -> {
                      payload.putBoolean("LevelEnds", !payload.getBoolean("LevelEnds"));
                      rebuildWidgets();
                    })
                .bounds(x, top + 134, 128, 18)
                .build());
        addRenderableWidget(
            Button.builder(
                    Component.literal("−"),
                    b -> {
                      setLaneWidth(Math.max(2, laneWidth() - .5));
                      rebuildWidgets();
                    })
                .bounds(x, y + 24, 30, 20)
                .build());
        addRenderableWidget(
                    Button.builder(
                            Component.literal(
                                (RoadProfile.modern(style)
                                        ? "车道 " + format(laneWidth()) + " · 总宽 "
                                        : "路宽 ")
                                    + format(roadWidth)
                                    + " 格"),
                            b -> {})
                        .bounds(x + 34, y + 24, 196, 20)
                        .build())
                .active =
            false;
        addRenderableWidget(
            Button.builder(
                    Component.literal("+"),
                    b -> {
                      setLaneWidth(Math.min(RoadProfile.modern(style) ? 8 : 64, laneWidth() + .5));
                      rebuildWidgets();
                    })
                .bounds(x + 234, y + 24, 30, 20)
                .build());
        medianWidthRow(x,y+48,264,true);
        medianWidthRow(x,y+72,264,false);
      }
    }
    if (!node && !optionsOnly())
      addRenderableWidget(
          Button.builder(
                  Component.literal(structureName()),
                  b -> {
                    structure = switch(structure) {
                      case AUTO, GROUND -> Structure.BRIDGE;
                      case BRIDGE -> Structure.TUNNEL;
                      case TUNNEL -> Structure.AUTO;
                    };
                    b.setMessage(Component.literal(structureName()));
                    changed();
                    refresh();
                  })
              .bounds(x, top + 156, 264, 18)
              .build());
    if (!node) {
      if (width >= 512 || optionsOnly())
        buildOptions(width >= 512 ? left + 294 : left + 12, top + 40, width >= 512 ? 194 : 264);
      if (width < 512 && !optionsOnly())
        option("▸", x+240,top+134,24,()->{extraPage=true;liftPage=false;});
    }
    if((!node)&&!optionsOnly()&&(payload.getInt("DegreeA")>0||payload.getInt("DegreeB")>0)){
      for(int endpoint=0;endpoint<2;endpoint++){
        String side=endpoint==0?"A":"B";boolean required=payload.getBoolean("Center"+side)||payload.getInt("Degree"+side)>=2;
        boolean incompatible=false;
        try {
          incompatible=!required&&payload.getInt("Degree"+side)>0&&payload.contains("JoinSection"+side)&&!com.sora.splineroads.core.RoadTransitions.compatible(new Settings(mode,style,roadWidth,thickness,tension,arc).options(options),RoadRecord.readSettings(payload.getCompound("JoinSection"+side)));
        } catch(IllegalArgumentException e) { incompatible=true;status="连接数据无效，请关闭后重新右键道路："+e.getMessage(); }
        required|=incompatible;boolean force=com.sora.splineroads.world.AutoJunctions.forced(payload,side);
        String label=side+" 端："+(required?"已有/必要路口":force?"生成路口":"连续道路");
        var button=addRenderableWidget(Button.builder(Component.literal(label),b->{payload.putBoolean("ForceJunction"+side,!com.sora.splineroads.world.AutoJunctions.forced(payload,side));rebuildWidgets();}).bounds(x+endpoint*134,top+177,130,18).build());
        button.active=!required&&payload.getInt("Degree"+side)>0&&RoadProfile.catalog(style).type()==Type.ORDINARY;
      }
    }

    boolean edit = payload.hasUUID("Id")&&!sectionRoad();
    save =
        addRenderableWidget(
            Button.builder(Component.literal(node ? "保存端点" : "建造 / 保存"), b -> submit())
                .bounds(x, top + 206, edit ? 64 : 70, 20)
                .build());
    previewButton=addRenderableWidget(
        Button.builder(
                Component.literal("实景预览"),
                b -> {
                  clearFailure();
                  if (refresh()) {ManualPlannerClient.worldPreview();minecraft.setScreen(null);}
                })
            .bounds(x + (edit ? 68 : 74), top + 206, edit ? 56 : 62, 20)
            .build());
    failureButton=addRenderableWidget(Button.builder(Component.literal("失败详情"),
        b->minecraft.setScreen(new FailureDetails(this))).bounds(x+(edit?128:140),top+206,edit?56:62,20).build());
    failureButton.visible=!serverFailure.isEmpty();
    addRenderableWidget(
        Button.builder(
                Component.literal("取消"),
                b -> {
                  ClientRoads.discardDraft();
                  clearFailure();
                  ClientRoads.preview = null;
                  minecraft.setScreen(null);
                })
            .bounds(x + (edit ? 188 : 206), top + 206, edit ? 38 : 58, 20)
            .build());
    if (edit)
      delete = addRenderableWidget(
          Button.builder(
                  Component.literal("删除"),
                  b -> {
                    if (!pending) {
                      CompoundTag t = new CompoundTag();
                      t.putString("Action", "deletePreview");
                      t.putUUID("Id", payload.getUUID("Id"));
                      pending = true;
                      b.active = false;
                      ClientRoads.sendAction(t);
                    }
                  })
              .bounds(x + 230, top + 206, 34, 20)
              .build());

    if(sectionRoad()){
      if(pathButton!=null){pathButton.setMessage(Component.literal("接入线形随路口衔接"));pathButton.active=false;}
      for(var child:children())if(child instanceof Button b){String label=b.getMessage().getString();
        if((optionsPage==2||optionsPage==3)&&b.getX()>=(optionsOnly()?left+12:left+294)&&b.getY()>=top+40&&b.getY()<top+180)b.active=false;
        if(label.equals(typeName())||label.equals(structureName())||label.startsWith("两端贴地")||label.contains("靠左行驶")||label.startsWith("中间控制点")||label.contains("直路边缘")||label.startsWith("启用桥梁")||label.startsWith("启用隧道")||label.startsWith("龙门架")||label.startsWith("配跨")||label.startsWith("端点：")||label.startsWith("下潜：")||label.startsWith("最大坡度"))b.active=false;
      }
      for(String key:List.of("thickness","tension","angle","bridgeSpan","bridgeRise","tunnelHeight","tunnelDepth","gantrySpacing"))if(fields.containsKey(key))fields.get(key).setEditable(false);
    }
    if(laneRoad()){
      if(styleButton!=null)styleButton.active=false;
      if(pathButton!=null){pathButton.active=false;pathButton.setMessage(Component.literal("线路与扩出：用匝道连接器编辑"));}
      for(var child:children())if(child instanceof Button b&&b.getMessage().getString().startsWith("两端贴地"))b.active=false;
      for(String key:List.of("tension","angle"))if(fields.containsKey(key))fields.get(key).setEditable(false);
    }
    String retainedFailure=serverFailure;var retainedRoads=failureRoads;
    boolean restoreFailure=returningFromFailureDetails;returningFromFailureDetails=false;
    refresh();
    if(restoreFailure&&!retainedFailure.isEmpty())showFailure(retainedFailure,retainedRoads);
  }

  private String structureName() {
    return switch (structure) {
      case AUTO, GROUND -> "自动识别";
      case BRIDGE -> "桥梁："+options.infrastructure().bridge().label;
      case TUNNEL -> "地下："+options.infrastructure().tunnel().label;
    };
  }

  private void field(String label, String key, double value, int x, int y) {
    EditBox f = new EditBox(font, x, y + 12, 128, 18, Component.literal(label));
    f.setMaxLength(32);
    f.setValue(format(value));
    f.setResponder(s -> changed());
    fields.put(key, f);
    addRenderableWidget(f);
  }

  private String format(double n) {
    return Double.toString(n);
  }

  private double number(String key) {
    double n = Double.parseDouble(fields.get(key).getValue());
    if (!Double.isFinite(n)) throw new IllegalArgumentException("请输入有限数值");
    return n;
  }

  private boolean capture() {
    try {
      if (!node && fields.containsKey("width")) {
        setLaneWidth(number("width"));
        thickness = number("thickness");
        tension = number("tension");
        arc = number("angle");
      }
      if (!node && fields.containsKey("lift")){
        options=options.lift(number("liftPosition")/100,number("lift"));
      }
      if(fields.containsKey("controlX")){
        var cs=new ArrayList<>(options.routing().controls());var c=cs.get(controlIndex);
        cs.set(controlIndex,new Control(c.id(),new V(number("controlX"),number("controlY"),number("controlZ"))));options=options.route(options.routing().controls(cs));
      }
      if(!node&&!options.routing().controls().isEmpty())mode=Mode.CURVE;
      payload.putBoolean("MapPage",mapPage);payload.putInt("ControlIndex",controlIndex);
      if(!node)options=options.sidewalk(options.sidewalk().smooth(true));
      if(fields.containsKey("bridgeRise"))options=options.infrastructure(options.infrastructure().rise(number("bridgeRise")));
      if(fields.containsKey("bridgeSpan"))options=options.infrastructure(options.infrastructure().span(number("bridgeSpan")));
      if(fields.containsKey("tunnelHeight"))options=options.infrastructure(options.infrastructure().headroom(number("tunnelHeight")));
      if(fields.containsKey("tunnelDepth"))options=options.infrastructure(options.infrastructure().depth(number("tunnelDepth")));
      if(fields.containsKey("gantrySpacing"))options=options.infrastructure(options.infrastructure().spacing(number("gantrySpacing")));
      if(fields.containsKey("lampSpacing"))options=options.streetscape(options.streetscape().lampSpacing(number("lampSpacing")));
      if(fields.containsKey("walkLampSpacing"))options=options.streetscape(options.streetscape().walkLampSpacing(number("walkLampSpacing")));
      if(fields.containsKey("medianTreeSpacing"))options=options.streetscape(options.streetscape().plantings(options.streetscape().plantings().medianSpacing(number("medianTreeSpacing"))));
      if(fields.containsKey("separatorTreeSpacing"))options=options.streetscape(options.streetscape().plantings(options.streetscape().plantings().separatorSpacing(number("separatorTreeSpacing"))));
      if(fields.containsKey("plantingSpacing"))options=options.streetscape(options.streetscape().plantingSpacing(number("plantingSpacing")));
      payload.putInt("OptionsPage",optionsPage);
      return true;
    } catch (IllegalArgumentException e) {
      status = e instanceof NumberFormatException ? "请先填写完整数值" : e.getMessage();
      return false;
    }
  }

  private boolean junctionMode(){return !sectionRoad() && (com.sora.splineroads.world.AutoJunctions.requiresJunction(payload,new Settings(mode,style,roadWidth,thickness,tension,arc).structure(structure).options(options))
      ||RoadProfile.catalog(style).type()==RoadProfile.Type.HIGHWAY&&payload.getBoolean("AutoJunction")&&!com.sora.splineroads.core.RoadTunnelFit.enabled(new Settings(mode,style,roadWidth,thickness,tension,arc).structure(structure).options(options)));}
  private Settings settings() {
    if (!capture()) throw new IllegalArgumentException("请填写完整数值");
    if(laneRoad()||sectionRoad())return new Settings(mode,style,roadWidth,thickness,tension,arc).structure(structure).options(options);
    if(junctionMode())return new Settings(mode,style,roadWidth,thickness,tension,arc).structure(structure).options(options);
    return RoadData.joinSections(
        RoadData.joinWidths(
            new Settings(mode, style, roadWidth, thickness, tension, arc)
                .structure(structure)

                .options(options),
            payload.getDouble("JoinWidthA"),
            payload.getDouble("JoinWidthB")),
        payload);
  }

  private Node editedNode() {
    BlockPos p = BlockPos.of(payload.getLong("Pos"));
    return new Node(
        new V(p.getX() + .5 + number("x"), p.getY() + number("y"), p.getZ() + .5 + number("z")),
        number("yaw"),
        number("grade") / 100);
  }

  private void changed() {
    savedPreviewAllowed=false;
    ClientRoads.editorChanged(this);
    clearFailure();
    debounce = 4;
    status = "";
    ClientRoads.error = "";
  }

  private Object previewInput;
  private record RoadPreviewInput(Settings settings,boolean levelEnds,boolean forceJunction,
      boolean forceA,boolean forceB) {}
  private long previewRoadRevision=Long.MIN_VALUE;
  private Mesh resolvedPreview;
  private String resolvedStatus="";
  private Component resolvedPathLabel;
  private boolean refresh() {
    debounce=0;
    Object input;
    try { input=node?editedNode():new RoadPreviewInput(settings(),payload.getBoolean("LevelEnds"),
        payload.getBoolean("ForceJunction"),com.sora.splineroads.world.AutoJunctions.forced(payload,"A"),
        com.sora.splineroads.world.AutoJunctions.forced(payload,"B")); }
    catch(IllegalArgumentException error){return refreshPlan();}
    long revision=ClientRoads.INDEX.revision();
    if(input.equals(previewInput)&&previewRoadRevision==revision&&ClientRoads.preview==resolvedPreview
        &&resolvedPreview!=null&&!ClientRoads.conflictPreview){
      // Rebuilding widgets for a page/section change must reuse the resolved
      // geometry and furniture. They do not depend on the visible controls.
      clearFailure();status=resolvedStatus;ClientRoads.error="";
      if(pathButton!=null&&resolvedPathLabel!=null)pathButton.setMessage(resolvedPathLabel);
      if(save!=null)save.active=!pending;
      payload.putBoolean("Advanced",advanced);payload.putBoolean("ExtraPage",extraPage);payload.putBoolean("LiftPage",liftPage);
      ClientRoads.storeDraft(payload);RoadRenderer.previewFurnitureStatus(ClientRoads.preview);return true;
    }
    boolean valid=refreshPlan();
    if(valid){previewInput=input;previewRoadRevision=revision;resolvedPreview=ClientRoads.preview;resolvedStatus=status;resolvedPathLabel=pathButton==null?null:pathButton.getMessage();}
    else previewInput=null;
    return valid;
  }
  private boolean refreshPlan() {
    clearFailure();
    // A new editor or resumed draft owns the preview; an earlier rejected
    // road's red diagnostic must not tint this normal candidate.
    ClientRoads.conflictPreview=false;
    try {
      var candidates = new ArrayList<RoadRecord>();
      ClientRoads.nodePreviews = List.of();
      if (node) {
        Node n = editedNode();
        if (Math.abs(number("x")) > 8
            || Math.abs(number("z")) > 8
            || number("y") < -8
            || number("y") > 16
            || Math.abs(n.grade()) > .5) throw new IllegalArgumentException("偏移或坡度超出范围");
        payload.put("Node", RoadRecord.writeNode(n));
        ClientRoads.preview = null;
        List<Mesh> connected = new ArrayList<>();
        for (var tag : payload.getList("Attached", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
          CompoundTag entry = (CompoundTag) tag;
          var record = RoadRecord.load(entry.getCompound("Road"));
          var candidate=RoadData.planNodeEdit(record,BlockPos.of(payload.getLong("Pos")),n,
              RoadRecord.readNode(payload.getCompound("OriginalNode")),
              RoadData.readHint(entry.getCompound("AutoA")),RoadData.readHint(entry.getCompound("AutoB")));
          candidates.add(candidate);connected.add(candidate.mesh());
        }
        ClientRoads.nodePreviews = List.copyOf(connected);
        if (!connected.isEmpty()) ClientRoads.preview = connected.get(0);
        status = "保存时同步调整相连道路，并自动清障";
      } else {
        Settings s = settings();
        s.validate();
        payload.put("Settings", RoadRecord.writeSettings(s));
        // Keep the selected mode visible even when the automatic-junction preview returns early.
        if((pathButton!=null))pathButton.setMessage(Component.literal(mode==Mode.AUTO?"智能选线 ▸":"手动："+RoadPlanner.name(mode)+" ▸"));

        var saved=savedPreview();
        if(saved!=null){
          ClientRoads.preview=saved.mesh;ClientRoads.nodePreviews=List.of();
          RoadRenderer.previewCandidates(List.of(saved.record));
          status="已保存道路；修改参数后更新预览，保存时完整校验";
          furnitureStatus("");
          ClientRoads.error="";ClientRoads.storeDraft(payload);if(save!=null)save.active=!pending;return true;
        }

        if(laneRoad()){
          var cached=ClientRoads.INDEX.roads.get(payload.getUUID("Id"));
          var old=cached==null?RoadRecord.load(payload.getCompound("Road")):cached.record;
          // Attribute preview uses the saved path; route search belongs to explicit server validation.
          var candidate=old.settings(s);ClientRoads.preview=candidate.mesh();ClientRoads.nodePreviews=List.of();
          RoadRenderer.previewCandidates(List.of(candidate));
          status="单向 1 车道匝道；路宽、附属设置可编辑，路径使用匝道连接器调整";
          if(pathButton!=null)pathButton.setMessage(Component.literal("线路与扩出：用匝道连接器编辑"));
          ClientRoads.storeDraft(payload);if(save!=null)save.active=!pending;return true;
        }
        if(sideRoad()){
          var meshes=new ArrayList<Mesh>();var faces=new ArrayList<com.sora.splineroads.core.RoadSurface.Face>();
          for(String key:payload.getBoolean("Paired")?List.of("Spec","PairSpec"):List.of("Spec")){
            var spec=com.sora.splineroads.world.JunctionCodec.read(payload.getCompound(key));
            spec=com.sora.splineroads.world.SideRoads.preview(spec,s,key.equals("PairSpec")||!payload.getBoolean("MainFirst"));
            var plan=com.sora.splineroads.core.JunctionPlanner.plan(spec);meshes.add(plan.pieces().get(2).mesh());
            for(var piece:plan.pieces()){faces.addAll(piece.paint());piece.structures().forEach(p->faces.addAll(p.faces()));}
          }
          ClientRoads.preview=meshes.get(0);ClientRoads.nodePreviews=List.copyOf(meshes);JunctionScreen.previewMesh=ClientRoads.preview;
          RoadRenderer.previewCandidates(List.of());JunctionScreen.previewFaces=List.copyOf(faces);
          status="附属点连接道路 · 总宽 "+format(s.width())+" 格；路口设置使用路口编辑器";
          ClientRoads.storeDraft(payload);if(save!=null)save.active=!pending;return true;
        }
    if(payload.getString("Kind").equals("armRoad")){
          if(pathButton!=null)pathButton.setMessage(Component.literal("接入线形随路口衔接"));
          var spec=com.sora.splineroads.world.JunctionCodec.read(payload.getCompound("Spec"));int index=payload.getInt("Arm");
          var arms=new ArrayList<>(spec.arms());arms.set(index,com.sora.splineroads.world.JunctionRoads.change(arms.get(index),s));
          var plan=com.sora.splineroads.core.JunctionPlanner.plan(spec.arms(arms));var piece=plan.pieces().get(index);
          ClientRoads.preview=piece.mesh();ClientRoads.nodePreviews=List.of();JunctionScreen.previewMesh=piece.mesh();
          RoadRenderer.previewCandidates(List.of());
          var faces=new ArrayList<com.sora.splineroads.core.RoadSurface.Face>(piece.paint());piece.structures().forEach(p->faces.addAll(p.faces()));JunctionScreen.previewFaces=List.copyOf(faces);
          status="仅更新接入路段 "+(index+1)+" · 总宽 "+format(arms.get(index).width())+" 格";ClientRoads.storeDraft(payload);if(save!=null)save.active=!pending;return true;
        }
        if(junctionMode()){
          var draft=com.sora.splineroads.world.AutoJunctions.preview(payload);
          var selected=com.sora.splineroads.world.AutoJunctions.selected(draft,payload.hasUUID("Id")?payload.getUUID("Id"):com.sora.splineroads.world.AutoJunctions.proposed(payload).id());
          ClientRoads.nodePreviews=selected.stream().map(RoadRecord::mesh).toList();
          RoadRenderer.previewCandidates(selected);
          ClientRoads.preview=ClientRoads.nodePreviews.get(0);JunctionScreen.previewMesh=ClientRoads.preview;
          var faces=new ArrayList<com.sora.splineroads.core.RoadSurface.Face>();
          var walkGroups=new java.util.HashSet<com.sora.splineroads.core.JunctionSpec>();
          for(var r:selected)if(r.junction()!=null){var piece=r.junction().get();faces.addAll(com.sora.splineroads.core.RoadSurface.custom(new com.sora.splineroads.core.RoadSurface.Geometry(List.of(),List.of()),piece.mesh(),piece.paint()).markings());piece.structures().forEach(part->faces.addAll(part.faces()));}
          JunctionScreen.previewFaces=List.copyOf(faces);status="自动路口预览："+draft.centers().size()+" 处；各方向保留自己的车道和宽度";ClientRoads.storeDraft(payload);if(save!=null)save.active=!pending;return true;
        }
        var a =
            mode == Mode.AUTO && payload.contains("AutoA")
                ? RoadData.readHint(payload.getCompound("AutoA"))
                : RoadPlanner.Hint.free(RoadRecord.readNode(payload.getCompound("StartNode")));
        var b =
            mode == Mode.AUTO && payload.contains("AutoB")
                ? RoadData.readHint(payload.getCompound("AutoB"))
                : RoadPlanner.Hint.free(RoadRecord.readNode(payload.getCompound("EndNode")));
        if (payload.getBoolean("LevelEnds")) {
          a =
              new RoadPlanner.Hint(
                  RoadData.atGround(a.node(), BlockPos.of(payload.getLong("A"))),
                  a.headingLocked(),
                  true,
                  a.linked());
          b =
              new RoadPlanner.Hint(
                  RoadData.atGround(b.node(), BlockPos.of(payload.getLong("B"))),
                  b.headingLocked(),
                  true,
                  b.linked());
        }
        if(payload.getBoolean("LiveSectionA")&&payload.contains("AutoA")){var n=RoadData.readHint(payload.getCompound("AutoA")).node();a=new RoadPlanner.Hint(new Node(n.position(),a.node().yaw(),a.node().grade()),a.headingLocked(),a.gradeLocked(),a.linked());}
        if(payload.getBoolean("LiveSectionB")&&payload.contains("AutoB")){var n=RoadData.readHint(payload.getCompound("AutoB")).node();b=new RoadPlanner.Hint(new Node(n.position(),b.node().yaw(),b.node().grade()),b.headingLocked(),b.gradeLocked(),b.linked());}
        var previous=payload.contains("Road")?RoadRecord.load(payload.getCompound("Road")):null;
        var plan=previous==null?null:previous.retainedPlan(s,a,b,RoadRecord.readNode(payload.getCompound("StartNode")),RoadRecord.readNode(payload.getCompound("EndNode")));
        if(plan==null)plan = com.sora.splineroads.core.RoadTunnelFit.plan(a, b, s);
        var seamA=payload.contains("AutoA")?RoadData.readHint(payload.getCompound("AutoA")):null;
        var seamB=payload.contains("AutoB")?RoadData.readHint(payload.getCompound("AutoB")):null;
        if(payload.getBoolean("LevelEnds")) {
          seamA=com.sora.splineroads.core.RoadConnectionChecks.atLevel(seamA,BlockPos.of(payload.getLong("A")).getY());
          seamB=com.sora.splineroads.core.RoadConnectionChecks.atLevel(seamB,BlockPos.of(payload.getLong("B")).getY());
        }
        com.sora.splineroads.core.RoadConnectionChecks.require(plan,seamA,seamB);
        int caps =
            (payload.getCompound("AutoA").getBoolean("Linked") ? 0 : 1)
                | (payload.getCompound("AutoB").getBoolean("Linked") ? 0 : 2);
        // Use the same record path as construction: median anchoring, reserved
        // slots and ADD lanes are part of the preview, not just later world writes.
        var previewRecord=new RoadRecord(previous==null?new UUID(0,0):previous.id(),previous==null?new UUID(0,0):previous.owner(),
            BlockPos.of(payload.getLong("A")),BlockPos.of(payload.getLong("B")),plan.start(),plan.end(),plan.settings(),mode==Mode.AUTO,4).caps(caps);
        if(previous!=null)previewRecord=previous.retainAlignment(previewRecord,s,RoadRecord.readNode(payload.getCompound("StartNode")),RoadRecord.readNode(payload.getCompound("EndNode")));
        previewRecord=com.sora.splineroads.world.RoadCrossingTransitions.preview(previewRecord,payload);
        ClientRoads.preview=previewRecord.mesh();candidates.add(previewRecord);
        payload.put("Settings", RoadRecord.writeSettings(s));
        if (pathButton != null && !optionsOnly())
          pathButton.setMessage(
              Component.literal(
                  mode == Mode.AUTO
                          ? "智能识别：" + RoadPlanner.name(plan.settings().mode()) + "  ▸"
                          : "手动：" + RoadPlanner.name(mode) + "  ▸"));
        if(pathButton!=null&&!options.routing().controls().isEmpty()){pathButton.setMessage(Component.literal("平滑曲线 · "+options.routing().controls().size()+(options.routing().through()?" 个经过点":" 个控制点")));pathButton.active=false;}
        status = String.format(Locale.ROOT, "%s · %.1f 格", plan.reason(), plan.mesh().length());
        if(com.sora.splineroads.core.RoadVertical.automatic(s)){
          double da=plan.start().position().distance(a.node().position()),db=plan.end().position().distance(b.node().position());
          boolean rising=com.sora.splineroads.core.RoadVertical.rising(s);
          status=String.format(Locale.ROOT,(rising?"最高":"最低")+" Y %.2f；两端延长 %.1f / %.1f 格",rising?com.sora.splineroads.core.RoadVertical.maximum(plan.start(),plan.end(),s):com.sora.splineroads.core.RoadTunnel.minimum(plan.start(),plan.end(),s),da,db);
        }
      }
      payload.putBoolean("Advanced", advanced);
      payload.putBoolean("ExtraPage", extraPage);
      payload.putBoolean("LiftPage", liftPage);

      RoadRenderer.previewCandidates(candidates);
      ClientRoads.storeDraft(payload);
      ClientRoads.error = "";
      if (save != null) save.active = !pending;
      return true;
    } catch (IllegalArgumentException e) {
      payload.putBoolean("Advanced", advanced);
      payload.putBoolean("ExtraPage", extraPage);
      payload.putBoolean("LiftPage", liftPage);

      ClientRoads.storeDraft(payload);
      ClientRoads.preview = null;
      RoadRenderer.previewCandidates(List.of());
      status = e instanceof NumberFormatException ? "请输入完整数值" : e.getMessage();
      ClientRoads.error = status;
      if (save != null) save.active = false;
      if (pathButton != null)
        if (pathButton != null && !optionsOnly())
          pathButton.setMessage(
              Component.literal(
                  mode == Mode.AUTO ? "智能识别：需调整  ▸" : "手动：" + RoadPlanner.name(mode) + "  ▸"));
      return false;
    }
  }

  /** Show the authoritative saved geometry immediately. Dirty/resumed drafts,
   * moved endpoints, stale index entries and junction edits still replan. */
  private com.sora.splineroads.world.RoadIndex.Built savedPreview(){
    if(!savedPreviewAllowed||!payload.getString("Kind").equals("road")||!payload.hasUUID("Id")
        ||payload.getBoolean("LevelEnds")||payload.getBoolean("ForceJunction")
        ||payload.getBoolean("ForceJunctionA")||payload.getBoolean("ForceJunctionB")
        ||payload.contains("DraftConflict")||junctionMode())return null;
    var built=ClientRoads.INDEX.roads.get(payload.getUUID("Id"));
    if(built==null||built.record.junction()!=null||built.record.toll()!=null||built.record.railway()
        ||!built.record.header().equals(payload.getCompound("Road")))return null;
    var r=built.record;
    if(!r.start().equals(RoadRecord.readNode(payload.getCompound("StartNode")))
        ||!r.end().equals(RoadRecord.readNode(payload.getCompound("EndNode"))))return null;
    var expected=RoadProfile.editable(r.settings());
    if(r.automatic())expected=RoadPlanner.mode(expected,Mode.AUTO,expected.arcDegrees());
    var actual=new Settings(mode,style,roadWidth,thickness,tension,arc).structure(structure).options(options);
    return expected.equals(actual)?built:null;
  }

  private void submit() {
    if (pending || !refresh()) return;
    CompoundTag t = com.sora.splineroads.net.RoadDraft.action(payload, node);

    if(payload.getString("Kind").equals("armRoad")){t=new CompoundTag();t.putString("Action","junctionRoad");t.putUUID("Id",payload.getUUID("Id"));t.putInt("Arm",payload.getInt("Arm"));t.putInt("Signature",payload.getInt("Signature"));t.put("Settings",payload.getCompound("Settings").copy());}
    if(sideRoad())t=com.sora.splineroads.world.SideRoads.action(payload);
    if(laneRoad()){t=new CompoundTag();t.putString("Action","laneRampRoad");t.putUUID("Id",payload.getUUID("Id"));t.putInt("Signature",payload.getInt("Signature"));t.put("Settings",payload.getCompound("Settings").copy());}
    pending = true;
    save.active = false;
    status = "正在建造并清理障碍…";
    ClientRoads.sendAction(t);
  }

  /** A child settings screen can remain open while this submission completes. */
  void completed(){pending=false;}

  void requestSuperseded(){pending=false;if(save!=null)save.active=true;if(delete!=null)delete.active=true;}

  public void previewFailed(String reason) {
    pending = false;
    status = reason;
    if (save != null) save.active = false;
  }

  private String furnitureFailure="",beforeFurnitureFailure="";
  void furnitureStatus(String reason){
    if(pending||!serverFailure.isEmpty()||ClientRoads.conflictPreview)return;
    if(!reason.isEmpty()){
      if(furnitureFailure.isEmpty()||!status.equals(furnitureFailure))beforeFurnitureFailure=status;
      furnitureFailure=reason;status=reason;ClientRoads.error=reason;
      if(save!=null)save.active=false;
    }else if(!furnitureFailure.isEmpty()){
      if(status.equals(furnitureFailure))status=beforeFurnitureFailure;
      if(ClientRoads.error.equals(furnitureFailure))ClientRoads.error="";
      furnitureFailure="";
      if(save!=null&&ClientRoads.preview!=null&&!ClientRoads.conflictPreview)save.active=true;
    }
  }

  public void failed(String reason) {
    failed(reason,new CompoundTag());
  }

  public void failed(String reason,CompoundTag reply) {
    showFailure(reason,ClientRoads.conflictRoads(reply));
  }

  void failed(String reason,List<Mesh> roads){showFailure(reason,roads);}
  void failureGeometry(List<Mesh> roads){failureRoads=roads;}

  private void showFailure(String reason,List<Mesh> roads){
    pending = false;
    serverFailure=reason;failureRoads=roads;debounce=0;
    status=com.sora.splineroads.core.RoadConflictIds.display(reason);
    ClientRoads.error=status;
    ClientRoads.conflictPreview=!roads.isEmpty();
    if(!roads.isEmpty()){ClientRoads.preview=roads.get(0);ClientRoads.nodePreviews=roads;}
    if(save!=null)save.active = true;
    if(delete != null)delete.active = true;
    if(previewButton!=null)previewButton.active=true;
    if(failureButton!=null)failureButton.visible=true;
  }

  private void clearFailure(){
    if(!serverFailure.isEmpty()){
      serverFailure="";failureRoads=List.of();ClientRoads.conflictPreview=false;
      ClientRoads.preview=null;ClientRoads.nodePreviews=List.of();
      if(previewButton!=null)previewButton.setMessage(Component.literal("实景预览"));
      if(failureButton!=null)failureButton.visible=false;
    }
  }

  private static final class FailureDetails extends Screen {
    private final RoadScreen parent;private Button world;
    FailureDetails(RoadScreen parent){super(Component.literal("道路保存失败详情"));this.parent=parent;}
    @Override protected void init(){
      addRenderableWidget(Button.builder(Component.literal("返回道路设置"),b->onClose())
          .bounds(width/2-134,height-30,130,20).build());
      world=addRenderableWidget(Button.builder(Component.literal("查看标红道路"),b->parent.onClose())
          .bounds(width/2+4,height-30,130,20).build());
      world.active=!parent.failureRoads.isEmpty();
    }
    @Override public void tick(){super.tick();if(world!=null)world.active=!parent.failureRoads.isEmpty();}
    @Override public void render(GuiGraphics g,int mx,int my,float dt){
      g.fill(12,12,width-12,height-12,0xf015222e);
      g.drawString(font,"道路未保存：完整诊断",24,24,0xffcf8c,false);
      String message=com.sora.splineroads.core.RoadConflictIds.display(parent.serverFailure);
      if(!parent.failureRoads.isEmpty())message+="；失败道路已标红，透过上方道路也可见。按提示坐标查找；它可能是此次编辑联动的邻路。";
      g.drawWordWrap(font,Component.literal(message),24,44,Math.max(100,width-48),0xffffff);
      super.render(g,mx,my,dt);
    }
    @Override public void onClose(){parent.returningFromFailureDetails=true;minecraft.setScreen(parent);}
    @Override public boolean isPauseScreen(){return false;}
  }

  @Override
  public void tick() {
    super.tick();
    fields.values().forEach(EditBox::tick);
    if (debounce > 0 && --debounce == 0) refresh();
    // The open-editor packet can precede its road/index snapshot. Adopt that
    // authoritative saved geometry when it arrives, without requiring a page
    // change to escape a speculative support plan made against partial context.
    long revision=ClientRoads.INDEX.revision();
    if(savedPreviewAllowed&&!pending&&debounce==0&&serverFailure.isEmpty()
        &&!ClientRoads.conflictPreview&&revision!=observedSavedRevision){
      observedSavedRevision=revision;
      var saved=savedPreview();if(saved!=null&&ClientRoads.preview!=saved.mesh)refresh();
    }
  }
  private long observedSavedRevision=Long.MIN_VALUE;

  @Override
  public boolean isPauseScreen() {
    return false;
  }

  @Override
  public void onClose() {
    if(serverFailure.isEmpty())refresh();
    super.onClose();
  }

  @Override
  public void render(GuiGraphics g, int mx, int my, float dt) {

    g.fill(left, top, left + 288, top + 238, 0xEC12212C);
    g.fill(left, top, left + 4, top + 238, 0xFF50DED3);
    g.drawString(
        font,
        node ? "端点微调" : "SR · 智能铺路",
        left + 12,
        top + 12,
        0xFFFFFF,
        false);
    g.drawString(
        font,
        node
            ? "普通铺路无需调整这些参数"
            : optionsOnly() ? "" : "选两点 → 自动预览 → 建造",
        left + 12,
        top + 27,
        0xA8C3CB,
        false);
    if (node) {
      label(g, "X 偏移", "x");
      label(g, "Y 偏移", "y");
      label(g, "Z 偏移", "z");
      label(g, "朝向 / 度", "yaw");
      label(g, "坡度 / %", "grade");
    } else if (!optionsOnly() && advanced) {
      label(g, "单车道宽 / 格", "width");
      label(g, "厚度 / 格", "thickness");
      label(g, "曲线强度", "tension");
      label(g, "圆弧角度 / 度", "angle");
    } else if (!optionsOnly()) {
      if (ClientRoads.preview != null)
        g.drawString(
            font,
            "Y "
                + format(ClientRoads.preview.first().center().y())
                + " → "
                + format(ClientRoads.preview.last().center().y()),
            left + 148,
            top + 139,
            0xBBD3DC,
            false);

    }
    if ((!optionsOnly()&&!node)&&(payload.getInt("DegreeA")>0||payload.getInt("DegreeB")>0))
      g.drawString(font,font.plainSubstrByWidth(status,264),left+12,top+196,ClientRoads.error.isEmpty()?0xB9D4DD:0xFF9B89,false);
    else if (!optionsOnly())
      g.drawWordWrap(
          font,
          Component.literal(status),
          left + 12,
          top + 180,
          264,
          ClientRoads.error.isEmpty() ? 0xB9D4DD : 0xFF9B89);
    if (!node && (width >= 512 || optionsOnly())) {
      int sx = width >= 512 ? left + 294 : left + 12;
      if (width >= 512) g.fill(sx - 6, top, sx + 200, top + 238, 0xEC12212C);
      if(mapPage){renderMap(g,sx,top+27,width>=512?194:264);}
      else {
      g.drawString(font, liftPage ? "高度控制点与自动避让" : new String[]{"基本选项","人行道","桥梁 / 跨线桥","地下通道","龙门架 / 隔音板","道路 / 人行道路灯","绿化 / 包边","隔离带植树","中央隔离宽度"}[optionsPage]+" · "+(optionsPage+1)+(advanced?"/9":"/8"), sx, top + 27, 0xFFFFFF, false);
      if(optionsPage>=5&&!liftPage) {
        label(g,"道路路灯间距 / 8–96 格","lampSpacing");label(g,"人行道灯距 / 8–96 格","walkLampSpacing");label(g,"绿化间距 / 8–64 格","plantingSpacing");label(g,"植树间距 / 8–64 格","medianTreeSpacing");label(g,"植树间距 / 8–64 格","separatorTreeSpacing");
        if(optionsPage<7)g.drawWordWrap(font,Component.literal(optionsPage==5?"灯型按道路断面自动选择；高速不设路灯，隧道使用洞内照明。":"绿化仅地面段生成；与灯位冲突时让位。空间不足自动拓宽人行道，盲道保留在外侧。"),sx,top+171,width>=512?194:264,0xBBD3DC);
      } else if(optionsPage>=2&&!liftPage) {
        if (optionsPage == 2) {
          label(g,"手动桥跨 / 格","bridgeSpan");
          label(g,"抬升高度 / 格","bridgeRise");
        }
        else if (optionsPage == 3) {
          label(g,"边缘净高 / 格","tunnelHeight");
          label(g,"下潜深度 / 格","tunnelDepth");
        }
        else
          label(g,"布设间距 / 格","gantrySpacing");
        String help=optionsPage==2?"小河用梁桥，跨道路用跨线桥；大河可用拱桥或斜拉桥，跨海可用悬索桥或连续梁。斜拉 / 悬索可自动按全长配跨。抬升从较高端向上量。":optionsPage==3?"两端为洞口；深度从较低洞口向下量，0 沿端点。":"隔音板用于高架，左右按 A→B。龙门架不用于匝道、有顶隧道或开放隧道（含自动识别）。";
        if(optionsPage==3&&options.infrastructure().tunnelDepth()>0){
          double low=Math.min(payload.getCompound("StartNode").getDouble("Y"),payload.getCompound("EndNode").getDouble("Y"))-options.infrastructure().tunnelDepth();
          help=String.format(Locale.ROOT,"最低路面 Y %.2f；深度从较低洞口向下量。",low);
        }
        if((optionsPage==2||optionsPage==3)&&!status.isEmpty())help=status;
        if((optionsPage==2||optionsPage==3)&&!ClientRoads.error.isEmpty())help=ClientRoads.error;
        g.drawWordWrap(font,Component.literal(help),sx,top+(optionsPage==2?190:optionsPage==3?(optionsOnly()?181:166):158),width>=512?194:264,optionsPage==3&&!ClientRoads.error.isEmpty()?0xFF9B89:0xBBD3DC);
      } else if(optionsPage==1&&!liftPage) {

        
      } else if (liftPage) {
        label(g, "沿路位置 / %", "liftPosition");
        label(g, "相对抬升 / 格", "lift");
        g.drawWordWrap(
            font,
            Component.literal("保持两端高度与接入坡度；正值上抬，负值下挖。"),
            sx,
            top + (128),
            width >= 512 ? 194 : 264,
            0xBBD3DC);
      } else {
        String note =
            (laneRoad()||style.connectorRamp())
                ? "一般箭头关闭；额外扩入保留并线导向"
                : RoadProfile.catalog(style).type() == Type.HIGHWAY
                ? ""
                : RoadProfile.modern(style) ? "" : "旧断面保留；切换样式升级";
        g.drawWordWrap(
            font,
            Component.literal(width >= 512 ? note : ""),
            sx,
            top + 214,
            width >= 512 ? 110 : 154,
            0xBBD3DC);
      }
    }
    }
    super.render(g, mx, my, dt);
  }

  private double laneWidth() {
    return RoadProfile.modern(style)
        ? RoadProfile.layout(
                new Settings(Mode.AUTO, style, roadWidth, thickness, tension, arc).options(options),
                roadWidth)
            .laneWidth()
        : roadWidth;
  }

  private void setLaneWidth(double value) {
    if(laneRoad()||style.connectorRamp()){
      var sized=LaneRampAlignment.usableWidth(new Settings(Mode.AUTO,style,roadWidth,thickness,tension,arc).options(options),value);
      roadWidth=sized.width();options=sized.options();return;
    }
    if(RoadProfile.modern(style)){
      var sized=RoadLanes.width(new Settings(Mode.AUTO,style,roadWidth,thickness,tension,arc).options(options),value);
      roadWidth=sized.width();options=sized.options();
    }else roadWidth=value;
  }

  private String typeName() {
    if(laneRoad()||style.connectorRamp())return RoadProfile.highway(style)?"自由匝道（高速）":"自由匝道（普通）";
    return RoadProfile.catalog(style).type() == Type.HIGHWAY ? "高速道路" : "普通道路";
  }

  private String styleName() {
    if(laneRoad()||style.connectorRamp())return "单车道匝道";
    if(RoadProfile.modern(style)&&!advanced){var counts=RoadLanes.counts(style,options);return (counts.reverse()>0?counts.forward()+"+"+counts.reverse():"单向 "+counts.forward())+" 车道 ▸";}
    return RoadProfile.modern(style) ? RoadLanes.counts(style,options).label()+" ▸" : STYLES[style.ordinal()];
  }

  private void selectStyle(Style next, boolean resetWidth) {
    double lane = RoadProfile.modern(style) ? laneWidth() : 4;
    style = next;

    if(RoadProfile.catalog(style).type()==Type.HIGHWAY)payload.putBoolean("ForceJunction",false);
    if ((RoadProfile.catalog(style).type()!=Type.ORDINARY)) options=options.sidewalk(options.sidewalk().enabled(false));
    if (resetWidth) lane = RoadProfile.catalog(style).laneWidth();
    setLaneWidth(lane);
    rebuildWidgets();
  }

  private void openLaneConfiguration() {
    if(laneRoad()||style.connectorRamp())return;
    minecraft.setScreen(new RoadLaneConfigScreen(this,RoadProfile.highway(style)?Type.HIGHWAY:Type.ORDINARY,RoadLanes.counts(style,options)));
  }
  void applyLaneConfiguration(Type type,RoadLanes.Counts counts,boolean resetWidth) {
    double lane=resetWidth?(type==Type.HIGHWAY?5:4):laneWidth();
    style=RoadLanes.carrier(type,counts,RoadProfile.catalog(style).median());
    options=options.lanes(counts).ends(RoadTransitions.Ends.NONE);
    if(type==Type.HIGHWAY){payload.putBoolean("ForceJunction",false);options=options.sidewalk(options.sidewalk().enabled(false));}
    setLaneWidth(lane);changed();rebuildWidgets();
  }

  private Button option(String text, int x, int y, int w, Runnable action) {
    int buttonHeight=20;
    if((optionsOnly()&&optionsPage==0&&!liftPage)&&y>=top+40){y=top+55+(y-top-40)*5/6;buttonHeight=18;}
    return addRenderableWidget(
        Button.builder(
                Component.literal(text),
                b -> {
                  if (capture()) {
                    action.run();
                    rebuildWidgets();
                  }
                })
            .bounds(x, y, w, buttonHeight)
            .build());
  }

  private void page(int delta){

    var pages=advanced?List.of(0,1,2,3,4,5,6,7,8):List.of(0,1,2,3,4,5,6,7);
    optionsPage=pages.get(Math.floorMod(pages.indexOf(optionsPage)+delta,pages.size()));liftPage=false;
  }

  private void changeMedianWidth(boolean green,double delta){
    double lane=laneWidth();var p=options.streetscape().plantings();
    options=options.streetscape(options.streetscape().plantings(green?p.greenWidth(p.greenWidth()+delta):p.railGap(p.railGap()+delta)));setLaneWidth(lane);
  }

  private void medianWidthRow(int x,int y,int w,boolean green){
    var p=options.streetscape().plantings();double value=green?p.greenWidth():p.railGap();
    option("−",x,y,30,()->changeMedianWidth(green,-.5)).active=value>(green?.5:0);
    option((green?"绿化宽度：":"护栏间距：")+format(value)+" 格",x+34,y,w-68,()->{}).active=false;
    option("+",x+w-30,y,30,()->changeMedianWidth(green,.5)).active=value<(green?8:7);
  }

  private void buildOptions(int x, int y, int w) {
    if(mapPage){
      var cs=options.routing().controls();controlIndex=Math.max(0,Math.min(controlIndex,cs.size()-1));
      if(!cs.isEmpty()){
        option("◀",x,top+150,24,()->controlIndex=Math.floorMod(controlIndex-1,cs.size()));
        option((options.routing().through()?"经过点 ":"控制点 ")+(controlIndex+1)+" / "+cs.size(),x+28,top+150,w-56,()->{});
        option("▶",x+w-24,top+150,24,()->controlIndex=(controlIndex+1)%cs.size());
        var p=cs.get(controlIndex).position();int fw=(w-8)/3;
        String[] keys={"controlX","controlY","controlZ"};double[] values={p.x(),p.y(),p.z()};
        for(int i=0;i<3;i++){var f=new EditBox(font,x+i*(fw+4),top+184,fw,18,Component.literal((options.routing().through()?"经过点 ":"控制点 ")+"XYZ".charAt(i)));f.setMaxLength(24);f.setValue(Double.toString(values[i]));f.setResponder(v->changed());fields.put(keys[i],f);addRenderableWidget(f);}
      }
      option("返回",x+w-58,width<512?top+10:top+206,58,()->{mapPage=false;if(width<512)extraPage=false;});return;
    }
    int navY=liftPage?(optionsOnly()?top+182:top+206):optionsOnly()?top+31:top+206;
    option("◀",x+w-58,navY,26,()->page(-1));
    option("▶",x+w-28,navY,26,()->page(1));

    if((optionsOnly()&&optionsPage>=1&&!liftPage))y=Math.max(y,top+55);
    if(optionsPage>=7&&!liftPage){
      if(optionsPage==8){medianWidthRow(x,y,w,true);medianWidthRow(x,y+28,w,false);}
      else for(boolean median:new boolean[]{true,false}){
        var plants=options.streetscape().plantings();var catalog=RoadProfile.catalog(style);
        var type=median?plants.medianTrees():plants.separatorTrees();int row=y+(median?0:78);
        boolean available=structure!=Structure.TUNNEL&&(median?catalog.twoWay()&&catalog.median()==Median.GREEN&&plants.greenWidth()>=1.5:options.cycle()&&!options.streetscape().parking()&&options.streetscape().separator()==com.sora.splineroads.core.RoadStreetscape.Separator.GREEN);
        option((median?"中央：":"非机动车隔离：")+type.label+" ▸",x,row,w,()->{var current=options.streetscape().plantings();var types=com.sora.splineroads.core.RoadStreetscape.Planting.values();var next=types[(type.ordinal()+1)%5];options=options.streetscape(options.streetscape().plantings(median?current.medianTrees(next):current.separatorTrees(next)));}).active=available;
        String key=median?"medianTreeSpacing":"separatorTreeSpacing";field("植树间距 / 8–64 格",key,median?plants.medianSpacing():plants.separatorSpacing(),x,row+27);fields.get(key).setWidth(w);
      }
      if(optionsOnly())option("返回道路设置",x+164,top+10,100,()->extraPage=false);return;
    }
    if((optionsPage>=5&&!liftPage)){
      if(!com.sora.splineroads.core.RoadStreetscape.walkLampAllowed(options,structure,style)&&options.streetscape().walkLamps())options=options.streetscape(options.streetscape().walkLamps(false));
      var c=options.streetscape();boolean eligible=(RoadProfile.catalog(style).type()==Type.ORDINARY);
      if(optionsPage==5){
        field("道路路灯间距 / 8–96 格","lampSpacing",c.lampSpacing(),x,y);fields.get("lampSpacing").setWidth(w);
        option("人行道路灯："+(c.walkLamps()?"开启":com.sora.splineroads.core.RoadStreetscape.walkLampAllowed(options,structure,style)?"关闭":"关闭（当前断面禁用）"),x,y+42,w,()->options=options.streetscape(options.streetscape().walkLamps(!options.streetscape().walkLamps()))).active=eligible&&com.sora.splineroads.core.RoadStreetscape.walkLampAllowed(options,structure,style);
        field("人行道灯距 / 8–96 格","walkLampSpacing",c.walkLampSpacing(),x,y+68);fields.get("walkLampSpacing").setWidth(w);
      }else{
        option("人行道绿化："+c.planting().label+" ▸",x,y,w,()->{var values=com.sora.splineroads.core.RoadStreetscape.Planting.values();options=options.streetscape(options.streetscape().planting(values[(options.streetscape().planting().ordinal()+1)%values.length]));}).active=eligible&&options.sidewalk().enabled()&&structure!=Structure.TUNNEL;
        field("绿化间距 / 8–64 格","plantingSpacing",c.plantingSpacing(),x,y+36);fields.get("plantingSpacing").setWidth(w);
        option("道路包边："+c.apron().label+" ▸",x,y+72,w,()->{var values=com.sora.splineroads.core.RoadStreetscape.Apron.values();options=options.streetscape(options.streetscape().apron(values[(options.streetscape().apron().ordinal()+1)%values.length]));});
      }
      if(optionsOnly())option("返回道路设置",x+164,top+10,100,()->extraPage=false);return;
    }
    if((optionsPage>=2&&!liftPage)) {
      var infra=options.infrastructure();
      if(optionsPage==2||optionsPage==3){
        boolean bridge=optionsPage==2;int half=(w-8)/2,right=x+(w+8)/2;
        option(bridge?"启用桥梁":"启用隧道",x,y,half,()->structure=bridge?Structure.BRIDGE:Structure.TUNNEL);
        option((bridge?infra.bridge().label:infra.tunnel().label)+" ▸",right,y,half,()->{
          options=options.infrastructure(bridge?infra.bridge(RoadInfrastructure.Bridge.values()[(infra.bridge().ordinal()+1)%RoadInfrastructure.Bridge.values().length]):infra.tunnel(RoadInfrastructure.Tunnel.values()[(infra.tunnel().ordinal()+1)%2]));
          structure=bridge?Structure.BRIDGE:Structure.TUNNEL;
        });
        String upper=bridge?"bridgeSpan":"tunnelHeight",lower=bridge?"bridgeRise":"tunnelDepth";
        field(bridge?"手动桥跨 / 格":"边缘净高 / 格",upper,bridge?infra.span():infra.headroom(),x,y+32);fields.get(upper).setWidth(half);
        field(bridge?"抬升高度 / 格":"下潜深度 / 格",lower,bridge?infra.bridgeRise():infra.tunnelDepth(),x,y+66);fields.get(lower).setWidth(half);
        if(bridge){
          option("配跨："+(infra.autoSpan()?"自动":"手动"),right,y+44,half,()->options=options.infrastructure(options.infrastructure().autoSpan(!infra.autoSpan())))
              .active=infra.bridge()==RoadInfrastructure.Bridge.CABLE||infra.bridge()==RoadInfrastructure.Bridge.SUSPENSION;
        }else option("下潜："+(infra.tunnelDepth()>0?"开":"关"),right,y+44,half,()->{
          options=options.lift(.5,0).infrastructure(options.infrastructure().depth(infra.tunnelDepth()>0?0:8));structure=Structure.TUNNEL;
        });
        option("端点："+infra.adjustment().label,right,y+78,half,()->{
          var modes=com.sora.splineroads.core.RoadTunnelFit.Adjustment.values();
          options=options.infrastructure(options.infrastructure().adjustment(modes[(infra.adjustment().ordinal()+1)%modes.length]));
        });
        option(infra.maxGrade()==0?"最大坡度：旧版 ▸":String.format(Locale.ROOT,"最大坡度：%.0f%% ▸",infra.maxGrade()*100),x,y+102,w,()->{
          double grade=infra.maxGrade(),next=grade==0?.06:grade<.04?.04:grade<.06?.06:grade<.08?.08:grade<.10?.10:grade<.12?.12:.04;
          options=options.infrastructure(options.infrastructure().grade(next));
        });
        if(bridge)option("高架中央转护栏："+infra.medianConversion().label+" ▸",x,y+126,w,()->{
          var modes=RoadInfrastructure.MedianConversion.values();
          options=options.infrastructure(options.infrastructure().medianConversion(modes[(infra.medianConversion().ordinal()+1)%modes.length]));
        });
      }else{
        option("龙门架："+(infra.gantry()==RoadInfrastructure.Gantry.OFF?"关闭":"开启"),x,y,w,()->options=options.infrastructure(infra.gantry(infra.gantry()==RoadInfrastructure.Gantry.OFF?RoadInfrastructure.Gantry.FRAME:RoadInfrastructure.Gantry.OFF)));
        field("布设间距 / 格","gantrySpacing",infra.spacing(),x,y+32);
        option("高架外侧："+options.outerRail().label+" ▸",x,y+70,w,()->options=options.outerRail(OuterRail.values()[(options.outerRail().ordinal()+1)%OuterRail.values().length])).active=(structure!=Structure.TUNNEL);
      }
      if(optionsOnly())option("返回道路设置",x+164,top+10,100,()->extraPage=false);
      return;
    }
    if((optionsPage==1&&!liftPage)) {
      boolean eligible=(structure!=Structure.TUNNEL)&&RoadProfile.catalog(style).type()==Type.ORDINARY;
      var walk=options.sidewalk();
      option((walk.enabled()?"☑":"☐")+" 智能人行道",x,y,w,()->options=options.sidewalk(options.sidewalk().enabled(!options.sidewalk().enabled()))).active=eligible;
      option("铺设位置："+walk.side().label,x,y+24,w,()->options=options.sidewalk(options.sidewalk().side(com.sora.splineroads.core.RoadSidewalks.Side.values()[(options.sidewalk().side().ordinal()+1)%3]))).active=eligible;
      option("−",x,y+48,26,()->options=options.sidewalk(options.sidewalk().width(Math.max(1,options.sidewalk().width()-1)))).active=eligible;
      option("宽度 "+walk.width()+" 格",x+30,y+48,w-60,()->{}).active=false;
      option("+",x+w-26,y+48,26,()->options=options.sidewalk(options.sidewalk().width(Math.min(15,options.sidewalk().width()+1)))).active=eligible;
      option("材质："+com.sora.splineroads.core.RoadSidewalks.Finish.of(walk.material()).label+" ▸",x,y+76,w,()->{
        var values=com.sora.splineroads.core.RoadSidewalks.Finish.values();options=options.sidewalk(options.sidewalk().material(values[(com.sora.splineroads.core.RoadSidewalks.Finish.of(options.sidewalk().material()).ordinal()+1)%values.length].id));
      }).active=eligible;
      option(walk.width()>=2?"盲道："+(walk.tactile()?"开":"关"):"盲道：宽度需至少 2 格",x,y+104,w,()->options=options.sidewalk(options.sidewalk().tactile(!options.sidewalk().tactile()))).active=eligible&&walk.enabled()&&walk.width()>=2;
      if(optionsOnly())option("返回道路设置",x+164,top+10,100,()->extraPage=false);
      return;
    }

    if (liftPage) {
      field("沿路位置 / %", "liftPosition", options.liftPosition() * 100, x, y + 4);
      fields.get("liftPosition").setWidth((w - 8) / 2);
      field("相对抬升 / 格", "lift", options.liftHeight(), x + (w + 8) / 2, y + 4);
      fields.get("lift").setWidth((w - 8) / 2);
      option(
          "降低 1 格",
          x,
          y + 40,
          (w - 8) / 2,
          () ->
              options =
                  options.lift(options.liftPosition(), Math.max(-64, options.liftHeight() - 1)));
      option(
          "抬高 1 格",
          x + (w + 8) / 2,
          y + 40,
          (w - 8) / 2,
          () ->
              options =
                  options.lift(options.liftPosition(), Math.min(64, options.liftHeight() + 1)));
      option("重置控制点", x, y + 64, w, () -> options = options.lift(.5, 0));
      option("返回额外选项", x, y + 126, w, () -> liftPage = false);
      if (optionsOnly()) option("返回道路设置", x, y + 150, w, () -> extraPage = false);
      return;
    }
    option(
        (options.leftTraffic() ? "☑" : "☐") + " 靠左行驶（默认靠右）",
        x,
        y,
        w,
        () -> options = options.traffic(!options.leftTraffic()));
    var c = RoadProfile.catalog(style);
    boolean ordinary = c.type() == Type.ORDINARY;
    {
      option(
                  RoadProfile.medianName(c.median()) + " ▸",
                  x,
                  y + 24,
                  w,
                  () -> {
                    if (!c.twoWay() || c.type() == Type.LEGACY) return;
                    List<Median> choices =
                        c.type() == Type.HIGHWAY
                            ? List.of(Median.RAIL, Median.GREEN)
                            : c.lanes() == 2
                                ? List.of(
                                    Median.DOUBLE_YELLOW,
                                    Median.DASHED_YELLOW,
                                    Median.RAIL,
                                    Median.GREEN)
                                : List.of(Median.DOUBLE_YELLOW, Median.RAIL, Median.GREEN);
                    Median m = choices.get((choices.indexOf(c.median()) + 1) % choices.size());
                    double lane = laneWidth();
                    style = RoadLanes.carrier(c.type(),RoadLanes.counts(style,options),m);
                    setLaneWidth(lane);
                  })
              .active =
          c.twoWay() && c.type() != Type.LEGACY;
      option(
                  options.cycleFinish().label,
                  x,
                  y + 48,
                  w,
                  () -> {
                    double lane = laneWidth();
                    options = options.cycleFinish(Options.CycleFinish.values()[(options.cycleFinish().ordinal()+1)%(structure==Structure.TUNNEL?Options.CycleFinish.values().length-1:Options.CycleFinish.values().length)]);
                    setLaneWidth(lane);
                  })
              .active =
          ordinary;
      option(
                  "非机动车分隔：" + options.streetscape().separator().label,
                  x,
                  y + 72,
                  w,
                  () -> {double lane=laneWidth();options=options.streetscape(options.streetscape().separator(com.sora.splineroads.core.RoadStreetscape.Separator.values()[(options.streetscape().separator().ordinal()+1)%3]));setLaneWidth(lane);})
              .active =
          ordinary && options.cycle()&&!options.streetscape().parking()&&structure!=Structure.TUNNEL;
      option(
                  (options.curb() ? "☑" : "☐") + " 最外侧马路牙子",
                  x,
                  y + 96,
                  w,
                  () -> {
                    double lane = laneWidth();
                    options = options.extras(options.cycle(), options.cycleRail(), !options.curb());
                    setLaneWidth(lane);
                  })
              .active =
          ordinary;
    }
    option("中间控制点："+format(options.liftHeight())+" 格 ▸",x,y+120,w,()->liftPage=true);
    option(
          (options.routing().fitEdges() ? "☑" : "☐") + " 直路边缘对齐方块",
          x,
          y + 144,
          w,
          () -> options = options.route(options.routing().fit(!options.routing().fitEdges())));
    if (optionsOnly()) option("返回道路设置", x + 164, top + 10, 100, () -> extraPage = false);
  }

  private void label(GuiGraphics g, String text, String key) {
    EditBox f = fields.get(key);
    if (f == null) return;
    g.drawString(font, text, f.getX(), f.getY() - 11, 0xBBD3DC, false);
  }

  private double[] mapTransform(int x,int y,int w){
    var mesh=ClientRoads.preview;double minX=Double.POSITIVE_INFINITY,minZ=minX,maxX=-minX,maxZ=-minX;
    var points=new ArrayList<V>();if(mesh!=null)for(var sample:mesh.samples())points.add(sample.center());
    for(var c:options.routing().controls())points.add(c.position());
    if(points.isEmpty())return new double[]{0,0,1,x+8,y+20};
    for(var p:points){minX=Math.min(minX,p.x());minZ=Math.min(minZ,p.z());maxX=Math.max(maxX,p.x());maxZ=Math.max(maxZ,p.z());}
    double scale=Math.min((w-24)/Math.max(1,maxX-minX),90/Math.max(1,maxZ-minZ));
    return new double[]{minX,minZ,scale,x+(w-(maxX-minX)*scale)/2,y+22+(90-(maxZ-minZ)*scale)/2};
  }
  private static int mapX(V p,double[] t){return (int)Math.round(t[3]+(p.x()-t[0])*t[2]);}
  private static int mapY(V p,double[] t){return (int)Math.round(t[4]+(p.z()-t[1])*t[2]);}
  private static void mapLine(GuiGraphics g,V a,V b,double[] t,int color){int ax=mapX(a,t),ay=mapY(a,t),bx=mapX(b,t),by=mapY(b,t);int steps=Math.max(Math.abs(ax-bx),Math.abs(ay-by));for(int i=0;i<=steps;i++){double q=steps==0?0:(double)i/steps;int x=(int)Math.round(ax+(bx-ax)*q),y=(int)Math.round(ay+(by-ay)*q);g.fill(x,y,x+1,y+1,color);}}
  private void renderMap(GuiGraphics g,int x,int y,int w){
    g.drawString(font,"俯视图 · 北 ↑"+(options.routing().through()?" · 经过点":""),x,y,0xffffff,false);g.fill(x,y+17,x+w,top+144,0xff09171e);
    var t=mapTransform(x,y,w);var mesh=ClientRoads.preview;var cs=options.routing().controls();
    if(mesh!=null){V previous=mesh.first().center();for(var c:cs){mapLine(g,previous,c.position(),t,0xff626c7c);previous=c.position();}mapLine(g,previous,mesh.last().center(),t,0xff626c7c);
      previous=mesh.first().center();for(var p:mesh.samples()){mapLine(g,previous,p.center(),t,0xff5fe3d5);previous=p.center();}
      for(int i=0;i<2;i++){var p=i==0?mesh.first().center():mesh.last().center();int px=mapX(p,t),py=mapY(p,t);g.fill(px-2,py-2,px+3,py+3,0xffeeeeee);g.drawString(font,i==0?"A":"B",px+4,py-3,0xffffff,false);}}
    for(int i=0;i<cs.size();i++){var p=cs.get(i).position();int px=mapX(p,t),py=mapY(p,t);g.fill(px-3,py-3,px+4,py+4,i==controlIndex?0xffffd37c:0xffb2a4f7);g.drawString(font,""+(i+1),px+4,py+2,0xffe7cf92,false);}
    if(cs.isEmpty())g.drawWordWrap(font,Component.literal("A/B 为端点；选择起点后，左 Alt＋右键端点添加控制点。"),x,top+157,w,0xffbbd3dc);
    else {label(g,"X","controlX");label(g,"Y","controlY");label(g,"Z","controlZ");}
  }
  @Override public boolean mouseClicked(double mx,double my,int button){
    if(mapPage&&button==0){int x=width>=512?left+294:left+12;var t=mapTransform(x,top+27,width>=512?194:264);var cs=options.routing().controls();
      for(int i=0;i<cs.size();i++)if(Math.hypot(mx-mapX(cs.get(i).position(),t),my-mapY(cs.get(i).position(),t))<=7){if(capture()){controlIndex=i;rebuildWidgets();}return true;}}
    return super.mouseClicked(mx,my,button);
  }
  private void plan(GuiGraphics g, int x, int y, int w, int h) {
    var mesh = ClientRoads.preview;
    g.fill(x, y, x + w, y + h, 0xD9112029);
    g.drawString(font, "俯视路径", x + 8, y + 8, 0xFFFFFF, false);
    double dx = Math.max(1, mesh.max().x() - mesh.min().x()),
        dz = Math.max(1, mesh.max().z() - mesh.min().z()),
        scale = Math.min((w - 24) / dx, (h - 44) / dz);
    for (var p : mesh.samples()) {
      int px = x + 12 + (int) ((p.center().x() - mesh.min().x()) * scale),
          py = y + 30 + (int) ((p.center().z() - mesh.min().z()) * scale);
      g.fill(px, py, px + 2, py + 2, 0xFF5FE3D5);
    }
    g.drawString(font, "完整路面在世界中同步预览", x + 8, y + h - 12, 0xBBD3DC, false);
  }
}
