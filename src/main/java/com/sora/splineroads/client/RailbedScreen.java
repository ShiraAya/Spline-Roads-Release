package com.sora.splineroads.client;

import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.world.RailbedCodec;
import com.sora.splineroads.world.RoadRecord;
import com.sora.splineroads.net.RoadNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import java.util.*;

public final class RailbedScreen extends Screen {
  private static Mesh owned;
  private static final Set<UUID> cancelledTokens=new LinkedHashSet<>();
  private static void cancelled(UUID token){cancelledTokens.add(token);while(cancelledTokens.size()>32)cancelledTokens.remove(cancelledTokens.iterator().next());}
  private static RailbedGeometry.Plan cachedPlan;private static String cachedPlanKey;
  static List<RoadStructures.Part> structures=List.of();
  private static UUID previewToken;private static double previewWidth;private static Structure previewMode;private static String previewSelection="";private static RailbedOptions previewOptions;private static CompoundTag lastPreview;
  private Button buildButton,deleteButton;private int previewDelay;private boolean previewReady;private String structureProblem="";
  static List<RailbedPath.Path> highlighted=List.of(),candidates=List.of();
  private final CompoundTag payload;private final List<RailbedPath.Path> paths;private Structure mode;private RailbedOptions options;private int page;private double deckWidth;private int x,y;private String status="";private RailbedGeometry.Plan plan;private EditBox widthBox;
  public RailbedScreen(CompoundTag tag){super(Component.literal("SR 铁路路基"));payload=tag.copy();paths=tag.getList("Paths",Tag.TAG_COMPOUND).stream().map(t->RailbedCodec.read((CompoundTag)t)).toList();mode=Structure.valueOf(tag.getString("Mode"));deckWidth=tag.getDouble("Width");options=RailbedCodec.options(tag.getCompound("Options"));}
  public static void open(CompoundTag tag){if(tag.hasUUID("Token")&&cancelledTokens.contains(tag.getUUID("Token")))return;if(tag.getString("Kind").equals("railbedStructurePreview")){
      if(!tag.getUUID("Token").equals(previewToken)||tag.getDouble("Width")!=previewWidth||previewMode==null||!tag.getString("Mode").equals(previewMode.name())||!tag.getString("Selection").equals(previewSelection)||!RailbedCodec.options(tag.getCompound("Options")).equals(previewOptions))return;
      lastPreview=tag.copy();
      // A matching request can finish after the user has switched editors.
      // Ordinary overlays (chat/inventory) keep the world preview; other SR editors own theirs.
      var current=Minecraft.getInstance().screen;
      if(owned==null||ClientRoads.preview!=owned||ClientRoads.toolScreen(current))return;
      String problem=tag.getString("Problem");structures=List.of();if(tag.contains("Road")){var road=RoadRecord.load(tag.getCompound("Road"));structures=road.structures();owned=road.mesh();ClientRoads.preview=owned;}
      if(Minecraft.getInstance().screen instanceof RailbedScreen screen){screen.previewDelay=0;screen.previewReady=problem.isEmpty()&&tag.contains("Road");screen.structureProblem=problem;screen.updateBuild();}
      return;
    }else if(tag.getString("Kind").equals("railbedReset")){if(tag.hasUUID("Token")&&previewToken!=null&&!previewToken.equals(tag.getUUID("Token")))return;reset();if(Minecraft.getInstance().screen instanceof RailbedScreen)Minecraft.getInstance().setScreen(null);}else{lastPreview=tag.contains("CachedPreview")?tag.getCompound("CachedPreview").copy():null;var screen=new RailbedScreen(tag);highlighted=screen.paths;candidates=tag.getList("Candidates",Tag.TAG_COMPOUND).stream().map(t->RailbedCodec.read((CompoundTag)t)).toList();if(tag.getString("Kind").equals("railbedPreview")){screen.refresh();screen.previewDelay=0;screen.restorePreview();}else Minecraft.getInstance().setScreen(screen);}}
  private static void clear(){if(ClientRoads.preview==owned)ClientRoads.preview=null;owned=null;structures=List.of();}
  public static void reset(){clear();previewToken=null;previewOptions=null;lastPreview=null;cachedPlan=null;cachedPlanKey=null;highlighted=List.of();candidates=List.of();}
  @Override protected void init(){x=(this.width-340)/2;y=(height-220)/2;
    addRenderableWidget(Button.builder(Component.literal(new String[]{"基本 1/3","护栏/照明 2/3","接触网 3/3"}[page]),b->{page=(page+1)%3;rebuildWidgets();}).bounds(x+218,y+9,110,20).build());
    if(page==0){
    addRenderableWidget(Button.builder(Component.literal("路基样式："+label()),b->{mode=mode==Structure.AUTO?Structure.BRIDGE:mode==Structure.BRIDGE?Structure.TUNNEL:Structure.AUTO;b.setMessage(Component.literal("路基样式："+label()));refresh();}).bounds(x+12,y+35,316,20).build());
    addRenderableWidget(Button.builder(Component.literal("桥梁样式："+bridgeLabel()),b->{var choices=List.of(RoadInfrastructure.Bridge.STANDARD,RoadInfrastructure.Bridge.SUSPENSION,RoadInfrastructure.Bridge.CABLE,RoadInfrastructure.Bridge.ARCH);options=options.styles(choices.get((choices.indexOf(options.bridge())+1)%choices.size()),options.tunnel(),options.fence());rebuildWidgets();}).bounds(x+12,y+62,316,20).build());
    addRenderableWidget(Button.builder(Component.literal("隧道样式："+(options.tunnel()==RoadInfrastructure.Tunnel.BOX?"箱型":"圆形")),b->{options=options.styles(options.bridge(),options.tunnel()==RoadInfrastructure.Tunnel.BOX?RoadInfrastructure.Tunnel.ROUND:RoadInfrastructure.Tunnel.BOX,options.fence());rebuildWidgets();}).bounds(x+12,y+88,316,20).build());
    widthBox=new EditBox(font,x+132,y+116,80,20,Component.literal("路基总宽"));widthBox.setValue(deckWidth==0?"自动":String.valueOf(deckWidth));widthBox.setResponder(value->{try{deckWidth=value.trim().equals("自动")||value.trim().equalsIgnoreCase("auto")?0:Double.parseDouble(value.trim());refresh();}catch(NumberFormatException failure){plan=null;clear();previewReady=false;status="请输入总宽，或输入“自动”";updateBuild();}});addRenderableWidget(widthBox);
    addRenderableWidget(Button.builder(Component.literal("−"),b->change(-.5)).bounds(x+216,y+116,26,20).build());addRenderableWidget(Button.builder(Component.literal("+"),b->change(.5)).bounds(x+246,y+116,26,20).build());
    addRenderableWidget(Button.builder(Component.literal("自动"),b->widthBox.setValue("自动")).bounds(x+276,y+116,48,20).build());
    }else if(page==1){
      addRenderableWidget(Button.builder(Component.literal("左侧护栏："+on(options.leftRail())),b->{options=options.flags(!options.leftRail(),options.rightRail(),options.catenary());rebuildWidgets();}).bounds(x+12,y+40,154,20).build());
      addRenderableWidget(Button.builder(Component.literal("右侧护栏："+on(options.rightRail())),b->{options=options.flags(options.leftRail(),!options.rightRail(),options.catenary());rebuildWidgets();}).bounds(x+174,y+40,154,20).build());
      addRenderableWidget(Button.builder(Component.literal("地面护栏样式："+options.fence().label),b->{options=options.styles(options.bridge(),options.tunnel(),options.fence()==RailbedOptions.Fence.SOLID?RailbedOptions.Fence.MESH:RailbedOptions.Fence.SOLID);rebuildWidgets();}).bounds(x+12,y+67,316,20).build());
    }else{
      addRenderableWidget(Button.builder(Component.literal("露天接触网："+on(options.catenary())),b->{options=options.flags(options.leftRail(),options.rightRail(),!options.catenary());rebuildWidgets();}).bounds(x+12,y+35,316,20).build());
      var side=Button.builder(Component.literal(paths.size()==1?"单轨支柱："+(options.singleSide()>0?"左侧":"右侧"):"多轨：轨间支柱，双向悬臂"),b->{options=options.wire(options.poleSpacing(),options.wireHeight(),-options.singleSide());rebuildWidgets();}).bounds(x+12,y+62,316,20).build();side.active=paths.size()==1;addRenderableWidget(side);
      number("支柱间距（米）",options.poleSpacing(),90,true);
      number("接触网高度（米）",options.wireHeight(),116,false);
    }
    var build=Button.builder(Component.literal(payload.hasUUID("Id")?"保存":"建造"),b->{if(plan!=null&&previewReady&&RailbedGeometry.unsupported(paths)==null){send("railbedBuild");minecraft.setScreen(null);}}).bounds(x+12,y+184,74,20).build();buildButton=build;build.active=false;addRenderableWidget(build);
    addRenderableWidget(Button.builder(Component.literal("实景预览"),b->{ManualPlannerClient.worldPreview();onClose();}).bounds(x+92,y+184,82,20).build());addRenderableWidget(Button.builder(Component.literal("取消"),b->{cancelled(payload.getUUID("Token"));previewDelay=0;send("railbedCancel");reset();minecraft.setScreen(null);}).bounds(x+180,y+184,68,20).build());var delete=Button.builder(Component.literal("删除"),b->{send("railbedDelete");minecraft.setScreen(null);}).bounds(x+254,y+184,74,20).build();deleteButton=delete;addRenderableWidget(delete);refresh();restorePreview();
  }
  private static String on(boolean enabled){return enabled?"开":"关";}
  private void number(String title,double value,int row,boolean spacing){
    var box=new EditBox(font,x+202,y+row,126,20,Component.literal(title));box.setValue(String.valueOf(value));
    box.setResponder(text->{try{
      double n=Double.parseDouble(text.trim());options=options.wire(spacing?n:options.poleSpacing(),spacing?options.wireHeight():n,options.singleSide());refresh();
    }catch(IllegalArgumentException failure){plan=null;clear();previewReady=false;structureProblem="";status=spacing?"间距请输入 8–64 米":"高度请输入 6.1–12 米";updateBuild();}});addRenderableWidget(box);
  }
  private String bridgeLabel(){return options.bridge()==RoadInfrastructure.Bridge.STANDARD?"高架":options.bridge()==RoadInfrastructure.Bridge.SUSPENSION?"悬索":options.bridge()==RoadInfrastructure.Bridge.CABLE?"斜拉索":"拱桥";}
  private String label(){return mode==Structure.AUTO?"自动识别":mode==Structure.BRIDGE?"桥梁":"隧道";}
  private void restorePreview(){if(lastPreview!=null)open(lastPreview.copy());}
  private void change(double delta){try{double minimum=plan==null?RailbedGeometry.plan(paths,0,mode).minimumWidth():plan.minimumWidth();double base=deckWidth==0||!Double.isFinite(deckWidth)?minimum:deckWidth;widthBox.setValue(String.valueOf(Math.max(minimum,Math.min(64,base+delta))));}catch(RuntimeException failure){status=failure.getMessage();}}
  private void refresh(){previewReady=false;structureProblem="";previewDelay=8;previewToken=payload.getUUID("Token");previewWidth=deckWidth;previewMode=mode;previewOptions=options;previewSelection=payload.getString("Selection");try{String key=payload.getUUID("Token")+":"+payload.getString("Selection")+":"+deckWidth+":"+mode;if(!key.equals(cachedPlanKey)){cachedPlan=RailbedGeometry.plan(paths,deckWidth,mode);cachedPlanKey=key;}plan=cachedPlan;clear();owned=plan.mesh();ClientRoads.preview=owned;String unsupported=RailbedGeometry.unsupported(paths);status=unsupported==null?String.format(Locale.ROOT,"已选 %d 轨 · %.1f 格 · 最小总宽 %.1f",paths.size(),paths.get(0).length(),plan.minimumWidth()):unsupported;}catch(RuntimeException failure){plan=null;clear();status=failure.getMessage();}updateBuild();}
  private void updateBuild(){boolean reading=payload.getString("SourceStatus").startsWith("正在核验");if(buildButton!=null)buildButton.active=!reading&&plan!=null&&previewReady&&RailbedGeometry.unsupported(paths)==null;if(deleteButton!=null)deleteButton.active=payload.hasUUID("Id")&&!reading;}
  @Override public void tick(){super.tick();if(previewDelay>0&&--previewDelay==0&&plan!=null)send("railbedSettings");}
  private void send(String action){var tag=new CompoundTag();tag.putString("Action",action);tag.putUUID("Token",payload.getUUID("Token"));if(payload.hasUUID("PlannerSession"))tag.putUUID("PlannerSession",payload.getUUID("PlannerSession"));tag.putDouble("Width",deckWidth);tag.putString("Mode",mode.name());tag.put("Options",RailbedCodec.options(options));RoadNetwork.CHANNEL.sendToServer(new RoadNetwork.Action(tag));}
  @Override public void onClose(){if(plan!=null&&previewDelay>0){send("railbedSettings");previewDelay=0;}minecraft.setScreen(null);}
  @Override public boolean isPauseScreen(){return false;}
  @Override public void render(GuiGraphics g,int mx,int my,float dt){renderBackground(g);g.fill(x,y,x+340,y+220,0xee15232e);g.drawString(font,title,x+12,y+12,0xffffff,false);if(page==0){g.drawString(font,"路基总宽度",x+12,y+122,0xffffff,false);}
    else if(page==1){g.drawWordWrap(font,Component.literal("左右以 A → B 为准。隧道内不另设护栏；关闭一侧即移除该侧墙，预留站台接口。"),x+12,y+94,316,0xcbd9df);g.drawWordWrap(font,Component.literal("有顶隧道每 16 米设置真实照明。开放隧道不计入。"),x+12,y+121,316,0xcbd9df);}
    else{g.drawString(font,"支柱间距（8–64 米）",x+12,y+96,0xffffff,false);g.drawString(font,"高度（6.1–12 米）",x+12,y+122,0xffffff,false);}g.drawWordWrap(font,Component.literal(structureProblem.isEmpty()?status+(previewReady?"":" · 校验结构中…"):structureProblem),x+12,y+143,316,plan==null||!structureProblem.isEmpty()?0xff8888:0xcbd9df);g.drawString(font,font.plainSubstrByWidth(payload.getString("SourceStatus"),316),x+12,y+171,0xffcc77,false);super.render(g,mx,my,dt);}
}
