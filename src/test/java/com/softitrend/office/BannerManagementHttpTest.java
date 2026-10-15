package com.softitrend.office;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/** Real Office security/controller/WebClient; synthetic loopback upstream, not real Feezit. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties="spring.profiles.active=test")
class BannerManagementHttpTest {
    private static final String ID="00000000-0000-0000-0000-000000000001";
    private static final String PATH="/office-api/management/promos/"+ID+"/banner";
    private static final String SECRET="synthetic-banner-management-secret";
    private static final AtomicReference<String> RESPONSE=new AtomicReference<>();
    private static final AtomicInteger STATUS=new AtomicInteger(200);
    private static final AtomicInteger CALLS=new AtomicInteger();
    private static final AtomicReference<String> RECEIVED=new AtomicReference<>();
    private static final AtomicReference<String> BODY=new AtomicReference<>();
    private static final java.util.concurrent.ExecutorService EXECUTOR=Executors.newVirtualThreadPerTaskExecutor();
    private static final HttpServer UPSTREAM=createServer();
    @LocalServerPort int port;

    private static HttpServer createServer() {
        try {
            var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.setExecutor(EXECUTOR);
            server.createContext("/", e -> {
                try(e) {
                    var h=e.getRequestHeaders();
                    RECEIVED.set(e.getRequestMethod()+" "+e.getRequestURI()+" "+h.getFirst("X-Management-Secret")
                        +" "+h.getFirst("Authorization")+" "+h.getFirst("Cookie")+" "+h.getFirst("X-CSRF-TOKEN"));
                    BODY.set(new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
                    CALLS.incrementAndGet();
                    byte[] bytes=RESPONSE.get().getBytes(StandardCharsets.UTF_8);
                    e.getResponseHeaders().set("Content-Type","application/json");
                    e.getResponseHeaders().set("Location","http://127.0.0.1:"+server.getAddress().getPort()+"/redirected");
                    e.getResponseHeaders().set("Set-Cookie","upstream=private");
                    e.sendResponseHeaders(STATUS.get(),bytes.length);
                    e.getResponseBody().write(bytes);
                }
            });
            server.start(); return server;
        } catch(Exception e) { throw new ExceptionInInitializerError(e); }
    }
    @DynamicPropertySource static void config(DynamicPropertyRegistry r) {
        r.add("office.operator.username",()->"operator-test");
        r.add("office.operator.password-hash",()->new BCryptPasswordEncoder(4).encode("synthetic-password-only"));
        r.add("office.upstream.base-url",()->"http://127.0.0.1:"+UPSTREAM.getAddress().getPort());
        r.add("office.upstream.secret",()->SECRET);
        r.add("office.upstream.timeout-ms",()->"3000");
        r.add("office.login.max-attempts",()->"100");
    }
    @BeforeEach void reset() { STATUS.set(200); RESPONSE.set("{}"); RECEIVED.set(null); BODY.set(null); }
    @AfterAll static void stop() { UPSTREAM.stop(0); EXECUTOR.shutdownNow(); }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"url", "clear", "image/png", "image/jpeg"})
    void forwardsBannerMutationUnchangedAndReturnsThePromo(String mode) {
        var b=new HttpBrowser(port); b.login();
        String payload=switch(mode) {
            case "url" -> "{\"url\":\"http://127.0.0.1:9/synthetic-never-fetched.png\"}";
            case "clear" -> "{\"url\":\"\"}";
            default -> "{\"contentType\":\""+mode+"\",\"base64\":\""+Base64.getEncoder().encodeToString(new byte[1_048_576])+"\"}";
        };
        String promo="{\"id\":\""+ID+"\",\"articleImage\":\"https://feezit.example.test/api/promos/"+ID+"/banner\",\"publicationStatus\":\"masked\",\"endDate\":null}";
        RESPONSE.set(promo);
        b.client.put().uri(PATH).cookie("SESSION",b.cookie).header("X-CSRF-TOKEN",b.token)
            .header("Authorization","Bearer synthetic-creator-token").header("X-Management-Secret","untrusted-browser-value")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON).bodyValue(payload)
            .exchange().expectStatus().isOk().expectHeader().valueEquals("Cache-Control","no-store")
            .expectHeader().doesNotExist("Location").expectHeader().doesNotExist("Set-Cookie")
            .expectBody(String.class).isEqualTo(promo);
        assertThat(BODY.get()).isEqualTo(payload);
        assertThat(RECEIVED.get()).isEqualTo("PUT /api/management/promos/"+ID+"/banner "+SECRET+" null null null");
    }

    @Test void updatesOnlyDescriptionThroughTheDedicatedFacadeWithNormalSecurityAndLimits() {
        var b=new HttpBrowser(port); b.login(); int before=CALLS.get();
        String path="/office-api/management/promos/"+ID+"/description";
        String payload="{\"articleDescription\":\"Description synthétique\"}";
        String promo="{\"id\":\""+ID+"\",\"articleDescription\":\"Description synthétique\",\"articleImage\":\"https://images.example.test/current.png\",\"publicationStatus\":\"masked\"}";
        RESPONSE.set(promo);
        b.client.put().uri(path).cookie("SESSION",b.cookie).header("X-CSRF-TOKEN",b.token)
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON).bodyValue(payload)
            .exchange().expectStatus().isOk().expectBody(String.class).isEqualTo(promo);
        assertThat(RECEIVED.get()).isEqualTo("PUT /api/management/promos/"+ID+"/description "+SECRET+" null null null");
        assertThat(BODY.get()).isEqualTo(payload);
        b.client.put().uri(path).cookie("SESSION",b.cookie).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .bodyValue(payload).exchange().expectStatus().isForbidden().expectBody(Void.class);
        b.client.get().uri(path).cookie("SESSION",b.cookie).exchange().expectStatus().isForbidden().expectBody(Void.class);
        b.client.put().uri(path+"?url=http://127.0.0.1:9").cookie("SESSION",b.cookie).header("X-CSRF-TOKEN",b.token)
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON).bodyValue(payload)
            .exchange().expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("VALIDATION");
        assertThat(CALLS.get()).isEqualTo(before+1);
        var anonymous=new HttpBrowser(port); anonymous.csrf();
        anonymous.client.put().uri(path).cookie("SESSION",anonymous.cookie).header("X-CSRF-TOKEN",anonymous.token)
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON).bodyValue(payload)
            .exchange().expectStatus().isUnauthorized().expectBody(Void.class);
        b.client.put().uri(path).cookie("SESSION",b.cookie).header("X-CSRF-TOKEN",b.token)
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .bodyValue("{\"articleDescription\":\""+"a".repeat(262_144)+"\"}")
            .exchange().expectStatus().isEqualTo(413).expectBody().jsonPath("$.code").isEqualTo("PAYLOAD_TOO_LARGE");
        assertThat(CALLS.get()).isEqualTo(before+1);
        RESPONSE.set("{\"data\":\""+"a".repeat(262_144)+"\"}");
        b.client.put().uri(path).cookie("SESSION",b.cookie).header("X-CSRF-TOKEN",b.token)
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON).bodyValue(payload)
            .exchange().expectStatus().isEqualTo(502).expectBody().jsonPath("$.code").isEqualTo("UPSTREAM_UNAVAILABLE");
        assertThat(CALLS.get()).isEqualTo(before+2);
    }

    @Test void requiresOperatorSessionEvenWithBrowserManagementSecretOrCreatorBearer() {
        var b=new HttpBrowser(port); int before=CALLS.get();
        b.client.get().uri(PATH).header("X-Management-Secret",SECRET).header("Authorization","Bearer synthetic-creator-token")
            .exchange().expectStatus().isUnauthorized().expectBody(Void.class);
        b.csrf();
        b.client.put().uri(PATH).cookie("SESSION",b.cookie).header("X-CSRF-TOKEN",b.token)
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON).bodyValue("{\"url\":\"\"}")
            .exchange().expectStatus().isUnauthorized().expectBody(Void.class);
        assertThat(CALLS.get()).isEqualTo(before);
    }

    @Test void requiresCsrfOnBannerPut() {
        var b=new HttpBrowser(port); b.login(); int before=CALLS.get();
        b.client.put().uri(PATH).cookie("SESSION",b.cookie).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .bodyValue("{\"url\":\"\"}").exchange().expectStatus().isForbidden().expectBody(Void.class);
        assertThat(CALLS.get()).isEqualTo(before);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={
        "promos/not-uuid/banner", "promos/00000000-0000-0000-0000-00000000001/banner",
        "promos/00000000-0000-0000-0000-000000000001/banner/extra",
        "promos/00000000-0000-0000-0000-000000000001/banner/", "promos/00000000-0000-0000-0000-000000000001/%62anner",
        "promos/00000000-0000-0000-0000-000000000001/banner%2fextra",
        "promos/00000000-0000-0000-0000-000000000001/banner;extra", "promos//00000000-0000-0000-0000-000000000001/banner",
        "https://attacker.example/banner", "banner", "../catalog"})
    void rejectsUnknownOrEncodedRawPathsBeforeUpstream(String suffix) {
        var b=new HttpBrowser(port); b.login(); int before=CALLS.get();
        b.client.get().uri(java.net.URI.create("http://127.0.0.1:"+port+"/office-api/management/"+suffix))
            .cookie("SESSION",b.cookie).exchange().expectStatus().is4xxClientError().expectBody(Void.class);
        assertThat(CALLS.get()).isEqualTo(before);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"POST", "PATCH", "DELETE", "HEAD"})
    void rejectsUndeclaredBannerMethods(String method) {
        var b=new HttpBrowser(port); b.login(); int before=CALLS.get();
        b.client.method(org.springframework.http.HttpMethod.valueOf(method)).uri(PATH).cookie("SESSION",b.cookie)
            .header("X-CSRF-TOKEN",b.token).exchange().expectStatus().is4xxClientError().expectBody(Void.class);
        assertThat(CALLS.get()).isEqualTo(before);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"GET,?url=http://127.0.0.1:9", "PUT,?url=http://127.0.0.1:9",
        "GET,?page=0", "PUT,?base64=x", "GET,?%75rl=x", "GET,?q=a&q=b"})
    void rejectsEveryBannerQueryBeforeUpstream(String method,String query) {
        var b=new HttpBrowser(port); b.login(); int before=CALLS.get();
        b.client.method(org.springframework.http.HttpMethod.valueOf(method))
            .uri(java.net.URI.create("http://127.0.0.1:"+port+PATH+query)).cookie("SESSION",b.cookie)
            .header("X-CSRF-TOKEN",b.token).exchange().expectStatus().isBadRequest()
            .expectBody().jsonPath("$.code").isEqualTo("VALIDATION");
        assertThat(CALLS.get()).isEqualTo(before);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"PUT", "GET"})
    void boundsBannerRequestInBytesIncludingChunkedBodies(String method) {
        var b=new HttpBrowser(port); b.login(); int before=CALLS.get();
        String json="{\"url\":\"\"}";
        String exact=json+" ".repeat(1_500_000-json.length());
        var request=b.client.method(org.springframework.http.HttpMethod.valueOf(method)).uri(PATH).cookie("SESSION",b.cookie)
            .header("X-CSRF-TOKEN",b.token).contentType(org.springframework.http.MediaType.APPLICATION_JSON);
        request.bodyValue(exact).exchange().expectStatus().isOk().expectBody().json("{}");
        assertThat(BODY.get()).isEqualTo(exact);
        // Multibyte UTF-8: character count is below the limit, byte count is above it.
        String tooLarge="{\"url\":\""+"é".repeat(750_000)+"\"}";
        byte[] bytes=tooLarge.getBytes(StandardCharsets.UTF_8);
        var factory=new org.springframework.core.io.buffer.DefaultDataBufferFactory();
        var chunks=reactor.core.publisher.Flux.range(0,(bytes.length+8191)/8192)
            .<org.springframework.core.io.buffer.DataBuffer>map(i->factory.wrap(java.util.Arrays.copyOfRange(bytes,i*8192,Math.min(bytes.length,(i+1)*8192))));
        b.client.method(org.springframework.http.HttpMethod.valueOf(method)).uri(PATH).cookie("SESSION",b.cookie)
            .header("X-CSRF-TOKEN",b.token).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .body(org.springframework.web.reactive.function.BodyInserters.fromDataBuffers(chunks))
            .exchange().expectStatus().isEqualTo(413).expectBody().jsonPath("$.code").isEqualTo("PAYLOAD_TOO_LARGE");
        assertThat(CALLS.get()).isEqualTo(before+1);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"GET,1500000,200", "GET,1500001,502", "PUT,1500000,200", "PUT,1500001,502"})
    void boundsBannerResponseInBytesWithoutEnlargingTheGatewayCodec(String method,int size,int status) {
        var b=new HttpBrowser(port); b.login();
        String dto="{\"url\":\"\",\"contentType\":null,\"base64\":null}";
        RESPONSE.set(dto+" ".repeat(size-dto.length()));
        var request=b.client.mutate().codecs(c->c.defaultCodecs().maxInMemorySize(1_500_000)).build()
            .method(org.springframework.http.HttpMethod.valueOf(method)).uri(PATH).cookie("SESSION",b.cookie).header("X-CSRF-TOKEN",b.token);
        var result=request.exchange().expectStatus().isEqualTo(status);
        if(status==200) result.expectBody(String.class).isEqualTo(RESPONSE.get());
        else result.expectBody().jsonPath("$.code").isEqualTo("UPSTREAM_UNAVAILABLE");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"promos/00000000-0000-0000-0000-000000000001/content",
        "promos/00000000-0000-0000-0000-000000000001/destination", "brands/aliases"})
    void keepsLegacyRequestLimitAt256KiB(String suffix) {
        var b=new HttpBrowser(port); b.login(); int before=CALLS.get();
        b.client.put().uri("/office-api/management/"+suffix).cookie("SESSION",b.cookie).header("X-CSRF-TOKEN",b.token)
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON).bodyValue("{\"url\":\""+"a".repeat(262_144)+"\"}")
            .exchange().expectStatus().isEqualTo(413).expectBody().jsonPath("$.code").isEqualTo("PAYLOAD_TOO_LARGE");
        assertThat(CALLS.get()).isEqualTo(before);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"brands", "promos/00000000-0000-0000-0000-000000000001"})
    void keepsLegacyResponseLimitAt256KiB(String suffix) {
        var b=new HttpBrowser(port); b.login(); RESPONSE.set("{\"data\":\""+"a".repeat(262_144)+"\"}");
        b.client.get().uri("/office-api/management/"+suffix).cookie("SESSION",b.cookie)
            .exchange().expectStatus().isEqualTo(502).expectBody().jsonPath("$.code").isEqualTo("UPSTREAM_UNAVAILABLE");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints={400,401,403,404,409,413,415,429,500,302})
    void returnsSanitizedUpstreamErrorsWithoutFollowingRedirects(int status) {
        var b=new HttpBrowser(port); b.login(); int before=CALLS.get();
        STATUS.set(status); RESPONSE.set("{\"message\":\""+SECRET+" http://private.example.test\"}");
        b.client.put().uri(PATH).cookie("SESSION",b.cookie).header("X-CSRF-TOKEN",b.token)
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON).bodyValue("{\"url\":\"\"}")
            .exchange().expectStatus().isEqualTo(status>=400&&status<500?status:502)
            .expectHeader().doesNotExist("Location").expectHeader().doesNotExist("Set-Cookie")
            .expectBody(String.class).value(body->assertThat(body).doesNotContain(SECRET,"private.example.test"));
        assertThat(CALLS.get()).isEqualTo(before+1);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"not-json", "", "secret", "escaped-secret"})
    void rejectsUnsafeBannerSuccessBodies(String kind) {
        var b=new HttpBrowser(port); b.login();
        RESPONSE.set(switch(kind) {
            case "secret" -> "{\"url\":\""+SECRET+"\"}";
            case "escaped-secret" -> "{\"url\":\""+SECRET.replace("s","\\u0073")+"\"}";
            default -> kind;
        });
        b.client.get().uri(PATH).cookie("SESSION",b.cookie).exchange().expectStatus().isEqualTo(502)
            .expectBody().jsonPath("$.code").isEqualTo("UPSTREAM_UNAVAILABLE");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"[]", "null", "{} {}", "not-json"})
    void refusesInvalidJsonEnvelopesBeforeUpstream(String payload) {
        var b=new HttpBrowser(port); b.login(); int before=CALLS.get();
        b.client.put().uri(PATH).cookie("SESSION",b.cookie).header("X-CSRF-TOKEN",b.token)
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON).bodyValue(payload)
            .exchange().expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("VALIDATION");
        assertThat(CALLS.get()).isEqualTo(before);
    }

    @Test void rejectsNonJsonBannerContentType() {
        var b=new HttpBrowser(port); b.login(); int before=CALLS.get();
        b.client.put().uri(PATH).cookie("SESSION",b.cookie).header("X-CSRF-TOKEN",b.token)
            .contentType(org.springframework.http.MediaType.TEXT_PLAIN).bodyValue("{}")
            .exchange().expectStatus().isEqualTo(415).expectBody().jsonPath("$.code").isEqualTo("UNSUPPORTED_MEDIA_TYPE");
        assertThat(CALLS.get()).isEqualTo(before);
    }

    @Test void readsPrivateOneMiBImportWithoutTheWebClientDefaultCodecLimit() {
        var b=new HttpBrowser(port); b.login();
        // File-size fixture only: Office must not decode/validate the image bytes.
        String base64=Base64.getEncoder().encodeToString(new byte[1_048_576]);
        String dto="{\"url\":\"https://feezit.example.test/api/promos/"+ID+"/banner\",\"contentType\":\"image/png\",\"base64\":\""+base64+"\"}";
        RESPONSE.set(dto);
        b.client.mutate().codecs(c->c.defaultCodecs().maxInMemorySize(1_500_000)).build()
            .get().uri(PATH).cookie("SESSION",b.cookie).header("Authorization","Bearer synthetic-creator-token")
            .header("X-Management-Secret","untrusted-browser-value")
            .exchange().expectStatus().isOk().expectHeader().valueEquals("Cache-Control","no-store")
            .expectHeader().doesNotExist("Location").expectHeader().doesNotExist("Set-Cookie")
            .expectBody(String.class).isEqualTo(dto);
        assertThat(RECEIVED.get()).isEqualTo("GET /api/management/promos/"+ID+"/banner "+SECRET+" null null null");
    }
}
