cmake_minimum_required(VERSION 3.25)

project(DirectExpNative LANGUAGES CXX)

find_package(JNI REQUIRED)

add_library(directexp_native SHARED
	src/directexp_bridge_probe.cpp
)

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
