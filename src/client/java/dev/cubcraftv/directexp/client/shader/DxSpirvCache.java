package dev.cubcraftv.directexp.client.shader;

import com.mojang.blaze3d.shaders.ShaderType;
import net.minecraft.resources.Identifier;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caches successfully-compiled {@link DxSpirvModule}s so {@link DxIrCompilationStage}
 * doesn't ask {@code shaderc} to recompile the same shader on every call.
 * <p>
 * Milestone 4.3's checklist calls this "a stepping stone toward the
 * pipeline cache keys 4.6 needs -- doesn't need to be the final cache
 * design yet, just needs to exist so 4.4/4.5 aren't recompiling from
 * scratch on every call." Accordingly this is deliberately simple: an
 * unbounded, never-invalidated map keyed on shader identity plus the
 * exact preprocessed source text. That last part matters -- keying on
 * {@code (id, type)} alone would return a stale module if the same
 * shader identity is later preprocessed with different
 * {@code ShaderDefines} (a different pipeline using the same shader
 * source with different defines, matching how the existing Vulkan path's
 * own {@code ShaderCompilationKey} includes defines, not just id/type).
 * Milestone 4.6's real pipeline cache key design is expected to replace
 * this, not extend it.
 * <p>
 * Only successful compiles are ever {@link #put} here (see
 * {@link DxIrCompilationStage}) -- a {@code shaderc} compile error is
 * cheap to re-report and re-deriving it fresh each time means this cache
 * never has to reason about invalidating a cached failure.
 */
public final class DxSpirvCache {
	private final ConcurrentHashMap<CacheKey, DxSpirvModule> modules = new ConcurrentHashMap<>();

	public DxSpirvModule get(Identifier id, ShaderType type, String preprocessedSource) {
		return this.modules.get(new CacheKey(id, type, preprocessedSource));
	}

	public void put(Identifier id, ShaderType type, String preprocessedSource, DxSpirvModule module) {
		Objects.requireNonNull(module, "module");
		this.modules.put(new CacheKey(id, type, preprocessedSource), module);
	}

	public int size() {
		return this.modules.size();
	}

	public void clear() {
		this.modules.clear();
	}

	private record CacheKey(Identifier id, ShaderType type, String preprocessedSource) {
	}
}
