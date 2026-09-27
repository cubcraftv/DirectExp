// Shared by directexp_shader_reflection.cpp (Milestone 4.4) and
// directexp_shader_hlsl.cpp (Milestone 4.5): both need to derive the same
// engine resource name from a `spirv_cross::Resource`, and both need to
// derive *the same* name for the same resource, or a resource with no
// SPIR-V debug name would reflect under one synthesized name in 4.4 but
// fail to be found under a different synthesized name when 4.5 looks up
// its engine-assigned slot -- silently dropping that resource's forced
// register binding. Kept in its own tiny header, included by both .cpp
// files, rather than duplicated or exported from
// directexp_shader_reflection.h (which deliberately stays free of
// spirv_cross types in its own interface).
#pragma once

#ifdef _WIN32

#include <spirv_cross/spirv_cross.hpp>

#include <string>

namespace directexp {

// SPIRV-Cross's `Resource::name` is usually the GLSL source name, but it
// can legitimately come back empty (SPIR-V compiled with debug info
// stripped, or a resource the source never named explicitly). Falls back
// to a stable name derived from the resource's own SPIR-V id rather than
// handing an empty string to either the engine-owned layout model
// (Milestone 4.4) or the HLSL resource-binding lookup (Milestone 4.5).
inline std::string SpirvResourceName(const spirv_cross::Resource& resource) {
	if (!resource.name.empty()) {
		return resource.name;
	}
	return "spirv_resource_" + std::to_string(resource.id);
}

} // namespace directexp

#endif // _WIN32
