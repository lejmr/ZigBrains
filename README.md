# ZigBrains, a personal fork

This is a personal fork of [ZigBrains](https://codeberg.org/FalsePattern/ZigBrains), the Zig plugin for JetBrains IDEs by FalsePattern. The official plugin is on the [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/22456-zigbrains) and is developed under a strict No AI policy. This fork adds the features listed below on top of it, and every one of them is AI-generated: written with Claude, then reviewed and tested by me. Report problems with them here, not upstream.

## Install

1. Download the `.zip` from the [latest release](https://github.com/lejmr/ZigBrains/releases/latest). Leave it packed.
2. In the IDE open Settings → Plugins → ⚙ → Install Plugin from Disk…, pick the zip and restart.
3. Set up the Zig toolchain and ZLS in Settings → Languages & Frameworks → Zig.

It needs a 2026.2 IDE or newer: CLion, IntelliJ IDEA, PyCharm, RustRover, GoLand and the rest. The fork keeps the official plugin's ID, so it replaces an installed ZigBrains; when the IDE offers a ZigBrains update from the Marketplace, accepting it puts the official plugin back.

## What this fork adds

[CHANGELOG.md](CHANGELOG.md) lists every change; everything after 31.0.0 is the fork's.

- **Test runner tree** for `zig test` and `zig build test`: suites per package, file and module, traces, memory leaks and `std.log.err` as failures, a compile error as a "build failed" test. Run and Debug from a node, rerun failed tests, run all tests in a file or a directory.
- **Monorepos** with path dependencies: a node per package in the Zig tool window, build steps of the working directory's package, a file's module imports passed to `zig test`.
- **Build and Run / Debug for `main`** in a project with a `build.zig`.
- **Web UI and fuzzing**: the `--webui` / `--fuzz` page in an embedded browser, a Fuzz gutter action, crashes as failed tests that open the saved input.
- **Coverage** with kcov, in the Coverage tool window and the editor gutter.
- **Profiler**: a Profile executor for tests and `main`, xctrace on macOS and perf on Linux, self and total samples per function and a call tree.
- **Navigation without ZLS**: Go to Declaration, Find Usages and rename for identifiers and `@import` aliases, across the packages of a monorepo.
- **Editor**: Quick Documentation for `///` comments, Git blame hints above declarations, breadcrumbs and sticky lines.
- **Releases**: GitHub Actions build and verify the plugin and attach the zip to every tagged release.

How each of them works, and where it stops: [Testing, coverage, profiling](#testing-coverage-profiling) and [docs/monorepo.md](docs/monorepo.md).

## Versions

Versions continue upstream's semver; 32.0.0 is the first release of the fork.

## Upstream's No AI policy

The official ZigBrains takes no LLM-written issues, patches or bug tracker comments. This fork gives the AI-written features a home of their own, so please keep issues and patches about them here.

## Testing, coverage, profiling

### Test runner

- The gutter icon on a `test` block offers Run, Debug and (for the first test in a file) "Run 'all tests in <file>'".
- Right-click a directory and choose Run to run every `.zig` file under it that contains a test. Files that another selected file `@import`s are skipped, so each test runs once.
- Results land in a test tree. Suites are packages (in a monorepo), files and imported modules. A failed test carries its trace. A memory leak or a `std.log.err` line marks the test red even though zig prints `OK`. A file that does not compile shows as one failed test named "build failed" with the compiler output.
- Run and Debug work from a node of the tree. Debug needs a single file, not a directory.
- Double-click on a suite (package/file/module) toggles it, same as any test tree; use the platform's "Jump to Source" action (F4, or Navigate menu) to open what it stands for instead — a file's `.zig` file, a package's `build.zig`.
- Each file runs as `zig test <file>`. When the build scan knows the file's module, its imports are passed as `--dep`/`-M` flags. Otherwise the file runs standalone.
- A build configuration whose steps run tests (`zig build test`) gets the same tree. The build runs with `--verbose`, and every `zig test` compile it printed is run again as a plain `zig test` (a cache hit) whose per-test output feeds the tree, one suite per root source file. The tests therefore run twice, and the re-runs do not reproduce the Run step's own environment, working directory, extra arguments or stdin (`setEnvironmentVariable`, `setCwd`, `addArgs`, `setStdIn` in `build.zig`). The build's own output sits under a `build.zig` suite, which turns red when the build fails before any test compiles, or fails although every test passed. Debug of a build configuration, and runs with `--fuzz`, `--webui` or `--watch`, are unchanged.
- `main` in a project with a `build.zig` gets "Build and Run" (`zig build run`). Debug builds the `install` step and launches `zig-out/bin/<name>`, taken from the first `addExecutable` in `build.zig`. The debug console prints the compile command.

### Web UI and fuzzing

- A run configuration that passes `--webui` or `--fuzz` to `zig build` opens the printed URL in the "Zig Web UI" tool window. Without an embedded browser (JCEF) the URL comes as a notification.
- A test that calls `std.testing.fuzz` gets a "Fuzz '<name>'" gutter action. It runs `zig build test --fuzz --webui -Doptimize=ReleaseSafe` in the test's package. Zig fuzzes every fuzz test the build graph reaches, not only that one.
- Fuzzing needs zig 0.16. `--fuzz=N` (a fixed number of iterations) cannot be combined with `--webui`; use one or the other in the build steps.

### Coverage

- Right-click a Zig test configuration and pick "Run with Coverage". Each test binary runs under kcov and the report shows in the Coverage tool window and the editor gutter.
- Needs kcov on the PATH or in a Homebrew prefix (`brew install kcov`).
- Coverage runs force `-O Debug`; kcov's line data is unreliable in release modes. The console says so.
- Only files inside the project directory are reported. The kcov output lives under `.zig-cache/zigbrains-coverage/<configuration name>`.

### Profiler

- On macOS, "Profile" is a regular run executor: the gutter icon on a test or `main`, the editor context menu, and wherever the IDE puts a registered executor for the selected run configuration (the run-configuration toolbar for a "primary" executor, otherwise the New UI's "More" menu) all offer "Profile '<name>'". The plugin builds the target with `-O ReleaseSafe` (or `-Doptimize=ReleaseSafe` for `zig build`), records it with `xcrun xctrace record --template 'Time Profiler'` and shows self and total samples per function in the "Zig Profiler" tool window. "Open in Instruments" opens the trace.
- Needs the Xcode command line tools for `xctrace`.
- For a build configuration, `build.zig` must call `b.standardOptimizeOption(.{})` or `-Doptimize` is rejected.
- Closing the run tab cancels the profile.

### Known limits

- A per-file `zig test` is not `zig build test`. It has no build graph, so build options, `addOptions` modules and generated modules are not available.
- A file that is only ever imported from outside its package, or from a build module the scan cannot resolve, does not compile standalone. Its tests run through their importer.
- The Linux profiler (perf) is tested against recorded `perf script` output only, not on a live machine.
- Coverage always builds Debug, whatever the configuration's optimization level says.

# Description

<!-- Plugin description -->
Personal fork of ZigBrains with AI-generated additions: test runner tree, coverage, profiler, fuzzing, monorepos and navigation without ZLS. See https://github.com/lejmr/ZigBrains.

Adds support for the Zig Language, utilizing the ZLS language server for advanced coding assistance.

Before you can properly use the plugin, you need to select or download the Zig toolchain and language server in `Settings` -> `Languages & Frameworks` -> `Zig`.

## Debugging

Debugger settings are available in the `Settings | Build, Execution, Deployment | Debugger` menu, under the `Zig` section. 

### IDE Compatibility
Debugging Zig code is supported in any native debugging capable IDE. The following have been verified to work so far:

- CLion
- IntelliJ IDEA Ultimate
- RustRover (including the non-commercial free version too)
- GoLand
- PyCharm Professional
- Android Studio

Additionally, in CLion, the plugin uses the C++ Toolchains for sourcing the debugger (this can be toggled off in the settings).

The open-source Community edition IDEs don't have the native debugging code as it's a proprietary module, so you cannot
debug zig code with them. You can still use those IDEs to develop code and use everything else the plugin has to offer.

### Windows

Supported debuggers: `LLDB`, `GDB`

### Linux

Supported debuggers: `LLDB`, `GDB`

### MacOS

Supported debuggers: `LLDB`

<!-- Plugin description end -->
