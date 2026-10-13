package com.softitrend.office;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.web.reactive.server.WebTestClient;
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={"spring.profiles.active=test"})
class SessionHttpTest {
 @org.springframework.test.context.DynamicPropertySource
 static void credentials(org.springframework.test.context.DynamicPropertyRegistry r) {
  r.add("office.operator.username", () -> "operator-test");
  r.add("office.operator.password-hash", () -> new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4).encode("synthetic-password-only"));
 }
 @LocalServerPort int port;
 private WebTestClient client() { return WebTestClient.bindToServer().baseUrl("http://127.0.0.1:"+port).build(); }
 private record Browser(String cookie, String token) {}
 private Browser csrf(String cookie) {
  var result=client().get().uri("/office-api/csrf").cookie("SESSION",cookie).exchange().expectStatus().isOk()
   .expectBody(java.util.Map.class).returnResult();
  var set=result.getResponseCookies().getFirst("SESSION");
  return new Browser(set==null?cookie:set.getValue(), (String)result.getResponseBody().get("token"));
 }
 @Test void loginWithSessionCsrfExposesOnlyOperatorAndLogoutInvalidatesCookie() {
  client().get().uri("/office-api/session").exchange().expectStatus().isUnauthorized().expectBody().isEmpty();
  var before=csrf("");
  client().post().uri("/office-api/auth/login").cookie("SESSION",before.cookie()).header("X-CSRF-TOKEN",before.token())
   .body(org.springframework.web.reactive.function.BodyInserters.fromFormData("username","operator-test").with("password","wrong-synthetic-password"))
   .exchange().expectStatus().isUnauthorized().expectBody().isEmpty();
  client().post().uri("/office-api/auth/login").cookie("SESSION",before.cookie())
   .body(org.springframework.web.reactive.function.BodyInserters.fromFormData("username","operator-test").with("password","synthetic-password-only"))
   .exchange().expectStatus().isForbidden().expectBody();
  var login=client().post().uri("/office-api/auth/login").cookie("SESSION",before.cookie()).header("X-CSRF-TOKEN",before.token())
   .body(org.springframework.web.reactive.function.BodyInserters.fromFormData("username","operator-test").with("password","synthetic-password-only"))
   .exchange().expectStatus().isNoContent().expectBody().isEmpty();
  var cookie=login.getResponseCookies().getFirst("SESSION").getValue();
  org.assertj.core.api.Assertions.assertThat(cookie).isNotEqualTo(before.cookie());
  client().get().uri("/office-api/session").cookie("SESSION",before.cookie()).exchange().expectStatus().isUnauthorized().expectBody().isEmpty();
  client().get().uri("/office-api/session").cookie("SESSION",cookie).exchange().expectStatus().isOk()
   .expectBody().json("{\"username\":\"operator-test\"}",org.springframework.test.json.JsonCompareMode.STRICT);
  client().post().uri("/office-api/auth/logout").cookie("SESSION",cookie).header("X-CSRF-TOKEN",before.token())
   .exchange().expectStatus().isForbidden().expectBody();
  var after=csrf(cookie);
  client().post().uri("/office-api/auth/logout").cookie("SESSION",after.cookie()).exchange().expectStatus().isForbidden().expectBody();
  client().post().uri("/office-api/auth/logout").cookie("SESSION",after.cookie()).header("X-CSRF-TOKEN",after.token())
   .exchange().expectStatus().isNoContent().expectBody().isEmpty();
  client().get().uri("/office-api/session").cookie("SESSION",cookie).exchange().expectStatus().isUnauthorized().expectBody().isEmpty();
 }

 @Test void publicCsrfCreatesHttpOnlyStrictSession() {
  WebTestClient.bindToServer().baseUrl("http://127.0.0.1:"+port).build().get().uri("/office-api/csrf").exchange()
   .expectStatus().isOk().expectCookie().httpOnly("SESSION",true).expectCookie().sameSite("SESSION","Strict")
   .expectBody().jsonPath("$.token").isNotEmpty().jsonPath("$.headerName").isEqualTo("X-CSRF-TOKEN");
 }
}
