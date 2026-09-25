cmake_minimum_required(VERSION 3.25)

project(DirectExpNative LANGUAGES CXX)

find_package(JNI REQUIRED)

add_library(directexp_native SHARED
	src/directexp_common.cpp
	src/directexp_bridge_probe.cpp
	src/directexp_bridge_surface.cpp
	src/directexp_bridge_texture.cpp
	src/directexp_shader_toolchain.cpp
)

# CMake's default shared-library naming differs by toolchain on Windows:
# MSVC produces "directexp_native.dll", but MinGW-w64/GCC (e.g. MSYS2
# UCRT64) follows the Unix convention and produces "libdirectexp_native.dll"
# unless told otherwise. Force the bare name on both so it matches what
# build.gradle's copy step looks for and what
# NativeDxBridgeLoader.java's System.loadLibrary("directexp_native") call
# expects (Java's loadLibrary never adds a "lib" prefix on Windows).
set_target_properties(directexp_native PROPERTIES PREFIX "")

target_include_directories(directexp_native PRIVATE
	${JNI_INCLUDE_DIRS}
	src
)

target_compile_features(directexp_native PRIVATE cxx_std_17)

if(WIN32)
	# Bare names (no ".lib" suffix) so this resolves correctly under both
	# MSVC (CMake appends .lib) and MinGW-w64/GCC toolchains such as MSYS2
	# UCRT64 (CMake emits -ld3d11/-ldxgi against libd3d11.a/libdxgi.a).
	#
	# d3dcompiler (Milestone 4.1): mingw-w64's CRT ships a generated .def
	# file for d3dcompiler_47.dll, producing a linkable import library the
	# same bare-name way as d3d11/dxgi above -- see the toolchain research
	# notes' "D3DCompile / d3dcompiler on MinGW-w64" section. Unlike
	# d3d11.dll/dxgi.dll, d3dcompiler_47.dll is only a guaranteed OS
	# component on Windows 8.1+; NativeDxBridgeLoader.checkD3DCompilerAvailability
	# (Java side) gives that specific failure its own diagnostic instead of
	# letting it show up as a generic native-load failure.
	target_link_libraries(directexp_native PRIVATE
		d3d11
		dxgi
		d3dcompiler
	)

	# --- Vendored shaderc (Milestone 4.1) -----------------------------------
	# MSYS2's prebuilt mingw-w64-ucrt-x86_64-shaderc package does not
	# statically bundle glslang/SPIRV-Tools object code into
	# libshaderc_combined.a the way upstream's own build does -- it's built
	# "unbundled" against MSYS2's separately-packaged glslang/spirv-tools,
	# the same packaging style Arch uses (see
	# https://bugs.archlinux.org/task/69974). Worse, there's no metadata on
	# disk that can reconstruct the real static link line after the fact:
	# shaderc's own shaderc_combined.pc generator only ever emits
	# "-lshaderc_combined" (see define_pkg_config_file(shaderc_combined
	# -lshaderc_combined) in shaderc's own CMakeLists.txt), so pkg-config
	# can't help either. "Combined" is something shaderc's build creates at
	# compile time by merging glslang/SPIRV-Tools/libshaderc's object files
	# into one archive (shaderc_combine_static_lib() in
	# libshaderc/CMakeLists.txt) -- it isn't recoverable from a prebuilt
	# package after the fact.
	#
	# So: build it from source, in-tree, pinned to shaderc's own
	# tested-compatible dependency set (utils/known_good.json on shaderc's
	# "known-good" branch), rather than whatever HEAD happens to be:
	#   shaderc        b64fd42c4a2a9d501c47aca5d3571f7673a5e9cc
	#   glslang        e1b562a8bed273a02f30b59b66a5d499793cede5
	#   SPIRV-Tools    ef96ed763b43b59b33b31b362f09a02b729fa1c9
	#   SPIRV-Headers  04fd3caa1e8267e4d95c806cad901181728e1006
	#
	# Trade-offs this brings, see native/README.md: needs `git` and network
	# access during CMake's first configure, and adds real compile time
	# since glslang + SPIRV-Tools + shaderc are now all built from source
	# (subsequent builds only recompile what changed, as usual).
	include(FetchContent)
	if(POLICY CMP0169)
		# We need the classic two-step Populate-then-configure flow (sets
		# options in between the two), which CMP0169=NEW disallows calling
		# directly.
		cmake_policy(SET CMP0169 OLD)
	endif()

	FetchContent_Declare(shaderc_src
		GIT_REPOSITORY https://github.com/google/shaderc.git
		GIT_TAG        b64fd42c4a2a9d501c47aca5d3571f7673a5e9cc
		GIT_SHALLOW    TRUE
	)
	FetchContent_GetProperties(shaderc_src)
	if(NOT shaderc_src_POPULATED)
		FetchContent_Populate(shaderc_src)
	endif()

	# glslang and SPIRV-Tools must physically live under shaderc's own
	# third_party/ tree -- that's where shaderc's third_party/CMakeLists.txt
	# looks for them (IS_DIRECTORY checks, not a configurable dependency).
	FetchContent_Declare(glslang_src
		GIT_REPOSITORY https://github.com/KhronosGroup/glslang.git
		GIT_TAG        e1b562a8bed273a02f30b59b66a5d499793cede5
		GIT_SHALLOW    TRUE
		SOURCE_DIR     ${shaderc_src_SOURCE_DIR}/third_party/glslang
	)
	FetchContent_GetProperties(glslang_src)
	if(NOT glslang_src_POPULATED)
		FetchContent_Populate(glslang_src)
	endif()

	FetchContent_Declare(spirv_tools_src
		GIT_REPOSITORY https://github.com/KhronosGroup/SPIRV-Tools.git
		GIT_TAG        ef96ed763b43b59b33b31b362f09a02b729fa1c9
		GIT_SHALLOW    TRUE
		SOURCE_DIR     ${shaderc_src_SOURCE_DIR}/third_party/spirv-tools
	)
	FetchContent_GetProperties(spirv_tools_src)
	if(NOT spirv_tools_src_POPULATED)
		FetchContent_Populate(spirv_tools_src)
	endif()

	# Unlike glslang/spirv-tools above, SPIRV-Tools' own external/CMakeLists.txt
	# accepts a pre-defined SPIRV-Headers_SOURCE_DIR instead of requiring a
	# physical external/spirv-headers checkout, so this one doesn't need to be
	# nested inside the spirv-tools tree.
	FetchContent_Declare(spirv_headers_src
		GIT_REPOSITORY https://github.com/KhronosGroup/SPIRV-Headers.git
		GIT_TAG        04fd3caa1e8267e4d95c806cad901181728e1006
		GIT_SHALLOW    TRUE
	)
	FetchContent_GetProperties(spirv_headers_src)
	if(NOT spirv_headers_src_POPULATED)
		FetchContent_Populate(spirv_headers_src)
	endif()
	set(SPIRV-Headers_SOURCE_DIR ${spirv_headers_src_SOURCE_DIR})

	# Trim this to just the static libs we actually link: skip shaderc's own
	# tests/examples/executables (glslc.exe) and its copyright-check custom
	# target, none of which we need -- skipping tests also means
	# third_party/CMakeLists.txt won't additionally pull in
	# googletest/effcee/re2/abseil.
	set(SHADERC_SKIP_TESTS ON CACHE BOOL "" FORCE)
	set(SHADERC_SKIP_EXAMPLES ON CACHE BOOL "" FORCE)
	set(SHADERC_SKIP_EXECUTABLES ON CACHE BOOL "" FORCE)
	set(SHADERC_SKIP_COPYRIGHT_CHECK ON CACHE BOOL "" FORCE)
	set(SHADERC_SKIP_INSTALL ON CACHE BOOL "" FORCE)

	# DirectExp only compiles GLSL input. Keep the deprecated glslang HLSL
	# frontend out of this build; SPIRV-Cross handles the SPIR-V -> HLSL step.
	set(SHADERC_ENABLE_HLSL OFF CACHE BOOL "" FORCE)

	# This dependency is built in-tree and linked directly; no install/export
	# package is needed. Skipping install rules also avoids a broken export in
	# this pinned Shaderc/glslang/SPIRV-Tools combination, where SPIRV-Tools-opt
	# is referenced but not included in glslang's export set.
	set(CMAKE_SKIP_INSTALL_RULES ON)

	# This pinned snapshot predates GCC 16's warning defaults; don't let
	# shaderc's own -Werror option turn newer warnings into hard failures.
	set(SHADERC_ENABLE_WERROR_COMPILE OFF CACHE BOOL "" FORCE)

	add_subdirectory(${shaderc_src_SOURCE_DIR} ${shaderc_src_BINARY_DIR})
	# -------------------------------------------------------------------------

	# SPIRV-Cross's CompilerHLSL (spirv_hlsl.hpp) needs core+glsl+hlsl; there
	# is no combined single-lib equivalent for it upstream. Unlike shaderc
	# above, SPIRV-Cross's MSYS2 package links fine as-is, so it's left as a
	# prebuilt pacman package (see native/README.md).
	target_link_libraries(directexp_native PRIVATE
		shaderc_combined
		spirv-cross-hlsl
		spirv-cross-glsl
		spirv-cross-core
	)
	# shaderc_combine_static_lib() (shaderc's own CMake helper) merges object
	# files only -- it doesn't carry over shaderc's PUBLIC include directory,
	# so directexp_shader_toolchain.cpp's #include <shaderc/shaderc.hpp>
	# needs it added explicitly here.
	target_include_directories(directexp_native PRIVATE
		${shaderc_src_SOURCE_DIR}/libshaderc/include
	)

	target_compile_definitions(directexp_native PRIVATE
		WIN32_LEAN_AND_MEAN
		NOMINMAX
	)
endif()