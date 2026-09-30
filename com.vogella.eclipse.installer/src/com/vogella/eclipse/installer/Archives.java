package com.vogella.eclipse.installer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Status;

/** Extracts archives and handles the files around them. */
public final class Archives {

	private Archives() {
	}

	/** Extracts via a new staging folder next to the target, dropping a single top-level folder. */
	static void extract(Path archive, Path target, StatusListener listener) throws CoreException {
		Path part = null;
		try {
			checkCanceled(listener);
			Files.createDirectories(target.getParent());
			part = Files.createTempDirectory(target.getParent(), target.getFileName() + ".part-");
			String name = archive.getFileName().toString().toLowerCase(Locale.ROOT);
			if (name.endsWith(".zip")) {
				unzip(archive, part, listener);
			} else if (name.endsWith(".tar.gz") || name.endsWith(".tgz")) {
				untar(archive, part, listener);
			} else {
				throw new CoreException(Status.error("Unsupported archive format: " + archive.getFileName()));
			}
			Path root = part;
			List<Path> entries = list(part);
			if (entries.size() == 1 && Files.isDirectory(entries.get(0))) {
				root = entries.get(0);
			}
			if (Files.exists(target)) {
				Files.delete(target);
			}
			move(root, target);
		} catch (IOException e) {
			throw new CoreException(Status.error("Extracting " + archive.getFileName() + " failed: " + e.getMessage(), e));
		} finally {
			if (part != null) {
				try {
					deleteRecursively(part);
				} catch (IOException e) {
					listener.log("Could not delete " + part + ": " + e.getMessage());
				}
			}
		}
	}

	/** Reads the zip through the zip file system, which also exposes the Unix permissions of its entries. */
	private static void unzip(Path archive, Path dest, StatusListener listener) throws IOException, CoreException {
		boolean posix = dest.getFileSystem().supportedFileAttributeViews().contains("posix");
		try (FileSystem zip = FileSystems.newFileSystem(archive, Map.of("enablePosixFileAttributes", "true"))) {
			Path top = zip.getPath("/");
			try (var entries = Files.walk(top)) {
				for (Path entry : (Iterable<Path>) entries::iterator) {
					checkCanceled(listener);
					String name = top.relativize(entry).toString();
					if (name.isEmpty()) {
						continue;
					}
					Path out = resolve(dest, name);
					if (Files.isDirectory(entry)) {
						Files.createDirectories(out);
						continue;
					}
					Files.createDirectories(out.getParent());
					Files.copy(entry, out, StandardCopyOption.REPLACE_EXISTING);
					if (posix && Files.getPosixFilePermissions(entry).contains(PosixFilePermission.OWNER_EXECUTE)) {
						out.toFile().setExecutable(true, false);
					}
				}
			}
		}
	}

	/** Runs a command and stops it when the run is canceled. */
	private static void run(StatusListener listener, String... command) throws IOException, CoreException {
		checkCanceled(listener);
		Path log = Files.createTempFile("eclipse-installer", ".log");
		try {
			Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile())
					.start();
			while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
				if (listener.isCanceled()) {
					process.destroyForcibly().waitFor();
					throw new OperationCanceledException();
				}
			}
			String output = Files.readString(log, StandardCharsets.UTF_8).trim();
			if (process.exitValue() != 0) {
				throw new CoreException(Status.error(String.join(" ", command) + " failed: " + output));
			}
			if (!output.isEmpty()) {
				listener.log(output);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new OperationCanceledException();
		} finally {
			Files.deleteIfExists(log);
		}
	}

	private static void untar(Path archive, Path dest, StatusListener listener) throws IOException, CoreException {
		run(listener, "tar", "-xzf", archive.toString(), "-C", dest.toString());
	}

	static Path resolve(Path base, String entry) throws IOException {
		Path out = base.resolve(entry).normalize();
		if (!out.startsWith(base.normalize())) {
			throw new IOException("Archive entry points outside the target folder: " + entry);
		}
		return out;
	}

	private static void checkCanceled(StatusListener listener) {
		if (listener.isCanceled()) {
			throw new OperationCanceledException();
		}
	}

	static List<Path> list(Path dir) throws IOException {
		try (var stream = Files.list(dir)) {
			return stream.toList();
		}
	}

	public static boolean isMissingOrEmpty(Path dir) throws IOException {
		return !Files.exists(dir) || (Files.isDirectory(dir) && list(dir).isEmpty());
	}

	static void deleteRecursively(Path path) throws IOException {
		if (!Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
			return;
		}
		Files.walkFileTree(path, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				delete(file);
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
				if (exc != null) {
					throw exc;
				}
				delete(dir);
				return FileVisitResult.CONTINUE;
			}
		});
	}

	private static void delete(Path path) throws IOException {
		try {
			Files.delete(path);
		} catch (java.nio.file.AccessDeniedException e) {
			path.toFile().setWritable(true);
			Files.delete(path);
		}
	}

	/** Retries because virus scanners hold fresh files. */
	private static void move(Path from, Path to) throws IOException {
		for (int attempt = 1;; attempt++) {
			try {
				Files.move(from, to);
				return;
			} catch (IOException e) {
				if (attempt == 10) {
					throw e;
				}
				try {
					Thread.sleep(500);
				} catch (InterruptedException ie) {
					Thread.currentThread().interrupt();
					throw e;
				}
			}
		}
	}
}
