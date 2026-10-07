package dev.cubcraftv.directexp.client.api;

import java.nio.ByteBuffer;

/**
 * Per-draw constants for shaders that declare a Vulkan-style
 * {@code layout(push_constant)} block, implemented by DirectExp's render pass
 * backend (obtain it with {@code instanceof} on the {@code RenderPassBackend}
 * behind a {@code RenderPass}; any other backend simply does not implement
 * it).
 *
 * <p>D3D11 has no push constants. DirectExp rewrites such a block into an
 * ordinary {@code std140} constant buffer under the fixed name
 * {@code DxPushConstants} (see {@code DxShaderInterfaceLayout}) and, on every
 * draw, fills it from the bytes given here, exactly like the contents a
 * Vulkan caller would pass to {@code vkCmdPushConstants} at offset 0. The
 * values stay in effect for every later draw of the same render pass until
 * replaced, so a caller that changes them per group of draws (Sodium: once
 * per region, before that region's multi-draw) calls this once per group.
 *
 * <h2>Data layout</h2>
 * Members are packed with std140 rules. For scalars, vectors, {@code mat3} and
 * {@code mat4}, and {@code matCx3}/{@code matCx4} this is identical to the
 * std430 layout a push-constant block normally has (DirectExp refuses shaders
 * whose push-constant block uses anything that would differ). For example
 * Sodium's block {@code { vec3 u_RegionOffset; int u_CurrentTime; uint
 * u_RegionID; }} is 20 bytes: {@code float x, y, z} at offsets 0, 4, 8, the
 * time at 12 and the region id at 16.
 */
public interface DxPushConstants {
	/**
	 * Largest accepted payload. Matches the 128 bytes Vulkan guarantees for
	 * {@code maxPushConstantsSize}, so anything that fits there fits here.
	 */
	int MAX_BYTES = 128;

	/**
	 * Copies {@code [data.position(), data.limit())} (the buffer itself is
	 * neither modified nor retained, so the caller may reuse it immediately)
	 * as the push constants for the following draws of this render pass. An
	 * empty buffer clears them again.
	 *
	 * @throws IllegalArgumentException if {@code data} is null or longer than {@link #MAX_BYTES}
	 */
	void setPushConstants(ByteBuffer data);
}
