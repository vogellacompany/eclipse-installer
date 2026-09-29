package com.vogella.eclipse.installer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.Status;

/** Extracts the application into an empty target and installs or updates the features. */
public final class Installer {

	public record Result(Path target, boolean extracted, List<String> changedFeatures) {
	}

	private final Settings settings;
	private final StatusListener listener;

	public Installer(Settings settings, StatusListener listener) {
		this.settings = settings;
		this.listener = listener;
	}

	public Result run() throws CoreException {
		settings.validate();
		Path target = settings.target;
		try {
			if (settings.clean) {
				listener.step("Removing " + target);
				clean(target);
			}
			boolean extract = Archives.isMissingOrEmpty(target);
			if (extract) {
				if (settings.applicationUrl == null) {
					throw new CoreException(Status.error(target + " is empty and no application archive is configured"));
				}
				listener.step("Downloading " + fileName(settings.applicationUrl.getPath()));
				Path archive = Downloader.download(settings.applicationUrl, settings.cacheDir, listener);
				listener.step("Extracting " + archive.getFileName());
				Archives.extract(archive, target, listener);
			} else {
				listener.log("Keeping the existing installation in " + target);
			}
			Path home = home(target);
			if (home == null) {
				throw new CoreException(Status.error(target + " has no p2 folder, so features cannot be installed into it"));
			}
			List<String> changed = new P2Provisioner(home, listener).installOrUpdate(settings.repositories,
					settings.features);
			if (changed.isEmpty()) {
				listener.log("Nothing to install or update");
			}
			return new Result(target, extract, changed);
		} catch (IOException e) {
			throw new CoreException(Status.error(e.getMessage(), e));
		}
	}

	/** Deletes only empty folders and Eclipse installations. */
	static void clean(Path target) throws IOException, CoreException {
		if (!Files.exists(target)) {
			return;
		}
		if (!Files.isDirectory(target)) {
			throw new CoreException(Status.error(target + " is a file, not an installation folder"));
		}
		if (!Archives.isMissingOrEmpty(target) && !isInstallation(target)) {
			throw new CoreException(Status.error(
					target + " does not look like an Eclipse installation (no p2 folder, .eclipseproduct or eclipsec.exe), so it is not deleted"));
		}
		Archives.deleteRecursively(target);
	}

	public static boolean isInstallation(Path dir) {
		return home(dir) != null || Files.exists(dir.resolve(".eclipseproduct"))
				|| Files.exists(dir.resolve("eclipsec.exe"));
	}

	/** The folder holding the p2 data, or {@code null}. */
	public static Path home(Path target) {
		return Files.isDirectory(target.resolve("p2")) ? target : null;
	}

	public static List<String> launchCommand(Path target) {
		for (String name : List.of("eclipse.exe", "eclipse")) {
			Path file = target.resolve(name);
			if (Files.isRegularFile(file)) {
				return List.of(file.toString());
			}
		}
		return null;
	}

	private static String fileName(String path) {
		int slash = path.lastIndexOf('/');
		return slash >= 0 ? path.substring(slash + 1) : path;
	}
}
