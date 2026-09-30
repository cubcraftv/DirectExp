package dev.cubcraftv.directexp.client.backend;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/**
 * Draw-time expansion of {@code TRIANGLE_FAN} into triangle-list indices.
 * D3D11 has no fan topology, so a fan draw is turned into a
 * {@code TRIANGLELIST} draw of {@code (v0, vi, vi+1)} triangles, which
 * preserves the fan's winding (Milestone 4.6 source of truth, 11.3).
 *
 * <p>Produced indices are <em>relative</em>: they index the vertices the fan
 * addresses, exactly like the source indices would, so the caller keeps
 * passing the original base vertex to {@code DrawIndexedInstanced}. Instance
 * count is untouched too; only the index count changes.
 *
 * <p>Owns one reusable scratch array, grown on demand, so steady-state
 * submission does not allocate. Not thread-safe (render thread only).
 */
final class DxTriangleFanExpander {
	/** Index count for a fan of {@code sourceCount} vertices/indices (0 when fewer than 3). */
	static int expandedIndexCount(int sourceCount) {
		return sourceCount < 3 ? 0 : Math.multiplyExact(sourceCount - 2, 3);
	}

	private int[] scratch = new int[0];
	private int count;

	/**
	 * Non-indexed fan: source sequence is {@code firstVertex .. firstVertex+vertexCount-1}.
	 * The result holds absolute vertex numbers; draw it indexed with base vertex 0.
	 */
	void expandNonIndexed(int firstVertex, int vertexCount) {
		if (firstVertex < 0 || vertexCount < 0) {
			throw new IllegalArgumentException("firstVertex and vertexCount must be non-negative.");
		}
		int total = expandedIndexCount(vertexCount);
		ensureCapacity(total);
		int out = 0;
		for (int i = 1; i + 1 < vertexCount; i++) {
			this.scratch[out++] = firstVertex;
			this.scratch[out++] = firstVertex + i;
			this.scratch[out++] = firstVertex + i + 1;
		}
		this.count = total;
	}

	/**
	 * Indexed fan: reads {@code indexCount} indices starting at element
	 * {@code firstIndex} of {@code source} (little-endian, {@code indexBytes}
	 * = 2 or 4 each; absolute position, buffer position/limit untouched) and
	 * emits the fan as triangle-list indices with the same values, i.e. still
	 * relative to the caller's base vertex.
	 */
	void expandIndexed(ByteBuffer source, int indexBytes, int firstIndex, int indexCount) {
		Objects.requireNonNull(source, "source");
		if (indexBytes != 2 && indexBytes != 4) {
			throw new IllegalArgumentException("indexBytes must be 2 or 4.");
		}
		if (firstIndex < 0 || indexCount < 0) {
			throw new IllegalArgumentException("firstIndex and indexCount must be non-negative.");
		}
		long endByte = ((long) firstIndex + indexCount) * indexBytes;
		if (endByte > source.limit()) {
			throw new IllegalArgumentException("Index range exceeds the index buffer.");
		}

		ByteBuffer view = source.duplicate().order(ByteOrder.LITTLE_ENDIAN);
		int total = expandedIndexCount(indexCount);
		ensureCapacity(total);
		int out = 0;
		if (indexCount >= 3) {
			int hub = read(view, indexBytes, firstIndex);
			int previous = read(view, indexBytes, firstIndex + 1);
			for (int i = 2; i < indexCount; i++) {
				int next = read(view, indexBytes, firstIndex + i);
				this.scratch[out++] = hub;
				this.scratch[out++] = previous;
				this.scratch[out++] = next;
				previous = next;
			}
		}
		this.count = total;
	}

	/** Number of valid entries in {@link #indices()} from the last expansion. */
	int count() {
		return this.count;
	}

	/** The scratch array; only the first {@link #count()} entries are valid, and it is overwritten by the next expansion. */
	int[] indices() {
		return this.scratch;
	}

	private static int read(ByteBuffer view, int indexBytes, int element) {
		int position = element * indexBytes;
		return indexBytes == 2 ? view.getShort(position) & 0xFFFF : view.getInt(position);
	}

	private void ensureCapacity(int needed) {
		if (this.scratch.length < needed) {
			this.scratch = new int[Math.max(needed, this.scratch.length * 2)];
		}
	}
}
