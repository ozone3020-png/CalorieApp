package com.calorieapp.data

import com.calorieapp.data.remote.AnalyzeApi
import com.calorieapp.data.remote.AnalyzeRequest
import com.calorieapp.data.remote.BarcodeRequest
import com.calorieapp.data.remote.ErrorResponse
import com.calorieapp.data.remote.LookupRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import retrofit2.HttpException
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException

/** A failure with a message that is safe to show the user as-is. */
class AnalyzeException(message: String, cause: Throwable? = null) : Exception(message, cause)

class AnalyzeRepository(
    private val apiFor: (baseUrl: String) -> AnalyzeApi,
    private val currentServerUrl: suspend () -> String,
    private val json: Json,
) {
    /** Photo -> detected foods with nutrition; in [ScanMode.LABEL], the one product on the panel. */
    suspend fun analyze(photo: File, mode: ScanMode = ScanMode.MEAL): List<FoodItem> {
        val api = api()
        // Labels need more pixels: the print is small and phone photos of cans are tall and narrow.
        val maxSide = if (mode == ScanMode.LABEL) 1600 else 1024
        val image = withContext(Dispatchers.Default) {
            try {
                ImageEncoder.encodeForUpload(photo, maxSide = maxSide)
            } catch (e: IOException) {
                throw AnalyzeException(UNREADABLE_PHOTO, e)
            } catch (e: IllegalArgumentException) {
                throw AnalyzeException(UNREADABLE_PHOTO, e)
            } catch (e: IllegalStateException) {
                throw AnalyzeException(UNREADABLE_PHOTO, e)
            }
        }
        return call { api.analyze(AnalyzeRequest(image = image, mode = mode.wire)) }
            .items
            .map { it.toFoodItem() }
            .filter { it.name.isNotBlank() }
    }

    /** A scanned barcode -> the product from Open Food Facts. */
    suspend fun barcode(code: String): List<FoodItem> {
        val api = api()
        return call { api.barcode(BarcodeRequest(code)) }.items.map { it.toFoodItem() }
    }

    /** Nutrition for [amount] [unit] of the food called [name] (AFCD, then USDA, then AI estimate). */
    suspend fun lookup(name: String, amount: Double, unit: FoodUnit, serverUrl: String? = null): FoodItem {
        val api = api(serverUrl)
        return call { api.lookup(LookupRequest(name = name, grams = amount, unit = unit.wire)) }.item.toFoodItem()
    }

    /** Checks that [serverUrl] is a working calorie-analyzer Worker (a cheap, text-only lookup). */
    suspend fun testServer(serverUrl: String) {
        lookup("apple", 100.0, FoodUnit.GRAMS, serverUrl)
    }

    private suspend fun api(serverUrl: String? = null): AnalyzeApi {
        val url = serverUrl ?: currentServerUrl()
        if ("YOUR-SUBDOMAIN" in url) {
            throw AnalyzeException("Server not set up. Enter your Worker's URL in Settings.")
        }
        return apiFor(url)
    }

    private suspend fun <T> call(request: suspend () -> T): T = try {
        request()
    } catch (e: HttpException) {
        throw AnalyzeException(serverMessage(e), e)
    } catch (e: SocketTimeoutException) {
        throw AnalyzeException("The server took too long to answer. Try again.", e)
    } catch (e: IOException) {
        throw AnalyzeException("Couldn't reach the server. Check your internet connection.", e)
    } catch (e: SerializationException) {
        throw AnalyzeException("The server sent a response the app didn't understand.", e)
    }

    private suspend fun serverMessage(e: HttpException): String {
        val body = withContext(Dispatchers.IO) { e.response()?.errorBody()?.string() }
        val parsed = body?.let { runCatching { json.decodeFromString<ErrorResponse>(it) }.getOrNull() }
        val detail = parsed?.detail.orEmpty()
        return when {
            parsed?.error == "unknown food" -> detail.ifEmpty { "That isn't a food I recognise." }
            parsed?.error == "product not found" ->
                "This product isn't in Open Food Facts yet. Switch to Label and photograph its nutrition panel."
            parsed?.error == "no label" ->
                "Couldn't read a nutrition panel. Fill the frame with the panel, flat on and in focus."
            // Workers AI daily free allowance used up (error 3036 / "neurons" in the message).
            "3036" in detail || "neuron" in detail.lowercase() ->
                "Today's free analysis allowance is used up. It resets at 00:00 UTC."
            parsed?.error != null -> "Server error: ${parsed.error}"
            else -> "Server error (HTTP ${e.code()})."
        }
    }

    private companion object {
        const val UNREADABLE_PHOTO = "Couldn't read the photo. Try taking it again."
    }
}
