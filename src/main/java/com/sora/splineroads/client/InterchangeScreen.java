package com.sora.splineroads.client;

import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.net.RoadNetwork;
import com.sora.splineroads.world.*;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;

public final class InterchangeScreen extends Screen {
  private static final Map<Integer, CompoundTag> drafts = new HashMap<>();
  private static CompoundTag draft;
  private static final LatestPlanningTask<InterchangePlanner.Plan> WORKER =
      new LatestPlanningTask<>("SplineRoads interchange preview");
  private static long generation;
  private final CompoundTag payload;
  private final Map<String, EditBox> fields = new LinkedHashMap<>();
  private InterchangePlanner.Options options;
  private InterchangePlanner.Plan plan;
  private Button save, preview, heights;
  private int left, top, panel, panelHeight, control, delay, page, axis = 1, endpointTextBottom;
  private int stylePage;
  private boolean doubleDetails;
  private EditBox tollName;
  private String deleteConfirmation = "";
  private boolean pending, initializing, computing, needsPlan;
  private String status = "正在计算整座立交…";
  private void edited(){ClientRoads.editorChanged(this);pending=false;}

  public static void clear() {
    drafts.clear();
    draft = null;
    generation++;
    WORKER.cancel();
    ClientRoads.nodePreviews = List.of();
  }

  public static void open(CompoundTag fresh) {
    var mc = Minecraft.getInstance();
    int arms =
        fresh.contains("GeneratorArms")
            ? fresh.getInt("GeneratorArms")
            : fresh.getLongArray("Points").length;
    draft = drafts.get(arms);
    String kind = fresh.getString("Kind");
    if (kind.equals("interchangeReset")) {
      drafts.remove(arms);
      draft = null;
      generation++;
    WORKER.cancel();
      ClientRoads.nodePreviews = List.of();
      ClientRoads.preview = null;
      return;
    }
    if (kind.equals("interchangeResume")) {
      if (draft != null) mc.setScreen(new InterchangeScreen(draft));
      else if (mc.player != null)
        mc.player.displayClientMessage(
            Component.literal(arms == 3 ? "先选 A/B 主路两端，再选 C 支路端点" : arms == 4 ? "依次选择 A/B 与 C/D 两条主路端点" : arms == 5 ? "依次选择 A/B、C/D 两条主路与 E 支路端点" : "依次选择 A/B、C/D、E/F 三条主路端点"), true);
      return;
    }
    CompoundTag merged = fresh.copy();
    if (draft != null
        && Arrays.equals(draft.getLongArray("Points"), fresh.getLongArray("Points"))
        && draft.hasUUID("Id") == fresh.hasUUID("Id")
        && (!draft.hasUUID("Id") || draft.getUUID("Id").equals(fresh.getUUID("Id"))))
      for (String key : List.of("Options", "Main1", "Main2", "Main3"))
        merged.put(key, draft.getCompound(key).copy());
    draft = merged.copy();
    drafts.put(arms, draft);
    mc.setScreen(new InterchangeScreen(merged));
  }

  public InterchangeScreen(CompoundTag payload) {
    super(Component.literal("Spline Roads · 自动立交"));
    this.payload = payload.copy();
    for (String key : payload.getLongArray("Points").length>=5?List.of("Main1", "Main2", "Main3"):List.of("Main1", "Main2"))
      this.payload.put(
          key,
          RoadRecord.writeSettings(
              RoadProfile.editable(RoadRecord.readSettings(this.payload.getCompound(key)))));
    options = Interchanges.read(payload.getCompound("Options"));
    normalizeJunctionAxis();
    if (payload.getBoolean("Partial")) status = "立交已有路段被单独删除；整体更新将重新生成完整布局";
    if (!com.sora.splineroads.world.HostAxes.presets(payload)
        .contains(options.preset())) {
      options =
          changed(
              com.sora.splineroads.world.HostAxes.presets(payload).get(0),
              options.leftTraffic(),
              options.lanes(),
              options.rampWidth(),
              options.adjustEndpoints());
      status = HostAxes.retiredInterchange(payload)?HostAxes.RETIRED_MESSAGE:"旧布局已停用；更新时将使用当前预设，也可仅删除匝道保留主路";
    }
  }

  private Button button(String label, int x, int y, int w, Runnable click) {
    return addRenderableWidget(
        Button.builder(
                Component.literal(label),
                b -> {
                  if (capture()) {
                    try {
                      var before=payload.copy();var beforeOptions=options;
                      click.run();
                      if(!before.equals(payload)||!beforeOptions.equals(options))edited();
                      WORKER.cancel(); generation++; computing = false;
                      plan = null;
                      stash();
                      rebuildWidgets();
                    } catch (IllegalArgumentException e) {
                      status = e.getMessage();
                    }
                  }
                })
            .bounds(x, y, w, 20)
            .build());
  }

  private void tab(String text, int x, int y, int w, int target) {
    addRenderableWidget(
        Button.builder(
                Component.literal((page == target ? "• " : "") + text),
                b -> {
                  if (!capture()) return;
                  persist();
                  page = target;
                  rebuildWidgets();
                })
            .bounds(x, y, w, 20)
            .build());
  }

  private InterchangePlanner.Options changed(
      InterchangePlanner.Preset preset,
      boolean leftTraffic,
      int lanes,
      double width,
      boolean adjust) {
    return new InterchangePlanner.Options(
        preset,
        leftTraffic,
        lanes,
        options.transition(),
        options.radius(),
        options.clearance(),
        options.upper(),
        width,
        adjust,
        options.allowShrink(),
        options.maxLowering(), options.junctionAxis(),options.loopMask(),options.allowAbove(),options.flatConnector(),options.connectorHeight(),options.toll());
  }

  private boolean highway(int axis){return RoadProfile.catalog(RoadRecord.readSettings(payload.getCompound("Main"+(axis+1)))).type()==RoadProfile.Type.HIGHWAY;}
  private void normalizeJunctionAxis(){
    if((options.preset()==InterchangePlanner.Preset.DIAMOND||options.preset()==InterchangePlanner.Preset.SPUI)&&highway(options.junctionAxis())&&!highway(1-options.junctionAxis()))options=options.junction(1-options.junctionAxis());
  }

  Settings mainSettings() {
    return RoadRecord.readSettings(payload.getCompound("Main" + axis));
  }

  void mainSettings(Settings value) {
    value.validate();
    payload.put("Main" + axis, RoadRecord.writeSettings(value));
  }

  void changeExtras(RoadProfile.Options options) {
    var old = mainSettings();
    double lane = RoadProfile.layout(old, old.width()).laneWidth();
    mainSettings(
        new Settings(
                Mode.STRAIGHT,
                old.style(),
                RoadProfile.width(old.style(), options, lane),
                old.thickness(),
                .4,
                90)
            .options(options).structure(old.structure()));
  }

  void changeStyle(Style next) {
    var old = mainSettings();
    double lane = RoadProfile.layout(old, old.width()).laneWidth();
    mainSettings(
        new Settings(
                Mode.STRAIGHT,
                next,
                RoadProfile.width(next, old.options(), lane),
                old.thickness(),
                .4,
                90)
            .options(old.options()).structure(old.structure()));
  }

  @Override
  protected void init() {
    initializing = true;
    boolean parallel=com.sora.splineroads.world.ParallelSpui.present(payload);
    if(parallel)page=0;
    fields.clear();tollName=null;
    panel = Math.min(700, width - 16);
    if(page==3)page=0;
    left = (width - panel) / 2;
    panelHeight = Math.min(316, height - 8);
    top = Math.max(4, (height - panelHeight) / 2);
    control = Math.min(306, (int)((panel - 36) * .54));
    int x = left + 12, y = top + 26, w = control, half = (w - 8) / 2, third = (w - 8) / 3;
    if(HostAxes.retiredInterchange(payload)){
      status=HostAxes.RETIRED_MESSAGE;needsPlan=false;initializing=false;page=0;
      save=addRenderableWidget(Button.builder(Component.literal("曲线立交建造已撤下"),b->{}).bounds(x,y,w,20).build());save.active=false;
      preview=save;
      addRenderableWidget(Button.builder(Component.literal("关闭"),b->onClose()).bounds(x,y+28,w,20).build());
      if(payload.hasUUID("Id"))for(int i=0;i<2;i++){
        String action=i==0?"interchangeDeleteRamps":"interchangeDelete";String label=i==0?"仅删匝道，保留主路":"删除整座";
        addRenderableWidget(Button.builder(Component.literal(label),b->{if(!action.equals(deleteConfirmation)){deleteConfirmation=action;b.setMessage(Component.literal("确认："+label));}else submit(action);}).bounds(x,y+56+i*28,w,20).build());
      }
      return;
    }
    int tabs=3,tw=(w-4*(tabs-1))/tabs;
    tab("布局 / 匝道", x, y, tw, 0);
    if(!parallel){tab("主路样式", x + tw + 4, y, tw, 1);
    tab("端点调整", x + 2 * (tw + 4), y, tw, 2);}
    y += 26;
    if (page == 0) {
      button(
          options.preset().label + (payload.getLongArray("Points").length>=5?"":" ▸"),
          x,
          y,
          w,
          () -> {
            var choices =
                new ArrayList<>(com.sora.splineroads.world.HostAxes.presets(payload));
            options =
                changed(
                    choices.get((choices.indexOf(options.preset()) + 1) % choices.size()),
                    options.leftTraffic(),
                    options.lanes(),
                    options.rampWidth(),
                    options.adjustEndpoints());
          });
      button(
          options.leftTraffic() ? "靠左行驶" : "靠右行驶",
          x,
          y + 24,
          half,
          () ->
              options =
                  changed(
                      options.preset(),
                      !options.leftTraffic(),
                      options.lanes(),
                      options.rampWidth(),
                      options.adjustEndpoints()));
      var nodes = payload.getList("Nodes", Tag.TAG_COMPOUND);
      double ab =
          (RoadRecord.readNode(nodes.getCompound(0)).position().y()
                  + RoadRecord.readNode(nodes.getCompound(1)).position().y())
              / 2;
      double cd =
          (RoadRecord.readNode(nodes.getCompound(2)).position().y()
                  + RoadRecord.readNode(nodes.getCompound(nodes.size() == 3 ? 2 : 3))
                      .position()
                      .y())
              / 2;
      double minimum =
          InterchangePlanner.minimumDifference(
              RoadRecord.readSettings(payload.getCompound("Main1")),
              RoadRecord.readSettings(payload.getCompound("Main2")),
              com.sora.splineroads.world.HostAxes.curved(payload)?CurvedRoadPlans.planningOptions(options):options);
      heights =
          addRenderableWidget(
              Button.builder(
                      Component.literal(
                          String.format(
                              Locale.ROOT, payload.getLongArray("Points").length>=5?"分层升降 · 自动拟合":options.preset().three?"高差 %.0f · 最低 0":"原高差 %.0f / 最低 %.0f", Math.abs(ab - cd), minimum)),
                      b -> {})
                  .bounds(x + half + 8, y + 24, half, 20)
                  .build());
      heights.active = false;
      if(parallel){heights.setMessage(Component.literal("路口：沿用下层已有路口"));}
      else if(options.preset()==InterchangePlanner.Preset.DIAMOND||options.preset()==InterchangePlanner.Preset.SPUI){
        heights.visible=false;
        button("路口设在："+(options.junctionAxis()==0?"AB":"CD")+" 主路 ▸",
            x+half+8,y+24,half,()->{if(!highway(1-options.junctionAxis()))options=options.junction(1-options.junctionAxis());});
      }
      if(options.preset()==InterchangePlanner.Preset.HYBRID){
        heights.visible=false;
        StringBuilder names=new StringBuilder();String[] arms={"A","C","B","D"};
        for(int i=0;i<4;i++)if((options.loopMask()&(1<<i))!=0)names.append(arms[i]);
        button("绕圈左转："+names+" ▸",x+half+8,y+24,half,()->{
          var masks=List.of(5,10,3,6,12,9);options=options.loops(masks.get((masks.indexOf(options.loopMask())+1)%masks.size()));
        });
      }
      updateHeightLabel();
      button(
          options.lanes() + " 车道匝道 ▸",
          x,
          y + 58,
          half,
          () -> {
            int lanes = 3 - options.lanes();
            double oldDefault = options.lanes() == 1 ? 5 : 9;
            double value =
                Math.abs(options.width() - oldDefault) < 1e-7
                    ? 0
                    : Math.max(lanes * 4, options.width() * lanes / options.lanes());
            options =
                changed(
                    options.preset(),
                    options.leftTraffic(),
                    lanes,
                    value,
                    options.adjustEndpoints());
          });
      field(parallel?"匝道中段总宽 / 格":"匝道总宽 / 格", "rampWidth", parallel?options.lanes()*RoadProfile.layout(RoadRecord.readSettings(payload.getCompound("Main2")),RoadRecord.readSettings(payload.getCompound("Main2")).width()).laneWidth()+1.5:options.width(), x + half + 8, y + 48, half);
      if (panelHeight < 280) {
        field("过渡 / 格", "transition", options.transition(), x, y + 82, third);
        field("半径 / 格", "radius", options.radius(), x + third + 4, y + 82, third);
        if(parallel)button("净空按主路校验",x+2*(third+4),y+92,third,()->{}).active=false;
        else field("净高 / 格", "clearance", options.clearance(), x + 2 * (third + 4), y + 82, third);
        button(
            "调整端点：" + (options.adjustEndpoints() ? "允许" : "关闭"),
            x,
            y + 114,
            w,
            () -> options = options.adjust(!options.adjustEndpoints()));
      } else {
        field("过渡 / 格", "transition", options.transition(), x, y + 80, half);
        field("环绕半径 / 格", "radius", options.radius(), x + half + 8, y + 80, half);
        if(parallel)button("净空按主路校验",x,y+122,half,()->{}).active=false;
        else field("交叉净高 / 格", "clearance", options.clearance(), x, y + 112, half);
        button(
            "调整端点：" + (options.adjustEndpoints() ? "允许" : "关闭"),
            x + half + 8,
            y + 122,
            half,
            () -> options = options.adjust(!options.adjustEndpoints()));
      }
      if(options.aboveAvailable())button("允许匝道高于主路："+(options.allowAbove()?"开":"关"),x,y+146,w,()->options=options.above(!options.allowAbove()));
      if(options.preset()==InterchangePlanner.Preset.DOUBLE_TRUMPET){
        doubleView("双喇叭配置",x,y+146,half,true);
        doubleView("立交俯视图",x+half+8,y+146,w-half-8,false);
        if(doubleDetails)doubleSettings(left+control+24,top+38,panel-control-36);
      }
    } else if (page == 1) {
      if(payload.getLongArray("Points").length>=5){
        button((axis==1?"• ":"")+"AB 主路",x,y,third,()->axis=1);
        button((axis==2?"• ":"")+"CD 主路",x+third+4,y,third,()->axis=2);
        button((axis==3?"• ":"")+(payload.getLongArray("Points").length==5?"E 支路":"EF 主路"),x+2*(third+4),y,w-2*(third+4),()->axis=3);
      }else{
        button((axis == 1 ? "• " : "") + "AB 主路", x, y, half, () -> axis = 1);
        button((axis == 2 ? "• " : "") + "CD 主路", x + half + 8, y, half, () -> axis = 2);
      }
      var road = mainSettings();
      var catalog = RoadProfile.catalog(road);
      button(catalog.type()==RoadProfile.Type.HIGHWAY?"高速道路 ▸":"普通道路 ▸",x,y+24,half,()->{
        var type=catalog.type()==RoadProfile.Type.HIGHWAY?RoadProfile.Type.ORDINARY:RoadProfile.Type.HIGHWAY;
        mainSettings(RoadLanes.configure(road,type,RoadLanes.counts(road),RoadProfile.layout(road,road.width()).laneWidth()));
      });
      button(RoadLanes.counts(road).label()+"…",x+half+8,y+24,half,()->{
        final int editedAxis=axis;
        minecraft.setScreen(new RoadLaneConfigScreen(this,catalog.type(),RoadLanes.counts(road),(type,counts)->{
          axis=editedAxis;
          mainSettings(RoadLanes.configure(mainSettings(),type,counts,RoadProfile.layout(road,road.width()).laneWidth()));
          edited();
          plan=null;stash();
        },true));
      });
      var plants=road.options().streetscape().plantings();
      field("护栏间距 / 格","railGap",plants.railGap(),x,y+48,half);
      field("绿化带宽度 / 格","greenWidth",plants.greenWidth(),x+half+8,y+48,half);
      field("主路总宽 / 格", "mainWidth", road.width(), x, y + 80, half);
      field("路板厚度 / 格", "thickness", road.thickness(), x + half + 8, y + 80, half);
      button(
          "外侧护栏：" + road.options().outerRail().label + " ▸",
          x,
          y + 114,
          half,
          () ->
              mainSettings(
                  mainSettings()
                      .options(
                          mainSettings()
                              .options()
                              .outerRail(
                                  RoadProfile.OuterRail.values()[
                                      (road.options().outerRail().ordinal() + 1) % RoadProfile.OuterRail.values().length]))));
      button("龙门架：" + (road.options().infrastructure().gantry()==RoadInfrastructure.Gantry.OFF?"关闭":"开启"),
          x+half+8,y+114,half,()->{
            var current=mainSettings();var infra=current.options().infrastructure();
            mainSettings(current.options(current.options().infrastructure(infra.gantry(infra.gantry()==RoadInfrastructure.Gantry.OFF?RoadInfrastructure.Gantry.FRAME:RoadInfrastructure.Gantry.OFF))));
          });
      if (catalog.type() == RoadProfile.Type.ORDINARY && panelHeight >= 280) {
        button(
            "非机动车道：" + (road.options().cycle() ? "开" : "关"),
            x,
            y + 138,
            half,
            () -> {
              var o = mainSettings().options();
              changeExtras(o.extras(!o.cycle(), o.cycleRail(), o.curb()));
            });
        button(
            "路缘石：" + (road.options().curb() ? "开" : "关"),
            x + half + 8,
            y + 138,
            half,
            () -> {
              var o = mainSettings().options();
              changeExtras(o.extras(o.cycle(), o.cycleRail(), !o.curb()));
            });
      }
    } else if (page == 2) {
      button(
          "调整端点：" + (options.adjustEndpoints() ? "允许" : "关闭"),
          x,
          y,
          half,
          () -> options = options.adjust(!options.adjustEndpoints()));
      button(
          "允许缩小：" + (options.allowShrink() ? "开" : "关"),
          x + half + 8,
          y,
          half,
          () -> options = options.shrink(!options.allowShrink()));
      var lowering = addRenderableWidget(new AbstractSliderButton(
          x, y + 26, w, 20, Component.empty(), options.maxLowering() / 64) {
        { updateMessage(); }
        @Override protected void updateMessage() {
          setMessage(Component.literal("高度最多降低：" + Math.round(value * 64) + " 格"));
        }
        @Override protected void applyValue() {
          edited();
          WORKER.cancel(); generation++; computing = false;
          options = options.lowering(Math.round(value * 64));
          plan = null;
          delay = 8;
          needsPlan = true;
          save.active = false;
          preview.active = false;
          stash();
        }
      });
      lowering.active = options.allowShrink() && options.adjustEndpoints();
    }
    if(page==1){
      int sx=left+control+24,sw=panel-control-36;
      button("◀",sx+sw-52,top+26,24,()->stylePage=Math.floorMod(stylePage-1,8));
      button("▶",sx+sw-24,top+26,24,()->stylePage=(stylePage+1)%8);
      InterchangeStyleOptions.build(this,stylePage,sx,top+54,sw,panelHeight-110);
    }
    int footer = top + panelHeight - 26, n = payload.hasUUID("Id") ? 5 : 3;
    int bw = (panel - 24 - 8 * (n - 1)) / n;
    save =
        addRenderableWidget(
            Button.builder(
                    Component.literal(payload.hasUUID("Id") ? "整体更新" : "整体建造"),
                    b -> submit("interchange"))
                .bounds(x, footer, bw, 20)
                .build());
    preview =
        addRenderableWidget(
            Button.builder(
                    Component.literal("实景预览"),
                    b -> {
                      persist();
                      ManualPlannerClient.worldPreview();
                      minecraft.setScreen(null);
                    })
                .bounds(x + bw + 8, footer, bw, 20)
                .build());
    addRenderableWidget(
        Button.builder(Component.literal("关闭"), b -> onClose())
            .bounds(x + (bw + 8) * 2, footer, bw, 20)
            .build());
    if (n == 5) {
      for (int index = 0; index < 2; index++) {
        final boolean rampsOnly = index == 0;
        addRenderableWidget(
            Button.builder(
                    Component.literal(rampsOnly ? "仅删匝道" : "删除整座"),
                    b -> {
                      String action = rampsOnly ? "interchangeDeleteRamps" : "interchangeDelete";
                      if (!action.equals(deleteConfirmation)) {
                        deleteConfirmation = action;
                        b.setMessage(Component.literal(rampsOnly ? "确认删匝道" : "确认删整座"));
                        status = parallel?"删除四条连接匝道；恢复原有车道，保留下层路口":rampsOnly ? "保留贯通主路；三/五向末端支路一并删除，仅保留端点" : "删除全部路段和设施，保留端点";
                      } else submit(action);
                    })
                .bounds(x + (bw + 8) * (3 + index), footer, bw, 20)
                .build());
      }
    }
    save.active = plan != null && !pending && !needsPlan && !computing;
    preview.active = plan != null && !needsPlan && !computing;
    if(parallel){
      fields.get("rampWidth").active=false;
      for(var widget:children())if(widget instanceof Button b){String label=b.getMessage().getString();
        if(label.equals("靠左行驶")||label.equals("靠右行驶"))b.active=false;
      }
    }
    initializing = false;
    delay = 1;
    needsPlan |= plan == null;
  }

  private void doubleView(String title,int x,int y,int w,boolean details){
    addRenderableWidget(Button.builder(Component.literal((doubleDetails==details?"• ":"")+title),b->{
      if(!capture())return;stash();doubleDetails=details;rebuildWidgets();
    }).bounds(x,y,w,20).build());
  }
  private int connectorLanes(){
    var a=RoadLanes.counts(RoadRecord.readSettings(payload.getCompound("Main1")));
    var b=RoadLanes.counts(RoadRecord.readSettings(payload.getCompound("Main2")));
    return Math.min(Math.min(a.forward(),a.reverse()),Math.min(b.forward(),b.reverse()));
  }
  private void doubleSettings(int x,int y,int w){
    int half=(w-8)/2;var c=options.toll();int n=connectorLanes();
    button("中央水平段："+(options.flatConnector()?"开":"关"),x,y,half,()->{
      var nodes=payload.getList("Nodes",Tag.TAG_COMPOUND);double a=RoadRecord.readNode(nodes.getCompound(0)).position().y(),b=RoadRecord.readNode(nodes.getCompound(2)).position().y();
      options=options.connector(!options.flatConnector(),Math.max(Math.ceil(Math.min(a,b)),Math.min(Math.floor(Math.max(a,b)),Math.rint((a+b)/2))));
    });
    field("水平高度 / 格","connectorHeight",options.connectorHeight(),x+half+8,y-10,w-half-8);fields.get("connectorHeight").active=options.flatConnector();
    button("生成收费站："+(c.enabled()?"开":"关"),x,y+27,w,()->options=options.toll(options.toll().enabled(!options.toll().enabled())));
    button(c.twoWay()?"双向收费":"单向收费",x,y+51,half,()->options=options.toll(options.toll().mode(!options.toll().twoWay(),options.toll().reverse()))).active=c.enabled();
    button(c.twoWay()?"两个方向":c.reverse()?"CD → AB":"AB → CD",x+half+8,y+51,w-half-8,()->options=options.toll(options.toll().mode(false,!options.toll().reverse()))).active=c.enabled()&&!c.twoWay();
    button("位置：靠近 "+c.near()+" 主路",x,y+75,w,()->options=options.toll(options.toll().near(options.toll().near()==DoubleTrumpetToll.Near.AB?DoubleTrumpetToll.Near.CD:DoubleTrumpetToll.Near.AB))).active=c.enabled();
    tollSlider(x,y+99,w,n,false,c.enabled()&&(c.twoWay()||!c.reverse()));
    tollSlider(x,y+123,w,n,true,c.enabled()&&(c.twoWay()||c.reverse()));
    tollName=new EditBox(font,x,y+159,w,18,Component.literal("收费站名称（选填）"));tollName.setMaxLength(80);tollName.setValue(c.name());tollName.active=c.enabled();
    tollName.setResponder(v->{if(!initializing){edited();WORKER.cancel();generation++;computing=false;delay=8;needsPlan=true;save.active=false;preview.active=false;}});
    addRenderableWidget(tollName);
  }

  private void tollSlider(int x,int y,int w,int lanes,boolean reverse,boolean enabled){
    int count=Math.min(lanes,reverse?options.toll().reverseEtc():options.toll().forwardEtc());
    var slider=addRenderableWidget(new AbstractSliderButton(x,y,w,20,Component.empty(),count/(double)lanes){
      { updateMessage(); }
      @Override protected void updateMessage(){
        int etc=(int)Math.round(value*lanes);
        setMessage(Component.literal((reverse?"CD→AB":"AB→CD")+"  ETC "+etc+" / 人工 "+(lanes-etc)));
      }
      @Override protected void applyValue(){
        int etc=(int)Math.round(value*lanes);var c=options.toll();
        if(etc==(reverse?c.reverseEtc():c.forwardEtc()))return;
        edited();WORKER.cancel();generation++;computing=false;
        options=options.toll(reverse?c.etc(c.forwardEtc(),etc):c.etc(etc,c.reverseEtc()));
        plan=null;delay=8;needsPlan=true;save.active=false;preview.active=false;stash();
      }
    });
    slider.active=enabled;
  }

  private void field(String label, String key, double value, int x, int y, int w) {
    var f = new EditBox(font, x, y + 10, w, 18, Component.literal(label));
    f.setMaxLength(32);
    f.setValue(Double.toString(value));
    f.setResponder(
        v -> {
          if (!initializing) {
            edited();
            WORKER.cancel();
            generation++;
            computing = false;
            delay = 8;
            needsPlan = true;
            save.active = false;
            preview.active = false;
          }
        });
    fields.put(key, f);
    addRenderableWidget(f);
  }

  Button styleButton(String text,int x,int y,int w,Runnable action){return button(text,x,y,w,action);}
  void styleField(String label,String key,double value,int x,int y,int w){field(label,key,value,x,y,w);}
  boolean leftTraffic(){return options.leftTraffic();}
  void toggleTraffic(){options=changed(options.preset(),!options.leftTraffic(),options.lanes(),options.rampWidth(),options.adjustEndpoints());
    for(int a=1;a<=3;a++){String key="Main"+a;if(payload.contains(key)){var s=RoadRecord.readSettings(payload.getCompound(key));payload.put(key,RoadRecord.writeSettings(s.options(s.options().traffic(options.leftTraffic()))));}}}

  private double number(String key, double fallback) {
    return fields.containsKey(key) ? Double.parseDouble(fields.get(key).getValue()) : fallback;
  }

  private boolean capture() {
    try {
      options =
          new InterchangePlanner.Options(
              options.preset(),
              options.leftTraffic(),
              options.lanes(),
              number("transition", options.transition()),
              number("radius", options.radius()),
              number("clearance", options.clearance()),
              options.upper(),
              number("rampWidth", options.rampWidth()),
              options.adjustEndpoints(),
              options.allowShrink(),
              options.maxLowering(), options.junctionAxis(),options.loopMask(),options.allowAbove(),options.flatConnector(),number("connectorHeight",options.connectorHeight()),tollName==null?options.toll():options.toll().named(tollName.getValue()));
      if (page==1) {
        var old=mainSettings();var next=InterchangeStyleOptions.capture(old.options(),this::number);
        double lane=RoadProfile.layout(old,old.width()).laneWidth();
        double width=number("mainWidth",old.width());
        if(!next.streetscape().plantings().equals(old.options().streetscape().plantings())&&Math.abs(width-old.width())<1e-7){
          width=RoadProfile.width(old.style(),next,lane);
          initializing=true;fields.get("mainWidth").setValue(Double.toString(width));initializing=false;
        }
        mainSettings(new Settings(Mode.STRAIGHT,old.style(),width,number("thickness",old.thickness()),.4,90).options(next).structure(old.structure()));
      }
      normalizeJunctionAxis();
      return true;
    } catch (IllegalArgumentException e) {
      status = e instanceof NumberFormatException ? "请输入有效数字" : e.getMessage();
      return false;
    }
  }

  private void stash() {
    payload.put("Options", Interchanges.write(options));
    draft = payload.copy();
    drafts.put(payload.getLongArray("Points").length, draft);
  }

  private void persist() {
    if (capture()) stash();
  }

  @Override
  public void tick() {
    super.tick();
    if (delay > 0) delay--;
    if (delay == 0 && needsPlan && !computing) calculate();
  }

  private void calculate() {
    if(HostAxes.retiredInterchange(payload)){needsPlan=false;status=HostAxes.RETIRED_MESSAGE;return;}
    if (!capture()) {
      needsPlan = false;
      return;
    }
    persist();
    plan = null;
    save.active = false;
    preview.active = false;
    status = "正在检查全部转向、15% 纵坡与交叉净高…";
    computing = true;
    needsPlan = false;
    long token = ++generation;
    CompoundTag inputs = payload.copy();
    WORKER.submit(() -> Interchanges.plan(inputs),
            (result, error) ->
                Minecraft.getInstance()
                    .execute(
                        () -> {
                          if (token != generation || draft == null) return;
                          computing = false;
                          if (needsPlan) {
                            delay = 1;
                            return;
                          }
                          if (error != null) {
                            Throwable cause = error.getCause() == null ? error : error.getCause();
                            status = cause.getMessage() == null ? "预设无法放入当前范围" : cause.getMessage();
                            ClientRoads.preview = null;
                            ClientRoads.nodePreviews = List.of();
                            return;
                          }
                          plan = result;
                          updateHeightLabel();
                          ClientRoads.nodePreviews =
                              result.legs().stream()
                                  .map(l -> RoadRenderMesh.simplify(l.mesh()))
                                  .toList();
                          ClientRoads.preview = ClientRoads.nodePreviews.get(0);
                          status =
                              result.movements()
                                  + " 条转向 · 最小半径 "
                                  + Math.round(result.minRadius())
                                  + " 格 · 最大坡度 "
                                  + String.format(
                                      java.util.Locale.ROOT,
                                      "%.1f%%",
                                      result.legs().stream()
                                              .mapToDouble(l -> RoadGrades.maximum(l.mesh()))
                                              .max()
                                              .orElse(0)
                                          * 100)
                                  + " · 最高 Y="
                                  + Math.round(result.highest())
                                  + (switch (options.preset()) {
                                    case DIAMOND, PARCLO, SPUI -> com.sora.splineroads.world.ParallelSpui.present(payload)?" · 沿用下层已有路口":" · "+(options.junctionAxis()==0?"AB":"CD")+" 主路设路口";
                                    case ROUNDABOUT -> " · 双主路直通 / 独立中层环道";
                                    case STACK -> " · 四层堆栈";
                                    default -> " · 分层匝道";
                                  });
                          if (payload.getBoolean("Partial")) status += " · 更新将补回已删除路段";
                          if (adjusted()) status += " · 端点将调整，见端点页";
                          save.active = !pending;
                          preview.active = true;
                        }));
  }

  @Override public void removed() {
    WORKER.cancel(); generation++;
    if (computing) needsPlan = true;
    computing = false;
    super.removed();
  }

  private void updateHeightLabel(){
    if(heights!=null&&com.sora.splineroads.world.ParallelSpui.present(payload)){heights.setMessage(Component.literal("路口：沿用下层已有路口"));return;}
    if(heights==null||plan==null||plan.anchors().size()<3||plan.anchors().size()>4)return;
    var old=payload.getList("Nodes",Tag.TAG_COMPOUND);var fitted=plan.anchors();
    double before=Math.abs(RoadRecord.readNode(old.getCompound(0)).position().y()-RoadRecord.readNode(old.getCompound(2)).position().y());
    double after=Math.abs(fitted.get(0).position().y()-fitted.get(2).position().y());
    if(options.preset().three){
      double upper=Math.max(fitted.get(0).position().y(),fitted.get(2).position().y());
      heights.setMessage(Component.literal(String.format(Locale.ROOT,"高差 %.0f · %s",after,plan.highest()>upper+1e-6?"匝道上跨":"层间布置")));
    }else heights.setMessage(Component.literal(String.format(Locale.ROOT,"高差 %.0f → %.0f（已拟合）",before,after)));
  }

  private void submit(String action) {
    boolean deletion = !action.equals("interchange");
    if (pending || !deletion && (plan == null || needsPlan || computing)) return;
    persist();
    CompoundTag t = new CompoundTag();
    for (String key : List.of("Id", "Points", "Options", "Main1", "Main2", "Main3"))
      if (payload.contains(key)) t.put(key, payload.get(key).copy());
    t.putString("Action", action);
    pending = true;
    save.active = false;
    t.putInt("SourceAxesHash",com.sora.splineroads.world.HostAxes.signature(payload));
    t.put("SourceNodes", payload.getList("Nodes", Tag.TAG_COMPOUND).copy());
    if (plan != null) {
      ListTag targets = new ListTag();
      plan.anchors().forEach(node -> targets.add(RoadRecord.writeNode(node)));
      t.put("FittedNodes", targets);
    }
    status =
        deletion
            ? "正在删除并恢复地形…"
            : minecraft.hasSingleplayerServer() ? "正在检查地形并建造立交…" : "正在请求服务器检查并建造立交…";
    ClientRoads.sendAction(t);
  }

  /** A child settings screen can remain open while this submission completes. */
  void completed(){pending=false;}

  public void failed(String reason) {
    pending = false;
    status = reason;
    save.active = plan != null&&!HostAxes.retiredInterchange(payload);
  }

  @Override
  public boolean isPauseScreen() {
    return false;
  }

  @Override
  public void onClose() {
    generation++;
    WORKER.cancel();
    persist();
    ClientRoads.preview = null;
    ClientRoads.nodePreviews = List.of();
    super.onClose();
  }

  private boolean adjusted() {
    if (plan == null) return false;
    var old = payload.getList("Nodes", Tag.TAG_COMPOUND);
    for (int i = 0; i < old.size(); i++)
      if (RoadRecord.readNode(old.getCompound(i))
              .position()
              .distance(plan.anchors().get(i).position())
          > 1e-6) return true;
    return false;
  }

  @Override
  public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
    renderBackground(g);
    g.fill(left, top, left + panel, top + panelHeight, 0xEF182530);
    g.drawString(font, title, left + 12, top + 10, 0xFFFFFF, false);
    if(page==0&&doubleDetails&&options.preset()==InterchangePlanner.Preset.DOUBLE_TRUMPET)g.fill(left+control+20,top+26,left+panel-8,top+panelHeight-64,0xFF09161F);
    super.render(g, mouseX, mouseY, partial);
    for (var f : fields.values())
      g.drawString(font, f.getMessage(), f.getX(), f.getY() - 9, 0xC8DAE5, false);
    int statusY = top + panelHeight - (panelHeight < 260 ? 42 : 54);
    List<Component> endpointHint=null;endpointTextBottom=0;
    if (page == 2) {
      var old = payload.getList("Nodes", Tag.TAG_COMPOUND);
      g.drawString(
          font,
          options.adjustEndpoints()
              ? (options.allowShrink() ? "自动采用搜索到的最小可行范围" : "原布点不可行时向外调整")
              : "自动调整已关闭",
          left + 12,
          top + 105,
          0xFFDC91,
          false);
      int y = top + 117;
      int room=statusY-6-y;
      boolean stacked=room>=old.size()*22+13;
      int row=stacked?22:Math.max(10,Math.min(12,room/Math.max(1,old.size())));
      for (int i = 0; i < old.size(); i++) {
        V before = RoadRecord.readNode(old.getCompound(i)).position();
        V after = plan == null ? before : plan.anchors().get(i).position();
        String original=String.format(Locale.ROOT,"%c 原：%.1f, %.1f, %.1f",'A'+i,before.x(),before.y(),before.z());
        String updated=String.format(Locale.ROOT,"%c → %.1f, %.1f, %.1f",'A'+i,after.x(),after.y(),after.z());
        if(stacked)endpointLine(g,original,y,0xA9BAC8);
        endpointLine(g,updated,y+(stacked?11:0),before.distance(after)>1e-6?0xFFD27C:0xBFE7D1);
        if((!stacked||font.width(original)>control||font.width(updated)>control)&&mouseX>=left+12&&mouseX<left+12+control&&mouseY>=y&&mouseY<y+row)endpointHint=List.of(Component.literal(original),Component.literal(updated));
        y += row;
      }
      if(y+13<=statusY-6)endpointLine(g,"整体建造 / 更新时才移动端点",y+4,0xE0E8EF);
    }
    if(page==1)g.drawString(font,InterchangeStyleOptions.TITLES[stylePage]+" · "+(stylePage+1)+"/8",left+control+24,top+14,0xffffff,false);
    else if(page==0&&doubleDetails&&options.preset()==InterchangePlanner.Preset.DOUBLE_TRUMPET){
      if(tollName!=null)g.drawString(font,tollName.getMessage(),tollName.getX(),tollName.getY()-10,0xC8DAE5,false);
      if(panelHeight>=280)g.drawString(font,"通道总数固定，每方向 "+connectorLanes()+" 条",left+control+24,top+220,0xAFC8D3,false);
    }else map(g, left + control + 24, top + 28, panel - control - 36, panelHeight - 92);
    var lines = font.split(Component.literal(status == null ? "" : status), panel - 24);
    for (int i = 0; i < Math.min(panelHeight < 260 ? 1 : 2, lines.size()); i++)
      g.drawString(
          font,
          lines.get(i),
          left + 12,
          statusY + i * 10,
          plan == null ? 0xFFB6A3 : 0xC8E9DA,
          false);
    if (lines.size() > (panelHeight < 260 ? 1 : 2) && mouseY >= statusY && mouseY < statusY + 22)
      g.renderTooltip(font, Component.literal(status), mouseX, mouseY);
    if(endpointHint!=null)g.renderComponentTooltip(font,endpointHint,mouseX,mouseY);
  }
  private void endpointLine(GuiGraphics g,String text,int y,int color){
    String visible=font.width(text)<=control?text:font.plainSubstrByWidth(text,control-8)+"…";
    g.drawString(font,visible,left+12,y,color,false);endpointTextBottom=Math.max(endpointTextBottom,y+font.lineHeight);
  }

  private void map(GuiGraphics g, int x, int y, int w, int h) {
    g.fill(x, y, x + w, y + h, 0xFF09161F);
    if (plan == null) {
      g.drawString(font, "方案生成后显示整体俯视图", x + 8, y + 8, 0xAFC8D3, false);
      return;
    }
    var meshes = plan.legs().stream().map(InterchangePlanner.Leg::mesh).toList();
    double minX = meshes.stream().mapToDouble(m -> m.min().x()).min().orElse(0),
        minZ = meshes.stream().mapToDouble(m -> m.min().z()).min().orElse(0),
        maxX = meshes.stream().mapToDouble(m -> m.max().x()).max().orElse(1),
        maxZ = meshes.stream().mapToDouble(m -> m.max().z()).max().orElse(1),
        scale = Math.min((w - 20) / (maxX - minX), (h - 20) / (maxZ - minZ));
    int originX=x+(int)((w-(maxX-minX)*scale)/2),originY=y+(int)((h-(maxZ-minZ)*scale)/2);
    List<Sample> samples = new ArrayList<>();
    for (Mesh m : meshes)
      for (int i = 0; i < m.samples().size(); i += 3) samples.add(m.samples().get(i));
    samples.sort(Comparator.comparingDouble(p -> p.center().y()));
    for (var p : samples) {
      int px = originX + (int) ((p.center().x() - minX) * scale),
          py = originY + (int) ((p.center().z() - minZ) * scale),
          r = Math.max(1, (int) (p.halfWidth() * scale));
      int shade = (int) Math.max(0, Math.min(150, (p.center().y() - plan.center().y()) * 5));
      g.fill(px - r, py - r, px + r + 1, py + r + 1, 0xFF479FC2 + (shade << 16));
    }
    for(var leg:plan.legs())if(leg.toll()!=null){
      var edge=leg.mesh().samples();
      for(int i=1;i<edge.size();i++)for(int side:new int[]{-1,1})
        mapLine(g,edge.get(i-1).at(side*edge.get(i-1).halfWidth(),0),edge.get(i).at(side*edge.get(i).halfWidth(),0),originX,originY,minX,minZ,scale);
      for(var p:List.of(edge.get(0),edge.get(edge.size()-1)))mapLine(g,p.at(-p.halfWidth(),0),p.at(p.halfWidth(),0),originX,originY,minX,minZ,scale);
    }
    var nodes = payload.getList("Nodes", Tag.TAG_COMPOUND);
    for (int i = 0; i < nodes.size(); i++) {
      V p = plan.anchors().get(i).position();
      int px = originX + (int) ((p.x() - minX) * scale),
          py = originY + (int) ((p.z() - minZ) * scale);
      g.drawString(
          font,
          String.valueOf((char) ('A' + i)),
          Math.min(x + w - 9, Math.max(x + 2, px)),
          Math.min(y + h - 10, Math.max(y + 2, py)),
          0xFFFFFF,
          false);
    }
  }
  private static void mapLine(GuiGraphics g,V a,V b,int x,int y,double minX,double minZ,double scale){
    double ax=x+(a.x()-minX)*scale,ay=y+(a.z()-minZ)*scale,bx=x+(b.x()-minX)*scale,by=y+(b.z()-minZ)*scale;
    int steps=Math.max(1,(int)Math.ceil(Math.max(Math.abs(bx-ax),Math.abs(by-ay))));
    for(int i=0;i<=steps;i++){double t=i/(double)steps;int px=(int)Math.round(ax+(bx-ax)*t),py=(int)Math.round(ay+(by-ay)*t);g.fill(px,py,px+2,py+2,0xFFFFD34E);}
  }

}
