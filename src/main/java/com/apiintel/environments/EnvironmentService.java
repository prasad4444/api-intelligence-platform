package com.apiintel.environments;
import com.apiintel.store.RecordStore;
import com.apiintel.security.Vault;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
public class EnvironmentService {
  private final RecordStore store;private final Vault vault;
  public EnvironmentService(RecordStore store,Vault vault){this.store=store;this.vault=vault;}
  public List<ObjectNode> list(){return store.list("environments").stream().map(this::publicView).toList();}
  private ObjectNode values(ObjectNode record){String raw=vault.get(record.path("secretRef").asText());return raw.isBlank()?store.parse("{}"):store.parse(raw);}
  private ObjectNode publicView(ObjectNode record){var result=record.deepCopy();var stored=values(record);for(var field:result.path("values")){var row=(ObjectNode)field;String key=row.path("key").asText();row.put("hasValue",stored.has(key)&&!stored.path(key).asText().isBlank());row.put("value",row.path("secret").asBoolean()?"":stored.path(key).asText());}result.remove("secretRef");return result;}
  @Transactional public ObjectNode save(ObjectNode input,String actor){
    if(input.path("name").asText().isBlank()||!input.path("values").isArray()||input.path("values").size()>200)throw new IllegalArgumentException("Name and up to 200 variables are required");
    String id=input.path("id").asText(UUID.randomUUID().toString());if(!id.matches("[a-zA-Z0-9-]{1,45}"))throw new IllegalArgumentException("Invalid environment ID");
    ObjectNode old=null;for(var row:store.list("environments"))if(row.path("id").asText().equals(id))old=row;
    var previous=old==null?store.parse("{}"):values(old);var stored=store.parse("{}");var result=store.parse("{}");result.put("id",id);result.put("name",input.path("name").asText());result.put("revision",input.path("revision").asInt());String scope=input.path("scope").asText("environment");
    if(!Set.of("global","collection","environment").contains(scope))throw new IllegalArgumentException("Invalid variable scope");result.put("scope",scope);result.put("collectionId",input.path("collectionId").asText());
    if(scope.equals("collection"))store.get("collections",input.path("collectionId").asText());
    for(var existing:store.list("environments"))if(!existing.path("id").asText().equals(id)&&existing.path("scope").asText().equals(scope)&&(scope.equals("global")||(scope.equals("collection")&&existing.path("collectionId").asText().equals(input.path("collectionId").asText()))))throw new IllegalArgumentException("Edit the existing variable profile for this scope");
    var hosts=result.putArray("allowedHosts");if(!input.path("allowedHosts").isArray()||input.path("allowedHosts").isEmpty())throw new IllegalArgumentException("Add at least one exact allowed destination hostname");
    for(var h:input.path("allowedHosts")){String host=h.asText().trim().toLowerCase();if(!host.matches("[a-z0-9][a-z0-9.-]*"))throw new IllegalArgumentException("Use exact hostnames without schemes or wildcards");hosts.add(host);}
    var rows=result.putArray("values");var seen=new HashSet<String>();for(var value:input.path("values")){String key=value.path("key").asText();if(key.isBlank())continue;if(!key.matches("[a-zA-Z_][a-zA-Z0-9_.-]{0,99}")||!seen.add(key))throw new IllegalArgumentException("Variable names must be unique and contain letters, digits, underscores, dots or hyphens");
      String text=value.path("value").asText();if(value.path("secret").asBoolean()&&text.isBlank()&&value.path("hasValue").asBoolean())text=previous.path(key).asText();stored.put(key,text);rows.addObject().put("key",key).put("enabled",value.path("enabled").asBoolean(true)).put("secret",value.path("secret").asBoolean());}
    String ref="env_"+id;result.put("secretRef",ref);var saved=store.save("environments",result,actor);vault.put(ref,stored.toString());return publicView(saved);
  }
  @Transactional public void delete(String id,String actor){var record=store.get("environments",id);store.delete("environments",id,actor);vault.delete(record.path("secretRef").asText());}
  public record Resolved(ObjectNode request,Set<String> secrets){}
  public Resolved resolve(ObjectNode input){
    var variables=new LinkedHashMap<String,String>();var secrets=new HashSet<String>();var profiles=new ArrayList<ObjectNode>();
    for(String field:List.of("globalProfileId","collectionProfileId","environmentId")){String id=input.path(field).asText();if(id.isBlank())continue;var profile=store.get("environments",id);String expected=field.equals("globalProfileId")?"global":field.equals("collectionProfileId")?"collection":"environment";if(!expected.equals(profile.path("scope").asText("environment")))throw new IllegalArgumentException("Variable profile scope mismatch");if(expected.equals("collection")&&!input.path("collectionId").asText().equals(profile.path("collectionId").asText()))throw new IllegalArgumentException("Collection profile does not belong to this request");profiles.add(profile);var stored=values(profile);for(var row:profile.path("values"))if(row.path("enabled").asBoolean(true)){String key=row.path("key").asText(),value=stored.path(key).asText();variables.put(key,value);if(row.path("secret").asBoolean()&&!value.isBlank())secrets.add(value);}}
    var result=input.deepCopy();
    // Resolve nested variable values in a bounded pass; cycles remain visible as errors.
    for(int pass=0;pass<10;pass++)for(var entry:variables.entrySet())entry.setValue(expand(entry.getValue(),variables));
    for(var profile:profiles)for(var row:profile.path("values"))if(row.path("secret").asBoolean()){String value=variables.get(row.path("key").asText());if(value!=null&&!value.isBlank())secrets.add(value);}
    result.put("body",expand(result.path("body").asText(),variables));
    String url=result.path("url").asText().replace("%7B%7B","{{").replace("%7D%7D","}}");result.put("url",expandEncoded(url,variables));
    if(!profiles.isEmpty()){String host=URI.create(result.path("url").asText()).getHost();for(var profile:profiles){boolean allowed=false;for(var h:profile.path("allowedHosts"))if(h.asText().equalsIgnoreCase(host))allowed=true;if(!allowed)throw new IllegalArgumentException("Destination is not allowed by variable profile "+profile.path("name").asText());}}
    var headers=result.with("headers");var fields=new ArrayList<String>();headers.fieldNames().forEachRemaining(fields::add);for(String field:fields)headers.put(field,expand(headers.path(field).asText(),variables));
    if(result.path("auth").path("type").asText().equals("basic")){String user=expand(result.path("auth").path("username").asText(),variables),password=expand(result.path("auth").path("password").asText(),variables);headers.put("Authorization","Basic "+Base64.getEncoder().encodeToString((user+":"+password).getBytes(StandardCharsets.UTF_8)));result.remove("auth");}
    String contentType="";for(String field:fields)if(field.equalsIgnoreCase("content-type"))contentType=headers.path(field).asText();
    if(contentType.startsWith("application/x-www-form-urlencoded")){String body=input.path("body").asText().replace("%7B%7B","{{").replace("%7D%7D","}}");result.put("body",expandEncoded(body,variables));}
    if(result.toString().matches("(?s).*\\{\\{[^}]+\\}\\}.*"))throw new IllegalArgumentException("Unresolved variable or circular variable reference");
    return new Resolved(result,secrets);
  }
  private String expandEncoded(String text,Map<String,String> vars){var encoded=new HashMap<String,String>();vars.forEach((key,value)->encoded.put(key,java.net.URLEncoder.encode(value,StandardCharsets.UTF_8).replace("+","%20")));return expand(text,encoded);}
  private String expand(String text,Map<String,String> vars){var matcher=Pattern.compile("\\{\\{([a-zA-Z_][a-zA-Z0-9_.-]*)\\}\\}").matcher(text);var out=new StringBuffer();while(matcher.find())matcher.appendReplacement(out,Matcher.quoteReplacement(vars.getOrDefault(matcher.group(1),matcher.group())));matcher.appendTail(out);return out.toString();}
}
