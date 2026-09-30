package com.vogella.eclipse.installer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;

import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.Version;

/** Checks whether the published installer is newer than the running one, by its bundle version. */
public final class UpdateCheck {

	private static final Duration TIMEOUT = Duration.ofSeconds(3);

	private UpdateCheck() {
	}

	public static Optional<Instant> newerRelease(URI archive) {
		if (archive == null) {
			return Optional.empty();
		}
		try {
			// The release publishes the bundle version of its installer next to the archives.
			HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
					.connectTimeout(TIMEOUT).build();
			HttpRequest request = HttpRequest.newBuilder(archive.resolve("installer-version.txt")).timeout(TIMEOUT)
					.build();
			HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200) {
				return Optional.empty();
			}
			Version released = Version.parseVersion(response.body().trim());
			Version running = FrameworkUtil.getBundle(UpdateCheck.class).getVersion();
			if (released.compareTo(running) <= 0) {
				return Optional.empty();
			}
			return Optional.of(Optional.ofNullable(time(released.getQualifier())).orElse(Instant.now()));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return Optional.empty();
		} catch (Exception e) {
			return Optional.empty();
		}
	}

	/** Tycho's qualifier is the UTC build time. */
	static Instant time(String qualifier) {
		try {
			return LocalDateTime.parse(qualifier, DateTimeFormatter.ofPattern("yyyyMMddHHmm")).toInstant(ZoneOffset.UTC);
		} catch (DateTimeParseException e) {
			return null;
		}
	}

	public static String date(Instant instant) {
		return DateTimeFormatter.ISO_LOCAL_DATE.format(instant.atZone(ZoneOffset.UTC));
	}
}
