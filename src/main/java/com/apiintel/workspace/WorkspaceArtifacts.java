package com.apiintel.workspace;
import com.apiintel.store.RecordStore;
import com.apiintel.tests.TestGenerationService;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
public class WorkspaceArtifacts {
 private final RecordStore store;private final SpecService specs;private final TestGenerationService tests;
 public WorkspaceArtifacts(RecordStore store,SpecService specs,TestGenerationService tests){this.store=store;this.specs=specs;this.tests=tests;}
 @Transactional public ObjectNode importSource(String source,String actor){var document=specs.parse(source);var record=store.parse("{}");record.put("name",document.path("info").path("title").asText("API"));record.put("version",document.path("info").path("version").asText("1.0"));record.put("status","DRAFT");record.put("source",source);record.put("contractHash",hash(document));record.set("document",document);var saved=store.save("specs",record,actor);prepare(saved,actor);return saved;}
 @Transactional public ObjectNode prepare(ObjectNode spec,String actor){var collection=collection(spec,actor);var bundle=collection.path("item").isEmpty()?store.parse("{\"cases\":[],\"suites\":[],\"plans\":[]}"):tests.local(spec.path("id").asText(),actor);var out=store.parse("{}");out.set("collection",collection);out.set("tests",bundle);return out;}
 @Transactional public ObjectNode collection(ObjectNode spec,String actor){
  var document=specs.parse(spec.path("document").toString());String hash=hash(document);
  for(var c:store.list("collections"))if(c.path("sourceSpecId").asText().equals(spec.path("id").asText())&&c.path("sourceContractHash").asText().equals(hash))return c;
  var c=specs.collection(document);String name=spec.path("name").asText()+" · "+spec.path("version").asText();c.put("name",name);c.with("info").put("name",name);c.put("sourceSpecId",spec.path("id").asText());c.put("sourceSpecRevision",spec.path("revision").asInt());c.put("sourceContractHash",hash);return store.save("collections",c,actor);
 }
 public static String hash(com.fasterxml.jackson.databind.JsonNode document){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(document.toString().getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}

}
