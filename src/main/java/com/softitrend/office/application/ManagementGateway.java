package com.softitrend.office.application;
import com.softitrend.office.domain.ManagementRoute;
import reactor.core.publisher.Mono;
public interface ManagementGateway {
 record Reply(int status,String json) {}
 Mono<Reply> execute(ManagementRoute route, java.util.Map<String,java.util.List<String>> query, String json);
}
