// DirectExp native bridge - capability probe entrypoint.
//
// Scope (mirrors MetalExp's "Milestone 2: Native Surface Bring-Up" starting
// point): this file only answers "can we stand up a D3D11 device at feature
// level 10.1 or better on this machine", plus basic adapter identity for
// diagnostics. It does NOT yet create a swap chain, attach a window, or
// submit any commands. Those land in later milestones alongside the
// corresponding Java-side bridge methods (see docs/plans).
//
// NOTE: this file has not been compiled in the authoring environment (a
// Linux sandbox without an MSVC/Windows SDK toolchain available). Treat it
// as a structurally-complete first draft to compile, run, and correct on an
// actual Windows machine, not as verified-working native code.
//
// JNI naming: Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_<method>
// mirrors the `native` method declarations in NativeDxBridge.java.

#include <jni.h>

#ifdef _WIN32
#include <windows.h>
#include <d3d11.h>
#include <dxgi.h>
#include <iterator>
#include <string>
#include <vector>

// Deliberately not using Microsoft::WRL::ComPtr (<wrl/client.h>) here: it is
// an MSVC-first header that is only reliably available on newer mingw-w64
// distributions, and this project explicitly supports MinGW toolchains
// (e.g. MSYS2 UCRT64) as a first-class option, not just MSVC. Plain
// pointers with manual Release() calls below avoid that dependency
// entirely. Linking itself goes through CMake's target_link_libraries with
// bare library names (see native/CMakeLists.txt), not #pragma comment(lib),
// for the same MSVC/MinGW portability reason.

namespace {

// Keep in sync with DxBridgeProbeStatus-adjacent outcome codes used by
// NativeDxBridgeProbeResult.java.
constexpr jint OUTCOME_READY = 0;
constexpr jint OUTCOME_STUB_UNIMPLEMENTED = 1;
constexpr jint OUTCOME_ERROR = 2;

// DirectExp's floor per project scope: DX11 feature set, FL 10.1 devices.
constexpr D3D_FEATURE_LEVEL kMinimumFeatureLevel = D3D_FEATURE_LEVEL_10_1;

const D3D_FEATURE_LEVEL kRequestedFeatureLevels[] = {
	D3D_FEATURE_LEVEL_11_1,
	D3D_FEATURE_LEVEL_11_0,
	D3D_FEATURE_LEVEL_10_1,
};

std::wstring Utf8ToWide(const char* text) {
	if (text == nullptr) {
		return std::wstring();
	}
	int length = MultiByteToWideChar(CP_UTF8, 0, text, -1, nullptr, 0);
	if (length <= 0) {
		return std::wstring();
	}
	std::wstring wide(static_cast<size_t>(length), L'\0');
	MultiByteToWideChar(CP_UTF8, 0, text, -1, wide.data(), length);
	return wide;
}

std::string WideToUtf8(const wchar_t* text) {
	if (text == nullptr) {
		return std::string();
	}
	int length = WideCharToMultiByte(CP_UTF8, 0, text, -1, nullptr, 0, nullptr, nullptr);
	if (length <= 0) {
		return std::string();
	}
	std::string narrow(static_cast<size_t>(length), '\0');
	WideCharToMultiByte(CP_UTF8, 0, text, -1, narrow.data(), length, nullptr, nullptr);
	// Drop the trailing NUL WideCharToMultiByte counted into length.
	if (!narrow.empty() && narrow.back() == '\0') {
		narrow.pop_back();
	}
	return narrow;
}

jstring ToJString(JNIEnv* env, const std::string& text) {
	return env->NewStringUTF(text.c_str());
}

// Mirrors: record NativeDxBridgeProbeResult(int outcomeCode, String detail,
// String[] missingCapabilities, int featureLevel, String adapterDescription,
// int vendorId, long dedicatedVideoMemoryBytes)
jobject BuildProbeResult(
	JNIEnv* env,
	jint outcomeCode,
	const std::string& detail,
	const std::vector<std::string>& missingCapabilities,
	jint featureLevel,
	const std::string& adapterDescription,
	jint vendorId,
	jlong dedicatedVideoMemoryBytes
) {
	jclass resultClass = env->FindClass("dev/cubcraftv/directexp/bridge/NativeDxBridgeProbeResult");
	if (resultClass == nullptr) {
		return nullptr;
	}

	jclass stringClass = env->FindClass("java/lang/String");
	jobjectArray missingArray = env->NewObjectArray(
		static_cast<jsize>(missingCapabilities.size()), stringClass, nullptr
	);
	for (jsize i = 0; i < static_cast<jsize>(missingCapabilities.size()); ++i) {
		env->SetObjectArrayElement(missingArray, i, ToJString(env, missingCapabilities[static_cast<size_t>(i)]));
	}

	jmethodID constructor = env->GetMethodID(
		resultClass,
		"<init>",
		"(ILjava/lang/String;[Ljava/lang/String;ILjava/lang/String;IJ)V"
	);
	if (constructor == nullptr) {
		return nullptr;
	}

	return env->NewObject(
		resultClass,
		constructor,
		outcomeCode,
		ToJString(env, detail),
		missingArray,
		featureLevel,
		ToJString(env, adapterDescription),
		vendorId,
		dedicatedVideoMemoryBytes
	);
}

// Encodes a D3D_FEATURE_LEVEL as a plain int (e.g. 10_1 -> 0xA100) so the
// Java side does not need to know about the native enum values.
jint EncodeFeatureLevel(D3D_FEATURE_LEVEL level) {
	return static_cast<jint>(level);
}

} // namespace

extern "C" JNIEXPORT jobject JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_probe0(JNIEnv* env, jclass) {
	ID3D11Device* device = nullptr;
	ID3D11DeviceContext* context = nullptr;
	D3D_FEATURE_LEVEL negotiatedLevel = static_cast<D3D_FEATURE_LEVEL>(0);

	UINT createFlags = 0;
#ifdef _DEBUG
	createFlags |= D3D11_CREATE_DEVICE_DEBUG;
#endif

	HRESULT hr = D3D11CreateDevice(
		nullptr,                    // default adapter
		D3D_DRIVER_TYPE_HARDWARE,
		nullptr,
		createFlags,
		kRequestedFeatureLevels,
		static_cast<UINT>(std::size(kRequestedFeatureLevels)),
		D3D11_SDK_VERSION,
		&device,
		&negotiatedLevel,
		&context
	);

	if (FAILED(hr)) {
		return BuildProbeResult(
			env,
			OUTCOME_ERROR,
			"D3D11CreateDevice failed; no D3D11 runtime or no hardware adapter available.",
			{"d3d11_device"},
			0,
			std::string(),
			0,
			0
		);
	}

	if (negotiatedLevel < kMinimumFeatureLevel) {
		jobject result = BuildProbeResult(
			env,
			OUTCOME_ERROR,
			"D3D11 device created but negotiated feature level is below the DirectExp floor (10.1).",
			{"feature_level_10_1"},
			EncodeFeatureLevel(negotiatedLevel),
			std::string(),
			0,
			0
		);
		if (context != nullptr) {
			context->Release();
		}
		device->Release();
		return result;
	}

	// Adapter identity, for diagnostics, via the device's owning DXGI adapter.
	IDXGIDevice* dxgiDevice = nullptr;
	IDXGIAdapter* dxgiAdapter = nullptr;
	std::string adapterDescription;
	jint vendorId = 0;
	jlong dedicatedVideoMemoryBytes = 0;

	if (SUCCEEDED(device->QueryInterface(__uuidof(IDXGIDevice), reinterpret_cast<void**>(&dxgiDevice)))) {
		if (SUCCEEDED(dxgiDevice->GetAdapter(&dxgiAdapter))) {
			DXGI_ADAPTER_DESC desc = {};
			if (SUCCEEDED(dxgiAdapter->GetDesc(&desc))) {
				adapterDescription = WideToUtf8(desc.Description);
				vendorId = static_cast<jint>(desc.VendorId);
				dedicatedVideoMemoryBytes = static_cast<jlong>(desc.DedicatedVideoMemory);
			}
			dxgiAdapter->Release();
		}
		dxgiDevice->Release();
	}

	jobject result = BuildProbeResult(
		env,
		OUTCOME_READY,
		"D3D11 device created at or above feature level 10.1.",
		{},
		EncodeFeatureLevel(negotiatedLevel),
		adapterDescription,
		vendorId,
		dedicatedVideoMemoryBytes
	);

	if (context != nullptr) {
		context->Release();
	}
	device->Release();

	return result;
}

#else // !_WIN32

// Non-Windows build: the library still needs to link (e.g. if a CI matrix
// ever compiles it for lint/format checks), but every entrypoint reports
// "unsupported OS" rather than attempting anything.
extern "C" JNIEXPORT jobject JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_probe0(JNIEnv* env, jclass) {
	(void) env;
	return nullptr;
}

#endif
