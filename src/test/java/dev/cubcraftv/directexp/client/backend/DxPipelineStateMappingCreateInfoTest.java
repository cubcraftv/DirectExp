package dev.cubcraftv.directexp.client.backend;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PolygonMode;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import dev.cubcraftv.directexp.bridge.DxPipelineStateDescriptor;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

final class DxPipelineStateMappingCreateInfoTest {
	private static BackendRenderPipeline.CreateInfo info(
		List<BackendRenderPipeline.CreateInfo.VertexBuffer> buffers,
		List<BackendRenderPipeline.CreateInfo.AttribBinding> attribs,
		List<ColorTargetState> targets
	) {
		return new BackendRenderPipeline.CreateInfo(
			"test", List.of(), buffers, attribs, List.of(), 0,
			DepthStencilState.DEFAULT, PolygonMode.FILL, true, targets, PrimitiveTopology.QUADS
		);
	}

	@Test
	void skippedShaderLocationsAreFilledWithAnUnusedFloatElement() {
		DxPipelineStateDescriptor state = DxPipelineStateMapping.describe(info(
			List.of(new BackendRenderPipeline.CreateInfo.VertexBuffer(0, 20, 0)),
			List.of(
				new BackendRenderPipeline.CreateInfo.AttribBinding(0, 0, 0, GpuFormat.RGB32_FLOAT),
				new BackendRenderPipeline.CreateInfo.AttribBinding(0, 3, 12, GpuFormat.RG32_FLOAT)
			),
			Arrays.asList(new ColorTargetState(BlendFunction.TRANSLUCENT))
		));
		assertEquals(4, state.attributeFormats().length);
		assertEquals(DxPipelineStateMapping.DXGI_FORMAT_R32_FLOAT, state.attributeFormats()[1]);
		assertArrayEquals(new int[] {0, 0, 0, 12}, state.attributeOffsets());
		assertEquals(20, state.vertexStride());
		assertTrue(state.blendEnable());
	}

	@Test
	void secondVertexBufferIsRejected() {
		assertThrows(UnsupportedOperationException.class, () -> DxPipelineStateMapping.describe(info(
			List.of(new BackendRenderPipeline.CreateInfo.VertexBuffer(0, 16, 0), new BackendRenderPipeline.CreateInfo.VertexBuffer(1, 16, 0)),
			List.of(),
			Arrays.asList(ColorTargetState.DEFAULT)
		)));
	}

	@Test
	void multipleColorTargetsAreRejected() {
		assertThrows(UnsupportedOperationException.class, () -> DxPipelineStateMapping.describe(info(
			List.of(), List.of(), Arrays.asList(ColorTargetState.DEFAULT, ColorTargetState.DEFAULT)
		)));
	}

	@Test
	void depthOnlyPipelineWritesNoColor() {
		DxPipelineStateDescriptor state = DxPipelineStateMapping.describe(info(List.of(), List.of(), Arrays.asList((ColorTargetState) null)));
		assertEquals(0, state.colorWriteMask());
	}
}
