package com.softitrend.office.infrastructure;
import com.softitrend.office.adapters.upstream.FeezitHttpGateway;
import com.softitrend.office.domain.ManagementRoute;
import com.softitrend.office.infrastructure.SecurityConfiguration;
import org.springframework.mock.env.MockEnvironment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
class ConfigurationSafetyTest {
 @ParameterizedTest @ValueSource(strings={"http://example.com","http://user:password@127.0.0.1:8080","http://127.0.0.1:8080/?token=value","http://127.0.0.1:8080/api","http://127.0.0.1:8080/#secret","ftp://127.0.0.1:8080","//example.com"})
 void rejectsDangerousConfiguredDestinations(String url) {
  var e=new MockEnvironment().withProperty("office.upstream.base-url",url).withProperty("office.upstream.secret","synthetic-secret"); e.setActiveProfiles("dev");
  assertThatThrownBy(() -> new FeezitHttpGateway(e)).isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid Feezit connection configuration");
 }
 @Test void refusesPlainHttpEvenOnLoopbackOutsideLocalProfiles() {
  var e=new MockEnvironment().withProperty("office.upstream.base-url","http://127.0.0.1:8080").withProperty("office.upstream.secret","synthetic-secret");
  assertThatThrownBy(() -> new FeezitHttpGateway(e)).isInstanceOf(IllegalArgumentException.class);
 }
 @Test void absentUpstreamConfigurationIsDisabledNotDefaultedToSomeServer() {
  var reply=new FeezitHttpGateway(new MockEnvironment()).execute(new ManagementRoute("GET","brands"),java.util.Map.of(),"").block();
  assertThat(reply.status()).isEqualTo(503); assertThat(reply.json()).contains("UPSTREAM_DISABLED").doesNotContain("localhost");
 }
 @Test void insecureCookieProfilesCannotBindToPublicInterfaces() {
  var e=new MockEnvironment().withProperty("server.address","0.0.0.0"); e.setActiveProfiles("dev");
  assertThatThrownBy(() -> new SecurityConfiguration().sessionCookies(e)).isInstanceOf(IllegalArgumentException.class);
 }
}
