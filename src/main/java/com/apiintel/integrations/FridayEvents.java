package com.apiintel.integrations;

import com.apiintel.store.RecordStore;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

@Service
public class FridayEvents {

  private final RecordStore store;
  private final IntegrationService integrations;

  public FridayEvents(RecordStore s, IntegrationService i) {
    store = s;
    integrations = i;
  }

  @org.springframework.context.event.EventListener
  public void onPullRequest(IntegrationService.PullRequestCreated e) {
    emit("prCreated", e.payload(), e.actor());
  }

  public int emit(String event, ObjectNode payload, String actor) {
    int queued = 0;
    for (var c : store.list("integrations")) {
      if (
        !c.path("provider").asText().equals("friday") ||
        !c.path("enabled").asBoolean()
      ) continue;
      String signal = c.path("eventSignals").path(event).asText(),
        workspace = c.path("workspaceId").asText();
      if (signal.isBlank() || workspace.isBlank()) continue;
      if (
        !signal.matches("[a-zA-Z0-9_-]+") ||
        !workspace.matches("[a-zA-Z0-9_-]+")
      ) continue;
      var action = store.parse("{}");
      action.put("method", "POST");
      action.put("path", "/api/workspaces/" + workspace + "/signals/" + signal);
      action.putObject("body").set("payload", payload);
      integrations.propose(c.path("id").asText(), action, actor);
      queued++;
    }
    return queued;
  }
}
