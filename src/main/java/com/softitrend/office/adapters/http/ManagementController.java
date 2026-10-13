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
 @org.springframework.web.bind.annotation.PutMapping("/office-api/management/promos/{id}/content")
 public Mono<ResponseEntity<String>> updatePromoContent(ServerWebExchange exchange,@RequestBody(required=false) Mono<String> body) {
  return execute(exchange,body);
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
