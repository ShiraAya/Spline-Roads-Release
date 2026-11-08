package com.sora.splineroads.net;

import java.util.List;
import java.util.LinkedHashSet;
import java.util.Objects;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** Preserve user edits when the server reopens the same target after world preview. */
public final class RoadDraft {
  public static CompoundTag merge(CompoundTag fresh, CompoundTag saved) {
    CompoundTag out = baseline(fresh);
    if (saved == null
        || fresh.getBoolean("DeleteOnly")
        || saved.getBoolean("DeleteOnly")
        || !fresh.getString("Kind").equals(saved.getString("Kind"))) return out;
    boolean same =
        fresh.getString("Kind").equals("node")
            ? fresh.getLong("Pos") == saved.getLong("Pos")
            : fresh.hasUUID("Id")
                ? saved.hasUUID("Id") && fresh.getUUID("Id").equals(saved.getUUID("Id"))
                : !saved.hasUUID("Id")
                    && fresh.getLong("A") == saved.getLong("A")
                    && fresh.getLong("B") == saved.getLong("B");
    if(fresh.getString("Kind").equals("armRoad"))same &= fresh.getInt("Arm")==saved.getInt("Arm")&&fresh.getInt("Signature")==saved.getInt("Signature");
    if(fresh.getString("Kind").equals("sideRoad"))same &= fresh.getInt("Signature")==saved.getInt("Signature")
        &&Objects.equals(fresh.get("Main"),saved.get("Main"))&&Objects.equals(fresh.get("AttachedPoint"),saved.get("AttachedPoint"))
        &&Objects.equals(fresh.get("Branch"),saved.get("Branch"))&&Objects.equals(fresh.get("MainB"),saved.get("MainB"))
        &&Objects.equals(fresh.get("AttachedPointB"),saved.get("AttachedPointB"))&&fresh.getBoolean("MainFirst")==saved.getBoolean("MainFirst");
    if (same) {
      for (String key :
          List.of(
              "Settings", "Node", "Advanced", "ExtraPage", "LiftPage", "OptionsPage", "LevelEnds", "ForceJunction", "ForceJunctionA", "ForceJunctionB"))
        if (saved.contains(key)) out.put(key, saved.get(key).copy());
      if (fresh.getString("Kind").equals("road") && fresh.contains("Settings")) {
        var base=saved.contains("BaseSettings")?saved.getCompound("BaseSettings"):saved.getCompound("Settings");
        var current=fresh.getCompound("Settings");
        try {
          out.put("Settings",reconcileSettings(base,saved.getCompound("Settings"),current));
          out.put("BaseSettings",editableSettings(current));out.remove("DraftConflict");
        } catch(IllegalArgumentException conflict) {
          // Keep the unsaved choice and its old baseline. The server will reject
          // conflicting changes until the user resolves them or clears the draft.
          out.put("Settings",mergeSettings(base,saved.getCompound("Settings"),current,"",false));
          out.put("BaseSettings",base.copy());out.putString("DraftConflict",conflict.getMessage());
        }
      }
    }
    return out;
  }

  /** The baseline is captured before any widget writes; Esc keeps it with the draft. */
  public static CompoundTag baseline(CompoundTag value) {
    var out=value.copy();
    if(out.getString("Kind").equals("road")&&out.contains("Settings")&&!out.contains("BaseSettings"))
      out.put("BaseSettings",editableSettings(out.getCompound("Settings")));
    return out;
  }

  private static CompoundTag editableSettings(CompoundTag settings) {
    var out=settings.copy();
    for(String key:List.of("AttachedRoadPoints","LanePointsV2","LaneLines","HideArrows"))out.remove(key);
    var infra=out.getCompound("Infrastructure");for(String key:List.of("GantryEdits","FixedStations","Signs"))infra.remove(key);
    return out;
  }

  /** Three-way dirty-field merge, repeated against authoritative settings at save time. */
  public static CompoundTag reconcileSettings(CompoundTag base,CompoundTag edited,CompoundTag current) {
    return mergeSettings(base,edited,current,"",true);
  }

  private static boolean toolOwned(String path) {
    return path.equals("Infrastructure.GantryEdits")||path.equals("Infrastructure.FixedStations")
        ||path.equals("AttachedRoadPoints")||path.equals("LanePointsV2")||path.equals("LaneLines")
        ||path.equals("HideArrows")||path.equals("Infrastructure.Signs");
  }

  private static CompoundTag mergeSettings(CompoundTag base,CompoundTag edited,CompoundTag current,String parent,boolean strict) {
    var out=new CompoundTag();var keys=new LinkedHashSet<String>();
    keys.addAll(base.getAllKeys());keys.addAll(edited.getAllKeys());keys.addAll(current.getAllKeys());
    for(String key:keys) {
      String path=parent.isEmpty()?key:parent+"."+key;
      Tag before=base.get(key),wanted=edited.get(key),latest=current.get(key),chosen;
      if(toolOwned(path)||Objects.equals(wanted,before))chosen=latest;
      else if(wanted instanceof CompoundTag a&&latest instanceof CompoundTag b&&(before==null||before instanceof CompoundTag))
        chosen=mergeSettings(before instanceof CompoundTag c?c:new CompoundTag(),a,b,path,strict);
      else if(Objects.equals(latest,before)||Objects.equals(latest,wanted))chosen=wanted;
      else {
        if(strict)throw new IllegalArgumentException("道路设置已更新（"+path+"）；请重新打开核对，或 Shift＋右键清除旧草稿");
        chosen=wanted;
      }
      if(chosen!=null)out.put(key,chosen.copy());
    }
    return out;
  }

  /** Commands never echo road meshes, neighboring structures or preview context. */
  public static CompoundTag action(CompoundTag draft, boolean node) {
    CompoundTag out = new CompoundTag();
    for (String key :
        node ? List.of("Pos", "Node") : List.of("A", "B", "Id", "Settings", "BaseSettings", "LevelEnds", "ForceJunction", "ForceJunctionA", "ForceJunctionB"))
      if (draft.contains(key)) out.put(key, draft.get(key).copy());
    out.putString("Action", node ? "node" : "connect");
    return out;
  }

  private RoadDraft() {}
}
