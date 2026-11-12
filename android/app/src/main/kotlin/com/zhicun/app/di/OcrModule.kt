package com.zhicun.app.di

import com.zhicun.app.data.ocr.MlKitOcrEngine
import com.zhicun.app.domain.ocr.OcrEngine
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * OCR 相关依赖（M7）。
 *
 * 用 `@Binds` 而不是 `@Provides`：实现类自带 `@Singleton @Inject constructor(@ApplicationContext ...)`，
 * 换引擎（例如将来的端侧小模型）只需换这一行绑定，`OcrWorker` 不用动。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class OcrModule {

    @Binds
    abstract fun bindOcrEngine(impl: MlKitOcrEngine): OcrEngine
}
