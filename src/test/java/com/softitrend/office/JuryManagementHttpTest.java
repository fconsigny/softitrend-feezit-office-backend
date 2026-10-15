package com.softitrend.office;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/** Real Office HTTP stack and WebClient; upstream fixture is not the Feezit application. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.profiles.active=test"})
class JuryManagementHttpTest {
    private static final String PREFIX = "/office-api/management/";
    private static final String ID = "00000000-0000-0000-0000-000000000001";
    private static final String PROMO = "promos/" + ID;
    private static final String SECRET = "synthetic-jr03-management-secret";
    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
    private static final AtomicInteger CALLS = new AtomicInteger();
    private static final AtomicReference<ReceivedRequest> RECEIVED = new AtomicReference<>();
    private static final AtomicReference<UpstreamReply> REPLY = new AtomicReference<>();
    private static final HttpServer UPSTREAM = createUpstream();

    private record ReceivedRequest(String method, String uri, String body, String secret,
                                   String authorization, String cookie, String csrf, String forwardedHost) {}
    private record UpstreamReply(int status, String body, boolean json, long delayMillis) {}
    private record Extension(String method, String suffix, String body) {}

    static Stream<Extension> extensions() {
        return Stream.of(
                new Extension("PUT", PROMO + "/destination", "{\"destinationUrl\":\"https://merchant.example/offer\"}"),
                new Extension("GET", PROMO + "/participation", null),
                new Extension("GET", "outbox-page", null),
                new Extension("GET", "operations-status", null));
    }

    @LocalServerPort int port;

    @BeforeEach void reset() {
        REPLY.set(new UpstreamReply(200, "{}", true, 0));
        RECEIVED.set(null);
    }

    private static HttpServer createUpstream() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(EXECUTOR);
            server.createContext("/", exchange -> {
                try (exchange) {
                    var headers = exchange.getRequestHeaders();
                    RECEIVED.set(new ReceivedRequest(exchange.getRequestMethod(), exchange.getRequestURI().toString(),
                            new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8),
                            headers.getFirst("X-Management-Secret"), headers.getFirst("Authorization"),
                            headers.getFirst("Cookie"), headers.getFirst("X-CSRF-TOKEN"), headers.getFirst("X-Forwarded-Host")));
                    CALLS.incrementAndGet();
                    // Snapshot before any delay: a timed-out request must not consume the next test's reply.
                    var reply = REPLY.get();
                    if (reply.status() == 0) return;
                    try {
                        if (reply.delayMillis() > 0) Thread.sleep(reply.delayMillis());
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (reply.json()) exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.getResponseHeaders().set("Cache-Control", "public, max-age=3600");
                    exchange.getResponseHeaders().set("Set-Cookie", "UPSTREAM=must-not-escape");
                    exchange.getResponseHeaders().set("Location", "http://127.0.0.1:" + server.getAddress().getPort() + "/redirected");
                    byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(reply.status(), reply.status() == 204 || body.length == 0 ? -1 : body.length);
                    if (reply.status() != 204 && body.length > 0) exchange.getResponseBody().write(body);
                } catch (IOException disconnectedClient) {
                    // Expected for timeout/size-limit probes; no retries or external I/O.
                }
            });
            server.start();
            return server;
        } catch (IOException error) {
            EXECUTOR.shutdownNow();
            throw new ExceptionInInitializerError(error);
        }
    }

    @DynamicPropertySource static void configuration(DynamicPropertyRegistry properties) {
        properties.add("office.operator.username", () -> "operator-test");
        properties.add("office.operator.password-hash", () -> new BCryptPasswordEncoder(4).encode("synthetic-password-only"));
        properties.add("office.upstream.base-url", () -> "http://127.0.0.1:" + UPSTREAM.getAddress().getPort());
        properties.add("office.upstream.secret", () -> SECRET);
        properties.add("office.upstream.timeout-ms", () -> "500");
        properties.add("office.login.max-attempts", () -> "100");
        properties.add("office.login.window-ms", () -> "1000");
    }

    @AfterAll static void stopUpstream() {
        try {
            UPSTREAM.stop(0);
        } finally {
            EXECUTOR.shutdownNow();
            EXECUTOR.close();
        }
    }

    private HttpBrowser operator() {
        var browser = new HttpBrowser(port);
        browser.login();
        return browser;
    }

    private WebTestClient.ResponseSpec send(HttpBrowser browser, String method, String suffix, String body) {
        var request = browser.client.method(HttpMethod.valueOf(method))
                .uri(URI.create("http://127.0.0.1:" + port + PREFIX + suffix))
                .cookie("SESSION", browser.cookie).header("X-CSRF-TOKEN", browser.token)
                .header("Authorization", "Bearer browser-creator-token")
                .header("X-Management-Secret", "browser-value")
                .header("X-Forwarded-Host", "untrusted.example.test");
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).bodyValue(body);
        return request.exchange();
    }

    private void assertForwarded(String method, String suffix, String body) {
        assertThat(RECEIVED.get()).isEqualTo(new ReceivedRequest(method, "/api/management/" + suffix,
                body, SECRET, null, null, null, null));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"waitlist", "waitlist/responses"})
    void boundsStoreAt100BeforeContactingFeezit(String suffix) {
        var browser = operator();
        String query = "?store=" + "S".repeat(100);
        send(browser, "GET", suffix + query, null).expectStatus().isOk().expectBody();
        assertForwarded("GET", suffix + query, "");
        int before = CALLS.get();
        send(browser, "GET", suffix + query + "X", null).expectStatus().isBadRequest()
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody().jsonPath("$.code").isEqualTo("VALIDATION");
        assertThat(CALLS.get()).isEqualTo(before);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"accounts", "promos"})
    void boundsSearchAt200BeforeContactingFeezit(String suffix) {
        var browser = operator();
        String query = "?q=" + "Q".repeat(200);
        send(browser, "GET", suffix + query, null).expectStatus().isOk().expectBody();
        assertForwarded("GET", suffix + query, "");
        int before = CALLS.get();
        send(browser, "GET", suffix + query + "X", null).expectStatus().isBadRequest()
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody().jsonPath("$.code").isEqualTo("VALIDATION");
        assertThat(CALLS.get()).isEqualTo(before);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void readsEffectiveOperationsConfigurationWithoutInventingHealth(boolean emailEnabled, boolean jobsEnabled) {
        var browser = operator();
        String status = "{\"emailTransportEnabled\":" + emailEnabled + ",\"participationJobsEnabled\":" + jobsEnabled
                + ",\"observedAt\":\"2026-10-13T10:11:12Z\"}";
        REPLY.set(new UpstreamReply(200, status, true, 0));
        send(browser, "GET", "operations-status", null)
                .expectStatus().isOk().expectHeader().valueEquals("Cache-Control", "no-store")
                .expectHeader().doesNotExist("Set-Cookie").expectHeader().doesNotExist("Location")
                .expectBody(String.class).isEqualTo(status);
        assertForwarded("GET", "operations-status", "");
    }

    @Test void readsPagedOutboxPreservingNullsAndEmptyPageTotals() {
        var browser = operator();
        String page = """
                {"items":[{"id":"00000000-0000-0000-0000-000000000003",
                 "ownerId":"00000000-0000-0000-0000-000000000002","resourceId":null,"kind":"brand_available",
                 "recipient":"synthetic@example.test","deliveryStatus":"pending","createdAt":"2026-10-12T12:00:00Z",
                 "sentAt":null,"deliveryReference":null}],"total":137,"page":0,"size":20}
                """;
        REPLY.set(new UpstreamReply(200, page, true, 0));
        send(browser, "GET", "outbox-page?page=0&size=20", null)
                .expectStatus().isOk().expectHeader().valueEquals("Cache-Control", "no-store")
                .expectHeader().doesNotExist("Set-Cookie").expectHeader().doesNotExist("Location")
                .expectBody(String.class).isEqualTo(page);
        assertForwarded("GET", "outbox-page?page=0&size=20", "");

        String emptyPage = "{\"items\":[],\"total\":137,\"page\":10000,\"size\":1}";
        REPLY.set(new UpstreamReply(200, emptyPage, true, 0));
        send(browser, "GET", "outbox-page?page=10000&size=1", null)
                .expectStatus().isOk().expectBody(String.class).isEqualTo(emptyPage);
        assertForwarded("GET", "outbox-page?page=10000&size=1", "");
    }

    @Test void readsParticipationWithoutInventingDatesOrFormattingReferences() {
        var browser = operator();
        String participation = """
                {"promoId":"00000000-0000-0000-0000-000000000001","ownerId":"00000000-0000-0000-0000-000000000002",
                 "agreementReceivedAt":"2026-10-10T08:09:10Z","agreementReference":"draft __proto__ constructor é",
                 "documentVersion":"2026-10-12","reservationExpiresAt":"2026-10-17T08:09:10Z"}
                """;
        REPLY.set(new UpstreamReply(200, participation, true, 0));
        send(browser, "GET", PROMO + "/participation", null)
                .expectStatus().isOk().expectHeader().valueEquals("Cache-Control", "no-store")
                .expectHeader().doesNotExist("Set-Cookie").expectHeader().doesNotExist("Location")
                .expectBody(String.class).isEqualTo(participation);
        assertForwarded("GET", PROMO + "/participation", "");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("extensions")
    void refusesOtherMethodsAndNoncanonicalRawPathsBeforeUpstream(Extension route) {
        var browser = operator();
        int before = CALLS.get();
        for (String method : List.of("GET", "PUT", "POST", "PATCH", "DELETE", "HEAD", "OPTIONS")) {
            if (!method.equals(route.method())) {
                send(browser, method, route.suffix(), route.body())
                        .expectStatus().isForbidden().expectBody();
            }
        }
        for (String suffix : List.of(route.suffix() + "/", route.suffix() + "/extra",
                route.suffix() + ";extra", "/" + route.suffix(),
                "%" + Integer.toHexString(route.suffix().charAt(0)) + route.suffix().substring(1),
                route.suffix() + "%2fextra", route.suffix() + "%252fextra")) {
            send(browser, route.method(), suffix, route.body()).expectStatus().is4xxClientError().expectBody();
        }
        if (route.suffix().startsWith("promos/")) {
            for (String id : List.of("not-uuid", "1-1-1-1-1", ID.replace("-", ""), "%30" + ID.substring(1))) {
                send(browser, route.method(), route.suffix().replace(ID, id), route.body())
                        .expectStatus().is4xxClientError().expectBody();
            }
        }
        assertThat(CALLS.get()).isEqualTo(before);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("extensions")
    void refusesUndeclaredOrDuplicateQueriesBeforeUpstream(Extension route) {
        var browser = operator();
        int before = CALLS.get();
        var queries = route.suffix().equals("outbox-page")
                ? List.of("?status=pending", "?q=a", "?store=x", "?page=0&page=1", "?size=1&size=2",
                          "?page=0&%70age=1", "?page=0&url=https://attacker.invalid", "?%75rl=x")
                : List.of("?page=0", "?size=20", "?q=a", "?q=a&q=b", "?url=https://attacker.invalid", "?%75rl=x");
        for (String query : queries) {
            send(browser, route.method(), route.suffix() + query, route.body())
                    .expectStatus().isBadRequest().expectHeader().valueEquals("Cache-Control", "no-store")
                    .expectBody().jsonPath("$.code").isEqualTo("VALIDATION");
        }
        assertThat(CALLS.get()).isEqualTo(before);
    }

    @Test void outboxPaginationRetainsBoundsAndDoesNotInjectDefaults() {
        var browser = operator();
        for (String query : List.of("", "?page=0", "?size=100", "?page=10000&size=100")) {
            send(browser, "GET", "outbox-page" + query, null).expectStatus().isOk().expectBody();
            assertForwarded("GET", "outbox-page" + query, "");
        }
        int before = CALLS.get();
        for (String query : List.of("?page=-1", "?page=10001", "?page=", "?page=1.0", "?page=%2B1",
                "?page=9999999999", "?page=1e2", "?page=%00", "?size=0", "?size=101", "?size=", "?size=%201")) {
            send(browser, "GET", "outbox-page" + query, null).expectStatus().isBadRequest()
                    .expectBody().jsonPath("$.code").isEqualTo("VALIDATION");
        }
        assertThat(CALLS.get()).isEqualTo(before);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"accounts,q,200", "promos,q,200", "waitlist,store,100", "waitlist/responses,store,100"})
    void queryBoundsUseDecodedLiteralValuesWithoutTrimmingOrInjection(String suffix, String key, int limit) {
        var browser = operator();
        String accented = "?" + key + "=" + "%C3%A9".repeat(limit);
        send(browser, "GET", suffix + accented, null).expectStatus().isOk().expectBody();
        assertForwarded("GET", suffix + accented, "");
        String literal = "?" + key + "=Mixed%26evil%3D1%2B%7Bx%7D";
        send(browser, "GET", suffix + literal, null).expectStatus().isOk().expectBody();
        assertForwarded("GET", suffix + literal, "");
        int before = CALLS.get();
        for (String query : List.of(accented + "%C3%A9", "?" + key + "=" + "a".repeat(limit) + "%20", "?" + key + "=a%0Ab")) {
            send(browser, "GET", suffix + query, null).expectStatus().isBadRequest()
                    .expectBody().jsonPath("$.code").isEqualTo("VALIDATION");
        }
        assertThat(CALLS.get()).isEqualTo(before);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("extensions")
    void requiresOperatorSessionEvenWithBrowserBearerOrManagementSecret(Extension route) {
        var anonymous = new HttpBrowser(port);
        anonymous.csrf();
        int before = CALLS.get();
        send(anonymous, route.method(), route.suffix(), route.body())
                .expectStatus().isUnauthorized().expectHeader().value("Cache-Control", value -> assertThat(value).contains("no-store"))
                .expectBody();
        var withServerSecret = anonymous.client.method(HttpMethod.valueOf(route.method())).uri(PREFIX + route.suffix())
                .cookie("SESSION", anonymous.cookie).header("X-CSRF-TOKEN", anonymous.token)
                .header("Authorization", "Bearer browser-creator-token").header("X-Management-Secret", SECRET);
        if (route.body() != null) withServerSecret.contentType(MediaType.APPLICATION_JSON).bodyValue(route.body());
        withServerSecret.exchange().expectStatus().isUnauthorized().expectBody();
        var browser = operator();
        browser.client.post().uri("/office-api/auth/logout").cookie("SESSION", browser.cookie).header("X-CSRF-TOKEN", browser.token)
                .exchange().expectStatus().isNoContent().expectBody().isEmpty();
        var stale = send(browser, route.method(), route.suffix(), route.body());
        if (route.method().equals("GET")) stale.expectStatus().isUnauthorized().expectBody();
        else stale.expectStatus().isForbidden().expectBody();
        assertThat(CALLS.get()).isEqualTo(before);
    }

    @Test void destinationRequiresCurrentCsrfBeforeUpstream() {
        var browser = operator();
        int before = CALLS.get();
        for (String token : List.of("", "invalid-token")) {
            browser.client.put().uri(PREFIX + PROMO + "/destination").cookie("SESSION", browser.cookie)
                    .header("X-CSRF-TOKEN", token).contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{\"destinationUrl\":\"https://merchant.example/offer\"}").exchange()
                    .expectStatus().isForbidden().expectBody();
        }
        assertThat(CALLS.get()).isEqualTo(before);
    }

    @Test void destinationRetainsJsonEnvelopeContentTypeAndSizeGuards() {
        var browser = operator();
        int before = CALLS.get();
        for (String body : List.of("not-json", "[]", "null", "{} {}")) {
            send(browser, "PUT", PROMO + "/destination", body).expectStatus().isBadRequest()
                    .expectBody().jsonPath("$.code").isEqualTo("VALIDATION");
        }
        browser.client.put().uri(PREFIX + PROMO + "/destination").cookie("SESSION", browser.cookie)
                .header("X-CSRF-TOKEN", browser.token).contentType(MediaType.TEXT_PLAIN).bodyValue("{}")
                .exchange().expectStatus().isEqualTo(415).expectBody();
        send(browser, "PUT", PROMO + "/destination", "{\"destinationUrl\":\"" + "a".repeat(300000) + "\"}")
                .expectStatus().isEqualTo(413).expectBody().jsonPath("$.code").isEqualTo("PAYLOAD_TOO_LARGE");
        assertThat(CALLS.get()).isEqualTo(before);
    }

    @Test void destinationDelegatesBusinessValidationToFeezitWithoutRewritingObjects() {
        var browser = operator();
        REPLY.set(new UpstreamReply(400, "{\"message\":\"private owner validation\"}", true, 0));
        for (String body : List.of("{}", "{\"destinationUrl\":null}", "{\"destinationUrl\":7}",
                "{\"destinationUrl\":\"\"}", "{\"destinationUrl\":\"javascript:alert(1)\"}",
                "{\"destinationUrl\":\"https://user:pass@example.test\"}",
                "{\"destinationUrl\":\"https://merchant.example/offer\",\"publicationStatus\":\"published\"}",
                "{\"destinationUrl\":\"https://merchant.example/" + "a".repeat(2048) + "\"}")) {
            int before = CALLS.get();
            send(browser, "PUT", PROMO + "/destination", body).expectStatus().isBadRequest()
                    .expectBody().jsonPath("$.code").isEqualTo("VALIDATION")
                    .jsonPath("$.message").isEqualTo("Opération refusée par Feezit");
            assertForwarded("PUT", PROMO + "/destination", body);
            assertThat(CALLS.get()).isEqualTo(before + 1);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("extensions")
    void preservesSafeErrorsAndPrivateResponsesOnEveryExtension(Extension route) {
        var browser = operator();
        int[] statuses = {400, 401, 403, 404, 409, 422, 429, 500, 503};
        String[] codes = {"VALIDATION", "UNAUTHORIZED", "FORBIDDEN", "NOT_FOUND", "CONFLICT", "UPSTREAM_REJECTED",
                          "RATE_LIMITED", "UPSTREAM_UNAVAILABLE", "UPSTREAM_UNAVAILABLE"};
        for (int i = 0; i < statuses.length; i++) {
            int status = statuses[i];
            REPLY.set(new UpstreamReply(status, "{\"message\":\"" + SECRET + " http://private.example.test\"}", true, 0));
            int before = CALLS.get();
            send(browser, route.method(), route.suffix(), route.body())
                    .expectStatus().isEqualTo(status < 500 ? status : 502)
                    .expectHeader().valueEquals("Cache-Control", "no-store")
                    .expectHeader().doesNotExist("Set-Cookie").expectHeader().doesNotExist("Location")
                    .expectBody().jsonPath("$.status").isEqualTo(status < 500 ? status : 502)
                    .jsonPath("$.code").isEqualTo(codes[i])
                    .jsonPath("$.message").isEqualTo(status < 500 ? "Opération refusée par Feezit" : "Feezit temporairement indisponible");
            assertThat(CALLS.get()).isEqualTo(before + 1);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("extensions")
    void rejectsUnsafeSuccessesIncludingEncodedSecretOnEveryExtension(Extension route) {
        var browser = operator();
        String escapedSecret = SECRET.substring(0, SECRET.length() - 1) + "\\u0074";
        for (String body : List.of("", "   ", "not-json", "{} {}", "{\"echo\":\"" + SECRET + "\"}",
                "{\"echo\":\"" + escapedSecret + "\"}", "{\"large\":\"" + "a".repeat(300000) + "\"}")) {
            REPLY.set(new UpstreamReply(200, body, true, 0));
            send(browser, route.method(), route.suffix(), route.body()).expectStatus().isEqualTo(502)
                    .expectHeader().valueEquals("Cache-Control", "no-store")
                    .expectBody().jsonPath("$.code").isEqualTo("UPSTREAM_UNAVAILABLE");
        }
        REPLY.set(new UpstreamReply(200, "{}", false, 0));
        send(browser, route.method(), route.suffix(), route.body()).expectStatus().isEqualTo(502)
                .expectBody().jsonPath("$.code").isEqualTo("UPSTREAM_UNAVAILABLE");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("extensions")
    void refusesRedirectAndNetworkRetryOnEveryExtension(Extension route) {
        var browser = operator();
        for (int upstreamStatus : List.of(302, 0)) {
            REPLY.set(new UpstreamReply(upstreamStatus, "{}", true, 0));
            int before = CALLS.get();
            send(browser, route.method(), route.suffix(), route.body()).expectStatus().isEqualTo(502)
                    .expectHeader().doesNotExist("Location").expectHeader().doesNotExist("Set-Cookie")
                    .expectBody().jsonPath("$.code").isEqualTo("UPSTREAM_UNAVAILABLE");
            assertThat(CALLS.get()).isEqualTo(before + 1);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("extensions")
    void boundsUpstreamTimeoutOnEveryExtension(Extension route) {
        var browser = operator();
        REPLY.set(new UpstreamReply(200, "{}", true, 1500));
        int before = CALLS.get();
        send(browser, route.method(), route.suffix(), route.body()).expectStatus().isEqualTo(504)
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody().jsonPath("$.code").isEqualTo("UPSTREAM_TIMEOUT");
        assertThat(CALLS.get()).isEqualTo(before + 1);
    }

    @Test void legacyOutboxStillPreservesUppercaseProjectionAndManualConfirmationConflict() {
        var browser = operator();
        String outbox = """
                [{"ID":"00000000-0000-0000-0000-000000000003","OWNER_ID":"00000000-0000-0000-0000-000000000002",
                  "RESOURCE_ID":null,"KIND":"brand_available","RECIPIENT":"synthetic@example.test","DELIVERY_STATUS":"pending",
                  "CREATED_AT":"2026-10-12T12:00:00Z","SENT_AT":null,"DELIVERY_REFERENCE":null}]
                """;
        REPLY.set(new UpstreamReply(200, outbox, true, 0));
        send(browser, "GET", "outbox", null).expectStatus().isOk().expectBody(String.class).isEqualTo(outbox);
        assertForwarded("GET", "outbox", "");
        REPLY.set(new UpstreamReply(409, "", false, 0));
        String body = "{\"sentAt\":\"2026-10-12T12:00:00Z\",\"deliveryReference\":\"synthetic-not-sent\"}";
        send(browser, "PUT", "outbox/" + ID + "/sent", body).expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.code").isEqualTo("CONFLICT")
                .jsonPath("$.message").isEqualTo("Opération refusée par Feezit");
        assertForwarded("PUT", "outbox/" + ID + "/sent", body);
    }

    @Test void updatesDestinationWithoutRewritingPayloadOrReturnedPromo() {
        var browser = operator();
        String payload = "{\"destinationUrl\":\"https://merchant.example/Offer?code=MixedCase&x=%2B\"}";
        String promo = """
                {"id":"00000000-0000-0000-0000-000000000001","ownerId":"00000000-0000-0000-0000-000000000002",
                 "articleName":"Article é","articleImage":"","articleDescription":"Description","category":"Mode",
                 "conditions":"Conditions","code":"MixedCase","startDate":"2026-10-13","endDate":null,
                 "store":"Marque","destinationUrl":"https://merchant.example/Offer?code=MixedCase&x=%2B",
                 "createdAt":"2026-10-12T12:00:00Z","ownerName":"Créateur","ownerUsername":"creator",
                 "ownerAvatar":"","status":"upcoming","publicationStatus":"draft"}
                """;
        REPLY.set(new UpstreamReply(200, promo, true, 0));
        int before = CALLS.get();
        send(browser, "PUT", PROMO + "/destination", payload)
                .expectStatus().isOk().expectHeader().valueEquals("Cache-Control", "no-store")
                .expectHeader().doesNotExist("Set-Cookie").expectHeader().doesNotExist("Location")
                .expectBody(String.class).isEqualTo(promo);
        assertForwarded("PUT", PROMO + "/destination", payload);
        assertThat(CALLS.get()).isEqualTo(before + 1);
    }
}
