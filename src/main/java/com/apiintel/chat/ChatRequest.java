package com.apiintel.chat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Everything an adapter needs for one send. {@code connection} is the runtime
 * (secret-free) config; {@code providerState} is persisted back onto the
 * conversation after the stream finishes (e.g. Devin session id, Copilot
 * conversationId). {@code cancelled} lets adapters abort work cooperatively.
 */
public record ChatRequest(
  ObjectNode connection,
  String prompt,
  List<ObjectNode> history,
  ObjectNode providerState,
  String ownerId,
  String username,
  BooleanSupplier cancelled
) {}
