#version 150

// ============================================================================
// Derived from Re:Avaritia cosmic shader
// Copyright (c) Nova-Committee (cnlimiter, Asek3, MikhailTapio)
// Licensed under the MIT License
// Source: https://github.com/Nova-Committee/Re-Avaritia/tree/neo/1.21.1
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in all
// copies or substantial portions of the Software.
// ============================================================================

#define M_PI 3.1415926535897932384626433832795

#moj_import <fog.glsl>

const int cosmiccount = 10;
const int cosmicoutof = 101;
const float lightmix = 0.2f;

uniform sampler2D Sampler0;

uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;

uniform float time;

uniform float yaw;
uniform float pitch;
uniform float externalScale;

uniform float opacity;

uniform mat2 cosmicuvs[cosmiccount];

in float vertexDistance;
in vec4 vertexColor;
in vec2 texCoord0;
in vec4 normal;
in vec3 fPos;

out vec4 fragColor;

mat4 rotationMatrix(vec3 axis, float angle)
{

    axis = normalize(axis);
    float s = sin(angle);
    float c = cos(angle);
    float oc = 1.0 - c;

    return mat4(oc * axis.x * axis.x + c,           oc * axis.x * axis.y - axis.z * s,  oc * axis.z * axis.x + axis.y * s,  0.0,
                oc * axis.x * axis.y + axis.z * s,  oc * axis.y * axis.y + c,           oc * axis.y * axis.z - axis.x * s,  0.0,
                oc * axis.z * axis.x - axis.y * s,  oc * axis.y * axis.z + axis.x * s,  oc * axis.z * axis.z + c,           0.0,
                0.0,                                0.0,                                0.0,                                1.0);
}

vec3 cosmicRainbow(float phase)
{
    phase = fract(phase);

    vec3 rose = vec3(0.90, 0.48, 0.68);
    vec3 lavender = vec3(0.62, 0.54, 0.90);
    vec3 sky = vec3(0.43, 0.72, 0.88);
    vec3 mint = vec3(0.48, 0.82, 0.66);
    vec3 peach = vec3(0.93, 0.67, 0.45);

    if (phase < 0.2) {
        return mix(rose, lavender, phase * 5.0);
    } else if (phase < 0.4) {
        return mix(lavender, sky, (phase - 0.2) * 5.0);
    } else if (phase < 0.6) {
        return mix(sky, mint, (phase - 0.4) * 5.0);
    } else if (phase < 0.8) {
        return mix(mint, peach, (phase - 0.6) * 5.0);
    }
    return mix(peach, rose, (phase - 0.8) * 5.0);
}

void main (void)
{
    vec4 mask = texture(Sampler0, texCoord0.xy);

    float oneOverExternalScale = 1.0/externalScale;

    int uvtiles = 16;

    float pulse = mod(time,400)/400.0;

    // A clean deep-indigo base keeps the star mask distinct without a muddy cyan cast.
    vec3 cosmicBase = vec3(0.06, 0.05, 0.12) + cosmicRainbow(pulse) * 0.15;
    vec4 col = vec4(cosmicBase, 1.0);

    // get ray from camera to fragment
    vec4 dir = normalize(vec4(-fPos, 0));

    // rotate the ray to show the right bit of the sphere for the angle
    float sb = sin(pitch);
    float cb = cos(pitch);
    dir = normalize(vec4(dir.x, dir.y * cb - dir.z * sb, dir.y * sb + dir.z * cb, 0));

    float sa = sin(-yaw);
    float ca = cos(-yaw);
    dir = normalize(vec4(dir.z * sa + dir.x * ca, dir.y, dir.z * ca - dir.x * sa, 0));

    vec4 ray;

    // draw the layers
    for (int i=0; i<16; i++) {
        int mult = 16-i;

        // get semi-random stuff
        int j = i + 7;
        float rand1 = (j * j * 4321 + j * 8) * 2.0F;
        int k = j + 1;
        float rand2 = (k * k * k * 239 + k * 37) * 3.6F;
        float rand3 = rand1 * 347.4 + rand2 * 63.4;

        // random rotation matrix by random rotation around random axis
        vec3 axis = normalize(vec3(sin(rand1), sin(rand2) , cos(rand3)));

        // apply
        ray = dir * rotationMatrix(axis, mod(rand3, 2*M_PI));

        // calcuate the UVs from the final ray
        float rawu = 0.5 + (atan(ray.z,ray.x)/(2*M_PI));
        float rawv = 0.5 + (asin(ray.y)/M_PI);

        // get UV scaled for layers and offset by time;
        float scale = mult*0.5 + 2.75;
        float u = rawu * scale * externalScale;
        //float v = (rawv + time * 0.00006) * scale * 0.6;
        float v = (rawv + time * 0.0002 * oneOverExternalScale) * scale * 0.6 * externalScale;

        vec2 tex = vec2( u, v );

        // tile position of the current uv
        int tu = int(mod(floor(u*uvtiles),uvtiles));
        int tv = int(mod(floor(v*uvtiles),uvtiles));

        // get pseudorandom variants
        int position = ((171 * tu) + (489 * tv) + (303 * (i+31)) + 17209 ) ^ 10;
        int symbol = int(mod(position, cosmicoutof));
        int rotation = int(mod(pow(tu,float(tv)) + tu + 3 + tv*i, 8));
        bool flip = false;
        if (rotation >= 4) {
            rotation -= 4;
            flip = true;
        }

        // if it's an icon, then add the colour!
        if (symbol >= 0 && symbol < cosmiccount) {

            vec2 cosmictex = vec2(1.0,1.0);
            vec4 tcol = vec4(1.0,0.0,0.0,1.0);

            // get uv within the tile
            float ru = clamp(mod(u,1.0)*uvtiles - tu, 0.0, 1.0);
            float rv = clamp(mod(v,1.0)*uvtiles - tv, 0.0, 1.0);

            if (flip) {
                ru = 1.0 - ru;
            }

            float oru = ru;
            float orv = rv;

            // rotate uvs if necessary
            if (rotation == 1) {
                oru = 1.0-rv;
                orv = ru;
            } else if (rotation == 2) {
                oru = 1.0-ru;
                orv = 1.0-rv;
            } else if (rotation == 3) {
                oru = rv;
                orv = 1.0-ru;
            }

            // get the iicon uvs for the tile
            float umin = cosmicuvs[symbol][0][0];
            float umax = cosmicuvs[symbol][1][0];
            float vmin = cosmicuvs[symbol][0][1];
            float vmax = cosmicuvs[symbol][1][1];

            // interpolate based on tile uvs
            cosmictex.x = umin * (1.0-oru) + umax * oru;
            cosmictex.y = vmin * (1.0-orv) + vmax * orv;

            tcol = texture(Sampler0, cosmictex);

            // set the alpha, blending out at the bunched ends
            float a = tcol.r * (0.5 + (1.0/mult) * 1.0) * (1.0-smoothstep(0.15, 0.48, abs(rawv-0.5)));

            float colorPhase = fract(
                (mod(rand1, 29.0) / 29.0) * 0.65
                + (mod(rand2, 35.0) / 35.0) * 0.35
                + pulse * 0.2
            );
            vec3 starColor = cosmicRainbow(colorPhase);
            float starBrightness = (mod(rand1, 17.0) / 17.0) * 0.12 + 0.82;

            // mix the colours
            col = col + vec4(starColor * starBrightness, 1.0) * a * 0.85;
        }
    }

    // apply lighting
    vec3 shade = vertexColor.rgb * (lightmix) + vec3(1.0-lightmix,1.0-lightmix,1.0-lightmix);
    col.rgb *= shade;

    // apply mask
    col.a *= mask.r * opacity;

    col = clamp(col,0.0,1.0);

    fragColor = linear_fog(col * ColorModulator, vertexDistance, FogStart, FogEnd, FogColor);
}
