package com.sports.analytics.kalshi_epl_engine;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpClientsTest {

    @Test
    void aServerThatNeverAnswersFailsAfterTheReadTimeoutInsteadOfHanging() throws Exception {
        try (ServerSocket silentServer = new ServerSocket(0)) {
            // Accepts the connection, then never sends a byte - like a request caught by a dropped connection.
            Thread acceptor = new Thread(() -> {
                try (Socket ignored = silentServer.accept()) {
                    Thread.sleep(5_000);
                } catch (Exception ignored) {
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();

            RestTemplate client = HttpClients.withTimeouts(Duration.ofSeconds(2), Duration.ofMillis(300));
            long start = System.nanoTime();
            assertThrows(ResourceAccessException.class,
                () -> client.getForObject("http://127.0.0.1:" + silentServer.getLocalPort() + "/", String.class));
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertTrue(elapsedMs < 3_000, "should give up quickly, took " + elapsedMs + "ms");
        }
    }

    @Test
    void defaultTimeoutsAreSet() {
        assertTrue(HttpClients.CONNECT_TIMEOUT.toSeconds() > 0);
        assertTrue(HttpClients.READ_TIMEOUT.toSeconds() > 0);
    }
}
