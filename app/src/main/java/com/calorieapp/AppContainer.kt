package com.calorieapp

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.calorieapp.data.AnalyzeRepository
import com.calorieapp.data.BackupRepository
import com.calorieapp.data.MealRepository
import com.calorieapp.data.SettingsRepository
import com.calorieapp.data.normalizeServerUrl
import com.calorieapp.data.local.AppDatabase
import com.calorieapp.data.remote.AnalyzeApi
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID
import java.util.concurrent.TimeUnit

class CalorieApplication : Application() {
    val container by lazy { AppContainer(this) }
}

/** Hand-rolled dependency container. */
class AppContainer(context: Context) {
    private val database = Room.databaseBuilder(context, AppDatabase::class.java, "calorie.db").build()

    val mealRepository = MealRepository(database.mealDao())

    val settingsRepository = SettingsRepository(context)

    val backupRepository = BackupRepository(database, settingsRepository, context.contentResolver)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    /** The Worker URL built into this APK (gradle.properties WORKER_URL); Settings can override it. */
    val defaultServerUrl: String = normalizeServerUrl(BuildConfig.WORKER_URL) ?: BuildConfig.WORKER_URL

    /** Random id for this install, so the Worker can rate-limit per device. Not linked to any account. */
    private val deviceId: String = context.getSharedPreferences("device", Context.MODE_PRIVATE).let { prefs ->
        prefs.getString("id", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("id", it).apply() }
    }

    private val okHttp = OkHttpClient.Builder()
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("X-App-Token", BuildConfig.APP_TOKEN)
                    .header("X-Device-Id", deviceId)
                    .header("User-Agent", "CalorieApp/${BuildConfig.VERSION_NAME}")
                    .build(),
            )
        }
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS) // vision models can take a while on a cold start
        .apply {
            if (BuildConfig.DEBUG) {
                // BASIC, not BODY: the request body is a large base64 image.
                addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
            }
        }
        .build()

    /** One Retrofit client per server URL (normally just one). */
    private val apis = ConcurrentHashMap<String, AnalyzeApi>()

    private fun apiFor(baseUrl: String): AnalyzeApi = apis.getOrPut(baseUrl) {
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttp)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(AnalyzeApi::class.java)
    }

    val analyzeRepository = AnalyzeRepository(
        apiFor = ::apiFor,
        currentServerUrl = { settingsRepository.settings.first().serverUrl ?: defaultServerUrl },
        currentProvider = { settingsRepository.settings.first().aiProvider },
        json = json,
    )
}
