/*
 * Copyright 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.exoplayer2.effect;

/**
 * An interface for 1 dimensional convolution functions.
 *
 * <p>The domain defines the region over which the function operates, in pixels.
 *
 * @deprecated com.google.android.exoplayer2 is deprecated. Please migrate to androidx.media3 (which
 *     contains the same ExoPlayer code). See <a
 *     href="https://developer.android.com/guide/topics/media/media3/getting-started/migration-guide">the
 *     migration guide</a> for more details, including a script to help with the migration.
 */
@Deprecated
public interface ConvolutionFunction1D {

  /** Returns the start of the domain. */
  float domainStart();

  /** Returns the end of the domain. */
  float domainEnd();

  /** Returns the width of the domain. */
  default float width() {
    return domainEnd() - domainStart();
  }

  /** Returns the value of the function at the {@code samplePosition}. */
  float value(float samplePosition);
}