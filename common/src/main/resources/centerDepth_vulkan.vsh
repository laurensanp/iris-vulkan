#version 330 core
// centerDepth.vsh for the Vulkan backend: uniforms in the IrisUniforms block, attributes named like the
// POSITION_TEX vertex format so RenderPearl can match them.

layout(std140) uniform IrisUniforms {
	mat4 projection;
	float lastFrameTime;
	float decay;
};

layout(location = 0) in vec3 Position;

void main() {
	gl_Position = projection * vec4(Position, 1.0);
}
