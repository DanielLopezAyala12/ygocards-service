package edu.jala.ygocards;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
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
 * Checks the reason the probe result is cached at all.
 *
 * <p>Caching it is not enough on its own. If several callers arrive at the moment the stored
 * result expires, each one finds it stale, and without single flighting each one calls the
 * upstream: the burst the cache exists to prevent happens anyway, at exactly the moment a
 * monitoring system is most likely to produce it. This is the regression test for that.
 *
 * <p>The fake upstream is made slow on purpose, to widen the window in which callers can collide.
 * A fast fake would let the first caller finish before the others arrive and the test would pass
 * whether or not the code were correct.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "ygo.upstream.probe-ttl=50ms")
class UpstreamProbeConcurrencyTest {

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
    void anExpiredProbeIsRefreshedOnceEvenUnderConcurrentCallers() throws Exception {
        UPSTREAM.respondAfter(300);

        // One call to store a result, so the interesting path is expiry rather than cold start.
        get("/health/upstream");
        int afterFirst = UPSTREAM.probeRequests();
        assertThat(afterFirst).isEqualTo(1);

        // Let it expire.
        Thread.sleep(120);

        int callers = 10;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            List<Callable<HttpResponse<String>>> calls = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                calls.add(() -> get("/health/upstream"));
            }
            List<Future<HttpResponse<String>>> futures = pool.invokeAll(calls);
            for (Future<HttpResponse<String>> future : futures) {
                assertThat(future.get().statusCode()).isEqualTo(200);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(UPSTREAM.probeRequests() - afterFirst)
                .as("ten callers finding an expired result must produce one refresh, not ten")
                .isEqualTo(1);
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .GET()
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }
}
