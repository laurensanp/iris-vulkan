package net.irisshaders.iris.vulkan;

import com.mojang.renderpearl.backend.api.SpvModule;

/** A sampler descriptor reported as 2D to RenderPearl's pipeline builder (see MixinSPIRVModule_Storage). */
public record DescriptorAs2D(SpvModule.Reflection.Descriptor descriptor) implements SpvModule.Reflection.Descriptor {
	@Override
	public String name() {
		return descriptor.name();
	}

	@Override
	public SpvModule.Reflection.Type type() {
		SpvModule.Reflection.Type type = descriptor.type();

		return new SpvModule.Reflection.Type() {
			@Override
			public int baseType() {
				return type.baseType();
			}

			@Override
			public int dimensions() {
				return 1; // SpvDim2D
			}

			@Override
			public int vectorSize() {
				return type.vectorSize();
			}

			@Override
			public int arrayDimensions() {
				return type.arrayDimensions();
			}

			@Override
			public int arrayLength(int dimension) {
				return type.arrayLength(dimension);
			}
		};
	}

	@Override
	public int resourceType() {
		return descriptor.resourceType();
	}

	@Override
	public int descriptorSetIndex() {
		return descriptor.descriptorSetIndex();
	}

	@Override
	public void descriptorSetIndex(int set) {
		descriptor.descriptorSetIndex(set);
	}

	@Override
	public int binding() {
		return descriptor.binding();
	}

	@Override
	public void binding(int binding) {
		descriptor.binding(binding);
	}
}
