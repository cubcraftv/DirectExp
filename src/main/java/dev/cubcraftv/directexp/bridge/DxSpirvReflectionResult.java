package dev.cubcraftv.directexp.bridge;

import java.util.List;
import java.util.Objects;

/**
 * Result of one {@link DxBridge#reflectSpirv} call: either the reflected
 * resources ({@code status == READY}), a structured SPIRV-Cross
 * reflection failure ({@code status == REFLECTION_ERROR}, see
 * {@link #detail}), or a bridge-level failure that never reached
 * SPIRV-Cross at all (library not loaded, native entrypoint missing,
 * etc).
 *
 * <p>Follows the same record+status pair shape as
 * {@link DxIrCompilationResult}.
 */
public record DxSpirvReflectionResult(
	DxSpirvReflectionStatus status,
	List<DxReflectedResource> resources,
	String detail
) {
	public boolean isReady() {
		return this.status == DxSpirvReflectionStatus.READY && this.resources != null;
	}

	/**
	 * True only for {@code REFLECTION_ERROR}: SPIRV-Cross was actually
	 * reached and failed on this specific SPIR-V module, as opposed to
	 * the bridge itself being unavailable ({@link #status} one of the
	 * other non-{@code READY} values).
	 */
	public boolean isReflectionError() {
		return this.status == DxSpirvReflectionStatus.REFLECTION_ERROR;
	}

	public static DxSpirvReflectionResult ready(List<DxReflectedResource> resources) {
		Objects.requireNonNull(resources, "resources");
		return new DxSpirvReflectionResult(DxSpirvReflectionStatus.READY, List.copyOf(resources), null);
	}

	public static DxSpirvReflectionResult reflectionError(String detail) {
		return new DxSpirvReflectionResult(DxSpirvReflectionStatus.REFLECTION_ERROR, null, detail);
	}

	public static DxSpirvReflectionResult bridgeUnavailable(DxSpirvReflectionStatus status, String detail) {
		if (status == DxSpirvReflectionStatus.READY || status == DxSpirvReflectionStatus.REFLECTION_ERROR) {
			throw new IllegalArgumentException("bridgeUnavailable() cannot be used with READY or REFLECTION_ERROR; use ready()/reflectionError() instead.");
		}
		return new DxSpirvReflectionResult(status, null, detail);
	}
}
