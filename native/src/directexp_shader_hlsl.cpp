// See directexp_shader_hlsl.h for scope. Windows build verification is
// recorded in docs/plans/2026-09-20-directexp-bootstrap-plan.md.

#include "directexp_shader_hlsl.h"
#include "directexp_shader_resource_naming.h"
#include "directexp_common.h"

#include <jni.h>

#ifdef _WIN32

#include <spirv_cross/spirv_hlsl.hpp>

#include <d3dcompiler.h>

#include <cstdio>
#include <cstdlib>
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
		case ShaderExecutionStage::Geometry:
			return shaderModel5 ? "gs_5_0" : "gs_4_1";
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

// SPIR-V core opcodes/fields used here; only PointSize stripping needs to
// read instructions this closely, so the handful of literals stay local
// instead of growing a general enum table. Values must stay in sync with
// spirv.hpp/spirv.coreGrammar (verified against the UCRT64 spirv-cross
// package's headers).
constexpr uint32_t kSpirvMagic = 0x07230203;
constexpr uint32_t kOpName = 5;
constexpr uint32_t kOpEntryPoint = 15;
constexpr uint32_t kOpVariable = 59;
constexpr uint32_t kOpStore = 62;
constexpr uint32_t kOpDecorate = 71;
constexpr uint32_t kDecorationBuiltIn = 11;
constexpr uint32_t kBuiltInPointSize = 1;
constexpr uint32_t kStorageClassOutput = 3;

// Names for the SPIR-V builtins D3DCompile's HLSL cannot express; used so a
// failure says WHICH builtin failed (SPIRV-Cross's own message is only
// "Unsupported builtin in HLSL: 1", the raw enum value). Enum values must
// stay in sync with spirv.hpp's BuiltIn (verified against the UCRT64
// spirv-cross package's headers). Non-exhaustive: the common vertex/fragment
// builtins first (this project's shaders are vertex/fragment only), then
// nullptr (message left as-is).
const char* NameSpirvBuiltin(uint32_t builtin) {
	switch (builtin) {
		case 0: return "Position";
		case 1: return "PointSize";
		case 3: return "ClipDistance";
		case 4: return "CullDistance";
		case 5: return "VertexId";
		case 6: return "InstanceId";
		case 7: return "PrimitiveId";
		case 15: return "FragCoord";
		case 16: return "PointCoord";
		case 17: return "FrontFacing";
		case 18: return "SampleId";
		case 19: return "SamplePosition";
		case 41: return "SubgroupLocalInvocationId";
		default: return nullptr;
	}
}

// Appends `builtin`'s name to a "Unsupported builtin in HLSL: N" message.
std::string DescribeUnsupportedBuiltin(std::string message) {
	const std::string prefix = "Unsupported builtin in HLSL: ";
	size_t at = message.find(prefix);
	if (at == std::string::npos) {
		return message;
	}
	size_t digits = 0;
	while (at + prefix.size() + digits < message.size()
		&& message[at + prefix.size() + digits] >= '0'
		&& message[at + prefix.size() + digits] <= '9') {
		digits++;
	}
	if (digits == 0) {
		return message;
	}
	uint32_t builtin = static_cast<uint32_t>(std::strtoul(message.c_str() + at + prefix.size(), nullptr, 10));
	const char* name = NameSpirvBuiltin(builtin);
	if (name == nullptr) {
		return message;
	}
	return message.insert(at + prefix.size() + digits, std::string(" (") + name + ")");
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

	// Push constants: SPIRV-Cross binds the block through the dedicated
	// push-constant (desc_set, binding) pair rather than decorations. The
	// reflection stage reports it as the constant buffer "DxPushConstants".
	for (const spirv_cross::Resource& resource : resources.push_constant_buffers) {
		(void)resource;
		const HlslResourceSlotAssignment* assignment = FindAssignment(assignments, "DxPushConstants", SpirvResourceKind::ConstantBuffer);
		if (assignment == nullptr) {
			continue;
		}
		spirv_cross::HLSLResourceBinding binding;
		binding.stage = stage;
		binding.desc_set = spirv_cross::ResourceBindingPushConstantDescriptorSet;
		binding.binding = spirv_cross::ResourceBindingPushConstantBinding;
		binding.cbv.register_binding = assignment->slot;
		compiler.add_hlsl_resource_binding(binding);
	}

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
	// gl_PointSize has no HLSL equivalent; ignore it instead of failing the shader (debug points draw 1px).
	options.point_size_compat = true;
	compiler.set_hlsl_options(options);

	// Vulkan-orientation emulation. Blaze3D renders for Vulkan's convention:
	// positive-height viewport, so NDC Y=-1 lands on texture row 0 and every
	// render-to-texture image is stored bottom-up (VulkanGpuSurface flips it
	// again when presenting). D3D11 puts NDC Y=+1 on row 0, so without this
	// flip anything drawn into a texture (e.g. the animate_sprite_blit pass
	// that assembles the GUI atlas) lands on the wrong rows. Negating
	// gl_Position.y in the vertex stage makes D3D framebuffer positions equal
	// Vulkan's; the rasterizer front face (FrontCounterClockwise = FALSE) and
	// the surface blit (vertical flip) are the paired changes.
	if (stage == ShaderExecutionStage::Vertex) {
		spirv_cross::CompilerGLSL::Options common = compiler.get_common_options();
		common.vertex.flip_vert_y = true;
		compiler.set_common_options(common);
	}

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

// Word encoding for the PointSize strip pass below. SPIR-V instructions are
// [opcode | word-count<<16, operands...]; the word count includes this
// two-word pair, so an instruction occupies exactly `wordCount` words.
constexpr size_t kSpirvHeaderSize = 5;

bool PeekInstruction(const std::vector<uint32_t>& spirv, size_t word, size_t& wordCount) {
	uint32_t count = spirv[word] >> 16;
	if (count < 1 || word + count > spirv.size()) {
		return false;
	}
	wordCount = count;
	return true;
}

bool ContainsZeroByte(uint32_t word) {
	return (word & 0xFF) == 0
		|| ((word >> 8) & 0xFF) == 0
		|| ((word >> 16) & 0xFF) == 0
		|| ((word >> 24) & 0xFF) == 0;
}

bool IdVectorContains(const std::vector<uint32_t>& ids, uint32_t id) {
	for (uint32_t known : ids) {
		if (known == id) {
			return true;
		}
	}
	return false;
}

// Removes a vertex stage's `BuiltIn PointSize` output from a SPIR-V module
// (what the header comment on StripPointSizeOutput above describes). Two
// word-level passes so the stripping stays a local, self-contained rewrite
// rather than a dependency on SPIRV-Cross internals (its own parser offers
// no module-rewriting API for this): the first classifies (finds the output
// variable's id), the second rebuilds (drops the OpVariable, its stores and
// decorations, and its OpEntryPoint interface entries).
std::vector<uint32_t> StripPointSizeOutput(const std::vector<uint32_t>& spirv) {
	if (spirv.size() <= kSpirvHeaderSize || spirv[0] != kSpirvMagic) {
		return spirv;
	}

	// Pass 1: locate the candidate -- the output-storage variable decorated
	// BuiltIn PointSize. Anything unexpected (a builtin-1 target that is not
	// an output variable, more than one candidate, a malformed instruction)
	// leaves the module untouched: SPIRV-Cross's `point_size_compat` and this
	// file's unsupported-builtin diagnostics then still apply, so stripping
	// can only make emission more reliable, never worse.
	std::vector<uint32_t> outputVariables;
	std::vector<uint32_t> pointSizeTargets;
	size_t wordCount;
	for (size_t word = kSpirvHeaderSize; word < spirv.size(); word += wordCount) {
		if (!PeekInstruction(spirv, word, wordCount)) {
			return spirv;
		}
		uint32_t opcode = spirv[word] & 0xFFFF;
		const uint32_t* operands = spirv.data() + word + 1;
		size_t operandCount = wordCount - 1;

		if (opcode == kOpVariable) {
			// [result_type, result_id, storage_class, initializers...]
			if (operandCount < 3) {
				return spirv;
			}
			if (operands[2] == kStorageClassOutput) {
				outputVariables.push_back(operands[1]);
			}
		} else if (opcode == kOpDecorate) {
			// [target, decoration, literals...]
			if (operandCount < 2) {
				return spirv;
			}
			if (operands[1] == kDecorationBuiltIn) {
				if (operandCount < 3) {
					return spirv;
				}
				if (operands[2] == kBuiltInPointSize) {
					pointSizeTargets.push_back(operands[0]);
				}
			}
		}
	}

	std::vector<uint32_t> candidates;
	for (uint32_t output : outputVariables) {
		if (IdVectorContains(pointSizeTargets, output)) {
			candidates.push_back(output);
		}
	}
	if (candidates.size() != 1) {
		return spirv; // nothing to strip, or ambiguous -- leave as-is
	}
	uint32_t candidate = candidates[0];

	// Pass 2: rebuild without the candidate's instructions. Exactly one
	// candidate was confirmed above, so verification happens per instruction
	// here: any reference to the candidate outside the removal set aborts.
	std::vector<uint32_t> stripped;
	stripped.reserve(spirv.size());
	stripped.insert(stripped.end(), spirv.begin(), spirv.begin() + kSpirvHeaderSize);

	for (size_t word = kSpirvHeaderSize; word < spirv.size(); word += wordCount) {
		if (!PeekInstruction(spirv, word, wordCount)) {
			return spirv; // unreachable (pass 1 verified), kept for safety
		}
		uint32_t opcode = spirv[word] & 0xFFFF;
		const uint32_t* operands = spirv.data() + word + 1;
		size_t operandCount = wordCount - 1;

		if (opcode == kOpVariable && operands[1] == candidate) {
			continue;
		}

		// OpStore is [pointer, value]; only the pointer identifies the
		// candidate (the value operand may never be the builtin variable --
		// builtin outputs are written, not loaded).
		if (opcode == kOpStore && operandCount >= 1 && operands[0] == candidate) {
			continue;
		}

		// All of the candidate's decorations go (BuiltIn, plus whatever else
		// SPIRV-Cross decorated the variable with, e.g. its pointer type).
		if (opcode == kOpDecorate && operands[0] == candidate) {
			continue;
		}
		if (opcode == kOpName && operandCount >= 1 && operands[0] == candidate) {
			continue;
		}

		if (opcode == kOpEntryPoint) {
			// [execution_model, entry_point_id, name string words..., interface ids...]
			// The name is a literal string: it starts at operands[2] and ends
			// at the first word containing a NUL byte. Keep the name whole and
			// rebuild the header word count after dropping candidate entries
			// from the interface list -- an OpEntryPoint without the removed
			// id must stay a valid, self-consistent instruction.
			if (operandCount < 3) {
				return spirv;
			}
			size_t nameEnd = 2;
			while (nameEnd < operandCount && !ContainsZeroByte(operands[nameEnd])) {
				nameEnd++;
			}
			if (nameEnd == operandCount) {
				return spirv; // unterminated name -- leave the module untouched
			}
			size_t entryStart = stripped.size();
			stripped.push_back(spirv[word]); // header word, word count patched below
			stripped.push_back(operands[0]); // execution model
			stripped.push_back(operands[1]); // entry point id
			for (size_t nameWord = 2; nameWord <= nameEnd; nameWord++) {
				stripped.push_back(operands[nameWord]);
			}
			for (size_t index = nameEnd + 1; index < operandCount; index++) {
				if (operands[index] != candidate) {
					stripped.push_back(operands[index]);
				}
			}
			uint32_t newWordCount = static_cast<uint32_t>(stripped.size() - entryStart);
			stripped[entryStart] = (newWordCount << 16) | kOpEntryPoint;
			continue;
		}

		// Any other instruction must not reference the candidate at all (an
		// OpLoad/OpAccessChain reading the output back would, e.g.) -- being
		// permissive there is exactly how modules get corrupted.
		for (size_t index = 0; index < operandCount; index++) {
			if (operands[index] == candidate) {
				return spirv;
			}
		}
		stripped.push_back(spirv[word]);
		for (size_t index = 0; index < operandCount; index++) {
			stripped.push_back(operands[index]);
		}
	}

	stripped.shrink_to_fit();
	return stripped;
}

ShaderCompilationResult CompileSpirvToD3DBytecode(
	const std::vector<uint32_t>& spirv,
	ShaderExecutionStage stage,
	const std::string& shaderName,
	const std::vector<HlslResourceSlotAssignment>& assignments
) {
	ShaderCompilationResult result;

	try {
		result.hlsl = EmitHlsl(stage == ShaderExecutionStage::Vertex ? StripPointSizeOutput(spirv) : spirv, stage, assignments);
		result.emissionSucceeded = true;
	} catch (const std::exception& error) {
		result.emissionSucceeded = false;
		result.errorMessage = DescribeUnsupportedBuiltin(error.what());
		if (result.errorMessage.empty()) {
			result.errorMessage = "SPIRV-Cross SPIR-V -> HLSL translation failed with no further detail.";
		}
		return result;
	}

	CompileHlslToBytecode(result.hlsl, stage, shaderName, result);
	return result;
}

ShaderCompilationResult CompileHlslToD3DBytecode(
	const std::string& hlslSource,
	ShaderExecutionStage stage,
	const std::string& shaderName
) {
	ShaderCompilationResult result;
	result.emissionSucceeded = true;
	result.hlsl = hlslSource;
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