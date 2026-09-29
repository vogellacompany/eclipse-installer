package com.vogella.eclipse.installer.ui;

import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jface.notifications.NotificationPopup;
import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTError;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Listener;

import com.vogella.eclipse.installer.Settings;
import com.vogella.eclipse.installer.UpdateCheck;

/** Notifies about a newer installer during a headless run. */
public final class UpdateNotification {

	private static final long DELAY_MILLIS = 20_000;

	private UpdateNotification() {
	}

	/** Returns {@code null} without running the installation if there is no display. */
	public static Integer runWithNotification(Settings settings, Instant released, Callable<Integer> installation) {
		Display display;
		try {
			Display.setAppName(settings.name + " Installer");
			display = new Display();
		} catch (SWTError | UnsatisfiedLinkError e) {
			return null;
		}
		try {
			AtomicReference<Integer> result = new AtomicReference<>(Integer.valueOf(1));
			Display d = display;
			Thread worker = new Thread(() -> {
				try {
					result.set(installation.call());
				} catch (Exception e) {
					System.err.println("ERROR: " + e);
				} finally {
					d.wake();
				}
			}, "Installer");
			worker.start();

			AtomicBoolean clicked = new AtomicBoolean();
			AtomicReference<NotificationPopup> popup = new AtomicReference<>();
			Listener open = e -> {
				clicked.set(true);
				popup.get().close();
			};
			popup.set(NotificationPopup.forDisplay(display).title(settings.name + " Installer", true)
					.content(parent -> content(parent, released, open)).delay(DELAY_MILLIS).build());
			popup.get().open();

			while (worker.isAlive() || isOpen(popup.get())) {
				if (!display.readAndDispatch()) {
					display.sleep();
				}
			}
			if (clicked.get()) {
				System.out.println("Opening the installer window");
				new InstallerWindow(settings).open(display);
			}
			return result.get();
		} finally {
			display.dispose();
		}
	}

	private static Control content(Composite parent, Instant released, Listener open) {
		Composite composite = new Composite(parent, SWT.NONE);
		GridLayout layout = new GridLayout(1, false);
		layout.marginWidth = 0;
		layout.marginHeight = 0;
		composite.setLayout(layout);
		Label text = new Label(composite, SWT.WRAP);
		text.setText("A newer installer is available (built " + UpdateCheck.date(released) + ").");
		text.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		Link link = new Link(composite, SWT.NONE);
		link.setText("<a>Open the installer to update</a>");
		link.addListener(SWT.Selection, open);
		composite.addListener(SWT.MouseUp, open);
		text.addListener(SWT.MouseUp, open);
		return composite;
	}

	private static boolean isOpen(NotificationPopup popup) {
		return popup.getShell() != null && !popup.getShell().isDisposed();
	}
}
