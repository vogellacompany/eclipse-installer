package com.vogella.eclipse.installer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Tells whether the installer runs with an elevated administrator token on Windows. */
public final class Elevation {

	private static Boolean elevated;

	private Elevation() {
	}

	public static synchronized boolean isElevated() {
		if (elevated == null) {
			elevated = "win32".equals(System.getProperty("osgi.os")) && hasHighIntegrityToken();
		}
		return elevated;
	}

	/** An elevated full administrator token has the high (or system) mandatory label; group membership alone does not. */
	private static boolean hasHighIntegrityToken() {
		try {
			Process process = new ProcessBuilder("whoami", "/groups").redirectErrorStream(true).start();
			String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			process.waitFor();
			return output.contains("S-1-16-12288") || output.contains("S-1-16-16384");
		} catch (IOException e) {
			return false;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}
}
