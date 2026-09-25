package dev.cubcraftv.directexp.client.shader;

import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import net.minecraft.client.renderer.ShaderDefines;

/**
 * The define-injection step of Milestone 4.2's source/preprocessing stage,
 * pulled out behind a functional interface instead of calling
 * {@link GlslPreprocessor#injectDefines} directly from
 * {@link DxShaderSourceStage}. Two reasons:
 * <ol>
 *   <li>It documents, at the type level, exactly which real Mojang call
 *   this stage uses to match the modern Vulkan path -- see
 *   {@code VulkanDevice.compileShader}, which calls
 *   {@code GlslPreprocessor.injectDefines(source, key.defines)} directly,
 *   confirmed against the decompiled
 *   {@code com.mojang.blaze3d.vulkan.VulkanDevice} source this milestone
 *   was implemented against (docs/research/2026-09-25-directexp-milestone4-shader-toolchain-research.md
 *   section 4 flagged this as "still genuinely unknown" before that
 *   source was available).</li>
 *   <li>{@link ShaderDefines} is a Mojang value type this project has no
 *   decompiled source for and no reason to construct by hand in a test.
 *   Routing through this interface lets {@link DxShaderSourceStage}'s
 *   core logic be unit-tested with a fake injector, without a test ever
 *   needing to build a real {@code ShaderDefines} instance or call the
 *   real static method.</li>
 * </ol>
 */
@FunctionalInterface
public interface DxGlslDefinesInjector {
	String injectDefines(String source, ShaderDefines defines);

	static DxGlslDefinesInjector mojangDefault() {
		return GlslPreprocessor::injectDefines;
	}
}
