package dev.cubcraftv.directexp.bridge;

/**
 * Which shader stage a {@link DxBridge#compileGlslToSpirv} call is for.
 *
 * <p>Deliberately not Blaze3D's {@code ShaderType} (vertex/fragment):
 * {@code ShaderType} is a client-only Mojang type, but {@link DxBridge}
 * lives in this project's {@code main} source set alongside the rest of
 * the native bridge surface (probe, surface, texture), which has no
 * client-only dependency today. Callers in {@code client} (Milestone 4.2's
 * {@code DxPreprocessedShaderSource#type}) map their own {@code ShaderType}
 * to this enum at the call site instead.
 */
public enum DxShaderStageKind {
	VERTEX,
	FRAGMENT,
	/**
	 * Only valid for {@link DxBridge#compileHlsl}: the generated point-sprite
	 * geometry shader (see {@code DxPointSpriteGeometryShader}). Not a
	 * {@code shaderc} stage, so {@link DxBridge#compileGlslToSpirv} must not be
	 * given it.
	 */
	GEOMETRY;

	/**
	 * Encodes this stage for the {@code shaderStageKind} native bridge
	 * parameter. Keep in sync with directexp_bridge_shader.cpp's
	 * {@code ToShadercShaderKind}.
	 */
	public int nativeShaderKind() {
		return switch (this) {
			case VERTEX -> 0;
			case FRAGMENT -> 1;
			case GEOMETRY -> 2;
		};
	}
}
