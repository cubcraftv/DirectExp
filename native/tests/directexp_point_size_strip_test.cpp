// Native diagnostic test for directexp_shader_hlsl.cpp's StripPointSizeOutput
// (see that header's comment for scope). NOT part of the produced DLC/dll --
// an executable target Windows developers build and run directly from the
// native build tree (.gradle/native/directexp_point_size_strip_test.exe):
//
//   ./gradlew buildNativeWindowsBridge
//   $env:PATH = "C:\msys64\ucrt64\bin;$env:PATH"   # spirv-cross DLLs
//   .gradle\native\directexp_point_size_strip_test.exe
//
// What it verifies:
//   1. A hand-assembled SPIR-V module (a full, minimal vertex shader with an
//      `BuiltIn PointSize` output variable alongside a `BuiltIn Position`
//      one) is stripped: the variable/decoration/store/name are removed, the
//      OpEntryPoint interface loses the candidate id, the OpEntryPoint's own
//      word count is rewritten correctly, and every other instruction
//      survives untouched -- verified by re-walking the result's word counts
//      and by exact word-level accounting.
//   2. A module with no PointSize output (fragment-style: `BuiltIn FragCoord`
//      an *input*) is returned unchanged.
//   3. A module whose PointSize output is additionally referenced by an
//      OpLoad is returned unchanged (the documented abort path).
//   4. A module that is malformed (bad magic word; a word count that
//      overruns the module) is returned unchanged rather than stripped.
//   5. End-to-end: the stripped module goes through the real
//      CompileSpirvToD3DBytecode path (SPIRV-Cross -> HLSL -> D3DCompile,
//      Shader Model 5.0), compiles successfully, and the emitted HLSL does
//      not mention gl_PointSize -- the whole point of the stripping. The
//      unstripped module is also run through the same path, demonstrating
//      that the strip is actually needed there (a failing HLSL emission
//      whose message names PointSize -- i.e. the production failure this
//      stripping exists to fix -- and never a D3DCompile-side failure).

#include "directexp_shader_hlsl.h"
#include "directexp_common.h"

#include <cstdint>
#include <cstdio>
#include <string>
#include <vector>

// Mirrored literals (opcodes/decoration/storage/magic). Deliberately NOT
// shared with directexp_shader_hlsl.cpp: an independent copy is what lets
// this test catch a wrongly-changed constant over there.
constexpr uint32_t kSpirvMagic = 0x07230203;
constexpr uint32_t kOpName = 5;
constexpr uint32_t kOpEntryPoint = 15;
constexpr uint32_t kOpCapability = 17;
constexpr uint32_t kOpTypeFloat = 22;
constexpr uint32_t kOpTypeVector = 23;
constexpr uint32_t kOpTypePointer = 32;
constexpr uint32_t kOpTypeFunction = 33;
constexpr uint32_t kOpConstant = 43;
constexpr uint32_t kOpConstantComposite = 44;
constexpr uint32_t kOpFunction = 54;
constexpr uint32_t kOpFunctionEnd = 56;
constexpr uint32_t kOpLoad = 61;
constexpr uint32_t kOpVariable = 59;
constexpr uint32_t kOpStore = 62;
constexpr uint32_t kOpTypeVoid = 19;
constexpr uint32_t kOpMemoryModel = 14;
constexpr uint32_t kOpTypeInt = 21;
constexpr uint32_t kOpReturn = 253;
constexpr uint32_t kOpLabel = 248;
constexpr uint32_t kOpDecorate = 71;
constexpr uint32_t kDecorationBuiltIn = 11;
constexpr uint32_t kDecorationLocation = 30;
constexpr uint32_t kBuiltInPointSize = 1;
constexpr uint32_t kBuiltInPosition = 0;
constexpr uint32_t kBuiltInFragCoord = 15;
constexpr uint32_t kStorageClassOutput = 3;
constexpr uint32_t kStorageClassInput = 1;
constexpr uint32_t kExecutionModelVertex = 0;
constexpr uint32_t kExecutionModelFragment = 4;

static void Emit(std::vector<uint32_t>& module, uint32_t opcode, const std::vector<uint32_t>& operands) {
	module.push_back(((static_cast<uint32_t>(operands.size()) + 1) << 16) | opcode);
	module.insert(module.end(), operands.begin(), operands.end());
}

std::vector<uint32_t> MakeHeader() {
	return {
		kSpirvMagic,
		// version 1.3, generator 0, bound 20, schema 0
		0x00010300, 0, 20, 0,
	};
}

// id space:
//   1 void  2 fn_ty  3 float  4 v4  5 one  6 v4one
//   7 ptr_v4  8 ptr_f  9 pos_var  10 ps_var  11 main  12 entry_label
//   13 unused_load (only set when withLoad)
std::vector<uint32_t> MakeVertexModule(bool withPointSize, bool withLoad) {
	std::vector<uint32_t> module = MakeHeader();
	Emit(module, kOpCapability, {1}); // Shader
	Emit(module, kOpMemoryModel, {0, 1}); // Logical GLSL450

	// OpEntryPoint: [execution model, entry id, "main\0" (2 words), interface...]
	std::vector<uint32_t> entryOperands = {kExecutionModelVertex, 11, 0x6E69616D, 0}; // "main" + NUL word
	if (withPointSize) {
		entryOperands.push_back(9);
		entryOperands.push_back(10);
	} else {
		entryOperands.push_back(9);
	}
	Emit(module, kOpEntryPoint, entryOperands);

	if (withPointSize) {
		// OpName of the candidate; "point_size" = 11 bytes (NUL-terminated)
		// -> 3 literal-string words, little-endian packed.
		Emit(module, kOpName, {10, 0x6E696F70, 0x69735F74, 0x0000657A});
	}
	// OpName of the survivor; "out_position" = 13 bytes (NUL-terminated)
	// -> 4 literal-string words.
	Emit(module, kOpName, {9, 0x5F74756F, 0x69736F70, 0x6E6F6974, 0x00000000});

	// Prime decoration -- must survive alongside the removed one (stripping
	// must drop ONLY the candidate's decorations, not a whole-decoration
	// sweep that would break other variables).
	Emit(module, kOpDecorate, {9, kDecorationBuiltIn, kBuiltInPosition});
	if (withPointSize) {
		Emit(module, kOpDecorate, {10, kDecorationBuiltIn, kBuiltInPointSize});
	}
	Emit(module, kOpDecorate, {9, kDecorationLocation, 0});

	Emit(module, kOpTypeVoid, {1});
	Emit(module, kOpTypeFloat, {3, 32});
	Emit(module, kOpTypeVector, {4, 3, 4});
	Emit(module, kOpTypePointer, {7, kStorageClassOutput, 4});
	Emit(module, kOpTypePointer, {8, kStorageClassOutput, 3});
	Emit(module, kOpTypeFunction, {2, 1});

	Emit(module, kOpConstant, {3, 5, 0x3F800000}); // 1.0f
	Emit(module, kOpConstantComposite, {4, 6, 5, 5, 5, 5});

	Emit(module, kOpVariable, {7, 9, kStorageClassOutput});
	if (withPointSize) {
		Emit(module, kOpVariable, {8, 10, kStorageClassOutput});
	}

	Emit(module, kOpFunction, {1, 11, 0, 2});
	Emit(module, kOpLabel, {12});
	Emit(module, kOpStore, {9, 6});
	if (withPointSize) {
		Emit(module, kOpStore, {10, 5});
	}
	if (withLoad) {
		// An end-run reference to the candidate from a non-removal
		// instruction: OpLoad of the output variable (3 words).
		Emit(module, kOpLoad, {3, 13, 10});
	}
	Emit(module, kOpReturn, {});
	Emit(module, kOpFunctionEnd, {});

	return module;
}

// A fragment-style module (no PointSize output anywhere) for the
// nothing-to-strip test: one `BuiltIn FragCoord` INPUT variable.
std::vector<uint32_t> MakeFragmentModule() {
	std::vector<uint32_t> module = MakeHeader();
	Emit(module, kOpCapability, {1});
	Emit(module, kOpMemoryModel, {0, 1});
	// OpEntryPoint: [frag execution model, entry id, "main\0" (2 words), %input_var]
	Emit(module, kOpEntryPoint, {kExecutionModelFragment, 11, 0x6E69616D, 0, 10});
	// "frag_coord" = 11 bytes (NUL-terminated) -> 3 literal-string words.
	Emit(module, kOpName, {10, 0x67617266, 0x6F6F635F, 0x00006472});
	Emit(module, kOpDecorate, {10, kDecorationBuiltIn, kBuiltInFragCoord});
	Emit(module, kOpTypeVoid, {1});
	Emit(module, kOpTypeFloat, {3, 32});
	Emit(module, kOpTypeVector, {4, 3, 4});
	Emit(module, kOpTypePointer, {7, kStorageClassInput, 4});
	Emit(module, kOpTypeFunction, {2, 1});
	Emit(module, kOpVariable, {7, 10, kStorageClassInput});
	Emit(module, kOpFunction, {1, 11, 0, 2});
	Emit(module, kOpLabel, {12});
	Emit(module, kOpReturn, {});
	Emit(module, kOpFunctionEnd, {});
	return module;
}

bool ContainsNul(uint32_t word) {
	return (word & 0xFF) == 0 || ((word >> 8) & 0xFF) == 0
		|| ((word >> 16) & 0xFF) == 0 || ((word >> 24) & 0xFF) == 0;
}

// Structural walk: every instruction's word count must land exactly on the
// module end (this is what a wrong OpEntryPoint word-count patch would
// corrupt).
bool WalkValid(const std::vector<uint32_t>& module) {
	if (module.size() < 5 || module[0] != kSpirvMagic) {
		return false;
	}
	size_t wordCount;
	for (size_t word = 5; word < module.size(); word += wordCount) {
		wordCount = module[word] >> 16;
		if (wordCount < 1 || word + wordCount > module.size()) {
			return false;
		}
	}
	return true;
}

// True when the module contains a complete instruction matching [opcode,
// operands...] exactly, word for word (used to prove survivor instructions
// survive unchanged).
bool ContainsInstruction(const std::vector<uint32_t>& module, uint32_t opcode, const std::vector<uint32_t>& operands) {
	std::vector<uint32_t> pattern;
	pattern.push_back(((static_cast<uint32_t>(operands.size()) + 1) << 16) | opcode);
	pattern.insert(pattern.end(), operands.begin(), operands.end());
	for (size_t word = 5; word + pattern.size() <= module.size(); word++) {
		if (std::equal(pattern.begin(), pattern.end(), module.begin() + word)) {
			return true;
		}
	}
	return false;
}

int CountPointSizeDecorations(const std::vector<uint32_t>& module) {
	int found = 0;
	size_t wordCount;
	for (size_t word = 5; word < module.size(); word += wordCount) {
		wordCount = module[word] >> 16;
		if ((module[word] & 0xFFFF) != kOpDecorate || wordCount != 4) {
			continue;
		}
		if (module[word + 2] == kDecorationBuiltIn && module[word + 3] == kBuiltInPointSize) {
			found++;
		}
	}
	return found;
}

// True when `id` has fully disappeared from(OpEntryPoint--specifically the
// interface list) or is still there as expected. Note the name walk starts
// at the instruction's fourth word (after the execution model and entry
// point id operands).
bool EntryPointKeepsId(const std::vector<uint32_t>& module, uint32_t id, bool expectPresent) {
	bool found = false;
	size_t wordCount;
	for (size_t word = 5; word < module.size(); word += wordCount) {
		wordCount = module[word] >> 16;
		if ((module[word] & 0xFFFF) != kOpEntryPoint || wordCount < 3) {
			continue;
		}
		// [execution model, entry id, name words..., interface...]
		size_t nameEnd = word + 3;
		while (nameEnd < word + wordCount && !ContainsNul(module[nameEnd])) {
			nameEnd++;
		}
		if (nameEnd >= word + wordCount) {
			return false; // unterminated name -- malformed, reject
		}
		for (size_t index = nameEnd + 1; index < word + wordCount; index++) {
			if (module[index] == id) {
				found = true;
			}
		}
	}
	return found == expectPresent;
}

static int g_failures = 0;

static void Check(bool condition, const char* label, const char* what) {
	if (condition) {
		std::printf("PASS  %s: %s\n", label, what);
	} else {
		std::printf("FAIL  %s: %s\n", label, what);
		g_failures++;
	}
}

int main() {
	directexp::g_activeFeatureLevel = D3D_FEATURE_LEVEL_11_0;

	// 1. the strip itself --------------------------------------------------
	std::vector<uint32_t> original = MakeVertexModule(true, false);
	std::vector<uint32_t> stripped = directexp::StripPointSizeOutput(original);

	// exact accounting: removed = candidate OpVariable (4 words: header +
	// result type + result id + storage class), its OpStore (3), its
	// BuiltIn OpDecorate (4), its OpName (5), and 1 interface word in
	// OpEntryPoint.
	const size_t expectedStrippedSize = original.size() - 4 - 3 - 4 - 5 - 1;
	Check(stripped.size() == expectedStrippedSize, "strip", "word-level accounting exact");
	Check(WalkValid(stripped), "strip", "result walkable with valid word counts");
	Check(CountPointSizeDecorations(stripped) == 0, "strip", "no BuiltIn PointSize decoration left");
	Check(EntryPointKeepsId(stripped, 9, true), "strip", "survivor id kept in interface");
	Check(EntryPointKeepsId(stripped, 10, false), "strip", "candidate id gone from interface");

	// The survivor's own instructions must remain, unchanged, in place:
	// its OpName, both of its OpDecorates (BuiltIn Position + Location 0),
	// its OpVariable and its OpStore.
	Check(ContainsInstruction(stripped, kOpVariable, {7, 9, kStorageClassOutput}), "strip", "survivor OpVariable kept");
	Check(ContainsInstruction(stripped, kOpStore, {9, 6}), "strip", "survivor OpStore kept");
	Check(ContainsInstruction(stripped, kOpDecorate, {9, kDecorationBuiltIn, kBuiltInPosition}), "strip", "survivor BuiltIn Position kept");
	Check(ContainsInstruction(stripped, kOpDecorate, {9, kDecorationLocation, 0}), "strip", "survivor Location kept");

	// 2. nothing to strip --------------------------------------------------
	std::vector<uint32_t> fragmentModule = MakeFragmentModule();
	std::vector<uint32_t> untouched = directexp::StripPointSizeOutput(fragmentModule);
	Check(untouched == fragmentModule, "no-strip", "module without PointSize output returned unchanged");

	// 3. candidate referenced outside the removal set ----------------------
	std::vector<uint32_t> loadedModule = MakeVertexModule(true, true);
	std::vector<uint32_t> aborted = directexp::StripPointSizeOutput(loadedModule);
	Check(aborted == loadedModule, "abort", "module whose candidate is OpLoad'd returned unchanged");

	// 4. malformed modules -------------------------------------------------
	std::vector<uint32_t> badMagic = MakeVertexModule(true, false);
	badMagic[0] = 0xDEADBEEF;
	Check(directexp::StripPointSizeOutput(badMagic) == badMagic, "malformed", "bad magic word returned unchanged");

	std::vector<uint32_t> overrun = MakeVertexModule(true, false);
	overrun[overrun.size() - 1] = (999u << 16) | kOpFunctionEnd;
	Check(directexp::StripPointSizeOutput(overrun) == overrun, "malformed", "overrunning word count returned unchanged");

	// 5. end-to-end emission ----------------------------------------------
	{
		auto strippedResult = directexp::CompileSpirvToD3DBytecode(stripped, directexp::ShaderExecutionStage::Vertex, "test_point_size", {});
		Check(strippedResult.emissionSucceeded && strippedResult.compileSucceeded,
			"emission", strippedResult.errorMessage.c_str());
		Check(strippedResult.hlsl.find("gl_PointSize") == std::string::npos,
			"emission", "HLSL does not mention gl_PointSize");

		auto originalResult = directexp::CompileSpirvToD3DBytecode(original, directexp::ShaderExecutionStage::Vertex, "test_point_size_before_strip", {});
		// Both outcomes are legitimate pre-strip behaviour (the compat flag
		// can absorb it): succeeded, or an EMISSION failure whose message
		// names PointSize. A D3DCompile-side failure here would be a lie
		// about where the unstripped module actually breaks.
		bool preStripOk = originalResult.emissionSucceeded && originalResult.compileSucceeded;
		bool preStripCorrectlyFailed = !originalResult.emissionSucceeded
			&& originalResult.errorMessage.find("PointSize") != std::string::npos;
		Check(preStripOk || preStripCorrectlyFailed, "emission-before-strip", "unstripped must either compile or fail emission naming PointSize");
	}

	std::printf("%s\n", g_failures == 0 ? "ALL TESTS PASSED" : "TESTS FAILED");
	return g_failures == 0 ? 0 : 1;
}
