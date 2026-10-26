package net.irisshaders.iris.gl.image;

import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.vulkan.IrisVulkan;
import net.irisshaders.iris.vulkan.VulkanProgram;
import org.lwjgl.opengl.GL42C;

import java.util.function.IntSupplier;

public class ImageBinding {
	private final String name;
	private final int imageUnit;
	private final int internalFormat;
	private final IntSupplier textureID;

	public ImageBinding(String name, int imageUnit, int internalFormat, IntSupplier textureID) {
		this.name = name;
		this.textureID = textureID;
		this.imageUnit = imageUnit;
		this.internalFormat = internalFormat;
	}

	public void update() {
		if (IrisVulkan.isActive()) {
			// Images are bound by name on Vulkan (descriptor set 1, see VulkanStorage).
			VulkanProgram program = VulkanProgram.current();

			if (program != null) {
				program.setImage(name, textureID.getAsInt());
			}

			return;
		}

		// We can assume that image bindings are supported here as either the EXT extension or 4.2 core, as otherwise ImageLimits
		// would report that zero image units are supported.
		// RRe36: I'm not sure if its perfectly fine to always have it be layered, but according to Balint its *probably* fine. Still might need to verify that though.
		IrisRenderSystem.bindImageTexture(imageUnit, textureID.getAsInt(), 0, true, 0, GL42C.GL_READ_WRITE, internalFormat);
	}
}
