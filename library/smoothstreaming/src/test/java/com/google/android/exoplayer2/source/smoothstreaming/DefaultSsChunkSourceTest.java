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
package com.google.android.exoplayer2.source.smoothstreaming;

import static com.google.common.truth.Truth.assertThat;

import android.net.Uri;
import android.os.SystemClock;
import androidx.annotation.Nullable;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.LoadingInfo;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.source.TrackGroup;
import com.google.android.exoplayer2.source.chunk.ChunkHolder;
import com.google.android.exoplayer2.source.chunk.MediaChunk;
import com.google.android.exoplayer2.source.smoothstreaming.manifest.SsManifest;
import com.google.android.exoplayer2.source.smoothstreaming.manifest.SsManifestParser;
import com.google.android.exoplayer2.testutil.FakeDataSource;
import com.google.android.exoplayer2.testutil.TestUtil;
import com.google.android.exoplayer2.trackselection.AdaptiveTrackSelection;
import com.google.android.exoplayer2.upstream.CmcdConfiguration;
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter;
import com.google.android.exoplayer2.upstream.LoaderErrorThrower;
import com.google.android.exoplayer2.util.Assertions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import java.io.IOException;
import java.time.Duration;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.shadows.ShadowSystemClock;

/** Unit test for {@link DefaultSsChunkSource}. */
@RunWith(AndroidJUnit4.class)
public class DefaultSsChunkSourceTest {
  private static final String SAMPLE_ISMC_1 = "media/smooth-streaming/sample_ismc_1";

  @Test
  public void getNextChunk_chunkSourceWithDefaultCmcdConfiguration_setsCmcdHttpRequestHeaders()
      throws Exception {
    CmcdConfiguration.Factory cmcdConfigurationFactory = CmcdConfiguration.Factory.DEFAULT;
    MediaItem mediaItem = new MediaItem.Builder().setMediaId("mediaId").build();
    CmcdConfiguration cmcdConfiguration =
        cmcdConfigurationFactory.createCmcdConfiguration(mediaItem);
    SsChunkSource chunkSource = createSsChunkSource(/* numberOfTracks= */ 2, cmcdConfiguration);
    ChunkHolder output = new ChunkHolder();

    chunkSource.getNextChunk(
        new LoadingInfo.Builder().setPlaybackPositionUs(0).setPlaybackSpeed(1.0f).build(),
        /* loadPositionUs= */ 0,
        /* queue= */ ImmutableList.of(),
        output);

    assertThat(output.chunk.dataSpec.httpRequestHeaders)
        .containsExactly(
            "CMCD-Object",
            "br=308,d=1968,ot=v,tb=1536",
            "CMCD-Request",
            "bl=0,dl=0,mtp=1000,nor=\"..%2FFragments(video%3D19680000)\",su",
            "CMCD-Session",
            "cid=\"mediaId\",sf=s,sid=\"" + cmcdConfiguration.sessionId + "\",st=v");

    chunkSource.getNextChunk(
        new LoadingInfo.Builder().setPlaybackPositionUs(3_000_000).setPlaybackSpeed(2.0f).build(),
        /* loadPositionUs= */ 4_000_000,
        /* queue= */ ImmutableList.of((MediaChunk) output.chunk),
        output);

    assertThat(output.chunk.dataSpec.httpRequestHeaders)
        .containsExactly(
            "CMCD-Object",
            "br=308,d=898,ot=v,tb=1536",
            "CMCD-Request",
            "bl=1000,dl=500,mtp=1000,nor=\"..%2FFragments(video%3D28660000)\"",
            "CMCD-Session",
            "cid=\"mediaId\",pr=2.00,sf=s,sid=\"" + cmcdConfiguration.sessionId + "\",st=v");
  }

  @Test
  public void getNextChunk_chunkSourceWithDefaultCmcdConfiguration_setsCorrectBufferStarvationKey()
      throws Exception {
    CmcdConfiguration.Factory cmcdConfigurationFactory = CmcdConfiguration.Factory.DEFAULT;
    MediaItem mediaItem = new MediaItem.Builder().setMediaId("mediaId").build();
    CmcdConfiguration cmcdConfiguration =
        cmcdConfigurationFactory.createCmcdConfiguration(mediaItem);
    SsChunkSource chunkSource = createSsChunkSource(/* numberOfTracks= */ 2, cmcdConfiguration);
    ChunkHolder output = new ChunkHolder();
    LoadingInfo loadingInfo =
        new LoadingInfo.Builder().setPlaybackPositionUs(0).setPlaybackSpeed(1.0f).build();

    chunkSource.getNextChunk(
        loadingInfo, /* loadPositionUs= */ 0, /* queue= */ ImmutableList.of(), output);

    assertThat(output.chunk.dataSpec.httpRequestHeaders).doesNotContainKey("CMCD-Status");

    loadingInfo =
        loadingInfo
            .buildUpon()
            .setPlaybackPositionUs(2_000_000)
            .setLastRebufferRealtimeMs(SystemClock.elapsedRealtime())
            .build();
    ShadowSystemClock.advanceBy(Duration.ofMillis(100));

    chunkSource.getNextChunk(
        loadingInfo, /* loadPositionUs= */ 4_000_000, /* queue= */ ImmutableList.of(), output);

    assertThat(output.chunk.dataSpec.httpRequestHeaders).containsEntry("CMCD-Status", "bs");

    loadingInfo = loadingInfo.buildUpon().setPlaybackPositionUs(6_000_000).build();
    ShadowSystemClock.advanceBy(Duration.ofMillis(100));

    chunkSource.getNextChunk(
        loadingInfo, /* loadPositionUs= */ 8_000_000, /* queue= */ ImmutableList.of(), output);

    assertThat(output.chunk.dataSpec.httpRequestHeaders).doesNotContainKey("CMCD-Status");
  }

  @Test
  public void getNextChunk_chunkSourceWithCustomCmcdConfiguration_setsCmcdHttpRequestHeaders()
      throws Exception {
    CmcdConfiguration.Factory cmcdConfigurationFactory =
        mediaItem -> {
          CmcdConfiguration.RequestConfig cmcdRequestConfig =
              new CmcdConfiguration.RequestConfig() {
                @Override
                public boolean isKeyAllowed(String key) {
                  return !key.equals(CmcdConfiguration.KEY_SESSION_ID);
                }

                @Override
                public int getRequestedMaximumThroughputKbps(int throughputKbps) {
                  return 5 * throughputKbps;
                }
              };

          return new CmcdConfiguration(
              /* sessionId= */ "sessionId",
              /* contentId= */ mediaItem.mediaId + "contentIdSuffix",
              cmcdRequestConfig);
        };
    MediaItem mediaItem = new MediaItem.Builder().setMediaId("mediaId").build();
    CmcdConfiguration cmcdConfiguration =
        cmcdConfigurationFactory.createCmcdConfiguration(mediaItem);
    SsChunkSource chunkSource = createSsChunkSource(/* numberOfTracks= */ 2, cmcdConfiguration);
    ChunkHolder output = new ChunkHolder();

    chunkSource.getNextChunk(
        new LoadingInfo.Builder().setPlaybackPositionUs(0).setPlaybackSpeed(1.0f).build(),
        /* loadPositionUs= */ 0,
        /* queue= */ ImmutableList.of(),
        output);

    assertThat(output.chunk.dataSpec.httpRequestHeaders)
        .containsExactly(
            "CMCD-Object",
            "br=308,d=1968,ot=v,tb=1536",
            "CMCD-Request",
            "bl=0,dl=0,mtp=1000,nor=\"..%2FFragments(video%3D19680000)\",su",
            "CMCD-Session",
            "cid=\"mediaIdcontentIdSuffix\",sf=s,st=v",
            "CMCD-Status",
            "rtp=1500");
  }

  @Test
  public void
      getNextChunk_chunkSourceWithCustomCmcdConfigurationAndCustomData_setsCmcdHttpRequestHeaders()
          throws Exception {
    CmcdConfiguration.Factory cmcdConfigurationFactory =
        mediaItem -> {
          CmcdConfiguration.RequestConfig cmcdRequestConfig =
              new CmcdConfiguration.RequestConfig() {
                @Override
                public ImmutableListMultimap<@CmcdConfiguration.HeaderKey String, String>
                    getCustomData() {
                  return new ImmutableListMultimap.Builder<
                          @CmcdConfiguration.HeaderKey String, String>()
                      .put(CmcdConfiguration.KEY_CMCD_OBJECT, "key-1=1")
                      .put(CmcdConfiguration.KEY_CMCD_REQUEST, "key-2=\"stringValue\"")
                      .put(CmcdConfiguration.KEY_CMCD_SESSION, "key-3=3")
                      .put(CmcdConfiguration.KEY_CMCD_STATUS, "key-4=5.0")
                      .build();
                }
              };

          return new CmcdConfiguration(
              /* sessionId= */ "sessionId", /* contentId= */ mediaItem.mediaId, cmcdRequestConfig);
        };
    MediaItem mediaItem = new MediaItem.Builder().setMediaId("mediaId").build();
    CmcdConfiguration cmcdConfiguration =
        cmcdConfigurationFactory.createCmcdConfiguration(mediaItem);
    SsChunkSource chunkSource = createSsChunkSource(/* numberOfTracks= */ 2, cmcdConfiguration);
    ChunkHolder output = new ChunkHolder();

    chunkSource.getNextChunk(
        new LoadingInfo.Builder().setPlaybackPositionUs(0).setPlaybackSpeed(1.0f).build(),
        /* loadPositionUs= */ 0,
        /* queue= */ ImmutableList.of(),
        output);

    assertThat(output.chunk.dataSpec.httpRequestHeaders)
        .containsExactly(
            "CMCD-Object",
            "br=308,d=1968,key-1=1,ot=v,tb=1536",
            "CMCD-Request",
            "bl=0,dl=0,key-2=\"stringValue\",mtp=1000,nor=\"..%2FFragments(video%3D19680000)\",su",
            "CMCD-Session",
            "cid=\"mediaId\",key-3=3,sf=s,sid=\"" + cmcdConfiguration.sessionId + "\",st=v",
            "CMCD-Status",
            "key-4=5.0");
  }

  @Test
  public void
      getNextChunk_chunkSourceWithCustomCmcdConfigurationAndCustomData_setsCmcdHttpQueryParameters()
          throws Exception {
    CmcdConfiguration.Factory cmcdConfigurationFactory =
        mediaItem -> {
          CmcdConfiguration.RequestConfig cmcdRequestConfig =
              new CmcdConfiguration.RequestConfig() {
                @Override
                public ImmutableListMultimap<@CmcdConfiguration.HeaderKey String, String>
                    getCustomData() {
                  return new ImmutableListMultimap.Builder<
                          @CmcdConfiguration.HeaderKey String, String>()
                      .put(CmcdConfiguration.KEY_CMCD_OBJECT, "key-1=1")
                      .put(CmcdConfiguration.KEY_CMCD_REQUEST, "key-2=\"stringValue\"")
                      .build();
                }
              };

          return new CmcdConfiguration(
              /* sessionId= */ "sessionId",
              /* contentId= */ mediaItem.mediaId,
              cmcdRequestConfig,
              CmcdConfiguration.MODE_QUERY_PARAMETER);
        };
    MediaItem mediaItem = new MediaItem.Builder().setMediaId("mediaId").build();
    CmcdConfiguration cmcdConfiguration =
        cmcdConfigurationFactory.createCmcdConfiguration(mediaItem);
    SsChunkSource chunkSource = createSsChunkSource(/* numberOfTracks= */ 2, cmcdConfiguration);
    ChunkHolder output = new ChunkHolder();

    chunkSource.getNextChunk(
        new LoadingInfo.Builder().setPlaybackPositionUs(0).setPlaybackSpeed(1.0f).build(),
        /* loadPositionUs= */ 0,
        /* queue= */ ImmutableList.of(),
        output);

    assertThat(
            Uri.decode(
                output.chunk.dataSpec.uri.getQueryParameter(
                    CmcdConfiguration.CMCD_QUERY_PARAMETER_KEY)))
        .isEqualTo(
            "bl=0,br=308,cid=\"mediaId\",d=1968,dl=0,key-1=1,key-2=\"stringValue\","
                + "mtp=1000,nor=\"..%2FFragments(video%3D19680000)\",ot=v,sf=s,sid=\"sessionId\","
                + "st=v,su,tb=1536");
  }

  private SsChunkSource createSsChunkSource(
      int numberOfTracks, @Nullable CmcdConfiguration cmcdConfiguration) throws IOException {
    Assertions.checkArgument(numberOfTracks < 6);
    SsManifestParser parser = new SsManifestParser();
    SsManifest manifest =
        parser.parse(
            Uri.parse("https://example.com/test.ismc"),
            TestUtil.getInputStream(ApplicationProvider.getApplicationContext(), SAMPLE_ISMC_1));
    int[] selectedTracks = new int[numberOfTracks];
    Format[] formats = new Format[numberOfTracks];
    for (int i = 0; i < numberOfTracks; i++) {
      selectedTracks[i] = i;
      formats[i] = manifest.streamElements[0].formats[i];
    }
    AdaptiveTrackSelection adaptiveTrackSelection =
        new AdaptiveTrackSelection(
            new TrackGroup(formats),
            selectedTracks,
            new DefaultBandwidthMeter.Builder(ApplicationProvider.getApplicationContext()).build());
    return new DefaultSsChunkSource(
        new LoaderErrorThrower.Placeholder(),
        manifest,
        /* streamElementIndex= */ 0,
        adaptiveTrackSelection,
        new FakeDataSource(),
        cmcdConfiguration);
  }
}
