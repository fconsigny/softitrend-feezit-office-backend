package com.softitrend.office.adapters.http;

import java.nio.charset.StandardCharsets;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** Global bounded-memory budget: one operator, no spoofable X-Forwarded-For keys. */
public final class LoginRateLimitFilter implements WebFilter {
 private final int max;
 private final long windowNanos;
 private long started=System.nanoTime();
 private int used;
 public LoginRateLimitFilter(Environment env) {
  max=env.getProperty("office.login.max-attempts",Integer.class,10);
  long millis=env.getProperty("office.login.window-ms",Long.class,60000L);
  if(max<1||max>100||millis<1000||millis>3600000) throw new IllegalArgumentException("Invalid login rate limit configuration");
  windowNanos=millis*1000000L;
 }
 private synchronized boolean allow() {
  long now=System.nanoTime();
  if(now-started>=windowNanos) { started=now; used=0; }
  if(used>=max) return false;
  used++; return true;
 }
 @Override public Mono<Void> filter(ServerWebExchange exchange,WebFilterChain chain) {
  if(!exchange.getRequest().getMethod().name().equals("POST")||!exchange.getRequest().getURI().getRawPath().equals("/office-api/auth/login")||allow())
   return chain.filter(exchange);
  var response=exchange.getResponse();
  response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS); response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
  response.getHeaders().set("Cache-Control","no-store"); response.getHeaders().set("Retry-After",Long.toString((windowNanos+999999999L)/1000000000L));
  byte[] body="{\"status\":429,\"code\":\"RATE_LIMITED\",\"message\":\"Trop de tentatives de connexion\"}".getBytes(StandardCharsets.UTF_8);
  return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
 }
}
