# BitChord Desktop

BitChord has a Kotlin Multiplatform shared module and a Compose Multiplatform
desktop application for macOS, Linux, and Windows. Desktop uses the JVM target, which is
the supported Compose Multiplatform desktop model.

The desktop target uses Java 21.

On macOS (Apple Silicon or Intel), run it with a Java 21 JDK available in your environment:

```bash
./gradlew :desktopApp:run
```

On Linux, run it with the JDK and native libraries available in the shell:

On NixOS:
```bash
nix-shell -p jdk21 libglvnd glib gtk3 pango atk cairo cmake gdk-pixbuf libXtst libXxf86vm alsa-lib ffmpeg_6 --run '\
  export LD_LIBRARY_PATH="$(nix eval --raw nixpkgs#libglvnd.outPath)/lib:$(nix eval --raw nixpkgs#glib.out)/lib:$(nix eval --raw nixpkgs#gtk3.outPath)/lib:$(nix eval --raw nixpkgs#pango.out)/lib:$(nix eval --raw nixpkgs#atk.outPath)/lib:$(nix eval --raw nixpkgs#cairo.outPath)/lib:$(nix eval --raw nixpkgs#gdk-pixbuf.outPath)/lib:$(nix eval --raw nixpkgs#libXtst.outPath)/lib:$(nix eval --raw nixpkgs#libXxf86vm.outPath)/lib:$(nix eval --raw nixpkgs#alsa-lib.outPath)/lib:$(nix eval --raw nixpkgs#ffmpeg_6.lib)/lib:$HOME/.openjfx/cache/21.0.6+3/amd64:/run/opengl-driver/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"; \
  bash ./gradlew :desktopApp:run
'
```

On Windows, use a Java 21 shell or install and select a Java 21 JDK before
running `gradlew.bat :desktopApp:run`.

## Installing a release

Every download on the [releases page](https://github.com/AnGorRoc/BitChord-X/releases) carries its own Java
runtime, the FFmpeg and ONNX natives, the Automix analyser and its models.
Nothing else has to be installed first — no JDK, no codec pack.

| Platform | Download | Notes |
|---|---|---|
| macOS | `BitChord-<version>-macos-arm64.dmg` | For Apple Silicon Macs (M1/M2/M3/M4/later). Drag to Applications. |
| Linux | `BitChord-<version>-linux-x86_64.AppImage` | `chmod +x` it and run. No install, works on any distribution. |
| Linux | `BitChord-<version>-linux-amd64.deb` | `sudo apt install ./BitChord-*.deb` on Debian, Ubuntu and derivatives. |
| Windows | `BitChord-<version>-windows-x64-setup.exe` | The ordinary installer. Installs for the current user, so it never asks for an administrator. |
| Windows | `BitChord-<version>-windows-x64-portable.zip` | Unzip anywhere and run `BitChord.exe`. Writes nothing outside the folder. |

**macOS Gatekeeper.** Unsigned binaries built locally or downloaded outside the Mac App Store
may trigger a macOS security prompt ("BitChord is damaged and can't be opened" or "unidentified developer").
To clear the quarantine flag, run:

```bash
xattr -cr /Applications/BitChord.app
```

The Linux packages are built on Ubuntu 22.04 against its glibc, so they install
on that release and anything newer.

**Linux system libraries.** The packages carry their own Java runtime and codecs
but not the desktop's own graphics stack, and jpackage cannot derive that list —
so the deb and rpm declare only `xdg-utils`. Any machine running a desktop
session already has what is needed (GTK 3, X11 or XWayland, GL, ALSA); a minimal
or headless install does not, and will fail at startup rather than at install
time.

**Windows and the Visual C++ runtime.** The Automix analyser is linked against a
static C runtime, so on almost every machine there is nothing to install. If
BitChord starts but Automix never analyses anything, install the
[Microsoft Visual C++ 2015–2022 Redistributable (x64)](https://aka.ms/vs/17/release/vc_redist.x64.exe)
and restart it — that is the one dependency the bundled runtime cannot carry
itself, because Windows expects it to be a system component.

## Window behaviour

- **One window.** Opening BitChord a second time brings the running window forward instead of starting
  another copy.
- **Closing the window quits the app.** This is the default. To keep the music going after the window is
  closed, turn on **Keep running in background when closed** in Settings: BitChord then stays in the system
  tray, and turning the option on also turns the tray icon on.
- **Keyboard shortcuts.** `Ctrl` (`Cmd` on macOS) with `,` opens Settings, with `F` focuses search and with
  `W` closes the window. The window remembers its size and position, and has a sensible minimum size.

## Building the packages yourself

jpackage builds a package by driving the target platform's own tooling, so each
one has to be built on the platform it is for. This is what CI does; see
[`.github/workflows/release.yml`](.github/workflows/release.yml).

On macOS:

```bash
# Builds the disk image (.dmg) installer
./gradlew :desktopApp:packageDmg
```

On Linux — `fakeroot` and `binutils` must be installed for the deb package,
and `file` for the AppImage:

```bash
bash ./gradlew :desktopApp:packageDeb

# The AppImage is wrapped around the app image jpackage produces.
bash ./gradlew :desktopApp:createDistributable
desktopApp/packaging/appimage.sh \
  desktopApp/build/compose/binaries/main/app/BitChord \
  dist/BitChord-linux-x86_64.AppImage
```

On Windows — [WiX Toolset 3](https://github.com/wixtoolset/wix3/releases) must
be on `PATH`, which is what jpackage builds the `.exe` with:

```bat
gradlew.bat :desktopApp:packageExe

REM The portable build is the app image, zipped.
gradlew.bat :desktopApp:createDistributable
```

Pass `-Pbitchord.version=1.2.3` to stamp a version other than the one in
`desktopApp/build.gradle.kts`; a release build takes it from the tag.

**Gradle notes.** The native tool lookup lives in `buildSrc`, so the first build needs network access to fetch
the `kotlin-dsl` plugin. With it, the native tasks no longer break when Gradle reuses its configuration cache,
and `--no-configuration-cache` is not needed.

## Start-up time on Windows

A cold JVM spends most of a launch loading and verifying about 14,000 classes, so the Windows
build ships two class-data-sharing archives that turn that into a memory map. Opening the window
went from about 7.5 s to about 3.2 s on a Ryzen 7 with the app's jars already in the disk cache.

- **The JDK's own** (`runtime/bin/server/classes.jsa`). jpackage's runtime image is made with
  `jlink --strip-native-commands`, which removes the launcher jlink needs to generate it. The
  `createRuntimeImage` task makes it afterwards, with the toolchain's `java.exe` run on a
  hard-linked copy of the runtime (`build/startup-cds/runtime-probe`). It cannot be borrowed from
  the toolchain JDK: the JVM only accepts an archive made against the very `lib/modules` it runs on.
- **The app's** (`app/resources/startup.jsa`, about 100 MB on disk), made at the end of
  `createDistributable` from `desktopApp/packaging/startup.classlist`, with the same probe. The JVM
  only trusts it when the installed class path is named and ordered exactly as it was at dump time,
  and jpackage names a jar after a checksum that changes on every run (even the empty Skiko stub
  jar), so the archive is made from the finished image, reading the class path from its own
  `BitChord.cfg`. `packageExe` is then wrapped around that image (`--app-image`) instead of
  assembling the jars again.

If the archive is missing or does not fit, the JVM runs without any class-data sharing: slower, never
broken. Check with `-Xlog:cds=info` (look for "Unable to map shared spaces" or "shared class paths
mismatch").

The class list is a snapshot of what a start loads. Classes that no longer exist are skipped and new
ones are loaded the ordinary way, so it ages gracefully, but it is worth regenerating after big
changes. With a JDK 21 and a throwaway home, so the real profile is untouched:

```powershell
$env:JAVA_TOOL_OPTIONS = "-XX:DumpLoadedClassList=$PWD\startup.classlist -Duser.home=$env:TEMP\bitchord-cds"
& "$env:LOCALAPPDATA\BitChord\BitChord.exe"   # let the window open, wait ~30 s, then close it
```

(The Windows launcher honours `JAVA_TOOL_OPTIONS`; the list was originally captured by running the
app's class path with `java -XX:DumpLoadedClassList=…`.) Then drop lines longer than 4000 characters
(a few huge Compose lambdas; the JVM rejects the whole list otherwise, and the build also filters
them), put the four `#` comment lines back on top, and replace the file.

Do not use `-XX:+AutoCreateSharedArchive` instead: recording a dynamic archive at run time made this
app several times slower to start in testing.
