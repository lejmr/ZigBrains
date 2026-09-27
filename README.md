# ZigBrains

Zig language support for IntelliJ IDEA, CLion, and other JetBrains IDEs. Now written in Kotlin!

# Installing

You can either install this plugin from the [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/22456-zigbrains), or from [FalsePattern's website](https://falsepattern.com/zigbrains).

See [the quick setup guide](#quick-setup-guide-for-zig-and-zls) for how to set up language server integration.

Note: marketplace updates are usually delayed by a few days from the actual release, so if you want to always have the
latest builds of ZigBrains, you can set up your IDE to download signed releases directly from FalsePattern's website
through the built-in plugin browser:

1. Go to `Settings -> Plugins`
2. To the right of the `Installed` button at the top, click on the `...` dropdown menu, then select `Manage Plugin Repositories...`
3. Click the add button, and then enter the ZigBrains updater URL, based on your IDE version:
   - `2026.2.*` or newer: https://falsepattern.com/zigbrains/updatePlugins-262.xml
   - `2026.1.*`: https://falsepattern.com/zigbrains/updatePlugins-261.xml
   - `2025.3.*`: https://falsepattern.com/zigbrains/updatePlugins-253.xml
4. Click `OK`, and your IDE should now automatically detect the latest version
(both in the Installed tab and in the Marketplace tab), even if it's not yet verified on the official JetBrains marketplace yet.

## Versioning scheme
To reduce confusion and to better utilize semver, the plugin uses the following versioning scheme:

X - Major version, incremented any time a relatively large features is added or removed
Y - Minor version, incremented for smaller features or large refactors that don't change user-perceived behaviour
Z - Patch version, incremented only when a fix is purely an internal change and doesn't exceed an arbitrary threshold
of complexity (determined at the discretion of FalsePattern)

Note: before version 11, the version scheme used was 0.X.Y, without separate patch versions.
As this plugin will constantly be evolving together with the zig language, it makes no sense to keep the 0 prefix,
and might as well utilize the full semver string for extra information.

## Changelog
The changelog file follows https://common-changelog.org/

## Strict No LLM / No AI Policy

No LLMs for issues.

No LLMs for patches / pull requests.

No LLMs for comments on the bug tracker, including translation.

English is encouraged, but not required. You are welcome to post in your native
language and rely on others to have their own translation tools of choice to
interpret your words.

# Credits

## Supporters

- ### [Techatrix](https://github.com/Techatrix)
- ### [nuxusr](https://github.com/nuxusr)
- gree7
- xceno
- AnErrupTion

## Contributors

- [gatesn](https://github.com/gatesn)
- [MarioAriasC](https://github.com/MarioAriasC)
- [JensvandeWiel](https://github.com/JensvandeWiel)
- [ENDERZOMBI102](https://github.com/ENDERZOMBI102)

## Additional Thanks

- The [ZigTools](https://github.com/zigtools/) team for developing the Zig Language Server.

- [HTGAzureX1212](https://github.com/HTGAzureX1212) for developing [intellij-zig](https://github.com/intellij-zig/intellij-zig),
which served as a fantastic reference for deep IDE integration features.

- The members of the `Zig Programming Language` discord server's `#tooling-dev` channel for providing encouragement,
feedback, and lots of bug reports. 

- The developers of [LSP4IJ](https://github.com/redhat-developer/lsp4ij) for providing a really good LSP backend

- The developers of the [intellij-rust](https://github.com/intellij-rust/intellij-rust/) plugin for providing an
excellent example on how to write debugger support that doesn't depend on CLion.

- And everyone who actively reported issues and helped ironing out all the remaining problems

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
- The profiler is macOS only.
- Coverage always builds Debug, whatever the configuration's optimization level says.

# Description

<!-- Plugin description -->
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
