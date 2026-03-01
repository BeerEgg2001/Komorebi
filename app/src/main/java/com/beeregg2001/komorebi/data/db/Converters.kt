package com.beeregg2001.komorebi.data.db

import androidx.room.TypeConverter
import com.beeregg2001.komorebi.data.model.EpgGenre // ★忘れずにインポートしてください
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class Converters {
    private val gson = Gson()

    // --- 既存のMap用コンバーター ---
    @TypeConverter
    fun fromStringMap(value: Map<String, String>?): String? {
        return value?.let { gson.toJson(it) }
    }

    @TypeConverter
    fun toStringMap(value: String?): Map<String, String>? {
        if (value == null) return null
        val mapType = object : TypeToken<Map<String, String>>() {}.type
        return gson.fromJson(value, mapType)
    }

    // --- ★新規追加: 録画番組のジャンルリスト用コンバーター ---
    @TypeConverter
    fun fromGenreList(value: List<EpgGenre>?): String? {
        if (value == null) return null
        val type = object : TypeToken<List<EpgGenre>>() {}.type
        return gson.toJson(value, type)
    }

    @TypeConverter
    fun toGenreList(value: String?): List<EpgGenre>? {
        if (value == null) return null
        val type = object : TypeToken<List<EpgGenre>>() {}.type
        return gson.fromJson(value, type)
    }
}