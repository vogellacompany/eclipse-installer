package com.vogella.eclipse.installer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Status;

/** Downloads files through a .part file. */
final class Downloader {

	private Downloader() {
	}

	static Path download(URI url, Path cacheDir, StatusListener listener) throws CoreException {
		if ("file".equals(url.getScheme())) {
			Path local = Path.of(url);
			if (!Files.isRegularFile(local)) {
				throw new CoreException(Status.error("File not found: " + local));
			}
			return local;
		}
		// One folder per URL, so equally named archives from different URLs do not share a cache entry.
		String name = Path.of(url.getPath()).getFileName().toString();
		Path file = cacheDir.resolve(hash(url.toString())).resolve(name);
		if (Files.isRegularFile(file)) {
			listener.log("Using cached " + file);
			return file;
		}
		fetch(url, file, listener);
		return file;
	}

	static void fetch(URI url, Path file, StatusListener listener) throws CoreException {
		Path part = file.resolveSibling(file.getFileName() + ".part");
		// Interrupts a read that is stuck on a stalled connection when the run is canceled.
		Thread worker = Thread.currentThread();
		Thread watchdog = Thread.ofVirtual().start(() -> {
			try {
				while (!listener.isCanceled()) {
					Thread.sleep(200);
				}
				worker.interrupt();
			} catch (InterruptedException e) {
				// The download finished.
			}
		});
		try {
			Files.createDirectories(file.getParent());
			HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
					.connectTimeout(Duration.ofSeconds(30)).build();
			HttpResponse<InputStream> response = client.send(HttpRequest.newBuilder(url).GET().build(),
					HttpResponse.BodyHandlers.ofInputStream());
			try (InputStream in = response.body()) {
				if (response.statusCode() != 200) {
					throw new CoreException(Status.error("Download of " + url + " failed with HTTP " + response.statusCode()));
				}
				try (OutputStream out = Files.newOutputStream(part)) {
					byte[] buffer = new byte[256 * 1024];
					int n;
					while ((n = in.read(buffer)) >= 0) {
						if (listener.isCanceled()) {
							throw new OperationCanceledException();
						}
						out.write(buffer, 0, n);
					}
				}
			}
			try {
				Files.move(part, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(part, file, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException e) {
			if (listener.isCanceled()) {
				throw new OperationCanceledException();
			}
			throw new CoreException(Status.error("Download of " + url + " failed: " + e.getMessage(), e));
		} catch (InterruptedException e) {
			throw new OperationCanceledException();
		} finally {
			watchdog.interrupt();
			Thread.interrupted();
		}
	}

	private static String hash(String value) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest, 0, 8);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
