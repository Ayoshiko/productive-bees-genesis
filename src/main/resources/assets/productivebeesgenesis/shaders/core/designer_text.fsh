#version 150

uniform sampler2D Sampler0;
uniform vec4 ColorModulator;

in vec2 texCoord;
in vec4 vertexColor;
out vec4 fragColor;

void main() {
    float distance = texture(Sampler0, texCoord).a - 128.0 / 255.0;
    float edgeWidth = max(length(vec2(dFdx(distance), dFdy(distance))), 0.00001);
    float coverage = clamp(distance / edgeWidth + 0.5, 0.0, 1.0);
    fragColor = vec4(vertexColor.rgb, vertexColor.a * coverage) * ColorModulator;
    if (fragColor.a < 0.005) discard;
}
