package com.sora.splineroads.world;
import java.util.*;import java.lang.reflect.*;import net.minecraft.core.BlockPos;import com.sora.splineroads.core.*;import com.sora.splineroads.core.RoadGeometry.*;
public class AccessTerminal203Validation {
 static int checks;static Method method;static void test(RoadRecord a,RoadRecord b,boolean expected,String label)throws Exception{checks++;if((boolean)method.invoke(null,a,b)!=expected)throw new AssertionError(label);}
 static RoadRecord with(RoadRecord r,RoadTransitions.Ends e){return r.settings(r.settings().options(r.settings().options().ends(e)));}
 static RoadTransitions.Ends end(RoadTransitions.Ends e,RoadTransitions.Section s){return new RoadTransitions.Ends(e.start(),s,e.persistent(),e.trimmedStart(),e.trimmedEnd(),e.port(),e.paintPhase(),e.startHold(),e.endHold());}
 public static void main(String[]args)throws Exception{
  method=ElevatedAccessRoads.class.getDeclaredMethod("sameGeometry",RoadRecord.class,RoadRecord.class);method.setAccessible(true);
  var s=RoadLanes.configure(new Settings(Mode.CURVE,Style.O6_GREEN,28,1,.35,90),RoadProfile.Type.ORDINARY,new RoadLanes.Counts(3,3),4);var l=RoadProfile.layout(s,s.width());
  var p=new RoadTransitions.Port(l.motorMin(),l.motorMax(),l.median(),l.dividers().stream().sorted().toList(),0,0,l.medianCenter());s=s.options(s.options().ends(RoadTransitions.Ends.NONE.port(p)));
  var r=new RoadRecord(UUID.randomUUID(),UUID.randomUUID(),new BlockPos(0,5,0),new BlockPos(0,5,300),new Node(new V(.5,5,.5),0,0),new Node(new V(.5,5,300.5),0,0),s,false,4);var e=s.options().ends();var clean=s.options(s.options().ends(RoadTransitions.Ends.NONE).sidewalk(new RoadSidewalks.Config(false,RoadSidewalks.Side.BOTH,5,"minecraft:stone_bricks",true,false)));var section=RoadTransitions.Section.of(clean);
  test(r,with(r,end(e,section)),true,"implicit section to equivalent explicit section");test(with(r,end(e,section)),r,true,"equivalent explicit section to implicit");
  var bad=p.motorMin()+.01;var shifted=new RoadTransitions.Port(bad,p.motorMax()+.01,p.median(),p.dividers().stream().map(v->v+.01).toList(),0,0,p.medianCenter()+.01);
  test(r,with(r,end(e,section.port(shifted))),false,"shifted port");test(r,with(r,e.hold(0,3)),false,"changed hold");test(r,with(r,e.trim(0,3)),false,"changed trim");
  test(r,with(r,end(e,RoadTransitions.Section.of(clean.options(clean.options().sidewalk(clean.options().sidewalk().enabled(true)))))),false,"enabled sidewalk");
  test(r,with(r,end(e,RoadTransitions.Section.of(clean.options(clean.options().extras(false,false,true))))),false,"enabled curb");
  test(r,with(r,end(e,RoadTransitions.Section.of(RoadLanes.configure(clean,RoadProfile.Type.ORDINARY,new RoadLanes.Counts(2,4),4)))),false,"changed lane direction split");
  var narrower=new Settings(Mode.CURVE,clean.style(),27,1,.35,90).options(clean.options());test(r,with(r,end(e,RoadTransitions.Section.of(narrower))),false,"narrower section");
  test(r,with(r,e.trim(0,1e-12)),true,"arithmetic dust in trim");test(r,with(r,e.trim(0,1e-6)),false,"real tiny trim");var moved=new RoadRecord(r.id(),r.owner(),r.a(),r.b(),r.start(),new Node(r.end().position().add(new V(0,.000001,0)),r.end().yaw(),0),r.settings(),false,4);test(r,moved,false,"real tiny terminal move");test(r,r,true,"unchanged record");System.out.println("AccessTerminal203Validation PASS checks="+checks);
 }
}


