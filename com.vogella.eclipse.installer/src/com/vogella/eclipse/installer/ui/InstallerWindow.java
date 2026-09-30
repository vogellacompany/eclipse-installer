package com.vogella.eclipse.installer.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.jface.resource.FontDescriptor;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.resource.LocalResourceManager;
import org.eclipse.jface.resource.ResourceManager;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;

import com.vogella.eclipse.installer.Archives;
import com.vogella.eclipse.installer.FeatureNames;
import com.vogella.eclipse.installer.Installer;
import com.vogella.eclipse.installer.StatusListener;
import com.vogella.eclipse.installer.SelfUpdate;
import com.vogella.eclipse.installer.Settings.Feature;
import com.vogella.eclipse.installer.Settings;
import com.vogella.eclipse.installer.UpdateCheck;

/** The installer window. */
public final class InstallerWindow {

	private final Settings settings;
	private Display display;
	private Shell shell;
	private ResourceManager resources;

	private Text targetText;
	private Button browseButton;
	private Label targetState;
	private Table featureTable;
	private Label featureStatus;
	private Composite statusArea;
	private Label stepLabel;
	private Label resultLabel;
	private Link detailsLink;
	private Text logText;
	private Composite buttonBar;
	private Composite updateBar;
	private Label title;
	private Label updateText;
	private Link updateLink;
	private SelfUpdate selfUpdate;

	private volatile boolean canceled;
	private boolean running;
	private boolean closeWhenDone;

	public InstallerWindow(Settings settings) {
		this.settings = settings;
	}

	public Integer open() {
		Display.setAppName(settings.name + " Installer");
		Display created = new Display();
		try {
			return open(created);
		} finally {
			created.dispose();
		}
	}

	public Integer open(Display existing) {
		display = existing;
		createShell();
		shell.open();
		checkForUpdate();
		loadFeatureNames();
		while (!shell.isDisposed()) {
			if (!display.readAndDispatch()) {
				display.sleep();
			}
		}
		return Integer.valueOf(0);
	}

	private void createShell() {
		shell = new Shell(display, SWT.SHELL_TRIM);
		shell.setText(settings.name + " Installer");
		resources = new LocalResourceManager(JFaceResources.getResources(display), shell);
		GridLayoutFactory.swtDefaults().margins(24, 20).spacing(5, 6).applyTo(shell);

		createUpdateBar();

		title = new Label(shell, SWT.NONE);
		title.setLayoutData(new GridData());
		title.setText(settings.name);
		title.setFont(font(1.7, SWT.BOLD));
		Label subtitle = new Label(shell, SWT.WRAP);
		subtitle.setText("Installs " + settings.name + " and keeps its features up to date.");
		subtitle.setLayoutData(fill());

		section("Install folder");
		Composite targetRow = new Composite(shell, SWT.NONE);
		GridLayoutFactory.fillDefaults().numColumns(2).applyTo(targetRow);
		targetRow.setLayoutData(fill());
		targetText = new Text(targetRow, SWT.BORDER | SWT.SINGLE);
		targetText.setText(settings.target.toString());
		GridDataFactory.fillDefaults().grab(true, false).hint(380, SWT.DEFAULT).applyTo(targetText);
		targetText.addModifyListener(e -> updateTargetState());
		browseButton = new Button(targetRow, SWT.PUSH);
		browseButton.setText("Browse…");
		browseButton.addListener(SWT.Selection, e -> browse());
		targetState = new Label(shell, SWT.WRAP);
		targetState.setLayoutData(fill());

		section("Features");
		featureTable = new Table(shell, SWT.CHECK | SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL);
		for (Feature feature : settings.features) {
			TableItem item = new TableItem(featureTable, SWT.NONE);
			item.setText(feature.displayName());
			item.setData(feature);
			item.setChecked(true);
		}
		GridData tableData = fill();
		tableData.heightHint = Math.max(3, Math.min(settings.features.size(), 6)) * featureTable.getItemHeight()
				+ 4;
		featureTable.setLayoutData(tableData);
		featureTable.addListener(SWT.Selection, e -> updateTargetState());
		featureStatus = new Label(shell, SWT.NONE);
		featureStatus.setLayoutData(fill());
		setVisible(featureStatus, false);

		statusArea = new Composite(shell, SWT.NONE);
		GridLayoutFactory.fillDefaults().extendedMargins(0, 0, 10, 0).spacing(5, 4).applyTo(statusArea);
		statusArea.setLayoutData(fill());
		stepLabel = new Label(statusArea, SWT.NONE);
		stepLabel.setLayoutData(fill());
		resultLabel = new Label(statusArea, SWT.WRAP);
		resultLabel.setFont(font(1.15, SWT.BOLD));
		GridDataFactory.fillDefaults().grab(true, false).hint(400, SWT.DEFAULT).applyTo(resultLabel);
		detailsLink = new Link(statusArea, SWT.NONE);
		detailsLink.setText("<a>Show details</a>");
		detailsLink.addListener(SWT.Selection, e -> showLog(!logText.getVisible()));
		logText = new Text(statusArea, SWT.MULTI | SWT.READ_ONLY | SWT.BORDER | SWT.V_SCROLL | SWT.H_SCROLL);
		GridDataFactory.fillDefaults().grab(true, true).hint(SWT.DEFAULT, 180).applyTo(logText);
		setVisible(resultLabel, false);
		setVisible(logText, false);
		setVisible(statusArea, false);

		Label spacer = new Label(shell, SWT.NONE);
		spacer.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

		buttonBar = new Composite(shell, SWT.NONE);
		buttonBar.setLayoutData(new GridData(SWT.FILL, SWT.END, true, false));

		shell.addListener(SWT.Close, e -> {
			if (running) {
				e.doit = false;
				closeWhenDone = true;
				cancel();
			}
		});

		updateTargetState();
		resize();
	}

	private void createUpdateBar() {
		updateBar = new Composite(shell, SWT.NONE);
		GridLayoutFactory.swtDefaults().numColumns(3).margins(12, 8).spacing(10, 5).applyTo(updateBar);
		updateBar.setLayoutData(fill());
		updateText = new Label(updateBar, SWT.WRAP);
		updateText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		updateLink = new Link(updateBar, SWT.NONE);
		updateLink.addListener(SWT.Selection, e -> {
			if (selfUpdate != null && "update".equals(e.text)) {
				updateAndRestart();
			} else if (settings.releasePage != null) {
				Program.launch(settings.releasePage.toString());
			}
		});
		Label close = new Label(updateBar, SWT.NONE);
		close.setText("\u2715");
		close.setToolTipText("Hide");
		close.setCursor(display.getSystemCursor(SWT.CURSOR_HAND));
		close.addListener(SWT.MouseUp, e -> {
			setVisible(updateBar, false);
			((GridData) title.getLayoutData()).verticalIndent = 0;
			shell.layout(true, true);
		});
		setVisible(updateBar, false);
	}

	private void loadFeatureNames() {
		if (settings.features.isEmpty() || settings.repositories.isEmpty()) {
			return;
		}
		featureStatus.setText("Reading feature names from the update sites…");
		setVisible(featureStatus, true);
		layout();
		List<String> ids = settings.features.stream().map(Feature::id).toList();
		background("Installer feature names", () -> {
			Map<String, String> names;
			try {
				names = FeatureNames.load(settings.repositories, ids);
			} catch (IOException e) {
				names = Map.of();
			}
			Map<String, String> result = names;
			ui(() -> showFeatureNames(result));
		});
	}

	private void showFeatureNames(Map<String, String> names) {
		if (shell.isDisposed()) {
			return;
		}
		setVisible(featureStatus, false);
		for (TableItem item : featureTable.getItems()) {
			Feature feature = (Feature) item.getData();
			if (!names.containsKey(feature.id())) {
				continue;
			}
			String name = names.get(feature.id());
			if (name == null) {
				item.setText(feature.displayName() + " (not in the update sites)");
			} else if (feature.label() == null) {
				item.setText(name);
			}
		}
		layout();
	}

	private void checkForUpdate() {
		if (!settings.updateCheck || settings.installerUrl == null) {
			return;
		}
		background("Installer update check",
				() -> UpdateCheck.newerRelease(settings.installerUrl).ifPresent(date -> ui(() -> showUpdate(date))));
	}

	private void showUpdate(java.time.Instant date) {
		if (shell.isDisposed()) {
			return;
		}
		updateText.setText("\u24D8  A newer installer is available (built " + UpdateCheck.date(date) + ").");
		selfUpdate = SelfUpdate.forRunningInstaller(settings);
		updateLink.setText(selfUpdate != null ? "<a href=\"update\">Update and restart</a>" : "<a>Download</a>");
		setVisible(updateBar, true);
		((GridData) title.getLayoutData()).verticalIndent = 10;
		layout();
	}

	private void updateAndRestart() {
		if (running) {
			return;
		}
		running = true;
		canceled = false;
		updateLink.setEnabled(false);
		targetText.setEnabled(false);
		browseButton.setEnabled(false);
		featureTable.setEnabled(false);
		setButtons();
		StatusListener listener = new StatusListener() {
			@Override
			public void step(String name) {
				ui(() -> updateText.setText("\u24D8  " + name + "…"));
			}

			@Override
			public boolean isCanceled() {
				return canceled;
			}
		};
		background("Installer self-update", () -> {
			try {
				selfUpdate.prepareAndSchedule(listener);
				ui(shell::dispose);
			} catch (CoreException | RuntimeException e) {
				ui(() -> updateFailed(e));
			}
		});
	}

	private void updateFailed(Exception e) {
		if (shell.isDisposed()) {
			return;
		}
		running = false;
		if (closeWhenDone) {
			shell.dispose();
			return;
		}
		updateText.setText(e instanceof OperationCanceledException ? "\u24D8  Update canceled."
				: "\u24D8  Update failed: " + firstLine(String.valueOf(e.getMessage())));
		updateLink.setText("<a>Open release page</a>");
		updateLink.setEnabled(true);
		targetText.setEnabled(true);
		browseButton.setEnabled(true);
		featureTable.setEnabled(true);
		updateTargetState();
		layout();
	}

	private static void background(String name, Runnable task) {
		Thread thread = new Thread(task, name);
		thread.setDaemon(true);
		thread.start();
	}

	private void ui(Runnable task) {
		if (!display.isDisposed()) {
			display.asyncExec(() -> {
				if (!shell.isDisposed()) {
					task.run();
				}
			});
		}
	}

	private void resize() {
		Point size = shell.computeSize(SWT.DEFAULT, SWT.DEFAULT);
		int width = Math.max(size.x, 620);
		Point preferred = shell.computeSize(width, SWT.DEFAULT);
		shell.setMinimumSize(560, preferred.y);
		Rectangle area = display.getPrimaryMonitor().getClientArea();
		int height = Math.min(preferred.y, area.height);
		shell.setBounds(area.x + (area.width - width) / 2, area.y + (area.height - height) / 3, width, height);
	}

	private void section(String text) {
		Label label = new Label(shell, SWT.NONE);
		label.setText(text);
		label.setFont(font(1.0, SWT.BOLD));
		GridData data = fill();
		data.verticalIndent = 14;
		label.setLayoutData(data);
	}

	private Font font(double scale, int style) {
		int height = (int) Math.round(shell.getFont().getFontData()[0].getHeight() * scale);
		return resources.create(FontDescriptor.createFrom(shell.getFont()).setHeight(height).setStyle(style));
	}

	private static GridData fill() {
		return new GridData(SWT.FILL, SWT.CENTER, true, false);
	}

	private void browse() {
		DirectoryDialog dialog = new DirectoryDialog(shell);
		dialog.setText("Install folder");
		dialog.setMessage("Choose an empty folder for a new installation, or an existing installation to update.");
		Path current = target();
		if (current != null) {
			Path existing = current;
			while (existing != null && !Files.isDirectory(existing)) {
				existing = existing.getParent();
			}
			if (existing != null) {
				dialog.setFilterPath(existing.toString());
			}
		}
		String result = dialog.open();
		if (result != null) {
			targetText.setText(result);
		}
	}

	private Path target() {
		String text = targetText.getText().trim();
		if (text.isEmpty()) {
			return null;
		}
		try {
			return Path.of(text).toAbsolutePath().normalize();
		} catch (InvalidPathException e) {
			return null;
		}
	}

	private enum TargetKind {
		INVALID, NEW, EXISTING, FOREIGN
	}

	private TargetKind targetKind(Path target) {
		if (target == null) {
			return TargetKind.INVALID;
		}
		try {
			if (Archives.isMissingOrEmpty(target)) {
				return TargetKind.NEW;
			}
		} catch (IOException e) {
			return TargetKind.INVALID;
		}
		if (Installer.home(target) != null) {
			return TargetKind.EXISTING;
		}
		return TargetKind.FOREIGN;
	}

	private void updateTargetState() {
		if (running) {
			return;
		}
		TargetKind kind = updateTargetLabel();
		boolean ok = !checkedFeatures().isEmpty() && (kind == TargetKind.NEW || kind == TargetKind.EXISTING);
		if (kind == TargetKind.EXISTING) {
			setButtons(new ButtonSpec("Reinstall…", false, ok, this::reinstall),
					new ButtonSpec("Update", true, ok, () -> start(false)));
		} else {
			setButtons(new ButtonSpec("Install", true, ok, () -> start(false)));
		}
	}

	private TargetKind updateTargetLabel() {
		TargetKind kind = targetKind(target());
		switch (kind) {
		case NEW -> targetState.setText(settings.name + " will be downloaded and installed here.");
		case EXISTING -> targetState.setText("Existing installation: selected features are installed or updated.");
		case FOREIGN -> targetState.setText("This folder is not empty and is not an Eclipse installation.");
		case INVALID -> targetState.setText("Enter a folder.");
		}
		return kind;
	}

	private List<Feature> checkedFeatures() {
		List<Feature> result = new ArrayList<>();
		for (TableItem item : featureTable.getItems()) {
			if (item.getChecked()) {
				result.add((Feature) item.getData());
			}
		}
		return result;
	}

	private void reinstall() {
		MessageBox box = new MessageBox(shell, SWT.ICON_WARNING | SWT.OK | SWT.CANCEL);
		box.setText("Reinstall");
		box.setMessage("Delete " + target() + " and install " + settings.name
				+ " again? Downloaded files are kept, but everything else in this folder is removed.");
		if (box.open() == SWT.OK) {
			start(true);
		}
	}

	private void start(boolean clean) {
		Settings run = settings.forRun(target(), checkedFeatures(), clean);
		canceled = false;
		running = true;
		targetText.setEnabled(false);
		browseButton.setEnabled(false);
		featureTable.setEnabled(false);
		logText.setText("");
		stepLabel.setText("Starting");
		setVisible(stepLabel, true);
		setVisible(resultLabel, false);
		setVisible(statusArea, true);
		setButtons(new ButtonSpec("Cancel", false, true, this::cancel));
		layout();

		UiStatus listener = new UiStatus();
		background("Installer", () -> {
			try {
				Installer.Result result = new Installer(run, listener).run();
				ui(() -> finished(listener, result, null));
			} catch (CoreException | RuntimeException e) {
				ui(() -> finished(listener, null, e));
			}
		});
	}

	private void cancel() {
		canceled = true;
		stepLabel.setText("Canceling…");
		setButtons();
	}

	private void finished(UiStatus listener, Installer.Result result, Exception failure) {
		if (shell.isDisposed()) {
			return;
		}
		listener.flush();
		running = false;
		if (closeWhenDone) {
			shell.dispose();
			return;
		}
		setVisible(stepLabel, false);
		setVisible(resultLabel, true);
		targetText.setEnabled(true);
		browseButton.setEnabled(true);
		featureTable.setEnabled(true);
		if (failure == null) {
			resultLabel.setText(result.changedFeatures().isEmpty() && !result.extracted()
					? "Everything is up to date."
					: settings.name + " is ready.");
			appendLog(resultLabel.getText());
			updateTargetLabel();
			List<String> launcher = Installer.launchCommand(result.target());
			List<ButtonSpec> buttons = new ArrayList<>();
			buttons.add(new ButtonSpec("Close", false, true, shell::close));
			buttons.add(new ButtonSpec("Open folder", false, true, () -> Program.launch(result.target().toString())));
			if (launcher != null) {
				buttons.add(new ButtonSpec("Start " + settings.name, true, true, () -> launch(launcher)));
			}
			setButtons(buttons.toArray(ButtonSpec[]::new));
		} else {
			boolean wasCanceled = failure instanceof OperationCanceledException;
			resultLabel.setText(wasCanceled ? "Canceled." : failure.getMessage() == null ? failure.toString()
					: firstLine(failure.getMessage()));
			appendLog(wasCanceled ? "Canceled" : "ERROR: " + (failure instanceof CoreException
					? failure.getMessage()
					: failure.toString()));
			if (!wasCanceled) {
				showLog(true);
			}
			updateTargetState();
		}
		layout();
	}

	private static String firstLine(String message) {
		int newline = message.indexOf('\n');
		return newline > 0 ? message.substring(0, newline).trim() : message;
	}

	private void launch(List<String> command) {
		try {
			new ProcessBuilder(command).directory(Path.of(command.get(0)).getParent().toFile())
					.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD)
					.start();
			shell.close();
		} catch (IOException e) {
			resultLabel.setText("Could not start " + settings.name + ": " + e.getMessage());
			layout();
		}
	}

	private void showLog(boolean show) {
		setVisible(logText, show);
		detailsLink.setText(show ? "<a>Hide details</a>" : "<a>Show details</a>");
		layout();
		if (show) {
			logText.setSelection(logText.getCharCount());
		}
	}

	private void appendLog(String line) {
		logText.append(line + Text.DELIMITER);
	}

	private void layout() {
		shell.layout(true, true);
		Point size = shell.getSize();
		Point preferred = shell.computeSize(size.x, SWT.DEFAULT);
		if (preferred.y > size.y) {
			Rectangle area = shell.getMonitor().getClientArea();
			shell.setSize(size.x, Math.min(preferred.y, area.height));
		}
	}

	private static void setVisible(Control control, boolean visible) {
		control.setVisible(visible);
		((GridData) control.getLayoutData()).exclude = !visible;
	}

	private record ButtonSpec(String text, boolean primary, boolean enabled, Runnable action) {
	}

	private void setButtons(ButtonSpec... specs) {
		for (Control child : buttonBar.getChildren()) {
			child.dispose();
		}
		GridLayoutFactory.fillDefaults().numColumns(specs.length + 1).extendedMargins(0, 0, 12, 0).spacing(8, 5)
				.applyTo(buttonBar);
		new Label(buttonBar, SWT.NONE).setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		for (ButtonSpec spec : specs) {
			Button button = new Button(buttonBar, SWT.PUSH);
			button.setText(spec.text());
			button.setEnabled(spec.enabled());
			button.addListener(SWT.Selection, e -> spec.action().run());
			GridData data = new GridData(SWT.END, SWT.CENTER, false, false);
			data.widthHint = Math.max(button.computeSize(SWT.DEFAULT, SWT.DEFAULT).x + 16, 96);
			button.setLayoutData(data);
			if (spec.primary()) {
				shell.setDefaultButton(button);
			}
		}
		if (shell.isVisible()) {
			layout();
		}
	}

	private final class UiStatus implements StatusListener {
		private final ConcurrentLinkedQueue<String> lines = new ConcurrentLinkedQueue<>();
		private final AtomicBoolean scheduled = new AtomicBoolean();
		private volatile String step;

		@Override
		public void step(String name) {
			step = name;
			lines.add(name);
			schedule();
		}

		@Override
		public void log(String line) {
			lines.add("  " + line);
			schedule();
		}

		@Override
		public boolean isCanceled() {
			return canceled;
		}

		private void schedule() {
			if (scheduled.compareAndSet(false, true) && !display.isDisposed()) {
				display.asyncExec(() -> {
					scheduled.set(false);
					flush();
				});
			}
		}

		void flush() {
			if (shell.isDisposed()) {
				return;
			}
			StringBuilder sb = new StringBuilder();
			String line;
			while ((line = lines.poll()) != null) {
				sb.append(line).append(Text.DELIMITER);
			}
			if (sb.length() > 0) {
				logText.append(sb.toString());
			}
			if (!running) {
				return;
			}
			if (!canceled && step != null) {
				stepLabel.setText(step);
			}
		}
	}
}
