package com.calorieapp.data.remote

import com.calorieapp.data.FoodItem
import com.calorieapp.data.FoodUnit
import com.calorieapp.data.NutritionSource
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.POST

interface AnalyzeApi {
    /** Photo -> detected foods with nutrition. */
    @POST("analyze")
    suspend fun analyze(@Body request: AnalyzeRequest): AnalyzeResponse

    /** Barcode -> the product from Open Food Facts. */
    @POST("barcode")
    suspend fun barcode(@Body request: BarcodeRequest): AnalyzeResponse

    /** Encrypted sync between the user's phones: send local changes, get everything newer than [SyncRequest.since]. */
    @POST("sync")
    suspend fun sync(@Body request: SyncRequest): SyncResponse

    /** Food name + weight -> nutrition (used when the user renames an item). */
    @POST("lookup")
    suspend fun lookup(@Body request: LookupRequest): LookupResponse
}

@Serializable
data class AnalyzeRequest(
    val image: String,
    @SerialName("mime_type") val mimeType: String = "image/jpeg",
    /** "meal" or "label". */
    val mode: String = "meal",
    /** AI to try first: "workers-ai" or "gemini". */
    val provider: String = "workers-ai",
)

@Serializable
data class AnalyzeResponse(
    val items: List<FoodItemDto> = emptyList(),
    /** The AI that answered, if one was used. */
    val provider: String? = null,
    /** True when the preferred AI failed and the other one was used. */
    val fallback: Boolean = false,
)

@Serializable
data class BarcodeRequest(val code: String)

@Serializable
data class SyncChange(
    val kind: String,
    val id: String,
    @SerialName("updated_at") val updatedAt: Long,
    val deleted: Boolean = false,
    /** Encrypted on the phone; null for deletions. */
    val data: String? = null,
)

@Serializable
data class SyncRequest(val account: String, val since: Long, val changes: List<SyncChange>)

@Serializable
data class SyncRecord(
    val kind: String,
    val id: String,
    @SerialName("updated_at") val updatedAt: Long,
    val deleted: Boolean = false,
    val data: String? = null,
    val rev: Long,
)

@Serializable
data class SyncResponse(val records: List<SyncRecord> = emptyList(), val rev: Long = 0, val more: Boolean = false)

@Serializable
data class LookupRequest(val name: String, val grams: Double, val unit: String = "g", val provider: String = "workers-ai")

@Serializable
data class LookupResponse(val item: FoodItemDto)

@Serializable
data class FoodItemDto(
    val name: String,
    val grams: Double = 0.0,
    val calories: Double = 0.0,
    @SerialName("protein_g") val proteinG: Double = 0.0,
    @SerialName("carbs_g") val carbsG: Double = 0.0,
    @SerialName("fat_g") val fatG: Double = 0.0,
    /** "afcd", "usda" or "ai". */
    val source: String? = null,
    /** Database food the numbers came from, e.g. "Rice, white, boiled or rice cooker, no added salt". */
    val match: String? = null,
    /** "g" or "ml"; the amount in [grams] is in this unit. */
    val unit: String? = null,
    /** Label mode: set when the transcribed numbers don't hang together. */
    val warning: String? = null,
) {
    fun toFoodItem() = FoodItem(
        name = name,
        grams = grams,
        calories = calories,
        proteinG = proteinG,
        carbsG = carbsG,
        fatG = fatG,
        source = NutritionSource.from(source),
        match = match,
        unit = FoodUnit.from(unit),
        warning = warning,
    )
}

/** Error body the worker sends with non-2xx responses. */
@Serializable
data class ErrorResponse(val error: String? = null, val detail: String? = null)
