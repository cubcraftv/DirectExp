# DirectExp D3D11 Feature-Level / Shader-Route Research Notes

Project-local notes, analogous in role to MetalExp's
`docs/research/2026-04-09-minecraft-26.2-vulkan-metal-research_cn.md`. This
file is a placeholder for findings that should be filled in during Milestone
2, once real device/driver testing is possible on Windows hardware. It is
not a substitute for hands-on verification.

## Open research questions

1. **Feature level 10.1 real-world coverage.** Confirm which currently
   relevant GPU generations report FL 10.1 vs. 11.0/11.1 vs. failing
   entirely, and whether `D3D11CreateDevice` with a hardware driver type
   ever silently falls back to WARP on some driver configurations (it can,
   under specific conditions -- verify whether that needs explicit guarding
   against for this project's floor policy).
2. **Shader Model ceiling at FL 10.1.** Confirm SM 5.0 (`vs_5_0`/`ps_5_0`)
   is fully available at FL 10.1, and enumerate which SM 5.0 features are
   not guaranteed at 10.1 vs. 11.0 (e.g. certain compute-shader-adjacent
   capabilities), since Blaze3D's Vulkan-era pipelines may assume
   capabilities beyond the 10.1 floor in places DirectExp will need to work
   around or reject gracefully.
3. **SPIRV-Cross HLSL backend fidelity.** Validate SPIRV-Cross's SPIR-V ->
   HLSL output against Mojang's actual current-line shaders (once available
   from the decompiled/source reference used for the Vulkan path), the same
   way MetalExp's research doc validated the MSL translation path.
4. **DXGI swap chain model.** Decide between the legacy bit-blit swap effect
   and `DXGI_SWAP_EFFECT_FLIP_DISCARD` / `FLIP_SEQUENTIAL`; flip-model swap
   chains are strongly preferred on modern Windows for both performance and
   because the legacy blt model is on a slow deprecation path, but flip
   model has its own constraints (buffer count, format restrictions) worth
   confirming against the feature-level-10.1 floor.
5. **Resize handling.** `IDXGISwapChain::ResizeBuffers` requires releasing
   every view/resource derived from the swap chain's back buffer first.
   Confirm the exact object lifetime discipline needed so `DxSurfaceBackend`
   does not leak or crash on resize -- this is one of the design spec's
   named medium risks.
6. **Debug layer availability.** Confirm whether `D3D11_CREATE_DEVICE_DEBUG`
   requires an optional Windows SDK component (the "Graphics Tools" optional
   feature) that may not be present on a given dev machine, and document the
   install step if so.

## Why this file starts mostly empty

Unlike MetalExp's research doc (written after real device testing was
possible on the author's Mac), this scaffold was authored in a non-Windows
sandbox. Filling this in with real findings should be one of the first
things a Windows-based session does, before writing much more native code --
several of the open questions above (especially #1, #4, #5) can change how
the native bridge should be structured.
