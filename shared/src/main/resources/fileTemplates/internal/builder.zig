const std = @import("std");
const builder = @import("build.zig");
const RetType = @typeInfo(@TypeOf(builder.build)).@"fn".return_type.?;


// serialization types, must be kept in sync with the ZB counterpart
const Serialization = struct {
	const Project = struct {
		/// The name of the project
  		name: ?[]const u8,
		/// The version of the project
  		version: ?[]const u8,
		/// The root of the project
  		path: []const u8,
		/// The (top-level) steps the project declares
		steps: []const Step,
		/// The modules this project owns, may them be private or public
		modules: []const Module,
		/// Contains the dependencies of the project
		dependencies: []const Dependency,
	};
	const Step = struct {
		/// Self-explanatory
		name: []const u8,
		/// Description given to the step
		description: []const u8,
		/// The kind of step, may be any one declared in `std.Build.Step.Id`
		kind: []const u8,
	};
	const Module = struct {
		/// The root `.zig` file, as an absolute path
		root: []const u8,
		/// Public modules are dependable on by other projects
  		public: bool,
		/// Imports of this module
  		imports: []const Import,
	};
	const Import = struct {
		/// Name under the import is available in code
		name: []const u8,
		/// The imported module's owner
  		owner: usize,
		/// The imported module's idx
  		module: usize,
	};
	const Dependency = struct {
		/// The index of the project in the projects list
		project: usize,
		/// Whether the dependency was declared lazy
		lazy: bool,
	};
};

// 0.14 compat
const postWritergate = @hasDecl( std, "Io" );
const newArrayLists = @hasDecl(std, "array_list");
const ArrayList = if ( newArrayLists ) std.ArrayList else std.ArrayListUnmanaged;

// 0.15 compat (IOGate added in 0.16)
const postIOGate = postWritergate and @hasDecl(std.Io, "Threaded");
const IoType = if ( postIOGate ) std.Io else void;

// 0.16 compat (new build apis)
const envInGraph = @hasField(std.Build.Graph, "environ_map");
const ioInGraph = @hasField(std.Build.Graph, "io");

// In-memory representation
const Storage = struct {
	projects: ArrayList(Project),

	const Project = struct {
		name: []const u8,
  		version: ?[]const u8,
		path: []const u8,
		steps: []const Serialization.Step,
		modules: []const Storage.Module,
		dependencies: []const Dependency,
	};
	const Module = struct {
		module: *std.Build.Module,
		public: bool,
		imports: *std.StringArrayHashMapUnmanaged(*std.Build.Module),
	};
	const Dependency = struct {
		name: []const u8,
		hash: []const u8,
		path: []const u8,
		lazy: bool,
	};
};

pub fn build( b: *std.Build ) !void {
	// run the project's build.zig
	const res = switch ( @typeInfo(RetType) ) {
		.error_union => try builder.build( b ),
		else => builder.build( b )
	};

	// this scope's lifespan is very short, so we can simply use an arena allocator
	var arena: std.heap.ArenaAllocator = .init( b.allocator );
	const alloc = arena.allocator();
	defer arena.deinit();

	// get the port environment variable
	const port_str = if (envInGraph)
		b.graph.environ_map.get("ZIGBRAINS_PORT") orelse return error.NoPortGiven
	else
		std.process.getEnvVarOwned(alloc, "ZIGBRAINS_PORT") catch |e| switch (e) {
			error.EnvironmentVariableNotFound => return error.NoPortGiven,
			else => return e,
		};

	// translate it to an int
	const port = try std.fmt.parseInt( u16, port_str, 10 );
	std.log.info( "[ZigBrains:BuildScan] IDE is listening on port {}", .{ port } );

	// get an Io implementation
	var threaded_io = if (postIOGate and !ioInGraph)
		std.Io.Threaded.init( b.allocator, .{ } )
	else
		{};
	defer if (postIOGate and !ioInGraph) threaded_io.deinit();
	const io = if (postIOGate) if (ioInGraph) b.graph.io else threaded_io.io() else {};

	// connect to the IDE
	var stream = if (postIOGate) netblk: {
		const ip = std.Io.net.IpAddress{
			.ip4 = .loopback(port),
		};
		break :netblk try ip.connect(io, .{
			.mode = .stream,
			.protocol = .tcp,
		});
	} else netblk: {
		break :netblk try std.net.tcpConnectToAddress(.{ .in = try std.net.Ip4Address.resolveIp( "127.0.0.1", port ) });
	};
	defer if (postIOGate) stream.close(io) else stream.close();

	// gather data
	var storage: Storage = .{ .projects = .empty };
	try gatherProjects( b, if ( postIOGate ) io else {}, &storage, "<root>", alloc );

	const Util = struct {
		pub fn findProjectIndex( strg: *Storage, needle: []const u8 ) ?usize {
			for ( strg.projects.items, 0.. ) |proj, idx| {
				if ( std.mem.eql( u8, needle, proj.path ) ) {
					return idx;
				}
			}
			return null;
		}
		// Pointer identity is tried first: it's always correct within a single project (there's only ever
		// one *std.Build instance for it), and is the ONLY correct answer for a module whose root has no
		// real static path (`.generated`/`.dependency`, e.g. a WriteFile output or a translate-c result) --
		// every such module would otherwise compare equal to every other one.
		// It can still legitimately fail across a path-dependency boundary: Zig does not dedupe a path
		// dependency's `*std.Build` instance across every parent that depends on it by the same physical
		// directory, only `findProjectIndex`'s path-based match does. Only then, and only for a module whose
		// root IS a real static path (`.src_path`/`.cwd_relative`), fall back to comparing resolved roots.
		pub fn findProjectModuleIndex( strg: *Storage, projIdx: usize, needle: *std.Build.Module, ioArg: IoType, allocArg: std.mem.Allocator ) !?usize {
			for ( strg.projects.items[projIdx].modules, 0.. ) |mod, idx| {
				if ( mod.module == needle ) {
					return idx;
				}
			}
			const needleRootLp = needle.root_source_file orelse return null;
			switch ( needleRootLp ) {
				.src_path, .cwd_relative => {},
				else => return null,
			}
			const needleRoot = try absLazyPath( needleRootLp, ioArg, allocArg );
			for ( strg.projects.items[projIdx].modules, 0.. ) |mod, idx| {
				const modRootLp = mod.module.root_source_file orelse continue;
				switch ( modRootLp ) {
					.src_path, .cwd_relative => {},
					else => continue,
				}
				const modRoot = try absLazyPath( modRootLp, ioArg, allocArg );
				if ( std.mem.eql( u8, needleRoot, modRoot ) ) {
					return idx;
				}
			}
			return null;
		}
	};

	// process everything to be serializable
	var projects = try alloc.alloc( Serialization.Project, storage.projects.items.len );
	for ( storage.projects.items, 0.. ) |proj, i| {
		// post-process the dependencies
		var dependencies: std.ArrayList(Serialization.Dependency) = try .initCapacity( alloc, proj.dependencies.len );
		for ( proj.dependencies ) |dep| {
			const depProj = Util.findProjectIndex( &storage, dep.path ) orelse continue;
			dependencies.addOneAssumeCapacity().* = .{ .project = depProj, .lazy = dep.lazy };
		}
		// post-process the modules
		const modules: []Serialization.Module = try alloc.alloc( Serialization.Module, proj.modules.len );
		for ( proj.modules, 0.. ) |mud, modIdx| {
			var imports: std.ArrayList(Serialization.Import) = try .initCapacity( alloc, mud.imports.count() );
			var iter = mud.imports.iterator();
			while ( iter.next() ) |imp| {
				const ownerRoot = try absRoot( imp.value_ptr.*.owner, io, alloc );
				const ownerIdx = Util.findProjectIndex( &storage, ownerRoot ) orelse continue;
				const moduleIdx = ( try Util.findProjectModuleIndex( &storage, ownerIdx, imp.value_ptr.*, io, alloc ) ) orelse continue;
				imports.addOneAssumeCapacity().* = .{
					.name = imp.key_ptr.*,
					.owner = ownerIdx,
					.module = moduleIdx,
				};
			}

			modules[modIdx] = .{
				.root = if (mud.module.root_source_file) |r| try absLazyPath( r, io, alloc ) else "<null>",
				.public = mud.public,
				.imports = imports.items
			};
		}

		// save the mappings
		projects[i] = .{
			.name = proj.name,
			.version = proj.version,
			.path = proj.path,
			.steps = proj.steps,
			.modules = modules,
			.dependencies = dependencies.items,
		};
	}

	// serialize
	if ( postWritergate ) {
		var writerBuf: [1024]u8 = undefined;
		var streamWriter = if (postIOGate) stream.writer( io, &writerBuf ) else stream.writer( &writerBuf );
		try std.json.Stringify.value( projects, .{ .whitespace = .indent_4 }, &streamWriter.interface );
		try streamWriter.interface.flush();
	} else {
		try std.json.stringify( projects, .{ .whitespace = .indent_4 }, stream.writer() );
	}

	// hook is done!
	return res;
}

fn gatherProjects( b: *std.Build, io: IoType, storage: *Storage, depName: []const u8, alloc: std.mem.Allocator ) !void {
	const root_path = try absRoot( b, io, alloc );
	// ensure we don't traverse a project twice
	for ( storage.projects.items ) |proj| {
		if ( std.mem.eql( u8, proj.path, root_path ) ) {
			return;
		}
	}

	// gather steps
	var steps = try alloc.alloc( Serialization.Step, b.top_level_steps.count() );
	{
		var i: usize = 0;
		var iter = b.top_level_steps.iterator();
		while ( iter.next() ) |it| {
			const topLevel = it.value_ptr.*;
			// usually they have either 0 or 1 dependencies, being the thing that actually runs, in case of 0 its a synthetic step type, usually `uninstall-$x`
			const deps = topLevel.step.dependencies.items;
			// the builtin "install" step legitimately has 0 dependencies when a package installs nothing
			// (e.g. a pure library), so the `deps.len == 0 -> "uninstall"` fallback below must not win over it
			steps[i] = .{
				.name = topLevel.step.name,
				.description = topLevel.description,
				.kind = if ( std.mem.eql( u8, topLevel.step.name, "install" ) )
					"install"
				else if ( std.mem.eql( u8, topLevel.step.name, "uninstall" ) )
					"uninstall"
				else if ( deps.len != 0 )
					@tagName( deps[0].id )
				else
					// a user step with no dependencies (e.g. a bare `b.step("docs", ...)`) is neither
					// install nor uninstall; "top_level" is a neutral kind (falls back to the Run icon)
					"top_level",
			};
			i += 1;
		}
	}

	// gather dependencies
	const deps = try alloc.alloc( Storage.Dependency, b.available_deps.len );
	{
		const snapshot = try snapshotLazyDeps(b.graph);
		defer restoreLazyDeps(b.graph, snapshot);
		// we do not find the project index here, as that would introduce a dependency on resolving the... dependencies, which can be a problem in case of circular ones
		for ( b.available_deps, 0.. ) |dep, i| {
			// HACK: for now, ignore lazy dependencies
			const obj = b.lazyDependency( dep.@"0", .{ } ) orelse continue;
			deps[i] = .{
				.name = dep.@"0",
				.path = try absRoot( obj.builder, io, alloc ),
				.hash = dep.@"1",
				.lazy = false,
			};
		}
	}

	// gather modules
	var modules: ArrayList(Storage.Module) = try .initCapacity( alloc, b.modules.count() );
	{
		// public modules, we know exactly how many there are, so we prealloc the space for them
		var modIter = b.modules.iterator();
		while ( modIter.next() ) |it| {
			modules.addOneAssumeCapacity().* = .{
				.module = it.value_ptr.*,
				.public = true,
				.imports = &it.value_ptr.*.import_table,
			};
		}

		// private modules
		var iter = b.top_level_steps.iterator();
		while ( iter.next() ) |it| {
			try discoverStepModules( b, &it.value_ptr.*.*.step, &modules, alloc );
		}

		// a module that is only ever `addImport`ed (never a public `addModule`, never a compile step's own
		// root module) is otherwise invisible to this scan, dropping its own imports along with it
		try discoverImportedModules( b, &modules, alloc );
	}

	// parse the .zon
	var name: []const u8 = depName;
	var version: ?[]const u8 = null;
	parseZigZon( b, io, alloc, root_path, &name, &version ) catch { };

	// save the gathered data
	(try storage.projects.addOne(alloc)).* = .{
		.name = name,
		.version = version,
		.path = root_path,
		.modules = modules.items,
		.steps = steps,
		.dependencies = deps,
	};

	// visit the dependencies
	{
		const snapshot = try snapshotLazyDeps(b.graph);
		defer restoreLazyDeps(b.graph, snapshot);
		for ( b.available_deps ) |dep| {
			try gatherProjects( (b.lazyDependency( dep.@"0", .{ } ) orelse continue).builder, io, storage, dep.@"0", alloc );
		}
	}
}

// Resolves a path that may be relative to the process' cwd (a build_root.path of a path dependency, or
// "." itself) to its canonical absolute form by opening it as a directory and asking for its real path.
fn absPath( io: IoType, alloc: std.mem.Allocator, path: []const u8 ) ![]const u8 {
	if ( std.fs.path.isAbsolute( path ) ) {
		return path;
	}
	if ( postIOGate ) {
		var dir = try std.Io.Dir.cwd().openDir( io, path, .{ } );
		defer dir.close( io );
		return try dir.realPathFileAlloc( io, ".", alloc );
	} else {
		var dir = try std.fs.cwd().openDir( path, .{ } );
		defer dir.close();
		return try dir.realpathAlloc( alloc, "." );
	}
}

// A project's build root, absolute: for a root project or a `zig build` invocation with `--build-file`
// elsewhere, `b.build_root.path` is already absolute, but for a path dependency it is relative to the
// whole build's process cwd (the top project's root), so `findProjectIndex`/owner lookups need it resolved
// the same way every time, or they never match the realpath'd `Storage.Project.path` they're compared to.
fn absRoot( b: *std.Build, io: IoType, alloc: std.mem.Allocator ) ![]const u8 {
	return absPath( io, alloc, b.build_root.path.? );
}

// A module's root file, absolute. `LazyPath.getDisplayName()` only returns the raw (often relative)
// `sub_path`, which is only ever meaningful together with its owner's build root.
fn absLazyPath( lp: std.Build.LazyPath, io: IoType, alloc: std.mem.Allocator ) ![]const u8 {
	return switch ( lp ) {
		.src_path => |sp| std.fs.path.resolve( alloc, &.{ try absRoot( sp.owner, io, alloc ), sp.sub_path } ),
		// "an absolute path or a path relative to the cwd of the build runner process" (LazyPath docs)
		.cwd_relative => |p| std.fs.path.resolve( alloc, &.{ try absPath( io, alloc, "." ), p } ),
		// .generated / .dependency: no single static path a run-configuration could point `zig test` at
		else => "<null>",
	};
}

fn snapshotLazyDeps( graph: *std.Build.Graph ) !std.StringArrayHashMapUnmanaged(void) {
	return try graph.needed_lazy_dependencies.clone(graph.arena);
}

fn restoreLazyDeps( graph: *std.Build.Graph, map: std.StringArrayHashMapUnmanaged(void) ) void {
	graph.needed_lazy_dependencies.deinit(graph.arena);
	graph.needed_lazy_dependencies = map;
}

fn discoverStepModules( b: *std.Build, step: *std.Build.Step, modules: *ArrayList(Storage.Module), alloc: std.mem.Allocator ) !void {
	if ( step.id == .compile ) blk: {
		const compile: *std.Build.Step.Compile = @fieldParentPtr( "step", step );
		// a step of this project can depend on a compile step owned by a dependency (e.g. installing its
		// artifact); that module belongs to the other project's gatherProjects call, not this one
		if ( compile.root_module.owner != b ) {
			break :blk;
		}
		// check if a module was already added
		for ( modules.items ) |mod| {
			if ( mod.module == compile.root_module ) {
				break :blk;
			}
		}
		(try modules.addOne( alloc )).* = .{
			.module = compile.root_module,
			.public = false,
			.imports = &compile.root_module.import_table,
		};
	}
	for ( step.dependencies.items ) |s| {
		try discoverStepModules( b, s, modules, alloc );
	}
}

// Modules found so far (public `addModule`s, or a compile step's own root module) only cover modules that
// are reachable that way; a module built with `b.createModule(...)` and only ever `addImport`ed onto
// another module never shows up on its own, silently dropping its imports too. This walks every module
// already found, appends any of ITS imports owned by this project that aren't listed yet, and repeats
// against those newly-added modules too, until nothing new turns up.
fn discoverImportedModules( b: *std.Build, modules: *ArrayList(Storage.Module), alloc: std.mem.Allocator ) !void {
	var i: usize = 0;
	while ( i < modules.items.len ) : ( i += 1 ) {
		var iter = modules.items[i].imports.iterator();
		while ( iter.next() ) |imp| {
			const child = imp.value_ptr.*;
			if ( child.owner != b ) {
				continue;
			}
			var already = false;
			for ( modules.items ) |mod| {
				if ( mod.module == child ) {
					already = true;
					break;
				}
			}
			if ( already ) {
				continue;
			}
			(try modules.addOne( alloc )).* = .{
				.module = child,
				.public = false,
				.imports = &child.import_table,
			};
		}
	}
}

fn parseZigZon( b: *std.Build, io: IoType, alloc: std.mem.Allocator, root_path: []const u8, name: *[]const u8, version: *?[]const u8 ) !void {
	var file = blk: {
		// well, we need to, resolve that bad boy!
		if ( postIOGate ) {
			var dir = try std.Io.Dir.openDirAbsolute( io, root_path, .{ } );
			defer dir.close( io );
			break :blk try dir.openFile( io, "build.zig.zon", .{ } );
		} else {
			var dir = try std.fs.openDirAbsolute( root_path, .{ } );
			defer dir.close();
			break :blk try dir.openFile( "build.zig.zon", .{ } );
		}
	};
	defer if ( postIOGate )
		file.close( io )
	else
		file.close();

	const stats = try if ( postIOGate )
		file.stat( io )
	else
		file.stat();
	const buffer = try alloc.allocSentinel( u8, stats.size, 0 );
	_ = try if ( postIOGate )
		file.readPositionalAll( io, buffer, 0 )
	else
		file.readAll( buffer );
	const ast = try std.zig.Ast.parse( alloc, buffer, .zon );
	const zoir = try std.zig.ZonGen.generate( alloc, ast, .{ } );

	const ZonIdx = std.zig.Zoir.Node.Index;
	const root = ZonIdx.get( ZonIdx.root, zoir ).struct_literal;

	for ( root.names, 0.. ) |nodeNameNts, idx| {
		const nodeName = nodeNameNts.get( zoir );
		const node = root.vals.at( @intCast( idx ) ).get( zoir );
		if ( std.mem.eql( u8, nodeName, "name" ) ) {
			name.* = b.dupe( node.enum_literal.get( zoir ) );
		} else if ( std.mem.eql( u8, nodeName, "version" ) ) {
			version.* = b.dupe( node.string_literal );
		}
	}
}
