package edu.jala.ygocards;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Checks the two claims the report makes about the card API that are easiest to break silently.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CardApiTest {

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

    /**
     * The provider answers 400 when a query matches no card. Finding nothing is a successful
     * search, so the API must not pass that status on to its own callers.
     */
    @Test
    void upstreamNoMatchBecomesAnEmptySuccess() throws Exception {
        UPSTREAM.searchMatchesNothing();

        HttpResponse<String> response = get("/api/cards?name=zzzznotacard");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"count\":0");
        assertThat(response.body()).contains("\"cards\":[]");
    }

    /**
     * The provider prohibits hotlinking its image host, so no response this service produces may
     * mention it. The fake upstream returns a payload that does contain it, which is the point:
     * the assertion fails if anyone ever passes the upstream shape through instead of mapping it.
     */
    @Test
    void noResponseMentionsTheUpstreamHost() throws Exception {
        HttpResponse<String> search = get("/api/cards?name=dark%20magician");
        HttpResponse<String> image = get("/api/cards/46986414/image?variant=small");
        HttpResponse<String> health = get("/health/upstream");

        assertThat(FakeUpstream.SEARCH_BODY)
                .as("the fake must carry the host, otherwise this test proves nothing")
                .contains("ygoprodeck");

        for (HttpResponse<String> response : java.util.List.of(search, image, health)) {
            String everything = response.body() + response.headers().map();
            assertThat(everything.toLowerCase())
                    .as("response to " + response.uri())
                    .doesNotContain("ygoprodeck");
        }

        assertThat(search.body()).contains("/api/cards/46986414/image?variant=small");
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .GET()
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }
}
