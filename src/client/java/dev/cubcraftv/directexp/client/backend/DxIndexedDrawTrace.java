package dev.cubcraftv.directexp.client.backend;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Opt-in validation and trace of indexed draws, for verifying {@code QUADS}
 * draws with a shared 32-bit index buffer (Sodium's chunk draws) on a real
 * D3D11 device: {@code -Ddirectexp.debug.indexdraws=true}.
 *
 * <p>{@code QUADS} reaches DirectX as {@code TRIANGLELIST} (the index buffer
 * already holds the two triangles of each quad, 6 indices per quad), so a
 * correct draw satisfies, per {@code (firstIndex, indexCount, baseVertex)}:
 * <ul>
 *   <li>{@code indexCount} is a positive multiple of 6 (QUADS) or 3,</li>
 *   <li>the index range {@code [firstIndex, firstIndex + indexCount)} lies
 *       inside the index buffer at the bound index width (4 bytes for
 *       {@code IndexType.INT}),</li>
 *   <li>{@code baseVertex} is not negative.</li>
 * </ul>
 * The first {@link #budget()} draws are logged in full, whatever their verdict;
 * a draw that breaks a rule is always logged (once per distinct problem). The
 * checks are pure so they are unit-testable; the logging lives in the render pass.
 */
final class DxIndexedDrawTrace {
	static final String ENABLED_PROPERTY = "directexp.debug.indexdraws";
	static final String DRAWS_PROPERTY = "directexp.debug.indexdraws.draws";

	private static final AtomicInteger REMAINING = new AtomicInteger(Integer.getInteger(DRAWS_PROPERTY, 32));

	private DxIndexedDrawTrace() {
	}

	static boolean enabled() {
		return Boolean.getBoolean(ENABLED_PROPERTY);
	}

	static int budget() {
		return Integer.getInteger(DRAWS_PROPERTY, 32);
	}

	/** True (and consumes budget) while draws are still being logged in full. */
	static boolean shouldLogDraw() {
		return enabled() && REMAINING.getAndDecrement() > 0;
	}

	/** Every rule the draw breaks, empty when it is sound. */
	static List<String> problems(boolean wideIndices, long indexBufferBytes, int firstIndex, int indexCount, int baseVertex, boolean quads) {
		List<String> problems = new ArrayList<>();
		int indexBytes = wideIndices ? 4 : 2;
		if (indexCount <= 0) {
			problems.add("indexCount " + indexCount + " is not positive");
		}
		int perPrimitive = quads ? 6 : 3;
		if (indexCount > 0 && indexCount % perPrimitive != 0) {
			problems.add("indexCount " + indexCount + " is not a multiple of " + perPrimitive + (quads ? " (6 indices per quad)" : " (triangle list)"));
		}
		if (firstIndex < 0) {
			problems.add("firstIndex " + firstIndex + " is negative");
		} else if (indexCount > 0 && ((long) firstIndex + indexCount) * indexBytes > indexBufferBytes) {
			problems.add("index range [" + firstIndex + ", " + ((long) firstIndex + indexCount) + ") at " + indexBytes
				+ " bytes per index ends at byte " + (((long) firstIndex + indexCount) * indexBytes) + ", past the " + indexBufferBytes + "-byte index buffer");
		}
		if (baseVertex < 0) {
			problems.add("baseVertex " + baseVertex + " is negative");
		}
		return problems;
	}

	static String describe(String pipeline, boolean wideIndices, long indexBufferBytes, int firstIndex, int indexCount, int baseVertex, boolean quads) {
		List<String> problems = problems(wideIndices, indexBufferBytes, firstIndex, indexCount, baseVertex, quads);
		return "indexed draw on " + pipeline + ": " + (quads ? "QUADS" : "triangles") + " as TRIANGLELIST, "
			+ (wideIndices ? "32-bit" : "16-bit") + " indices, firstIndex=" + firstIndex + ", indexCount=" + indexCount
			+ " (" + indexCount / (quads ? 6 : 3) + (quads ? " quads" : " triangles") + "), baseVertex=" + baseVertex
			+ ", index buffer " + indexBufferBytes + " B" + (problems.isEmpty() ? " [ok]" : " [PROBLEM: " + String.join("; ", problems) + "]");
	}
}
