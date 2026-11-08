package com.sora.splineroads.client;
import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadInfrastructure.Gantry;
import com.sora.splineroads.world.*;
import com.sora.splineroads.net.RoadNetwork;
import java.util.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

public final class GantryScreen extends Screen {
  private final CompoundTag payload;private RoadGantry.Edit edit;private EditBox offset,clearance;
  /** 1=A→B, -1=B→A, 2=live player facing, 0=both (speed limit only). */
  private int flow=1;private final Map<Integer,SmartSigns.Mode> styles=new HashMap<>();private Button apply,reset,remove;
  private String status="";private boolean pending,confirmRemove;private int x,y;
  private void edited(){ClientRoads.editorChanged(this);pending=false;if(apply!=null&&reset!=null)active();}
  public GantryScreen(CompoundTag tag){
    super(Component.literal("Spline Roads · 单架龙门架编辑"));payload=tag.copy();
    edit=RoadGantry.edit(RoadRecord.readSettings(tag.getCompound("Settings")).options().infrastructure(),tag.getInt("Slot"));
  }
  private int effective(){return flow==2?(payload.getInt("PlayerFlow")<0?-1:1):flow==0?1:flow;}
  private SmartSigns.State sign(int direction){return SmartSignCodec.read(payload.getCompound(direction>0?"ForwardSign":"ReverseSign"));}
  private SmartSigns.Mode mode(){return styles.computeIfAbsent(effective(),f->{var m=sign(f).mode();return m==SmartSigns.Mode.NORMAL?SmartSigns.Mode.EMPTY:m==SmartSigns.Mode.EXIT_AUTO?SmartSigns.Mode.EXIT_SIGN:m;});}
  public static List<Integer> directions(SmartSigns.Mode mode,boolean twoWay){return (mode==SmartSigns.Mode.SPEED_LIMIT||mode==SmartSigns.Mode.LANE_INDICATOR)&&twoWay?List.of(1,-1,2,0):List.of(1,-1,2);}
  private String directionLabel(){return flow==0?"双向":flow==2?"当前方向（玩家朝向）":flow>0?"A → B":"B → A";}
  @Override protected void init(){
    x=(width-284)/2;y=Math.max(2,(height-282)/2);
    offset=field("沿路位移",Double.toString(edit.offset()),x+10,y+45);
    clearance=field("横梁基准净高",Double.toString(edit.clearance()),x+148,y+45);
    SmartSigns.Mode mode=mode();
    addRenderableWidget(Button.builder(Component.literal("样式："+SmartSigns.styleLabel(mode)),b->{if(capture()){
      edited();var next=SmartSigns.STYLES.get((SmartSigns.STYLES.indexOf(mode)+1)%SmartSigns.STYLES.size());styles.put(effective(),next);if(flow==0&&next!=SmartSigns.Mode.SPEED_LIMIT&&next!=SmartSigns.Mode.LANE_INDICATOR)flow=1;confirmRemove=false;rebuildWidgets();
    }}).bounds(x+10,y+73,264,20).build());
    addRenderableWidget(Button.builder(Component.literal("行驶方向："+directionLabel()),b->{if(capture()){
      edited();var choices=directions(mode,payload.getBoolean("TwoWay"));flow=choices.get((choices.indexOf(flow)+1)%choices.size());
      styles.put(effective(),mode);confirmRemove=false;rebuildWidgets();
    }}).bounds(x+10,y+97,264,20).build());
    var sign=sign(effective());status=payload.getBoolean("SignUpdating")?"标牌索引正在更新，稍后重新打开查看结果":sign.diagnostic().isEmpty()?"图形自动识别，文字使用文字编辑器填写":sign.diagnostic();
    apply=addRenderableWidget(Button.builder(Component.literal("应用到这一架"),b->send(false,false)).bounds(x+10,y+153,128,20).build());
    reset=addRenderableWidget(Button.builder(Component.literal("恢复这一架默认"),b->send(true,false)).bounds(x+146,y+153,128,20).build());
    remove=addRenderableWidget(Button.builder(Component.literal(confirmRemove?"确认删除这一架":"删除这一架"),b->{if(confirmRemove)send(false,true);else{confirmRemove=true;rebuildWidgets();}}).bounds(x+10,y+177,264,20).build());
    addRenderableWidget(Button.builder(Component.literal("取消"),b->onClose()).bounds(x+10,y+201,264,20).build());
    active();
  }
  private void active(){apply.active=reset.active=!pending;if(remove!=null)remove.active=!pending;}
  private EditBox field(String name,String value,int px,int py){var box=new EditBox(font,px,py,126,20,Component.literal(name));box.setMaxLength(16);box.setValue(value);box.setResponder(v->edited());addRenderableWidget(box);return box;}
  private boolean capture(){
    try{var next=edit.parameters(Double.parseDouble(offset.getValue()),Double.parseDouble(clearance.getValue()),edit.kind(),edit.reverse());
      if(Math.abs(next.offset())>payload.getDouble("MaxOffset"))throw new IllegalArgumentException(String.format(Locale.ROOT,"这架最多前后移动 %.1f 格",payload.getDouble("MaxOffset")));
      edit=next;status="";return true;
    }catch(IllegalArgumentException e){status=e instanceof NumberFormatException?"请输入完整数值":e.getMessage();return false;}
  }
  private void send(boolean restore,boolean deleting){
    if(pending||!restore&&!deleting&&!capture())return;
    var t=new CompoundTag();t.putString("Action","gantry");t.putUUID("Id",payload.getUUID("Id"));t.putInt("Slot",edit.slot());t.putInt("Signature",payload.getInt("Signature"));t.putBoolean("Reset",restore);t.putBoolean("Remove",deleting);
    t.putInt("Flow",flow);t.putString("SignMode",restore?"NORMAL":mode().name());t.putLong("SignRevision",sign(effective()).revision());t.putLong("ReverseSignRevision",sign(-1).revision());
    t.putDouble("Offset",edit.offset());t.putDouble("Clearance",edit.clearance());t.putString("Style",edit.kind()==Gantry.OFF||edit.kind()==Gantry.AUTO?Gantry.SIGNS.name():edit.kind().name());t.putBoolean("Reverse",edit.reverse());
    pending=true;active();status=deleting?"正在删除这一架…":"正在更新这座龙门架…";ClientRoads.sendAction(t);
  }
  public void failed(String message){pending=false;status=message;active();}
  @Override public boolean isPauseScreen(){return false;}
  @Override public void render(GuiGraphics g,int mx,int my,float dt){
    renderBackground(g);g.fill(x,y,x+284,y+282,0xf012212c);
    g.drawString(font,edit.independent()?"独立龙门架 · 不受道路龙门架开关影响":"龙门架 "+(edit.slot()+1)+" / "+payload.getInt("Count")+" · 单独编辑",x+10,y+12,0xffffff,false);
    g.drawString(font,mode()==SmartSigns.Mode.LANE_INDICATOR?"用文字编辑器右键指示牌设置通行／禁行":"文字用文字编辑器填写；限速和车道指示样式支持双向",x+10,y+128,0xbbd3dc,false);
    g.drawString(font,"沿路位移 / 格",x+10,y+29,0xbbd3dc,false);g.drawString(font,"横梁净高 / 格",x+148,y+29,0xbbd3dc,false);
    super.render(g,mx,my,dt);g.drawWordWrap(font,Component.literal(status),x+10,y+231,264,0xffcaa2);
  }
}
