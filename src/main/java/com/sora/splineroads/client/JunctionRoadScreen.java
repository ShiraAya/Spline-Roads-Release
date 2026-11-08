package com.sora.splineroads.client;

import com.sora.splineroads.core.*;
import com.sora.splineroads.world.*;
import com.sora.splineroads.net.RoadNetwork;
import java.util.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;

/** Road settings for an owned manual approach, without intersection controls. */
public final class JunctionRoadScreen extends Screen {
  private final CompoundTag payload,values;private final JunctionSpec spec;private final int arm;
  private final Map<String,EditBox> fields=new LinkedHashMap<>();
  private int x,y,page,debounce;private String status="";private boolean pending;private Button apply;
  public JunctionRoadScreen(CompoundTag t){super(Component.literal("道路设置 · 路口接入路段"));payload=t.copy();spec=JunctionCodec.read(t.getCompound("Spec"));arm=t.getInt("Arm");values=JunctionRoads.fields(spec.arms().get(arm));}
  @Override protected void init(){x=12;y=Math.max(0,(height-238)/2);fields.clear();
    button(page==0?"道路断面 → 人行道设置":"人行道设置 → 道路断面",x+12,y+29,264,()->{if(capture()){page=1-page;rebuildWidgets();}});
    if(page==0){
      field("入向车道数","Incoming",x+12,y+65);field("出向车道数","Outgoing",x+146,y+65);
      field("道路总宽 / 格","Width",x+12,y+106);field("中央分隔宽 / 格","Median",x+146,y+106);
      field("非机动车道宽 / 单侧","CycleWidth",x+12,y+147);field("路缘宽 / 单侧","CurbWidth",x+146,y+147);
      button("中央："+medianLabel(),x+12,y+174,128,()->{if(capture()){var a=new RoadProfile.Median[]{RoadProfile.Median.DOUBLE_YELLOW,RoadProfile.Median.RAIL,RoadProfile.Median.GREEN};int i=Arrays.asList(a).indexOf(RoadProfile.Median.valueOf(values.getString("MedianKind")));values.putString("MedianKind",a[(i+1)%a.length].name());edited();rebuildWidgets();}});
      button(values.getBoolean("CycleAsphalt")?"非机动车：沥青":"非机动车：绿色",x+146,y+174,130,()->{if(capture()){values.putBoolean("CycleAsphalt",!values.getBoolean("CycleAsphalt"));edited();rebuildWidgets();}});
    }else{
      button(values.getBoolean("Walk")?"人行道：显示":"人行道：隐藏",x+12,y+60,128,()->toggle("Walk"));
      button("侧别："+RoadSidewalks.Side.valueOf(values.getString("WalkSide")).label,x+146,y+60,130,()->{if(capture()){var v=RoadSidewalks.Side.valueOf(values.getString("WalkSide"));values.putString("WalkSide",RoadSidewalks.Side.values()[(v.ordinal()+1)%3].name());edited();rebuildWidgets();}});
      field("人行道宽 / 格","WalkWidth",x+12,y+107);
      button("材质："+RoadSidewalks.Finish.of(values.getString("WalkMaterial")).label,x+146,y+107,130,()->{if(capture()){var v=RoadSidewalks.Finish.of(values.getString("WalkMaterial"));values.putString("WalkMaterial",RoadSidewalks.Finish.values()[(v.ordinal()+1)%RoadSidewalks.Finish.values().length].id);edited();rebuildWidgets();}});
      button(values.getBoolean("Tactile")?"盲道：显示":"盲道：隐藏",x+12,y+144,264,()->toggle("Tactile"));
    }
    apply=button("更新此路段",x+12,y+207,128,this::send);apply.active=!pending;
    button("取消",x+146,y+207,130,this::onClose);preview();
  }
  private void edited(){ClientRoads.editorChanged(this);pending=false;if(apply!=null)apply.active=true;}
  private String medianLabel(){return switch(RoadProfile.Median.valueOf(values.getString("MedianKind"))){case RAIL->"护栏";case GREEN->"绿化带";case DOUBLE_YELLOW->"双黄线";case DASHED_YELLOW->"黄虚线";case NONE->"无";};}
  private void toggle(String k){if(capture()){values.putBoolean(k,!values.getBoolean(k));edited();rebuildWidgets();}}
  private Button button(String title,int a,int b,int w,Runnable action){return addRenderableWidget(Button.builder(Component.literal(title),v->action.run()).bounds(a,b,w,20).build());}
  private void field(String label,String key,int a,int b){var f=new EditBox(font,a,b,128,18,Component.literal(label));f.setMaxLength(8);f.setValue(Double.toString(values.getDouble(key)));f.setResponder(v->{edited();debounce=8;if(apply!=null)apply.active=!pending;});fields.put(key,f);addRenderableWidget(f);}
  private boolean capture(){try{for(var entry:fields.entrySet()){double n=Double.parseDouble(entry.getValue().getValue());if(!Double.isFinite(n))throw new NumberFormatException();if(Set.of("Incoming","Outgoing","WalkWidth").contains(entry.getKey())){if(n!=Math.rint(n))throw new NumberFormatException();values.putInt(entry.getKey(),(int)n);}else values.putDouble(entry.getKey(),n);}return true;}catch(IllegalArgumentException e){status="请输入有效数值；车道数和人行道宽须为整数";return false;}}
  private boolean preview(){if(!capture())return false;try{
    var arms=new ArrayList<>(spec.arms());arms.set(arm,JunctionRoads.change(arms.get(arm),values));var plan=JunctionPlanner.plan(spec.arms(arms));
    ClientRoads.nodePreviews=plan.pieces().stream().map(JunctionPlanner.Piece::mesh).toList();ClientRoads.preview=plan.pieces().get(arm).mesh();JunctionScreen.previewMesh=ClientRoads.preview;
    var faces=new ArrayList<RoadSurface.Face>();for(var p:plan.pieces()){faces.addAll(p.paint());for(var part:p.structures())faces.addAll(part.faces());}JunctionScreen.previewFaces=List.copyOf(faces);
    status="仅编辑接入路段 "+(arm+1)+"；路口配时、转向请用普通路口编辑器。";return true;
  }catch(IllegalArgumentException e){status=e.getMessage();return false;}}
  @Override public void tick(){super.tick();if(debounce>0&&--debounce==0&&!pending)preview();}
  private void send(){if(pending||!preview())return;var t=new CompoundTag();t.putString("Action","junctionRoad");t.putUUID("Id",payload.getUUID("Id"));t.putInt("Arm",arm);t.putInt("Signature",payload.getInt("Signature"));t.put("Fields",values.copy());pending=true;apply.active=false;ClientRoads.sendAction(t);}
  public void failed(String text){pending=false;status=text;apply.active=true;}
  @Override public void onClose(){JunctionScreen.clear();ClientRoads.preview=null;ClientRoads.nodePreviews=List.of();super.onClose();}
  @Override public boolean isPauseScreen(){return false;}
  @Override public void render(GuiGraphics g,int mx,int my,float dt){g.fill(x,y,x+288,y+238,0xed15212c);g.drawString(font,title,x+12,y+12,0xffffff,false);
    for(var f:fields.values())g.drawString(font,f.getMessage(),f.getX(),f.getY()-11,0xbbd3dc,false);
    super.render(g,mx,my,dt);
    if(width>=480)g.drawWordWrap(font,Component.literal(status),x+302,y+20,Math.max(130,width-x-312),0xffffff);
    else if(my>=y+195&&my<y+207)g.renderTooltip(font,Component.literal(status),mx,my);
    else g.drawString(font,font.plainSubstrByWidth(status,264),x+12,y+195,0xbbd3dc,false);
  }
}
