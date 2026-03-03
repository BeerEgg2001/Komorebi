package com.beeregg2001.komorebi.data.repository

import android.util.Log
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.local.dao.AiSeriesDictionaryDao
import com.beeregg2001.komorebi.data.local.entity.AiSeriesDictionaryEntity
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.generationConfig
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
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

                val config = generationConfig {
                    responseMimeType = "application/json"
                }

                val generativeModel = GenerativeModel(
                    modelName = "gemini-1.5-flash",
                    apiKey = apiKey,
                    generationConfig = config
                )

                val prompt = """
                あなたは優秀な日本のテレビ番組のメタデータ整理AIです。
                以下の録画番組のタイトルリストを解析し、話数、サブタイトル、放送回、再放送や画質マーク、企画名、コーナー名などを取り除いた「公式のシリーズ名（親タイトル）」を抽出して、JSON形式で返してください。
                
                【重要ルール】
                1. 「！」「？」などのタイトル固有の記号は絶対に削らないでください。
                2. 「【連続テレビ小説】ばけばけ」のような放送枠名は外し、作品名だけにしてください。
                3. 2つの番組が合体している場合（A & B）、メインの番組名（A）をシリーズ名にしてください。
                4. 「金曜ロードショー」や「土曜プレミアム」などの映画枠・特別枠は、個別の映画作品名ではなく、枠名（「金曜ロードショー」など）をシリーズ名にしてください。
                5. バラエティ番組の後半にある企画名（例：「カレンダープロジェクト」「～の巻」「〇〇SP」）は全て削除してください。
                6. 極端に短いタイトル（例：マツコ、有吉など）に省略しすぎないでください。
                7. 入力されたタイトルリストの全件について、1件も漏らさずに結果を返してください。必ず入力と同じ数の配列になります。
                
                【抽出の例】
                "【連続テレビ小説】ばけばけ" -> "ばけばけ"
                "ヒルナンデス!冬の北海道日帰りバスツアーSP!GLAY・ HISASHI登場!" -> "ヒルナンデス!"
                "世界の果てまでイッテQ！ カレンダープロジェクト" -> "世界の果てまでイッテQ！"
                "金曜ロードショー「ハリー・ポッターと賢者の石」" -> "金曜ロードショー"
                "上田と女DEEP【セカンドキャリア】&「黒崎さんの一途な愛がとまらない」#7[字]" -> "上田と女DEEP"
                
                出力フォーマット: [{"original": "元のタイトル", "series": "シリーズ名"}, ... ]
                
                タイトルリスト:
                ${gson.toJson(titles)}
            """.trimIndent()

                val response = generativeModel.generateContent(prompt)
                val rawText = response.text
                    ?: return@withContext Result.failure(Exception("Empty response from AI"))

                // ★修正: Markdownの ```json や ``` を取り除く安全処理（パースエラーによる破棄を防止）
                val jsonText = rawText.replace(Regex("```json\\s*", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("```\\s*"), "")
                    .trim()

                val listType = object : TypeToken<List<Map<String, String>>>() {}.type
                val parsedList: List<Map<String, String>> = gson.fromJson(jsonText, listType)

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
                Log.e("AiNorm", "Parse or API Error", e)
                Result.failure(e)
            }
        }
}