package com.vogella.eclipse.installer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;

import org.osgi.framework.FrameworkUtil;

/** Checks whether the published installer is newer than the running one. */
public final class UpdateCheck {

	private static final Duration TIMEOUT = Duration.ofSeconds(3);

	private UpdateCheck() {
	}

	public static Optional<Instant> newerRelease(URI archive) {
		Instant built = buildTime();
		if (archive == null || built == null) {
			return Optional.empty();
		}
		try {
			HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
					.connectTimeout(TIMEOUT).build();
			HttpRequest request = HttpRequest.newBuilder(archive).method("HEAD", HttpRequest.BodyPublishers.noBody())
					.timeout(TIMEOUT).build();
			HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
			if (response.statusCode() != 200) {
				return Optional.empty();
			}
			return response.headers().firstValue("Last-Modified")
					.map(value -> ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant())
					.filter(released -> released.isAfter(built.plus(Duration.ofMinutes(30))));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return Optional.empty();
		} catch (Exception e) {
			return Optional.empty();
		}
	}

	/** Tycho's qualifier is the UTC build time. */
	static Instant buildTime() {
		String qualifier = FrameworkUtil.getBundle(UpdateCheck.class).getVersion().getQualifier();
		try {
			return LocalDateTime.parse(qualifier, DateTimeFormatter.ofPattern("yyyyMMddHHmm"))
					.toInstant(ZoneOffset.UTC);
		} catch (DateTimeParseException e) {
			return null;
		}
	}

	public static String date(Instant instant) {
		return DateTimeFormatter.ISO_LOCAL_DATE.format(instant.atZone(ZoneOffset.UTC));
	}
}
