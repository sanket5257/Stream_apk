#extension GL_OES_EGL_image_external : require
precision mediump float;

// Video overlay (external/OES texture from the MediaPlayer surface)
uniform samplerExternalOES uObject;
// Background already drawn (camera + lower overlays)
uniform sampler2D uSampler;
uniform float uAlpha;
// Chroma key controls
uniform float uSensitive;   // ~ key radius in RGB space (0..1.7)
uniform vec3 uChromaColor;  // key color, e.g. green (0,1,0)

varying vec2 vTextureCoord;
varying vec2 vTextureObjectCoord;

void main() {
  vec4 background = texture2D(uSampler, vTextureCoord);

  // Outside the overlay's box -> just show the background.
  if (vTextureObjectCoord.x < 0.0 || vTextureObjectCoord.x > 1.0 ||
      vTextureObjectCoord.y < 0.0 || vTextureObjectCoord.y > 1.0) {
    gl_FragColor = background;
    return;
  }

  vec4 video = texture2D(uObject, vTextureObjectCoord);

  // Distance of this video pixel from the key colour.
  float d = distance(video.rgb, uChromaColor);
  // removal = 1 near the key colour (cut it out), 0 far from it (keep the video).
  float removal = 1.0 - smoothstep(uSensitive * 0.5, uSensitive, d);
  float visible = (1.0 - removal) * video.a * uAlpha;

  gl_FragColor = vec4(mix(background.rgb, video.rgb, visible), 1.0);
}
