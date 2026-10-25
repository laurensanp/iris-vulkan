package net.irisshaders.iris.gl.blending;

public class BlendModeOverride {
	public static final BlendModeOverride OFF = new BlendModeOverride(null);

	private final BlendMode blendMode;

	public BlendModeOverride(BlendMode blendMode) {
		this.blendMode = blendMode;
	}

	public static void restore() {
		BlendModeStorage.restoreBlend();
	}

	public void apply() {
		BlendModeStorage.overrideBlend(this.blendMode);
	}

	/** The blend mode this override sets, or null if it turns blending off. */
	public BlendMode getBlendMode() {
		return blendMode;
	}
}
