package net.irisshaders.iris.vulkan;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL20C;

import java.util.OptionalDouble;

/**
 * GL sampler objects (glGenSamplers + glSamplerParameteri) on Vulkan: the parameters are recorded
 * and turned into a RenderPearl GpuSampler the first time the sampler is bound to a draw.
 * Depth comparison (hardware shadow sampling) is not expressible through RenderPearl's sampler API
 * and is handled with the shadow pass (M3).
 */
public final class VulkanSamplers {
	private static final Int2ObjectOpenHashMap<State> SAMPLERS = new Int2ObjectOpenHashMap<>();
	private static int nextId = 1;
	@Nullable
	private static GpuSampler fallbackLinear;

	private VulkanSamplers() {
	}

	public static int create() {
		int id = nextId++;
		SAMPLERS.put(id, new State());
		return id;
	}

	public static void parameter(int sampler, int pname, int param) {
		State state = SAMPLERS.get(sampler);

		if (state == null) {
			return;
		}

		switch (pname) {
			case GL11C.GL_TEXTURE_MAG_FILTER -> state.magLinear = param == GL11C.GL_LINEAR;
			case GL11C.GL_TEXTURE_MIN_FILTER -> {
				state.minLinear = param == GL11C.GL_LINEAR || param == GL11C.GL_LINEAR_MIPMAP_LINEAR
					|| param == GL11C.GL_LINEAR_MIPMAP_NEAREST;
				state.mipmapped = param != GL11C.GL_LINEAR && param != GL11C.GL_NEAREST;
			}
			case GL11C.GL_TEXTURE_WRAP_S -> state.clampU = param != GL11C.GL_REPEAT;
			case GL11C.GL_TEXTURE_WRAP_T -> state.clampV = param != GL11C.GL_REPEAT;
			case GL20C.GL_TEXTURE_COMPARE_MODE -> state.compare = param != GL11C.GL_NONE;
			default -> {
				// Other parameters (LOD bias, border colour) have no RenderPearl equivalent yet.
			}
		}

		state.close();
	}

	public static void destroy(int sampler) {
		State state = SAMPLERS.remove(sampler);

		if (state != null) {
			state.close();
		}
	}

	/** The GpuSampler for a GL sampler id; id 0 (no sampler bound) means "linear, clamped". */
	public static GpuSampler get(int sampler) {
		State state = SAMPLERS.get(sampler);

		if (state == null) {
			if (fallbackLinear == null) {
				fallbackLinear = RenderSystem.getDevice().createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
					FilterMode.LINEAR, FilterMode.LINEAR, 1, OptionalDouble.of(0.0));
			}

			return fallbackLinear;
		}

		if (state.sampler == null) {
			state.sampler = RenderSystem.getDevice().createSampler(
				state.clampU ? AddressMode.CLAMP_TO_EDGE : AddressMode.REPEAT,
				state.clampV ? AddressMode.CLAMP_TO_EDGE : AddressMode.REPEAT,
				state.minLinear ? FilterMode.LINEAR : FilterMode.NEAREST,
				state.magLinear ? FilterMode.LINEAR : FilterMode.NEAREST,
				1,
				state.mipmapped ? OptionalDouble.empty() : OptionalDouble.of(0.0));
		}

		return state.sampler;
	}

	public static boolean isCompare(int sampler) {
		State state = SAMPLERS.get(sampler);
		return state != null && state.compare;
	}

	private static final class State {
		// GL defaults for a new sampler object: NEAREST_MIPMAP_LINEAR min, LINEAR mag, REPEAT.
		boolean minLinear;
		boolean magLinear = true;
		boolean mipmapped = true;
		boolean clampU;
		boolean clampV;
		boolean compare;
		@Nullable
		GpuSampler sampler;

		/** Drops the created sampler after a parameter change; it is made again on next use. */
		void close() {
			if (sampler != null) {
				sampler.close();
				sampler = null;
			}
		}
	}
}
