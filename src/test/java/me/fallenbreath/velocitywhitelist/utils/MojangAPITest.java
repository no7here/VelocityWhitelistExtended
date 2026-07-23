package me.fallenbreath.velocitywhitelist.utils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.net.httpserver.HttpServer;
import com.velocitypowered.api.proxy.ProxyServer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;

/**
 * MojangAPI.ACCOUNT_URL_BASE is a `static final` field read once from the
 * "velocitywhitelist.mojang.accountserver" system property at class-init time, so it must be
 * set before the JVM ever touches MojangAPI - not from inside a running test. It's set as a
 * real JVM system property in build.gradle's `test {}` block, pointing at a fixed local port
 * that this test's stub server binds to.
 */
class MojangAPITest
{
	private static final int STUB_PORT = 18765;

	private HttpServer server;

	@AfterEach
	void tearDown()
	{
		if (this.server != null)
		{
			this.server.stop(0);
		}
	}

	@Test
	void queryPlayerByName_doesNotThrow_onNonJsonErrorBody() throws IOException
	{
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", STUB_PORT), 0);
		this.server.createContext("/", exchange -> {
			// e.g. a CDN/reverse-proxy error page served during a Mojang outage or rate-limit,
			// with a non-204 status and a body that isn't the ResponseObject JSON shape at all.
			byte[] body = "<html><body>502 Bad Gateway</body></html>".getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(502, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		this.server.start();

		Logger logger = LoggerFactory.getLogger(MojangAPITest.class);
		ProxyServer proxyServer = mock(ProxyServer.class);

		// Today, Gson#fromJson throws JsonSyntaxException on the non-JSON body, which is a
		// RuntimeException NOT covered by MojangAPI's `catch (IOException | InterruptedException
		// | IllegalArgumentException e)` clause, so it escapes queryPlayerByName uncaught -
		// breaking `/whitelist add <offline-name>` (or blacklist) right when Mojang is already
		// having problems.
		assertDoesNotThrow(() -> MojangAPI.queryPlayerByName(logger, proxyServer, "SomePlayer"),
				"a malformed/non-JSON error response from the account server must not escape as an uncaught exception");
	}
}
