package com.zhicun.app.di

import com.zhicun.app.data.audio.MediaMetadataAudioDurationProbe
import com.zhicun.app.data.audio.MediaPlayerAudioPlayer
import com.zhicun.app.data.audio.MediaRecorderVoiceRecorder
import com.zhicun.app.domain.audio.AudioDurationProbe
import com.zhicun.app.domain.audio.AudioPlayer
import com.zhicun.app.domain.audio.VoiceRecorder
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * 音频能力绑定（M9）。
 *
 * 实现类自带 `@Singleton @Inject constructor`，这里只决定"用哪个实现"：
 * 将来换 Opus 编码或换成 ExoPlayer，只改这两行，ViewModel 与 Worker 不用动。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AudioModule {

    @Binds
    abstract fun bindVoiceRecorder(impl: MediaRecorderVoiceRecorder): VoiceRecorder

    @Binds
    abstract fun bindAudioPlayer(impl: MediaPlayerAudioPlayer): AudioPlayer

    @Binds
    abstract fun bindAudioDurationProbe(impl: MediaMetadataAudioDurationProbe): AudioDurationProbe
}
