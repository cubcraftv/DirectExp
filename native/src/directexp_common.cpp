#include "directexp_common.h"

#ifdef _WIN32
#include <iterator>

namespace directexp {

const D3D_FEATURE_LEVEL kRequestedFeatureLevels[3] = {
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
	if (!narrow.empty() && narrow.back() == '\0') {
		narrow.pop_back();
	}
	return narrow;
}

jstring ToJString(JNIEnv* env, const std::string& text) {
	return env->NewStringUTF(text.c_str());
}

void ThrowIllegalState(JNIEnv* env, const std::string& message) {
	jclass exceptionClass = env->FindClass("java/lang/IllegalStateException");
	if (exceptionClass == nullptr) {
		// FindClass itself already left a pending exception (most likely
		// java/lang/NoClassDefFoundError) if this happens.
		return;
	}
	env->ThrowNew(exceptionClass, message.c_str());
}

double QueryDpiScaleForWindow(HWND hwnd) {
	using PFN_GetDpiForWindow = UINT(WINAPI*)(HWND);

	static PFN_GetDpiForWindow pGetDpiForWindow = reinterpret_cast<PFN_GetDpiForWindow>(
		reinterpret_cast<void*>(GetProcAddress(GetModuleHandleW(L"user32.dll"), "GetDpiForWindow"))
	);

	if (pGetDpiForWindow != nullptr) {
		UINT dpi = pGetDpiForWindow(hwnd);
		if (dpi > 0) {
			return static_cast<double>(dpi) / 96.0;
		}
	}

	return 1.0;
}

DeviceProbeResult RunDeviceCapabilityProbe(bool gatherAdapterInfo) {
	DeviceProbeResult result;

	ID3D11Device* device = nullptr;
	ID3D11DeviceContext* context = nullptr;
	D3D_FEATURE_LEVEL negotiatedLevel = static_cast<D3D_FEATURE_LEVEL>(0);

	UINT createFlags = 0;
#ifdef _DEBUG
	createFlags |= D3D11_CREATE_DEVICE_DEBUG;
#endif

	HRESULT hr = D3D11CreateDevice(
		nullptr, // default adapter
		D3D_DRIVER_TYPE_HARDWARE,
		nullptr,
		createFlags,
		kRequestedFeatureLevels,
		kRequestedFeatureLevelCount,
		D3D11_SDK_VERSION,
		&device,
		&negotiatedLevel,
		&context
	);

	if (FAILED(hr)) {
		result.ready = false;
		result.outcomeCode = OUTCOME_ERROR;
		result.detail = "D3D11CreateDevice failed; no D3D11 runtime or no hardware adapter available.";
		result.missingCapabilities.push_back("d3d11_device");
		return result;
	}

	if (negotiatedLevel < kMinimumFeatureLevel) {
		result.ready = false;
		result.outcomeCode = OUTCOME_ERROR;
		result.detail = "D3D11 device created but negotiated feature level is below the DirectExp floor (10.1).";
		result.missingCapabilities.push_back("feature_level_10_1");
		result.featureLevel = static_cast<jint>(negotiatedLevel);
		if (context != nullptr) {
			context->Release();
		}
		device->Release();
		return result;
	}

	result.ready = true;
	result.outcomeCode = OUTCOME_READY;
	result.detail = "D3D11 device created at or above feature level 10.1.";
	result.featureLevel = static_cast<jint>(negotiatedLevel);

	if (gatherAdapterInfo) {
		IDXGIDevice* dxgiDevice = nullptr;
		IDXGIAdapter* dxgiAdapter = nullptr;

		if (SUCCEEDED(device->QueryInterface(__uuidof(IDXGIDevice), reinterpret_cast<void**>(&dxgiDevice)))) {
			if (SUCCEEDED(dxgiDevice->GetAdapter(&dxgiAdapter))) {
				DXGI_ADAPTER_DESC desc = {};
				if (SUCCEEDED(dxgiAdapter->GetDesc(&desc))) {
					result.adapterDescription = WideToUtf8(desc.Description);
					result.vendorId = static_cast<jint>(desc.VendorId);
					result.dedicatedVideoMemoryBytes = static_cast<jlong>(desc.DedicatedVideoMemory);
				}
				dxgiAdapter->Release();
			}
			dxgiDevice->Release();
		}
	}

	if (context != nullptr) {
		context->Release();
	}
	device->Release();

	return result;
}

jobject BuildProbeResult(JNIEnv* env, const DeviceProbeResult& result) {
	jclass resultClass = env->FindClass("dev/cubcraftv/directexp/bridge/NativeDxBridgeProbeResult");
	if (resultClass == nullptr) {
		return nullptr;
	}

	jclass stringClass = env->FindClass("java/lang/String");
	jobjectArray missingArray = env->NewObjectArray(
		static_cast<jsize>(result.missingCapabilities.size()), stringClass, nullptr
	);
	for (jsize i = 0; i < static_cast<jsize>(result.missingCapabilities.size()); ++i) {
		env->SetObjectArrayElement(missingArray, i, ToJString(env, result.missingCapabilities[static_cast<size_t>(i)]));
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
		result.outcomeCode,
		ToJString(env, result.detail),
		missingArray,
		result.featureLevel,
		ToJString(env, result.adapterDescription),
		result.vendorId,
		result.dedicatedVideoMemoryBytes
	);
}

jobject BuildSurfaceBootstrapResult(
	JNIEnv* env,
	jlong nativeSurfaceHandle,
	jint drawableWidth,
	jint drawableHeight,
	jdouble dpiScale,
	jint outcomeCode,
	const std::string& detail,
	const std::vector<std::string>& missingCapabilities
) {
	jclass resultClass = env->FindClass("dev/cubcraftv/directexp/bridge/NativeDxBridgeSurfaceBootstrapResult");
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
		"(JIIDILjava/lang/String;[Ljava/lang/String;)V"
	);
	if (constructor == nullptr) {
		return nullptr;
	}

	return env->NewObject(
		resultClass,
		constructor,
		nativeSurfaceHandle,
		drawableWidth,
		drawableHeight,
		dpiScale,
		outcomeCode,
		ToJString(env, detail),
		missingArray
	);
}

} // namespace directexp

#endif // _WIN32
