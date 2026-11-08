package com.sora.splineroads.net;

import com.sora.splineroads.core.RoadEditRequests;
import net.minecraft.nbt.CompoundTag;

/** Correlation fields are transport metadata, never authority for a world edit. */
public final class RoadActionContext {
  public static String target(CompoundTag command) {
    if (command.hasUUID("Id")) return "road:" + command.getUUID("Id");
    if (command.hasUUID("Token")) return "token:" + command.getUUID("Token");
    if (command.contains("Pos")) return "node:" + command.getLong("Pos");
    if (command.contains("A") || command.contains("B"))
      return "ends:" + command.getLong("A") + ":" + command.getLong("B");
    return "";
  }

  public static void attach(CompoundTag command, RoadEditRequests.Request request) {
    command.putUUID("ReplyId", request.id());
    command.putLong("ReplyGeneration", request.generation());
  }

  public static void echo(CompoundTag command, CompoundTag reply) {
    if (!command.hasUUID("ReplyId")) return;
    reply.putUUID("ReplyId", command.getUUID("ReplyId"));
    reply.putLong("ReplyGeneration", command.getLong("ReplyGeneration"));
    reply.putString("ReplyAction", command.getString("Action"));
    reply.putString("ReplyTarget", target(command));
  }

  private RoadActionContext() {}
}
