package com.sora.splineroads.core;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.world.*;
import java.util.*;
import java.nio.file.*;
import net.minecraft.nbt.*;

public final class FeedbackFix5Core {
 static void check(boolean ok,String text){if(!ok)throw new AssertionError(text);}
 static double[] metrics(Mesh mesh){
  double maxGrade=0,maxJump=0,maxRate=0,previous=0,previousDs=0;int jumps=0;
  for(int i=1;i<mesh.samples().size();i++){
   var a=mesh.samples().get(i-1).center();var b=mesh.samples().get(i).center();double ds=b.sub(a).horizontalLength(),grade=(b.y()-a.y())/ds;
   maxGrade=Math.max(maxGrade,Math.abs(grade));
   if(i>1){double jump=Math.abs(grade-previous);maxJump=Math.max(maxJump,jump);maxRate=Math.max(maxRate,jump/((ds+previousDs)/2));if(jump>.01)jumps++;}
   previous=grade;previousDs=ds;
  }return new double[]{maxGrade,maxJump,maxRate,jumps};
 }
 static void inspect(String name,InterchangePlanner.Plan plan,Path out)throws Exception{
  var csv=new StringBuilder("name,station,x,y,z\n");double maxJump=0,maxRate=0,grade=0;int count=0;
  for(var leg:plan.legs())if(leg.mesh().settings().style().ramp()){
   count++;var m=metrics(leg.mesh());grade=Math.max(grade,m[0]);maxJump=Math.max(maxJump,m[1]);maxRate=Math.max(maxRate,m[2]);
   System.out.println("FIX5_ROUTE "+name+" "+leg.name()+" length="+leg.mesh().length()+" metrics="+Arrays.toString(m));
   double d=0;V last=null;for(var s:leg.mesh().samples()){if(last!=null)d+=s.center().sub(last).horizontalLength();last=s.center();csv.append(leg.name()).append(',').append(d).append(',').append(last.x()).append(',').append(last.y()).append(',').append(last.z()).append('\n');}
  }
  Files.writeString(out.resolve(name+".csv"),csv);System.out.println("FIX5_PROFILE "+name+" ramps="+count+" grade="+grade+" jump="+maxJump+" rate="+maxRate+" highest="+plan.highest());
  check(count>0,"no ramps: "+name);check(grade<=.150001,"grade: "+name);
  check(maxJump<.01&&maxRate<.02,"abrupt vertical grade change: "+name);
 }
 static void mixed(InterchangePlanner.Plan plan){
  var mains=plan.legs().stream().filter(l->!l.mesh().settings().style().ramp()).toList();
  var upper=mains.stream().max(Comparator.comparingDouble(l->l.mesh().first().center().y())).orElseThrow().mesh();
  int below=0,above=0;
  for(var leg:plan.legs())if(leg.mesh().settings().style().ramp())for(var c:RoadClearance.contacts(leg.mesh(),upper)){
   if(c.usableClearance()<5-.025)continue;
   if(c.ours().y()<c.other().y())below++;else above++;
  }
  check(below>0&&above>0,"allowAbove must retain feasible underpasses as well as flyovers");
  System.out.println("FIX5_MIXED_PASS underpassContacts="+below+" flyoverContacts="+above);
 }
 static void envelope(){
  int n=401;double[] preferred=new double[n*2],lower=new double[n*2],upper=new double[n*2];
  Arrays.fill(lower,Double.NEGATIVE_INFINITY);Arrays.fill(upper,Double.POSITIVE_INFINITY);
  double[][] d={new double[n],new double[n]};var edges=new ArrayList<InterchangeGradeProfile.Edge>();
  for(int r=0;r<2;r++)for(int i=0;i<n;i++){int k=r*n+i;d[r][i]=i;preferred[k]=r==0?0:6;
   if(i<10||i>n-11){lower[k]=preferred[k];upper[k]=preferred[k];}
   if(i>0){edges.add(new InterchangeGradeProfile.Edge(k-1,k,-.15));edges.add(new InterchangeGradeProfile.Edge(k,k-1,-.15));}}
  lower[200]=8;edges.add(new InterchangeGradeProfile.Edge(200,n+200,6));
  var y=InterchangeGradeProfile.smooth(preferred,lower,upper,edges,new int[]{0,n},d);
  check(y[200]>=8-1e-6&&y[n+200]-y[200]>=6-1e-6,"smoothed crossing envelope");
  double jump=0;for(int r=0;r<2;r++)for(int i=2;i<n;i++){int k=r*n+i;jump=Math.max(jump,Math.abs(y[k]-2*y[k-1]+y[k-2]));}
  check(jump<.01,"vertical curve derivative discontinuity");
  lower[1]=30;boolean rejected=false;try{InterchangeGradeProfile.smooth(preferred,lower,upper,edges,new int[]{0,n},d);}catch(IllegalArgumentException expected){rejected=true;}
  check(rejected,"infeasible grade must remain rejected");System.out.println("FIX5_ENVELOPE_PASS jump="+jump);
 }
 public static void main(String[] args)throws Exception{
  envelope();int passed=0;var root=NbtIo.readCompressed(Path.of(args[0]).toFile()).getCompound("data");var out=Path.of("F:/SR-feedback-2.1.2-fix5",args[1]);Files.createDirectories(out);
  for(var entry:root.getList("Interchanges",Tag.TAG_COMPOUND)){
   var saved=(CompoundTag)entry;String preset=saved.getCompound("Options").getString("Preset");
   System.out.println("FIX5_GROUP "+saved.getUUID("Id")+" "+saved.getCompound("Options")+" nodes="+saved.get("Nodes"));
   if(preset.equals("TRUMPET")){inspect(preset+"-feedback",Interchanges.plan(saved.copy()),out);passed++;continue;}
   if(!preset.equals("TURBINE")&&!preset.equals("STACK"))continue;
   for(String key:List.of("Main1","Main2")){var s=RoadRecord.readSettings(saved.getCompound(key));System.out.println("FIX5_MAIN "+key+" "+s.style()+" width="+s.width());}
   for(boolean above: preset.equals("TURBINE")?new boolean[]{true,false}:new boolean[]{false}){
    var t=saved.copy();t.getCompound("Options").putBoolean("AllowAbove",above);String name=preset+"-"+above+"-"+saved.getUUID("Id");long start=System.nanoTime();
    try{var plan=Interchanges.plan(t);inspect(name,plan,out);if(above)mixed(plan);passed++;System.out.println("FIX5_PLAN_PASS "+name+" ms="+(System.nanoTime()-start)/1e6);}catch(IllegalArgumentException e){check(!above&&e.getMessage().contains("最低"),"feedback world failed: "+e.getMessage());System.out.println("FIX5_EXPECTED_REJECT "+name+" "+e.getMessage());}
   }
   if(!args[1].equals("actual"))for(var presetCase:List.of(InterchangePlanner.Preset.STACK,InterchangePlanner.Preset.TURBINE))for(boolean left:new boolean[]{false,true})for(boolean above:new boolean[]{false,true}){
    var a=new Settings(Mode.STRAIGHT,Style.H4_RAIL,Style.H4_RAIL.defaultWidth(),1,.4,90);
    var options=new InterchangePlanner.Options(presetCase,left,1,64,32,5,1).above(above);
    double gap=InterchangePlanner.minimumDifference(a,a,options.above(false));
    var nodes=new Node[]{new Node(new V(-500,56+gap,0),90,0),new Node(new V(500,56+gap,0),90,0),new Node(new V(0,56,-500),0,0),new Node(new V(0,56,500),0,0)};
    var plan=InterchangePlanner.plan(nodes,a,a,options);inspect(presetCase+"-fixture-left="+left+"-above="+above,plan,out);passed++;
    if(!above)check(plan.highest()<=56+gap+1e-5,"disabled flyover ceiling");
   }
  }
  for(var preset:List.of(InterchangePlanner.Preset.CLOVERLEAF,InterchangePlanner.Preset.HYBRID)){
   var a=new Settings(Mode.STRAIGHT,Style.H4_RAIL,Style.H4_RAIL.defaultWidth(),1,.4,90);
   var options=new InterchangePlanner.Options(preset,false,1,64,32,5,1);
   double gap=InterchangePlanner.minimumDifference(a,a,options);
   var nodes=new Node[]{new Node(new V(-500,56+gap,0),90,0),new Node(new V(500,56+gap,0),90,0),new Node(new V(0,56,-500),0,0),new Node(new V(0,56,500),0,0)};
   inspect(preset+"-regression",InterchangePlanner.plan(nodes,a,a,options),out);passed++;
  }
  check(passed>0,"no feedback plan tested");System.out.println("FIX5_CORE_PASS plans="+passed);
 }
}
