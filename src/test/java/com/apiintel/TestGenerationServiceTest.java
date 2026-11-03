package com.apiintel;
import com.apiintel.tests.*;
import com.apiintel.integrations.IntegrationService;
import com.apiintel.store.RecordStore;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
class TestGenerationServiceTest {
  ObjectMapper json=new ObjectMapper(); RecordStore store;IntegrationService integrations;TestService tests;TestGenerationService service;ObjectNode spec,config,bundle;List<ObjectNode> written;
  ObjectNode node(String value)throws Exception{return (ObjectNode)json.readTree(value);}
  @BeforeEach void setup()throws Exception{
    store=mock(RecordStore.class);integrations=mock(IntegrationService.class);tests=mock(TestService.class);service=new TestGenerationService(store,integrations,tests);written=new ArrayList<>();
    spec=node("{\"id\":\"s\",\"name\":\"Orders\",\"revision\":2,\"document\":{\"paths\":{\"/orders\":{\"get\":{},\"post\":{}}}}}");
    config=node("{\"id\":\"ai\",\"enabled\":true,\"testGenerationEnabled\":true,\"provider\":\"openai\",\"model\":\"test\"}");
    bundle=node("{\"cases\":[{\"key\":\"c1\",\"title\":\"Read orders\",\"operation\":\"GET /orders\",\"type\":\"positive\",\"request\":{\"method\":\"GET\",\"url\":\"{{baseUrl}}/orders\",\"headers\":{},\"body\":\"\"},\"expectedStatus\":200}],\"suites\":[{\"key\":\"smoke\",\"name\":\"Smoke\",\"caseKeys\":[\"c1\"]}],\"plans\":[{\"name\":\"Regression\",\"suiteKeys\":[\"smoke\"]}]}");
    when(store.parse(anyString())).thenAnswer(i->node(i.getArgument(0)));when(store.get("specs","s")).thenReturn(spec);when(store.list(anyString())).thenReturn(List.of());
    when(store.save(anyString(),any(ObjectNode.class),anyString())).thenAnswer(i->{var n=((ObjectNode)i.getArgument(1)).deepCopy();n.put("id","id-"+written.size());n.put("revision",1);written.add(n);return n;});
    when(integrations.config("ai")).thenReturn(config);when(integrations.call(any(),anyString(),anyString(),any(),anyString())).thenReturn(TextNode.valueOf(bundle.toString()));
  }
  ObjectNode input()throws Exception{return node("{\"integrationId\":\"ai\",\"consent\":true}");}
  @Test void validatesCaseSuiteAndPlanReferences(){var v=service.validate(spec,bundle);assertEquals(1,v.path("cases").size());assertEquals("c1",v.path("suites").get(0).path("caseKeys").get(0).asText());}
  @Test void rejectsUnknownOperationAndExternalUrls(){((ObjectNode)bundle.path("cases").get(0)).put("operation","GET /other");assertThrows(IllegalArgumentException.class,()->service.validate(spec,bundle));((ObjectNode)bundle.path("cases").get(0)).put("operation","GET /orders");((ObjectNode)bundle.path("cases").get(0).path("request")).put("url","https://other.example/");assertThrows(IllegalArgumentException.class,()->service.validate(spec,bundle));}
  @Test void rejectsUnknownReferences(){((ArrayNode)bundle.path("suites").get(0).path("caseKeys")).add("missing");assertThrows(IllegalArgumentException.class,()->service.validate(spec,bundle));}
  @Test void rejectsUnknownPlanReferences(){((ArrayNode)bundle.path("plans").get(0).path("suiteKeys")).add("missing");assertThrows(IllegalArgumentException.class,()->service.validate(spec,bundle));}
  @Test void rejectsDuplicateCaseKeys(){((ArrayNode)bundle.path("cases")).add(bundle.path("cases").get(0).deepCopy());assertThrows(IllegalArgumentException.class,()->service.validate(spec,bundle));}
  @Test void rejectsInvalidStatusesAndMissingCases(){((ObjectNode)bundle.path("cases").get(0)).put("expectedStatus",999);assertThrows(IllegalArgumentException.class,()->service.validate(spec,bundle));bundle.putArray("cases");assertThrows(IllegalArgumentException.class,()->service.validate(spec,bundle));}
  @ParameterizedTest @ValueSource(strings={"openai","azure","anthropic","ollama","custom-json"}) void previewsThroughConfiguredProtocolsWithoutSaving(String protocol)throws Exception{config.put("testGenerationProtocol",protocol);config.put("chatPath","/chat");var result=service.preview("s",input(),"tester");assertEquals(2,result.path("specRevision").asInt());assertEquals(1,result.path("bundle").path("cases").size());verify(integrations).authorize("ai");verify(store,never()).save(anyString(),any(),anyString());}
  @Test void extractsOpenAiResponse()throws Exception{when(integrations.call(any(),anyString(),anyString(),any(),anyString())).thenReturn(json.createObjectNode().set("choices",json.createArrayNode().add(json.createObjectNode().set("message",json.createObjectNode().put("content","```json\n"+bundle+"\n```")))));assertEquals(1,service.preview("s",input(),"tester").path("bundle").path("cases").size());}
  @Test void extractsOllamaAnthropicAndCustomResponses()throws Exception{
    when(integrations.call(any(),anyString(),anyString(),any(),anyString())).thenReturn(json.createObjectNode().put("response",bundle.toString()));assertTrue(service.preview("s",input(),"t").has("bundle"));
    when(integrations.call(any(),anyString(),anyString(),any(),anyString())).thenReturn(json.createObjectNode().set("content",json.createArrayNode().add(json.createObjectNode().put("text",bundle.toString()))));assertTrue(service.preview("s",input(),"t").has("bundle"));
    config.put("testResponsePointer","/result/text");var response=json.createObjectNode();response.putObject("result").put("text",bundle.toString());when(integrations.call(any(),anyString(),anyString(),any(),anyString())).thenReturn(response);assertTrue(service.preview("s",input(),"t").has("bundle"));
  }
  @Test void requiresConsentAndOptIn()throws Exception{var body=input();body.put("consent",false);assertThrows(IllegalArgumentException.class,()->service.preview("s",body,"t"));config.put("testGenerationEnabled",false);body.put("consent",true);assertThrows(IllegalArgumentException.class,()->service.preview("s",body,"t"));verify(integrations,never()).call(any(),anyString(),anyString(),any(),anyString());}
  @Test void rejectsUnsupportedProtocolAndMalformedResponse()throws Exception{config.put("testGenerationProtocol","website");assertThrows(IllegalArgumentException.class,()->service.preview("s",input(),"t"));config.put("testGenerationProtocol","openai");when(integrations.call(any(),anyString(),anyString(),any(),anyString())).thenReturn(TextNode.valueOf("not json"));assertThrows(IllegalArgumentException.class,()->service.preview("s",input(),"t"));}
  @Test void savesReviewedBundleWithRealReferencesAndNoRuns()throws Exception{var preview=input();preview.put("specId","s");preview.put("specRevision",2);preview.set("bundle",bundle);var result=service.accept("s",preview,"tester");assertEquals("id-0",result.path("suites").get(0).path("caseIds").get(0).asText());assertEquals("id-1",result.path("plans").get(0).path("suiteIds").get(0).asText());verify(store,never()).save(eq("runs"),any(),anyString());assertEquals("Draft",result.path("cases").get(0).path("status").asText());}
  @Test void rejectsStalePreviewWithoutPartialWrites()throws Exception{var preview=input();preview.put("specId","s");preview.put("specRevision",1);preview.set("bundle",bundle);assertThrows(IllegalArgumentException.class,()->service.accept("s",preview,"t"));assertTrue(written.isEmpty());}
  @Test void localGeneratorCreatesFourSuitesAndOnePlan()throws Exception{var c=node("{\"id\":\"case\",\"type\":\"positive\",\"contractHash\":\"hash\"}");when(tests.generate("s","tester")).thenReturn(List.of(c));var result=service.local("s","tester");assertEquals(4,result.path("suites").size());assertEquals(1,result.path("plan").path("caseIds").size());verify(store,never()).save(eq("runs"),any(),anyString());}
}
