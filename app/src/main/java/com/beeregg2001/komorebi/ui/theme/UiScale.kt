package com.beeregg2001.komorebi.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/**
 * 「UI の大きさ」設定（`SettingsRepository.UI_SCALE`）の倍率。`MainActivity` が提供する。
 *
 * 通常は `MainActivity` で [LocalDensity] を直接上書きしているため、この値を個別に参照する
 * 必要はない。ダイアログの内側など、[LocalDensity] の上書きが引き継がれない場所で
 * [ProvideUiScale] を通して再適用するために公開している。
 */
val LocalUiScale = compositionLocalOf { 1f }

/**
 * ダイアログの内側で「UI の大きさ」設定を再適用する。
 *
 * Compose の `Dialog` / `Popup`（`AlertDialog` も内部で `Dialog` を使う）は独自の ComposeView を
 * 作り、その中で [LocalDensity] が**端末本来の density で提供し直される**。そのため
 * `MainActivity` で行っている density の上書きはダイアログの内側まで引き継がれず、
 * UI の大きさを変えてもダイアログだけが等倍で表示されてしまう。
 *
 * [LocalUiScale] は通常の CompositionLocal として継承されるので、ダイアログの内側で
 * この倍率を読み直し、その場の density へ改めて掛けることで表示を揃える。
 *
 * 画面の上に `Box` で重ねているだけのダイアログ（`SyncErrorDialog` や `RobustUpdateDialog` など）は
 * 同じコンポジションに属していて density の上書きが効いているため、これで包む必要はない。
 */
@Composable
fun ProvideUiScale(content: @Composable () -> Unit) {
    val scale = LocalUiScale.current
    val density = LocalDensity.current
    if (scale == 1f) {
        content()
        return
    }
    CompositionLocalProvider(
        LocalDensity provides Density(
            density = density.density * scale,
            fontScale = density.fontScale
        )
    ) {
        content()
    }
}
