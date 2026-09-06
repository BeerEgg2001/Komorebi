# ==========================================
# Komorebi アプリの難読化回避ルール
# ==========================================

# 1. データモデルの保護（JSONパースエラーを防ぐ）
# com.beeregg2001.komorebi.data.model パッケージ内のすべてのクラスと、
# その中にあるすべての変数・メソッドの名前を変換・削除しないようにする。
-keep class com.beeregg2001.komorebi.data.model.** { *; }

# JNI が完全修飾名とコンストラクタ署名で生成する字幕モデルを保持する。
-keep class com.beeregg2001.komorebi.ui.subtitle.NativeCaptionCue { *; }
-keep class com.beeregg2001.komorebi.ui.subtitle.NativeCaptionImage { *; }
-keep class com.beeregg2001.komorebi.NativeLib { native <methods>; }

# (もしAPIレスポンス用のクラスが別パッケージにある場合はそれも追加)
# -keep class com.beeregg2001.komorebi.data.api.response.** { *; }

# 2. Enum クラスの保護（品質やカテゴリーなどのEnumが壊れるのを防ぐ）
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Ktorが参照するAndroid非対応のJava Management APIの警告を無視する
-dontwarn java.lang.management.**

# Ktor内部のデバッグ機能関連の欠落警告を無視する
-dontwarn io.ktor.util.debug.**
