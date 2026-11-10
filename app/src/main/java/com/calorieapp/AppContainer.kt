package com.calorieapp

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.calorieapp.data.AnalyzeRepository
import com.calorieapp.data.MealRepository
import com.calorieapp.data.local.AppDatabase
import com.calorieapp.data.remote.AnalyzeApi
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

class CalorieApplication : Application() {
    val container by lazy { AppContainer(this) }
}

/** Hand-rolled dependency container. */
class AppContainer(context: Context) {
    private val database = Room.databaseBuilder(context, AppDatabase::class.java, "calorie.db").build()

    val mealRepository = MealRepository(database.mealDao())

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    private val baseUrl = BuildConfig.WORKER_URL.let { if (it.endsWith("/")) it else "$it/" }

    private val okHttp = OkHttpClient.Builder()
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

    private val retrofit = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(okHttp)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    val analyzeRepository = AnalyzeRepository(
        api = retrofit.create(AnalyzeApi::class.java),
        json = json,
        isConfigured = "YOUR-SUBDOMAIN" !in baseUrl,
    )
}
