package com.sora.splineroads.client;
import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.world.RoadIndex;
import java.util.*;

/** Keep both exact backends. When every input mesh is literally shared, build it once. */
final class RoadMeshSurfaces {
 record Pair(RoadSurface.Geometry surface,RoadSurface.Geometry exact,boolean reused){}
 static Pair prepare(RoadIndex.Built road,List<RoadIndex.Built> joined,List<RoadIndex.Built> higher){
  return prepare(road,joined,higher,true);
 }
 static Pair prepare(RoadIndex.Built road,List<RoadIndex.Built> joined,List<RoadIndex.Built> higher,boolean needTerrain){
  var reduced=road.renderMesh();var joinedRender=joined.stream().map(RoadIndex.Built::renderMesh).toList();var highRender=higher.stream().map(RoadIndex.Built::renderMesh).toList();var joinedExact=joined.stream().map(r->r.mesh).toList();
  var surface=build(road,reduced,joinedRender,highRender,joinedExact);
  boolean same=reduced==road.mesh&&joined.stream().allMatch(r->r.renderMesh()==r.mesh)&&higher.stream().allMatch(r->r.renderMesh()==r.mesh);
  var exact=!needTerrain?null:same?surface:build(road,road.mesh,joinedExact,higher.stream().map(r->r.mesh).toList(),joinedExact);
  return new Pair(surface,walks(exact,road.record.structures()),same);
 }
 static RoadSurface.Geometry build(RoadIndex.Built road,Mesh mesh,List<Mesh> joined,List<Mesh> higher,List<Mesh> exactJoined){
  RoadSurface.Geometry surface;
  if(road.record.junction()!=null)surface=RoadSurface.buildCustom(mesh,higher,joined,road.record.junction().get().paint());
  else if(road.record.toll()!=null)surface=RoadSurface.buildCustom(mesh,higher,joined,road.record.tollPlan().paint());
  else surface=RoadSurface.build(mesh,higher,joined,new RoadsideBypass(road.mesh,exactJoined));
  var decks=new ArrayList<Mesh>();decks.add(mesh);decks.addAll(joined);
  surface=RoadSurface.closurePavement(surface,road.record.structures(),decks);
  return road.record.junction()!=null&&road.record.junction().geometryVersion()>=48&&JunctionSlips.enabled(road.record.junction().spec())?JunctionSlips.exposedSurface(surface,road.record.structures()):surface;
 }
 record Window(double from,double to,V min,V max){
  boolean includes(double station){return RoadSurface.inWindow(station,from,to);}
  double distance(V eye){double x=Math.max(min.x(),Math.min(max.x(),eye.x()))-eye.x(),z=Math.max(min.z(),Math.min(max.z(),eye.z()))-eye.z();return x*x+z*z;}
 }
 static List<Window> windows(RoadIndex.Built road){
  var mesh=road.mesh;if(road.record.junction()!=null||road.record.toll()!=null)return List.of(new Window(0,mesh.length()+1,mesh.min(),mesh.max()));
  return windows(mesh);
 }
 static List<Window> windows(Mesh mesh){
  if(mesh.length()<=128)return List.of(new Window(0,mesh.length()+1,mesh.min(),mesh.max()));
  var windows=new ArrayList<Window>();var source=mesh.samples();int cursor=0;
  for(double from=0;from<mesh.length();from+=64){double to=Math.min(mesh.length(),from+64);double x0=Double.POSITIVE_INFINITY,z0=x0,x1=-x0,z1=-x0;
   var samples=new ArrayList<Sample>();samples.add(RoadStructures.sample(mesh,from));samples.add(RoadStructures.sample(mesh,to));
   // Stations are ordered. Each interior sample belongs to one window; keep
   // the exact interpolated endpoints as well, including a short final window.
   while(cursor<source.size()&&source.get(cursor).distance()<=from)cursor++;
   while(cursor<source.size()&&source.get(cursor).distance()<to)samples.add(source.get(cursor++));
   for(var p:samples){double margin=p.halfWidth()+32;x0=Math.min(x0,p.center().x()-margin);z0=Math.min(z0,p.center().z()-margin);x1=Math.max(x1,p.center().x()+margin);z1=Math.max(z1,p.center().z()+margin);}
   windows.add(new Window(from,to==mesh.length()?to+1:to,new V(x0,mesh.min().y(),z0),new V(x1,mesh.max().y(),z1)));
  }return List.copyOf(windows);
 }
 static List<RoadStructures.Part> parts(RoadIndex.Built road,Window window){
  if(window.from()==0&&window.to()>road.mesh.length())return RoadSignVisibility.parts(road.mesh,road.record.structures());
  return RoadSignVisibility.parts(road.mesh,road.record.structures()).stream().filter(p->window.includes(RoadQueries.horizontal(road.mesh,p.a().add(p.b()).mul(.5)).sample().distance())).toList();
 }
 /** Compact lightweight parts once; allocate faces only for the requested window. */
 static final class Structures {
  final RoadRenderMesh.StructureContext nearContext,farContext;final List<RoadStructures.Part> near,far;final Map<Window,List<RoadStructures.Part>> nearWindows,farWindows;
  Structures(RoadIndex.Built road,List<Window> windows){var visible=RoadSignVisibility.parts(road.mesh,road.record.structures());near=RoadRenderMesh.compact(visible,false);far=RoadRenderMesh.compact(visible,true);nearContext=new RoadRenderMesh.StructureContext(near);farContext=new RoadRenderMesh.StructureContext(far);nearWindows=partition(road,windows,near);farWindows=partition(road,windows,far);}
  private static Map<Window,List<RoadStructures.Part>> partition(RoadIndex.Built road,List<Window> windows,List<RoadStructures.Part> parts){
   var result=new HashMap<Window,List<RoadStructures.Part>>();for(var p:parts){RoadPlanningBudget.check();double station=RoadQueries.horizontal(road.mesh,p.a().add(p.b()).mul(.5)).sample().distance();for(var w:windows)if(w.includes(station)){result.computeIfAbsent(w,k->new ArrayList<>()).add(p);break;}}return result;
  }
  Map<RoadRenderMesh.Section,RoadRenderMesh.Layers> layers(RoadSurface.Geometry surface,Window w){return RoadRenderMesh.layersFromFaces(surface,RoadRenderMesh.structureFacesCompacted(nearWindows.getOrDefault(w,List.of()),nearContext,false),RoadRenderMesh.structureFacesCompacted(farWindows.getOrDefault(w,List.of()),farContext,true));}
  long bytes(){return (near.size()+far.size())*320L+nearContext.bytes()+farContext.bytes();}
 }
 static final class Context {
  final RoadIndex.Built road;final List<RoadIndex.Built> joined,higher;final List<Mesh> exactJoined;final Map<Window,List<RoadStructures.Part>> parts;
  RoadSurface.WindowContext surface,exact;
  Context(RoadIndex.Built road,List<RoadIndex.Built> joined,List<RoadIndex.Built> higher,List<Window> windows){this.road=road;this.joined=List.copyOf(joined);this.higher=List.copyOf(higher);exactJoined=joined.stream().map(r->r.mesh).toList();parts=Structures.partition(road,windows,road.record.structures());}
  Pair prepare(Window w,boolean surfaceWanted,boolean terrainWanted){
   var reduced=road.renderMesh();boolean same=reduced==road.mesh&&joined.stream().allMatch(r->r.renderMesh()==r.mesh)&&higher.stream().allMatch(r->r.renderMesh()==r.mesh);
   if(w.from()==0&&w.to()>road.mesh.length())return prepareWhole(surfaceWanted,terrainWanted,same);
   RoadSurface.Geometry a=null,b=null;
   if(surfaceWanted){if(surface==null)surface=new RoadSurface.WindowContext(reduced,higher.stream().map(RoadIndex.Built::renderMesh).toList(),joined.stream().map(RoadIndex.Built::renderMesh).toList(),new RoadsideBypass(road.mesh,exactJoined));a=finish(surface.build(w.from(),w.to()),reduced,joined.stream().map(RoadIndex.Built::renderMesh).toList(),w);}
   if(terrainWanted){if(same&&a!=null)b=a;else{if(exact==null)exact=same&&surface!=null?surface:new RoadSurface.WindowContext(road.mesh,higher.stream().map(r->r.mesh).toList(),exactJoined,new RoadsideBypass(road.mesh,exactJoined));b=finish(exact.build(w.from(),w.to()),road.mesh,exactJoined,w);}}
   return new Pair(a,walks(b,parts.getOrDefault(w,List.of())),same&&a!=null&&b!=null);
  }
  private Pair prepareWhole(boolean surfaceWanted,boolean terrainWanted,boolean same){
   var a=surfaceWanted?build(road,road.renderMesh(),joined.stream().map(RoadIndex.Built::renderMesh).toList(),higher.stream().map(RoadIndex.Built::renderMesh).toList(),exactJoined):null;
   var b=!terrainWanted?null:same&&a!=null?a:build(road,road.mesh,exactJoined,higher.stream().map(r->r.mesh).toList(),exactJoined);return new Pair(a,walks(b,road.record.structures()),a!=null&&a==b);
  }
  private RoadSurface.Geometry finish(RoadSurface.Geometry geometry,Mesh mesh,List<Mesh> joined,Window w){var decks=new ArrayList<Mesh>();decks.add(mesh);decks.addAll(joined);return RoadSurface.closurePavement(geometry,parts.getOrDefault(w,List.of()),decks);}
  long bytes(){return road.record.structures().size()*8L+(surface==null?0:surface.bytes())+(exact==null||exact==surface?0:exact.bytes());}
 }
 static Pair prepareWindow(RoadIndex.Built road,List<RoadIndex.Built> joined,List<RoadIndex.Built> higher,Window window,boolean surfaceWanted,boolean terrainWanted){
  return new Context(road,joined,higher,List.of(window)).prepare(window,surfaceWanted,terrainWanted);
 }
 static RoadSurface.Geometry walks(RoadSurface.Geometry geometry,List<RoadStructures.Part> parts){
  if(geometry==null)return null;var tops=new ArrayList<RoadSurface.Face>();
  for(var p:parts)if(p.material().name().startsWith("WALK_")||p.material()==RoadStructures.Material.TACTILE){var f=p.faces().get(0);tops.add(p.material()==RoadStructures.Material.TACTILE?new RoadSurface.Face(f.points(),f.color(),f.emissive(),RoadSurface.Texture.WALK_TACTILE):f);}
  if(tops.isEmpty())return geometry;var pavement=new ArrayList<>(geometry.pavement());pavement.addAll(tops);return new RoadSurface.Geometry(List.copyOf(pavement),geometry.markings());
 }
 private RoadMeshSurfaces(){}
}
