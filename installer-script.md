# Installer script

`install-eclipse-local.sh` (Linux x86_64) and `install-eclipse-local.ps1` (Windows) install an Eclipse application and install or update a set of its features.
They are small launchers: each downloads the current `install-eclipse-server.sh` or `install-eclipse-server.ps1` from the latest GitHub release and runs it with all options passed through.
If GitHub cannot be reached, they fall back to the last cached copy of that script.

## Download

Linux:

```bash
curl -fsSLO https://github.com/vogellacompany/eclipse-installer/releases/latest/download/install-eclipse-local.sh
```

Windows:

```powershell
Invoke-WebRequest https://github.com/vogellacompany/eclipse-installer/releases/latest/download/install-eclipse-local.ps1 -OutFile install-eclipse-local.ps1
Unblock-File .\install-eclipse-local.ps1
```

A `.ps1` downloaded from the internet is blocked by default, hence `Unblock-File`.
Alternatively, start every run with `powershell -ExecutionPolicy Bypass -File .\install-eclipse-local.ps1 ...`.

Java 21 or newer is required, either on the `PATH` or given with the Java option.

## Options

| What | Linux | Windows |
|---|---|---|
| Java 21 or newer | `--java-home <jdk>` | `-JavaHome <jdk>` |
| Application name shown by the installer | `--name "<name>"` | `-Name "<name>"` |
| Application archive, a URL or a local file | `--application-url <url>` | `-ApplicationUrl <url>` |
| Update sites: URLs, folders or zipped p2 update sites | `--repositories <a>,<b>` | `-Repositories <a>,<b>` |
| Features to install or update | `--features <id>,<id>` | `-Features <id>,<id>` |
| Folder for the installer and its downloads | `--cache-dir <dir>` | `-CacheDir <dir>` |
| Target folder of the application | `--install-dir <dir>` | `-InstallDir <dir>` |
| Run without a window | `--headless` | `-Headless` |
| Start from scratch | `--clean` | `-Clean` |

Options that are not given fall back to the installer's defaults.
The installer is extracted inside the cache folder, next to the downloaded archives.

A local zip in the update sites must contain the p2 metadata (`content.jar` and `artifacts.jar`, or their composite variants) at its top level.

## Headless

Linux:

```bash
./install-eclipse-local.sh \
  --java-home ~/.sdkman/candidates/java/21.0.10-tem \
  --name "Eclipse for RCP Developers" \
  --application-url https://download.eclipse.org/technology/epp/downloads/release/2026-06/R/eclipse-rcp-2026-06-R-linux-gtk-x86_64.tar.gz \
  --repositories https://download.eclipse.org/releases/2026-06,https://download.eclipse.org/egit/updates-nightly/,https://vogellacompany.github.io/eclipse-themes/ \
  --features org.eclipse.egit.feature.group,org.eclipse.egit.gitflow.feature.feature.group,org.eclipse.jgit.feature.group,com.vogella.eclipse.themes.feature.feature.group \
  --cache-dir ~/eclipse-installer \
  --install-dir ~/eclipse/rcp \
  --headless
```

Windows:

```powershell
.\install-eclipse-local.ps1 `
  -JavaHome "C:\Program Files\Eclipse Adoptium\jdk-21" `
  -Name "Eclipse for RCP Developers" `
  -ApplicationUrl https://download.eclipse.org/technology/epp/downloads/release/2026-06/R/eclipse-rcp-2026-06-R-win32-x86_64.zip `
  -Repositories https://download.eclipse.org/releases/2026-06,https://download.eclipse.org/egit/updates-nightly/,https://vogellacompany.github.io/eclipse-themes/ `
  -Features org.eclipse.egit.feature.group,org.eclipse.egit.gitflow.feature.feature.group,org.eclipse.jgit.feature.group,com.vogella.eclipse.themes.feature.feature.group `
  -CacheDir C:\eclipse-installer `
  -InstallDir C:\eclipse\rcp `
  -Headless
```

The run prints its steps and ends with `Done: <folder>`.
The exit code is 0 on success, 1 on failure and 2 for invalid options.

## With the window

The same commands without `--headless` or `-Headless` open the installer window with the name, the target folder and the features filled in.
The target folder can still be changed there, and **Install** (or **Update** for an existing installation) starts the run.

## Choosing features

The RCP package already contains EGit, Gitflow and JGit, and each of these features pins the others to the same version.
Updating only `org.eclipse.egit.feature.group` therefore fails with "conflicting dependency", so the examples list all three to update them together.
In general, list every installed feature that depends on the one you update.

## Update or start from scratch

Without the clean option, an existing installation is kept and only its features are updated:

- The application stays as it is; the application archive is used only when the target folder is missing or empty.
- Each listed feature is installed if it is missing and updated if a newer version is available; features that are up to date, or newer than what the update sites offer, are left alone.
- Features that were installed earlier but are no longer listed are neither removed nor updated.
- A folder that is not empty but has no `p2` folder stops the run, since features cannot be installed into it.

With `--clean` or `-Clean`, the run starts from scratch:

- The target folder is deleted first, but only if it is empty or looks like an Eclipse installation (it contains `p2`, `.eclipseproduct` or `eclipsec.exe`); any other folder stops the run and is kept.
- The application and the installer are extracted again from their cached archives, and all features are installed fresh.
- The downloads in the cache folder are kept, so nothing is downloaded again.

Moving an existing installation to a new application version therefore needs the clean option together with the new application archive.

## Without access to GitHub

Download `install-eclipse-server.sh` or `install-eclipse-server.ps1` and the installer archive elsewhere and run the server script directly with the same options.
`--installer-url` or `-InstallerUrl` takes the local installer archive.
The application archive and the update sites must still be reachable, or be given as local files.
