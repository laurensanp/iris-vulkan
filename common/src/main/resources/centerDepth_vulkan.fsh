#version 330 core
// centerDepth.fsh for the Vulkan backend (see centerDepth_vulkan.vsh).

layout(std140) uniform IrisUniforms {
	mat4 projection;
	float lastFrameTime;
	float decay;
};

uniform sampler2D depth;
uniform sampler2D altDepth;

layout(location = 0) out float iris_fragColor;

void main() {
	float currentDepth = 1.0 - texture(depth, vec2(0.5)).r;
	float decay2 = 1.0 - exp(-decay * lastFrameTime);
	float oldDepth = texture(altDepth, vec2(0.5)).r;

	if (isnan(oldDepth)) {
		oldDepth = currentDepth;
	}

	iris_fragColor = mix(oldDepth, currentDepth, decay2);
}
