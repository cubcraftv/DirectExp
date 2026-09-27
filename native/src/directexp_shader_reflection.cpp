// See directexp_shader_reflection.h for scope.
//
// NOTE: like the rest of this native bridge, this file has not been
// compiled by the authoring agent (a Linux sandbox without an MSYS2/MSVC
// Windows toolchain or SPIRV-Cross available). Check
// docs/plans/2026-09-20-directexp-bootstrap-plan.md's Progress Log for
// whether a later session has build/runtime-verified it since.

#include "directexp_shader_reflection.h"
#include "directexp_shader_resource_naming.h"
#include "directexp_common.h"

#include <jni.h>

#ifdef _WIN32

#include <spirv_cross/spirv_cross.hpp>

#include <cstdio>
#include <string>
#include <vector>

namespace directexp {

namespace {

// NOTE: the resource-naming fallback used to live here as a local
// `ResourceName` helper. Milestone 4.5 needs to derive the exact same
// name for the exact same resource (to look up its engine-assigned
// register slot), so it was pulled out into the shared
// directexp_shader_resource_naming.h (`SpirvResourceName`) both files now
// include, rather than risk the two independently-written fallbacks
// drifting apart. This deliberately does not call SPIRV-Cross's own
// `get_fallback_name` (a `CompilerGLSL`-only member, not present on the
// base `Compiler` class this file uses).

void ClassifyAndCollect(
	spirv_cross::Compiler& compiler,
	const spirv_cross::SmallVector<spirv_cross::Resource>& resources,
	SpirvResourceKind kind,
	bool includeSize,
	std::vector<SpirvResource>& out
) {
	for (const spirv_cross::Resource& resource : resources) {
		SpirvResource entry;
		entry.name = SpirvResourceName(resource);
		entry.kind = kind;
		entry.sizeBytes = includeSize
			? static_cast<uint32_t>(compiler.get_declared_struct_size(compiler.get_type(resource.base_type_id)))
			: 0;
		out.push_back(std::move(entry));
	}
}

} // namespace

SpirvReflectionResult ReflectSpirv(const std::vector<uint32_t>& spirv) {
	SpirvReflectionResult result;

	try {
		spirv_cross::Compiler compiler(spirv);
		spirv_cross::ShaderResources resources = compiler.get_shader_resources();

		ClassifyAndCollect(compiler, resources.uniform_buffers, SpirvResourceKind::ConstantBuffer, true, result.resources);
		ClassifyAndCollect(compiler, resources.storage_buffers, SpirvResourceKind::StructuredBuffer, true, result.resources);

		// GLSL's combined `sampler2D` (the form Mojang's shaders use, not
		// the GL_ARB_separate_shader_objects-style separate
		// texture/sampler declarations) reflects as a single
		// `sampled_images` resource in SPIR-V, but D3D11 has no combined
		// sampler type -- it needs a distinct SRV (`t#`) and sampler
		// state (`s#`), per the design spec's Binding Model section and
		// AGENTS.md's "D3D11 has no argument buffers ... only flat
		// constant-buffer/SRV/sampler/UAV slot binding". Milestone 4.5's
		// HLSL emission (SPIRV-Cross's `CompilerHLSL`) performs exactly
		// this split automatically when it emits HLSL, so the
		// engine-owned layout model reflects both halves up front, under
		// the *same* engine resource name -- which literal HLSL variable
		// name SPIRV-Cross gives each half is a 4.5 concern, not this
		// stage's.
		for (const spirv_cross::Resource& image : resources.sampled_images) {
			std::string name = SpirvResourceName(image);
			result.resources.push_back(SpirvResource{name, SpirvResourceKind::SampledTexture, 0});
			result.resources.push_back(SpirvResource{name, SpirvResourceKind::Sampler, 0});
		}

		// Real separate texture/sampler pairs. Not expected in any of
		// Mojang's current shaders (per the toolchain research notes,
		// confirming that is 4.2/4.3-and-later "actually run real
		// shaders through it" territory, not something resolvable up
		// front) -- reflected anyway rather than silently dropped if a
		// future shader does use this form.
		ClassifyAndCollect(compiler, resources.separate_images, SpirvResourceKind::SampledTexture, false, result.resources);
		ClassifyAndCollect(compiler, resources.separate_samplers, SpirvResourceKind::Sampler, false, result.resources);

		result.success = true;
	} catch (const std::exception& error) {
		result.success = false;
		result.errorMessage = error.what();
		if (result.errorMessage.empty()) {
			result.errorMessage = "SPIRV-Cross reflection failed with no further detail.";
		}
	}

	return result;
}

jobject BuildReflectionResult(JNIEnv* env, const SpirvReflectionResult& result) {
	jclass resultClass = env->FindClass("dev/cubcraftv/directexp/bridge/NativeDxSpirvReflectionResult");
	if (resultClass == nullptr) {
		return nullptr;
	}

	jmethodID constructor = env->GetMethodID(resultClass, "<init>", "(I[Ljava/lang/String;[I[ILjava/lang/String;)V");
	if (constructor == nullptr) {
		return nullptr;
	}

	// OUTCOME_READY / OUTCOME_REFLECTION_ERROR below must match
	// NativeDxSpirvReflectionResult.java's own constants -- kept as bare
	// literals here for the same reason directexp_shader_toolchain.cpp's
	// BuildIrCompilationResult does: a SPIRV-Cross reflection failure is
	// a distinct, expected outcome for *this* result type, not the
	// generic "unimplemented stub vs. native error" split
	// NativeDxBridgeProbeResult/NativeDxBridgeSurfaceBootstrapResult use.
	jint outcomeCode = result.success ? 0 /* OUTCOME_READY */ : 1 /* OUTCOME_REFLECTION_ERROR */;

	jobjectArray namesArray = nullptr;
	jintArray kindsArray = nullptr;
	jintArray sizesArray = nullptr;

	if (result.success) {
		jsize count = static_cast<jsize>(result.resources.size());

		jclass stringClass = env->FindClass("java/lang/String");
		if (stringClass == nullptr) {
			return nullptr;
		}

		namesArray = env->NewObjectArray(count, stringClass, nullptr);
		kindsArray = env->NewIntArray(count);
		sizesArray = env->NewIntArray(count);

		if (namesArray != nullptr && kindsArray != nullptr && sizesArray != nullptr) {
			std::vector<jint> kindValues(static_cast<size_t>(count));
			std::vector<jint> sizeValues(static_cast<size_t>(count));

			for (jsize i = 0; i < count; ++i) {
				const SpirvResource& resource = result.resources[static_cast<size_t>(i)];

				jstring name = ToJString(env, resource.name);
				env->SetObjectArrayElement(namesArray, i, name);
				if (name != nullptr) {
					env->DeleteLocalRef(name);
				}

				kindValues[static_cast<size_t>(i)] = static_cast<jint>(resource.kind);
				sizeValues[static_cast<size_t>(i)] = static_cast<jint>(resource.sizeBytes);
			}

			env->SetIntArrayRegion(kindsArray, 0, count, kindValues.data());
			env->SetIntArrayRegion(sizesArray, 0, count, sizeValues.data());
		}
	}

	jstring detail = ToJString(env, result.errorMessage);

	return env->NewObject(
		resultClass,
		constructor,
		outcomeCode,
		namesArray,
		kindsArray,
		sizesArray,
		detail
	);
}

} // namespace directexp

#endif // _WIN32
