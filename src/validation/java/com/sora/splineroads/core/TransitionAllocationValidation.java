package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.RoadProfile.*;
import com.sora.splineroads.core.RoadTransitions.*;
import java.util.*;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

public final class TransitionAllocationValidation {
  private static int checks;
  private static void require(boolean ok){checks++;if(!ok)throw new AssertionError("Section equality check "+checks);}
  private static String outcome(Runnable call){try{call.run();return "ok";}catch(RuntimeException e){return e.getClass().getName()+":"+e.getMessage();}}
  private static void equality(){
    var random=new Random(2111717);var styles=Style.values();var rails=OuterRail.values();
    for(int i=0;i<30000;i++){
      var style=styles[random.nextInt(styles.length)];double width=2+random.nextDouble()*(RoadLimits.maximumWidth(style)-2);
      var options=Options.DEFAULT.traffic(random.nextBoolean()).extras(random.nextBoolean(),random.nextBoolean(),random.nextBoolean()).outerRail(rails[random.nextInt(rails.length)]);
      var settings=new Settings(Mode.STRAIGHT,style,width,1,.4,90).options(options);
      var original=Section.of(settings);
      require(original.matches(settings)==original.equals(Section.of(settings)));
      for(int field=0;field<7;field++){
        var changed=new Section(field==0?styles[(style.ordinal()+1)%styles.length]:original.style(),
            field==0?Math.min(original.width(),RoadLimits.maximumWidth(styles[(style.ordinal()+1)%styles.length])):field==1?2:original.width(),field==2?!original.cycle():original.cycle(),field==3?!original.cycleRail():original.cycleRail(),
            field==4?!original.curb():original.curb(),field==5?rails[(original.outerRail().ordinal()+1)%rails.length]:original.outerRail(),
            original.sidewalk(),field==6?!original.cycleAsphalt():original.cycleAsphalt(),original.port(),original.streetscape(),original.lanes());
        require(changed.matches(settings)==changed.equals(Section.of(settings)));
      }
    }
    var valid=new Settings(Mode.STRAIGHT,Style.O4_RAIL,18,1,.4,90);var target=Section.of(valid);
    for(double width:new double[]{Double.NaN,Double.POSITIVE_INFINITY,-1,0,1.99,1024}){
      var invalid=new Settings(Mode.STRAIGHT,Style.O4_RAIL,width,1,.4,90);
      require(outcome(()->target.matches(invalid)).equals(outcome(()->target.equals(Section.of(invalid)))));
    }
    System.out.println("Transition equality PASS checks="+checks);
  }
  public static void main(String[] args)throws Exception{
    if(args.length>0&&args[0].equals("equality")){equality();return;}
    var digest=MessageDigest.getInstance("SHA-256");int scenarios=0,samples=0;
    for(var style:Style.values())for(boolean left:new boolean[]{false,true})for(int variant=0;variant<4;variant++){
      var options=Options.DEFAULT.traffic(left).extras(variant%2==0,variant%3==0,true);
      double width=RoadProfile.width(style,options,3.5);
      var base=new Settings(Mode.STRAIGHT,style,width,1,.4,90).options(options);
      var own=Section.of(base);var other=Section.of(new Settings(Mode.STRAIGHT,style,Math.min(width+2,RoadLimits.maximumWidth(style)),1,.4,90).options(base.options()));
      var ends=new Ends(variant==0?null:own,variant<2?null:other,true).trim(variant,variant*2).hold(variant*2,variant*3);
      var s=base.options(base.options().ends(ends));
      var mesh=RoadGeometry.build(new Node(new V(-50,64,7),90,0),new Node(new V(220,71,21),90,0),s);
      digest.update((style+":"+left+":"+variant+":"+RoadTransitions.span(s,mesh.length())).getBytes(StandardCharsets.UTF_8));
      for(var sample:mesh.samples()){
        String value=sample.toString()+RoadTransitions.layout(mesh,sample)+RoadTransitions.width(s,sample.distance(),mesh.length())
            +RoadTransitions.green(mesh,sample)+RoadTransitions.greenCycle(mesh,sample)+RoadTransitions.railWidth(mesh,sample)
            +RoadTransitions.railSeparation(mesh,sample,1)+RoadTransitions.dropBoundary(mesh,sample);
        digest.update(value.getBytes(StandardCharsets.UTF_8));samples++;
      }
      scenarios++;
    }
    if(scenarios<100||samples<10000)throw new AssertionError("Insufficient transition fixtures");
    System.out.println("Transition geometry fingerprint scenarios="+scenarios+" samples="+samples+" sha256="+HexFormat.of().formatHex(digest.digest()));
  }
}
