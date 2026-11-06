package com.apiintel.integrations;

import com.apiintel.diagnostics.ApiRequestLog;
import com.apiintel.diagnostics.OutboundLog;
import com.apiintel.network.NetworkGuard;
import com.apiintel.security.Vault;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Optional native Windows SSPI transport. Never invokes a command shell. */
@Component
public class WindowsAuthTransport {
  @org.springframework.beans.factory.annotation.Autowired
  private OutboundLog outbound = new OutboundLog();

  private static final int LIMIT=2*1024*1024;
  private static final String STATUS="\n__APIINTEL_HTTP_STATUS__";
  private final Vault vault;
  private final NetworkGuard guard;
  private final Set<String> hosts;
  private final String executable;
  public WindowsAuthTransport(Vault vault,NetworkGuard guard,
      @Value("${platform.windows-auth-hosts:}") String hosts,
      @Value("${platform.windows-curl:curl.exe}") String executable) {
    this.vault=vault;this.guard=guard;this.hosts=new HashSet<>();
    for(String host:hosts.split(","))if(!host.isBlank())this.hosts.add(host.trim().toLowerCase(Locale.ROOT));
    this.executable=executable;
  }
  public void validate(ObjectNode config,URI uri) {
    if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getFragment()!=null)
      throw new IllegalArgumentException("Windows authentication requires an HTTPS destination without URL credentials");
    if(!hosts.contains(uri.getHost().toLowerCase(Locale.ROOT)))throw new IllegalArgumentException("Enable this exact Windows authentication hostname in WINDOWS_AUTH_HOSTS on the server");
    if(!Set.of("NEGOTIATE","NTLM").contains(config.path("windowsProtocol").asText("NEGOTIATE")))throw new IllegalArgumentException("Choose Windows NEGOTIATE or NTLM");
    if(!config.path("windowsUseCurrentUser").asBoolean(true)) {
      if(config.path("windowsUsername").asText().isBlank()||config.path("windowsUsername").asText().matches("(?s).*[:\\r\\n].*"))throw new IllegalArgumentException("Set a Windows username such as DOMAIN\\user or user@domain");
      if(config.path("secretRef").asText().isBlank())throw new IllegalArgumentException("Choose a vault reference for the Windows password");
    }
  }
  protected InetAddress[] addresses(String host) throws UnknownHostException {return InetAddress.getAllByName(host);}
  public String pinnedAddress(String host) {
    try {
      var addresses=addresses(host);if(addresses.length==0)throw new UnknownHostException();
      for(var address:addresses) {
        byte[] bytes=address.getAddress();boolean privateAddress=address.isSiteLocalAddress()||(bytes.length==16&&(bytes[0]&254)==252);
        // Exact deployment allowlist permits RFC1918/ULA, never loopback or metadata/link-local.
        if(address.isAnyLocalAddress()||address.isLoopbackAddress()||address.isLinkLocalAddress()||address.isMulticastAddress())throw new IllegalArgumentException("Windows destinations cannot use loopback, metadata, link-local or multicast addresses");
        if(!privateAddress)guard.check(address);
      }
      String ip=addresses[0].getHostAddress();return ip.contains(":")?"["+ip+"]":ip;
    } catch(UnknownHostException e){throw new IllegalArgumentException("Windows destination hostname cannot be resolved");}
  }
  public Map<String,Object> check(ObjectNode config) {
    validate(config,URI.create(config.path("baseUrl").asText()));
    var version=run(List.of(executable,"--disable","--version"),"");
    if(version.exit()!=0||!version.output().matches("(?s).*\\bSSPI\\b.*"))throw new IllegalArgumentException("Windows login requires a Windows curl.exe build with SSPI support. Run curl.exe --version on the application host.");
    String protocol=config.path("windowsProtocol").asText("NEGOTIATE");
    if(protocol.equals("NTLM")&&!version.output().matches("(?s).*\\bNTLM\\b.*"))throw new IllegalArgumentException("This curl.exe build does not support NTLM");
    return Map.of("ready",true,"protocol",protocol,"identity",config.path("windowsUseCurrentUser").asBoolean(true)?"Application process Windows account":"Configured Windows account","destinationAuthenticated",false);
  }
  public JsonNode call(ObjectNode config,URI uri,String method,JsonNode payload) {
    validate(config,uri);check(config);
    if(!Set.of("GET","POST","PUT","PATCH","DELETE","HEAD","OPTIONS").contains(method))throw new IllegalArgumentException("Unsupported Windows connector HTTP method");
    String credentials=":";
    if(!config.path("windowsUseCurrentUser").asBoolean(true)) {
      String password=vault.get(config.path("secretRef").asText());if(password.isBlank())throw new IllegalArgumentException("Save the Windows password in the vault first");
      credentials=config.path("windowsUsername").asText()+":"+password;
    }
    int port=uri.getPort()==-1?443:uri.getPort();
    var text=new StringBuilder("silent\nshow-error\nconnect-timeout = 10\nmax-time = 30\nmax-filesize = 2097152\nproto = \"=https\"\nproxy = \"\"\nnoproxy = \"*\"\n");
    text.append(config.path("windowsProtocol").asText("NEGOTIATE").equals("NTLM")?"ntlm\n":"negotiate\n");
    text.append(option("user",credentials)).append(option("url",uri.toString())).append(option("resolve",uri.getHost()+":"+port+":"+pinnedAddress(uri.getHost())));
    text.append(option("request",method)).append(option("header","Accept: application/json")).append(option("header","Content-Type: application/json"));
    if(method.equals("HEAD"))text.append("head\n");
    if(payload!=null&&!method.equals("HEAD"))text.append(option("data",payload.toString()));
    text.append(option("write-out",STATUS+"%{http_code}"));
    long started = System.nanoTime();
    outbound.start("connector-windows", method, uri, "windows", false, true, false);
    Output result;
    try {
      result = run(List.of(executable,"--disable","--config","-"),text.toString());
    } catch (RuntimeException error) {
      outbound.result(ApiRequestLog.requestId(), "connector-windows", method, uri, 0, "not_inspected", started, OutboundLog.failure(error));
      throw error;
    }
    if (result.exit() != 0) outbound.result(ApiRequestLog.requestId(), "connector-windows", method, uri, 0, "not_inspected", started, "windows_curl_failed");
    if(result.exit()!=0)throw new IllegalArgumentException("Windows authentication request failed. Check VPN, certificate trust, account permissions and curl SSPI support.");
    int split=result.output().lastIndexOf(STATUS);if(split<0)throw new IllegalArgumentException("Windows connector returned an invalid response");
    int status;try{status=Integer.parseInt(result.output().substring(split+STATUS.length()).trim());}catch(NumberFormatException e){throw new IllegalArgumentException("Windows connector returned an invalid HTTP status");}
    outbound.result(ApiRequestLog.requestId(), "connector-windows", method, uri, status, "not_inspected", started, OutboundLog.statusReason(status));
    if(status<200||status>=300)throw new IllegalArgumentException("Windows destination returned HTTP "+status+". Check its authentication protocol and account access; redirects are not followed.");
    String body=result.output().substring(0,split);if(body.isBlank())return JsonNodeFactory.instance.objectNode();
    try{return new ObjectMapper().readTree(body);}catch(Exception e){return TextNode.valueOf(body);}
  }
  static String option(String key,String value) {
    if(value.indexOf('\0')>=0)throw new IllegalArgumentException("NUL characters are not supported in Windows requests");
    return key+" = \""+value.replace("\\","\\\\").replace("\"","\\\"").replace("\r","\\r").replace("\n","\\n").replace("\t","\\t")+"\"\n";
  }
  protected record Output(int exit,String output){}
  protected Output run(List<String> command,String input) {
    Process process=null;
    try {
      process=new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();final Process child=process;
      var output=CompletableFuture.supplyAsync(()->{try{byte[] bytes=child.getInputStream().readNBytes(LIMIT+1);if(bytes.length>LIMIT){child.destroyForcibly();throw new IllegalArgumentException("Windows response exceeded 2 MB");}return new String(bytes,StandardCharsets.UTF_8);}catch(java.io.IOException e){throw new CompletionException(e);}});
      try(var stdin=process.getOutputStream()){stdin.write(input.getBytes(StandardCharsets.UTF_8));}
      if(!process.waitFor(35,TimeUnit.SECONDS)){process.destroyForcibly();throw new IllegalArgumentException("Windows request timed out");}
      return new Output(process.exitValue(),output.get(2,TimeUnit.SECONDS));
    }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalArgumentException("Windows request interrupted");}
    catch(IllegalArgumentException e){throw e;}
    catch(Exception e){throw new IllegalArgumentException("Windows authentication runtime unavailable or request failed. Configure WINDOWS_CURL and verify curl.exe --version.");}
    finally{if(process!=null&&process.isAlive())process.destroyForcibly();}
  }
}
