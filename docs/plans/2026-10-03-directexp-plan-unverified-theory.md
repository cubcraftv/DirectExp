# DirectExp Planning: Unverified Theory

This file tracks hypotheses that are plausible, useful, and worth investigating, but are not yet confirmed by runtime evidence. They should not be treated as conclusions.

## 1. Black-button cause is still a hypothesis, not a diagnosis

The latest user-reported runtime result is that the main-menu button fill appears black and its expected texture is absent. This confirms the symptom, but not the root cause.

- The RenderDoc capture shows the button source texture binding at the expected sampler slot.
- The GUI atlas is being used as the render target and the relevant draw calls are present.
- The remaining issue may be in pipeline state, color conversion, custom alpha/blend logic, or a later render-target / source-view mismatch.

What is still unverified:

- whether the sampled source pixel data is actually the expected button texture,
- whether the button path uses the wrong clear or blend state,
- whether a native SRV or RTV is valid at the moment the draw occurs,
- whether the current issue is in the copy stage or in later atlas composition.

The same report says mob textures look correct, block textures are stretched
(grass is an example), and item textures are broken. These are additional
observations, not evidence that the button has the same cause. There is no
matching screenshot or runtime log attached to this update, so exact draw and
resource correlation remains outstanding.

## 2. Clear-color mismatch may be a channel-order mismatch rather than a draw bug

The red-clear issue is consistent with a channel-order bug, but the exact direction of that bug is not yet proven.

- Render-pass clear and CPU texture fill are separate code paths.
- A red/blue swap can happen in either one.
- The evidence is strong enough to keep the issue active, but not strong enough to say which code path is wrong without an explicit validation run.

## 3. Panorama support likely needs an intentional cubemap path

The cubemap issue is not a vague “maybe”; it is a clear structural gap, but the exact Java/native API shape still needs final runtime confirmation.

- The current path rejects non-1 layer counts and uses `TEXTURE2D` SRVs.
- The Vulkan reference strongly suggests a six-face array/cubemap contract.
- The unresolved question is whether the DirectX implementation should expose cubemap support through the existing `DxTexture` metadata or via a more explicit texture-view parameter path.

## 4. Some shader failures are known, but their causal chain needs runtime confirmation

The plan already records several confirmed shader-related defects, including:

- `TRIANGLE_FAN` support gaps,
- `gl_PointSize` translation problems,
- the `#line`/block-comment tokenizer issue,
- and a likely `logicalSize` / `capacity()` mismatch in the buffer path.

These are not “theories” in the same sense as the menu-button issue; they are known defects. They are still not treated as final root causes for any one runtime symptom until a specific failing draw or shader path is tied back to them with fresh evidence.

## Working rule

Until an item is proven by a fresh run, screenshot, or direct trace, treat it as a theory and keep it here. Once it is observed and repeated under controlled conditions, move it to the verified-not-prioritized or progress ledger instead.
