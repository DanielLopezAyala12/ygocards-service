package edu.jala.ygocards;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A stand-in for the YGOPRODeck API, used by every test in this project.
 *
 * <p>No test here touches the real provider. A test that depends on the network is not a test: it
 * fails when a third party has an outage, it cannot exercise error paths on demand, and running
 * it repeatedly would spend the request budget this service exists to protect.
 *
 * <p>Pointing the application at this server needs no test-only wiring, because the upstream
 * address is already configuration. That is factor IV paying for itself: the same property a
 * deployment uses to select a mirror is the one a test uses to select a fake.
 */
public class FakeUpstream implements AutoCloseable {

    /** A search response shaped like the real one, including the image host tests must not see. */
    public static final String SEARCH_BODY = """
            {"data":[
              {"id":46986414,"name":"Dark Magician","type":"Normal Monster","frameType":"normal",
               "desc":"The ultimate wizard in terms of attack and defense.",
               "race":"Spellcaster","attribute":"DARK","atk":2500,"def":2100,"level":7,
               "archetype":"Dark Magician",
               "card_images":[{"id":46986414,
                 "image_url":"https://images.ygoprodeck.com/images/cards/46986414.jpg",
                 "image_url_small":"https://images.ygoprodeck.com/images/cards_small/46986414.jpg"}],
               "ygoprodeck_url":"https://ygoprodeck.com/card/dark-magician-4003"}
            ],"meta":{"total_rows":1}}
            """;

    private static final String NO_MATCH_BODY =
            "{\"error\":\"No card matching your query was found in the database.\"}";

    private static final String DB_VERSION_BODY =
            "[{\"database_version\":\"147.17\",\"last_update\":\"2026-09-26 04:02:10\"}]";

    private final HttpServer server;
    private final AtomicInteger searchRequests = new AtomicInteger();
    private final AtomicInteger probeRequests = new AtomicInteger();
    private final AtomicInteger imageRequests = new AtomicInteger();

    private volatile boolean searchMatches = true;
    private volatile long responseDelayMillis = 0;

    public FakeUpstream() {
        try {
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.setExecutor(Executors.newFixedThreadPool(16));
            server.createContext("/cardinfo.php", this::handleSearch);
            server.createContext("/checkDBVer.php", this::handleProbe);
            server.createContext("/cards/", this::handleImage);
            server.createContext("/cards_small/", this::handleImage);
            server.start();
        } catch (IOException e) {
            throw new IllegalStateException("could not start the fake upstream", e);
        }
    }

    public String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    public int searchRequests() {
        return searchRequests.get();
    }

    public int probeRequests() {
        return probeRequests.get();
    }

    public int imageRequests() {
        return imageRequests.get();
    }

    /** Makes the next searches answer the way the provider does when nothing matches: 400. */
    public void searchMatchesNothing() {
        this.searchMatches = false;
    }

    /** Widens the window in which concurrent callers can collide, for the single-flight test. */
    public void respondAfter(long millis) {
        this.responseDelayMillis = millis;
    }

    private void handleSearch(HttpExchange exchange) throws IOException {
        searchRequests.incrementAndGet();
        pause();
        if (searchMatches) {
            send(exchange, 200, SEARCH_BODY);
        } else {
            send(exchange, 400, NO_MATCH_BODY);
        }
    }

    private void handleProbe(HttpExchange exchange) throws IOException {
        probeRequests.incrementAndGet();
        pause();
        send(exchange, 200, DB_VERSION_BODY);
    }

    private void handleImage(HttpExchange exchange) throws IOException {
        imageRequests.incrementAndGet();
        pause();
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9};
        exchange.getResponseHeaders().add("Content-Type", "image/jpeg");
        exchange.sendResponseHeaders(200, jpeg.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(jpeg);
        }
    }

    private void pause() {
        long delay = responseDelayMillis;
        if (delay > 0) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
