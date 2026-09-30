package com.vogella.eclipse.installer;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Installer inputs from the built-in defaults and the command line. */
public final class Settings {

	public record Feature(String id, String label) {

		public String displayName() {
			return label != null ? label : id;
		}
	}

	public static final String USAGE = """
			Usage: eclipsec --headless [options]
			  --target <dir>            installation folder
			  --name <name>             application name shown in the window and messages
			  --application-url <url>   application archive (.zip or .tar.gz), used if the target is empty
			  --repositories <a,b,...>  update sites
			  --features <a,b,...>      feature IUs to install or update, for example org.eclipse.egit.feature.group
			  --cache-dir <dir>         download cache
			  --clean                   delete the target installation first (downloads are kept)
			  --headless                run without a window
			  --no-update-check         do not look for a newer installer release
			""";

	private static final Pattern VARIABLE = Pattern.compile("\\$\\{([^}]+)}");

	public String name;
	public URI applicationUrl;
	public Path target;
	public List<URI> repositories = List.of();
	public List<Feature> features = List.of();
	public Path cacheDir;
	public boolean clean;
	public boolean headless;
	public boolean help;
	public String[] args = new String[0];
	public boolean updateCheck = true;
	public URI installerUrl;
	public URI releasePage;

	public static Settings load(String[] args) throws IOException {
		Properties props = new Properties();
		try (InputStream in = Settings.class.getResourceAsStream("/defaults.properties")) {
			if (in != null) {
				props.load(in);
			}
		}
		String urlOverride = null;
		Settings s = new Settings();
		s.args = args.clone();
		for (int i = 0; i < args.length; i++) {
			String arg = args[i];
			switch (arg) {
			case "--headless" -> s.headless = true;
			case "--clean" -> s.clean = true;
			case "--no-update-check" -> s.updateCheck = false;
			case "--help", "-h", "-?" -> s.help = true;
			case "--target" -> props.setProperty("target", value(args, ++i, arg));
			case "--name" -> props.setProperty("name", value(args, ++i, arg));
			case "--application-url" -> urlOverride = value(args, ++i, arg);
			case "--repositories" -> props.setProperty("repositories", value(args, ++i, arg));
			case "--features" -> props.setProperty("features", value(args, ++i, arg));
			case "--cache-dir" -> props.setProperty("cacheDir", value(args, ++i, arg));
			default -> {
				if (arg.startsWith("--")) {
					throw new IllegalArgumentException("Unknown option " + arg);
				}
			}
			}
		}

		String os = System.getProperty("osgi.os", "");
		s.name = get(props, "name", "Eclipse");
		String url = urlOverride != null ? urlOverride
				: get(props, "applicationUrl." + os, get(props, "applicationUrl", null));
		s.applicationUrl = url == null || url.isBlank() ? null : URI.create(url.trim());
		String target = get(props, "target", null);
		s.target = (target == null || target.isBlank() ? defaultTarget(os, s.name) : Path.of(target)).toAbsolutePath()
				.normalize();
		s.repositories = split(get(props, "repositories", "")).stream().map(URI::create).toList();
		List<Feature> features = new ArrayList<>();
		for (String id : split(get(props, "features", ""))) {
			features.add(new Feature(id, get(props, "label." + id, null)));
		}
		s.features = List.copyOf(features);
		String installer = get(props, "installerUrl." + os, null);
		s.installerUrl = installer == null || installer.isBlank() ? null : URI.create(installer);
		String release = get(props, "releasePage", null);
		s.releasePage = release == null || release.isBlank() ? null : URI.create(release);
		String cache = get(props, "cacheDir", null);
		s.cacheDir = (cache != null ? Path.of(cache) : defaultCacheDir(os)).toAbsolutePath().normalize();
		return s;
	}

	private static String value(String[] args, int index, String option) {
		if (index >= args.length || args[index].startsWith("--")) {
			throw new IllegalArgumentException("Missing value for " + option);
		}
		return args[index];
	}

	private static String get(Properties props, String key, String fallback) {
		String value = props.getProperty(key);
		return value == null ? fallback : expand(value.trim());
	}

	private static String expand(String value) {
		Matcher m = VARIABLE.matcher(value);
		StringBuilder sb = new StringBuilder();
		while (m.find()) {
			String name = m.group(1);
			String replacement = name.startsWith("env.") ? System.getenv(name.substring(4))
					: System.getProperty(name);
			m.appendReplacement(sb, Matcher.quoteReplacement(replacement == null ? "" : replacement));
		}
		m.appendTail(sb);
		return sb.toString();
	}

	private static List<String> split(String value) {
		return Arrays.stream(value.split(",")).map(String::trim).filter(v -> !v.isEmpty()).toList();
	}

	/** Per-user applications live in %LOCALAPPDATA%\Programs on Windows, which needs no administrator rights. */
	private static Path defaultTarget(String os, String name) {
		String localAppData = System.getenv("LOCALAPPDATA");
		if ("win32".equals(os) && localAppData != null) {
			String folder = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
					.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
			return Path.of(localAppData, "Programs", folder.isEmpty() ? "eclipse" : folder);
		}
		return Path.of(System.getProperty("user.home"), "eclipse", "sdk");
	}

	private static Path defaultCacheDir(String os) {
		String home = System.getProperty("user.home");
		String localAppData = System.getenv("LOCALAPPDATA");
		if ("win32".equals(os) && localAppData != null) {
			return Path.of(localAppData, "eclipse-installer");
		}
		String xdg = System.getenv("XDG_CACHE_HOME");
		return Path.of(xdg != null && !xdg.isBlank() ? xdg : home + "/.cache", "eclipse-installer");
	}

	public void validate() {
		if (repositories.isEmpty()) {
			throw new IllegalArgumentException("No update sites given (--repositories)");
		}
		if (features.isEmpty()) {
			throw new IllegalArgumentException("No features given (--features)");
		}
		Path realCache = real(cacheDir);
		Path realTarget = real(target);
		if (realCache.startsWith(realTarget) || realTarget.startsWith(realCache)) {
			throw new IllegalArgumentException("The cache folder and the target folder must not contain each other");
		}
	}

	/** Resolves symbolic links in the part of the path that exists already. */
	private static Path real(Path path) {
		Path existing = path.toAbsolutePath().normalize();
		while (existing != null && !Files.exists(existing)) {
			existing = existing.getParent();
		}
		if (existing == null) {
			return path.toAbsolutePath().normalize();
		}
		try {
			return existing.toRealPath().resolve(existing.relativize(path.toAbsolutePath().normalize()));
		} catch (IOException e) {
			return path.toAbsolutePath().normalize();
		}
	}

	/** A copy for one run, so the window's choices do not change the configuration. */
	public Settings forRun(Path runTarget, List<Feature> runFeatures, boolean runClean) {
		Settings copy = new Settings();
		copy.name = name;
		copy.applicationUrl = applicationUrl;
		copy.target = runTarget;
		copy.repositories = repositories;
		copy.features = List.copyOf(runFeatures);
		copy.cacheDir = cacheDir;
		copy.clean = runClean;
		copy.headless = headless;
		copy.updateCheck = updateCheck;
		copy.installerUrl = installerUrl;
		copy.releasePage = releasePage;
		copy.args = args.clone();
		return copy;
	}

	@Override
	public String toString() {
		return String.format(Locale.ROOT, "target=%s%napplication=%s%nrepositories=%s%nfeatures=%s%ncache=%s%nclean=%s",
				target, applicationUrl, repositories, features.stream().map(Feature::id).toList(), cacheDir, clean);
	}
}
