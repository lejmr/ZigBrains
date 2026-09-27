# Monorepos with path dependencies

## How zig path dependencies work

A package lists another local package in `build.zig.zon` with a `.path` entry:

```zig
.dependencies = .{
    .libcalc = .{ .path = "../libcalc" },
},
```

In `build.zig` the dependency is loaded with `b.dependency("libcalc", .{})`. Its public modules come from `dep.module("calc")` and are attached with `exe.root_module.addImport("calc", ...)`. Zig creates a separate `*std.Build` instance for every dependency. Two parents that point at the same directory do not share one instance.

Top-level steps do not propagate. `zig build test` at the root runs only the steps the root `build.zig` declares. It does not run `libcalc`'s or `cli`'s `test` step unless the root wires them in with `dependOn`. Each package has to be built and tested from its own directory, or the root has to depend on each step explicitly.

## What the plugin does per package

The build scanner (`builder.zig`, run as a helper against the project's `build.zig`) walks the root project and every path dependency it reaches. For each one it records the package path, its top-level steps, its modules with absolute root files and their imports (owner package plus module index), and its dependencies. All paths are resolved to absolute paths, so a dependency reached as `../libcalc` matches the same package on disk.

With more than one package under the IDE project directory:

- The Zig tool window shows one node per package, labelled with the package name and its path relative to the project. Double-clicking a step creates a `zig build <step> (<package>)` configuration whose working directory is that package. Packages fetched into the zig cache do not get a node.
- Build steps completion in a run configuration offers the steps of the package whose directory contains the configuration's working directory, falling back to the root project.
- A directory test run groups the test tree by package (package suite, then file suite, then tests). The package is the nearest ancestor with a `build.zig`.
- `zig test` on a file passes the file's build module imports as `--dep name=module` and `-Mmodule=<root>` flags. The module is the deepest one whose root directory contains the file. A file in `cli/src/` that imports `calc` runs with `--dep calc=calc -Mroot=cli/src/main.zig -Mcalc=libcalc/src/calc.zig`. A module with no imports, or a file outside any scanned module, runs as a plain `zig test <file>`.
- Generated modules (`WriteFile`, `translate-c`) have no static root and are skipped in the flags. Zig then reports a plain "no module named" error for them.

## Fixture layout

The tests and the manual checks use a three-package repository:

```
monorepo/
  build.zig           root; no modules of its own, depends on libcalc and cli
  build.zig.zon       .libcalc = .{ .path = "libcalc" }, .cli = .{ .path = "cli" }
  libcalc/
    build.zig         b.addModule("calc", ...) with a test step
    build.zig.zon
    src/calc.zig      public module "calc", no imports
  cli/
    build.zig         addExecutable, root module imports "calc" from libcalc
    build.zig.zon     .libcalc = .{ .path = "../libcalc" }
    src/main.zig      imports "calc"
```

The scan lists three projects: root (index 0), `libcalc` (index 1, module `calc`) and `cli` (index 2, module `main` importing `calc` from project 1 module 0). `ZigTestModulesTest` in `shared/src/test` mirrors this shape with `/repo` in place of the real path.
