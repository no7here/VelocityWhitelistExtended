package me.fallenbreath.velocitywhitelist.utils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;

import com.sun.net.httpserver.HttpServer;
import com.velocitypowered.api.proxy.ProxyServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// MojangAPI.ACCOUNT_URL_BASE is set via JVM property in build.gradle before class-init to point to this test's stub server port
class MojangAPITest {

    private static final int STUB_PORT = 18765;

    private HttpServer server;

    // Clean up after each test execution
    @AfterEach
    void tearDown() {
        if (this.server != null) {
            this.server.stop(0);
        }
    }

    // Test that querying a player by name does not throw an exception on a non-JSON error body
    @Test
    void queryPlayerByName_doesNotThrow_onNonJsonErrorBody()
        throws IOException {
        this.server = HttpServer.create(
            new InetSocketAddress("127.0.0.1", STUB_PORT),
            0
        );
        this.server.createContext("/", exchange -> {
            byte[] body = "<html><body>502 Bad Gateway</body></html>".getBytes(
                StandardCharsets.UTF_8
            );
            exchange.sendResponseHeaders(502, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        this.server.start();

        Logger logger = LoggerFactory.getLogger(MojangAPITest.class);
        ProxyServer proxyServer = mock(ProxyServer.class);

        // This prevents the whitelist or blacklist commands from breaking when Mojang is experiencing problems
        assertDoesNotThrow(
            () ->
                MojangAPI.queryPlayerByName(logger, proxyServer, "SomePlayer"),
            "a malformed/non-JSON error response from the account server must not escape as an uncaught exception"
        );
    }
}
