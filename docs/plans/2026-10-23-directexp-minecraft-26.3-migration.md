# Minecraft 26.2 -> 26.3 migration

Status: **ported against the real `com.mojang.renderpearl` sources; client
compilation, JVM tests, and a Windows smoke launch pass through SDL window
creation and first native pipeline assembly. Full gameplay/rendering is not
validated.**

## Done

- `minecraft_version=26.3`, Fabric API `0.161.0+26.3`; metadata and docs updated.
- All `com.mojang.blaze3d` GPU imports moved to `com.mojang.renderpearl.*`
  (including `TransientMemory` -> `api.buffers`); mixin descriptor for
  `getBackendsToTry()` updated.
- `DxBackend`: SDL instead of GLFW. Minecraft calls `createDevice` before
  creating a window, so DirectExp probes D3D11 there and resolves
  `SDL.window.win32.hwnd` / bootstraps the DXGI surface later when
  `GpuDeviceBackend.createSurface` receives the live SDL handle.
- `DxDeviceBackend`: new `GpuDeviceBackend` contract (`createSurface(handle,
  isIconified)`, single `createTexture`, `createTextureView(tex, mip, n)`,
  `compilePipeline(CreateInfo)`, `getTimestampCalibrationOffset`). New
  `DeviceFeatures`/`DeviceLimits`/`HintsAndWorkarounds` shapes; wireframe fill on.
- Resources: `DxBuffer/DxTexture/DxTextureView` extend the `Base*` classes,
  `DxSampler` implements `GpuSampler` (+`isClosed`).
- Shader path: the frontend now compiles GLSL with Shaderc and assigns bindings,
  so the mod's GLSL front half (`DxShaderSourceStage`, `DxShaderInterfaceLayout`,
  `DxPointSizeRewriter`, point-sprite geometry shader, `DxIrCompilationStage`,
  `DxSpirvCache`, ...) and its tests were deleted. `DxPipelineCompiler` starts
  from the SPIR-V in `CreateInfo` (copied on the compile thread, native work in
  `finishCompile`). `DxRenderPipeline` is now a `BackendRenderPipeline`; failures
  return `null`.
- `DxPipelineStateMapping.describe(CreateInfo)`: attribute index == shader
  location, gaps filled with an unused R32_FLOAT element; one color target only.
  New test `DxPipelineStateMappingCreateInfoTest`.
- `DxRenderPassBackend`: `setPipeline(BackendRenderPipeline)`, index-based
  `setUniform(int, Object)` (texture = `TextureViewAndSampler`, buffer =
  `GpuBufferSlice`), `pushConstants`, no `drawMultipleIndexed`/`bindTexture`.
- Native: `point_size_compat = true` (gl_PointSize ignored, debug points are 1 px);
  push-constant blocks are reflected as constant buffer `DxPushConstants` and bound
  through SPIRV-Cross' push-constant binding pair.

## Unverified / needs a real build

1. Native C++ edits (`ResourceBindingPushConstant*` names, `point_size_compat`)
   were not compiled.
2. A 26.3 Windows launch reached backend negotiation, confirming
   `MinecraftBackendNegotiationMixin` targets the active
   `PreferredGraphicsApi.getBackendsToTry()` path. That launch exposed the
   device-before-window lifecycle. After deferring HWND resolution and surface
   bootstrap to `createSurface`, a smoke launch created the SDL window and
   assembled the first native pipeline.
3. Two early buffer/sampler allocations fell back to CPU storage before the
   surface created the persistent native device. These allocations and full
   gameplay/rendering still need validation; LWJGL HWND resolution and DXGI
   swap-chain creation succeeded far enough to assemble a native pipeline.
4. Multi-color-target (OIT) and instanced/multi-slot vertex buffers are rejected
   by design; `maxColorAttachments` stays 1 so vanilla should not enable OIT.
5. Sodium's 26.3 build and its use of `pushConstants` are untested.

## Next steps

Run `./gradlew build` on Windows to verify the native DLL, then launch the game
to validate SDL HWND resolution, DXGI swap-chain creation, and pipeline logs.
