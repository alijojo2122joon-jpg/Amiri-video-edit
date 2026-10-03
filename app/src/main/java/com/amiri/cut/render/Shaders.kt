package com.amiri.cut.render

/**
 * GLSL ES 1.00 sources. All layer textures are premultiplied RGBA with the GL
 * origin (v = 0 at the bottom of the image).
 */
object Shaders {

    const val VS = """
attribute vec2 aPos;
varying vec2 vUv;
void main() {
  vUv = aPos * 0.5 + 0.5;
  gl_Position = vec4(aPos, 0.0, 1.0);
}
"""

    private const val COMMON = """
precision highp float;
varying vec2 vUv;
float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }
float hash(vec2 p) { return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453); }
vec3 unpremul(vec4 s) { return s.a > 0.0001 ? s.rgb / s.a : vec3(0.0); }
"""

    // ───────────────────────── input pass (source → layer, with masks) ─────────────────────────

    private const val MASKS = """
uniform sampler2D uRoto;
uniform float uHasRoto;
uniform float uRotoInvert;
uniform int uMaskCount;
uniform vec4 uMaskA[4];
uniform vec4 uMaskB[4];
uniform vec4 uMaskC[4];
uniform sampler2D uPath0;
uniform sampler2D uPath1;
uniform float uAspect;

float shapeMask(vec4 A, vec4 B, vec4 C, vec2 p) {
  vec2 d = p - A.xy;
  d.x *= uAspect;
  float cs = cos(-B.x);
  float sn = sin(-B.x);
  vec2 q = vec2(cs * d.x - sn * d.y, sn * d.x + cs * d.y);
  vec2 hs = max(vec2(A.z * uAspect, A.w) * 0.5 + vec2(B.z), vec2(0.0001));
  float f = max(B.y, 0.0005);
  float m;
  if (C.x < 0.5) {
    vec2 e = abs(q) - hs;
    float sd = length(max(e, vec2(0.0))) + min(max(e.x, e.y), 0.0);
    m = 1.0 - smoothstep(-f * 0.5, f * 0.5, sd);
  } else if (C.x < 1.5) {
    float k = length(q / hs);
    float sd = (k - 1.0) * min(hs.x, hs.y);
    m = 1.0 - smoothstep(-f * 0.5, f * 0.5, sd);
  } else {
    vec2 uv = q / (2.0 * hs) + 0.5;
    float inside = step(0.0, uv.x) * step(uv.x, 1.0) * step(0.0, uv.y) * step(uv.y, 1.0);
    float t = C.w < 0.5 ? texture2D(uPath0, uv).a : texture2D(uPath1, uv).a;
    m = t * inside;
  }
  m *= B.w;
  if (C.z > 0.5) m = 1.0 - m;
  return m;
}

float maskAlpha(vec2 glUv) {
  vec2 p = vec2(glUv.x, 1.0 - glUv.y);
  float acc = 1.0;
  if (uMaskCount > 0) {
    acc = uMaskC[0].y > 0.5 ? 1.0 : 0.0;
    for (int i = 0; i < 4; i++) {
      if (i >= uMaskCount) break;
      float m = shapeMask(uMaskA[i], uMaskB[i], uMaskC[i], p);
      float mode = uMaskC[i].y;
      if (mode < 0.5) acc = max(acc, m);
      else if (mode < 1.5) acc = acc * (1.0 - m);
      else acc = min(acc, m);
    }
  }
  if (uHasRoto > 0.5) {
    float r = texture2D(uRoto, p).a;
    if (uRotoInvert > 0.5) r = 1.0 - r;
    acc *= r;
  }
  return acc;
}
"""

    const val INPUT_OES = "#extension GL_OES_EGL_image_external : require\n" + COMMON + MASKS + """
uniform samplerExternalOES uTex;
uniform mat4 uTexMatrix;
void main() {
  vec2 tc = (uTexMatrix * vec4(vUv, 0.0, 1.0)).xy;
  vec4 c = texture2D(uTex, tc);
  c.a = 1.0;
  gl_FragColor = c * maskAlpha(vUv);
}
"""

    const val INPUT_2D = COMMON + MASKS + """
uniform sampler2D uTex;
void main() {
  vec4 c = texture2D(uTex, vec2(vUv.x, 1.0 - vUv.y));
  gl_FragColor = c * maskAlpha(vUv);
}
"""

    // ───────────────────────── compositing ─────────────────────────

    const val COMPOSITE = COMMON + """
uniform sampler2D uLayer;
uniform sampler2D uDst;
uniform mat3 uInv[12];
uniform int uSamples;
uniform vec4 uCrop;
uniform float uOpacity;
uniform int uBlend;

vec3 blendF(vec3 b, vec3 s) {
  if (uBlend == 1) return b + s - b * s;
  if (uBlend == 2) return min(b + s, vec3(1.0));
  if (uBlend == 3) return b * s;
  if (uBlend == 4) return mix(2.0 * b * s, 1.0 - 2.0 * (1.0 - b) * (1.0 - s), step(0.5, b));
  if (uBlend == 5) return (1.0 - 2.0 * s) * b * b + 2.0 * s * b;
  if (uBlend == 6) return mix(2.0 * b * s, 1.0 - 2.0 * (1.0 - b) * (1.0 - s), step(0.5, s));
  if (uBlend == 7) return min(b, s);
  if (uBlend == 8) return max(b, s);
  return s;
}

void main() {
  vec3 p = vec3(gl_FragCoord.xy, 1.0);
  vec4 acc = vec4(0.0);
  for (int i = 0; i < 12; i++) {
    if (i >= uSamples) break;
    vec3 uv = uInv[i] * p;
    float inside = step(uCrop.x, uv.x) * step(uv.x, 1.0 - uCrop.z) * step(uCrop.w, uv.y) * step(uv.y, 1.0 - uCrop.y);
    acc += texture2D(uLayer, uv.xy) * inside;
  }
  vec4 src = acc / float(uSamples) * uOpacity;
  vec4 dst = texture2D(uDst, vUv);
  if (uBlend == 0) {
    gl_FragColor = src + dst * (1.0 - src.a);
  } else {
    float sa = src.a;
    float da = dst.a;
    vec3 cs = sa > 0.0001 ? src.rgb / sa : vec3(0.0);
    vec3 cb = da > 0.0001 ? dst.rgb / da : vec3(0.0);
    vec3 rgb = (1.0 - da) * src.rgb + (1.0 - sa) * dst.rgb + sa * da * clamp(blendF(cb, cs), 0.0, 1.0);
    gl_FragColor = vec4(rgb, sa + da * (1.0 - sa));
  }
}
"""

    const val COPY = COMMON + """
uniform sampler2D uTex;
void main() { gl_FragColor = texture2D(uTex, vUv); }
"""

    const val MIX = COMMON + """
uniform sampler2D uTex;
uniform sampler2D uOrig;
uniform float uAmount;
void main() { gl_FragColor = mix(texture2D(uOrig, vUv), texture2D(uTex, vUv), uAmount); }
"""

    const val PRESENT = COMMON + """
uniform sampler2D uTex;
uniform float uChecker;
uniform float uCell;
uniform vec3 uView;
void main() {
  // Viewer zoom/pan (matches the Compose overlay transform: scale about center, then translate).
  float lx = 0.5 + (vUv.x - 0.5 - uView.y) / uView.x;
  float ly = 0.5 + ((1.0 - vUv.y) - 0.5 - uView.z) / uView.x;
  vec2 uv = vec2(lx, 1.0 - ly);
  if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) { gl_FragColor = vec4(0.06, 0.06, 0.07, 1.0); return; }
  vec4 c = texture2D(uTex, uv);
  vec3 bg = vec3(0.0);
  if (uChecker > 0.5) {
    vec2 q = floor(gl_FragCoord.xy / uCell);
    bg = mix(vec3(0.17), vec3(0.22), mod(q.x + q.y, 2.0));
  }
  gl_FragColor = vec4(c.rgb + bg * (1.0 - c.a), 1.0);
}
"""

    /** 9-tap separable gaussian (sigma ≈ 2 taps); uDir = texel step along one axis. */
    const val BLUR = COMMON + """
uniform sampler2D uTex;
uniform vec2 uDir;
void main() {
  vec4 s = texture2D(uTex, vUv) * 0.2042;
  s += (texture2D(uTex, vUv + uDir) + texture2D(uTex, vUv - uDir)) * 0.1802;
  s += (texture2D(uTex, vUv + uDir * 2.0) + texture2D(uTex, vUv - uDir * 2.0)) * 0.1238;
  s += (texture2D(uTex, vUv + uDir * 3.0) + texture2D(uTex, vUv - uDir * 3.0)) * 0.0663;
  s += (texture2D(uTex, vUv + uDir * 4.0) + texture2D(uTex, vUv - uDir * 4.0)) * 0.0276;
  gl_FragColor = s;
}
"""

    // ───────────────────────── color ─────────────────────────

    const val COLOR = COMMON + """
uniform sampler2D uTex;
uniform sampler2D uBlurTex;
uniform sampler2D uCurve;
uniform vec2 uTexel;
uniform float uAspect;
uniform float uExposure;
uniform float uContrast;
uniform float uBrightness;
uniform float uHighlights;
uniform float uShadows;
uniform float uWhites;
uniform float uBlacks;
uniform float uSaturation;
uniform float uTemp;
uniform float uTint;
uniform float uHslH[6];
uniform float uHslS[6];
uniform float uHslL[6];
uniform vec3 uLift;
uniform vec3 uGamma;
uniform vec3 uGain;
uniform float uVignette;
uniform float uVigFeather;
uniform float uSharpen;
uniform float uBlur;
uniform float uHasCurve;
uniform float uHasHsl;
uniform sampler2D uClarTex;
uniform float uClarity;
uniform float uDehaze;
uniform float uVibrance;
uniform float uFade;
uniform vec4 uSplit; // shHue, shSat, hiHue, hiSat
uniform float uSplitBal;

vec3 rgb2hsv(vec3 c) {
  vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
  vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
  vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
  float d = q.x - min(q.w, q.y);
  float e = 1.0e-10;
  return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);
}
vec3 hsv2rgb(vec3 c) {
  vec4 K = vec4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);
  vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
  return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
}

void main() {
  vec4 src = texture2D(uTex, vUv);
  if (uBlur > 0.001) src = mix(src, texture2D(uBlurTex, vUv), clamp(uBlur * 1.5, 0.0, 1.0));
  if (uSharpen > 0.001) {
    vec4 n = texture2D(uTex, vUv + vec2(uTexel.x, 0.0)) + texture2D(uTex, vUv - vec2(uTexel.x, 0.0))
           + texture2D(uTex, vUv + vec2(0.0, uTexel.y)) + texture2D(uTex, vUv - vec2(0.0, uTexel.y));
    src = clamp(src + (src - n * 0.25) * uSharpen * 2.0, 0.0, 1.0);
    src.rgb = min(src.rgb, vec3(src.a));
  }
  float a = src.a;
  if (a < 0.0001) { gl_FragColor = vec4(0.0); return; }
  vec3 c = src.rgb / a;

  c *= exp2(uExposure);
  c *= vec3(1.0 + 0.18 * uTemp, 1.0 + 0.04 * uTemp - 0.12 * uTint, 1.0 - 0.18 * uTemp);
  if (abs(uClarity) > 0.001) {
    vec4 bs = texture2D(uClarTex, vUv);
    vec3 bc = bs.a > 0.0001 ? bs.rgb / bs.a : c;
    float lm = luma(c);
    float mid = 1.0 - pow(abs(lm - 0.5) * 2.0, 2.0);
    c += (c - bc) * uClarity * 1.4 * mid;
  }
  if (abs(uDehaze) > 0.001) {
    if (uDehaze > 0.0) {
      float k = uDehaze * 0.12;
      c = (c - k) / (1.0 - k * 1.6);
      c = mix(vec3(luma(c)), c, 1.0 + uDehaze * 0.35);
    } else {
      c = mix(c, vec3(0.72, 0.74, 0.78), -uDehaze * 0.45);
    }
  }
  float bp = -uBlacks * 0.12;
  float wp = 1.0 - uWhites * 0.2;
  c = (c - bp) / max(wp - bp, 0.05);
  float l = luma(c);
  c += uShadows * 0.3 * (1.0 - smoothstep(0.0, 0.5, l));
  c += uHighlights * 0.3 * smoothstep(0.5, 1.0, l);
  c = (c - 0.5) * (1.0 + uContrast) + 0.5;
  c += uBrightness * 0.25;
  c = c * uGain;
  c = c + uLift * (1.0 - c);
  c = pow(max(c, vec3(0.0)), 1.0 / max(uGamma, vec3(0.05)));

  if (uHasHsl > 0.5) {
    vec3 h = rgb2hsv(clamp(c, 0.0, 1.0));
    float hue = h.x * 6.0;
    float dh = 0.0;
    float ds = 0.0;
    float dl = 0.0;
    for (int i = 0; i < 6; i++) {
      float d = abs(hue - float(i));
      d = min(d, 6.0 - d);
      float w = max(0.0, 1.0 - d);
      dh += w * uHslH[i];
      ds += w * uHslS[i];
      dl += w * uHslL[i];
    }
    float sat0 = h.y;
    h.x = fract(h.x + dh / 12.0);
    h.y = clamp(h.y * (1.0 + ds), 0.0, 1.0);
    c = hsv2rgb(h) * (1.0 + dl * 0.6 * sat0);
  }

  float L2 = luma(c);
  c = mix(vec3(L2), c, 1.0 + uSaturation);
  if (abs(uVibrance) > 0.001) {
    vec3 cc = clamp(c, 0.0, 1.0);
    float sat = max(cc.r, max(cc.g, cc.b)) - min(cc.r, min(cc.g, cc.b));
    c = mix(vec3(luma(c)), c, 1.0 + uVibrance * (1.0 - sat) * 1.3);
  }
  if (uSplit.y > 0.001 || uSplit.w > 0.001) {
    float l3 = clamp(luma(c), 0.0, 1.0);
    float b = uSplitBal * 0.3;
    float ws = 1.0 - smoothstep(0.0, 0.55 + b, l3);
    float wh = smoothstep(0.45 + b, 1.0, l3);
    vec3 ts = hsv2rgb(vec3(uSplit.x, 1.0, 1.0)); ts -= vec3(luma(ts));
    vec3 th = hsv2rgb(vec3(uSplit.z, 1.0, 1.0)); th -= vec3(luma(th));
    c += ts * uSplit.y * 0.45 * ws + th * uSplit.w * 0.45 * wh;
  }

  if (uHasCurve > 0.5) {
    c = clamp(c, 0.0, 1.0);
    vec3 m = vec3(texture2D(uCurve, vec2(c.r, 0.5)).a, texture2D(uCurve, vec2(c.g, 0.5)).a, texture2D(uCurve, vec2(c.b, 0.5)).a);
    c = vec3(texture2D(uCurve, vec2(m.r, 0.5)).r, texture2D(uCurve, vec2(m.g, 0.5)).g, texture2D(uCurve, vec2(m.b, 0.5)).b);
  }

  if (uFade > 0.001) {
    c = clamp(c, 0.0, 1.0);
    c = c * (1.0 - uFade * 0.22) + uFade * 0.13;
  }
  if (uVignette > 0.001) {
    vec2 d = vUv - 0.5;
    d.x *= uAspect;
    float r = length(d) / length(vec2(0.5 * uAspect, 0.5));
    c *= 1.0 - uVignette * smoothstep(1.0 - uVigFeather * 0.9 - 0.05, 1.05, r);
  }
  gl_FragColor = vec4(clamp(c, 0.0, 1.0) * a, a);
}
"""

    const val LUT = COMMON + """
uniform sampler2D uTex;
uniform sampler2D uLut;
uniform float uSize;
uniform float uStrength;
vec3 lut(vec3 c) {
  float n = uSize;
  float b = c.b * (n - 1.0);
  float b0 = floor(b);
  float b1 = min(b0 + 1.0, n - 1.0);
  float f = b - b0;
  float y = (c.g * (n - 1.0) + 0.5) / n;
  vec2 uv0 = vec2((b0 * n + c.r * (n - 1.0) + 0.5) / (n * n), y);
  vec2 uv1 = vec2((b1 * n + c.r * (n - 1.0) + 0.5) / (n * n), y);
  return mix(texture2D(uLut, uv0).rgb, texture2D(uLut, uv1).rgb, f);
}
void main() {
  vec4 s = texture2D(uTex, vUv);
  vec3 c = clamp(unpremul(s), 0.0, 1.0);
  c = mix(c, lut(c), uStrength);
  gl_FragColor = vec4(c * s.a, s.a);
}
"""

    const val CHROMA = COMMON + """
uniform sampler2D uTex;
uniform vec3 uKey;
uniform float uTol;
uniform float uSoft;
uniform float uSpill;
uniform float uEdge;
vec2 cbcr(vec3 c) { return vec2(-0.1687 * c.r - 0.3313 * c.g + 0.5 * c.b, 0.5 * c.r - 0.4187 * c.g - 0.0813 * c.b); }
void main() {
  vec4 s = texture2D(uTex, vUv);
  vec3 c = unpremul(s);
  float d = distance(cbcr(c), cbcr(uKey));
  float t0 = uTol * 0.35;
  float t1 = t0 + uSoft * 0.25 + uEdge * 0.12 + 0.002;
  float k = smoothstep(t0, t1, d);
  float spillAmt = uSpill * (1.0 - smoothstep(t0, t1 + 0.3, d));
  c = mix(c, vec3(luma(c)), spillAmt * 0.85);
  float a = s.a * k;
  gl_FragColor = vec4(c * a, a);
}
"""

    // ───────────────────────── light ─────────────────────────

    const val GLOW_BRIGHT = COMMON + """
uniform sampler2D uTex;
uniform float uThr;
uniform float uSoft;
void main() {
  vec4 s = texture2D(uTex, vUv);
  vec3 c = unpremul(s);
  float w = smoothstep(uThr, uThr + 0.05 + uSoft * 0.45, luma(c));
  gl_FragColor = vec4(c * w * s.a, s.a * w);
}
"""

    const val GLOW_COMBINE = COMMON + """
uniform sampler2D uTex;
uniform sampler2D uGlow;
uniform vec3 uColor;
uniform float uIntensity;
uniform int uMode;
void main() {
  vec4 s = texture2D(uTex, vUv);
  vec3 g = texture2D(uGlow, vUv).rgb * uColor * uIntensity;
  vec3 rgb;
  if (uMode == 1) rgb = s.rgb + g;
  else rgb = s.rgb + g * (1.0 - clamp(s.rgb, 0.0, 1.0));
  float a = clamp(s.a + (1.0 - s.a) * max(max(g.r, g.g), g.b), 0.0, 1.0);
  gl_FragColor = vec4(min(rgb, vec3(a)), a);
}
"""

    const val SWEEP = COMMON + """
uniform sampler2D uTex;
uniform float uPos;
uniform float uAngle;
uniform float uWidth;
uniform float uIntensity;
uniform float uSoft;
uniform float uOpacity;
uniform vec3 uColor;
uniform float uAspect;
uniform int uMode;
void main() {
  vec4 s = texture2D(uTex, vUv);
  vec2 p = vUv - 0.5;
  p.x *= uAspect;
  vec2 dir = vec2(cos(uAngle), sin(uAngle));
  float span = 0.5 * (abs(dir.x) * uAspect + abs(dir.y)) + uWidth;
  float d = abs(dot(p, dir) - (uPos - 0.5) * 2.0 * span);
  float band = 1.0 - smoothstep(uWidth * (1.0 - uSoft), uWidth + 0.0001, d);
  vec3 L = uColor * band * uIntensity * uOpacity * s.a;
  vec3 rgb = uMode == 1 ? s.rgb + L : s.rgb + L * (1.0 - clamp(s.rgb, 0.0, 1.0));
  gl_FragColor = vec4(min(rgb, vec3(s.a)), s.a);
}
"""

    /** After Effects-style CC Light Sweep: a light band (linear/smooth/sharp) plus edge highlights. */
    const val CC_SWEEP = COMMON + """
uniform sampler2D uTex;
uniform vec2 uCenter;
uniform float uDir;
uniform float uWidth;
uniform float uIntensity;
uniform float uEdgeI;
uniform float uEdgeT;
uniform vec3 uColor;
uniform float uAspect;
uniform vec2 uTexel;
uniform int uShape;
uniform int uRecept;
void main() {
  vec4 s = texture2D(uTex, vUv);
  vec2 p = vUv - uCenter;
  p.x *= uAspect;
  vec2 n = vec2(cos(uDir), sin(uDir));
  float d = abs(dot(p, n));
  float w = max(uWidth, 0.0005);
  float prof;
  if (uShape == 0) prof = max(0.0, 1.0 - d / w);
  else if (uShape == 2) prof = 1.0 - smoothstep(w * 0.85, w, d);
  else prof = 1.0 - smoothstep(0.0, w, d);
  vec2 o = uTexel * (1.0 + uEdgeT * 12.0);
  float gx = texture2D(uTex, vUv + vec2(o.x, 0.0)).a - texture2D(uTex, vUv - vec2(o.x, 0.0)).a;
  float gy = texture2D(uTex, vUv + vec2(0.0, o.y)).a - texture2D(uTex, vUv - vec2(0.0, o.y)).a;
  float edge = clamp(length(vec2(gx, gy)) * 2.0, 0.0, 1.0);
  vec3 light = uColor * (prof * uIntensity * s.a + edge * prof * uEdgeI);
  if (uRecept == 2) {
    float a = s.a * clamp(prof * uIntensity + edge * prof * uEdgeI, 0.0, 1.0);
    gl_FragColor = vec4(uColor * a, a);
  } else if (uRecept == 1) {
    vec3 rgb = s.rgb + light * (1.0 - clamp(s.rgb, 0.0, 1.0));
    float a = clamp(max(s.a, max(max(light.r, light.g), light.b)), 0.0, 1.0);
    gl_FragColor = vec4(min(rgb, vec3(a)), a);
  } else {
    vec3 rgb = s.rgb + light;
    gl_FragColor = vec4(min(rgb, vec3(s.a)), s.a);
  }
}
"""

    const val RAYS = COMMON + """
uniform sampler2D uTex;
uniform vec2 uCenter;
uniform float uDir;
uniform float uIntensity;
uniform float uLength;
uniform float uDecay;
uniform float uSoft;
uniform float uOpacity;
uniform vec3 uColor;
void main() {
  vec4 s = texture2D(uTex, vUv);
  vec2 delta = vUv - uCenter;
  float cs = cos(uDir);
  float sn = sin(uDir);
  delta = vec2(cs * delta.x - sn * delta.y, sn * delta.x + cs * delta.y);
  delta *= uLength / 48.0;
  vec2 tc = vUv;
  float illum = 1.0;
  float decay = 0.9 + 0.1 * uDecay;
  vec3 acc = vec3(0.0);
  float thr = 0.5 - uSoft * 0.35;
  for (int i = 0; i < 48; i++) {
    tc -= delta;
    vec4 t = texture2D(uTex, tc);
    vec3 c = unpremul(t) * t.a;
    acc += c * max(luma(c) - thr, 0.0) * illum;
    illum *= decay;
  }
  vec3 rays = acc * (uIntensity * 3.0 / 48.0) * uColor * uOpacity;
  vec3 rgb = s.rgb + rays * (1.0 - clamp(s.rgb, 0.0, 1.0));
  float a = clamp(s.a + (1.0 - s.a) * max(max(rays.r, rays.g), rays.b), 0.0, 1.0);
  gl_FragColor = vec4(min(rgb, vec3(a)), a);
}
"""

    const val LEAK = COMMON + """
uniform sampler2D uTex;
uniform vec3 uC1;
uniform vec3 uC2;
uniform vec3 uC3;
uniform vec2 uCenter;
uniform float uTime;
uniform float uIntensity;
uniform float uOpacity;
uniform float uScale;
uniform float uRot;
uniform float uSpeed;
uniform float uAspect;
uniform int uMode;
void main() {
  vec4 s = texture2D(uTex, vUv);
  vec2 p = vUv - uCenter;
  p.x *= uAspect;
  float cs = cos(uRot);
  float sn = sin(uRot);
  p = vec2(cs * p.x - sn * p.y, sn * p.x + cs * p.y) / max(uScale, 0.05);
  float t = uTime * uSpeed;
  vec2 o1 = vec2(-0.35 + sin(t * 0.7) * 0.25, 0.15 + cos(t * 0.5) * 0.2);
  vec2 o2 = vec2(0.3 + cos(t * 0.45) * 0.3, -0.2 + sin(t * 0.6) * 0.2);
  vec2 o3 = vec2(sin(t * 0.33) * 0.4, cos(t * 0.27) * 0.35);
  float b1 = exp(-dot(p - o1, p - o1) * 5.0);
  float b2 = exp(-dot(p - o2, p - o2) * 7.0);
  float b3 = exp(-dot(p - o3, p - o3) * 3.5);
  float streak = exp(-abs(p.y + 0.25 * sin(t * 0.3) + p.x * 0.35) * 6.0) * 0.45;
  vec3 leak = (uC1 * b1 + uC2 * b2 + uC3 * b3 * 0.7 + uC1 * streak) * uIntensity * uOpacity * s.a;
  vec3 rgb = uMode == 1 ? s.rgb + leak : s.rgb + leak * (1.0 - clamp(s.rgb, 0.0, 1.0));
  gl_FragColor = vec4(min(rgb, vec3(s.a)), s.a);
}
"""

    // ───────────────────────── film ─────────────────────────

    const val FILM = COMMON + """
uniform sampler2D uTex;
uniform sampler2D uBlurTex;
uniform vec2 uRes;
uniform float uTime;
uniform float uAspect;
uniform float uGrain;
uniform float uGrainSize;
uniform float uDust;
uniform float uScratch;
uniform float uFlicker;
uniform float uVignette;
uniform float uHalation;
uniform float uFade;
uniform float uChroma;
uniform float uBlurAmt;
uniform float uWarmth;
void main() {
  vec2 uv = vUv;
  vec2 dc = (uv - 0.5) * uChroma * 0.012;
  vec4 s = texture2D(uTex, uv);
  s.r = texture2D(uTex, uv + dc).r;
  s.b = texture2D(uTex, uv - dc).b;
  vec4 bl = texture2D(uBlurTex, uv);
  s = mix(s, bl, uBlurAmt * 0.8);
  float a = s.a;
  if (a < 0.0001) { gl_FragColor = vec4(0.0); return; }
  vec3 c = s.rgb / a;
  vec3 bc = unpremul(bl);
  c += vec3(1.0, 0.35, 0.15) * smoothstep(0.55, 1.0, luma(bc)) * uHalation * 0.6;
  c *= vec3(1.0 + 0.12 * uWarmth, 1.0 + 0.03 * uWarmth, 1.0 - 0.12 * uWarmth);
  c = c * (1.0 - uFade * 0.3) + uFade * 0.12;
  float frame = floor(uTime * 24.0);
  c *= 1.0 + uFlicker * 0.24 * (hash(vec2(frame, 1.7)) - 0.5);
  vec2 d = uv - 0.5;
  d.x *= uAspect;
  float vr = length(d) / length(vec2(0.5 * uAspect, 0.5));
  c *= 1.0 - uVignette * smoothstep(0.4, 1.05, vr);
  vec2 gp = floor(uv * uRes / max(uGrainSize, 0.5));
  c += (hash(gp + frame * 17.13) - 0.5) * uGrain * 0.22;
  vec2 grid = vec2(40.0 * uAspect, 40.0);
  vec2 cell = floor(uv * grid);
  if (hash(cell + frame * 3.1) > 1.0 - uDust * 0.03) {
    vec2 f = fract(uv * grid) - 0.5;
    c = mix(c, vec3(0.05), smoothstep(0.25, 0.0, length(f)) * 0.8);
  }
  float sx = hash(vec2(frame, 9.1));
  float sOn = step(1.0 - uScratch * 0.5, hash(vec2(frame, 3.3)));
  c = mix(c, vec3(0.85), smoothstep(0.0015, 0.0, abs(uv.x - sx)) * sOn * 0.6);
  gl_FragColor = vec4(clamp(c, 0.0, 1.0) * a, a);
}
"""

    // ───────────────────────── blur / distortion / stylize ─────────────────────────

    const val DIRBLUR = COMMON + """
uniform sampler2D uTex;
uniform vec2 uStep;
void main() {
  vec4 acc = vec4(0.0);
  for (int i = 0; i < 17; i++) {
    float k = float(i) - 8.0;
    acc += texture2D(uTex, vUv + uStep * k);
  }
  gl_FragColor = acc / 17.0;
}
"""

    const val ZOOMBLUR = COMMON + """
uniform sampler2D uTex;
uniform vec2 uCenter;
uniform float uAmount;
void main() {
  vec4 acc = vec4(0.0);
  vec2 d = uCenter - vUv;
  for (int i = 0; i < 16; i++) {
    acc += texture2D(uTex, vUv + d * uAmount * 0.3 * float(i) / 15.0);
  }
  gl_FragColor = acc / 16.0;
}
"""

    const val CHROMAB = COMMON + """
uniform sampler2D uTex;
uniform float uAmount;
uniform float uAngle;
void main() {
  vec2 dir = vec2(cos(uAngle), sin(uAngle));
  float r = length(vUv - 0.5) + 0.3;
  vec2 off = dir * uAmount * 0.012 * r;
  vec4 s = texture2D(uTex, vUv);
  vec4 sr = texture2D(uTex, vUv + off);
  vec4 sb = texture2D(uTex, vUv - off);
  float a = max(s.a, max(sr.a, sb.a));
  gl_FragColor = vec4(sr.r, s.g, sb.b, a);
}
"""

    const val WAVE = COMMON + """
uniform sampler2D uTex;
uniform float uAmp;
uniform float uFreq;
uniform float uSpeed;
uniform float uAngle;
uniform float uTime;
uniform float uAspect;
uniform float uPhase;
uniform int uType;
uniform int uPin;
float vnoise(float x) {
  float i = floor(x); float f = fract(x);
  float a = hash(vec2(i, 3.7)); float b = hash(vec2(i + 1.0, 3.7));
  return mix(a, b, f * f * (3.0 - 2.0 * f)) * 2.0 - 1.0;
}
float wave(float x) {
  float f = fract(x);
  if (uType == 1) return f < 0.5 ? 1.0 : -1.0;
  if (uType == 2) return 1.0 - 4.0 * abs(f - 0.5);
  if (uType == 3) return 2.0 * f - 1.0;
  if (uType == 4) { if (f < 0.5) { float y = 4.0 * f - 1.0; return sqrt(max(0.0, 1.0 - y * y)); } float y = 4.0 * f - 3.0; return -sqrt(max(0.0, 1.0 - y * y)); }
  if (uType == 5) { float y = 2.0 * f - 1.0; return sqrt(max(0.0, 1.0 - y * y)) * 2.0 - 1.0; }
  if (uType == 6) return vnoise(x * 2.0);
  return sin(x * 6.2831853);
}
void main() {
  vec2 dir = vec2(cos(uAngle), sin(uAngle));
  vec2 perp = vec2(-dir.y, dir.x);
  float ph = dot(vUv * vec2(uAspect, 1.0), dir) * uFreq - uTime * uSpeed + uPhase;
  float pin = 1.0;
  float ex = smoothstep(0.0, 0.12, min(vUv.x, 1.0 - vUv.x));
  float ey = smoothstep(0.0, 0.12, min(vUv.y, 1.0 - vUv.y));
  if (uPin == 1) pin = ex * ey;
  if (uPin == 2) pin = ex;
  if (uPin == 3) pin = ey;
  vec2 d = perp * uAmp * wave(ph) * pin;
  gl_FragColor = texture2D(uTex, vUv + vec2(d.x / uAspect, d.y));
}
"""

    /**
     * Transitions. uA / uB are the outgoing / incoming layers already composited onto
     * transparent canvases (premultiplied); the result is laid over uBase.
     */
    const val TRANSITION = COMMON + """
uniform sampler2D uBase;
uniform sampler2D uA;
uniform sampler2D uB;
uniform float uP;
uniform int uType;
uniform vec2 uDir;
uniform float uAspect;
uniform float uSoft;
uniform vec2 uTexel;
const float PI = 3.14159265;
vec4 SA(vec2 uv) { return (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) ? vec4(0.0) : texture2D(uA, uv); }
vec4 SB(vec2 uv) { return (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) ? vec4(0.0) : texture2D(uB, uv); }
vec2 mirror(vec2 uv) { return 1.0 - abs(1.0 - mod(abs(uv), 2.0)); }
float ease(float x) { return x * x * (3.0 - 2.0 * x); }
float easeInOutCubic(float x) { return x < 0.5 ? 4.0 * x * x * x : 1.0 - pow(-2.0 * x + 2.0, 3.0) / 2.0; }
float vn(vec2 p) {
  vec2 i = floor(p); vec2 f = fract(p); vec2 u = f * f * (3.0 - 2.0 * f);
  return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), u.x), mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), u.x), u.y);
}
float fbm(vec2 p) { float v = 0.0; float a = 0.5; for (int i = 0; i < 5; i++) { v += a * vn(p); p *= 2.03; a *= 0.5; } return v; }
vec4 blurA(vec2 uv, float r) {
  vec4 s = vec4(0.0);
  for (int i = 0; i < 12; i++) { float a = float(i) * 2.39996; float rr = r * sqrt((float(i) + 0.5) / 12.0); s += texture2D(uA, mirror(uv + vec2(cos(a) / uAspect, sin(a)) * rr)); }
  return s / 12.0;
}
vec4 blurB(vec2 uv, float r) {
  vec4 s = vec4(0.0);
  for (int i = 0; i < 12; i++) { float a = float(i) * 2.39996; float rr = r * sqrt((float(i) + 0.5) / 12.0); s += texture2D(uB, mirror(uv + vec2(cos(a) / uAspect, sin(a)) * rr)); }
  return s / 12.0;
}
vec4 over(vec4 top, vec4 bot) { return top + bot * (1.0 - top.a); }

vec4 trans(vec2 uv) {
  float p = uP;
  vec4 a; vec4 b;
  if (uType == 0) return mix(SA(uv), SB(uv), p);
  if (uType == 1) return p < 0.5 ? SA(uv) * (1.0 - ease(p * 2.0)) : SB(uv) * ease(p * 2.0 - 1.0);
  if (uType == 2) {
    vec4 w = vec4(1.0);
    return p < 0.5 ? mix(SA(uv), w, ease(p * 2.0)) : mix(w, SB(uv), ease(p * 2.0 - 1.0));
  }
  if (uType == 3) {
    float r = sin(p * PI) * 0.045;
    return mix(blurA(uv, r), blurB(uv, r), ease(p));
  }
  if (uType == 4) {
    vec4 bb = SB(uv);
    float lum = luma(unpremul(bb));
    float s = 0.02 + uSoft * 0.3;
    float th = 1.0 + s - p * (1.0 + 2.0 * s);
    return mix(SA(uv), bb, smoothstep(th - s, th + s, lum));
  }
  if (uType == 5) {
    float n = fbm(uv * vec2(uAspect, 1.0) * 3.5);
    float s = 0.02 + uSoft * 0.25;
    float th = p * (1.0 + 2.0 * s) - s;
    float m = smoothstep(n - s, n + s, th);
    return mix(SA(uv), SB(uv), m);
  }
  if (uType == 6) {
    float n = fbm(uv * vec2(uAspect, 1.0) * 2.2 + vec2(p * 0.6, 0.0));
    float th = p * 1.3 - 0.15;
    float m = smoothstep(n - 0.12, n + 0.12, th);
    vec4 c = mix(SA(uv), SB(uv), m);
    float edge = exp(-abs(n - th) * 9.0) * sin(p * PI);
    vec3 burn = vec3(1.0, 0.55, 0.18) * edge * 2.2 + vec3(1.0, 0.85, 0.6) * pow(sin(p * PI), 3.0) * 0.6;
    c.rgb += burn * max(c.a, edge);
    c.a = max(c.a, clamp(edge, 0.0, 1.0));
    return c;
  }
  if (uType == 7) {
    vec4 c = p < 0.5 ? SA(uv) : SB(uv);
    float f = pow(1.0 - abs(p * 2.0 - 1.0), 2.5);
    c.rgb = mix(c.rgb, vec3(1.0), f);
    c.a = max(c.a, f);
    return c;
  }
  if (uType == 8) {
    vec4 c = mix(SA(uv), SB(uv), ease(p));
    float g = sin(p * PI);
    vec2 q = uv * vec2(uAspect, 1.0);
    float l1 = exp(-length(q - vec2(-0.2 + p * 1.6 * uAspect, 0.3)) * 2.2);
    float l2 = exp(-length(q - vec2(uAspect * (1.2 - p * 1.3), 0.8)) * 2.8);
    vec3 leak = vec3(1.0, 0.45, 0.12) * l1 + vec3(1.0, 0.8, 0.35) * l2;
    c.rgb = 1.0 - (1.0 - c.rgb) * (1.0 - clamp(leak * g * 1.6, 0.0, 1.0));
    c.a = max(c.a, clamp((l1 + l2) * g, 0.0, 1.0));
    return c;
  }
  if (uType == 9 || uType == 10) {
    float e = uType == 9 ? easeInOutCubic(p) : ease(p);
    vec2 d = uDir;
    if (uType == 10) return SA(uv + d * e) + SB(uv + d * (e - 1.0));
    float bl = sin(p * PI) * 0.18;
    vec4 s = vec4(0.0);
    for (int i = 0; i < 14; i++) {
      float o = (float(i) / 13.0 - 0.5) * bl;
      vec2 u2 = uv + d * (e + o);
      s += SA(u2) + SB(u2 - d);
    }
    return s / 14.0;
  }
  if (uType == 11) {
    float e = easeInOutCubic(p);
    return over(SB(uv + uDir * (e - 1.0)), SA(uv));
  }
  if (uType == 12 || uType == 13) {
    bool zin = uType == 12;
    vec2 c0 = vec2(0.5);
    float e = easeInOutCubic(p);
    float z = zin ? (p < 0.5 ? 1.0 + e * 2.0 : 1.0 + (1.0 - e) * 2.0) : (p < 0.5 ? 1.0 / (1.0 + e * 1.5) : 1.0 / (1.0 + (1.0 - e) * 1.5));
    float bl = sin(p * PI) * 0.22;
    vec4 s = vec4(0.0);
    for (int i = 0; i < 12; i++) {
      float k = 1.0 - bl * float(i) / 12.0;
      vec2 u2 = mirror(c0 + (uv - c0) / z * k);
      s += p < 0.5 ? texture2D(uA, u2) : texture2D(uB, u2);
    }
    vec4 r = s / 12.0;
    return r;
  }
  if (uType == 14) {
    float e = easeInOutCubic(p);
    float ang = (p < 0.5 ? e : e - 1.0) * PI * 1.2;
    float bl = sin(p * PI) * 0.35;
    float z = 1.0 + sin(p * PI) * 0.6;
    vec4 s = vec4(0.0);
    for (int i = 0; i < 12; i++) {
      float aa = ang - bl * float(i) / 12.0;
      vec2 d = (uv - 0.5) * vec2(uAspect, 1.0) / z;
      d = vec2(d.x * cos(aa) - d.y * sin(aa), d.x * sin(aa) + d.y * cos(aa));
      vec2 u2 = mirror(0.5 + d / vec2(uAspect, 1.0));
      s += p < 0.5 ? texture2D(uA, u2) : texture2D(uB, u2);
    }
    return s / 12.0;
  }
  if (uType == 15) {
    float s = 0.005 + uSoft * 0.2;
    float x = dot(uv - 0.5, -uDir) + 0.5;
    float m = smoothstep(x - s, x + s, p * (1.0 + 2.0 * s) - s);
    return mix(SA(uv), SB(uv), m);
  }
  if (uType == 16) {
    float s = 0.005 + uSoft * 0.2;
    float r = length((uv - 0.5) * vec2(uAspect, 1.0)) / length(vec2(uAspect, 1.0) * 0.5);
    float m = smoothstep(r - s, r + s, ease(p) * (1.0 + 2.0 * s) - s);
    return mix(SA(uv), SB(uv), m);
  }
  if (uType == 17) {
    float s = 0.003 + uSoft * 0.08;
    vec2 d = (uv - 0.5) * vec2(uAspect, 1.0);
    float ang = fract(atan(d.x, d.y) / (2.0 * PI) + 0.5);
    float m = smoothstep(ang - s, ang + s, p * (1.0 + 2.0 * s) - s);
    return mix(SA(uv), SB(uv), m);
  }
  if (uType == 18) {
    float g = sin(p * PI);
    float seed = floor(p * 24.0);
    vec2 blk = floor(uv * vec2(6.0, 28.0));
    float r = hash(blk + seed);
    float off = r > 0.55 ? (hash(blk * 1.7 + seed) - 0.5) * 0.35 * g : 0.0;
    vec2 u2 = vec2(uv.x + off, uv.y);
    float sh = 0.03 * g;
    bool useB = p > 0.5 ? hash(vec2(seed, 3.0)) > 0.15 : hash(vec2(seed, 7.0)) > 0.85;
    vec4 cr = useB ? texture2D(uB, mirror(u2 + vec2(sh, 0.0))) : texture2D(uA, mirror(u2 + vec2(sh, 0.0)));
    vec4 cg = useB ? texture2D(uB, mirror(u2)) : texture2D(uA, mirror(u2));
    vec4 cb = useB ? texture2D(uB, mirror(u2 - vec2(sh, 0.0))) : texture2D(uA, mirror(u2 - vec2(sh, 0.0)));
    vec4 c = vec4(cr.r, cg.g, cb.b, max(cg.a, max(cr.a, cb.a)));
    float line = step(0.97, hash(vec2(floor(uv.y * 120.0), seed))) * g;
    c.rgb = mix(c.rgb, vec3(1.0) * c.a, line * 0.6);
    return c;
  }
  if (uType == 19) {
    float g = sin(p * PI);
    float cells = mix(1.0, 70.0, g * g);
    vec2 px = cells * uTexel;
    vec2 u2 = px.x > 0.0 ? (floor(uv / px) + 0.5) * px : uv;
    return mix(SA(u2), SB(u2), smoothstep(0.35, 0.65, p));
  }
  if (uType == 20) {
    float g = sin(p * PI);
    vec2 d = (uv - 0.5) * vec2(uAspect, 1.0);
    float r = length(d);
    float k = 1.0 - g * 0.55 * (1.0 - smoothstep(0.0, 0.9, r));
    vec2 u2 = 0.5 + d * k / vec2(uAspect, 1.0);
    float ca = g * 0.012;
    vec2 dir = normalize(d + 1e-5) / vec2(uAspect, 1.0) * ca;
    vec4 a1 = mix(texture2D(uA, mirror(u2)), texture2D(uB, mirror(u2)), ease(p));
    vec4 a2 = mix(texture2D(uA, mirror(u2 + dir)), texture2D(uB, mirror(u2 + dir)), ease(p));
    vec4 a3 = mix(texture2D(uA, mirror(u2 - dir)), texture2D(uB, mirror(u2 - dir)), ease(p));
    return vec4(a2.r, a1.g, a3.b, a1.a);
  }
  return mix(SA(uv), SB(uv), p);
}

void main() {
  vec4 t = trans(vUv);
  t = clamp(t, 0.0, 1.0);
  t.rgb = min(t.rgb, vec3(t.a));
  gl_FragColor = over(t, texture2D(uBase, vUv));
}
"""

    /** Copies a bitmap texture (y down) into an FBO (y up). */
    const val COPY_FLIP = COMMON + """
uniform sampler2D uTex;
void main() { gl_FragColor = texture2D(uTex, vec2(vUv.x, 1.0 - vUv.y)); }
"""

    const val SABER = COMMON + """
uniform sampler2D uTex;
uniform sampler2D uCore;
uniform sampler2D uG1;
uniform sampler2D uG2;
uniform vec3 uColor;
uniform float uGlow;
uniform float uCoreB;
uniform float uFlick;
uniform float uDist;
uniform float uTime;
uniform float uAspect;
uniform int uMode;
float n2(vec2 p) {
  vec2 i = floor(p); vec2 f = fract(p); vec2 u = f * f * (3.0 - 2.0 * f);
  return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), u.x), mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), u.x), u.y);
}
void main() {
  vec2 p = vUv * vec2(uAspect, 1.0) * 7.0;
  vec2 off = vec2(n2(p + vec2(uTime * 1.3, 0.0)) - 0.5, n2(p + vec2(5.2, uTime * 1.7)) - 0.5);
  off += 0.5 * vec2(n2(p * 2.3 - vec2(uTime * 2.1, 1.0)) - 0.5, n2(p * 2.3 + vec2(9.1, uTime * 2.6)) - 0.5);
  vec2 q = vUv + off * uDist * 0.035;
  float core = texture2D(uCore, q).a;
  float g1 = texture2D(uG1, q).a;
  float g2 = texture2D(uG2, q).a;
  vec3 glow = uColor * (g1 * 1.8 + g2 * 1.3) * uGlow * uFlick;
  vec3 hot = mix(uColor, vec3(1.0), 0.85) * smoothstep(0.05, 0.7, core) * uCoreB * mix(0.7, 1.0, uFlick);
  vec3 add = glow + hot;
  vec4 s = uMode == 1 ? vec4(0.0) : texture2D(uTex, vUv);
  float a = clamp(s.a + max(max(add.r, add.g), add.b), 0.0, 1.0);
  vec3 rgb = s.rgb + add;
  gl_FragColor = vec4(min(rgb, vec3(a)), a);
}
"""

    const val BULGE = COMMON + """
uniform sampler2D uTex;
uniform vec2 uCenter;
uniform float uAmount;
uniform float uRadius;
uniform float uAspect;
void main() {
  vec2 p = vUv - uCenter;
  p.x *= uAspect;
  float r = length(p) / max(uRadius, 0.001);
  if (r < 1.0 && r > 0.0001) {
    float nr = pow(r, 1.0 + uAmount * 0.8);
    p *= nr / r;
  }
  p.x /= uAspect;
  gl_FragColor = texture2D(uTex, uCenter + p);
}
"""

    const val SHARPEN = COMMON + """
uniform sampler2D uTex;
uniform vec2 uTexel;
uniform float uAmount;
void main() {
  vec4 s = texture2D(uTex, vUv);
  vec4 n = texture2D(uTex, vUv + vec2(uTexel.x, 0.0)) + texture2D(uTex, vUv - vec2(uTexel.x, 0.0))
         + texture2D(uTex, vUv + vec2(0.0, uTexel.y)) + texture2D(uTex, vUv - vec2(0.0, uTexel.y));
  vec4 o = clamp(s + (s - n * 0.25) * uAmount * 2.0, 0.0, 1.0);
  gl_FragColor = vec4(min(o.rgb, vec3(o.a)), o.a);
}
"""

    const val POSTERIZE = COMMON + """
uniform sampler2D uTex;
uniform float uLevels;
void main() {
  vec4 s = texture2D(uTex, vUv);
  vec3 c = floor(unpremul(s) * uLevels + 0.5) / uLevels;
  gl_FragColor = vec4(c * s.a, s.a);
}
"""

    const val MOSAIC = COMMON + """
uniform sampler2D uTex;
uniform vec2 uCell;
void main() {
  vec2 uv = (floor(vUv / uCell) + 0.5) * uCell;
  gl_FragColor = texture2D(uTex, uv);
}
"""

    const val VIGNETTE = COMMON + """
uniform sampler2D uTex;
uniform float uAmount;
uniform float uFeather;
uniform float uRound;
uniform float uAspect;
void main() {
  vec4 s = texture2D(uTex, vUv);
  vec2 d = vUv - 0.5;
  d.x *= mix(1.0, uAspect, uRound);
  float r = length(d) / length(vec2(0.5 * mix(1.0, uAspect, uRound), 0.5));
  float v = 1.0 - uAmount * smoothstep(1.0 - uFeather * 0.9 - 0.05, 1.05, r);
  gl_FragColor = vec4(s.rgb * v, s.a);
}
"""
}
