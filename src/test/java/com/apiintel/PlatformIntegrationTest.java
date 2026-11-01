package com.apiintel;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.apiintel.capacity.*;
import com.apiintel.integrations.*;
import com.apiintel.network.*;
import com.apiintel.security.*;
import com.apiintel.store.*;
import com.apiintel.tests.*;
import com.apiintel.workspace.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.io.*;
import java.net.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
  properties = {
    "spring.datasource.url=jdbc:h2:mem:platform;DB_CLOSE_DELAY=-1",
    "platform.admin-password=test-password-123",
    "platform.vault-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "platform.seed=true",
  }
)
@AutoConfigureMockMvc
@WithMockUser(
  username = "admin",
  roles = { "ADMIN", "REVIEWER", "DEVELOPER", "TESTER" }
)
class PlatformIntegrationTest {

  @Autowired
  MockMvc mvc;

  @Autowired
  RecordStore store;

  @Autowired
  SpecService specs;

  @Autowired
  WorkbookService workbook;

  @Autowired
  CapacityService capacity;

  @Autowired
  TestService tests;

  @Autowired
  Vault vault;

  @Autowired
  NetworkGuard guard;

  @Autowired
  ObjectMapper json;

  @Autowired
  TestWorkbookService testWorkbook;

  @Test
  void governanceRulesTrendsAndCsv() throws Exception {
    mvc
      .perform(
        post("/api/governance/rules")
          .with(csrf())
          .contentType("application/json")
          .content(
            "{\"name\":\"Summary\",\"check\":\"summary\",\"severity\":\"WARNING\",\"enabled\":true}"
          )
      )
      .andExpect(status().isOk());
    mvc
      .perform(post("/api/governance/evaluate").with(csrf()))
      .andExpect(status().isOk());
    mvc
      .perform(
        post("/api/jira/csv")
          .with(csrf())
          .contentType("application/json")
          .content(
            json.writeValueAsString(
              Map.of("csv", "Key,Summary,Status\nCSV-1,Imported story,To Do\n")
            )
          )
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.imported").value(1));
    mvc
      .perform(
        post("/api/jira/csv")
          .with(csrf())
          .contentType("application/json")
          .content(
            json.writeValueAsString(
              Map.of("csv", "Key,Summary,Status\n,Missing key,To Do\n")
            )
          )
      )
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.imported").value(0));
  }

  @Test
  @org.springframework.security.test.context.support.WithAnonymousUser
  void realLogin() throws Exception {
    mvc
      .perform(
        post("/api/login")
          .with(csrf())
          .param("username", "admin")
          .param("password", "test-password-123")
      )
      .andExpect(status().isNoContent());
    mvc
      .perform(
        post("/api/login")
          .with(csrf())
          .param("username", "admin")
          .param("password", "wrong")
      )
      .andExpect(status().isUnauthorized());
  }

  String first(String kind) {
    return store.list(kind).get(0).path("id").asText();
  }

  @Test
  void authenticatedResourcesAndAudit() throws Exception {
    for (String kind : RecordStore.KINDS)
      mvc.perform(get("/api/records/" + kind)).andExpect(status().isOk());
    mvc.perform(get("/api/me")).andExpect(jsonPath("$.name").value("admin"));
    mvc.perform(get("/api/csrf")).andExpect(jsonPath("$.token").exists());
    mvc.perform(get("/api/audit")).andExpect(status().isOk());
  }

  @Test
  void revisionsConflictCloneRestoreAndDelete() throws Exception {
    var n = store.parse("{\"title\":\"Case A\"}");
    var a = store.save("tests", n, "test");
    var b = a.deepCopy();
    b.put("title", "Case B");
    var updated = store.save("tests", b, "test");
    assertEquals(2, updated.path("revision").asInt());
    assertThrows(Exception.class, () -> store.save("tests", a, "test"));
    assertEquals(
      "Case A",
      store
        .restore("tests", a.path("id").asText(), 1, "test")
        .path("title")
        .asText()
    );
    String id = a.path("id").asText();
    mvc
      .perform(get("/api/records/tests/" + id + "/versions"))
      .andExpect(status().isOk());
    mvc
      .perform(post("/api/records/tests/" + id + "/restore/2").with(csrf()))
      .andExpect(status().isOk());
    mvc
      .perform(delete("/api/records/tests/" + id).with(csrf()))
      .andExpect(status().isOk());
    mvc
      .perform(get("/api/records/tests/" + id))
      .andExpect(status().isNotFound());
    assertThrows(Exception.class, () -> store.list("no-such-kind"));
  }

  @Test
  void recordCrudAndDedicatedEndpoints() throws Exception {
    var response = mvc
      .perform(
        post("/api/records/stories")
          .with(csrf())
          .contentType("application/json")
          .content("{\"key\":\"TEST-1\",\"status\":\"To Do\"}")
      )
      .andExpect(status().isOk())
      .andReturn();
    String id = json
      .readTree(response.getResponse().getContentAsString())
      .path("id")
      .asText();
    mvc.perform(get("/api/records/stories/" + id)).andExpect(status().isOk());
    mvc
      .perform(
        post("/api/records/integrations")
          .with(csrf())
          .contentType("application/json")
          .content("{}")
      )
      .andExpect(status().isBadRequest());
    mvc
      .perform(
        post("/api/records/specs")
          .with(csrf())
          .contentType("application/json")
          .content("{\"status\":\"APPROVED\"}")
      )
      .andExpect(status().isBadRequest());
  }

  @Test
  void openApiLifecycle() throws Exception {
    String source = store.list("specs").get(0).path("document").toString();
    String body = json.writeValueAsString(Map.of("source", source));
    mvc
      .perform(
        post("/api/workspace/lint")
          .with(csrf())
          .contentType("application/json")
          .content(body)
      )
      .andExpect(status().isOk());
    var res = mvc
      .perform(
        post("/api/workspace/import")
          .with(csrf())
          .contentType("application/json")
          .content(body)
      )
      .andExpect(status().isOk())
      .andReturn();
    String id = json
      .readTree(res.getResponse().getContentAsString())
      .path("id")
      .asText();
    for (String state : List.of("IN_REVIEW", "APPROVED", "REJECTED"))
      mvc
        .perform(
          post("/api/workspace/" + id + "/approval")
            .with(csrf())
            .contentType("application/json")
            .content("{\"status\":\"" + state + "\"}")
        )
        .andExpect(status().isOk());
    mvc
      .perform(post("/api/workspace/" + id + "/collection").with(csrf()))
      .andExpect(status().isOk());
    mvc
      .perform(
        post("/api/workspace/diff")
          .with(csrf())
          .contentType("application/json")
          .content("{\"before\":{\"a\":1,\"b\":2},\"after\":{\"a\":2}}")
      )
      .andExpect(jsonPath("$.length()").value(2));
    mvc.perform(get("/api/workspace/governance")).andExpect(status().isOk());
    assertThrows(Exception.class, () -> specs.parse("hello: world"));
    assertThrows(Exception.class, () -> specs.parse("{bad"));
    assertFalse(
      specs
        .lint(
          specs.parse("{\"openapi\":\"3.0.0\",\"paths\":{\"/a\":{\"get\":{}}}}")
        )
        .isEmpty()
    );
  }

  @Test
  void generatePreservesManualEditsAndExports() throws Exception {
    String id = first("specs");
    var generated = tests.generate(id, "test");
    assertTrue(generated.size() >= 4);
    var one = generated.get(0);
    one.put("title", "Manual edit");
    store.save("tests", one, "test");
    assertTrue(
      tests
        .generate(id, "test")
        .stream()
        .anyMatch(t -> t.path("title").asText().equals("Manual edit"))
    );
    mvc
      .perform(post("/api/tests/generate/" + id).with(csrf()))
      .andExpect(status().isOk());
    for (String format : List.of("json", "csv", "postman", "junit", "cucumber"))
      mvc
        .perform(get("/api/tests/export/" + format))
        .andExpect(status().isOk());
    assertThrows(Exception.class, () -> tests.export("invalid"));
  }

  @Test
  void capacityAndWorkbookRoundTrip() throws Exception {
    String sprint = first("sprints");
    assertEquals(2, capacity.calculate(sprint).size());
    mvc.perform(get("/api/capacity/" + sprint)).andExpect(status().isOk());
    byte[] xlsx = workbook.export(false);
    var report = workbook.load(new ByteArrayInputStream(xlsx), "test");
    assertEquals(List.of(), report.get("errors"));
    assertTrue((int) report.get("imported") > 0);
    mvc
      .perform(get("/api/capacity/workbook/template"))
      .andExpect(status().isOk());
    mvc
      .perform(get("/api/capacity/workbook/export"))
      .andExpect(status().isOk());
    mvc
      .perform(
        multipart("/api/capacity/workbook")
          .file(
            new MockMultipartFile(
              "file",
              "data.xlsx",
              "application/octet-stream",
              xlsx
            )
          )
          .with(csrf())
      )
      .andExpect(status().isOk());
    try (
      var wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook(
        new ByteArrayInputStream(workbook.export(true))
      );
      var out = new ByteArrayOutputStream()
    ) {
      var row = wb.getSheet("Team").createRow(1);
      row.createCell(0).setCellValue("Invalid member");
      row.createCell(1).setCellValue("Other");
      wb.write(out);
      var invalid = workbook.load(
        new ByteArrayInputStream(out.toByteArray()),
        "test"
      );
      assertEquals(0, invalid.get("imported"));
      assertFalse(((List<?>) invalid.get("errors")).isEmpty());
    }
  }

  @Test
  void leaveHolidaysAndOvercommit() {
    var sprint = store.get("sprints", first("sprints"));
    String day = sprint.path("start").asText();
    var member = store.list("team").get(0);
    var leave = store.parse("{}");
    leave.put("name", member.path("name").asText());
    leave.put("from", day);
    leave.put("to", day);
    var l = store.save("leaves", leave, "test");
    var holiday = store.parse("{}");
    holiday.put("date", day);
    holiday.put("region", member.path("region").asText());
    holiday.put("name", "Holiday");
    var h = store.save("holidays", holiday, "test");
    assertTrue(
      capacity
        .calculate(sprint.path("id").asText())
        .stream()
        .anyMatch(r ->
          ((List<Map<String, Object>>) r.get("days"))
            .stream()
            .anyMatch(d -> d.get("reason").equals("Holiday"))
        )
    );
    store.delete("leaves", l.path("id").asText(), "test");
    store.delete("holidays", h.path("id").asText(), "test");
  }

  @Test
  void testWorkbookRoundTrip() throws Exception {
    tests.generate(first("specs"), "test");
    byte[] bytes = testWorkbook.export();
    mvc.perform(get("/api/tests/workbook")).andExpect(status().isOk());
    mvc
      .perform(
        multipart("/api/tests/workbook")
          .file(
            new MockMultipartFile(
              "file",
              "cases.xlsx",
              "application/octet-stream",
              bytes
            )
          )
          .with(csrf())
      )
      .andExpect(status().isOk());
  }

  @Test
  void vaultEncryptionRotationAndApi() throws Exception {
    vault.put("sample", "first-token");
    assertEquals("first-token", vault.get("sample"));
    vault.put("sample", "rotated");
    assertEquals("rotated", vault.get("sample"));
    assertTrue(vault.names().contains("sample"));
    assertEquals("", vault.get("missing"));
    mvc
      .perform(
        put("/api/integrations/secrets/test-token")
          .with(csrf())
          .contentType("application/json")
          .content("{\"value\":\"token-value\"}")
      )
      .andExpect(status().isOk());
    mvc
      .perform(get("/api/integrations/secrets"))
      .andExpect(status().isOk())
      .andExpect(
        content().string(
          org.hamcrest.Matchers.not(
            org.hamcrest.Matchers.containsString("token-value")
          )
        )
      );
    mvc
      .perform(delete("/api/integrations/secrets/test-token").with(csrf()))
      .andExpect(status().isOk());
    assertThrows(Exception.class, () -> vault.put("../bad", "x"));
    vault.delete("sample");
  }

  @Test
  void blocksSsrfAndBadMethods() throws Exception {
    for (String target : List.of(
      "http://127.0.0.1",
      "http://169.254.169.254/latest",
      "http://10.0.0.1",
      "file:///etc/passwd",
      "http://[::1]",
      "https://user:pass@example.com",
      "http://100.64.0.1"
    ))
      assertThrows(IllegalArgumentException.class, () ->
        guard.validate(target)
      );
    guard.check(InetAddress.getByAddress(new byte[] { 8, 8, 8, 8 }));
    mvc
      .perform(
        post("/api/client/send")
          .with(csrf())
          .contentType("application/json")
          .content("{\"url\":\"http://127.0.0.1\"}")
      )
      .andExpect(status().isBadRequest());
    mvc
      .perform(
        post("/api/client/send")
          .with(csrf())
          .contentType("application/json")
          .content("{\"method\":\"CONNECT\",\"url\":\"https://example.com\"}")
      )
      .andExpect(status().isBadRequest());
  }

  @Test
  @WithMockUser(roles = "DEVELOPER")
  void authorizationAndCsrf() throws Exception {
    mvc.perform(get("/api/audit")).andExpect(status().isForbidden());
    mvc
      .perform(
        put("/api/integrations/secrets/no")
          .with(csrf())
          .contentType("application/json")
          .content("{\"value\":\"x\"}")
      )
      .andExpect(status().isForbidden());
    mvc
      .perform(
        post("/api/records/stories")
          .contentType("application/json")
          .content("{}")
      )
      .andExpect(status().isForbidden());
    String id = store
      .list("integrations")
      .stream()
      .filter(c -> c.path("provider").asText().equals("github"))
      .findFirst()
      .get()
      .path("id")
      .asText();
    mvc
      .perform(
        post("/api/integrations/" + id + "/read")
          .with(csrf())
          .contentType("application/json")
          .content("{\"path\":\"/\"}")
      )
      .andExpect(status().isForbidden());
  }
}
