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

  @Test
  void rejectsSpecRecordWithoutDocument() throws Exception {
    mvc.perform(post("/api/records/specs").with(csrf())
      .contentType("application/json")
      .content("{\"name\":\"Broken\",\"status\":\"DRAFT\",\"paths\":{}}"))
      .andExpect(status().isBadRequest());
  }

  @Test
  @org.springframework.transaction.annotation.Transactional
  void uploadedContractCreatesLinkedPackageAndRegeneratesAfterChanges() throws Exception {
    var contract = specs.parse("openapi: 3.0.3\ninfo:\n  title: Uploaded package API\n  version: '1'\npaths:\n  /widgets:\n    get:\n      operationId: widgets\n      responses:\n        '200':\n          description: OK\n");
    var record = store.parse("{}");record.put("name","Uploaded package API");record.put("version","1");record.put("status","DRAFT");record.set("document",contract);
    var saved=store.save("specs",record,"admin");String id=saved.path("id").asText();int before=store.list("runs").size();
    var result=mvc.perform(post("/api/tests/generate/"+id+"/package").with(csrf())).andExpect(status().isOk()).andReturn();
    var bundle=json.readTree(result.getResponse().getContentAsString());assertEquals(4,bundle.path("cases").size());assertEquals(4,bundle.path("suites").size());assertEquals(4,bundle.path("plan").path("caseIds").size());assertEquals(before,store.list("runs").size());
    String first=bundle.path("cases").get(0).path("id").asText();
    var repeated=mvc.perform(post("/api/tests/generate/"+id+"/package").with(csrf())).andExpect(status().isOk()).andReturn();assertEquals(first,json.readTree(repeated.getResponse().getContentAsString()).path("cases").get(0).path("id").asText());
    ((ObjectNode)saved.path("document").path("paths").path("/widgets").path("get")).put("summary","Updated operation");store.save("specs",saved,"admin");
    var changed=mvc.perform(post("/api/tests/generate/"+id+"/package").with(csrf())).andExpect(status().isOk()).andReturn();assertNotEquals(first,json.readTree(changed.getResponse().getContentAsString()).path("cases").get(0).path("id").asText());
  }

  @Autowired
  com.apiintel.environments.EnvironmentService environments;

  @Test
  @org.springframework.transaction.annotation.Transactional
  void encryptedEnvironmentMasksSecretsAndResolvesOnlyAllowedDestinations() throws Exception {
    var input=store.parse("{\"name\":\"Secure Dev\",\"scope\":\"environment\",\"allowedHosts\":[\"example.com\"],\"values\":[{\"key\":\"baseUrl\",\"value\":\"https://example.com\",\"enabled\":true},{\"key\":\"bearerToken\",\"value\":\"private-demo-token\",\"secret\":true,\"enabled\":true}]}");
    var saved=environments.save(input,"admin");String id=saved.path("id").asText();assertEquals("",saved.path("values").get(1).path("value").asText());assertTrue(saved.path("values").get(1).path("hasValue").asBoolean());assertFalse(store.get("environments",id).toString().contains("private-demo-token"));
    var req=store.parse("{\"method\":\"GET\",\"url\":\"https://example.com/orders\",\"headers\":{\"Authorization\":\"Bearer {{bearerToken}}\"}}");req.put("environmentId",id);
    var resolved=environments.resolve(req);assertEquals("Bearer private-demo-token",resolved.request().path("headers").path("Authorization").asText());assertTrue(resolved.secrets().contains("private-demo-token"));
    var rotated=environments.save(saved,"admin");assertEquals("Bearer private-demo-token",environments.resolve(req).request().path("headers").path("Authorization").asText());
    req.put("url","https://other.example/orders");assertThrows(IllegalArgumentException.class,()->environments.resolve(req));
    req.put("url","https://example.com/orders?token={{bearerToken}}");assertTrue(environments.resolve(req).request().path("url").asText().contains("private-demo-token"));
    mvc.perform(get("/api/environments")).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private-demo-token"))));
    mvc.perform(post("/api/records/environments").with(csrf()).contentType("application/json").content(input.toString())).andExpect(status().isBadRequest());
    environments.delete(id,"admin");assertTrue(store.list("environments").stream().noneMatch(e->e.path("id").asText().equals(id)));
  }

  @Test
  @org.springframework.transaction.annotation.Transactional
  void environmentScopesAndEncodingAreDeterministic() throws Exception {
    var global=store.parse("{\"name\":\"Global vars\",\"scope\":\"global\",\"allowedHosts\":[\"example.com\"],\"values\":[{\"key\":\"token\",\"value\":\"global\",\"secret\":true}]}");
    var g=environments.save(global,"admin");
    var env=store.parse("{\"name\":\"Environment vars\",\"allowedHosts\":[\"example.com\"],\"values\":[{\"key\":\"token\",\"value\":\"a&b c\",\"secret\":true},{\"key\":\"alias\",\"value\":\"{{token}}\"}]}");
    var e=environments.save(env,"admin");
    var request=store.parse("{\"method\":\"POST\",\"url\":\"https://example.com/?key={{token}}\",\"headers\":{\"Content-Type\":\"application/x-www-form-urlencoded\"},\"body\":\"token=%7B%7Balias%7D%7D\"}");request.put("globalProfileId",g.path("id").asText());request.put("environmentId",e.path("id").asText());
    var resolved=environments.resolve(request).request();assertTrue(resolved.path("url").asText().contains("a%26b%20c"));assertEquals("token=a%26b%20c",resolved.path("body").asText());
    assertThrows(IllegalArgumentException.class,()->environments.save(global,"admin"));
    request.put("globalProfileId",e.path("id").asText());assertThrows(IllegalArgumentException.class,()->environments.resolve(request));
    var invalid=env.deepCopy();invalid.putArray("allowedHosts").add("*.example.com");assertThrows(IllegalArgumentException.class,()->environments.save(invalid,"admin"));
  }

  @Test
  @org.springframework.transaction.annotation.Transactional
  void oneImportPreparesAllModulesAndChecksWorkflowRevisionAndRoles() throws Exception {
    String source="# Keep this comment for complete-file comparison\nopenapi: 3.0.3\ninfo:\n  title: Unified upload API\n  version: '2.0'\nservers:\n  - url: https://api.example.com\nsecurity: []\npaths:\n  /orders:\n    post:\n      operationId: createOrder\n      summary: Create order\n      requestBody:\n        content:\n          application/json:\n            schema:\n              type: object\n              properties:\n                name:\n                  type: string\n                  example: new\n      responses:\n        '201':\n          description: Created\n";
    var imported=mvc.perform(post("/api/workspace/import").with(csrf()).contentType("application/json").content(json.writeValueAsString(Map.of("source",source)))).andExpect(status().isOk()).andReturn();
    var record=json.readTree(imported.getResponse().getContentAsString());String id=record.path("id").asText();assertEquals(source,record.path("source").asText());
    var collection=store.list("collections").stream().filter(c->c.path("sourceSpecId").asText().equals(id)).findFirst().orElseThrow();assertEquals(1,collection.path("item").size());assertEquals("201",collection.path("item").get(0).path("x-studio-checks").get(0).path("expected").asText());assertEquals("application/json",collection.path("item").get(0).path("request").path("header").get(0).path("value").asText());assertTrue(collection.path("item").get(0).path("request").path("body").path("raw").asText().contains("new"));assertEquals("https://api.example.com",collection.path("variable").get(0).path("value").asText());
    assertEquals(4,store.list("tests").stream().filter(t->t.path("specId").asText().equals(id)).count());assertEquals(4,store.list("suites").stream().filter(t->t.path("specId").asText().equals(id)).count());assertEquals(1,store.list("plans").stream().filter(t->t.path("specId").asText().equals(id)).count());
    mvc.perform(post("/api/workspace/"+id+"/collection").with(csrf()).contentType("application/json").content("{\"expectedRevision\":1}")).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(collection.path("id").asText()));
    mvc.perform(post("/api/workspace/"+id+"/approval").with(csrf()).contentType("application/json").content("{\"expectedRevision\":1,\"status\":\"APPROVED\"}")).andExpect(status().isBadRequest());
    mvc.perform(post("/api/workspace/"+id+"/approval").with(csrf()).contentType("application/json").content("{\"expectedRevision\":1,\"status\":\"IN_REVIEW\"}")).andExpect(status().isOk());
    mvc.perform(post("/api/workspace/"+id+"/approval").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("dev").roles("DEVELOPER")).with(csrf()).contentType("application/json").content("{\"expectedRevision\":2,\"status\":\"APPROVED\"}")).andExpect(status().isForbidden());
    mvc.perform(post("/api/workspace/"+id+"/draft").with(csrf()).contentType("application/json").content("{\"expectedRevision\":1}")).andExpect(status().isConflict());
    mvc.perform(post("/api/workspace/"+id+"/approval").with(csrf()).contentType("application/json").content("{\"expectedRevision\":2,\"status\":\"APPROVED\"}")).andExpect(status().isOk());
    mvc.perform(post("/api/workspace/"+id+"/draft").with(csrf()).contentType("application/json").content("{\"expectedRevision\":3}")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DRAFT"));
    assertEquals(1,store.list("collections").stream().filter(c->c.path("sourceSpecId").asText().equals(id)).count());
  }

  @Autowired
  com.apiintel.workspace.WorkspaceArtifacts artifacts;

  @Test
  @org.springframework.transaction.annotation.Transactional
  void savingYamlSynchronizesArtifactsWithoutOverwritingEditedCases() throws Exception {
    String source="openapi: 3.0.3\ninfo:\n  title: Save sync API\n  version: '1'\npaths:\n  /items:\n    get:\n      operationId: listItems\n      summary: List items\n      responses:\n        '200':\n          description: OK\n";
    var saved=artifacts.importSource(source,"admin");String id=saved.path("id").asText();
    var test=store.list("tests").stream().filter(t->t.path("specId").asText().equals(id)).findFirst().orElseThrow();test.put("title","Manual edit retained");store.save("tests",test,"admin");
    saved.put("source","# Comment added\n"+source);
    mvc.perform(post("/api/records/specs").with(csrf()).contentType("application/json").content(saved.toString())).andExpect(status().isOk());
    assertEquals("Manual edit retained",store.get("tests",test.path("id").asText()).path("title").asText());assertEquals(1,store.list("collections").stream().filter(c->c.path("sourceSpecId").asText().equals(id)).count());
    var next=store.get("specs",id);next.put("source",source.replace("version: '1'","version: '2'"));
    mvc.perform(post("/api/records/specs").with(csrf()).contentType("application/json").content(next.toString())).andExpect(status().isOk()).andExpect(jsonPath("$.version").value("2"));
    assertEquals(2,store.list("collections").stream().filter(c->c.path("sourceSpecId").asText().equals(id)).count());assertEquals(8,store.list("tests").stream().filter(t->t.path("specId").asText().equals(id)).count());assertTrue(store.list("suites").stream().filter(t->t.path("specId").asText().equals(id)).allMatch(t->t.has("contractHash")));
  }

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

  @Test
  @org.springframework.security.test.context.support.WithAnonymousUser
  void correlationHeadersDistinguishMissingSessionFromRejectedLogin() throws Exception {
    var response = mvc.perform(get("/api/me").header("X-Request-ID", "untrusted-input"))
      .andExpect(status().isUnauthorized())
      .andExpect(header().string("X-Request-ID", org.hamcrest.Matchers.matchesPattern("[0-9a-f-]{36}")))
      .andExpect(header().string("X-Platform-Failure", "platform_session_required")).andReturn();
    String id = response.getResponse().getHeader("X-Request-ID");
    mvc.perform(get("/api/usage").param("requestId", id)
        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("admin").roles("ADMIN")))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.events[0].requestId").value(id))
      .andExpect(jsonPath("$.events[0].reason").value("platform_session_required"))
      .andExpect(jsonPath("$.totals.platformUnauthorized").isNumber());
    mvc.perform(post("/api/login").with(csrf()).param("username", "admin").param("password", "wrong"))
      .andExpect(status().isUnauthorized())
      .andExpect(header().string("X-Platform-Failure", "login_rejected"));
    mvc.perform(post("/api/client/send"))
      .andExpect(status().isForbidden())
      .andExpect(header().string("X-Platform-Failure", "csrf_missing"));
  }

  String first(String kind) {
    return store.list(kind).get(0).path("id").asText();
  }

  @Test
  void authenticatedResourcesAndAudit() throws Exception {
    for (String kind : RecordStore.KINDS)
      if (!Set.of("ai-drafts", "ai-checks").contains(kind)) mvc.perform(get("/api/records/" + kind)).andExpect(status().isOk());
    mvc.perform(get("/api/records/ai-drafts")).andExpect(status().isBadRequest());
    mvc.perform(get("/api/ai-drafts")).andExpect(status().isOk());
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
    for (String state : List.of("IN_REVIEW", "APPROVED")) {
      int revision=store.get("specs",id).path("revision").asInt();
      mvc.perform(post("/api/workspace/"+id+"/approval").with(csrf()).contentType("application/json").content("{\"status\":\""+state+"\",\"expectedRevision\":"+revision+"}")).andExpect(status().isOk());
    }
    int revision=store.get("specs",id).path("revision").asInt();
    mvc.perform(post("/api/workspace/"+id+"/draft").with(csrf()).contentType("application/json").content("{\"expectedRevision\":"+revision+"}")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DRAFT"));
    for(String state:List.of("IN_REVIEW","REJECTED")){revision=store.get("specs",id).path("revision").asInt();mvc.perform(post("/api/workspace/"+id+"/approval").with(csrf()).contentType("application/json").content("{\"status\":\""+state+"\",\"expectedRevision\":"+revision+"}")).andExpect(status().isOk());}
    revision=store.get("specs",id).path("revision").asInt();
    mvc.perform(post("/api/workspace/"+id+"/collection").with(csrf()).contentType("application/json").content("{\"expectedRevision\":"+revision+"}")).andExpect(status().isOk());
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
