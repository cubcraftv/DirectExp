cmake_minimum_required(VERSION 3.25)

project(DirectExpNative LANGUAGES CXX)

find_package(JNI REQUIRED)

add_library(directexp_native SHARED
	src/directexp_common.cpp
	src/directexp_bridge_probe.cpp
	src/directexp_bridge_surface.cpp
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
	# d3dcompiler is deliberately not linked yet -- nothing in this probe
	# uses it, and its import library situation differs enough between
	# MSVC and MinGW that it's worth revisiting explicitly in Milestone 4
	# (HLSL compilation) rather than linking it speculatively now.
	target_link_libraries(directexp_native PRIVATE
		d3d11
		dxgi
	)
	target_compile_definitions(directexp_native PRIVATE
		WIN32_LEAN_AND_MEAN
		NOMINMAX
	)
endif()
