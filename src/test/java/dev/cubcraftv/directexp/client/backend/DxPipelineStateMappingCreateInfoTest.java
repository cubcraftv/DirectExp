package dev.cubcraftv.directexp.client.backend;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
		assertArrayEquals(new int[] {0, 0, 0, 0}, state.attributeSlots());
		assertArrayEquals(new int[] {0, 0, 0, 0}, state.attributeStepRates());
		assertEquals(20, state.vertexStrideOf(0));
		assertEquals(0, state.vertexStrideOf(1));
		assertEquals(1, state.colorTargetCount());
		assertEquals(1, state.targetBlendEnable()[0]);
		assertEquals(0xF, state.targetWriteMask()[0]);
	}

	@Test
	void secondVertexBufferSlotIsAccepted() {
		DxPipelineStateDescriptor state = DxPipelineStateMapping.describe(info(
			List.of(
				new BackendRenderPipeline.CreateInfo.VertexBuffer(0, 16, 0),
				new BackendRenderPipeline.CreateInfo.VertexBuffer(1, 32, 1)
			),
			List.of(
				new BackendRenderPipeline.CreateInfo.AttribBinding(0, 0, 0, GpuFormat.RGB32_FLOAT),
				new BackendRenderPipeline.CreateInfo.AttribBinding(1, 2, 12, GpuFormat.RG32_FLOAT)
			),
			Arrays.asList(ColorTargetState.DEFAULT)
		));
		assertEquals(3, state.attributeFormats().length); // location 1 is skipped filler
		assertArrayEquals(new int[] {0, 0, 1}, state.attributeSlots());
		assertArrayEquals(new int[] {0, 0, 1}, state.attributeStepRates());
		assertArrayEquals(new int[] {0, 0, 12}, state.attributeOffsets());
		assertEquals(16, state.vertexStrideOf(0));
		assertEquals(32, state.vertexStrideOf(1));
		assertTrue(state.slotIsInstanced(1));
		assertFalse(state.slotIsInstanced(0));
	}

	@Test
	void attributesWithoutTheirOwnSlotsBindingAreRejected() {
		assertThrows(UnsupportedOperationException.class, () -> DxPipelineStateMapping.describe(info(
			List.of(new BackendRenderPipeline.CreateInfo.VertexBuffer(0, 16, 0)),
			List.of(new BackendRenderPipeline.CreateInfo.AttribBinding(1, 0, 0, GpuFormat.RGB32_FLOAT)),
			Arrays.asList(ColorTargetState.DEFAULT)
		)));
	}

	@Test
	void twoColorTargetsKeepTheirOwnBlendStates() {
		// Like the OIT transmittance pipelines: target 0 blends translucent,
		// target 1 writes an unblended coefficient pair (DEFAULT) next to it.
		DxPipelineStateDescriptor state = DxPipelineStateMapping.describe(info(
			List.of(),
			List.of(),
			Arrays.asList(new ColorTargetState(BlendFunction.TRANSLUCENT), ColorTargetState.DEFAULT)
		));
		assertEquals(2, state.colorTargetCount());
		assertArrayEquals(new int[] {1, 0}, state.targetBlendEnable());
		assertArrayEquals(new int[] {0xF, 0xF}, state.targetWriteMask());
	}

	@Test
	void moreColorTargetsThanD3d11SupportsAreRejected() {
		ColorTargetState[] targets = new ColorTargetState[DxPipelineStateDescriptor.MAX_COLOR_TARGETS + 1];
		Arrays.fill(targets, ColorTargetState.DEFAULT);
		assertThrows(UnsupportedOperationException.class, () -> DxPipelineStateMapping.describe(info(
			List.of(), List.of(), Arrays.asList(targets)
		)));
	}

	@Test
	void depthOnlyPipelineWritesNoColor() {
		DxPipelineStateDescriptor state = DxPipelineStateMapping.describe(info(List.of(), List.of(), Arrays.asList((ColorTargetState) null)));
		assertEquals(1, state.colorTargetCount());
		assertArrayEquals(new int[] {0}, state.targetBlendEnable());
		assertArrayEquals(new int[] {0}, state.targetWriteMask());
	}
}
