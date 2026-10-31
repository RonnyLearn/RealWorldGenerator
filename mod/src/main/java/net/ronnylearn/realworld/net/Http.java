package net.ronnylearn.realworld.net;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** One HTTP client for map tiles and place search, identifying the mod as OpenStreetMap's policies ask. */
public final class Http {
	public static final String USER_AGENT = "RealWorldMod/"
		+ FabricLoader.getInstance().getModContainer("realworld").map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("dev")
		+ " (Minecraft Fabric mod; world generation by Arnis)";

	private static final HttpClient CLIENT = HttpClient.newBuilder()
		.proxy(ProxySelector.getDefault())
		.followRedirects(HttpClient.Redirect.NORMAL)
		.connectTimeout(Duration.ofSeconds(10))
		.build();

	private Http() {
	}

	public static byte[] get(String url, String language) throws IOException, InterruptedException {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
			.timeout(Duration.ofSeconds(20))
			.header("User-Agent", USER_AGENT);
		if (language != null) {
			request.header("Accept-Language", language);
		}
		HttpResponse<byte[]> response = CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
		if (response.statusCode() != 200) {
			throw new IOException("HTTP " + response.statusCode() + " for " + url);
		}
		return response.body();
	}
}
