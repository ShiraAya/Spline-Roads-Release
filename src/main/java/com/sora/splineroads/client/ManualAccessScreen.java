package com.sora.splineroads.client;

import com.sora.splineroads.world.RoadRecord;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;

/** Explicit preview of the complete paired access, before its single world transaction. */
public final class ManualAccessScreen extends Screen {
  private final CompoundTag payload;private EditBox length;private Button build;private String status="先预览两条匝道、接出道路和主路分合流点";private boolean pending;
  ManualAccessScreen(CompoundTag t){super(Component.literal("双向进出匝道"));payload=t.copy();}
  @Override protected void init(){int x=width/2-150,y=height/2-85;length=new EditBox(font,x+120,y+42,165,20,Component.literal("接出道路长度"));length.setValue(Double.toString(payload.getDouble("SpurLength")));length.setResponder(v->{payload.remove("Token");if(build!=null)build.active=false;});addRenderableWidget(length);addRenderableWidget(Button.builder(Component.literal("计算完整预览"),b->send("manualAccessPreview")).bounds(x+12,y+102,128,20).build());build=addRenderableWidget(Button.builder(Component.literal("统一建造"),b->send("manualAccessBuild")).bounds(x+150,y+102,138,20).build());build.active=payload.hasUUID("Token");addRenderableWidget(Button.builder(Component.literal("实景预览"),b->{show();ManualPlannerClient.worldPreview();minecraft.setScreen(null);}).bounds(x+12,y+127,128,20).build());addRenderableWidget(Button.builder(Component.literal("返回地图"),b->onClose()).bounds(x+150,y+127,138,20).build());if(payload.hasUUID("Token")){status="完整预览已校验，可以统一建造；按 P 返回设置";show();}}
  private void show(){var roads=payload.getList("PlannerPreview",Tag.TAG_COMPOUND).stream().map(t->RoadRecord.load((CompoundTag)t).mesh()).toList();ClientRoads.nodePreviews=roads;ClientRoads.preview=roads.isEmpty()?null:roads.get(0);ClientRoads.conflictPreview=false;}
  private void send(String action){if(pending)return;try{double value=Double.parseDouble(length.getValue());if(!Double.isFinite(value)||value<64||value>160)throw new IllegalArgumentException();payload.putDouble("SpurLength",value);var t=payload.copy();t.remove("PlannerPreview");t.putString("Action",action);pending=true;status="正在校验完整占地与连接…";ClientRoads.sendAction(t);}catch(IllegalArgumentException e){status="长度请输入64–160格";}}
  void failed(String text){pending=false;status=text;}
  @Override public boolean isPauseScreen(){return false;}
  @Override public void render(GuiGraphics g,int mx,int my,float dt){renderBackground(g);int x=width/2-150,y=height/2-85;g.fill(x,y,x+300,y+176,0xf015222e);g.drawString(font,title,x+12,y+13,0xffffffff,false);g.drawString(font,"接出道路长度",x+12,y+48,0xffc6dbe7,false);g.drawWordWrap(font,Component.literal(status),x+12,y+72,276,0xffd2e2eb);super.render(g,mx,my,dt);}
}
