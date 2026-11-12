package com.zhicun.app.di

import com.zhicun.app.data.diagnostic.AndroidDiagnosticLog
import com.zhicun.app.domain.diagnostic.DiagnosticLog
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** 领域层诊断出口的绑定（2026-10-22 审核 P2：空 catch 整改）。 */
@Module
@InstallIn(SingletonComponent::class)
abstract class DiagnosticModule {

    @Binds
    abstract fun bindDiagnosticLog(impl: AndroidDiagnosticLog): DiagnosticLog
}
