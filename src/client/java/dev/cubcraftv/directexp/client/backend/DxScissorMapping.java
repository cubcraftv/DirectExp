package dev.cubcraftv.directexp.client.backend;

/**
 * Blaze3D scissor rectangle to D3D11 scissor rectangle.
 *
 * <p>Blaze3D's rectangle already uses a top-left origin: the decompiled
 * {@code VulkanRenderPass.enableScissor} hands {@code (x, y, width, height)}
 * straight to {@code vkCmdSetScissor}, and Vulkan's framebuffer origin is
 * top-left like D3D11's. (An earlier version of this class assumed GL-style
 * bottom-left and flipped Y; that was wrong.) The only work left is clamping
 * to the target so D3D never sees a rectangle outside it.
 */
final class DxScissorMapping {
	private DxScissorMapping() {
	}

	/** {x, y, width, height} in D3D pixel coordinates (top-left origin), clamped to the target. */
	static int[] toTopLeftRect(int x, int y, int width, int height, int targetWidth, int targetHeight) {
		long left = Math.max(0L, x);
		long top = Math.max(0L, y);
		long right = Math.min((long) targetWidth, (long) x + Math.max(0, width));
		long bottom = Math.min((long) targetHeight, (long) y + Math.max(0, height));
		return new int[] { (int) left, (int) top, (int) Math.max(0L, right - left), (int) Math.max(0L, bottom - top) };
	}

	/** The full target rectangle, i.e. "scissor disabled". */
	static int[] fullRect(int targetWidth, int targetHeight) {
		return new int[] { 0, 0, targetWidth, targetHeight };
	}
}
