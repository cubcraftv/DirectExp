package dev.cubcraftv.directexp.client.backend;

import dev.cubcraftv.directexp.bridge.DxBridge;
import dev.cubcraftv.directexp.bridge.DxBridgeProbe;
import dev.cubcraftv.directexp.bridge.DxResourceKind;
import dev.cubcraftv.directexp.client.shader.DxResourceLayout;
import dev.cubcraftv.directexp.client.shader.DxResourceLayoutEntry;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DxResourceBinderTest {
	private final List<String> calls = new ArrayList<>();
	private final Set<String> reports = new LinkedHashSet<>();
	private long nextHandle = 500L;

	private final DxBridge bridge = new DxBridge() {
		@Override
		public DxBridgeProbe probe() {
			return null;
		}

		@Override
		public long createBuffer(int bindMask, int byteWidth) {
			return DxResourceBinderTest.this.nextHandle++;
		}

		@Override
		public void updateBuffer(long handle, ByteBuffer mirror, int offset, int length) {
		}

		@Override
		public void releaseBuffer(long handle) {
		}

		@Override
		public void bindConstantBuffer(int slot, long handle, int stageMask) {
			DxResourceBinderTest.this.calls.add("bindCB(" + slot + "," + stageMask + ")");
		}

		@Override
		public void bindShaderResource(int slot, long handle, int stageMask) {
			DxResourceBinderTest.this.calls.add("bindSRV(" + slot + "," + handle + "," + stageMask + ")");
		}

		@Override
		public void bindSampler(int slot, long handle, int stageMask) {
			DxResourceBinderTest.this.calls.add("bindSampler(" + slot + "," + handle + "," + stageMask + ")");
		}
	};

	private final DxDrawDiagnostics diagnostics = (key, message) -> this.reports.add(key);
	private final DxDrawScratch scratch = new DxDrawScratch(this.bridge, this.diagnostics);
	private final DxResourceBinder binder = new DxResourceBinder(this.bridge, this.scratch, this.diagnostics);

	private final DxResourceLayout layout = new DxResourceLayout("test", List.of(
		new DxResourceLayoutEntry("Projection", DxResourceKind.CONSTANT_BUFFER, 1, 64, true, false),
		new DxResourceLayoutEntry("Missing", DxResourceKind.CONSTANT_BUFFER, 2, 16, false, true),
		new DxResourceLayoutEntry("Sampler0", DxResourceKind.SAMPLED_TEXTURE, 0, 0, false, true),
		new DxResourceLayoutEntry("Sampler0", DxResourceKind.SAMPLER, 0, 0, false, true),
		new DxResourceLayoutEntry("NoTex", DxResourceKind.SAMPLED_TEXTURE, 1, 0, true, true)
	));

	private final DxResourceBinder.Source source = new DxResourceBinder.Source() {
		@Override
		public ByteBuffer uniformBytes(String name) {
			return name.equals("Projection") ? ByteBuffer.allocateDirect(20).order(ByteOrder.nativeOrder()) : null;
		}

		@Override
		public long textureViewHandle(String name) {
			return name.equals("Sampler0") ? 9001L : 0L;
		}

		@Override
		public long samplerHandle(String name) {
			return name.equals("Sampler0") ? 9002L : 0L;
		}
	};

	@Test
	void stageMaskFollowsTheStagesAResourceIsUsedIn() {
		assertEquals(DxBridge.STAGE_VERTEX, DxResourceBinder.stageMask(new DxResourceLayoutEntry("a", DxResourceKind.CONSTANT_BUFFER, 0, 0, true, false)));
		assertEquals(DxBridge.STAGE_FRAGMENT, DxResourceBinder.stageMask(new DxResourceLayoutEntry("a", DxResourceKind.CONSTANT_BUFFER, 0, 0, false, true)));
		assertEquals(DxBridge.STAGE_VERTEX | DxBridge.STAGE_FRAGMENT, DxResourceBinder.stageMask(new DxResourceLayoutEntry("a", DxResourceKind.CONSTANT_BUFFER, 0, 0, true, true)));
	}

	@Test
	void constantBuffersBindToTheirLayoutSlotAndAnUnsetUniformIsReported() {
		boolean complete = this.binder.bindConstants(this.layout, this.source);

		assertFalse(complete, "a never-set uniform means the draw must be skipped");
		assertTrue(this.reports.contains("uniform-unset:Missing"));
		assertTrue(this.calls.contains("bindCB(1," + DxBridge.STAGE_VERTEX + ")"));
		assertEquals(1, this.calls.size(), "the unset uniform must not be bound");
	}

	@Test
	void texturesAndSamplersBindToTheirSlotsAndAMissingTextureUnbindsItsSlot() {
		this.binder.bindTextures(this.layout, this.source, true);

		assertTrue(this.calls.contains("bindSRV(0,9001," + DxBridge.STAGE_FRAGMENT + ")"));
		assertTrue(this.calls.contains("bindSampler(0,9002," + DxBridge.STAGE_FRAGMENT + ")"));
		assertTrue(this.calls.contains("bindSRV(1,0," + (DxBridge.STAGE_VERTEX | DxBridge.STAGE_FRAGMENT) + ")"));
		assertTrue(this.reports.contains("texture-missing:test:NoTex"));
	}

	@Test
	void texelBufferShaderResourceViewsBindBeforeTextureFallbacks() {
		DxResourceLayout layout = new DxResourceLayout("buffer-srv", List.of(
			new DxResourceLayoutEntry("SectionTimeInfo", DxResourceKind.SAMPLED_TEXTURE, 3, 0, false, true)
		));
		DxResourceBinder.Source source = new DxResourceBinder.Source() {
			@Override
			public ByteBuffer uniformBytes(String name) {
				return null;
			}

			@Override
			public long textureViewHandle(String name) {
				return 0L;
			}

			@Override
			public long bufferShaderResourceHandle(String name) {
				return name.equals("SectionTimeInfo") ? 7007L : 0L;
			}

			@Override
			public long samplerHandle(String name) {
				return 0L;
			}
		};

		this.binder.bindTextures(layout, source, true);

		assertTrue(this.calls.contains("bindSRV(3,7007," + DxBridge.STAGE_FRAGMENT + ")"));
		assertFalse(this.reports.contains("texture-missing:buffer-srv:SectionTimeInfo"));
	}

	@Test
	void missingTextureDiagnosticsAreReportedPerPipeline() {
		DxResourceLayout firstPipeline = new DxResourceLayout("first", List.of(
			new DxResourceLayoutEntry("Sampler0", DxResourceKind.SAMPLED_TEXTURE, 0, 0, false, true)
		));
		DxResourceLayout secondPipeline = new DxResourceLayout("second", List.of(
			new DxResourceLayoutEntry("Sampler0", DxResourceKind.SAMPLED_TEXTURE, 0, 0, false, true)
		));
		DxResourceBinder.Source missingSource = new DxResourceBinder.Source() {
			@Override
			public ByteBuffer uniformBytes(String name) {
				return null;
			}

			@Override
			public long textureViewHandle(String name) {
				return 0L;
			}

			@Override
			public long samplerHandle(String name) {
				return 0L;
			}
		};

		this.binder.bindTextures(firstPipeline, missingSource, true);
		this.binder.bindTextures(secondPipeline, missingSource, true);

		assertTrue(this.reports.contains("texture-missing:first:Sampler0"));
		assertTrue(this.reports.contains("texture-missing:second:Sampler0"));
	}
}
