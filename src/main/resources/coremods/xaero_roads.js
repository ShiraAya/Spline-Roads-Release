/* Optional map-only block sampling. No dependency on Xaero at startup and no
 * change to LevelChunk, collision, block states or the world's rendered models. */
function initializeCoreMod(){
  var O=Java.type('org.objectweb.asm.Opcodes');
  var Call=Java.type('org.objectweb.asm.tree.MethodInsnNode');
  var API=Java.type('net.minecraftforge.coremod.api.ASMAPI');
  function patch(node){
    var sites=[],refresh=[];
    for(var i=0;i<node.methods.size();i++){
      var method=node.methods.get(i);
      if(method.name==='writeTile'&&node.name==='xaero/common/minimap/write/MinimapWriter'){
        var scan=method.instructions.toArray();
        for(var r=0;r<scan.length;r++)if(scan[r].getOpcode()===O.INVOKEVIRTUAL
          &&scan[r].owner==='java/lang/reflect/Field'&&scan[r].name==='get'
          &&scan[r].desc==='(Ljava/lang/Object;)Ljava/lang/Object;')refresh.push({method:method,call:scan[r]});
      }
      if(method.name!=='loadPixel'&&method.name!=='findBlock')continue;
      var code=method.instructions.toArray();
      for(var j=0;j<code.length;j++){
        var n=code[j];if(n.getOpcode()===O.INVOKEVIRTUAL&&n.owner==='net/minecraft/world/level/chunk/LevelChunk'
          &&(n.name===API.mapMethod('m_8055_')||n.name==='getBlockState'||n.name==='m_8055_')
          &&n.desc==='(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;')sites.push({method:method,call:n});
      }
    }
    // Unknown writer implementations remain unchanged, with a diagnostic.
    if(sites.length===0){API.log('WARN','SR Xaero adapter found no sampling sites in '+node.name);return node;}
    if(node.name==='xaero/common/minimap/write/MinimapWriter'&&refresh.length!==1){
      API.log('WARN','SR Xaero adapter found an unknown chunk refresh path in '+node.name);return node;
    }
    for(var r=0;r<refresh.length;r++)refresh[r].method.instructions.set(refresh[r].call,new Call(O.INVOKESTATIC,
      'com/sora/splineroads/client/XaeroRoadMap','miniChunkClean',
      '(Ljava/lang/reflect/Field;Ljava/lang/Object;)Ljava/lang/Object;',false));
    for(var k=0;k<sites.length;k++)sites[k].method.instructions.set(sites[k].call,new Call(O.INVOKESTATIC,
      'com/sora/splineroads/client/XaeroRoadMap',node.name==='xaero/map/MapWriter'?'readWorld':'readMini',
      '(Lnet/minecraft/world/level/chunk/LevelChunk;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;',false));
    API.log('INFO','SR Xaero map sampling: '+node.name+' ('+sites.length+' sites, '+refresh.length+' refresh)');return node;
  }
  return {
    sr_xaero_world_roads:{target:{type:'CLASS',name:'xaero.map.MapWriter'},transformer:patch},
    sr_xaero_mini_roads:{target:{type:'CLASS',name:'xaero.common.minimap.write.MinimapWriter'},transformer:patch}
  };
}
