/* Preserve the concrete field type and all vanilla DataLayer/snapshot semantics. */
var O=Java.type('org.objectweb.asm.Opcodes');
var original='it/unimi/dsi/fastutil/longs/Long2ObjectOpenHashMap';
var replacement='com/sora/splineroads/lighting/RoadLightSnapshotMap';
function replaceInitialMap(node){
  var allocations=0,constructors=0;
  for(var m=0;m<node.methods.size();m++){
    var method=node.methods.get(m);
    if(method.name!=='<init>')continue;
    var instructions=method.instructions.toArray();
    for(var i=0;i<instructions.length;i++){
      var n=instructions[i];
      if(n.getOpcode()===O.NEW&&n.desc===original){n.desc=replacement;allocations++;}
      if(n.getOpcode()===O.INVOKESPECIAL&&n.owner===original&&n.name==='<init>'&&n.desc==='()V'){
        n.owner=replacement;constructors++;
      }
    }
  }
  if(allocations!==1||constructors!==1)throw new Error('SR light snapshot: expected one empty section map in '+node.name+', got '+allocations+'/'+constructors);
  return node;
}
function initializeCoreMod(){return {
  'sr_block_light_snapshot':{target:{type:'CLASS',name:'net.minecraft.world.level.lighting.BlockLightSectionStorage'},transformer:replaceInitialMap},
  'sr_sky_light_snapshot':{target:{type:'CLASS',name:'net.minecraft.world.level.lighting.SkyLightSectionStorage'},transformer:replaceInitialMap}
};}
