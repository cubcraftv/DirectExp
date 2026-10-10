package dev.cubcraftv.directexp.client.backend;

import dev.cubcraftv.directexp.bridge.DxTexelFormat;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DxTexelFormatTest {
	@Test
	void codesAreUniqueAndContiguousFromOneBecauseTheNativeSwitchRelaysOnThem() {
		Set<Integer> seen = new HashSet<>();
		for (DxTexelFormat format : DxTexelFormat.values()) {
			assertEquals(true, seen.add(format.code()), format.name());
			assertEquals(format, DxTexelFormat.fromCode(format.code()));
		}
		for (int code = 1; code <= DxTexelFormat.values().length; code++) {
			assertEquals(true, seen.contains(code), "missing code " + code);
		}
		assertNull(DxTexelFormat.fromCode(0));
		assertNull(DxTexelFormat.fromCode(DxTexelFormat.values().length + 1));
	}

	@Test
	void sodiumsSectionTimeFormatResolvesToFourByteElements() {
		DxTexelFormat format = DxTexelFormat.fromGpuFormatName("R32_SINT");
		assertEquals(DxTexelFormat.R32_SINT, format);
		assertEquals(4, format.bytesPerElement());
		assertEquals(20, format.code());
	}

	@Test
	void formatsD3D11CannotViewAsABufferAreRejected() {
		assertNull(DxTexelFormat.fromGpuFormatName("RGB8_UNORM"));
		assertNull(DxTexelFormat.fromGpuFormatName("D32_FLOAT"));
		assertNull(DxTexelFormat.fromGpuFormatName(null));
	}

	@Test
	void elementSizesMatchTheirComponentCounts() {
		assertEquals(16, DxTexelFormat.RGBA32_FLOAT.bytesPerElement());
		assertEquals(12, DxTexelFormat.RGB32_UINT.bytesPerElement());
		assertEquals(8, DxTexelFormat.RGBA16_UINT.bytesPerElement());
		assertEquals(4, DxTexelFormat.RGBA8_UNORM.bytesPerElement());
		assertEquals(1, DxTexelFormat.R8_UINT.bytesPerElement());
	}
}
