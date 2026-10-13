package com.softitrend.office;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.reactive.function.BodyInserters;
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"office.operator.username=","office.operator.password-hash=","office.upstream.base-url=","office.upstream.secret="})
class DisabledOperatorHttpTest {
 @LocalServerPort int port;
 @Test void defaultCookieIsSecureAndMissingCredentialsNeverGrantAccess() {
  var b=new HttpBrowser(port);
  b.client.get().uri("/office-api/csrf").exchange().expectStatus().isOk().expectCookie().secure("SESSION",true)
   .expectCookie().httpOnly("SESSION",true).expectCookie().sameSite("SESSION","Strict").expectBody();
  b.csrf();
  b.client.post().uri("/office-api/auth/login").cookie("SESSION",b.cookie).header("X-CSRF-TOKEN",b.token)
   .body(BodyInserters.fromFormData("username","user").with("password","synthetic-password-only"))
   .exchange().expectStatus().isUnauthorized().expectBody().isEmpty();
  b.client.get().uri("/office-api/session").cookie("SESSION",b.cookie).exchange().expectStatus().isUnauthorized().expectBody().isEmpty();
 }
}
