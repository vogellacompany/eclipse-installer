package com.vogella.eclipse.installer;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.Status;

/** Replaces the running installer with the published release and restarts it. */
public final class SelfUpdate {

	private final Settings settings;
	private final Path root;
	private final boolean windows;

	private SelfUpdate(Settings settings, Path home) {
		this.settings = settings;
		String os = System.getProperty("osgi.os", "");
		windows = "win32".equals(os);
		root = home;
	}

	public static SelfUpdate forRunningInstaller(Settings settings) {
		Path home = installHome();
		if (home == null || settings.installerUrl == null) {
			return null;
		}
		SelfUpdate update = new SelfUpdate(settings, home);
		Path parent = update.root.getParent();
		return parent != null && Files.isWritable(parent) ? update : null;
	}

	/** The caller must exit right after this returns. */
	public void prepareAndSchedule(StatusListener listener) throws CoreException {
		Path staging = null;
		try {
			// A folder of our own; the helper swaps the new installer in from here and deletes it afterwards.
			staging = Files.createTempDirectory(root.getParent(), root.getFileName() + ".update-");
			Path next = staging.resolve("new");
			Path archive = staging.resolve(Path.of(settings.installerUrl.getPath()).getFileName().toString());
			listener.step("Downloading the new installer");
			Downloader.fetch(settings.installerUrl, archive, listener);
			listener.step("Extracting the new installer");
			Archives.extract(archive, next, listener);
			Files.delete(archive);
			if (!Files.exists(next.resolve(relativeLauncher()))) {
				throw new CoreException(Status.error("The downloaded installer has no launcher " + relativeLauncher()));
			}
			startHelper(next);
		} catch (IOException | CoreException | RuntimeException e) {
			deleteQuietly(staging);
			if (e instanceof CoreException core) {
				throw core;
			}
			if (e instanceof RuntimeException runtime) {
				throw runtime;
			}
			throw new CoreException(Status.error("Preparing the update failed: " + e.getMessage(), e));
		}
	}

	private String relativeLauncher() {
		return windows ? "eclipse.exe" : "eclipse";
	}

	private void startHelper(Path next) throws IOException {
		List<String> pids = new ArrayList<>();
		ProcessHandle current = ProcessHandle.current();
		pids.add(Long.toString(current.pid()));
		// A launcher that forked the JVM holds its files too.
		current.parent().filter(parent -> parent.info().command().map(Path::of)
				.filter(command -> command.toAbsolutePath().startsWith(root)).isPresent())
				.ifPresent(parent -> pids.add(Long.toString(parent.pid())));
		String java = Path.of(System.getProperty("java.home"), "bin", windows ? "javaw.exe" : "java").toString();
		List<String> relaunch = new ArrayList<>(List.of("-vm", java));
		relaunch.addAll(List.of(settings.args));
		Path script = Files.createTempFile("eclipse-installer-update", windows ? ".ps1" : ".sh");
		try (InputStream in = SelfUpdate.class.getResourceAsStream(windows ? "/scripts/update.ps1" : "/scripts/update.sh")) {
			Files.copy(in, script, StandardCopyOption.REPLACE_EXISTING);
		}
		List<String> command = new ArrayList<>();
		if (windows) {
			Path arguments = Files.createTempFile("eclipse-installer-update", ".args");
			Files.write(arguments, relaunch);
			command.addAll(List.of("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden",
					"-File", script.toString(), "-Pids", String.join(" ", pids), "-Root", root.toString(), "-Next",
					next.toString(), "-ArgumentsFile", arguments.toString()));
		} else {
			command.addAll(List.of("nohup", "/bin/sh", script.toString(), String.join(" ", pids), root.toString(),
					next.toString()));
			command.addAll(relaunch);
		}
		new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD)
				.redirectError(ProcessBuilder.Redirect.DISCARD).start();
	}

	private static void deleteQuietly(Path path) {
		if (path != null) {
			try {
				Archives.deleteRecursively(path);
			} catch (IOException e) {
				// Only a leftover temporary folder.
			}
		}
	}

	private static Path installHome() {
		String location = System.getProperty("eclipse.home.location");
		if (location == null) {
			return null;
		}
		try {
			return Path.of(new URI(location.replace(" ", "%20")));
		} catch (URISyntaxException | IllegalArgumentException e) {
			return null;
		}
	}
}
