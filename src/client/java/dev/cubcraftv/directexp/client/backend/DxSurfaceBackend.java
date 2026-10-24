package dev.cubcraftv.directexp.client.backend;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.backend.api.GpuSurfaceBackend;
import com.mojang.renderpearl.api.device.SurfaceException;
import com.mojang.renderpearl.api.device.GpuSurface.PresentMode;
import com.mojang.renderpearl.api.textures.GpuTextureView;

import java.nio.ByteBuffer;
import java.util.Objects;

final class DxSurfaceBackend implements GpuSurfaceBackend {
	private final DxSurfaceLease surfaceLease;
	private GpuSurface.Configuration configuration;
	private boolean acquired;
	private boolean closed;

	DxSurfaceBackend(DxSurfaceLease surfaceLease) {
		this.surfaceLease = Objects.requireNonNull(surfaceLease, "surfaceLease");
	}

	DxSurfaceDescriptor descriptor() {
		return this.surfaceLease.descriptor();
	}

	@Override
	public void configure(GpuSurface.Configuration configuration) throws SurfaceException {
		if (this.closed || this.surfaceLease.isClosed()) {
			throw new SurfaceException("DirectX surface backend is closed.");
		}

		try {
			boolean vsync = configuration.presentMode() == PresentMode.FIFO || configuration.presentMode() == PresentMode.FIFO_RELAXED;
			this.surfaceLease.configure(configuration.width(), configuration.height(), vsync);
		} catch (IllegalStateException | IllegalArgumentException | UnsupportedOperationException error) {
			throw new SurfaceException(error.getMessage());
		}

		this.configuration = configuration;
	}

	@Override
	public boolean isSuboptimal() {
		return false;
	}

	@Override
	public void acquireNextTexture() throws SurfaceException {
		if (this.closed || this.surfaceLease.isClosed()) {
			throw new SurfaceException("DirectX surface backend is closed.");
		}

		try {
			this.surfaceLease.acquire();
			this.acquired = true;
		} catch (IllegalStateException | IllegalArgumentException | UnsupportedOperationException error) {
			this.acquired = false;
			throw new SurfaceException(error.getMessage());
		}
	}

	@Override
	public void blitFromTexture(CommandEncoderBackend commandEncoderBackend, GpuTextureView gpuTextureView) {
		if (this.closed || !this.acquired || this.surfaceLease.isClosed()) {
			throw new IllegalStateException("DirectX surface backend must be acquired before blitting.");
		}

		if (commandEncoderBackend == null) {
			throw new IllegalArgumentException("DirectX surface blit requires a command encoder.");
		}

		if (gpuTextureView == null || gpuTextureView.isClosed()) {
			throw new IllegalArgumentException("DirectX surface blit requires a live color texture view.");
		}

		if (!(gpuTextureView.texture() instanceof DxTexture dxTexture)) {
			throw new IllegalArgumentException("DirectX surface blit requires a DirectX texture.");
		}

		if (dxTexture.getFormat() != GpuFormat.RGBA8_UNORM) {
			throw new IllegalArgumentException("DirectX surface blit currently only supports RGBA8_UNORM textures.");
		}

		if (dxTexture.hasNativeTextureHandle()) {
			if (commandEncoderBackend instanceof DxCommandEncoderBackend dxCommandEncoderBackend) {
				this.surfaceLease.blitTexture(dxCommandEncoderBackend.commandContextHandle(), dxTexture.nativeTextureHandle());
			} else {
				this.surfaceLease.blitTexture(dxTexture.nativeTextureHandle());
			}
			return;
		}

		int mipLevel = gpuTextureView.baseMipLevel();
		ByteBuffer pixels = dxTexture.readRegion(
			mipLevel,
			0,
			0,
			gpuTextureView.getWidth(0),
			gpuTextureView.getHeight(0)
		);
		this.surfaceLease.blitRgba8(pixels, gpuTextureView.getWidth(0), gpuTextureView.getHeight(0));
	}

	@Override
	public void present() {
		if (this.closed || !this.acquired || this.surfaceLease.isClosed()) {
			this.acquired = false;
			return;
		}

		try {
			this.surfaceLease.present();
		} catch (IllegalStateException | IllegalArgumentException | UnsupportedOperationException error) {
			// Presentation is best-effort during bootstrap; a closed/invalid
			// lease or an unimplemented native path should not crash
			// shutdown or fallback paths.
		}

		this.acquired = false;
	}

	@Override
	public void close() {
		if (this.closed) {
			return;
		}

		this.closed = true;
		this.acquired = false;
		this.surfaceLease.close();
	}

	@Override
	public java.util.Collection<PresentMode> supportedPresentModes() {
		return java.util.List.of(PresentMode.IMMEDIATE, PresentMode.FIFO);
	}

	boolean isAcquired() {
		return this.acquired;
	}

	boolean isClosed() {
		return this.closed;
	}
}
