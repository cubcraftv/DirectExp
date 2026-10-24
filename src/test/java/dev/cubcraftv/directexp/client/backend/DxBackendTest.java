package dev.cubcraftv.directexp.client.backend;

import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import dev.cubcraftv.directexp.bridge.DxBridge;
import dev.cubcraftv.directexp.bridge.DxBridgeProbe;
import dev.cubcraftv.directexp.bridge.DxBridgeProbeStatus;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DxBackendTest {
	@Test
	void createsDeviceBeforeWindowAndDefersSurfaceResolution() throws Exception {
		AtomicBoolean probed = new AtomicBoolean();
		AtomicBoolean deviceCreated = new AtomicBoolean();
		AtomicBoolean surfaceResolved = new AtomicBoolean();
		DxBridge bridge = new DxBridge() {
			@Override
			public DxBridgeProbe probe() {
				probed.set(true);
				return new DxBridgeProbe(
					DxBridgeProbeStatus.READY,
					"Ready.",
					List.of(),
					true,
					true,
					DxBridgeProbe.FEATURE_LEVEL_11_0,
					"Test adapter",
					0,
					0L
				);
			}
		};
		Win32HostSurfaceResolver resolver = windowHandle -> {
			surfaceResolved.set(true);
			return new Win32HostSurface(1L);
		};
		GpuDevice device = (GpuDevice) Proxy.newProxyInstance(
			GpuDevice.class.getClassLoader(),
			new Class<?>[]{GpuDevice.class},
			(proxy, method, args) -> null
		);
		DxBackend backend = new DxBackend(
			bridge,
			resolver,
			(dxBridge, surfaceResolver) -> {
				deviceCreated.set(true);
				return device;
			}
		);

		assertSame(device, backend.createDevice(new GpuDebugOptions(0, false, false, false)));
		assertTrue(probed.get());
		assertTrue(deviceCreated.get());
		assertFalse(surfaceResolved.get());
	}
}
