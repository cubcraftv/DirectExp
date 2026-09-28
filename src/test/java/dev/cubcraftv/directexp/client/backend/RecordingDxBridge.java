package dev.cubcraftv.directexp.client.backend;

import dev.cubcraftv.directexp.bridge.DxBridge;
import dev.cubcraftv.directexp.bridge.DxBridgeProbe;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/** Records the resource-lifecycle calls DxBuffer/DxSampler/DxTextureView make; no native code involved. */
final class RecordingDxBridge implements DxBridge {
	final List<String> calls = new ArrayList<>();
	boolean failCreates;
	long nextHandle = 100L;
	long textureHandle = 77L;
	int lastSamplerAnisotropy;
	boolean lastSamplerMipLinear;
	float lastSamplerMaxLod;

	@Override
	public DxBridgeProbe probe() {
		return null;
	}

	private void failIfConfigured() {
		if (this.failCreates) {
			throw new IllegalStateException("no active device");
		}
	}

	@Override
	public long createBuffer(int bindMask, int byteWidth) {
		failIfConfigured();
		this.calls.add("createBuffer(mask=" + bindMask + ",width=" + byteWidth + ")");
		return this.nextHandle++;
	}

	@Override
	public void updateBuffer(long handle, ByteBuffer mirror, int offset, int length) {
		this.calls.add("updateBuffer(" + handle + ",off=" + offset + ",len=" + length + ",cap=" + mirror.capacity() + ")");
	}

	@Override
	public void releaseBuffer(long handle) {
		this.calls.add("releaseBuffer(" + handle + ")");
	}

	@Override
	public long createSampler(int addressU, int addressV, boolean minLinear, boolean magLinear, boolean mipLinear, int maxAnisotropy, float maxLod) {
		failIfConfigured();
		this.lastSamplerAnisotropy = maxAnisotropy;
		this.lastSamplerMipLinear = mipLinear;
		this.lastSamplerMaxLod = maxLod;
		this.calls.add("createSampler(u=" + addressU + ",v=" + addressV + ",min=" + minLinear + ",mag=" + magLinear + ")");
		return this.nextHandle++;
	}

	@Override
	public void releaseSampler(long handle) {
		this.calls.add("releaseSampler(" + handle + ")");
	}

	@Override
	public long createTexture2D(int width, int height, int mipLevels) {
		return this.textureHandle;
	}

	@Override
	public void releaseTexture(long handle) {
		this.calls.add("releaseTexture(" + handle + ")");
	}

	@Override
	public long createTextureView(long textureHandle, int baseMipLevel, int mipLevels) {
		failIfConfigured();
		this.calls.add("createTextureView(tex=" + textureHandle + ",base=" + baseMipLevel + ",mips=" + mipLevels + ")");
		return this.nextHandle++;
	}

	@Override
	public void releaseTextureView(long handle) {
		this.calls.add("releaseTextureView(" + handle + ")");
	}
}
