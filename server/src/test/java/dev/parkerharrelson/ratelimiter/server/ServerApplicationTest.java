package dev.parkerharrelson.ratelimiter.server;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the real application with the in-memory backend and the real limits file, then drives it
 * over HTTP. Proves the wiring (properties -> policy -> limiter -> filter -> metrics) end to end.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "ratelimiter.backend=memory",
                "ratelimiter.instance-id=test-instance",
                "ratelimiter.limits-file=../configs/limits.yaml"
        })
class ServerApplicationTest {

    @Autowired
    Environment env;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> get(String path, String user) throws Exception {
        int port = Integer.parseInt(env.getProperty("local.server.port"));
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (user != null) {
            b.header("X-User-ID", user);
        }
        return http.send(b.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void healthReportsConfiguration() throws Exception {
        HttpResponse<String> r = get("/healthz", null);
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("\"instanceId\":\"test-instance\"").contains("\"backend\":\"memory\"");
        assertThat(get("/limits", null).body()).contains("user-global").contains("50/10s");
    }

    @Test
    void enforcesTheConfiguredUserLimitAndExposesMetrics() throws Exception {
        int allowed = 0;
        int denied = 0;
        for (int i = 0; i < 60; i++) {
            int status = get("/api/cheap", "it-alice").statusCode();
            if (status == 200) {
                allowed++;
            } else if (status == 429) {
                denied++;
            }
        }
        assertThat(allowed).as("user-global is 50/10s").isEqualTo(50);
        assertThat(denied).isEqualTo(10);

        HttpResponse<String> denial = get("/api/cheap", "it-alice");
        assertThat(denial.headers().firstValue("X-RateLimit-Policy")).contains("user-global");
        assertThat(denial.headers().firstValue("Retry-After")).isPresent();
        assertThat(denial.headers().firstValue("X-Instance")).contains("test-instance");

        String metrics = get("/actuator/prometheus", null).body();
        assertThat(metrics).contains("ratelimiter_decisions_total").contains("ratelimiter_check_duration_seconds_bucket")
                .contains("ratelimiter_info");
    }
}
