package dev.cubcraftv.directexp.bridge;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DxBridgeMultiDrawTest {
	@Test
	void defaultMultiDrawPreservesInterleavedParametersAndSourcePosition() {
		List<String> draws = new ArrayList<>();
		DxBridge bridge = new DxBridge() {
			@Override
			public DxBridgeProbe probe() {
				return null;
			}

			@Override
			public void drawIndexed(int indexCount, int instanceCount, int firstIndex, int baseVertex) {
				draws.add(indexCount + "/" + instanceCount + "/" + firstIndex + "/" + baseVertex);
			}
		};
		IntBuffer parameters = IntBuffer.wrap(new int[] { 90, 99, 98, 5, 12, -3, 20, 6, 7 });
		parameters.position(3);

		bridge.multiDrawIndexed(parameters, 2, 2);

		assertEquals(List.of("12/2/5/-3", "6/2/20/7"), draws);
		assertEquals(3, parameters.position());
	}
}
