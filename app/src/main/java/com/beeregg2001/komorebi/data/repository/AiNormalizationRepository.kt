package com.beeregg2001.komorebi.data.repository

import android.util.Log
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.local.dao.AiSeriesDictionaryDao
import com.beeregg2001.komorebi.data.local.entity.AiSeriesDictionaryEntity
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.generationConfig // ★復活
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AiNormalizationRepository @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val dictionaryDao: AiSeriesDictionaryDao,
    private val gson: Gson
) {
    suspend fun normalizeTitles(titles: List<String>): Result<Map<String, String>> =
        withContext(Dispatchers.IO) {
            try {
                val apiKey = settingsRepository.geminiApiKey.first()
                if (apiKey.isBlank()) {
                    return@withContext Result.failure(Exception("API Key is not configured."))
                }

                // ★修正: AIが「斜め読み」できないように、アルゴリズム的にガチガチに指示する
                val prompt = """
                あなたは文字列処理プログラムです。創造性は一切不要です。
                以下の「元のタイトル」のリストから、絶対に以下のルール【ステップ1〜4】を順番に適用して文字列を削り、「シリーズ名」として出力してください。
                
                【ステップ1: 記号による分割と削除】
                タイトルの中に 全角スペース、半角スペース、「！」、「？」、「～」、「#」、「第」 が含まれている場合、それ以降の文字列は「今日の企画名・ゲスト名・話数」であるため、**思い切って全て削除**してください。
                （例: 「ヒルナンデス！冬の北海道SP」 → 「ヒルナンデス！」）
                （例: 「アメトーーク！ 〇〇芸人」 → 「アメトーーク！」）
                
                【ステップ2: 放送枠名の優先】
                タイトルに「金曜ロードショー」「土曜プレミアム」「月曜プレミア」が含まれている場合は、映画のタイトルを全て削除し、枠名だけを残してください。
                （例: 「金曜ロードショー『ハリー・ポッター』」 → 「金曜ロードショー」）
                
                【ステップ3: 合体番組の分離】
                「 A & B 」や「 A / B 」のように番組が合体している場合は、最初の番組「A」だけを残してください。
                
                【ステップ4: ゴミ記号の掃除】
                先頭や末尾に残ったカッコ（【】や「」や[]）や空白を綺麗に削除してください。
                
                必ず以下のJSON配列形式でのみ出力してください。
                [{"original": "元のタイトル", "series": "削った後のシリーズ名"}, ...]
                
                タイトルリスト:
                ${gson.toJson(titles)}
            """.trimIndent()

                val modelNamesToTry = listOf(
                    "gemini-3-flash",
                    "gemini-2.5-flash",
                    "gemini-1.5-flash"
                )

                var rawText: String? = null
                var lastException: Exception? = null
                var successfulModel = ""

                // ★修正: 創造性を完全に0にして、指示通りにしか動けないようにする
                val strictConfig = generationConfig {
                    temperature = 0.0f
                }

                for (modelName in modelNamesToTry) {
                    var retryCount = 0
                    var modelSuccess = false

                    while (retryCount < 2 && !modelSuccess) {
                        try {
                            Log.i("AiNorm", "Trying AI model: $modelName (Strict Mode)...")

                            val model = GenerativeModel(
                                modelName = modelName,
                                apiKey = apiKey,
                                generationConfig = strictConfig // 適用
                            )
                            val response = model.generateContent(prompt)

                            rawText = response.text
                            if (rawText != null) {
                                successfulModel = modelName
                                modelSuccess = true
                                Log.i("AiNorm", "🎉 Success with model: $modelName !!")
                                break
                            }
                        } catch (e: Exception) {
                            Log.w(
                                "AiNorm",
                                "Model $modelName attempt ${retryCount + 1} failed: ${e.message}"
                            )
                            lastException = e
                            retryCount++
                            if (retryCount < 2) {
                                delay(20000L)
                            }
                        }
                    }
                    if (modelSuccess) break
                }

                if (rawText == null) {
                    return@withContext Result.failure(
                        lastException ?: Exception("All AI models failed")
                    )
                }

                val cleanedText = rawText.replace(Regex("```json\\s*", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("```\\s*"), "")
                    .trim()

                val startIndex = cleanedText.indexOf("[")
                val endIndex = cleanedText.lastIndexOf("]")

                val safeJson = if (startIndex != -1 && endIndex != -1 && endIndex >= startIndex) {
                    cleanedText.substring(startIndex, endIndex + 1)
                } else {
                    cleanedText
                }

                val listType = object : TypeToken<List<Map<String, String>>>() {}.type
                val parsedList: List<Map<String, String>> = gson.fromJson(safeJson, listType)

                val resultMap = mutableMapOf<String, String>()
                val entities = mutableListOf<AiSeriesDictionaryEntity>()
                val now = System.currentTimeMillis()

                parsedList.forEach { map ->
                    val original = map["original"]
                    val series = map["series"]
                    if (original != null && series != null) {
                        resultMap[original] = series
                        entities.add(AiSeriesDictionaryEntity(original, series, now))
                    }
                }

                dictionaryDao.insertAll(entities)
                Log.i("AiNorm", "Successfully parsed and saved ${entities.size} items.")

                Result.success(resultMap)
            } catch (e: Exception) {
                Log.e("AiNorm", "Parse Error after receiving data", e)
                Result.failure(e)
            }
        }
}