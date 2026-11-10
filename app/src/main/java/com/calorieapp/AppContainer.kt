package com.calorieapp

import android.app.Application
import androidx.glance.appwidget.updateAll
import com.calorieapp.widget.CalorieWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.time.LocalDate
import android.content.Context
import androidx.room.Room
import com.calorieapp.data.AnalyzeRepository
import com.calorieapp.data.BackupRepository
import com.calorieapp.data.MealRepository
import com.calorieapp.data.sync.SyncRepository
import kotlinx.coroutines.flow.Flow
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

@OptIn(FlowPreview::class)
class CalorieApplication : Application() {
    val container by lazy { AppContainer(this) }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // Sync: once at start, then a few seconds after any change to the log (when sync is turned on).
        appScope.launch {
            container.syncRepository.syncNow()
            container.localChanges.drop(1).debounce(3_000).collect { container.syncRepository.syncNow() }
        }
        // Keep the home-screen widget current: refresh it whenever today's log or the settings change.
        appScope.launch {
            combine(container.mealRepository.observeDay(LocalDate.now()), container.settingsRepository.settings) { m, s -> m to s }
                .drop(1) // the first value is just the current state
                .debounce(500)
                .collect { CalorieWidget().updateAll(this@CalorieApplication) }
        }
    }
}

/** Hand-rolled dependency container. */
class AppContainer(context: Context) {
    private val database = Room.databaseBuilder(context, AppDatabase::class.java, "calorie.db")
        .addMigrations(AppDatabase.MIGRATION_4_5)
        .build()

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

    val syncRepository = SyncRepository(
        dao = database.mealDao(),
        settings = settingsRepository,
        api = { apiFor(settingsRepository.settings.first().serverUrl ?: defaultServerUrl) },
    )

    /** Emits whenever something syncable changes in the log (meals, favourites, deletions). */
    val localChanges: Flow<Long> = database.mealDao().observeChangeMarker()

    val analyzeRepository = AnalyzeRepository(
        apiFor = ::apiFor,
        currentServerUrl = { settingsRepository.settings.first().serverUrl ?: defaultServerUrl },
        currentProvider = { settingsRepository.settings.first().aiProvider },
        json = json,
    )
}
