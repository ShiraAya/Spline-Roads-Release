package com.sora.splineroads.client;
import com.sora.splineroads.core.*;
import com.sora.splineroads.core.SmartSigns.*;
import com.sora.splineroads.world.*;
import com.sora.splineroads.net.RoadNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.CompoundTag;
import java.util.*;

public final class SmartTextScreen extends Screen {
  private static SmartTextScreen draft;
  private final State original;private State editing;private final RoadRecord road;private final List<String> fields;private int selected,left,top;private EditBox value;private boolean initializing,pending,invalid;private String rawValue;private String status="所有文字均手填；输入 \\n 指定换行，空白区域不显示文字";
  public SmartTextScreen(CompoundTag t){super(Component.literal("Spline Roads · 文字编辑器"));original=SmartSignCodec.read(t.getCompound("Sign"));editing=original;road=RoadRecord.load(t.getCompound("Road"));fields=indicator()?new ArrayList<>(SmartSigns.layout(road.mesh(),original).boards().stream().filter(b->b.branch().startsWith("lane:")).sorted(Comparator.comparingDouble(Board::x)).map(b->b.branch()+"/status").toList()):new ArrayList<>(SmartSigns.fieldIds(original.mode(),original.branches()));if(fields.isEmpty())throw new IllegalArgumentException("当前标牌没有可编辑区域");selected=Math.max(0,fields.indexOf(t.getString("Field")));if(indicator())status="设置当前牌对应车道；确认前不会保存";}
  public static void open(CompoundTag t){String kind=t.getString("Kind");if(kind.equals("smartTextReset")){clear();return;}if(kind.equals("smartTextResume")){if(draft!=null)Minecraft.getInstance().setScreen(draft);return;}var incoming=SmartSignCodec.read(t.getCompound("Sign"));if(draft!=null&&draft.original.key().equals(incoming.key())&&draft.original.revision()==incoming.revision()&&(!draft.indicator()||draft.field().equals(t.getString("Field"))||draft.dirty())){Minecraft.getInstance().setScreen(draft);return;}draft=new SmartTextScreen(t);Minecraft.getInstance().setScreen(draft);}
  public static void clear(){draft=null;SmartSignClient.preview(null,null);}
  private void edited(){ClientRoads.editorChanged(this);pending=false;}
  private boolean indicator(){return original.mode()==Mode.LANE_INDICATOR;}
  private boolean dirty(){return invalid||!editing.text().equals(original.text())||!editing.hiddenHeaders().equals(original.hiddenHeaders())||!editing.hiddenAccessories().equals(original.hiddenAccessories())||!editing.closedLanes().equals(original.closedLanes());}
  private void initIndicator(){
    addRenderableWidget(Button.builder(Component.literal("上一车道指示牌"),b->select(-1)).bounds(left+10,top+30,165,20).build());
    addRenderableWidget(Button.builder(Component.literal("下一车道指示牌"),b->select(1)).bounds(left+185,top+30,165,20).build());
    addRenderableWidget(Button.builder(Component.literal(editing.closedLanes().contains(branch())?"状态：禁行（红叉）":"状态：通行（绿箭头）"),b->{edited();editing=editing.indicator(field(),!editing.closedLanes().contains(branch()));status="预览中；确认只保存当前牌对应车道";rebuildWidgets();}).bounds(left+10,top+83,340,20).build());
    addRenderableWidget(Button.builder(Component.literal("进入场景预览"),b->{draft=this;Minecraft.getInstance().setScreen(null);}).bounds(left+10,top+139,340,20).build());
    addRenderableWidget(Button.builder(Component.literal("确认当前指示牌"),b->submit(false)).bounds(left+10,top+261,165,20).build());
    addRenderableWidget(Button.builder(Component.literal("取消"),b->onClose()).bounds(left+185,top+261,165,20).build());
    initializing=false;SmartSignClient.preview(editing,road);
  }
  private String field(){return fields.get(selected);}
  private String branch(){return field().substring(0,field().lastIndexOf('/'));}
  private Text current(){return editing.text().getOrDefault(field(),Text.EMPTY);}
  @Override protected void init(){left=(width-360)/2;top=Math.max(4,(height-302)/2);initializing=true;if(indicator()){initIndicator();return;}var current=current();
    addRenderableWidget(Button.builder(Component.literal("上一文字区域"),b->select(-1)).bounds(left+10,top+30,165,20).build());addRenderableWidget(Button.builder(Component.literal("下一文字区域"),b->select(1)).bounds(left+185,top+30,165,20).build());
    value=new EditBox(font,left+10,top+83,340,20,Component.literal(SmartSigns.label(field())));value.setMaxLength(160);value.setValue(rawValue==null?current.value().replace("\n","\\n"):rawValue);value.setResponder(v->{rawValue=v;if(!initializing)change(v,current().size(),current().wrap());});addRenderableWidget(value);
    addRenderableWidget(Button.builder(Component.literal("字号："+new String[]{"小","中","大"}[current.size()]),b->{if(change(value.getValue(),(current().size()+1)%3,current().wrap()))rebuildWidgets();}).bounds(left+10,top+110,110,20).build());
    addRenderableWidget(Button.builder(Component.literal(current.wrap()?"允许换行":"单行"),b->{if(change(value.getValue(),current().size(),!current().wrap()))rebuildWidgets();}).bounds(left+125,top+110,110,20).build());
    addRenderableWidget(Button.builder(Component.literal("清空当前字段"),b->{value.setValue("");change("",current().size(),current().wrap());}).bounds(left+240,top+110,110,20).build());
    addRenderableWidget(Button.builder(Component.literal("进入场景预览"),b->{if(invalid)return;draft=this;Minecraft.getInstance().setScreen(null);}).bounds(left+10,top+139,165,20).build());addRenderableWidget(Button.builder(Component.literal("恢复此目标草稿"),b->submit(true)).bounds(left+185,top+139,165,20).build());
    if(SmartSigns.headerMode(editing.mode()))addRenderableWidget(Button.builder(Component.literal("本分支道路编号及名称区："+(editing.headerVisible(branch())?"显示":"隐藏")),b->{try{var next=editing.header(branch(),!editing.headerVisible(branch()));SmartSignClient.validate(road.mesh(),next);edited();editing=next;SmartSignClient.preview(editing,road);status="隐藏保留原文字，再次显示即可恢复";rebuildWidgets();}catch(IllegalArgumentException ex){status=ex.getMessage();}}).bounds(left+10,top+165,340,20).build());
    if(SmartSigns.headerMode(editing.mode()))for(int i=0;i<2;i++){String part=i==0?"exit":"distance",label=i==0?"显示出口编号":"显示距离";
      addRenderableWidget(Button.builder(Component.literal(label+"："+(editing.accessoryVisible(branch(),part)?"开":"关")),b->{try{var next=editing.accessory(branch(),part,!editing.accessoryVisible(branch(),part));SmartSignClient.validate(road.mesh(),next);edited();editing=next;SmartSignClient.preview(editing,road);status="仅改变附属牌显示，原文字与草稿保留";rebuildWidgets();}catch(IllegalArgumentException ex){status=ex.getMessage();}}).bounds(left+10+i*175,top+191,165,20).build());
    }
    addRenderableWidget(Button.builder(Component.literal("确认当前字段"),b->submit(false)).bounds(left+10,top+261,165,20).build());addRenderableWidget(Button.builder(Component.literal("取消"),b->onClose()).bounds(left+185,top+261,165,20).build());initializing=false;SmartSignClient.preview(editing,road);}
  private void select(int delta){ // One explicit field commit; switching never silently sends edits.
    if(dirty()){status="当前字段尚未确认；请先确认或取消，避免丢失修改";return;}edited();selected=Math.floorMod(selected+delta,fields.size());rawValue=null;rebuildWidgets();}
  private boolean change(String v,int size,boolean wrap){edited();try{var next=editing.edit(field(),new Text(v.replace("\\n","\n"),size,wrap));SmartSignClient.validate(road.mesh(),next);editing=next;status="预览中；确认前不会保存";invalid=false;SmartSignClient.preview(editing,road);return true;}catch(IllegalArgumentException e){invalid=true;status=e.getMessage();return false;}}
  private CompoundTag command(boolean restore){
      if(!restore&&!indicator()){var next=editing.edit(field(),new Text(value.getValue().replace("\\n","\n"),current().size(),current().wrap()));SmartSignClient.validate(road.mesh(),next);editing=next;}
      var t=SmartSignCodec.key(original.key());t.putString("Action","smartText");t.putLong("Revision",original.revision());
      if(original.target()!=null)t.putUUID("Target",original.target());t.putBoolean("Restore",restore);t.putString("Field",field());
      if(indicator())t.putBoolean("Closed",editing.closedLanes().contains(branch()));else t.put("Value",SmartSignCodec.text(current()));
      if(SmartSigns.headerMode(editing.mode())){t.putBoolean("HeaderVisible",editing.headerVisible(branch()));t.putBoolean("ExitVisible",editing.accessoryVisible(branch(),"exit"));t.putBoolean("DistanceVisible",editing.accessoryVisible(branch(),"distance"));}
      return t;
  }
  private void submit(boolean restore){
    if(pending)return;
    try{var t=command(restore);pending=true;ClientRoads.sendAction(t);}
    catch(IllegalArgumentException e){status=e.getMessage();}
  }
  public static void result(SmartTextScreen owner,boolean success,String message){if(draft!=owner||!owner.pending)return;draft.pending=false;if(success){clear();Minecraft.getInstance().setScreen(null);}else {draft.status=message;Minecraft.getInstance().setScreen(draft);}}
  @Override public void onClose(){clear();Minecraft.getInstance().setScreen(null);}
  @Override public boolean isPauseScreen(){return false;}
  @Override public void render(GuiGraphics g,int mx,int my,float dt){renderBackground(g);g.fill(left,top,left+360,top+297,0xf012212c);g.drawString(font,title,left+10,top+10,0xffffff);g.drawString(font,indicator()?"车道指示牌 "+(selected+1)+" / "+fields.size():"区域 "+(selected+1)+" / "+fields.size()+" · "+SmartSigns.label(field()),left+10,top+61,0xd2e4d8);super.render(g,mx,my,dt);g.drawWordWrap(font,Component.literal(status),left+10,top+221,340,0xffd9a5);}
}
