package com.apiintel.chat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import reactor.core.publisher.Flux;

/**
 * Provider adapter for the unified chat surface. Capabilities are declared
 * statically per adapter — the UI must never guess them. Adapters never see
 * raw secrets; outbound auth happens inside IntegrationService/Vault.
 */
public interface ChatAdapter {

  /** Stable adapter key, e.g. {@code template}, {@code openai-compatible}. */
  String name();

  /** {mode:"chat"|"task", streaming:bool, multiTurn:bool, contextAttach:bool, browserBacked:bool} */
  ObjectNode capabilities(ObjectNode connection);

  Flux<ChatEvent> stream(ChatRequest request);
}
