package com.softitrend.office.adapters.http;

import com.softitrend.office.application.ManagementGateway;
import com.softitrend.office.domain.ManagementRoute;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import reactor.core.publisher.Mono;

@RestController
public class ManagementController {
 private static final tools.jackson.databind.json.JsonMapper JSON=tools.jackson.databind.json.JsonMapper.builder()
  .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
 private final ManagementGateway gateway;
 public ManagementController(ManagementGateway gateway) { this.gateway=gateway; }
 @org.springframework.web.bind.annotation.GetMapping("/office-api/management/promos/{id}")
 public Mono<ResponseEntity<String>> readPromo(ServerWebExchange exchange) {
  return execute(exchange,Mono.empty());
 }
 @RequestMapping(value="/office-api/management/promos/{id}/banner",
  method={org.springframework.web.bind.annotation.RequestMethod.GET,org.springframework.web.bind.annotation.RequestMethod.PUT})
 public Mono<ResponseEntity<String>> banner(ServerWebExchange exchange) {
  // Only this exact capability bypasses the unchanged 256 KiB String decoder.
  var body=org.springframework.core.io.buffer.DataBufferUtils.join(exchange.getRequest().getBody(),1_500_000)
   .map(buffer -> {
    try { return buffer.toString(java.nio.charset.StandardCharsets.UTF_8); }
    finally { org.springframework.core.io.buffer.DataBufferUtils.release(buffer); }
   }).onErrorMap(org.springframework.core.io.buffer.DataBufferLimitException.class,
    error -> new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE));
  return execute(exchange,body);
 }
 @org.springframework.web.bind.annotation.PutMapping("/office-api/management/promos/{id}/content")
 public Mono<ResponseEntity<String>> updatePromoContent(ServerWebExchange exchange,@RequestBody(required=false) Mono<String> body) {
  return execute(exchange,body);
 }
 @org.springframework.web.bind.annotation.PutMapping("/office-api/management/promos/{id}/description")
 public Mono<ResponseEntity<String>> updatePromoDescription(ServerWebExchange exchange,@RequestBody(required=false) Mono<String> body) {
  return execute(exchange,body);
 }
 @org.springframework.web.bind.annotation.PutMapping("/office-api/management/promos/{id}/destination")
 public Mono<ResponseEntity<String>> updatePromoDestination(ServerWebExchange exchange,@RequestBody(required=false) Mono<String> body) {
  return execute(exchange,body);
 }
 @org.springframework.web.bind.annotation.GetMapping("/office-api/management/promos/{id}/participation")
 public Mono<ResponseEntity<String>> readPromoParticipation(ServerWebExchange exchange) {
  return execute(exchange,Mono.empty());
 }
 @org.springframework.web.bind.annotation.GetMapping("/office-api/management/outbox-page")
 public Mono<ResponseEntity<String>> readOutboxPage(ServerWebExchange exchange) {
  return execute(exchange,Mono.empty());
 }
 @org.springframework.web.bind.annotation.GetMapping("/office-api/management/operations-status")
 public Mono<ResponseEntity<String>> readOperationsStatus(ServerWebExchange exchange) {
  return execute(exchange,Mono.empty());
 }
 @RequestMapping({"/office-api/management/brands", "/office-api/management/brands/aliases",
  "/office-api/management/reservations", "/office-api/management/waitlist", "/office-api/management/waitlist/responses",
  "/office-api/management/promos/{id}/reports", "/office-api/management/promos/{id}/fraud-removal", "/office-api/management/promos/{id}/resolve",
  "/office-api/management/accounts/{id}/stats", "/office-api/management/accounts/{id}/moderation",
  "/office-api/management/accounts/{id}/ban", "/office-api/management/accounts/{id}/profile",
  "/office-api/management/outbox", "/office-api/management/outbox/{id}/sent", "/office-api/management/email-deliveries",
  "/office-api/management/jobs/run", "/office-api/management/accounts", "/office-api/management/promos", "/office-api/management/incidents"})
 public Mono<ResponseEntity<String>> execute(ServerWebExchange exchange,@RequestBody(required=false) Mono<String> body) {
  var request=exchange.getRequest();
  var route=ManagementRoute.from(request.getMethod().name(),request.getURI().getRawPath());
  route.validateQuery(request.getQueryParams());
  return body.defaultIfEmpty("").flatMap(json -> {
   if(!json.isEmpty()) {
    if(!MediaType.APPLICATION_JSON.isCompatibleWith(request.getHeaders().getContentType()))
     throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    try {
     var tree=JSON.readTree(json);
     if(tree==null||!tree.isObject()) throw new IllegalArgumentException("Invalid payload");
    } catch(tools.jackson.core.JacksonException error) { throw new IllegalArgumentException("Invalid payload"); }
   }
   return gateway.execute(route,request.getQueryParams(),json);
  })
   .map(r -> ResponseEntity.status(r.status()).contentType(MediaType.APPLICATION_JSON).header("Cache-Control","no-store").body(r.json()));
 }
}
