package com.apiintel.chat;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** One server-sent event in a chat stream: meta | delta | task | done | error. */
public record ChatEvent(String type, ObjectNode data) {

  public static ChatEvent of(String type, ObjectNode data) {
    return new ChatEvent(type, data == null ? new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode() : data);
  }
}
