package com.vogella.eclipse.installer;

import java.time.Instant;
import java.util.Optional;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;

import com.vogella.eclipse.installer.ui.InstallerWindow;
import com.vogella.eclipse.installer.ui.UpdateNotification;

/** Runs the installer window or a headless installation. */
public class InstallerApplication implements IApplication {

	private static final Integer EXIT_FAILED = 1;
	private static final Integer EXIT_USAGE = 2;

	@Override
	public Object start(IApplicationContext context) throws Exception {
		context.applicationRunning();
		String[] args = (String[]) context.getArguments().get(IApplicationContext.APPLICATION_ARGS);
		Settings settings;
		try {
			settings = Settings.load(args == null ? new String[0] : args);
		} catch (IllegalArgumentException e) {
			return fail(e.getMessage() + System.lineSeparator() + Settings.USAGE, EXIT_USAGE);
		}
		if (settings.help) {
			System.out.println(Settings.USAGE);
			return EXIT_OK;
		}
		if (!settings.headless) {
			return new InstallerWindow(settings).open();
		}
		System.out.println(settings.name + " installer");
		Optional<Instant> newer = settings.updateCheck ? UpdateCheck.newerRelease(settings.installerUrl)
				: Optional.empty();
		if (newer.isPresent()) {
			System.out.println("Note: a newer installer is available (built " + UpdateCheck.date(newer.get()) + "): "
					+ settings.releasePage);
			Integer code = UpdateNotification.runWithNotification(settings, newer.get(), () -> runHeadless(settings));
			if (code != null) {
				return code;
			}
		}
		return runHeadless(settings);
	}

	private static Integer runHeadless(Settings settings) {
		try {
			StatusListener console = new StatusListener() {
				@Override
				public void step(String name) {
					System.out.println("==> " + name);
				}

				@Override
				public void log(String line) {
					System.out.println("    " + line);
				}
			};
			Installer.Result result = new Installer(settings, console).run();
			System.out.println("Done: " + result.target());
			return EXIT_OK;
		} catch (IllegalArgumentException e) {
			return fail(e.getMessage(), EXIT_USAGE);
		} catch (CoreException e) {
			return fail(e.getMessage(), EXIT_FAILED);
		} catch (RuntimeException e) {
			e.printStackTrace();
			return fail(e.toString(), EXIT_FAILED);
		}
	}

	private static Integer fail(String message, Integer code) {
		System.err.println("ERROR: " + message);
		// Keeps the native launcher from showing an error dialog.
		System.setProperty("eclipse.exitdata", "");
		return code;
	}

	@Override
	public void stop() {
	}
}
