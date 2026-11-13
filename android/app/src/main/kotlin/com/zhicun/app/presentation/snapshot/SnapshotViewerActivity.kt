package com.zhicun.app.presentation.snapshot

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.zhicun.app.R
import com.zhicun.app.localizedContext
import dagger.hilt.android.AndroidEntryPoint
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 网页快照查看页（C：离线浏览的主体）。
 *
 * ## 为什么是读文件 + loadDataWithBaseURL，而不是 WebView.loadUrl("file://…")
 * Android 9 起明文 file:// 被默认禁用，而重新允许 `allowFileAccess = true` 会把
 * "加载这一份本地文件"扩大成"页面里任何代码都能再访问 file:// 资源"——
 * 快照来自任意网站，这个口子不能开。把 HTML 读成字符串、以 **null base URL** 注入后：
 * 页面里的相对资源、file:// 链接、网络请求全部天然失效，正好就是"离线快照"该有的样子；
 * 正文图片是唯一的例外——保存时已内嵌为 data URI（见 SnapshotImageInliner），
 * 禁网也能显示。
 *
 * ## 为什么安全开关全部显式关掉
 * `javaScriptEnabled`、`allowFileAccess`、`allowContentAccess`、`blockNetworkLoads`
 * 四个都显式置为禁用。快照是"存下当时的文字与排版"，不是复刻一个浏览器；
 * 其中 JavaScript 是最危险的一项——即使默认就是 false，也写成显式的，
 * 免得未来有人"顺手打开"时没有一道声明挡着。
 *
 * 编码：快照文件保留页面原始字节（可能是 GBK），注入前用 Jsoup 按自动检测解码、
 * 统一转成 UTF-8 并改写 meta charset，避免 WebView 用错编码显示乱码。
 */
@AndroidEntryPoint
class SnapshotViewerActivity : ComponentActivity() {

    /** 快照页的标题、关闭按钮与错误提示都取本页资源（P11-05）。 */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(localizedContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val path = intent.getStringExtra(EXTRA_SNAPSHOT_PATH).orEmpty()
        // produceState：文件读取在 IO 线程，完成后触发重组渲染 WebView
        setContent {
            MaterialTheme {
                SnapshotViewerScreen(path = path, onBack = { finish() })
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun SnapshotViewerScreen(path: String, onBack: () -> Unit) {
        val state by produceState<SnapshotUiState>(SnapshotUiState.Loading, path) {
            value = if (path.isBlank()) {
                SnapshotUiState.Unavailable
            } else {
                try {
                    SnapshotUiState.Ready(html = loadSnapshotUtf8(path))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "快照读取失败 path=$path", e)
                    SnapshotUiState.Unavailable
                }
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.snapshot_viewer_title)) },
                    navigationIcon = {
                        TextButton(onClick = onBack) {
                            Text(text = stringResource(R.string.snapshot_close))
                        }
                    },
                )
            },
        ) { padding ->
            when (val current = state) {
                SnapshotUiState.Loading -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.snapshot_loading),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                SnapshotUiState.Unavailable -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.snapshot_unavailable),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(24.dp),
                    )
                }

                is SnapshotUiState.Ready -> SnapshotWebView(
                    html = current.html,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                )
            }
        }
    }

    /** 只读、禁脚本、禁一切外部访问的 WebView。 */
    @Composable
    private fun SnapshotWebView(html: String, modifier: Modifier = Modifier) {
        AndroidView(
            modifier = modifier,
            factory = { context ->
                WebView(context).apply {
                    // 见类 KDoc：四个开关全部显式禁用，快照不是浏览器
                    settings.javaScriptEnabled = false
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.blockNetworkLoads = true
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    webViewClient = WebViewClient()
                }
            },
            update = { webView -> webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null) },
        )
    }

    /** 原始字节 → 自动检测编码 → 统一 UTF-8（改写 meta charset），供 WebView 直接注入。 */
    private suspend fun loadSnapshotUtf8(path: String): String = withContext(Dispatchers.IO) {
        val file = File(path)
        if (!file.exists()) throw IOException("快照文件不存在：$path")
        val bytes = file.readBytes()
        val document = org.jsoup.Jsoup.parse(ByteArrayInputStream(bytes), null, "")
        // 输出统一转 UTF-8；原文的 charset 声明（可能是 gbk）必须摘掉，
        // 否则 WebView 会按旧声明去解码已经转成 UTF-8 的内容，整页乱码
        document.select("meta[charset], meta[http-equiv=Content-Type]").remove()
        document.html()
    }

    private sealed interface SnapshotUiState {
        data object Loading : SnapshotUiState
        data object Unavailable : SnapshotUiState
        data class Ready(val html: String) : SnapshotUiState
    }

    companion object {
        private const val TAG = "SnapshotViewer"

        /** 快照文件的绝对路径（App 私有目录内，来自本应用的 Asset 行）。 */
        const val EXTRA_SNAPSHOT_PATH = "snapshot_path"
    }
}
