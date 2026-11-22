package dev.easyeconomy.pack;

import com.sun.net.httpserver.HttpServer;
import dev.easyeconomy.EasyEconomyMod;
import dev.easyeconomy.config.EeConfig;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

// Serves exactly one static file at /easyeconomy-pack.zip. Off by default.
// Every other path is a 404, non-GET is a 405, and each client address gets
// at most one download per few seconds.
public final class PackServer {
	public static final String PACK_PATH = "/easyeconomy-pack.zip";
	private static final long MIN_INTERVAL_MS = 5_000;

	private HttpServer server;
	private Path file;
	private final Map<String, Long> lastServed = new HashMap<>();

	public synchronized void start(EeConfig.PackServer config, Path zipFile) throws Exception {
		stop();
		this.file = zipFile;
		server = HttpServer.create(new InetSocketAddress(config.bind, config.port), 8);
		server.createContext(PACK_PATH, exchange -> {
			String ip = exchange.getRemoteAddress().getAddress().getHostAddress();
			try (exchange) {
				if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
					exchange.sendResponseHeaders(405, -1);
					return;
				}
				synchronized (lastServed) {
					long now = System.currentTimeMillis();
					Long last = lastServed.get(ip);
					if (last != null && now - last < MIN_INTERVAL_MS) {
						exchange.sendResponseHeaders(429, -1);
						return;
					}
					lastServed.put(ip, now);
				}
				byte[] body = Files.readAllBytes(file);
				exchange.getResponseHeaders().set("Content-Type", "application/zip");
				exchange.sendResponseHeaders(200, body.length);
				exchange.getResponseBody().write(body);
			} catch (Exception e) {
				EasyEconomyMod.LOGGER.warn("pack request from {} failed: {}", ip, e.getMessage());
			}
		});
		server.setExecutor(Executors.newFixedThreadPool(2));
		server.start();
		EasyEconomyMod.LOGGER.info("serving resource pack at http://{}:{}{}", config.bind, config.port, PACK_PATH);
	}

	public synchronized void stop() {
		if (server != null) {
			server.stop(1);
			server = null;
			lastServed.clear();
		}
	}
}
