package net.irisshaders.iris.vulkan;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.jetbrains.annotations.Nullable;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Iris passes textures around as GL names (ints, often behind an IntSupplier). On Vulkan those ints
 * are keys into this table: Iris' own render targets get one when created, game textures (atlas,
 * lightmap, depth) the first time Iris asks for them. Id 0 stays "no texture", like in GL.
 */
public final class VulkanTextures {
	private static final Int2ObjectOpenHashMap<Entry> BY_ID = new Int2ObjectOpenHashMap<>();
	private static final Map<GpuTexture, Integer> BY_TEXTURE = new IdentityHashMap<>();
	private static final int SWEEP_INTERVAL = 256;
	private static int nextId = 1;
	private static int registrationsSinceSweep;

	private VulkanTextures() {
	}

	/** A texture Iris owns: destroyed again through {@link #delete(int)}. */
	public static int create(String label, int usage, GpuFormat format, int width, int height, int mipLevels) {
		RenderSystem.assertOnRenderThread();
		GpuTexture texture = RenderSystem.getDevice().createTexture(label, usage, format, width, height, 1, mipLevels);
		return register(texture, true);
	}

	/** The id of a texture someone else owns (the game, Sodium); stable for the texture's lifetime. */
	public static int idOf(GpuTexture texture) {
		Integer id = BY_TEXTURE.get(texture);
		return id != null ? id : register(texture, false);
	}

	/** Gives an owned id a new texture (resize), keeping the id the pack's programs already sampled. */
	public static void replace(int id, String label, int usage, GpuFormat format, int width, int height, int mipLevels) {
		Entry old = BY_ID.get(id);

		if (old == null || !old.owned) {
			throw new IllegalStateException("No Iris-owned Vulkan texture with id " + id);
		}

		BY_TEXTURE.remove(old.texture);
		old.close();

		GpuTexture texture = RenderSystem.getDevice().createTexture(label, usage, format, width, height, 1, mipLevels);
		BY_ID.put(id, new Entry(texture, true));
		BY_TEXTURE.put(texture, id);
	}

	public static void delete(int id) {
		Entry entry = BY_ID.remove(id);

		if (entry != null) {
			BY_TEXTURE.remove(entry.texture);
			entry.close();
		}
	}

	@Nullable
	public static GpuTexture texture(int id) {
		Entry entry = BY_ID.get(id);
		return entry != null && !entry.texture.isClosed() ? entry.texture : null;
	}

	/** A view over all mip levels, created on first use. */
	@Nullable
	public static GpuTextureView view(int id) {
		Entry entry = BY_ID.get(id);

		if (entry == null || entry.texture.isClosed()) {
			return null;
		}

		if (entry.view == null) {
			entry.view = RenderSystem.getDevice().createTextureView(entry.texture);
		}

		return entry.view;
	}

	/** A view of one mip level (what a render pass writes into), created on first use. */
	@Nullable
	public static GpuTextureView levelView(int id, int level) {
		Entry entry = BY_ID.get(id);

		if (entry == null || entry.texture.isClosed()) {
			return null;
		}

		if (entry.texture.getMipLevels() == 1) {
			return view(id);
		}

		if (entry.levelViews == null) {
			entry.levelViews = new GpuTextureView[entry.texture.getMipLevels()];
		}

		if (entry.levelViews[level] == null) {
			entry.levelViews[level] = RenderSystem.getDevice().createTextureView(entry.texture, level, 1);
		}

		return entry.levelViews[level];
	}

	private static int register(GpuTexture texture, boolean owned) {
		if (++registrationsSinceSweep >= SWEEP_INTERVAL) {
			registrationsSinceSweep = 0;
			sweepClosed();
		}

		int id = nextId++;
		BY_ID.put(id, new Entry(texture, owned));
		BY_TEXTURE.put(texture, id);
		return id;
	}

	/** Game textures are closed by their owners without telling us; forget those. */
	private static void sweepClosed() {
		BY_ID.int2ObjectEntrySet().removeIf(e -> {
			Entry entry = e.getValue();

			if (!entry.owned && entry.texture.isClosed()) {
				BY_TEXTURE.remove(entry.texture);

				entry.closeViews();
				return true;
			}

			return false;
		});
	}

	private static final class Entry {
		final GpuTexture texture;
		final boolean owned;
		@Nullable
		GpuTextureView view;
		@Nullable
		GpuTextureView[] levelViews;

		Entry(GpuTexture texture, boolean owned) {
			this.texture = texture;
			this.owned = owned;
		}

		void closeViews() {
			if (view != null) {
				view.close();
			}

			if (levelViews != null) {
				for (GpuTextureView levelView : levelViews) {
					if (levelView != null) {
						levelView.close();
					}
				}
			}
		}

		void close() {
			closeViews();

			if (owned) {
				texture.close();
			}
		}
	}
}
