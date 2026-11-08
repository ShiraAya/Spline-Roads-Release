package com.sora.splineroads.client;
import com.sora.splineroads.core.*;
import com.sora.splineroads.world.RoadRecord;
import com.sora.splineroads.net.RoadNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
public final class LaneLineScreen extends Screen {
  private final CompoundTag payload;private final ListTag lines;private final RoadGeometry.Settings settings;
  private int selected,x,y,w,h;private RoadLaneLines.Pattern pattern;private double lineWidth;private EditBox widthBox;private Button apply,arrows;private String status="";private boolean pending,playerFacing=true;
  public LaneLineScreen(CompoundTag t){super(Component.literal("车道标线编辑器"));payload=t.copy();lines=t.getList("Lines",Tag.TAG_COMPOUND);settings=RoadRecord.readSettings(t.getCompound("Settings"));select(Math.min(lines.size()-1,t.getInt("Selected")));}
  private void edited(){ClientRoads.editorChanged(this);pending=false;if(apply!=null)apply.active=true;if(arrows!=null)arrows.active=true;}
  private void select(int i){edited();selected=i;var e=RoadLaneLines.get(settings,lines.getCompound(i).getString("Key"));pattern=e.pattern();lineWidth=e.width();}
  @Override protected void init(){w=Math.min(420,width-16);x=(width-w)/2;h=Math.min(280,height-8);y=(height-h)/2;
    addRenderableWidget(Button.builder(Component.literal(playerFacing?"朝向：与玩家一致":"朝向：起点→终点"),b->{playerFacing=!playerFacing;b.setMessage(Component.literal(playerFacing?"朝向：与玩家一致":"朝向：起点→终点"));}).bounds(x+12,y+25,w-24,18).build());
    addRenderableWidget(Button.builder(Component.literal("◀"),b->{select((selected+lines.size()-1)%lines.size());rebuildWidgets();}).bounds(x+12,y+h-132,28,20).build());
    addRenderableWidget(Button.builder(Component.literal(lines.getCompound(selected).getString("Label")),b->{select((selected+1)%lines.size());rebuildWidgets();}).bounds(x+44,y+h-132,w-88,20).build());
    addRenderableWidget(Button.builder(Component.literal("▶"),b->{select((selected+1)%lines.size());rebuildWidgets();}).bounds(x+w-40,y+h-132,28,20).build());
    addRenderableWidget(Button.builder(Component.literal("线型："+pattern.label),b->{edited();pattern=RoadLaneLines.Pattern.values()[(pattern.ordinal()+1)%RoadLaneLines.Pattern.values().length];b.setMessage(Component.literal("线型："+pattern.label));}).bounds(x+12,y+h-105,w-126,20).build());
    widthBox=new EditBox(font,x+w-106,y+h-105,94,20,Component.literal("线宽"));widthBox.setMaxLength(6);widthBox.setValue(Double.toString(lineWidth));widthBox.setResponder(v->edited());addRenderableWidget(widthBox);
    arrows=addRenderableWidget(Button.builder(Component.literal(settings.options().hideArrows()?"本路段箭头：隐藏 · 点击显示":"本路段箭头：显示 · 点击隐藏"),b->sendArrows()).bounds(x+12,y+h-51,w-24,20).build());arrows.active=!pending;
    apply=addRenderableWidget(Button.builder(Component.literal("应用到这根线"),b->send()).bounds(x+12,y+h-76,(w-30)/2,20).build());apply.active=!pending;
    addRenderableWidget(Button.builder(Component.literal("取消"),b->onClose()).bounds(x+w/2+3,y+h-76,(w-30)/2,20).build());
  }
  public static int facingSign(double roadYaw,double playerYaw){return Math.cos(Math.toRadians(roadYaw-playerYaw))>=0?1:-1;}
  private int lx(int i){return x+26+(int)((.5+(playerFacing?facingSign(payload.getDouble("RoadYaw"),payload.getDouble("PlayerYaw")):1)*lines.getCompound(i).getDouble("Offset")/payload.getDouble("Width"))*(w-52));}
  @Override public boolean mouseClicked(double mx,double my,int button){if(button==0&&my>=y+57&&my<=y+h-143){int best=selected;double d=12;for(int i=0;i<lines.size();i++)if(Math.abs(mx-lx(i))<d){d=Math.abs(mx-lx(i));best=i;}select(best);rebuildWidgets();return true;}return super.mouseClicked(mx,my,button);}
  private void send(){if(pending)return;try{var edit=new RoadLaneLines.Edit(lines.getCompound(selected).getString("Key"),pattern,Double.parseDouble(widthBox.getValue()));var t=new CompoundTag();t.putString("Action","laneLines");t.putUUID("Id",payload.getUUID("Id"));t.putInt("Signature",payload.getInt("Signature"));t.putDouble("Station",payload.getDouble("Station"));t.putString("Key",edit.key());t.putString("Pattern",pattern.name());t.putDouble("Width",edit.width());pending=true;apply.active=false;arrows.active=false;ClientRoads.sendAction(t);}catch(IllegalArgumentException e){status=e instanceof NumberFormatException?"请输入有效线宽":e.getMessage();}}
  private void sendArrows(){if(pending)return;var t=new CompoundTag();t.putString("Action","laneLines");t.putUUID("Id",payload.getUUID("Id"));t.putInt("Signature",payload.getInt("Signature"));t.putDouble("Station",payload.getDouble("Station"));t.putBoolean("HideArrows",!settings.options().hideArrows());pending=true;apply.active=false;arrows.active=false;ClientRoads.sendAction(t);}
  public void failed(String s){pending=false;status=s;apply.active=true;arrows.active=true;}
  @Override public boolean isPauseScreen(){return false;}
  @Override public void render(GuiGraphics g,int mx,int my,float dt){renderBackground(g);g.fill(x,y,x+w,y+h,0xf015222e);g.drawString(font,String.format(java.util.Locale.ROOT,"标线区间 %.1f–%.1f 米 · 点击选线",payload.getDouble("RangeStart"),payload.getDouble("RangeEnd")),x+12,y+13,0xffffff,false);g.fill(x+20,y+57,x+w-20,y+h-143,0xff43474b);
    for(int i=0;i<lines.size();i++){var p=i==selected?pattern:RoadLaneLines.get(settings,lines.getCompound(i).getString("Key")).pattern();int xx=lx(i),color=i==selected?0xff56d8ff:p.yellow()?0xffffd549:0xffeeeeee;for(int yy=y+65;yy<y+h-151;yy+=12)g.fill(xx-1,yy,xx+2,Math.min(yy+(p.dashed()?7:12),y+h-151),color);g.drawCenteredString(font,Integer.toString(i+1),xx,y+47,i==selected?0x56d8ff:0xbbbbbb);}
    super.render(g,mx,my,dt);g.drawWordWrap(font,Component.literal(status.isEmpty()?"线宽 0.06–0.4 格。箭头开关仅作用于本路段。":status),x+12,y+h-26,w-24,status.isEmpty()?0xbbd3dc:0xffb58a);
  }
}
