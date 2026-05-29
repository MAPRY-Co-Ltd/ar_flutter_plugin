package io.carius.lars.ar_flutter_plugin

import android.app.Activity
import android.content.Context
import android.view.View
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.platform.PlatformView
import io.github.sceneview.ar.ARScene

// =============================================================================
// Phase 0 足場検証用の最小スタブ実装。
// SceneView 4.16.x は Compose 専用のため、PlatformView の View として
// ComposeView をホストし、その中で @Composable ARScene を描画する。
// MethodChannel ハンドラは未実装（Phase 2 以降で状態アダプタ層を実装する）。
// =============================================================================
internal class AndroidARView(
        val activity: Activity,
        context: Context,
        messenger: BinaryMessenger,
        id: Int,
        creationParams: Map<String?, Any?>?
) : PlatformView {
    private val sessionManagerChannel: MethodChannel = MethodChannel(messenger, "arsession_$id")
    private val objectManagerChannel: MethodChannel = MethodChannel(messenger, "arobjects_$id")
    private val anchorManagerChannel: MethodChannel = MethodChannel(messenger, "aranchors_$id")

    private val composeView: ComposeView = ComposeView(context).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            // 最小: デフォルト引数のみで AR プレビューを表示
            ARScene(modifier = Modifier.fillMaxSize())
        }
    }

    init {
        // チャネルは生成のみ（Dart 側がアタッチしてもクラッシュしないよう no-op）
        val noop = MethodChannel.MethodCallHandler { _: MethodCall, result: MethodChannel.Result ->
            result.notImplemented()
        }
        sessionManagerChannel.setMethodCallHandler(noop)
        objectManagerChannel.setMethodCallHandler(noop)
        anchorManagerChannel.setMethodCallHandler(noop)
    }

    override fun getView(): View = composeView

    override fun dispose() {
        sessionManagerChannel.setMethodCallHandler(null)
        objectManagerChannel.setMethodCallHandler(null)
        anchorManagerChannel.setMethodCallHandler(null)
    }
}
