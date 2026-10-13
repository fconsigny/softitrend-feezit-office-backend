package com.softitrend.office;
import java.util.Map;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
final class HttpBrowser {
 final WebTestClient client;
 String cookie="", token="";
 HttpBrowser(int port) { client=WebTestClient.bindToServer().baseUrl("http://127.0.0.1:"+port).build(); }
 void csrf() {
  var r=client.get().uri("/office-api/csrf").cookie("SESSION",cookie).exchange().expectStatus().isOk().expectBody(Map.class).returnResult();
  if(r.getResponseCookies().getFirst("SESSION")!=null) cookie=r.getResponseCookies().getFirst("SESSION").getValue();
  token=(String)r.getResponseBody().get("token");
 }
 void login() {
  csrf();
  var r=client.post().uri("/office-api/auth/login").cookie("SESSION",cookie).header("X-CSRF-TOKEN",token)
    .body(BodyInserters.fromFormData("username","operator-test").with("password","synthetic-password-only"))
    .exchange().expectStatus().isNoContent().expectBody().isEmpty();
  cookie=r.getResponseCookies().getFirst("SESSION").getValue(); csrf();
 }
}
