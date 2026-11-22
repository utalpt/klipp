/*
 * Klipp Lab: an export may ask the video encoder for more than the bitrate it lists.
 *
 * The phone's media_codecs XML lists a bitrate range per encoder (the Xiaomi 17 Ultra's Dolby Vision encoder:
 * 1-160000000), and DefaultEncoderFactory clamped the requested bitrate to it. The encoders take more – the camera
 * (with George Kiarie's mods) records Dolby Vision at 200-240 Mbit/s – so with [ignoreBitrateCap] the bitrate asked for
 * is passed on as it is. An encoder that refuses it fails at configure, before any frame is written.
 */
package androidx.media3.transformer;

import androidx.media3.common.util.UnstableApi;

@UnstableApi
public final class KlippEncoderLimits {
  /** Pass the requested video bitrate on above the encoder's listed range (set by the app before an export). */
  public static volatile boolean ignoreBitrateCap = false;

  private KlippEncoderLimits() {}
}
