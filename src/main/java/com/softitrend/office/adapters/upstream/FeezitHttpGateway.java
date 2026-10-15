package com.softitrend.office.adapters.upstream;

import com.softitrend.office.application.ManagementGateway;
import com.softitrend.office.domain.ManagementRoute;
import io.netty.channel.ChannelOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

@Component
public class FeezitHttpGateway implements ManagementGateway {
 private static final JsonMapper JSON=JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
 private final WebClient client;
 private final String secret;
 private final Duration timeout;
 private final boolean enabled;
 public FeezitHttpGateway(Environment env) {
  var settings=com.softitrend.office.infrastructure.ConnectionSettings.read(env);
  secret=settings.secret(); timeout=settings.timeout(); enabled=settings.enabled();
  var transport=HttpClient.create().followRedirect(false).disableRetry(true)
   .option(ChannelOption.CONNECT_TIMEOUT_MILLIS,(int)timeout.toMillis()).responseTimeout(timeout);
  client=WebClient.builder().baseUrl(settings.baseUrl()==null?"https://disabled.invalid":settings.baseUrl().toString())
   .clientConnector(new ReactorClientHttpConnector(transport))
   .codecs(c -> c.defaultCodecs().maxInMemorySize(256*1024))
   .defaultHeader("X-Management-Secret",secret).defaultHeader("Accept","application/json").build();
 }
 public Mono<Reply> execute(ManagementRoute route,Map<String,List<String>> query,String json) {
  if(!enabled) return Mono.just(error(503,"UPSTREAM_DISABLED","Connexion Feezit non configurée"));
  var request=client.method(HttpMethod.valueOf(route.method())).uri(builder -> {
   builder.path("/api/management/"+route.suffix());
   var variables=new java.util.HashMap<String,String>();
   query.forEach((key,values) -> { builder.queryParam(key,"{"+key+"}"); variables.put(key,values.getFirst()); });
   return builder.build(variables);
  });
  if(!json.isEmpty()) request.contentType(MediaType.APPLICATION_JSON).bodyValue(json);
  return request.exchangeToMono(r -> {
   int status=r.statusCode().value();
   if(status>=300) return r.releaseBody().thenReturn(status>=400&&status<500 ? error(status,errorCode(status),"Opération refusée par Feezit") : unavailable());
   if(status==204) return r.releaseBody().thenReturn(new Reply(204,""));
   if(status==202 && route.method().equals("POST") && route.suffix().matches("promos/[0-9a-fA-F-]{36}/reports")) {
    return r.bodyToMono(String.class).defaultIfEmpty("").map(body -> {
     if(body.isEmpty()) return new Reply(202,"");
     return r.headers().contentType().filter(t -> MediaType.APPLICATION_JSON.isCompatibleWith(t)).isPresent() ? safeSuccess(status,body) : unavailable();
    });
   }
   if(r.headers().contentType().filter(t -> MediaType.APPLICATION_JSON.isCompatibleWith(t)).isEmpty()) return r.releaseBody().thenReturn(unavailable());
   Mono<String> body=route.isBanner()
    ? org.springframework.core.io.buffer.DataBufferUtils.join(r.bodyToFlux(org.springframework.core.io.buffer.DataBuffer.class),1_500_000)
     .map(buffer -> {
      try { return buffer.toString(java.nio.charset.StandardCharsets.UTF_8); }
      finally { org.springframework.core.io.buffer.DataBufferUtils.release(buffer); }
     })
    : r.bodyToMono(String.class);
   return body.defaultIfEmpty("").map(jsonBody -> safeSuccess(status,jsonBody));
  }).timeout(timeout).onErrorResume(e -> Mono.just(isTimeout(e) ? error(504,"UPSTREAM_TIMEOUT","Feezit ne répond pas dans le délai prévu") : unavailable()));
 }
 private Reply safeSuccess(int status,String body) {
  try {
   var tree=JSON.readTree(body);
   if(tree==null || tree.isMissingNode() || (!secret.isEmpty() && tree.toString().contains(secret))) return unavailable();
   return new Reply(status,body);
  } catch(Exception e) { return unavailable(); }
 }
 private static boolean isTimeout(Throwable error) {
  for(Throwable e=error;e!=null;e=e.getCause()) if(e instanceof TimeoutException || e instanceof io.netty.handler.timeout.ReadTimeoutException) return true;
  return false;
 }
 private static String errorCode(int status) {
  return switch(status) { case 400 -> "VALIDATION"; case 401 -> "UNAUTHORIZED"; case 403 -> "FORBIDDEN"; case 404 -> "NOT_FOUND"; case 409 -> "CONFLICT"; case 429 -> "RATE_LIMITED"; default -> "UPSTREAM_REJECTED"; };
 }
 private static Reply unavailable() { return error(502,"UPSTREAM_UNAVAILABLE","Feezit temporairement indisponible"); }
 private static Reply error(int status,String code,String message) {
  return new Reply(status,"{\"status\":"+status+",\"code\":\""+code+"\",\"message\":\""+message+"\"}");
 }
}
