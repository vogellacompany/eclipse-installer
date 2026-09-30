# Eclipse Installer

A small Eclipse product that installs an Eclipse application and keeps a set of its features up to date, with an SWT window and a headless mode.
It downloads the application archive into a cache, extracts it if the target folder is missing or empty, and installs or updates the latest versions of the configured features through p2, all in one JVM.
The plain p2 director stays available as `-application org.eclipse.equinox.p2.director`.
No JRE is bundled: Java 21 or newer is taken from the `PATH` or from `-vm`.

## Quick start

Windows (PowerShell):

```powershell
.\install-eclipse-local.ps1                              # opens the installer window
.\install-eclipse-local.ps1 -InstallDir C:\eclipse\sdk -Headless   # installs or updates headless
```

Linux x86_64:

```bash
./install-eclipse-local.sh                               # opens the installer window
./install-eclipse-local.sh --install-dir ~/eclipse/sdk --headless   # installs or updates headless
```

`install-eclipse-local.ps1` and `install-eclipse-local.sh` are small launchers that users keep: they download `install-eclipse-server.ps1` or `install-eclipse-server.sh` from the latest release and run it with all arguments passed through, so script changes reach users without redistributing the launcher.
They cache the last downloaded copy and fall back to it when GitHub cannot be reached; `-NoUpdate` / `--no-update` uses the cached copy and `-ScriptUrl` / `--script-url` runs another version, for example from a branch or a local file.

Without access to GitHub, download `install-eclipse-server.ps1` or `install-eclipse-server.sh` and the installer archive elsewhere and run the server script directly; it takes the same options, and `-InstallerUrl` / `--installer-url` accepts the local archive.

[installer-script.md](installer-script.md) describes all script options with complete examples.

The scripts check for Java 21 or newer, because the installer's launcher jar cannot start on older Java to report it.
They download the installer from the latest GitHub release (`-InstallerUrl` or `--installer-url` takes another URL or a local archive), cache it, fetch it again only when the release is newer, and start it.
Local paths in the application URL and the repositories become `file:` URIs, and zipped update sites `jar:file:...!/` URIs.

## Build

```bash
./mvnw clean verify
```

The build is pomless: Tycho 5.0.4 is set in `.mvn/maven.config` and enabled through `.mvn/extensions.xml`, so the bundle and the product have no `pom.xml` of their own, and the root `pom.xml` only lists them and holds the shared configuration.
It needs Java 21 or newer.
The archives land in `com.vogella.eclipse.installer.product/target/products/`:

| Archive | Platform |
|---|---|
| `eclipse-installer-win32.win32.x86_64.zip` | Windows x86_64 |
| `eclipse-installer-linux.gtk.x86_64.tar.gz` | Linux x86_64 |

Bundle versions come from the Eclipse I-build repository set by the `eclipse.repository` property in `pom.xml`.

## Release

Every push to `main` builds the product and replaces the assets of the single GitHub release tagged `latest`, together with the two scripts.
The scripts download from `https://github.com/vogellacompany/eclipse-installer/releases/latest/download/<archive>`.

## Installer options

Without options the installer opens its window.
`--headless` runs without a window and prints line-based progress; use `eclipsec.exe` for that on Windows.

| Option | Meaning |
|---|---|
| `--target <dir>` | installation folder |
| `--name <name>` | application name shown in the window and messages |
| `--application-url <url>` | application archive (`.zip` or `.tar.gz`), used only if the target is missing or empty |
| `--repositories <a,b>` | update sites |
| `--features <a,b>` | feature IUs, for example `org.eclipse.egit.feature.group` |
| `--cache-dir <dir>` | download cache |
| `--clean` | deletes the target first if it is empty or looks like an Eclipse installation; downloads are kept |

The exit code is 0 on success, 1 on failure and 2 for invalid options.
Archives in `.tar.gz` format are extracted with the system `tar`.

## Configuration

The built-in defaults are in `com.vogella.eclipse.installer/defaults.properties`.
Command-line options override them.
Without a `target`, Windows installs into `%LOCALAPPDATA%\Programs\<name>` (for example `eclipse-sdk`), the Windows convention for per-user applications that needs no administrator rights, and Linux into `~/eclipse/sdk`.
An installer running elevated on Windows offers no **Start** button, since Eclipse started from it would run as administrator and create its workspace in the administrator's profile.
Keys are `name`, `applicationUrl` (or `applicationUrl.win32`, `applicationUrl.linux`), `target`, `repositories`, `features`, `label.<feature id>` and `cacheDir`; `${user.home}` and `${env.NAME}` are expanded.

## Updates

A feature whose installed version equals or is newer than the latest available one is left alone, so switching to update sites with older versions never downgrades.
An outdated feature is updated in one plan that adds the latest version and removes the installed one; p2 would drop a feature whose identical version is both added and removed.
