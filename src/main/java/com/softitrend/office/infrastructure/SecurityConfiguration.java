package com.softitrend.office.infrastructure;

import com.softitrend.office.adapters.http.LoginRateLimitFilter;
import com.softitrend.office.domain.ManagementRoute;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.HttpStatusServerEntryPoint;
import org.springframework.security.web.server.authentication.logout.HttpStatusReturningServerLogoutSuccessHandler;
import org.springframework.security.web.server.csrf.WebSessionServerCsrfTokenRepository;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher.MatchResult;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import org.springframework.web.server.session.CookieWebSessionIdResolver;
import org.springframework.web.server.session.WebSessionIdResolver;
import reactor.core.publisher.Mono;

@Configuration
public class SecurityConfiguration {
    @Bean
    PasswordEncoder passwords() { return new BCryptPasswordEncoder(); }

    @Bean
    ReactiveUserDetailsService users(Environment env) {
        String username = env.getProperty("office.operator.username", "");
        String hash = env.getProperty("office.operator.password-hash", "");
        boolean configured = !username.isBlank()
                && hash.matches("\\$2[aby]\\$(0[4-9]|1[0-6])\\$[./A-Za-z0-9]{53}");
        return requested -> configured && username.equals(requested)
                ? Mono.just(User.withUsername(username).password(hash).roles("OPERATOR").build())
                : Mono.empty();
    }

    @Bean
    WebSessionServerCsrfTokenRepository csrfRepository() {
        return new WebSessionServerCsrfTokenRepository();
    }

    @Bean
    SecurityWebFilterChain security(ServerHttpSecurity http, Environment env,
                                    WebSessionServerCsrfTokenRepository csrf) {
        return http
                .addFilterBefore(new LoginRateLimitFilter(env), SecurityWebFiltersOrder.FORM_LOGIN)
                // Retain the default XOR/BREACH request handler. Repository is session-backed.
                .csrf(c -> c.csrfTokenRepository(csrf))
                .httpBasic(b -> b.disable())
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusServerEntryPoint(HttpStatus.UNAUTHORIZED)))
                .authorizeExchange(a -> a
                        .pathMatchers(HttpMethod.GET, "/office-api/csrf").permitAll()
                        .pathMatchers(HttpMethod.POST, "/office-api/auth/login").permitAll()
                        .pathMatchers(HttpMethod.GET, "/office-api/session").hasRole("OPERATOR")
                        .matchers(exchange -> ManagementRoute.allowed(exchange.getRequest().getMethod().name(),
                                exchange.getRequest().getURI().getRawPath()) ? MatchResult.match() : MatchResult.notMatch())
                        .hasRole("OPERATOR")
                        .anyExchange().denyAll())
                .formLogin(f -> f
                        .requiresAuthenticationMatcher(ServerWebExchangeMatchers.pathMatchers(HttpMethod.POST, "/office-api/auth/login"))
                        .authenticationSuccessHandler((web, auth) -> csrf.saveToken(web.getExchange(), null)
                                .then(Mono.defer(() -> {
                                    web.getExchange().getResponse().setStatusCode(HttpStatus.NO_CONTENT);
                                    return web.getExchange().getResponse().setComplete();
                                })))
                        .authenticationFailureHandler((web, error) -> {
                            web.getExchange().getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                            return web.getExchange().getResponse().setComplete();
                        }))
                .logout(l -> l
                        .requiresLogout(ServerWebExchangeMatchers.pathMatchers(HttpMethod.POST, "/office-api/auth/logout"))
                        .logoutSuccessHandler(new HttpStatusReturningServerLogoutSuccessHandler(HttpStatus.NO_CONTENT)))
                .build();
    }

    @Bean
    WebSessionIdResolver sessionCookies(Environment environment) {
        var resolver = new CookieWebSessionIdResolver();
        boolean local = environment.matchesProfiles("dev", "test");
        if (local && !ConnectionSettings.loopback(environment.getProperty("server.address", "127.0.0.1"))) {
            throw new IllegalArgumentException("Local profiles require loopback binding");
        }
        resolver.addCookieInitializer(c -> c.httpOnly(true).secure(!local).sameSite("Strict").path("/"));
        return resolver;
    }
}
