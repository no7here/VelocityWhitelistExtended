package me.fallenbreath.velocitywhitelist.utils;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.velocitypowered.api.proxy.ProxyServer;

// API wrapper for Mojang services
public class MojangAPI
{
	// Represents the response object from the Mojang API based on https://wiki.vg/Mojang_API#Username_to_UUID
	private static class ResponseObject
	{
		public String name;
		public String id;
		public String errorMessage;
	}

	// Represents a cached query entry for a player name
	private record QueryCacheEntry(String queryName, @Nullable QueryResult result, long expireAtMs)
	{
	}

	// Represents the result of a successful query containing the UUID and player name
	public record QueryResult(UUID uuid, String playerName)
	{
	}

	private static final String ACCOUNT_URL_BASE = System.getProperty("velocitywhitelist.mojang.accountserver", "https://api.mojang.com/users/profiles/minecraft/");
	private static final int QUERY_CACHE_TTL_MS = 5 * 60 * 1000;
	private static final int QUERY_CACHE_EMPTY_TTL_MS = 60 * 1000;
	private static final int QUERY_CACHE_CAPACITY = 100;
	private static final List<QueryCacheEntry> queryCache = Lists.newLinkedList();
	private static volatile HttpClient cachedClient;

	// Queries a player by their name
	public static Optional<QueryResult> queryPlayerByName(Logger logger, ProxyServer server, String name)
	{
		// Mojang's name lookup is case-insensitive so the cache is keyed on the lowercased name to avoid a needless duplicate API call or entry for "Steve" vs "steve"
		String cacheKey = name.toLowerCase(Locale.ROOT);
		synchronized (queryCache)
		{
			long now = System.currentTimeMillis();
			queryCache.removeIf(e -> now > e.expireAtMs);
			for (QueryCacheEntry entry : queryCache)
			{
				if (Objects.equals(entry.queryName, cacheKey))
				{
					return Optional.ofNullable(entry.result());
				}
			}
		}

		BiConsumer<@Nullable QueryResult, Integer> addQueryCache = (qr, ttl) -> {
			synchronized (queryCache)
			{
				queryCache.add(new QueryCacheEntry(cacheKey, qr, System.currentTimeMillis() + ttl));
				while (queryCache.size() > QUERY_CACHE_CAPACITY)
				{
					queryCache.remove(0);
				}
			}
		};

		String url = ACCOUNT_URL_BASE + name;
		HttpClient client = getHttpClient(server);

		HttpRequest request = HttpRequest.newBuilder()
				.GET()
				.uri(URI.create(url))
				.timeout(Duration.ofSeconds(5))
				.build();
		try
		{
			HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

			ResponseObject obj;
			try
			{
				obj = new Gson().fromJson(response.body(), ResponseObject.class);
			}
			catch (JsonParseException e)
			{
				// Treat HTML error pages from CDNs or reverse-proxies during outages or rate-limits served with a non-204 status as any other lookup failure instead of letting them escape uncaught
				logger.warn("Mojang API returned an unparsable response (status {}): {}", response.statusCode(), e.toString());
				return Optional.empty();
			}

			if (obj == null || response.statusCode() == 204 || (obj.errorMessage != null && obj.errorMessage.startsWith("Couldn't find any profile with that name")))
			{
				addQueryCache.accept(null, QUERY_CACHE_EMPTY_TTL_MS);
			}

			if (obj == null || Strings.isNullOrEmpty(obj.id))
			{
				return Optional.empty();
			}
			var ret = UuidUtils.tryParseUuid(obj.id).map(uuid -> new QueryResult(uuid, obj.name));
			ret.ifPresent(result -> addQueryCache.accept(result, QUERY_CACHE_TTL_MS));
			return ret;
		}
		catch (IOException | InterruptedException | IllegalArgumentException e)
		{
			logger.warn("Get UUID from mojang API failed: {}", e.toString());
			return Optional.empty();
		}
	}

	// Lazily builds and reuses a single HttpClient for the plugin's lifetime rather than paying for a fresh client and its own internal thread pool and a repeat reflection probe on every lookup
	private static HttpClient getHttpClient(ProxyServer server)
	{
		HttpClient client = cachedClient;
		if (client == null)
		{
			synchronized (MojangAPI.class)
			{
				client = cachedClient;
				if (client == null)
				{
					client = createHttpClient(server);
					cachedClient = client;
				}
			}
		}
		return client;
	}

	// Creates a new HTTP client instance
	private static HttpClient createHttpClient(ProxyServer server)
	{
		try
		{
			// Try using the auth proxy setting from https://github.com/TISUnion/Velocity
			Class<?> clazz = Class.forName("com.velocitypowered.proxy.VelocityServer");
			if (clazz.isInstance(server))
			{
				Method method = clazz.getMethod("createProxiedHttpClient");
				method.setAccessible(true);
				Object client = method.invoke(server);
				if (client instanceof HttpClient httpClient)
				{
					return httpClient;
				}
			}
		}
		catch (ReflectiveOperationException | RuntimeException ignored)
		{
		    // Optional optimisation only, so any failure here just falls back to a plain client
		}
		return HttpClient.newBuilder().build();
	}
}
