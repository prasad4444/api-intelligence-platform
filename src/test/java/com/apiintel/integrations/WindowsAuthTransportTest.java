package com.apiintel.integrations;
import com.apiintel.network.NetworkGuard;
import com.apiintel.security.Vault;
import com.apiintel.store.RecordStore;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.net.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.springframework.test.util.ReflectionTestUtils;
class WindowsAuthTransportTest {
  final ObjectMapper json=new ObjectMapper();Vault vault;Fake windows;ObjectNode config;
  class Fake extends WindowsAuthTransport {
    String features="curl 8.0 Features: SSPI SPNEGO NTLM HTTPS-proxy";
    String response="{\"ok\":true}\n__APIINTEL_HTTP_STATUS__200";
    String input="",address="10.20.30.40";List<String> args;int exit;
    Fake(){super(vault,new NetworkGuard(),"win.internal.example","curl.exe");}
    @Override protected InetAddress[] addresses(String host)throws UnknownHostException{return new InetAddress[]{InetAddress.getByName(address)};}
    @Override protected Output run(List<String> args,String input){this.args=args;if(args.contains("--version"))return new Output(0,features);this.input=input;return new Output(exit,response);}
  }
  @BeforeEach void setup(){vault=mock(Vault.class);windows=new Fake();config=json.createObjectNode().put("baseUrl","https://win.internal.example").put("authType","WINDOWS").put("windowsProtocol","NEGOTIATE").put("enabled",true);}
  @Test void usesCurrentWindowsAccountWithoutReadingVault(){var result=windows.call(config,URI.create("https://win.internal.example/api"),"GET",null);assertTrue(result.path("ok").asBoolean());assertTrue(windows.input.contains("negotiate\n"));assertTrue(windows.input.contains("user = \":\""));assertTrue(windows.input.contains("win.internal.example:443:10.20.30.40"));assertTrue(windows.input.contains("proxy = \"\""));assertFalse(windows.input.contains("location"));assertEquals(List.of("curl.exe","--disable","--config","-"),windows.args);verifyNoInteractions(vault);}
  @Test void explicitAccountUsesEncryptedPasswordOnlyOnStdin(){config.put("windowsUseCurrentUser",false).put("windowsUsername","DOMAIN\\user").put("secretRef","windows-password").put("windowsProtocol","NTLM");when(vault.get("windows-password")).thenReturn("a\"b\\c\nnext");windows.call(config,URI.create("https://win.internal.example/api"),"POST",json.createObjectNode().put("query","test"));assertTrue(windows.input.contains("ntlm\n"));assertTrue(windows.input.contains("DOMAIN\\\\user"));assertTrue(windows.input.contains("a\\\"b\\\\c\\nnext"));assertFalse(windows.args.toString().contains("DOMAIN"));assertFalse(windows.args.toString().contains("next"));}
  @Test void requiresExplicitHostAndHttps(){assertThrows(IllegalArgumentException.class,()->windows.validate(config,URI.create("http://win.internal.example")));assertThrows(IllegalArgumentException.class,()->windows.validate(config,URI.create("https://other.example")));assertThrows(IllegalArgumentException.class,()->windows.validate(config,URI.create("https://u:p@win.internal.example")));}
  @Test void rejectsReservedAndMetadataEvenForAllowedHostname(){for(String ip:List.of("127.0.0.1","169.254.169.254","0.0.0.0","224.0.0.1")){windows.address=ip;assertThrows(IllegalArgumentException.class,()->windows.pinnedAddress("win.internal.example"));}}
  @Test void supportsPinnedIpv6PrivateDestination(){windows.address="fd00::123";assertTrue(windows.pinnedAddress("win.internal.example").startsWith("["));}
  @Test void checksRuntimeWithoutClaimingDestinationLogin(){assertEquals(false,windows.check(config).get("destinationAuthenticated"));windows.features="curl Features: SSL NTLM";assertThrows(IllegalArgumentException.class,()->windows.check(config));windows.features="curl Features: SSPI";config.put("windowsProtocol","NTLM");assertThrows(IllegalArgumentException.class,()->windows.check(config));}
  @Test void validatesExplicitIdentityAndProtocol(){config.put("windowsProtocol","BASIC");assertThrows(IllegalArgumentException.class,()->windows.validate(config,URI.create(config.path("baseUrl").asText())));config.put("windowsProtocol","NEGOTIATE").put("windowsUseCurrentUser",false);assertThrows(IllegalArgumentException.class,()->windows.validate(config,URI.create(config.path("baseUrl").asText())));config.put("windowsUsername","DOMAIN\\user");assertThrows(IllegalArgumentException.class,()->windows.validate(config,URI.create(config.path("baseUrl").asText())));}
  @Test void doesNotLeakProviderErrorBodiesOrPasswords(){windows.response="private token error\n__APIINTEL_HTTP_STATUS__401";var e=assertThrows(IllegalArgumentException.class,()->windows.call(config,URI.create("https://win.internal.example/api"),"GET",null));assertTrue(e.getMessage().contains("401"));assertFalse(e.getMessage().contains("private token"));windows.exit=1;assertThrows(IllegalArgumentException.class,()->windows.call(config,URI.create("https://win.internal.example/api"),"GET",null));}
  @Test void validatesResponseAndHandlesTextOrEmptyBodies(){windows.response="text\n__APIINTEL_HTTP_STATUS__200";assertEquals("text",windows.call(config,URI.create("https://win.internal.example"),"GET",null).asText());windows.response="\n__APIINTEL_HTTP_STATUS__204";assertTrue(windows.call(config,URI.create("https://win.internal.example"),"HEAD",null).isObject());assertTrue(windows.input.contains("head\n"));windows.response="invalid";assertThrows(IllegalArgumentException.class,()->windows.call(config,URI.create("https://win.internal.example"),"GET",null));}
  @Test void preventsConfigInjection(){String encoded=WindowsAuthTransport.option("user","u\"\nurl = \"https://evil.example\"");assertEquals(1,encoded.chars().filter(c->c=='\n').count());assertTrue(encoded.contains("\\nurl"));assertThrows(IllegalArgumentException.class,()->WindowsAuthTransport.option("user","x\0y"));}
  @Test void integrationCallsDispatchToWindowsTransportAndStreamingFailsExplicitly(){var store=mock(RecordStore.class);var transport=mock(WindowsAuthTransport.class);var integrations=new IntegrationService(store,vault,new NetworkGuard(),"win.internal.example", "http://localhost:18080");ReflectionTestUtils.setField(integrations,"windows",transport);when(transport.call(eq(config),any(),eq("GET"),isNull())).thenReturn(json.createObjectNode().put("ok",true));assertTrue(integrations.call(config,"GET","/api",null,"admin").path("ok").asBoolean());verify(transport).validate(eq(config),any());assertThrows(IllegalArgumentException.class,()->integrations.stream(config,"/chat",json.createObjectNode()));}
  @Test void missingNativeRuntimeFailsWithActionableError(){var transport=new WindowsAuthTransport(vault,new NetworkGuard(),"win.internal.example","api-intelligence-nonexistent-curl-test");var e=assertThrows(IllegalArgumentException.class,()->transport.check(config));assertTrue(e.getMessage().contains("WINDOWS_CURL"));}
  @Test void boundedProcessRunnerDoesNotInvokeAShell(){var transport=new WindowsAuthTransport(vault,new NetworkGuard(),"win.internal.example","curl.exe");var result=transport.run(List.of(java.nio.file.Path.of(System.getProperty("java.home"),"bin","java").toString(),"-version"),"");assertEquals(0,result.exit());}

}
