package com.sora.splineroads.client;
import com.sora.splineroads.core.*;
import com.sora.splineroads.world.*;
import com.sora.splineroads.net.RoadNetwork;
import java.util.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
public final class RoundaboutScreen extends Screen {
 private final CompoundTag payload;private RoadGeometry.Mesh ownedPreview;private List<RoadGeometry.Mesh> ownedPreviews;private List<RoadSurface.Face> ownedFaces;private EditBox radius;private String status="";private boolean pending;private Button build;
 private void edited(){ClientRoads.editorChanged(this);pending=false;}
 public RoundaboutScreen(CompoundTag t){super(Component.literal("Spline Roads · 环岛生成器"));payload=t.copy();}
 @Override protected void init(){int x=width/2-140,y=height/2-80;radius=new EditBox(font,x+130,y+30,140,20,Component.literal("中心线半径"));radius.setValue(Double.toString(payload.getDouble("Radius")));addRenderableWidget(radius);
 addRenderableWidget(Button.builder(Component.literal(payload.getInt("Lanes")==1?"环道：单车道":"环道：双车道"),b->{capture();payload.putInt("Lanes",payload.getInt("Lanes")==1?2:1);edited();rebuildWidgets();}).bounds(x,y+60,130,20).build());
 addRenderableWidget(Button.builder(Component.literal(payload.getBoolean("LeftTraffic")?"左侧通行":"右侧通行"),b->{capture();payload.putBoolean("LeftTraffic",!payload.getBoolean("LeftTraffic"));edited();rebuildWidgets();}).bounds(x+140,y+60,130,20).build());
 addRenderableWidget(Button.builder(Component.literal("外侧护栏："+(payload.getBoolean("OuterRail")?"开":"关")),b->{capture();payload.putBoolean("OuterRail",!payload.getBoolean("OuterRail"));edited();rebuildWidgets();}).bounds(x,y+86,270,20).build());
 build=addRenderableWidget(Button.builder(Component.literal("生成环岛"),b->{if(refresh()&&!pending){var t=payload.copy();t.putString("Action","roundabout");pending=true;build.active=false;ClientRoads.sendAction(t);}}).bounds(x,y+115,130,20).build());
 addRenderableWidget(Button.builder(Component.literal("取消"),b->onClose()).bounds(x+140,y+115,130,20).build());radius.setResponder(v->{edited();refresh();});refresh();}
 private boolean capture(){try{payload.putDouble("Radius",Double.parseDouble(radius.getValue()));return true;}catch(NumberFormatException e){status="请输入半径";return false;}}
 private boolean refresh(){if(!capture()){if(build!=null)build.active=false;clearPreview();return false;}try{var spec=RoundaboutTool.specification(payload);var plan=JunctionPlanner.plan(spec);ownedPreviews=plan.pieces().stream().map(JunctionPlanner.Piece::mesh).toList();ClientRoads.nodePreviews=ownedPreviews;ClientRoads.preview=ClientRoads.nodePreviews.get(0);ownedPreview=ClientRoads.preview;JunctionScreen.previewMesh=ownedPreview;var faces=new ArrayList<RoadSurface.Face>();for(var part:plan.pieces()){faces.addAll(part.paint());part.structures().forEach(s->faces.addAll(s.faces()));}ownedFaces=List.copyOf(faces);JunctionScreen.previewFaces=ownedFaces;status="生成 8 个方向接路点；A 北 B 南 C 西 D 东";if(build!=null)build.active=!pending;return true;}catch(IllegalArgumentException e){status=e.getMessage();if(build!=null)build.active=false;clearPreview();return false;}}
 public void failed(String message){pending=false;status=message;build.active=true;}
 private void clearPreview(){
  if(ownedPreview!=null&&ClientRoads.preview==ownedPreview)ClientRoads.preview=null;
  if(ownedPreviews!=null&&ClientRoads.nodePreviews==ownedPreviews)ClientRoads.nodePreviews=List.of();
  if(ownedPreview!=null&&JunctionScreen.previewMesh==ownedPreview)JunctionScreen.previewMesh=null;
  if(ownedFaces!=null&&JunctionScreen.previewFaces==ownedFaces)JunctionScreen.previewFaces=List.of();
  ownedPreview=null;ownedPreviews=null;ownedFaces=null;
 }
 @Override public void onClose(){clearPreview();super.onClose();}
 @Override public void removed(){clearPreview();super.removed();}
 @Override public boolean isPauseScreen(){return false;}
 @Override public void render(GuiGraphics g,int mx,int my,float dt){renderBackground(g);int x=width/2-140,y=height/2-80;g.fill(x-12,y-12,x+282,y+156,0xee1b2530);g.drawString(font,title,x,y,0xffffff);g.drawString(font,"中心线半径 / 格",x,y+36,0xd6e2ed);super.render(g,mx,my,dt);g.drawString(font,font.plainSubstrByWidth(status,280),x,y+143,0xb8d6cb);}
}
