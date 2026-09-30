package dev.cubcraftv.directexp.client.shader;

/**
 * A shader pair {@link DxShaderInterfaceLayout} refuses to number: no
 * {@code #version}, a declaration shape the small scanner does not model, or
 * an explicit {@code location}/{@code binding} that would conflict with the
 * assignment. Checked, and caught by {@code DxPipelineCompiler}, which turns
 * it into an invalid pipeline whose reason names this stage. Mirrors the role
 * of Vulkan's {@code ShaderCompileException}.
 */
public final class DxShaderInterfaceException extends Exception {
	public DxShaderInterfaceException(String message) {
		super(message);
	}
}
