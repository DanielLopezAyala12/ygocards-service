package edu.jala.ygocards;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Checks that the outbound ceiling is enforced, and that hitting it is reported as a wait rather
 * than as a provider failure.
 *
 * <p>The ceiling is set to one request per second here through configuration, which is the same
 * mechanism a deployment would use. Saturating the real default of eight would need a burst large
 * enough to make the test slow and flaky for no extra confidence.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "ygo.upstream.rate-limit-per-second=1",
                "ygo.upstream.rate-limit-wait=1ms"
        })
class RateLimitTest {

    private static final FakeUpstream UPSTREAM = new FakeUpstream();

    @Value("${local.server.port}")
    private int port;

    @DynamicPropertySource
    static void pointAtTheFake(DynamicPropertyRegistry registry) {
        registry.add("ygo.upstream.base-url", UPSTREAM::baseUrl);
        registry.add("ygo.upstream.image-base-url", UPSTREAM::baseUrl);
    }

    @AfterAll
    static void stopUpstream() {
        UPSTREAM.close();
    }

    @Test
    void saturatingTheCeilingAnswers503WithRetryAfter() throws Exception {
        UPSTREAM.respondAfter(150);

        int callers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            List<Callable<HttpResponse<String>>> calls = new java.util.ArrayList<>();
            for (int i = 0; i < callers; i++) {
                String name = "card" + i;
                calls.add(() -> get("/api/cards?name=" + name));
            }

            List<Future<HttpResponse<String>>> futures = pool.invokeAll(calls);

            List<HttpResponse<String>> refused = new java.util.ArrayList<>();
            for (Future<HttpResponse<String>> future : futures) {
                HttpResponse<String> response = future.get();
                if (response.statusCode() == 503) {
                    refused.add(response);
                }
            }

            assertThat(refused)
                    .as("a ceiling of one per second cannot let eight concurrent callers through")
                    .isNotEmpty();

            HttpResponse<String> one = refused.get(0);
            assertThat(one.headers().firstValue("Retry-After"))
                    .as("a caller told to wait needs to know for how long")
                    .isPresent();
            assertThat(one.body()).contains("\"error\":\"rate_limited\"");
            assertThat(one.body())
                    .as("this is our own ceiling, not a provider failure")
                    .doesNotContain("upstream_unavailable");
        } finally {
            pool.shutdownNow();
        }
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .GET()
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }
}
