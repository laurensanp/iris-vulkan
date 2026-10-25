package net.irisshaders.iris.vulkan;

import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.api.vertex.VertexFormatElement;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;

/** Copies of RenderPearl pipelines and vertex formats with some parts changed. */
public final class VulkanPipelines {
	private static final MethodHandle VERTEX_FORMAT;

	static {
		try {
			VERTEX_FORMAT = MethodHandles.privateLookupIn(VertexFormat.class, MethodHandles.lookup())
				.findConstructor(VertexFormat.class, MethodType.methodType(void.class, List.class, int.class, int.class));
		} catch (ReflectiveOperationException e) {
			throw new ExceptionInInitializerError(e);
		}
	}

	private VulkanPipelines() {
	}

	/** {@code source} with other vertex formats and color targets; shaders, defines and the rest stay. */
	public static RenderPipeline copy(RenderPipeline source, List<VertexFormat> vertexFormats, List<ColorTargetState> colorTargets) {
		return new Copy(source, vertexFormats.toArray(VertexFormat[]::new), colorTargets.toArray(ColorTargetState[]::new));
	}

	/** The same vertex layout with the elements renamed (RenderPearl matches attributes to shader inputs by name). */
	public static VertexFormat renamed(VertexFormat format, List<String> names) {
		List<VertexFormatElement> elements = format.getElements();
		VertexFormatElement[] renamed = new VertexFormatElement[elements.size()];

		for (int i = 0; i < renamed.length; i++) {
			VertexFormatElement element = elements.get(i);
			renamed[i] = new VertexFormatElement(names.get(i), element.offset(), element.format());
		}

		try {
			return (VertexFormat) VERTEX_FORMAT.invoke(List.of(renamed), format.getVertexSize(), format.getStepRate());
		} catch (Throwable e) {
			throw new IllegalStateException("Couldn't copy vertex format " + format, e);
		}
	}

	private static final class Copy extends RenderPipeline {
		private Copy(RenderPipeline source, VertexFormat[] vertexFormats, ColorTargetState[] colorTargets) {
			super(source.getLocation(), source.getShaders(), source.getShaderDefines(), source.getBindGroupLayouts(), colorTargets,
				source.getDepthStencilState(), source.getPolygonMode(), source.isCull(), vertexFormats, source.getPrimitiveTopology(),
				source.pushConstantSize(), source.getSortKey());
		}
	}
}
