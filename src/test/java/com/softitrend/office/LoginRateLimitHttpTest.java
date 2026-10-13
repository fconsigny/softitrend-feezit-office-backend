package com.softitrend.office;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.reactive.function.BodyInserters;
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"spring.profiles.active=test","office.login.max-attempts=2","office.login.window-ms=1000"})
class LoginRateLimitHttpTest {
 @LocalServerPort int port;
 @Test void boundsLoginAttemptsAndAllowsAnotherWindow() throws InterruptedException {
  var b=new HttpBrowser(port); b.csrf();
  for(int i=0;i<2;i++) b.client.post().uri("/office-api/auth/login").cookie("SESSION",b.cookie).header("X-CSRF-TOKEN",b.token)
   .body(BodyInserters.fromFormData("username","unknown").with("password","synthetic-password-only"))
   .exchange().expectStatus().isUnauthorized().expectBody().isEmpty();
  b.client.post().uri("/office-api/auth/login").cookie("SESSION",b.cookie).header("X-CSRF-TOKEN",b.token)
   .body(BodyInserters.fromFormData("username","unknown").with("password","synthetic-password-only"))
   .exchange().expectStatus().isEqualTo(429).expectHeader().exists("Retry-After").expectBody().jsonPath("$.code").isEqualTo("RATE_LIMITED");
  Thread.sleep(1100);
  b.client.post().uri("/office-api/auth/login").cookie("SESSION",b.cookie).header("X-CSRF-TOKEN",b.token)
   .body(BodyInserters.fromFormData("username","unknown").with("password","synthetic-password-only"))
   .exchange().expectStatus().isUnauthorized().expectBody().isEmpty();
 }
}
