# ==========================================
# Komorebi アプリの難読化回避ルール
# ==========================================

# 1. データモデルの保護（JSONパースエラーを防ぐ）
# com.beeregg2001.komorebi.data.model パッケージ内のすべてのクラスと、
# その中にあるすべての変数・メソッドの名前を変換・削除しないようにする。
-keep class com.beeregg2001.komorebi.data.model.** { *; }

# (もしAPIレスポンス用のクラスが別パッケージにある場合はそれも追加)
# -keep class com.beeregg2001.komorebi.data.api.response.** { *; }

# 2. Enum クラスの保護（品質やカテゴリーなどのEnumが壊れるのを防ぐ）
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}