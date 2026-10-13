package com.softitrend.office;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterAll;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import static org.assertj.core.api.Assertions.assertThat;
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={"spring.profiles.active=test"})
class ManagementHttpTest {
 static final AtomicReference<String> received=new AtomicReference<>();
 static final AtomicReference<String> receivedBody=new AtomicReference<>();
 static volatile int responseStatus=200;
 static volatile String responseBody="[{\"STORE\":\"MixedCase\"}]";
 static volatile int delay=0;
 static volatile boolean jsonContentType=true;
 static final java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();
 static final HttpServer upstream=createServer();
 @org.junit.jupiter.api.BeforeEach void reset() { responseStatus=200; responseBody="[{\"STORE\":\"MixedCase\"}]"; delay=0; jsonContentType=true; }

 static HttpServer createServer() {
  try {
   var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
   server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool(r -> { var t=new Thread(r,"fake-upstream"); t.setDaemon(true); return t; }));
   server.createContext("/", e -> {
    received.set(e.getRequestMethod()+" "+e.getRequestURI()+" "+e.getRequestHeaders().getFirst("X-Management-Secret")+" "+e.getRequestHeaders().getFirst("Authorization"));
    receivedBody.set(new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
    calls.incrementAndGet();
    if(responseStatus==0) { e.close(); return; }
    try { if(delay>0) Thread.sleep(delay); } catch(InterruptedException ex) { Thread.currentThread().interrupt(); }
    byte[] body=responseBody.getBytes(StandardCharsets.UTF_8);
    if(jsonContentType) e.getResponseHeaders().set("Content-Type","application/json");
    if(responseStatus==302) e.getResponseHeaders().set("Location","http://127.0.0.1:"+server.getAddress().getPort()+"/redirected");
    e.sendResponseHeaders(responseStatus,responseStatus==204?-1:body.length);
    if(responseStatus!=204) e.getResponseBody().write(body); e.close();
   }); server.start(); return server;
  } catch(Exception e) { throw new ExceptionInInitializerError(e); }
 }
 @DynamicPropertySource static void config(DynamicPropertyRegistry r) {
  r.add("office.operator.username", () -> "operator-test");
  r.add("office.operator.password-hash", () -> new BCryptPasswordEncoder(4).encode("synthetic-password-only"));
  r.add("office.upstream.base-url", () -> "http://127.0.0.1:"+upstream.getAddress().getPort());
  r.add("office.upstream.secret", () -> "synthetic-management-secret");
  r.add("office.upstream.timeout-ms", () -> "300");
  r.add("office.login.max-attempts", () -> "100");
 }
 @org.junit.jupiter.params.ParameterizedTest
 @org.junit.jupiter.params.provider.CsvSource({
  "GET,brands,", "PUT,brands/aliases,", "POST,reservations,",
  "GET,waitlist,?store=MixedCase", "POST,waitlist,", "GET,waitlist/responses,?store=MixedCase", "POST,waitlist/responses,",
  "POST,promos/00000000-0000-0000-0000-000000000001/reports,",
  "POST,promos/00000000-0000-0000-0000-000000000001/fraud-removal,",
  "PUT,promos/00000000-0000-0000-0000-000000000001/resolve,",
  "GET,accounts/00000000-0000-0000-0000-000000000001/stats,?from=2026-10-01&to=2026-10-10",
  "GET,accounts/00000000-0000-0000-0000-000000000001/moderation,",
  "PUT,accounts/00000000-0000-0000-0000-000000000001/moderation,",
  "DELETE,accounts/00000000-0000-0000-0000-000000000001/moderation,",
  "PUT,accounts/00000000-0000-0000-0000-000000000001/ban,",
  "DELETE,accounts/00000000-0000-0000-0000-000000000001/ban,",
  "POST,accounts/00000000-0000-0000-0000-000000000001/profile,",
  "GET,outbox,", "PUT,outbox/00000000-0000-0000-0000-000000000001/sent,",
  "GET,email-deliveries,", "POST,jobs/run,", "GET,accounts,?page=0&size=20&q=MixedCase",
  "GET,promos,?page=0&size=20&q=MixedCase", "GET,incidents,?page=0&size=20"
 })
 void forwardsOnlyDeclaredCapabilitiesWithoutChangingPathOrCode(String method,String suffix,String query) {
  var b=new HttpBrowser(port); b.login(); query=query==null?"":query;
  var request=b.client.method(org.springframework.http.HttpMethod.valueOf(method)).uri("/office-api/management/"+suffix+query)
   .cookie("SESSION",b.cookie).header("X-CSRF-TOKEN",b.token);
  if(method.equals("POST")||method.equals("PUT")) request.contentType(org.springframework.http.MediaType.APPLICATION_JSON).bodyValue("{\"code\":\"MixedCase\"}");
  request.exchange().expectStatus().isOk().expectBody();
  assertThat(received.get()).isEqualTo(method+" /api/management/"+suffix+query+" synthetic-management-secret null");
  assertThat(receivedBody.get()).isEqualTo(method.equals("POST")||method.equals("PUT")?"{\"code\":\"MixedCase\"}":"");
 }
 @Test void rejectsUnknownQueryBeforeContactingFeezit() {
  var b=new HttpBrowser(port); b.login(); received.set("untouched");
  b.client.get().uri("/office-api/management/brands?url=https://attacker.invalid").cookie("SESSION",b.cookie)
   .exchange().expectStatus().isBadRequest().expectBody();
  assertThat(received.get()).isEqualTo("untouched");
 }
 @Test void preservesConflictButNeverExternalMessageOrSecret() {
  var b=new HttpBrowser(port); b.login(); responseStatus=409;
  responseBody="{\"status\":409,\"code\":\"CONFLICT\",\"message\":\"synthetic-management-secret http://private.example\"}";
  b.client.get().uri("/office-api/management/brands").cookie("SESSION",b.cookie).exchange().expectStatus().isEqualTo(409)
   .expectBody().jsonPath("$.code").isEqualTo("CONFLICT").jsonPath("$.message").isEqualTo("Opération refusée par Feezit");
 }
 @org.junit.jupiter.params.ParameterizedTest
 @org.junit.jupiter.params.provider.ValueSource(strings={"not json", "{\"echo\":\"synthetic-management-secret\"}","large"})
 void rejectsUnsafeSuccessBodies(String external) {
  var b=new HttpBrowser(port); b.login(); responseBody=external.equals("large")?"[\""+"a".repeat(300000)+"\"]":external;
  b.client.get().uri("/office-api/management/brands").cookie("SESSION",b.cookie).exchange().expectStatus().isEqualTo(502)
   .expectBody().jsonPath("$.code").isEqualTo("UPSTREAM_UNAVAILABLE");
 }
 @Test void refusesRedirectInsteadOfFollowingIt() {
  var b=new HttpBrowser(port); b.login(); responseStatus=302; int before=calls.get();
  b.client.get().uri("/office-api/management/brands").cookie("SESSION",b.cookie).exchange().expectStatus().isEqualTo(502).expectBody();
  assertThat(calls.get()).isEqualTo(before+1);
 }
 @Test void mapsNetworkFailureToSafe502() {
  var b=new HttpBrowser(port); b.login(); responseStatus=0;
  b.client.get().uri("/office-api/management/brands").cookie("SESSION",b.cookie).exchange().expectStatus().isEqualTo(502)
   .expectBody().jsonPath("$.code").isEqualTo("UPSTREAM_UNAVAILABLE");
 }
 @Test void boundsWholeUpstreamExchangeWith504() {
  var b=new HttpBrowser(port); b.login(); delay=700;
  b.client.get().uri("/office-api/management/brands").cookie("SESSION",b.cookie).exchange().expectStatus().isEqualTo(504)
   .expectBody().jsonPath("$.code").isEqualTo("UPSTREAM_TIMEOUT");
 }
 @org.junit.jupiter.params.ParameterizedTest
 @org.junit.jupiter.params.provider.ValueSource(strings={"waitlist", "waitlist?store=", "accounts?page=-1", "accounts?size=101", "accounts?q=a&q=b", "incidents?q=forbidden", "accounts/00000000-0000-0000-0000-000000000001/stats?from=2026-02-30", "brands?%75rl=forbidden"})
 void refusesInvalidQueriesWithoutForwarding(String suffix) {
  var b=new HttpBrowser(port); b.login(); received.set("untouched");
  b.client.get().uri(java.net.URI.create("http://127.0.0.1:"+port+"/office-api/management/"+suffix)).cookie("SESSION",b.cookie)
    .exchange().expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("VALIDATION");
  assertThat(received.get()).isEqualTo("untouched");
 }
 @org.junit.jupiter.params.ParameterizedTest
 @org.junit.jupiter.params.provider.ValueSource(strings={"/office-api/management/brands/extra", "/office-api/management/accounts/not-uuid/moderation", "/office-api/management/%62rands", "/office-api/management/brands%2f..%2freservations", "/office-api/management/brands;extra", "/office-api/management//brands", "/office-api/catalog"})
 void refusesUndeclaredRawPaths(String path) {
  var b=new HttpBrowser(port); b.login(); received.set("untouched");
  b.client.get().uri(java.net.URI.create("http://127.0.0.1:"+port+path)).cookie("SESSION",b.cookie)
    .exchange().expectStatus().is4xxClientError().expectBody();
  assertThat(received.get()).isEqualTo("untouched");
 }
 @Test void rejectsMalformedPayloadRatherThanForwardingAsJson() {
  var b=new HttpBrowser(port); b.login(); received.set("untouched");
  b.client.post().uri("/office-api/management/reservations").cookie("SESSION",b.cookie).header("X-CSRF-TOKEN",b.token)
   .contentType(org.springframework.http.MediaType.APPLICATION_JSON).bodyValue("not-json").exchange().expectStatus().isBadRequest().expectBody();
  assertThat(received.get()).isEqualTo("untouched");
 }
 @Test void rejectsNonJsonMutationContentType() {
  var b=new HttpBrowser(port); b.login(); received.set("untouched");
  b.client.post().uri("/office-api/management/reservations").cookie("SESSION",b.cookie).header("X-CSRF-TOKEN",b.token)
   .contentType(org.springframework.http.MediaType.TEXT_PLAIN).bodyValue("{}").exchange().expectStatus().isEqualTo(415).expectBody();
  assertThat(received.get()).isEqualTo("untouched");
 }
 @Test void boundsIncomingBodyWithoutCallingFeezit() {
  var b=new HttpBrowser(port); b.login(); received.set("untouched");
  b.client.post().uri("/office-api/management/reservations").cookie("SESSION",b.cookie).header("X-CSRF-TOKEN",b.token)
   .contentType(org.springframework.http.MediaType.APPLICATION_JSON).bodyValue("{\"code\":\""+"a".repeat(300000)+"\"}")
   .exchange().expectStatus().isEqualTo(413).expectBody().jsonPath("$.code").isEqualTo("PAYLOAD_TOO_LARGE");
  assertThat(received.get()).isEqualTo("untouched");
 }
 @Test void requiresCsrfForManagementMutationAndNeverUsesBrowserSecretWithoutSession() {
  var b=new HttpBrowser(port); b.login(); received.set("untouched");
  b.client.post().uri("/office-api/management/jobs/run").cookie("SESSION",b.cookie).exchange().expectStatus().isForbidden().expectBody();
  b.client.get().uri("/office-api/management/brands").header("X-Management-Secret","synthetic-management-secret")
   .exchange().expectStatus().isUnauthorized().expectBody();
  assertThat(received.get()).isEqualTo("untouched");
 }
 @Test void queryValueCannotInjectAnotherParameter() {
  var b=new HttpBrowser(port); b.login();
  b.client.get().uri(java.net.URI.create("http://127.0.0.1:"+port+"/office-api/management/waitlist?store=Mixed%26evil%3D1%2B%7Bx%7D"))
   .cookie("SESSION",b.cookie).exchange().expectStatus().isOk().expectBody();
  assertThat(received.get()).isEqualTo("GET /api/management/waitlist?store=Mixed%26evil%3D1%2B%7Bx%7D synthetic-management-secret null");
 }
 @org.junit.jupiter.params.ParameterizedTest
 @org.junit.jupiter.params.provider.ValueSource(ints={201,202,204})
 void preservesSuccessfulBusinessStatuses(int status) {
  var b=new HttpBrowser(port); b.login(); responseStatus=status;
  var response=b.client.post().uri("/office-api/management/reservations").cookie("SESSION",b.cookie).header("X-CSRF-TOKEN",b.token)
   .contentType(org.springframework.http.MediaType.APPLICATION_JSON).bodyValue("{\"code\":\"MixedCase\"}")
   .exchange().expectStatus().isEqualTo(status);
  if(status==204) response.expectBody().isEmpty(); else response.expectBody().json("[{\"STORE\":\"MixedCase\"}]");
 }
 @Test void preservesAcceptedEmptyReportWithoutContentType() {
  var b=new HttpBrowser(port); b.login(); responseStatus=202; responseBody=""; jsonContentType=false;
  b.client.post().uri("/office-api/management/promos/00000000-0000-0000-0000-000000000001/reports")
   .cookie("SESSION",b.cookie).header("X-CSRF-TOKEN",b.token)
   .contentType(org.springframework.http.MediaType.APPLICATION_JSON).bodyValue("{\"reporterEmail\":\"synthetic@example.test\"}")
   .exchange().expectStatus().isEqualTo(202).expectBody().isEmpty();
 }
 @Test void acceptedNonemptyReportStillRequiresJsonContentType() {
  var b=new HttpBrowser(port); b.login(); responseStatus=202; responseBody="{}"; jsonContentType=false;
  b.client.post().uri("/office-api/management/promos/00000000-0000-0000-0000-000000000001/reports")
   .cookie("SESSION",b.cookie).header("X-CSRF-TOKEN",b.token)
   .contentType(org.springframework.http.MediaType.APPLICATION_JSON).bodyValue("{}")
   .exchange().expectStatus().isEqualTo(502).expectBody().jsonPath("$.code").isEqualTo("UPSTREAM_UNAVAILABLE");
 }
 @Test void acceptedReportWithMalformedJsonIsNotFalselyReportedAsSuccess() {
  var b=new HttpBrowser(port); b.login(); responseStatus=202; responseBody="not-json"; jsonContentType=true;
  b.client.post().uri("/office-api/management/promos/00000000-0000-0000-0000-000000000001/reports")
   .cookie("SESSION",b.cookie).header("X-CSRF-TOKEN",b.token)
   .contentType(org.springframework.http.MediaType.APPLICATION_JSON).bodyValue("{}")
   .exchange().expectStatus().isEqualTo(502).expectBody().jsonPath("$.code").isEqualTo("UPSTREAM_UNAVAILABLE");
 }
 @Test void acceptedEmptyReservationDoesNotUseReportException() {
  var b=new HttpBrowser(port); b.login(); responseStatus=202; responseBody=""; jsonContentType=false;
  b.client.post().uri("/office-api/management/reservations")
   .cookie("SESSION",b.cookie).header("X-CSRF-TOKEN",b.token)
   .contentType(org.springframework.http.MediaType.APPLICATION_JSON).bodyValue("{}")
   .exchange().expectStatus().isEqualTo(502).expectBody().jsonPath("$.code").isEqualTo("UPSTREAM_UNAVAILABLE");
 }
 @Test void emptyCatalogReplyIsNotFalselyReportedAsSuccess() {
  var b=new HttpBrowser(port); b.login(); responseBody=""; jsonContentType=false;
  b.client.get().uri("/office-api/management/brands").cookie("SESSION",b.cookie)
   .exchange().expectStatus().isEqualTo(502).expectBody().jsonPath("$.code").isEqualTo("UPSTREAM_UNAVAILABLE");
 }
 static final String PROMO_PATH="/office-api/management/promos/00000000-0000-0000-0000-000000000001";
 @org.junit.jupiter.params.ParameterizedTest
 @org.junit.jupiter.params.provider.ValueSource(strings={"draft","masked"})
 void readsInternalPromoWithoutChangingPublicationStatus(String status) {
  var b=new HttpBrowser(port); b.login();
  responseBody="{\"id\":\"00000000-0000-0000-0000-000000000001\",\"status\":\""+status+"\",\"articleDescription\":\"MixedCase\",\"articleImage\":\"\",\"endDate\":null}";
  b.client.get().uri(PROMO_PATH).cookie("SESSION",b.cookie)
   .header("Authorization","Bearer browser-value").header("X-Management-Secret","browser-value")
   .exchange().expectStatus().isOk().expectHeader().valueEquals("Cache-Control","no-store")
   .expectBody().json(responseBody);
  assertThat(received.get()).isEqualTo("GET /api/management/promos/00000000-0000-0000-0000-000000000001 synthetic-management-secret null");
  assertThat(receivedBody.get()).isEmpty();
 }
 @org.junit.jupiter.params.ParameterizedTest
 @org.junit.jupiter.params.provider.ValueSource(strings={"{\"articleDescription\":\"MixedCase é\",\"articleImage\":\"https://images.example.test/a.png?x=1&y=2\"}","{\"articleDescription\":\"\",\"articleImage\":\"\"}"})
 void updatesOnlyPromoContentAndPreservesDraftDto(String payload) {
  var b=new HttpBrowser(port); b.login();
  responseBody="{\"status\":\"draft\",\"articleDescription\":\"MixedCase é\",\"articleImage\":\"\",\"startDate\":null,\"endDate\":null}";
  b.client.put().uri(PROMO_PATH+"/content").cookie("SESSION",b.cookie).header("X-CSRF-TOKEN",b.token)
   .header("Authorization","Bearer browser-value").header("X-Management-Secret","browser-value")
   .contentType(org.springframework.http.MediaType.APPLICATION_JSON).bodyValue(payload)
   .exchange().expectStatus().isOk().expectHeader().valueEquals("Cache-Control","no-store").expectBody().json(responseBody);
  assertThat(received.get()).isEqualTo("PUT /api/management/promos/00000000-0000-0000-0000-000000000001/content synthetic-management-secret null");
  assertThat(receivedBody.get()).isEqualTo(payload);
 }
 @Test void promoContentReservationFieldsAreForwardedWithoutPublishingOrChangingDates() {
  var b=new HttpBrowser(port); b.login(); responseStatus=201;
  responseBody="{\"status\":\"draft\",\"startDate\":null,\"endDate\":null}";
  for(String payload:java.util.List.of("{\"ownerId\":\"00000000-0000-0000-0000-000000000002\",\"code\":\"MixedCase\"}",
    "{\"ownerId\":\"00000000-0000-0000-0000-000000000002\",\"code\":\"MixedCase\",\"articleDescription\":\"Un article é\",\"articleImage\":\"http://images.example.test/a.png\"}")) {
   b.client.post().uri("/office-api/management/reservations").cookie("SESSION",b.cookie).header("X-CSRF-TOKEN",b.token)
    .contentType(org.springframework.http.MediaType.APPLICATION_JSON).bodyValue(payload)
    .exchange().expectStatus().isCreated().expectBody().json(responseBody);
   assertThat(receivedBody.get()).isEqualTo(payload);
  }
 }
 @Test void promoReadsAndContentRejectExtraOrDuplicateQueriesBeforeUpstream() {
  var b=new HttpBrowser(port); b.login(); int before=calls.get();
  for(String method:java.util.List.of("GET","PUT")) {
   String path=PROMO_PATH+(method.equals("PUT")?"/content":"");
   for(String query:java.util.List.of("?page=0","?articleImage=https://attacker.invalid","?q=a&q=b","?%75rl=forbidden")) {
    b.client.method(org.springframework.http.HttpMethod.valueOf(method))
     .uri(java.net.URI.create("http://127.0.0.1:"+port+path+query)).cookie("SESSION",b.cookie).header("X-CSRF-TOKEN",b.token)
     .exchange().expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("VALIDATION");
   }
  }
  assertThat(calls.get()).isEqualTo(before);
 }
 @Test void promoWhitelistNeverAllowsOtherMethodsOrArbitrarySubroutes() {
  var b=new HttpBrowser(port); b.login(); int before=calls.get();
  for(String method:java.util.List.of("GET","PUT","POST","DELETE","PATCH","HEAD","OPTIONS")) {
   for(String suffix:java.util.List.of("","/content")) {
    if(method.equals("GET")&&suffix.isEmpty()||method.equals("PUT")&&suffix.equals("/content")) continue;
    b.client.method(org.springframework.http.HttpMethod.valueOf(method)).uri(PROMO_PATH+suffix)
     .cookie("SESSION",b.cookie).header("X-CSRF-TOKEN",b.token)
     .exchange().expectStatus().isForbidden().expectBody();
   }
  }
  for(String path:java.util.List.of("/office-api/management/promos/not-uuid","/office-api/management/promos/not-uuid/content",
    PROMO_PATH+"/content/extra",PROMO_PATH+"/extra",PROMO_PATH+"/content/",PROMO_PATH.replace("promos","%70romos"),PROMO_PATH+"%2fcontent")) {
   for(String method:java.util.List.of("GET","PUT")) {
    b.client.method(org.springframework.http.HttpMethod.valueOf(method)).uri(java.net.URI.create("http://127.0.0.1:"+port+path))
     .cookie("SESSION",b.cookie).header("X-CSRF-TOKEN",b.token)
     .exchange().expectStatus().is4xxClientError().expectBody();
   }
  }
  assertThat(calls.get()).isEqualTo(before);
 }
 @Test void promoContentRequiresOperatorSessionAndValidCsrf() {
  var anonymous=new HttpBrowser(port); anonymous.csrf(); int before=calls.get();
  anonymous.client.get().uri(PROMO_PATH).header("Authorization","Bearer creator-token")
   .header("X-Management-Secret","synthetic-management-secret").exchange().expectStatus().isUnauthorized().expectBody();
  anonymous.client.put().uri(PROMO_PATH+"/content").cookie("SESSION",anonymous.cookie).header("X-CSRF-TOKEN",anonymous.token)
   .header("Authorization","Bearer creator-token").header("X-Management-Secret","synthetic-management-secret")
   .contentType(org.springframework.http.MediaType.APPLICATION_JSON).bodyValue("{}").exchange().expectStatus().isUnauthorized().expectBody();
  var b=new HttpBrowser(port); b.login();
  b.client.put().uri(PROMO_PATH+"/content").cookie("SESSION",b.cookie)
   .contentType(org.springframework.http.MediaType.APPLICATION_JSON).bodyValue("{}").exchange().expectStatus().isForbidden().expectBody();
  b.client.put().uri(PROMO_PATH+"/content").cookie("SESSION",b.cookie).header("X-CSRF-TOKEN","invalid-token")
   .contentType(org.springframework.http.MediaType.APPLICATION_JSON).bodyValue("{}").exchange().expectStatus().isForbidden().expectBody();
  b.client.post().uri("/office-api/auth/logout").cookie("SESSION",b.cookie).header("X-CSRF-TOKEN",b.token)
   .exchange().expectStatus().isNoContent().expectBody().isEmpty();
  b.client.get().uri(PROMO_PATH).cookie("SESSION",b.cookie).exchange().expectStatus().isUnauthorized().expectBody();
  assertThat(calls.get()).isEqualTo(before);
 }
 @org.junit.jupiter.params.ParameterizedTest
 @org.junit.jupiter.params.provider.ValueSource(strings={"not-json","[]","null","{} {}"})
 void promoContentRetainsJsonObjectEnvelopePolicy(String payload) {
  var b=new HttpBrowser(port); b.login(); int before=calls.get();
  b.client.put().uri(PROMO_PATH+"/content").cookie("SESSION",b.cookie).header("X-CSRF-TOKEN",b.token)
   .contentType(org.springframework.http.MediaType.APPLICATION_JSON).bodyValue(payload)
   .exchange().expectStatus().isBadRequest().expectBody();
  assertThat(calls.get()).isEqualTo(before);
 }
 @org.junit.jupiter.params.ParameterizedTest
 @org.junit.jupiter.params.provider.CsvSource({"GET,404,NOT_FOUND","PUT,400,VALIDATION","PUT,409,CONFLICT","PUT,422,UPSTREAM_REJECTED","GET,500,UPSTREAM_UNAVAILABLE","PUT,500,UPSTREAM_UNAVAILABLE"})
 void promoEndpointsPreserveSafeUpstreamErrors(String method,int status,String expectedCode) {
  var b=new HttpBrowser(port); b.login(); responseStatus=status;
  responseBody="{\"status\":"+status+",\"code\":\"CONFLICT\",\"message\":\"synthetic-management-secret http://private.example\"}";
  var request=b.client.method(org.springframework.http.HttpMethod.valueOf(method)).uri(PROMO_PATH+(method.equals("PUT")?"/content":""))
   .cookie("SESSION",b.cookie).header("X-CSRF-TOKEN",b.token);
  if(method.equals("PUT")) request.contentType(org.springframework.http.MediaType.APPLICATION_JSON).bodyValue("{}");
  request.exchange().expectStatus().isEqualTo(status<500?status:502).expectBody()
   .jsonPath("$.code").isEqualTo(expectedCode)
   .jsonPath("$.message").isEqualTo(status<500?"Opération refusée par Feezit":"Feezit temporairement indisponible");
  assertThat(receivedBody.get()).isEqualTo(method.equals("PUT")?"{}":"");
 }
 @Test void promoContentRejectsWrongContentTypeAndOversizedBodyBeforeUpstream() {
  var b=new HttpBrowser(port); b.login(); int before=calls.get();
  b.client.put().uri(PROMO_PATH+"/content").cookie("SESSION",b.cookie).header("X-CSRF-TOKEN",b.token)
   .contentType(org.springframework.http.MediaType.TEXT_PLAIN).bodyValue("{}")
   .exchange().expectStatus().isEqualTo(415).expectBody();
  b.client.put().uri(PROMO_PATH+"/content").cookie("SESSION",b.cookie).header("X-CSRF-TOKEN",b.token)
   .contentType(org.springframework.http.MediaType.APPLICATION_JSON).bodyValue("{\"articleDescription\":\""+"a".repeat(300000)+"\",\"articleImage\":\"\"}")
   .exchange().expectStatus().isEqualTo(413).expectBody().jsonPath("$.code").isEqualTo("PAYLOAD_TOO_LARGE");
  assertThat(calls.get()).isEqualTo(before);
 }
 @Test void promoReadsAndContentNeverReportUnsafeUpstreamResponsesAsSuccess() {
  var b=new HttpBrowser(port); b.login();
  for(String method:java.util.List.of("GET","PUT")) {
   for(String external:java.util.List.of("", "   ", "not-json", "{\"echo\":\"synthetic-management-secret\"}")) {
    responseBody=external;
    var request=b.client.method(org.springframework.http.HttpMethod.valueOf(method)).uri(PROMO_PATH+(method.equals("PUT")?"/content":""))
     .cookie("SESSION",b.cookie).header("X-CSRF-TOKEN",b.token);
    if(method.equals("PUT")) request.contentType(org.springframework.http.MediaType.APPLICATION_JSON).bodyValue("{\"articleDescription\":\"\",\"articleImage\":\"\"}");
    request.exchange().expectStatus().isEqualTo(502).expectBody().jsonPath("$.code").isEqualTo("UPSTREAM_UNAVAILABLE");
   }
  }
 }
 @Test void promoReadsAndContentRetainUpstreamTimeoutPolicy() {
  var b=new HttpBrowser(port); b.login(); delay=700;
  for(String method:java.util.List.of("GET","PUT")) {
   var request=b.client.method(org.springframework.http.HttpMethod.valueOf(method)).uri(PROMO_PATH+(method.equals("PUT")?"/content":""))
    .cookie("SESSION",b.cookie).header("X-CSRF-TOKEN",b.token);
   if(method.equals("PUT")) request.contentType(org.springframework.http.MediaType.APPLICATION_JSON).bodyValue("{\"articleDescription\":\"\",\"articleImage\":\"\"}");
   request.exchange().expectStatus().isEqualTo(504).expectBody().jsonPath("$.code").isEqualTo("UPSTREAM_TIMEOUT");
  }
 }
 @AfterAll static void stop() { upstream.stop(0); }
 @LocalServerPort int port;
 @Test void authenticatedBrandsUsesFixedServerSecretAndPreservesJson() {
  var b=new HttpBrowser(port); b.login();
  b.client.get().uri("/office-api/management/brands").cookie("SESSION",b.cookie).header("Authorization","Bearer browser-secret")
   .header("X-Management-Secret","browser-value").exchange().expectStatus().isOk().expectHeader().valueEquals("Cache-Control","no-store")
   .expectBody().json("[{\"STORE\":\"MixedCase\"}]");
  assertThat(received.get()).isEqualTo("GET /api/management/brands synthetic-management-secret null");
 }
}
