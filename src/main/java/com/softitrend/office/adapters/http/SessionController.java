package com.softitrend.office.adapters.http;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.web.server.csrf.CsrfToken;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
@RestController
public class SessionController {
 public record SessionResponse(String username) {}
 @GetMapping("/office-api/session")
 public Mono<SessionResponse> session(java.security.Principal principal) { return Mono.just(new SessionResponse(principal.getName())); }
 public record CsrfResponse(String token, String headerName) {}
 @GetMapping("/office-api/csrf")
 public Mono<CsrfResponse> csrf(ServerWebExchange exchange) {
  Mono<CsrfToken> token = exchange.getAttribute(CsrfToken.class.getName());
  return token.map(t -> new CsrfResponse(t.getToken(), t.getHeaderName()));
 }
}
