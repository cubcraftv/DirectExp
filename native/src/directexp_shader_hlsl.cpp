// See directexp_shader_hlsl.h for scope.
//
// NOTE ON VERIFICATION: like the rest of this native bridge, this file
// has not been compiled by the authoring agent (a Linux sandbox without
// an MSYS2/MSVC Windows toolchain, D3DCompile, or SPIRV-Cross available).
// What *was* possible in this sandbox: this session fetched the real,
// current upstream `spirv_hlsl.hpp` from
// https://github.com/KhronosGroup/SPIRV-Cross (raw.githubusercontent.com
// is reachable here) and checked `HLSLResourceBinding`,
// `CompilerHLSL::Options`, `add_hlsl_resource_binding`, and
// `set_hlsl_options` against it directly, rather than against the
// authoring agent's own recollection of SPIRV-Cross's API -- see the
// specific field names/comments below for what that check confirmed.
// `D3DCompile`'s own signature (from `<d3dcompiler.h>`) was not similarly
// fetched during authoring (Microsoft's Win32 headers are not on GitHub
// the way SPIRV-Cross is), but the person driving this session pasted the
// official signature from Microsoft's own documentation afterward, and
// the call below was checked against it argument-by-argument (order and
// type of all eleven parameters: pSrcData, SrcDataSize, pSourceName,
// pDefines, pInclude, pEntrypoint, pTarget, Flags1, Flags2, ppCode,
// ppErrorMsgs) -- it matches. That confirms the call's *shape* is right;
// it does not substitute for an actual compile, since this sandbox still
// has no MSVC/Windows SDK to build against. Check
// docs/plans/2026-09-20-directexp-bootstrap-plan.md's Progress Log for
// whether a later session has build/runtime-verified this file.

#include "directexp_shader_hlsl.h"
#include "directexp_shader_resource_naming.h"
#include "directexp_common.h"

#include <jni.h>

#ifdef _WIN32

#include <spirv_cross/spirv_hlsl.hpp>

#include <d3dcompiler.h>

#include <cstdio>
#include <string>
#include <vector>

namespace directexp {

namespace {

// `ExecutionModel`/`ExecutionModelVertex`/`ExecutionModelFragment` are
// declared in the `spv` namespace (spirv.hpp), not `spirv_cross` --
// spirv_cross.hpp/spirv_hlsl.hpp each bring them into unqualified lookup
// *inside their own* `namespace spirv_cross { ... }` blocks via a
// using-directive, which does not make `spirv_cross::ExecutionModel...`
// valid qualified syntax from an unrelated namespace like this file's.
// Always qualify with `spv::` here instead, which is unambiguous and
// correct regardless of that using-directive's exact scope.
spv::ExecutionModel ToExecutionModel(ShaderExecutionStage stage) {
	switch (stage) {
		case ShaderExecutionStage::Fragment:
			return spv::ExecutionModelFragment;
		case ShaderExecutionStage::Vertex:
		default:
			return spv::ExecutionModelVertex;
	}
}

// CORRECTION (Milestone 4.6 commit 4 -- see the source of truth doc):
// vs_5_0/ps_5_0 does NOT "match the feature-level-10.1 floor" as 4.1's
// comment here used to claim -- Shader Model 5.0 requires
// D3D_FEATURE_LEVEL_11_0; a 10.1 device only guarantees Shader Model 4.1
// [Microsoft Learn, D3D_FEATURE_LEVEL enumeration: 10.1 -> "shader model
// 4", 11.0 -> "shader model 5"]. D3DCompile itself doesn't care what the
// runtime device supports -- it compiled vs_5_0 bytecode successfully
// regardless -- but ID3D11Device::CreateVertexShader/CreatePixelShader
// reject Shader-Model-5.0 bytecode on a sub-11.0 device, which is exactly
// what a `runClient` log on a feature-level-10.1 machine showed: GLSL ->
// SPIR-V -> HLSL -> D3DCompile all succeeding, then
// "ID3D11Device::CreateVertexShader failed." for nearly every pipeline --
// this was never caught earlier because no shader had compiled far enough
// to reach CreateVertexShader before Milestone 4.6 commit 3's
// DxShaderInterfaceLayout fix. Must stay in sync with EmitHlsl's
// options.shader_model below -- both derive from the same g_activeDevice
// feature level and must agree (a vs_4_1 target compiled against
// shader_model 50 HLSL, or vice versa, is a mismatch of its own).
const char* ToD3DCompileTarget(ShaderExecutionStage stage) {
	bool shaderModel5 = g_activeFeatureLevel >= D3D_FEATURE_LEVEL_11_0;
	switch (stage) {
		case ShaderExecutionStage::Fragment:
			return shaderModel5 ? "ps_5_0" : "ps_4_1";
		case ShaderExecutionStage::Vertex:
		default:
			return shaderModel5 ? "vs_5_0" : "vs_4_1";
	}
}

const HlslResourceSlotAssignment* FindAssignment(
	const std::vector<HlslResourceSlotAssignment>& assignments,
	const std::string& name,
	SpirvResourceKind kind
) {
	for (const HlslResourceSlotAssignment& assignment : assignments) {
		if (assignment.kind == kind && assignment.name == name) {
			return &assignment;
		}
	}
	return nullptr;
}

// Forces each resource named in `assignments` to the exact register the
// engine-owned layout model (Milestone 4.4) assigned it, via SPIRV-Cross's
// `add_hlsl_resource_binding` (confirmed against the real, current
// upstream spirv_hlsl.hpp during this session -- see this file's header
// comment). `add_hlsl_resource_binding` keys each entry on
// `(stage, desc_set, binding)` -- the resource's own SPIR-V decorations,
// found here via `get_decoration` -- not on the resource's name, so this
// must look each resource's `(desc_set, binding)` up itself rather than
// somehow attaching a slot number to the name directly.
//
// "On SM 5.0 and lower, register_space is ignored" per spirv_hlsl.hpp's
// own comment on HLSLResourceBinding, so `register_space` is left at its
// default (`0`) throughout -- only `register_binding` (the `#` in
// `register(b#)`/`register(t#)`/etc.) matters for this project's SM 5.0
// target.
void ApplyResourceBindings(
	spirv_cross::CompilerHLSL& compiler,
	spv::ExecutionModel stage,
	const std::vector<HlslResourceSlotAssignment>& assignments
) {
	spirv_cross::ShaderResources resources = compiler.get_shader_resources();

	auto forceRegister = [&](
		const spirv_cross::Resource& resource,
		SpirvResourceKind kind,
		void (*setField)(spirv_cross::HLSLResourceBinding&, uint32_t)
	) {
		const HlslResourceSlotAssignment* assignment = FindAssignment(assignments, SpirvResourceName(resource), kind);
		if (assignment == nullptr) {
			// No engine-assigned slot for this resource -- leave
			// SPIRV-Cross to pick a register automatically. Should not
			// happen for a layout built from this exact SPIR-V's own
			// reflection (Milestone 4.4), but not fatal if it does (e.g.
			// a caller passed a stale layout from before a shader edit).
			return;
		}

		spirv_cross::HLSLResourceBinding binding;
		binding.stage = stage;
		binding.desc_set = compiler.get_decoration(resource.id, spv::DecorationDescriptorSet);
		binding.binding = compiler.get_decoration(resource.id, spv::DecorationBinding);
		setField(binding, assignment->slot);
		compiler.add_hlsl_resource_binding(binding);
	};

	for (const spirv_cross::Resource& resource : resources.uniform_buffers) {
		forceRegister(resource, SpirvResourceKind::ConstantBuffer, [](spirv_cross::HLSLResourceBinding& binding, uint32_t slot) {
			binding.cbv.register_binding = slot;
		});
	}

	for (const spirv_cross::Resource& resource : resources.storage_buffers) {
		// A structured buffer becomes either an SRV (readonly ->
		// ByteAddressBuffer) or a UAV depending on SPIRV-Cross's own
		// readonly-decoration check (see
		// CompilerHLSL::Options::force_storage_buffer_as_uav's doc
		// comment in spirv_hlsl.hpp) -- this project's layout model
		// (Milestone 4.4) does not yet distinguish the two, so the same
		// engine slot is assigned to both the SRV and UAV register
		// spaces here. That is safe (t#/u# are independent register
		// files; only one of the two ever actually applies to a given
		// resource), not a collision.
		forceRegister(resource, SpirvResourceKind::StructuredBuffer, [](spirv_cross::HLSLResourceBinding& binding, uint32_t slot) {
			binding.srv.register_binding = slot;
			binding.uav.register_binding = slot;
		});
	}

	// A combined GLSL `sampler2D` needs both an SRV slot and a Sampler
	// slot -- Milestone 4.4 reflected it as two separate
	// DxReflectedResource/DxResourceLayoutEntry rows under the same name
	// (SampledTexture + Sampler; see directexp_shader_reflection.cpp's
	// own handling of `sampled_images`), so both are looked up here and
	// merged into one HLSLResourceBinding, since add_hlsl_resource_binding
	// keys on (stage, desc_set, binding) -- one SPIR-V resource, one
	// entry -- not on this project's separate engine resource kinds.
	for (const spirv_cross::Resource& resource : resources.sampled_images) {
		std::string name = SpirvResourceName(resource);
		const HlslResourceSlotAssignment* textureAssignment = FindAssignment(assignments, name, SpirvResourceKind::SampledTexture);
		const HlslResourceSlotAssignment* samplerAssignment = FindAssignment(assignments, name, SpirvResourceKind::Sampler);
		if (textureAssignment == nullptr && samplerAssignment == nullptr) {
			continue;
		}

		spirv_cross::HLSLResourceBinding binding;
		binding.stage = stage;
		binding.desc_set = compiler.get_decoration(resource.id, spv::DecorationDescriptorSet);
		binding.binding = compiler.get_decoration(resource.id, spv::DecorationBinding);
		if (textureAssignment != nullptr) {
			binding.srv.register_binding = textureAssignment->slot;
		}
		if (samplerAssignment != nullptr) {
			binding.sampler.register_binding = samplerAssignment->slot;
		}
		compiler.add_hlsl_resource_binding(binding);
	}

	for (const spirv_cross::Resource& resource : resources.separate_images) {
		forceRegister(resource, SpirvResourceKind::SampledTexture, [](spirv_cross::HLSLResourceBinding& binding, uint32_t slot) {
			binding.srv.register_binding = slot;
		});
	}

	for (const spirv_cross::Resource& resource : resources.separate_samplers) {
		forceRegister(resource, SpirvResourceKind::Sampler, [](spirv_cross::HLSLResourceBinding& binding, uint32_t slot) {
			binding.sampler.register_binding = slot;
		});
	}
}

// SPIRV-Cross's SPIR-V -> HLSL translation step. Returns the emitted HLSL
// text, or throws on translation failure (caught by the caller,
// CompileSpirvToD3DBytecode) -- kept as a small standalone function only
// so that function reads as "emit, then compile" at a glance.
std::string EmitHlsl(
	const std::vector<uint32_t>& spirv,
	ShaderExecutionStage stage,
	const std::vector<HlslResourceSlotAssignment>& assignments
) {
	spirv_cross::CompilerHLSL compiler(spirv);

	spirv_cross::CompilerHLSL::Options options;
	// Must agree with ToD3DCompileTarget's vs_5_0/vs_4_1 choice below --
	// see that function's comment for why this can't be hardcoded to 50.
	options.shader_model = (g_activeFeatureLevel >= D3D_FEATURE_LEVEL_11_0) ? 50 : 41;
	compiler.set_hlsl_options(options);

	ApplyResourceBindings(compiler, ToExecutionModel(stage), assignments);

	return compiler.compile();
}

// `D3DCompile`'s HLSL -> D3D11 bytecode step. Never throws: a compile
// failure is `D3DCompile` returning a failed HRESULT with an error blob,
// not an exception, so this reports failure through `result` directly.
void CompileHlslToBytecode(
	const std::string& hlslSource,
	ShaderExecutionStage stage,
	const std::string& shaderName,
	ShaderCompilationResult& result
) {
	ID3DBlob* codeBlob = nullptr;
	ID3DBlob* errorBlob = nullptr;

	// Flags1/Flags2 left at 0 (no D3DCOMPILE_* flags): a reasonable
	// starting point, not a final, tuned choice -- optimization level and
	// debug-info flags (D3DCOMPILE_OPTIMIZATION_LEVEL*,
	// D3DCOMPILE_DEBUG) are worth revisiting once a real shader has
	// actually round-tripped through this path, per this milestone's own
	// "still not drawn yet, just compiled" success criterion.
	HRESULT hr = D3DCompile(
		hlslSource.data(),
		hlslSource.size(),
		shaderName.c_str(),
		nullptr, // pDefines
		nullptr, // pInclude
		"main",  // pEntrypoint -- SPIRV-Cross's CompilerHLSL emits "main"
		         // by default (Options::use_entry_point_name defaults to
		         // false, meaning it does NOT use SPIR-V's own entry
		         // point name and emits the fixed name "main" instead;
		         // see spirv_hlsl.hpp), matching GLSL's own `main()`
		         // shaderc compiled this SPIR-V from.
		ToD3DCompileTarget(stage),
		0, // Flags1
		0, // Flags2
		&codeBlob,
		&errorBlob
	);

	if (FAILED(hr)) {
		result.compileSucceeded = false;
		if (errorBlob != nullptr) {
			result.errorMessage.assign(
				static_cast<const char*>(errorBlob->GetBufferPointer()),
				errorBlob->GetBufferSize()
			);
		}
		if (result.errorMessage.empty()) {
			char hresultBuffer[32];
			std::snprintf(hresultBuffer, sizeof(hresultBuffer), "0x%08lX", static_cast<unsigned long>(hr));
			result.errorMessage = std::string("D3DCompile failed (HRESULT ") + hresultBuffer + ") with no further detail.";
		}
	} else {
		result.compileSucceeded = true;
		const uint8_t* bytecodeStart = static_cast<const uint8_t*>(codeBlob->GetBufferPointer());
		result.bytecode.assign(bytecodeStart, bytecodeStart + codeBlob->GetBufferSize());
	}

	if (codeBlob != nullptr) {
		codeBlob->Release();
	}
	if (errorBlob != nullptr) {
		errorBlob->Release();
	}
}

} // namespace

ShaderCompilationResult CompileSpirvToD3DBytecode(
	const std::vector<uint32_t>& spirv,
	ShaderExecutionStage stage,
	const std::string& shaderName,
	const std::vector<HlslResourceSlotAssignment>& assignments
) {
	ShaderCompilationResult result;

	try {
		result.hlsl = EmitHlsl(spirv, stage, assignments);
		result.emissionSucceeded = true;
	} catch (const std::exception& error) {
		result.emissionSucceeded = false;
		result.errorMessage = error.what();
		if (result.errorMessage.empty()) {
			result.errorMessage = "SPIRV-Cross SPIR-V -> HLSL translation failed with no further detail.";
		}
		return result;
	}

	CompileHlslToBytecode(result.hlsl, stage, shaderName, result);
	return result;
}

jobject BuildHlslCompilationResult(JNIEnv* env, const ShaderCompilationResult& result) {
	jclass resultClass = env->FindClass("dev/cubcraftv/directexp/bridge/NativeDxHlslCompilationResult");
	if (resultClass == nullptr) {
		return nullptr;
	}

	jmethodID constructor = env->GetMethodID(resultClass, "<init>", "(I[BLjava/lang/String;Ljava/lang/String;)V");
	if (constructor == nullptr) {
		return nullptr;
	}

	// OUTCOME_READY / OUTCOME_EMISSION_ERROR / OUTCOME_COMPILE_ERROR below
	// must match NativeDxHlslCompilationResult.java's own constants --
	// kept as bare literals here for the same reason
	// directexp_shader_reflection.cpp's BuildReflectionResult does: this
	// result type's own distinct, expected outcomes, not the generic
	// "unimplemented stub vs. native error" split
	// NativeDxBridgeProbeResult/NativeDxBridgeSurfaceBootstrapResult
	// share.
	jint outcomeCode;
	if (!result.emissionSucceeded) {
		outcomeCode = 1; // OUTCOME_EMISSION_ERROR
	} else if (!result.compileSucceeded) {
		outcomeCode = 2; // OUTCOME_COMPILE_ERROR
	} else {
		outcomeCode = 0; // OUTCOME_READY
	}

	jbyteArray bytecodeArray = nullptr;
	if (result.compileSucceeded) {
		jsize byteLength = static_cast<jsize>(result.bytecode.size());
		bytecodeArray = env->NewByteArray(byteLength);
		if (bytecodeArray != nullptr && byteLength > 0) {
			env->SetByteArrayRegion(bytecodeArray, 0, byteLength, reinterpret_cast<const jbyte*>(result.bytecode.data()));
		}
	}

	// The emitted HLSL is attached whenever emission succeeded, even if
	// D3DCompile then failed -- see this file's header comment on
	// ShaderCompilationResult::hlsl for why.
	jstring hlsl = result.emissionSucceeded ? ToJString(env, result.hlsl) : nullptr;
	jstring detail = ToJString(env, result.errorMessage);

	return env->NewObject(
		resultClass,
		constructor,
		outcomeCode,
		bytecodeArray,
		hlsl,
		detail
	);
}

} // namespace directexp

#endif // _WIN32
