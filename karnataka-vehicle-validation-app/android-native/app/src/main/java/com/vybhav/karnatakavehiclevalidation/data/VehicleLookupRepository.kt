package com.vybhav.karnatakavehiclevalidation.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class VehicleLookupRepository {
    private val sourceUrl = "https://etc.karnataka.gov.in/ReportingUser/Scgr1.aspx"
    private val lookupCache = ConcurrentHashMap<String, CachedLookup>()

    suspend fun lookupVehicle(registrationNumber: String): VehicleLookupResult = withContext(Dispatchers.IO) {
        val normalized = registrationNumber.uppercase().filter { it.isLetterOrDigit() }
        require(normalized.isNotBlank()) { "Please enter a vehicle registration number." }

        lookupCache[normalized]
            ?.takeIf { System.currentTimeMillis() - it.cachedAtMillis < CACHE_TTL_MILLIS }
            ?.let { return@withContext it.result }

        val sessionClient = buildUnsafeClient(SessionCookieJar())
        val checked = mutableListOf<FuelType>()

        for (fuelType in listOf(FuelType.PETROL, FuelType.DIESEL)) {
            checked += fuelType
            val records = fetchRecords(sessionClient, normalized, fuelType)
            if (records.isNotEmpty()) {
                return@withContext VehicleLookupResult(
                    normalizedRegistration = normalized,
                    matchedFuelType = fuelType,
                    records = records,
                    checkedFuelTypes = checked.toList(),
                ).also { lookupCache[normalized] = CachedLookup(System.currentTimeMillis(), it) }
            }

            Thread.sleep(FUEL_SWITCH_DELAY_MILLIS)
        }

        VehicleLookupResult(
            normalizedRegistration = normalized,
            matchedFuelType = null,
            records = emptyList(),
            checkedFuelTypes = checked.toList(),
        ).also { lookupCache[normalized] = CachedLookup(System.currentTimeMillis(), it) }
    }

    private fun fetchRecords(client: OkHttpClient, registrationNumber: String, fuelType: FuelType): List<PucRecord> {
        var lastError: Exception? = null

        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                val initialRequest = Request.Builder()
                    .url(sourceUrl)
                    .get()
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .build()

                val initialHtml = client.newCall(initialRequest).execute().use { response ->
                    if (!response.isSuccessful) error("Initial request failed: ${response.code}")
                    response.body?.string().orEmpty()
                }

                ensurePageLooksValid(initialHtml, "initial page")

                val initialDocument = Jsoup.parse(initialHtml, sourceUrl)
                val viewState = initialDocument.selectFirst("input[name=__VIEWSTATE]")?.attr("value").orEmpty()
                val viewStateGenerator = initialDocument.selectFirst("input[name=__VIEWSTATEGENERATOR]")?.attr("value").orEmpty()
                val eventValidation = initialDocument.selectFirst("input[name=__EVENTVALIDATION]")?.attr("value").orEmpty()

                if (viewState.isBlank() || viewStateGenerator.isBlank() || eventValidation.isBlank()) {
                    error("Lookup form tokens were missing from the Karnataka source.")
                }

                val formBody = FormBody.Builder()
                    .add("__VIEWSTATE", viewState)
                    .add("__VIEWSTATEGENERATOR", viewStateGenerator)
                    .add("__EVENTVALIDATION", eventValidation)
                    .add("Sreg", registrationNumber)
                    .add("Veh_Type", fuelType.code)
                    .add("Button1", "Search")
                    .build()

                val responseRequest = Request.Builder()
                    .url(sourceUrl)
                    .post(formBody)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Origin", "https://etc.karnataka.gov.in")
                    .header("Referer", sourceUrl)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .build()

                val responseHtml = client.newCall(responseRequest).execute().use { response ->
                    if (!response.isSuccessful) error("Lookup request failed: ${response.code}")
                    response.body?.string().orEmpty()
                }

                ensurePageLooksValid(responseHtml, "lookup response")

                val responseDocument = Jsoup.parse(responseHtml, sourceUrl)
                return parseTable(responseDocument, fuelType.tableId)
            } catch (exception: Exception) {
                lastError = exception
                Thread.sleep(backoffMillis(attempt))
            }
        }

        throw IOException(lastError?.message ?: "The Karnataka source could not be reached right now.", lastError)
    }

    private fun parseTable(document: Document, tableId: String): List<PucRecord> {
        val table = document.selectFirst("table#$tableId") ?: return emptyList()
        return table.select("tr").drop(1).mapNotNull { row ->
            val cells = row.select("td")
            if (cells.isEmpty()) return@mapNotNull null

            val anchor = cells[0].selectFirst("a")
            PucRecord(
                puccNumber = anchor?.text().orEmpty().ifBlank { cells.getOrNull(0)?.text().orEmpty() },
                registrationNumber = cells.getOrNull(1)?.text().orEmpty(),
                make = cells.getOrNull(2)?.text().orEmpty(),
                category = cells.getOrNull(3)?.text().orEmpty(),
                registrationDate = cells.getOrNull(4)?.text().orEmpty(),
                testDate = cells.getOrNull(5)?.text().orEmpty(),
                testTime = cells.getOrNull(6)?.text().orEmpty(),
                validDate = cells.getOrNull(7)?.text().orEmpty(),
                hsuMean = cells.getOrNull(8)?.text().orEmpty(),
                kMean = cells.getOrNull(9)?.text().orEmpty(),
                oilTempMean = cells.getOrNull(10)?.text().orEmpty(),
                rpmMaxMean = cells.getOrNull(11)?.text().orEmpty(),
                rpmMinMean = cells.getOrNull(12)?.text().orEmpty(),
                result = cells.getOrNull(13)?.text().orEmpty(),
                cancelled = cells.getOrNull(14)?.text().orEmpty(),
                detailsUrl = anchor?.absUrl("href").orEmpty(),
            )
        }
    }

    private fun ensurePageLooksValid(html: String, stage: String) {
        if (html.isBlank()) {
            error("Empty response from Karnataka source during $stage.")
        }

        val transientMarkers = listOf(
            "server error in '/' application",
            "runtime error",
            "http error 500",
            "internal server error",
            "request timed out",
        )

        val normalizedHtml = html.lowercase()
        if (transientMarkers.any(normalizedHtml::contains)) {
            error("Karnataka source returned a temporary server error during $stage.")
        }
    }

    private fun buildUnsafeClient(cookieJar: CookieJar): OkHttpClient {
        val trustAllCertificates = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(trustAllCertificates), SecureRandom())

        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAllCertificates)
            .hostnameVerifier(HostnameVerifier { _, _ -> true })
            .cookieJar(cookieJar)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private fun backoffMillis(attempt: Int): Long = (1500L * (attempt + 1)).coerceAtMost(5000L)

    private data class CachedLookup(
        val cachedAtMillis: Long,
        val result: VehicleLookupResult,
    )

    private class SessionCookieJar : CookieJar {
        private val cookieStore = mutableMapOf<String, MutableList<Cookie>>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookieStore[url.host] = cookies.toMutableList()
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val now = System.currentTimeMillis()
            return cookieStore[url.host]
                ?.filterNot { it.expiresAt < now }
                .orEmpty()
        }
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"
        private const val MAX_ATTEMPTS = 5
        private const val CACHE_TTL_MILLIS = 5 * 60 * 1000L
        private const val FUEL_SWITCH_DELAY_MILLIS = 400L
    }
}
