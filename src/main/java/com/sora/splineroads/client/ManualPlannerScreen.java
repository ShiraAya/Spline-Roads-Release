package com.sora.splineroads.client;

import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.world.*;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.*;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import org.lwjgl.glfw.GLFW;

/** A client-only view of session-resident terrain and authoritative road/point records. */
public final class ManualPlannerScreen extends Screen {
  final UUID session;private CompoundTag state;private double cx,cz,zoom=3;private int tool=1,arms=4,endpointMode;
  private final List<Button> toolbar=new ArrayList<>();
  private Button resumePreview;
  private String status="选择工具后单击地图；右键点可打开菜单";private boolean help;
  private record Point(String kind,UUID road,UUID id,BlockPos pos,V at){}
  private Point selected;private UUID selectedRoad;private final List<Point> points=new ArrayList<>();
  private boolean pressed,dragged,moving,openSelected;
  private final Set<UUID> hidden=new HashSet<>();
  private final Map<UUID,RoadIndex.Built> mapRoads=new LinkedHashMap<>();
  // Geometry/occlusion is independent of the mouse, selection tint and player
  // cursor. Reuse it until a map snapshot, node tile, hidden set or view changes.
  private long mapRevision,preparedRevision=-1;
  private double preparedCx,preparedCz,preparedZoom;
  private int preparedWidth,preparedHeight;
  private Set<UUID> preparedHidden=Set.of();
  private final List<Strip> preparedStrips=new ArrayList<>();
  private final List<Point> displayedPoints=new ArrayList<>();
  private UUID snapshot;private int nextPage,pageCount,expectedRoads,expectedNodes;private long snapshotAt;
  private final Map<UUID,RoadIndex.Built> pendingRoads=new LinkedHashMap<>();
  private final Map<Long,CompoundTag> pendingNodes=new HashMap<>();
  private final Map<Long,MapTile> tiles=new HashMap<>();private final Map<Long,CompoundTag> mapNodes=new HashMap<>();private int terrainStep=1,loadedTiles,totalTiles;
  private record MapTile(int[] colors,int[] heights){}
  /** Surface data only: no session, road, node, selection or editor state survives closing. */
  static final class SurfaceCache {
    final Map<Long,MapTile> tiles;DynamicTexture terrain;ResourceLocation texture;
    SurfaceCache(Map<Long,MapTile> tiles,DynamicTexture terrain,ResourceLocation texture){this.tiles=tiles;this.terrain=terrain;this.texture=texture;}
    void release(){if(texture!=null)Minecraft.getInstance().getTextureManager().release(texture);terrain=null;texture=null;tiles.clear();}
  }
  void invalidateSurface(){
    tiles.clear();if(texture!=null)Minecraft.getInstance().getTextureManager().release(texture);texture=null;
    if(terrain!=null){terrain=new DynamicTexture(new NativeImage(1024,1024,false));texture=Minecraft.getInstance().getTextureManager().register("sr_manual_map",terrain);terrainReady=false;resetTerrain();}
  }
  int cacheSeconds(){return Math.max(0,Math.min(60,state.getInt("CacheSeconds")));}
  int cacheRadius(){return Math.max(16,Math.min(2048,state.getInt("Radius")));}
  SurfaceCache detachSurface(){var saved=new SurfaceCache(new HashMap<>(tiles),terrain,texture);tiles.clear();terrain=null;texture=null;return saved;}
  void restoreSurface(SurfaceCache saved){tiles.putAll(saved.tiles);saved.tiles.clear();terrain=saved.terrain;texture=saved.texture;saved.terrain=null;saved.texture=null;terrainReady=false;}
private double pressX,pressY,pressCenterX,pressCenterZ,pressAt;private V movingAt;private int axis;private long confirmAt;private double confirmX,confirmY;
  private int menuX=-1,menuY,terrainX,terrainZ,terrainCursor;private long terrainUploaded,terrainFinished;private int terrainScanStart;private boolean terrainReady,terrainDirty;private final int[] heights=new int[1024*1024];
  private DynamicTexture terrain;private ResourceLocation texture;private final Set<Integer> keys=new HashSet<>();
  ManualPlannerScreen(CompoundTag t){super(Component.literal("Spline Roads · 手动规划"));session=t.getUUID("PlannerSession");state=t.copy();var p=Minecraft.getInstance().player;cx=p.getX();cz=p.getZ();Arrays.fill(heights,Integer.MIN_VALUE);}
  void state(CompoundTag t){interchangePending=false;if(t.contains("MapRoads")||t.contains("Nodes"))mapRevision++;state=t.copy();tool=t.getInt("Tool");arms=t.getInt("Arms");endpointMode=Math.max(0,Math.min(3,t.getInt("EndpointMode")));if(t.contains("MapRoads")){var ids=new HashSet<UUID>();for(Tag value:t.getList("MapRoads",Tag.TAG_COMPOUND)){var r=RoadRecord.load((CompoundTag)value);ids.add(r.id());var old=mapRoads.get(r.id());if(old==null||!old.record.equals(r))mapRoads.put(r.id(),RoadIndex.Built.loading(r));}mapRoads.keySet().retainAll(ids);hidden.retainAll(ids);}if(t.contains("Nodes")){mapNodes.clear();for(Tag value:t.getList("Nodes",Tag.TAG_COMPOUND)){var n=(CompoundTag)value;mapNodes.put(n.getLong("Pos"),n);}}updateToolbar();}
  void beginSnapshot(CompoundTag t){snapshot=t.getUUID("MapSnapshot");nextPage=0;pageCount=t.getInt("Pages");expectedRoads=t.getInt("RoadCount");expectedNodes=t.getInt("NodeCount");pendingRoads.clear();pendingNodes.clear();snapshotAt=net.minecraft.Util.getMillis();if(expectedRoads<0||expectedNodes<0||pageCount<1||pageCount>Math.max(1,(long)expectedRoads+expectedNodes))throw new IllegalArgumentException("规划地图批次数量错误，请重新打开");status="正在加载规划道路…";}
  boolean loading(){return snapshot!=null;}
  boolean snapshotExpired(long now){return loading()&&now-snapshotAt>30000;}
  void failSnapshot(String message){snapshot=null;pendingRoads.clear();pendingNodes.clear();status=message;}
  void page(CompoundTag t){
    if(snapshot==null||!t.hasUUID("MapSnapshot")||!snapshot.equals(t.getUUID("MapSnapshot")))return;
    if(t.getInt("Pages")!=pageCount||t.getInt("Page")!=nextPage)throw new IllegalArgumentException("规划地图分批顺序错误，请重新打开");
    for(Tag value:t.getList("MapRoads",Tag.TAG_COMPOUND)){var r=RoadRecord.load((CompoundTag)value);var old=mapRoads.get(r.id());pendingRoads.put(r.id(),old!=null&&old.record.equals(r)?old:RoadIndex.Built.loading(r));}
    for(Tag value:t.getList("Nodes",Tag.TAG_COMPOUND)){var node=(CompoundTag)value;pendingNodes.put(node.getLong("Pos"),node);}
    if(pendingRoads.size()>expectedRoads||pendingNodes.size()>expectedNodes)throw new IllegalArgumentException("规划地图数据数量错误，请重新打开");
    snapshotAt=net.minecraft.Util.getMillis();
    if(++nextPage==pageCount){
      if(pendingRoads.size()!=expectedRoads||pendingNodes.size()!=expectedNodes)throw new IllegalArgumentException("规划地图数据不完整，请重新打开");
      mapRevision++;mapRoads.clear();mapRoads.putAll(pendingRoads);mapNodes.clear();mapNodes.putAll(pendingNodes);hidden.retainAll(mapRoads.keySet());pendingRoads.clear();pendingNodes.clear();snapshot=null;status=hint();
    }
  }
  void result(boolean success,String message){if(!message.isEmpty())status=message.equals("已选择地图工具")?hint():message;}
  void refresh(){if(minecraft==null)return;var t=command("manualRefresh");ClientRoads.sendAction(t);}
  private CompoundTag command(String action){var t=new CompoundTag();t.putString("Action",action);t.putUUID("PlannerSession",session);return t;}
  private int left(){return 116;}private int top(){return 36;}private int right(){return width-8;}private int bottom(){return height-43;}
  private double sx(double x){return (left()+right())*.5+(x-cx)*zoom;}private double sy(double z){return (top()+bottom())*.5+(z-cz)*zoom;}
  private double wx(double x){return cx+(x-(left()+right())*.5)/zoom;}private double wz(double y){return cz+(y-(top()+bottom())*.5)/zoom;}
  private boolean map(double x,double y){return x>=left()&&x<right()&&y>=top()&&y<bottom();}
  @Override protected void init(){
    toolbar.clear();String[] labels={"1  指针","2  端点","3  道路连接","4  匝道连接","5  自动立交","6  收费站","7  环岛"};
    int step=Math.min(25,Math.max(10,(height-(height>=240?144:120))/7));
    for(int i=0;i<7;i++){int n=i+1;toolbar.add(addRenderableWidget(Button.builder(Component.literal(labels[i]),b->choose(n)).bounds(8,38+i*step,100,step-3).build()));}
    addRenderableWidget(Button.builder(Component.literal("清除选点"),b->{selected=null;selectedRoad=null;moving=false;menuX=-1;confirmAt=0;send("manualClear",null,null,0,0);}).bounds(8,41+7*step,100,20).build());
    if(height>=240)addRenderableWidget(Button.builder(Component.literal("回到玩家"),b->{cx=minecraft.player.getX();cz=minecraft.player.getZ();resetTerrain();}).bounds(8,65+7*step,100,20).build());
    addRenderableWidget(Button.builder(Component.literal("设置"),b->minecraft.setScreen(new PlannerSettings())).bounds(width-158,8,50,20).build());
    addRenderableWidget(Button.builder(Component.literal("? 操作说明"),b->help=!help).bounds(width-102,8,94,20).build());
    resumePreview=addRenderableWidget(Button.builder(Component.literal("返回设置"),b->ManualPlannerClient.resumeMapPreview()).bounds(8,height-54,100,20).build());
    addRenderableWidget(Button.builder(Component.literal("关闭地图"),b->onClose()).bounds(8,height-28,100,20).build());
    updateToolbar();if(terrain==null){terrain=new DynamicTexture(new NativeImage(1024,1024,false));texture=minecraft.getTextureManager().register("sr_manual_map",terrain);}resetTerrain();
  }
  private void updateToolbar(){for(int i=0;i<toolbar.size();i++){var text=toolbar.get(i).getMessage().getString();if(i==4)text="5  "+arms+"向立交";if(i==1)text="2  "+endpointName();toolbar.get(i).setMessage(Component.literal(text).withStyle(i+1==tool?net.minecraft.ChatFormatting.AQUA:net.minecraft.ChatFormatting.WHITE));}}
  private void choose(int n){interchangePending=false;if(tool!=n){selected=null;selectedRoad=null;openSelected=false;}tool=n;updateToolbar();menuX=-1;moving=false;var t=command("manualTool");t.putInt("Tool",tool);t.putInt("Arms",arms);t.putInt("EndpointMode",endpointMode);ClientRoads.sendAction(t);status=hint();}
  private String hint(){return switch(tool){case 2->"Ctrl＋滚轮切换：道路端点 → 路口点 → 附属点 → 出入点；当前："+endpointName();case 3->"选择端点或附属点 A → B；Ctrl：经过点；左 Alt：控制点";case 4->"Ctrl：创建车道点；选 A / B；左 Alt：匝道放置点";case 5->"双击主路依次选 AB、CD、EF；3/5向末端选 C/E；Ctrl＋滚轮切换向数";case 6->"单击道路断面，或依次选择端点 A / B";case 7->"单击空闲端点作为环岛圆心";default->"首次单击选中，再次单击打开；右键菜单；H 恢复隐藏";};}
  private void resetTerrain(){
    int step=Math.max(1,(int)Math.ceil(Math.max((right()-left())/zoom,(bottom()-top())/zoom)/1024));
    int nx=((int)Math.floor(cx)-512*step)&~15,nz=((int)Math.floor(cz)-512*step)&~15;
    if(terrainReady&&nx==terrainX&&nz==terrainZ&&step==terrainStep)return;
    terrainX=nx;terrainZ=nz;terrainStep=step;terrainReady=true;terrainCursor=0;
    var image=terrain.getPixels();for(int z=0;z<1024;z++)for(int x=0;x<1024;x++){
      int bx=terrainX+x*step,bz=terrainZ+z*step;var tile=tiles.get(net.minecraft.world.level.ChunkPos.asLong(bx>>4,bz>>4));int at=(bz&15)*16+(bx&15);
      image.setPixelRGBA(x,z,tile==null?0xff252b30:tile.colors()[at]);heights[z*1024+x]=tile==null?Integer.MIN_VALUE:tile.heights()[at];
    }terrainDirty=true;
  }
  void terrain(CompoundTag t){
    if(t.contains("Error")){status=t.getString("Error");return;}
    int[] colors=t.getIntArray("Colors"),values=t.getIntArray("Heights");if(colors.length!=256||values.length!=256)return;
    mapRevision++;int x=t.getInt("ChunkX"),z=t.getInt("ChunkZ");tiles.put(net.minecraft.world.level.ChunkPos.asLong(x,z),new MapTile(colors,values));
    if(tiles.size()>60000){var player=Minecraft.getInstance().player;tiles.keySet().removeIf(key->{var c=new net.minecraft.world.level.ChunkPos(key);return Math.hypot(c.x*16+8-player.getX(),c.z*16+8-player.getZ())>2080;});}
    mapNodes.entrySet().removeIf(e->{var pos=BlockPos.of(e.getKey());return pos.getX()>>4==x&&pos.getZ()>>4==z;});
    for(Tag item:t.getList("Nodes",Tag.TAG_COMPOUND)){var n=(CompoundTag)item;mapNodes.put(n.getLong("Pos"),n);}
    loadedTiles=t.getInt("Loaded");totalTiles=t.getInt("Total");paintTile(x,z);
  }
  private void paintTile(int cx,int cz){
    if(terrain==null)return;var tile=tiles.get(net.minecraft.world.level.ChunkPos.asLong(cx,cz));var image=terrain.getPixels();boolean changed=false;
    for(int z=Math.max(0,Math.floorDiv(cz*16-terrainZ+terrainStep-1,terrainStep));z<1024&&terrainZ+z*terrainStep<cz*16+16;z++)
      for(int x=Math.max(0,Math.floorDiv(cx*16-terrainX+terrainStep-1,terrainStep));x<1024&&terrainX+x*terrainStep<cx*16+16;x++){
        int i=((terrainZ+z*terrainStep)&15)*16+((terrainX+x*terrainStep)&15);image.setPixelRGBA(x,z,tile.colors()[i]);heights[z*1024+x]=tile.heights()[i];changed=true;
      }
    if(changed)terrainDirty=true;
  }
  void release(){if(texture!=null)Minecraft.getInstance().getTextureManager().release(texture);terrain=null;texture=null;tiles.clear();mapNodes.clear();mapRoads.clear();pendingRoads.clear();pendingNodes.clear();snapshot=null;hidden.clear();points.clear();displayedPoints.clear();preparedStrips.clear();preparedHidden=Set.of();mapRevision++;preparedRevision=-1;}
  @Override public void tick(){
    if(minecraft.level==null||terrain==null)return;double step=(keys.contains(GLFW.GLFW_KEY_LEFT_SHIFT)?12:5)/zoom;if(keys.contains(GLFW.GLFW_KEY_W))cz-=step;if(keys.contains(GLFW.GLFW_KEY_S))cz+=step;if(keys.contains(GLFW.GLFW_KEY_A))cx-=step;if(keys.contains(GLFW.GLFW_KEY_D))cx+=step;
    double dx=cx-minecraft.player.getX(),dz=cz-minecraft.player.getZ(),distance=Math.hypot(dx,dz);if(distance>2048){cx=minecraft.player.getX()+dx*2048/distance;cz=minecraft.player.getZ()+dz*2048/distance;}
    int wanted=Math.max(1,(int)Math.ceil(Math.max((right()-left())/zoom,(bottom()-top())/zoom)/1024));
    if(Math.abs(cx-(terrainX+512*terrainStep))>192*terrainStep||Math.abs(cz-(terrainZ+512*terrainStep))>192*terrainStep||wanted!=terrainStep)resetTerrain();
    // Immediately seed visible loaded chunks; remote tiles arrive independently from the server.
    long end=System.nanoTime()+3_000_000;int read=0;while(read++<4096&&System.nanoTime()<end){
      int at=terrainCursor++&4095,x=(terrainX>>4)+(at&63),z=(terrainZ>>4)+(at>>6);long key=net.minecraft.world.level.ChunkPos.asLong(x,z);if(tiles.containsKey(key))continue;
      var chunk=minecraft.level.getChunkSource().getChunk(x,z,ChunkStatus.FULL,false);if(chunk==null)continue;
      int[] colors=new int[256],height=new int[256];for(int j=0;j<16;j++)for(int i=0;i<16;i++){int y=chunk.getHeight(Heightmap.Types.WORLD_SURFACE,i,j);var pos=new BlockPos(x*16+i,y,z*16+j);int color=chunk.getBlockState(pos).getMapColor(minecraft.level,pos).col;if(color==0)color=0x777777;int north=chunk.getHeight(Heightmap.Types.WORLD_SURFACE,i,Math.max(0,j-1)),shade=y>north?255:y<north?175:220;colors[j*16+i]=0xff000000|((color&255)*shade/255<<16)|(((color>>8)&255)*shade/255<<8)|((color>>16)&255)*shade/255;height[j*16+i]=y+1;}tiles.put(key,new MapTile(colors,height));paintTile(x,z);
    }
  }
  private V terrain(double x,double y){int bx=(int)Math.floor(wx(x)),bz=(int)Math.floor(wz(y));var tile=tiles.get(net.minecraft.world.level.ChunkPos.asLong(bx>>4,bz>>4));return tile==null?null:new V(wx(x),tile.heights()[(bz&15)*16+(bx&15)],wz(y));}
  private boolean interchangePending;private long roadClickAt;private double roadClickX,roadClickY;private UUID roadClickId;
  private boolean interchangeTerminal(){int count=state.getCompound("Selection").getLongArray("Points").length;return (arms==3||arms==5)&&count==arms-1;}
  private boolean nodeVisible(BlockPos pos){boolean linked=false;for(var b:mapRoads.values())if(b.record.a().equals(pos)||b.record.b().equals(pos)){linked=true;if(!hidden.contains(b.record.id()))return true;}return !linked;}
  private void collect(){
    if(preparedRevision==mapRevision&&preparedCx==cx&&preparedCz==cz&&preparedZoom==zoom&&preparedWidth==width&&preparedHeight==height&&preparedHidden.equals(hidden))return;
    preparedRevision=-1;collectPoints();preparedStrips.clear();displayedPoints.clear();
    for(var b:mapRoads.values()){if(hidden.contains(b.record.id())||!visible(b.mesh))continue;var samples=b.mesh.samples();for(int i=1;i<samples.size();i++)preparedStrips.add(new Strip(b,samples.get(i-1),samples.get(i)));}
    preparedStrips.sort(Comparator.comparingDouble(Strip::height).thenComparing(q->q.road().record.id()));
    for(var p:points){var cover=road(sx(p.at().x()),sy(p.at().z()));if(cover!=null){var q=RoadQueries.horizontal(cover.mesh,p.at());if(q.horizontalDistance()<q.sample().halfWidth()&&q.sample().center().y()>p.at().y()+.1)continue;}displayedPoints.add(p);}
    preparedCx=cx;preparedCz=cz;preparedZoom=zoom;preparedWidth=width;preparedHeight=height;preparedHidden=Set.copyOf(hidden);preparedRevision=mapRevision;
  }
  private void collectPoints(){points.clear();var nodes=new HashSet<BlockPos>();for(Tag value:mapNodes.values()){var t=(CompoundTag)value;var pos=BlockPos.of(t.getLong("Pos"));if(!nodeVisible(pos))continue;nodes.add(pos);points.add(new Point(t.getBoolean("JunctionMarker")?"junction":"node",null,null,pos,RoadRecord.readNode(t.getCompound("Node")).position()));}
    for(var b:mapRoads.values()){var r=b.record;if(hidden.contains(b.record.id())||!visible(b.mesh))continue;if(r.railway()){for(boolean first:new boolean[]{true,false})points.add(new Point("rail",r.id(),null,null,(first?r.start():r.end()).position()));continue;}if(!r.settings().style().ramp()&&LaneTopology.metadata(r).link()==null)for(boolean first:new boolean[]{true,false}){var pos=first?r.a():r.b();if(nodes.add(pos))points.add(new Point("node",null,null,pos,(first?r.start():r.end()).position()));}
      for(var p:LaneTopology.visiblePoints(r))points.add(new Point("lane",r.id(),p.id(),null,p.position()));var controls=new HashSet<UUID>();for(var p:r.settings().options().routing().controls()){controls.add(p.id());points.add(new Point("control",r.id(),p.id(),null,p.position()));}for(var p:r.settings().options().attachments().points())if(!controls.contains(p.id()))points.add(new Point(p.elevated()?"elevated":"attached",r.id(),p.id(),null,p.position()));
    }
  }
  private boolean visible(Mesh mesh){return sx(mesh.max().x())>=left()&&sx(mesh.min().x())<=right()&&sy(mesh.max().z())>=top()&&sy(mesh.min().z())<=bottom();}
  private Point pick(double x,double y){Point best=null;double distance=8;for(var p:points){double d=Math.hypot(sx(p.at().x())-x,sy(p.at().z())-y);if(d<8&&(best==null||p.at().y()>best.at().y()+.01||Math.abs(p.at().y()-best.at().y())<=.01&&d<distance)){var cover=road(x,y);if(cover!=null){var q=RoadQueries.horizontal(cover.mesh,p.at());if(q.horizontalDistance()<q.sample().halfWidth()&&q.sample().center().y()>p.at().y()+.1)continue;}distance=d;best=p;}}return best;}
  private RoadIndex.Built road(double x,double y){V hit=new V(wx(x),0,wz(y));RoadIndex.Built best=null;double height=-Double.MAX_VALUE,distance=Double.MAX_VALUE;for(var b:mapRoads.values()){if(hidden.contains(b.record.id())||!visible(b.mesh))continue;var q=RoadQueries.horizontal(b.mesh,hit);double h=q.sample().center().y();if(q.horizontalDistance()<=Math.max(q.sample().halfWidth(),5/zoom)&&(h>height+.01||Math.abs(h-height)<=.01&&(q.horizontalDistance()<distance||q.horizontalDistance()==distance&&(best==null||b.record.id().compareTo(best.record.id())<0)))){distance=q.horizontalDistance();height=h;best=b;}}return best;}
  private void target(CompoundTag t,Point point,UUID road,V at){t.putDouble("X",at.x());t.putDouble("Y",at.y());t.putDouble("Z",at.z());if(point!=null){t.putString("PointKind",point.kind());if(point.pos()!=null)t.putLong("Pos",point.pos().asLong());if(point.id()!=null){t.putUUID("Point",point.id());t.putString("PointKind",point.kind());}if(point.road()!=null)t.putUUID("Road",point.road());}else if(road!=null)t.putUUID("Road",road);}
  private void send(String action,Point point,UUID id,double mx,double my){var t=command(action);if(point!=null||id!=null||action.equals("manualClick")){V at=point!=null?point.at():terrain(mx,my);if(id!=null&&point==null){var b=mapRoads.get(id);if(b!=null){var q=RoadQueries.horizontal(b.mesh,new V(wx(mx),0,wz(my)));at=new V(wx(mx),q.sample().center().y(),wz(my));}}if(at==null){status="该位置尚未加载，请靠近后重试";return;}target(t,point,id,at);}t.putBoolean("Ctrl",hasControlDown());t.putBoolean("Alt",com.mojang.blaze3d.platform.InputConstants.isKeyDown(minecraft.getWindow().getWindow(),GLFW.GLFW_KEY_LEFT_ALT));ClientRoads.sendAction(t);}
  @Override public boolean mouseClicked(double x,double y,int button){
    if(menuX>=0){if(button==0&&x>=menuX&&x<menuX+122&&y>=menuY&&y<menuY+menuRows()*22){int item=(int)(y-menuY)/22;menuX=-1;if(item==0){if(selected!=null&&!selected.kind().equals("rail"))minecraft.setScreen(new PointDetails(selected));else send("manualDetails",selected,selectedRoad,x,y);}if(item==1)remove();if(item==2&&selectedRoad!=null){hidden.add(selectedRoad);selectedRoad=null;selected=null;status="已暂时隐藏；按 H 恢复全部";}if(item==2&&selectedRoad==null&&selected!=null){moving=true;movingAt=selected.at();axis=0;confirmAt=0;status="拖动 X / Z 箭头；双击左键确认，右键取消";}return true;}menuX=-1;}
    if(moving&&map(x,y)){
      if(button==1){moving=false;confirmAt=0;return true;}if(button!=0)return true;
      double px=sx(movingAt.x()),py=sy(movingAt.z());
      if(Math.abs(y-py)<7&&x>px+8&&x<px+55){axis=1;confirmAt=0;return true;}
      if(Math.abs(x-px)<7&&y>py+8&&y<py+55){axis=2;confirmAt=0;return true;}
      long now=net.minecraft.Util.getMillis();
      if(confirmAt!=0&&now-confirmAt<=300&&Math.hypot(x-confirmX,y-confirmY)<=5){move(movingAt);moving=false;confirmAt=0;}
      else {confirmAt=now;confirmX=x;confirmY=y;}
      return true;
    }
    if(tool==5&&interchangePending&&button==0&&map(x,y)){status="正在同步已选主路，请稍候选择下一条";return true;}
    if(super.mouseClicked(x,y,button))return true;if(!map(x,y))return false;collect();var previousPoint=selected;var previousRoad=selectedRoad;selected=tool==5&&!interchangeTerminal()?null:pick(x,y);var road=selected==null?road(x,y):null;selectedRoad=selected!=null&&selected.kind().equals("rail")?selected.road():road==null?null:road.record.id();openSelected=selected!=null&&previousPoint!=null&&same(selected,previousPoint)||selected==null&&selectedRoad!=null&&selectedRoad.equals(previousRoad);
    if(button==0&&selected!=null&&selected.kind().equals("rail")&&tool!=1){status="铁路端点 · 使用指针再次单击打开编辑";return true;}
    if(button==1){if(selected!=null||selectedRoad!=null){menuX=Math.min((int)x,right()-122);menuY=Math.min((int)y,bottom()-menuRows()*22);}return true;}
    if(button==0){pressed=true;dragged=false;pressX=x;pressY=y;pressCenterX=cx;pressCenterZ=cz;pressAt=net.minecraft.Util.getMillis();return true;}return false;
  }
  @Override public boolean mouseDragged(double x,double y,int button,double dx,double dy){if(moving&&axis!=0){movingAt=new V(axis==1?wx(x):movingAt.x(),movingAt.y(),axis==2?wz(y):movingAt.z());return true;}if(pressed&&button==0&&(dragged||net.minecraft.Util.getMillis()-pressAt>160&&Math.hypot(x-pressX,y-pressY)>3)){dragged=true;cx=pressCenterX-(x-pressX)/zoom;cz=pressCenterZ-(y-pressY)/zoom;return true;}return super.mouseDragged(x,y,button,dx,dy);}
  @Override public boolean mouseReleased(double x,double y,int button){if(moving&&axis!=0){axis=0;return true;}if(pressed&&button==0){pressed=false;if(dragged&&tool==1){selected=null;selectedRoad=null;openSelected=false;}if(dragged){roadClickAt=0;roadClickId=null;}if(!dragged&&map(x,y)){if(tool==5&&!interchangeTerminal()){
      long now=net.minecraft.Util.getMillis();if(selectedRoad!=null&&selectedRoad.equals(roadClickId)&&now-roadClickAt<=300&&Math.hypot(x-roadClickX,y-roadClickY)<=5){interchangePending=true;send("manualClick",null,selectedRoad,x,y);roadClickAt=0;roadClickId=null;}else{roadClickAt=now;roadClickId=selectedRoad;roadClickX=x;roadClickY=y;status="双击此主路以选择一对端点";}
    }else if(tool!=1||openSelected)send("manualClick",selected,selectedRoad,x,y);else status="已选中，再次单击打开设置；右键打开菜单";}return true;}return super.mouseReleased(x,y,button);}
  @Override public boolean mouseScrolled(double x,double y,double delta){if(hasControlDown()&&tool==2){cycleEndpointMode(delta);return true;}if(hasControlDown()&&tool==5){cycleArms(delta);return true;}if(!map(x,y))return false;double beforeX=wx(x),beforeZ=wz(y);zoom=Math.max(minimumZoom(),Math.min(24,zoom*Math.pow(1.2,delta)));cx+=beforeX-wx(x);cz+=beforeZ-wz(y);return true;}
  private String endpointName(){return switch(endpointMode){case 1->"路口点";case 2->"附属点";case 3->"出入点";default->"道路端点";};}
  void cycleEndpointMode(double delta){if(delta==0)return;endpointMode=Math.floorMod(endpointMode+(delta>0?1:-1),4);choose(tool);}
  private double minimumZoom(){return Math.max(.025,Math.min(right()-left(),bottom()-top())/(4096.0*1.1));}
  void cycleArms(double delta){if(delta==0)return;arms=3+Math.floorMod(arms-3+(delta>0?1:-1),4);choose(tool);}
  @Override public boolean keyPressed(int key,int scan,int modifiers){if(key>=GLFW.GLFW_KEY_1&&key<=GLFW.GLFW_KEY_7){choose(key-GLFW.GLFW_KEY_1+1);return true;}if(hasControlDown()&&(key==GLFW.GLFW_KEY_Z||key==GLFW.GLFW_KEY_Y)){ClientRoads.sendAction(command(key==GLFW.GLFW_KEY_Z?"manualUndo":"manualRedo"));return true;}if(key==GLFW.GLFW_KEY_H){hidden.clear();status="已恢复全部道路和铁路路基";return true;}if(key==GLFW.GLFW_KEY_DELETE){remove();return true;}if(ManualPlannerClient.KEY.matches(key,scan)){onClose();return true;}if(Set.of(GLFW.GLFW_KEY_W,GLFW.GLFW_KEY_A,GLFW.GLFW_KEY_S,GLFW.GLFW_KEY_D,GLFW.GLFW_KEY_LEFT_SHIFT).contains(key)){keys.add(key);return true;}return super.keyPressed(key,scan,modifiers);}
  @Override public boolean keyReleased(int key,int scan,int modifiers){keys.remove(key);return super.keyReleased(key,scan,modifiers);}
  private void remove(){if(selected!=null)send("manualDelete",selected,null,sx(selected.at().x()),sy(selected.at().z()));else if(selectedRoad!=null){var b=mapRoads.get(selectedRoad);if(b!=null)send("manualDelete",null,selectedRoad,sx(b.mesh.first().center().x()),sy(b.mesh.first().center().z()));}}
  private void move(V at){var t=command("manualMove");target(t,selected,null,at);ClientRoads.sendAction(t);}
  @Override public void onClose(){ManualPlannerClient.close();}
  @Override public void removed(){keys.clear();pressed=false;}
  @Override public boolean isPauseScreen(){return false;}
  @Override public void render(GuiGraphics g,int mx,int my,float dt){
    g.fill(0,0,width,height,0xff141d27);g.drawString(font,"SR · 手动道路规划",10,13,0xfff2f4fa,false);g.drawString(font,"撤销 "+state.getInt("Undo")+"  /  重做 "+state.getInt("Redo"),left()+8,14,0xff9bacbf,false);
    g.fill(left(),top(),right(),bottom(),0xff252b30);g.enableScissor(left(),top(),right(),bottom());
    if(terrainDirty&&terrain!=null){terrain.upload();terrainDirty=false;}
    if(texture!=null){int x=(int)Math.floor(sx(terrainX)),y=(int)Math.floor(sy(terrainZ)),size=(int)Math.ceil(1024*terrainStep*zoom);g.blit(texture,x,y,size,size,0,0,1024,1024,1024,1024);}
    drawRoads(g);drawPreview(g);collect();
    for(var p:displayedPoints){int x=(int)sx(p.at().x()),y=(int)sy(p.at().z());int color=switch(p.kind()){case "junction"->0xff73f07b;case "rail"->0xffc9a3ff;case "lane"->0xff4db8ff;case "control"->0xffde76ff;case "attached"->0xffff7571;case "elevated"->0xff33ccff;default->0xffffd36b;};g.fill(x-2,y-2,x+3,y+3,color);if(p.kind().equals("rail"))g.renderOutline(x-3,y-3,7,7,0xff7858a3);if(selected!=null&&same(selected,p)){g.renderOutline(x-5,y-5,11,11,0xffffffff);}}
    var selection=state.getCompound("Selection");var selectedNodes=new HashSet<Long>();if(selection.contains("Start"))selectedNodes.add(selection.getLong("Start"));for(long pos:selection.getLongArray("Points"))selectedNodes.add(pos);for(var p:points)if(p.pos()!=null&&selectedNodes.contains(p.pos().asLong())||selection.hasUUID("SidePoint")&&selection.getUUID("SidePoint").equals(p.id()))g.renderOutline((int)sx(p.at().x())-6,(int)sy(p.at().z())-6,13,13,0xff74ffae);
    int px=(int)sx(minecraft.player.getX()),py=(int)sy(minecraft.player.getZ());g.fill(px-3,py-3,px+4,py+4,0xffffffff);g.drawString(font,"你",px+6,py-3,0xffffffff,false);
    if(moving)drawMoveAxes(g,mx,my);
    g.disableScissor();g.drawString(font,font.plainSubstrByWidth(status,width-left()-15),left()+4,height-33,0xffd5e2ee,false);
    V at=map(mx,my)?terrain(mx,my):null;String coords=at==null?"地表加载中 "+loadedTiles+" / "+totalTiles:String.format(Locale.ROOT,"X %.2f  Y %.1f  Z %.2f",at.x(),at.y(),at.z());g.drawString(font,coords,width-font.width(coords)-12,height-17,0xff9fb9cc,false);
    resumePreview.visible=ManualPlannerClient.hasMapPreview();resumePreview.active=resumePreview.visible;
    if(height>330&&!resumePreview.visible)g.drawString(font,"工具 "+tool+(tool==5?" · "+arms+" 向":""),10,height-54,0xff74ffae,false);
    super.render(g,mx,my,dt);
    if(menuX>=0){g.fill(menuX,menuY,menuX+122,menuY+menuRows()*22,0xf5213142);String[] rows=selectedRoad!=null?new String[]{"详细设置","删除","暂时隐藏（H 恢复）","取消"}:new String[]{"详细设置 / XYZ","删除","移动","取消"};for(int i=0;i<rows.length;i++){if(mx>=menuX&&mx<menuX+122&&my>=menuY+i*22&&my<menuY+(i+1)*22)g.fill(menuX+1,menuY+i*22,menuX+121,menuY+(i+1)*22,0xff34536b);g.drawString(font,rows[i],menuX+9,menuY+i*22+7,0xffffffff,false);}}
    if(help){String[] lines={"W A S D 平移；滚轮缩放；长按左键拖动", "主键盘 1–7 切换工具（不含小键盘）", "Delete 删除；Ctrl Z 撤销；Ctrl Y 重做", "道路：A → 经过点/控制点 → B", "Ctrl 经过点在路上；左 Alt 控制点影响弯曲", "同一路段只能使用其中一种点", "端点：Ctrl＋滚轮切换四种点", "立交：Ctrl＋滚轮选择 3–6 向", "右键点：详细设置、删除、XZ 移动", "拖动箭头后双击确认；右键取消移动", "高架：深蓝/深紫；地下：灰/浅灰", "实景预览后按 P 返回设置；Esc 返回地图", "缓存关闭延时0–60秒；离开加载半径立即释放", "操作半径2048格；H 恢复全部隐藏道路"};int x=Math.max(left()+8,width-365),y=38;g.fill(x-8,y-8,width-8,y+lines.length*16+8,0xf5162533);for(int i=0;i<lines.length;i++)g.drawString(font,lines[i],x,y+i*16,0xffe1eaf0,false);}
  }
  private void drawMoveAxes(GuiGraphics g,int mx,int my){
    int x=(int)sx(movingAt.x()),y=(int)sy(movingAt.z());
    int red=axis==1||Math.abs(my-y)<7&&mx>x+8&&mx<x+55?0xffffb0a7:0xffef796f;
    int blue=axis==2||Math.abs(mx-x)<7&&my>y+8&&my<y+55?0xffc0edff:0xff75ccef;
    g.fill(x+6,y-2,x+46,y+3,0xb0162533);g.fill(x-2,y+6,x+3,y+46,0xb0162533);
    g.fill(x+6,y,x+46,y+1,red);g.fill(x,y+6,x+1,y+46,blue);
    for(int i=0;i<6;i++){g.fill(x+46+i,y-5+i,x+47+i,y+6-i,red);g.fill(x-5+i,y+46+i,x+6-i,y+47+i,blue);}
    g.fill(x-5,y-5,x+6,y+6,0xff203345);g.renderOutline(x-5,y-5,11,11,0xffdcebf7);g.fill(x-1,y-1,x+2,y+2,0xffdcebf7);
    g.fill(x+55,y-7,x+68,y+7,0xe51a2a38);g.drawString(font,"X",x+59,y-3,red,false);
    g.fill(x-7,y+55,x+7,y+69,0xe51a2a38);g.drawString(font,"Z",x-3,y+59,blue,false);
  }
  private static boolean same(Point a,Point b){return Objects.equals(a.kind(),b.kind())&&Objects.equals(a.id(),b.id())&&Objects.equals(a.road(),b.road())&&Objects.equals(a.pos(),b.pos());}
  private int menuRows(){return 4;}
  static int roadColor(RoadIndex.Built b,Sample at){boolean rail=b.record.railway();if(RoadStreetscape.underground(b.mesh,at.distance()))return rail?0xffe3e3eb:0xff8b9097;if(RoadStreetscape.raised(b.mesh,at))return rail?0xff51316f:0xff234e86;return rail?0xff9075ad:b.record.toll()!=null?0xffc99c68:b.record.junction()!=null?0xff8aadc1:0xff627c91;}
  private record Strip(RoadIndex.Built road,Sample a,Sample b){double height(){return (a.center().y()+b.center().y())/2;}}
  private void drawRoads(GuiGraphics g){
    collect();var strips=preparedStrips;
    g.flush();RenderSystem.disableCull();RenderSystem.enableBlend();RenderSystem.defaultBlendFunc();RenderSystem.setShader(GameRenderer::getPositionColorShader);var buffer=Tesselator.getInstance().getBuilder();buffer.begin(VertexFormat.Mode.QUADS,DefaultVertexFormat.POSITION_COLOR);var pose=g.pose().last().pose();
    for(var strip:strips){var b=strip.road();var a=strip.a();var c=strip.b();int color=b.record.id().equals(selectedRoad)?0xffffd76b:roadColor(b,a);V[] quad={a.at(a.halfWidth(),0),a.at(-a.halfWidth(),0),c.at(-c.halfWidth(),0),c.at(c.halfWidth(),0)};for(V p:quad)buffer.vertex(pose,(float)sx(p.x()),(float)sy(p.z()),0).color((color>>16)&255,(color>>8)&255,color&255,255).endVertex();}BufferUploader.drawWithShader(buffer.end());RenderSystem.disableBlend();RenderSystem.enableCull();
  }
  private List<Mesh> previewMeshes(){
    if(!ManualPlannerClient.hasMapPreview()||ClientRoads.conflictPreview)return List.of();
    var meshes=new ArrayList<Mesh>(ClientRoads.nodePreviews);if(ClientRoads.preview!=null&&!meshes.contains(ClientRoads.preview))meshes.add(ClientRoads.preview);return meshes;
  }
  private void drawPreview(GuiGraphics g){
    var meshes=previewMeshes();if(meshes.isEmpty())return;
    g.flush();RenderSystem.disableCull();RenderSystem.enableBlend();RenderSystem.defaultBlendFunc();RenderSystem.setShader(GameRenderer::getPositionColorShader);
    var buffer=Tesselator.getInstance().getBuilder();buffer.begin(VertexFormat.Mode.QUADS,DefaultVertexFormat.POSITION_COLOR);var pose=g.pose().last().pose();
    for(var mesh:meshes){if(!visible(mesh))continue;var samples=mesh.samples();for(int i=1;i<samples.size();i++){var a=samples.get(i-1);var b=samples.get(i);for(V p:List.of(a.at(a.halfWidth(),0),a.at(-a.halfWidth(),0),b.at(-b.halfWidth(),0),b.at(b.halfWidth(),0)))buffer.vertex(pose,(float)sx(p.x()),(float)sy(p.z()),0).color(67,221,136,235).endVertex();}}
    BufferUploader.drawWithShader(buffer.end());RenderSystem.disableBlend();RenderSystem.enableCull();g.drawString(font,"绿色：待建预览",left()+8,top()+8,0xff74ffae,false);
  }
  final class PlannerSettings extends Screen {
    private int radius=state.contains("Radius")?state.getInt("Radius"):256,steps=state.contains("MaxUndo")?state.getInt("MaxUndo"):30,cacheSeconds=ManualPlannerScreen.this.cacheSeconds();
    PlannerSettings(){super(Component.literal("道路规划设置"));}
    @Override protected void init(){int x=width/2-140,y=height/2-76;
      addRenderableWidget(new SettingSlider(x,y,0,radius,16,2048));
      addRenderableWidget(new SettingSlider(x,y+28,1,steps,1,30));
      addRenderableWidget(new SettingSlider(x,y+56,2,cacheSeconds,0,60));
      addRenderableWidget(Button.builder(Component.literal("保存"),b->{var t=command("manualSettings");t.putInt("Radius",radius);t.putInt("MaxUndo",steps);t.putInt("CacheSeconds",cacheSeconds);ClientRoads.sendAction(t);onClose();}).bounds(x,y+108,133,20).build());
      addRenderableWidget(Button.builder(Component.literal("返回"),b->onClose()).bounds(x+147,y+108,133,20).build());ManualPlannerClient.child(this);ClientRoads.beginEditor(this);}
    @Override public void onClose(){minecraft.setScreen(ManualPlannerScreen.this);ClientRoads.beginEditor(ManualPlannerScreen.this);}
    @Override public boolean isPauseScreen(){return false;}
    @Override public void render(GuiGraphics g,int x,int y,float dt){renderBackground(g);g.drawCenteredString(font,title,width/2,height/2-102,0xffffffff);super.render(g,x,y,dt);for(var child:children())if(child instanceof SettingSlider slider&&slider.isMouseOver(x,y)){g.renderTooltip(font,font.split(Component.literal(slider.hint()),Math.max(1,Math.min(300,width-16))),(sw,sh,mx,my,tw,th)->new org.joml.Vector2i(Math.max(4,Math.min(mx+12,sw-tw-4)),Math.max(4,Math.min(my+12,sh-th-6))),x,y);break;}}
    final class SettingSlider extends AbstractSliderButton {
      private final int setting,min,max;
      SettingSlider(int x,int y,int setting,int current,int min,int max){super(x,y,280,22,Component.empty(),(current-min)/(double)(max-min));this.setting=setting;this.min=min;this.max=max;updateMessage();}
      String hint(){return switch(setting){case 0->"以打开地图的位置为中心加载地表；离开此范围会释放缓存";case 1->"最多保留的规划撤销步骤数";default->"关闭后按时释放；离开打开位置超过加载半径即释放";};}
      protected void updateMessage(){setMessage(Component.literal(switch(setting){case 0->"地表加载半径："+radius+" 格";case 1->"撤销最大步："+steps;default->"关闭后释放地表缓存："+cacheSeconds+" 秒";}));}
      protected void applyValue(){int n=min+(int)Math.round(value*(max-min));switch(setting){case 0->radius=n;case 1->steps=n;default->cacheSeconds=n;}updateMessage();}
    }
  }
  final class PointDetails extends Screen {
    private final Point point;private final EditBox[] xyz=new EditBox[3];private String message="坐标修改与原有道路校验同时执行";
    void result(boolean success,String text){message=text;if(success){onClose();status=text;}}
    PointDetails(Point p){super(Component.literal("点的详细设置"));point=p;}
    @Override protected void init(){int x=width/2-140,y=height/2-70;double[] values={point.at().x(),point.at().y(),point.at().z()};for(int i=0;i<3;i++){xyz[i]=new EditBox(font,x+30,y+i*27,235,20,Component.literal("XYZ".substring(i,i+1)));xyz[i].setMaxLength(24);xyz[i].setValue(String.format(Locale.ROOT,"%.3f",values[i]));addRenderableWidget(xyz[i]);}addRenderableWidget(Button.builder(Component.literal("保存坐标"),b->{try{var at=new V(Double.parseDouble(xyz[0].getValue()),Double.parseDouble(xyz[1].getValue()),Double.parseDouble(xyz[2].getValue()));selected=point;var t=command("manualMove");target(t,point,null,at);ClientRoads.sendAction(t);}catch(IllegalArgumentException e){message="请输入有效坐标";}}).bounds(x,y+89,87,20).build());addRenderableWidget(Button.builder(Component.literal("原有设置"),b->{minecraft.setScreen(ManualPlannerScreen.this);send("manualDetails",point,null,sx(point.at().x()),sy(point.at().z()));}).bounds(x+94,y+89,87,20).build());addRenderableWidget(Button.builder(Component.literal("返回地图"),b->onClose()).bounds(x+188,y+89,87,20).build());ManualPlannerClient.child(this);ClientRoads.beginEditor(this);}
    @Override public void onClose(){minecraft.setScreen(ManualPlannerScreen.this);ClientRoads.beginEditor(ManualPlannerScreen.this);}
    @Override public boolean isPauseScreen(){return false;}
    @Override public void render(GuiGraphics g,int x,int y,float dt){renderBackground(g);int left=width/2-140,top=height/2-70;g.drawString(font,title,left,top-22,0xffffffff,false);for(int i=0;i<3;i++)g.drawString(font,"XYZ".substring(i,i+1),left,top+i*27+6,0xffffffff,false);g.drawString(font,font.plainSubstrByWidth(message,290),left,top+117,0xffb8ccd9,false);super.render(g,x,y,dt);}
  }
}
