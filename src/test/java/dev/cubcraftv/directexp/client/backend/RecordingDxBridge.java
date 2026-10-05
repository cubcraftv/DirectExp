package dev.cubcraftv.directexp.client.backend;

import dev.cubcraftv.directexp.bridge.DxBridge;
import dev.cubcraftv.directexp.bridge.DxBridgeProbe;
import dev.cubcraftv.directexp.bridge.DxPipelineStateDescriptor;

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
	public long createTexture(int format, int width, int height, int arrayLayers, int mipLevels, int bindMask, boolean cubemap) {
		failIfConfigured();
		this.calls.add("createTexture(fmt=" + format + "," + width + "x" + height + ",layers=" + arrayLayers + ",mips=" + mipLevels + ",bind=" + bindMask + ",cube=" + cubemap + ")");
		return this.textureHandle;
	}

	@Override
	public void uploadTextureRgba8(long handle, int mipLevel, int layer, ByteBuffer pixels, int width, int height) {
		this.calls.add("uploadTexture(" + handle + ",mip=" + mipLevel + ",layer=" + layer + ",size=" + width + "x" + height + ",cap=" + pixels.capacity() + ")");
	}

	@Override
	public void uploadTextureRegionRgba8(long handle, int mipLevel, int layer, ByteBuffer pixels, int mipWidth, int mipHeight, int x, int y, int width, int height) {
		this.calls.add("uploadTextureRegion(" + handle + ",mip=" + mipLevel + ",layer=" + layer + ",rect=" + x + "," + y + " " + width + "x" + height + ")");
	}

	@Override
	public long createPipeline(byte[] vertexBytecode, byte[] pixelBytecode, DxPipelineStateDescriptor state) {
		failIfConfigured();
		this.calls.add("createPipeline(vs=" + vertexBytecode.length + ",ps=" + pixelBytecode.length + ")");
		return this.nextHandle++;
	}

	@Override
	public void releasePipeline(long handle) {
		this.calls.add("releasePipeline(" + handle + ")");
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
