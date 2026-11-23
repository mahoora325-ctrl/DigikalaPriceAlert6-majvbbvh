package ir.example.digikalapricealert

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import android.util.Log

data class ProductInfo(val title: String?, val priceToman: Long?, val imageUrl: String?)

/** یک رنگ/گونه‌ی مشخص از یک محصول (اگر محصول چند رنگ داشته باشد). */
data class ProductVariant(
    val variantId: String,
    val colorName: String?,
    val priceToman: Long?
)

/** اطلاعات کامل محصول به‌همراه فهرست رنگ‌های موجودش (اگر بیش از یکی باشد). */
data class ProductDetails(
    val title: String?,
    val imageUrl: String?,
    val variants: List<ProductVariant>
)

/** یک نتیجه‌ی خام جستجو، قبل از افزوده شدن به فهرست پایش. */
data class SearchResultItem(
    val productId: String,
    val title: String?,
    val priceToman: Long?,
    val imageUrl: String?
)

/**
 * این کلاینت از API غیررسمی و مستندنشده‌ی دیجی‌کالا استفاده می‌کند
 * (https://api.digikala.com/v2/product/{id}/).
 * چون این API رسمی نیست، دیجی‌کالا می‌تواند ساختار پاسخ را بدون اطلاع قبلی تغییر دهد.
 * به همین دلیل پارس کردن JSON با چند مسیر احتمالی و به‌صورت defensive انجام شده؛
 * اگر در آینده کار نکرد، خروجی خام را در Logcat با تگ DigikalaApi ببینید و
 * مسیر فیلدهای جدید را در تابع parseProductJson اصلاح کنید.
 */
object DigikalaApi {

    private const val TAG = "DigikalaApi"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private const val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 13; SM-A536B) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Mobile Safari/537.36"

    /**
     * آخرین خطای دریافت اطلاعات (مثلاً «HTTP 403» یا «خطای اتصال»)؛ برای نمایش به کاربر
     * تا بدون Logcat بشود علت ناموفق‌بودن را فهمید. با هر درخواست موفق null می‌شود.
     */
    @Volatile
    var lastError: String? = null
        private set

    private fun buildRequest(url: String): Request = Request.Builder()
        .url(url)
        .header("User-Agent", BROWSER_UA)
        .header("Referer", "https://www.digikala.com/")
        .header("Origin", "https://www.digikala.com")
        .header("Accept", "application/json, text/plain, */*")
        .header("Accept-Language", "fa-IR,fa;q=0.9,en-US;q=0.8,en;q=0.7")
        .build()

    /** از روی لینک کامل محصول یا فقط عدد، شناسه‌ی عددی محصول را استخراج می‌کند. */
    fun extractProductId(input: String): String? {
        val trimmed = input.trim()
        // حالت ۱: کاربر فقط عدد را وارد کرده
        if (trimmed.matches(Regex("^\\d+$"))) return trimmed
        // حالت ۲: لینک کامل شامل dkp-123456
        val match = Regex("dkp-(\\d+)").find(trimmed)
        if (match != null) return match.groupValues[1]
        // حالت ۳: هر رشته‌ی دیگری که یک عدد بلند داخلش باشد
        val anyNumber = Regex("(\\d{4,})").find(trimmed)
        return anyNumber?.groupValues?.get(1)
    }

    /** درخواست همزمان (synchronous) - باید از یک ترد پس‌زمینه/Coroutine IO صدا زده شود. */
    private fun fetchProductInfoFromApi(productId: String): ProductInfo? {
        val request = buildRequest("https://api.digikala.com/v2/product/$productId/")

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "HTTP ${response.code} برای محصول $productId")
                    lastError = "HTTP ${response.code}"
                    return null
                }
                val body = response.body?.string()
                if (body == null) {
                    lastError = "پاسخ خالی"
                    return null
                }
                val info = parseProductJson(body)
                lastError = if (info == null) "ساختار پاسخ تغییر کرده (JSON نامعتبر)" else null
                return info
            }
        } catch (e: Exception) {
            Log.e(TAG, "خطا در دریافت اطلاعات محصول $productId", e)
            lastError = "خطای اتصال: ${e.javaClass.simpleName}"
            return null
        }
    }

    /**
     * جستجوی محصول با API جدید جستجوی دیجی‌کالا (discovery/api/v2/search).
     * درخواست همزمان است - باید از ترد پس‌زمینه صدا زده شود. در صورت خطا فهرست خالی برمی‌گرداند
     * و علتش را در lastError می‌گذارد تا صفحه‌ی جستجو بتواند آن را نشان دهد.
     */
    fun searchProducts(query: String): List<SearchResultItem> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        lastError = null

        val encoded = URLEncoder.encode(trimmed, "UTF-8")
        val request = buildRequest(
            "https://api.digikala.com/discovery/api/v2/search?columns_per_page=1&page=1&q=$encoded"
        )

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "HTTP ${response.code} برای جستجوی «$trimmed»")
                    lastError = "جستجو: HTTP ${response.code}"
                    return emptyList()
                }
                val body = response.body?.string()
                if (body.isNullOrEmpty()) {
                    lastError = "جستجو: پاسخ خالی"
                    return emptyList()
                }
                val results = parseSearchJson(body)
                if (results.isEmpty()) {
                    lastError = describeSearchBody(body)
                }
                return results
            }
        } catch (e: Exception) {
            Log.e(TAG, "خطا در جستجوی «$trimmed»", e)
            lastError = "جستجو: خطای اتصال: ${e.javaClass.simpleName}"
            return emptyList()
        }
    }

    /** وقتی پاسخ دریافت شد ولی هیچ محصولی از آن درنیامد، ساختارش را خلاصه می‌کند (برای عیب‌یابی). */
    private fun describeSearchBody(body: String): String {
        return try {
            val root = JSONObject(body)
            val arrays = mutableListOf<String>()
            describeArrays(root, "root", arrays)
            "جستجو: محصولی در پاسخ پیدا نشد | طول: ${body.length}\n" +
                (if (arrays.isEmpty()) "فهرستی پیدا نشد" else arrays.joinToString("\n"))
        } catch (e: Exception) {
            "جستجو: پاسخ JSON نیست"
        }
    }

    /**
     * اولین آرایه‌ای را پیدا می‌کند که آیتم‌هایش واقعاً «محصول» باشند، نه برند/دسته/فیلتر:
     * علاوه بر id و عنوان، باید قیمت‌دار (default_variant یا price) یا عکس‌دار (images) باشند.
     */
    private fun looksLikeProduct(o: JSONObject): Boolean {
        if (o.optLong("id", -1L) <= 0) return false
        val hasTitle = o.has("title_fa") || o.has("title_en") || o.has("title") || o.has("name")
        if (!hasTitle) return false
        // رنگ‌ها/برندها/فیلترها هم id و عنوان و حتی عکس دارند؛ پس فقط «نشانه‌ی قیمت» یا «لینک محصول» ملاک است
        if (o.has("default_variant") || deepFindSellingPrice(o) != null) return true
        val keys = o.keys()
        while (keys.hasNext()) {
            if (keys.next().contains("price", ignoreCase = true)) return true
        }
        return o.toString().contains("/product/dkp-")
    }

    /** هر محصول ممکن است مستقیم یک آبجکت باشد، یا داخل پوشش {"data": {...}} (مثل ویجت‌های جستجو). */
    private fun unwrapProduct(o: JSONObject?): JSONObject? {
        if (o == null) return null
        if (looksLikeProduct(o)) return o
        val inner = o.optJSONObject("data")
        return if (inner != null && looksLikeProduct(inner)) inner else null
    }

    private fun arrayHasProducts(arr: JSONArray): Boolean {
        var checked = 0
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (unwrapProduct(o) != null) return true
            checked++
            if (checked >= 5) break
        }
        return false
    }

    private fun countProducts(arr: JSONArray): Int =
        (0 until arr.length()).count { unwrapProduct(arr.optJSONObject(it)) != null }

    /** همه‌ی فهرست‌های محصول داخل پاسخ را جمع می‌کند (نتایج اصلی، پیشنهادها، تبلیغات و ...). */
    private fun collectProductArrays(node: Any?, out: MutableList<JSONArray>) {
        if (node is JSONArray) {
            if (arrayHasProducts(node)) {
                out.add(node)
                return
            }
            for (i in 0 until node.length()) collectProductArrays(node.opt(i), out)
        } else if (node is JSONObject) {
            val keys = node.keys()
            while (keys.hasNext()) collectProductArrays(node.opt(keys.next()), out)
        }
    }

    private fun findProductArray(node: Any?): JSONArray? {
        if (node is JSONArray) {
            if (arrayHasProducts(node)) return node
            for (i in 0 until node.length()) {
                val found = findProductArray(node.opt(i))
                if (found != null) return found
            }
        } else if (node is JSONObject) {
            val keys = node.keys()
            while (keys.hasNext()) {
                val found = findProductArray(node.opt(keys.next()))
                if (found != null) return found
            }
        }
        return null
    }

    private fun deepFindImageUrl(node: Any?): String? {
        if (node is String) return if (node.startsWith("http")) node else null
        if (node is JSONObject) {
            val keys = node.keys()
            while (keys.hasNext()) {
                val found = deepFindImageUrl(node.opt(keys.next()))
                if (found != null) return found
            }
        } else if (node is JSONArray) {
            for (i in 0 until node.length()) {
                val found = deepFindImageUrl(node.opt(i))
                if (found != null) return found
            }
        }
        return null
    }

    /** مسیر و ساختار فهرست‌های بزرگ داخل پاسخ، برای عیب‌یابی (حداکثر ۶ مورد). */
    private fun describeArrays(node: Any?, path: String, out: MutableList<String>) {
        if (out.size >= 6) return
        if (node is JSONArray) {
            val first = (0 until node.length()).asSequence().mapNotNull { node.optJSONObject(it) }.firstOrNull()
            if (node.length() >= 3 && first != null) {
                val keys = first.keys().asSequence().take(10).joinToString(",")
                out.add("$path[${node.length()}]{$keys}")
            }
            for (i in 0 until minOf(node.length(), 3)) describeArrays(node.opt(i), "$path[$i]", out)
        } else if (node is JSONObject) {
            val keys = node.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                describeArrays(node.opt(k), "$path.$k", out)
            }
        }
    }

    private fun parseSearchJson(raw: String): List<SearchResultItem> {
        val results = mutableListOf<SearchResultItem>()
        try {
            val root = JSONObject(raw)
            // مسیرهای معمول: data.products / data.items؛ اگر نبود، در کل درخت دنبال آرایه‌ی محصولات می‌گردیم
            val data = root.optJSONObject("data")
            fun validArray(arr: JSONArray?): JSONArray? {
                return if (arr != null && arrayHasProducts(arr)) arr else null
            }
            // ممکن است چند فهرست محصول داشته باشیم؛ بزرگ‌ترین را (نتایج اصلی جستجو) انتخاب می‌کنیم
            val candidates = mutableListOf<JSONArray>()
            validArray(data?.optJSONArray("products"))?.let { candidates.add(it) }
            validArray(data?.optJSONArray("items"))?.let { candidates.add(it) }
            collectProductArrays(root, candidates)
            val products = candidates.maxByOrNull { countProducts(it) } ?: return results

            for (i in 0 until products.length()) {
                // آیتم‌های غیرمحصول (مثلاً بنر یا ویجت تبلیغاتی داخل همان آرایه) رد می‌شوند
                val product = unwrapProduct(products.optJSONObject(i)) ?: continue
                val id = product.optLong("id", -1L)
                if (id <= 0) continue
                if (results.any { it.productId == id.toString() }) continue

                val title = listOf("title_fa", "title_en", "title", "name")
                    .firstOrNull { product.has(it) && !product.isNull(it) && product.optString(it).isNotEmpty() }
                    ?.let { product.getString(it) }

                val priceRial: Long? =
                    parsePriceRial(product.optJSONObject("default_variant")?.optJSONObject("price"))
                        ?: parsePriceRial(product.optJSONObject("price"))
                        ?: deepFindSellingPrice(product)
                        ?: product.optLong("final_price", 0L).takeIf { it > 0 }
                        ?: product.optLong("price", 0L).takeIf { it > 0 }
                val priceToman = priceRial?.let { it / 10 }

                var imageUrl: String? = null
                val images = product.optJSONObject("images")
                val mainImage = images?.optJSONObject("main")
                val urlArray = mainImage?.optJSONArray("url")
                if (urlArray != null && urlArray.length() > 0) {
                    imageUrl = urlArray.optString(0, "").ifEmpty { null }
                }
                if (imageUrl == null) {
                    imageUrl = mainImage?.optString("url", "")?.ifEmpty { null }
                }
                if (imageUrl == null) {
                    imageUrl = deepFindImageUrl(product.opt("images") ?: product.opt("image"))
                }

                results.add(
                    SearchResultItem(
                        productId = id.toString(),
                        title = title,
                        priceToman = priceToman,
                        imageUrl = imageUrl
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "خطا در پارس JSON جستجو: $raw", e)
        }
        return results
    }

    /**
     * مثل fetchProductInfo، ولی علاوه بر عنوان/عکس/قیمت، فهرست رنگ‌های موجود
     * محصول (variants) را هم برمی‌گرداند - برای پایش قیمت بر اساس رنگ خاص.
     * اگر محصول اصلاً چند رنگ نداشته باشد، یک آیتم تکی (رنگ=null) برگردانده
     * می‌شود تا فراخوان بتواند به همان روش قبلی رفتار کند.
     * درخواست همزمان (synchronous) است - باید از یک ترد پس‌زمینه/Coroutine IO صدا زده شود.
     */
    private fun fetchProductDetailsFromApi(productId: String): ProductDetails? {
        val request = buildRequest("https://api.digikala.com/v2/product/$productId/")

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "HTTP ${response.code} برای محصول $productId (fetchProductDetails)")
                    lastError = "HTTP ${response.code}"
                    return null
                }
                val body = response.body?.string()
                if (body == null) {
                    lastError = "پاسخ خالی"
                    return null
                }
                val details = parseProductDetailsJson(productId, body)
                lastError = if (details == null) "ساختار پاسخ تغییر کرده (JSON نامعتبر)" else null
                return details
            }
        } catch (e: Exception) {
            Log.e(TAG, "خطا در دریافت جزئیات محصول $productId", e)
            lastError = "خطای اتصال: ${e.javaClass.simpleName}"
            return null
        }
    }


    // ------------------------------------------------------------------
    // فال‌بک: اگر API محصول (v2/product) جواب نداد (مثلاً ۴۰۴ شد)، اطلاعات از خودِ
    // صفحه‌ی HTML محصول در سایت خوانده می‌شود. ساختار صفحه رسمی و مستند نیست و ممکن
    // است عوض شود؛ برای همین چند روش پشت‌سرهم امتحان می‌شود.
    // ------------------------------------------------------------------

    private class PageResult(val json: String?, val error: String?)

    private val nextDataRegex =
        Regex("<script[^>]*id=\"__NEXT_DATA__\"[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL)
    private val ldJsonRegex =
        Regex("<script[^>]*type=\"application/ld\\+json\"[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL)

    /** ترتیب: API جدید (/product/v1/products) ← API قدیم (v2/product) ← صفحه‌ی HTML. خطای هر سه در lastError. */
    fun fetchProductInfo(productId: String): ProductInfo? {
        val newApi = productJsonFromNewApi(productId)
        if (newApi.json != null) {
            val info = parseProductJson(newApi.json)
            if (info != null) {
                lastError = null
                return info
            }
        }
        val newError = newApi.error ?: "API جدید: ساختار ناشناخته"

        fetchProductInfoFromApi(productId)?.let {
            lastError = null
            return it
        }
        val oldError = lastError ?: "نامشخص"

        val page = fetchProductJsonFromPage(productId)
        if (page.json != null) {
            val info = parseProductJson(page.json)
            if (info != null) {
                lastError = null
                return info
            }
        }
        val pageError = page.error?.lineSequence()?.firstOrNull() ?: "صفحه: ساختار ناشناخته"
        lastError = "$newError | API قدیم: $oldError | $pageError"
        return null
    }

    /** مثل fetchProductInfo ولی با فهرست رنگ‌ها (اگر پاسخ آن‌ها را داشته باشد). */
    fun fetchProductDetails(productId: String): ProductDetails? {
        val newApi = productJsonFromNewApi(productId)
        if (newApi.json != null) {
            val details = parseProductDetailsJson(productId, newApi.json)
            if (details != null) {
                lastError = null
                return details
            }
        }
        val newError = newApi.error ?: "API جدید: ساختار ناشناخته"

        fetchProductDetailsFromApi(productId)?.let {
            lastError = null
            return it
        }
        val oldError = lastError ?: "نامشخص"

        val page = fetchProductJsonFromPage(productId)
        if (page.json != null) {
            val details = parseProductDetailsJson(productId, page.json)
            if (details != null) {
                lastError = null
                return details
            }
        }
        val pageError = page.error?.lineSequence()?.firstOrNull() ?: "صفحه: ساختار ناشناخته"
        lastError = "$newError | API قدیم: $oldError | $pageError"
        return null
    }

    // ----- API جدید دیجی‌کالا: https://api.digikala.com/product/v1/products/{id}/ -----

    private const val NEW_API_BASE = "https://api.digikala.com/product/v1/products/"

    private fun productJsonFromNewApi(productId: String): PageResult {
        val request = buildRequest("$NEW_API_BASE$productId/")
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "API جدید: HTTP ${response.code} برای محصول $productId")
                    return PageResult(null, "API جدید: HTTP ${response.code}")
                }
                val body = response.body?.string()
                if (body.isNullOrEmpty()) return PageResult(null, "API جدید: پاسخ خالی")
                val root = try {
                    JSONObject(body)
                } catch (e: Exception) {
                    return PageResult(null, "API جدید: پاسخ JSON نیست")
                }
                val product = normalizeProduct(root, productId)
                return if (product != null) {
                    PageResult(wrapProduct(product), null)
                } else {
                    Log.w(TAG, "API جدید: قیمت در پاسخ محصول $productId پیدا نشد")
                    PageResult(null, "API جدید: قیمت در پاسخ پیدا نشد\n" + describeJson(root, body, productId))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "خطا در API جدید برای محصول $productId", e)
            return PageResult(null, "API جدید: خطای اتصال: ${e.javaClass.simpleName}")
        }
    }

    /**
     * ساختار دقیق پاسخ API جدید برای من مشخص نبود؛ پس چند شکل ممکن را امتحان می‌کنیم:
     * ۱) همان data.product قدیمی، ۲) آبجکتی با همین id و default_variant،
     * ۳) آبجکتی با همین id که فقط یک selling_price داخلش باشد (ساخت محصول ساده).
     */
    private fun normalizeProduct(root: JSONObject, productId: String): JSONObject? {
        val direct = root.optJSONObject("data")?.optJSONObject("product")
        if (direct != null && (direct.has("default_variant") || direct.has("variants"))) return direct

        findProductObject(root, productId)?.let { return it }

        val withId = findObjectById(root, productId) ?: return null
        val price = deepFindSellingPrice(withId) ?: return null
        val product = JSONObject()
        val title = withId.optString("title_fa", "").ifEmpty { withId.optString("title", "") }
        if (title.isNotEmpty()) product.put("title_fa", title)
        product.put(
            "default_variant",
            JSONObject().put("price", JSONObject().put("selling_price", price))
        )
        withId.optJSONObject("images")?.let { product.put("images", it) }
        withId.optJSONArray("variants")?.let { product.put("variants", it) }
        return product
    }

    private fun findObjectById(node: Any?, productId: String): JSONObject? {
        if (node is JSONObject) {
            if (node.optLong("id", -1L).toString() == productId) return node
            val keys = node.keys()
            while (keys.hasNext()) {
                val found = findObjectById(node.opt(keys.next()), productId)
                if (found != null) return found
            }
        } else if (node is JSONArray) {
            for (i in 0 until node.length()) {
                val found = findObjectById(node.opt(i), productId)
                if (found != null) return found
            }
        }
        return null
    }

    private fun deepFindSellingPrice(node: Any?): Long? {
        if (node is JSONObject) {
            val direct = node.optLong("selling_price", 0L)
            if (direct > 0) return direct
            val keys = node.keys()
            while (keys.hasNext()) {
                val found = deepFindSellingPrice(node.opt(keys.next()))
                if (found != null) return found
            }
        } else if (node is JSONArray) {
            for (i in 0 until node.length()) {
                val found = deepFindSellingPrice(node.opt(i))
                if (found != null) return found
            }
        }
        return null
    }

    /** گزارش کوتاه از ساختار JSON پاسخ، برای وقتی که قیمت را پیدا نکردیم. */
    private fun describeJson(root: JSONObject, body: String, productId: String): String {
        fun keysOf(o: JSONObject?): String =
            o?.keys()?.asSequence()?.take(10)?.joinToString(",") ?: "-"
        val data = root.optJSONObject("data")
        val sellingCount = Regex("selling_price").findAll(body).count()
        val idCount = Regex("\"id\"\\s*:\\s*" + productId + "\\b").findAll(body).count()
        return "طول: ${body.length} | کلیدها: ${keysOf(root)} | کلیدهای data: ${keysOf(data)} | " +
            "کلیدهای data.product: ${keysOf(data?.optJSONObject("product"))} | " +
            "selling_price: $sellingCount | id: $idCount"
    }

    private fun fetchProductJsonFromPage(productId: String): PageResult {
        val request = Request.Builder()
            .url("https://www.digikala.com/product/dkp-$productId/")
            .header("User-Agent", BROWSER_UA)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "fa-IR,fa;q=0.9,en-US;q=0.8,en;q=0.7")
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "صفحه‌ی محصول $productId: HTTP ${response.code}")
                    return PageResult(null, "صفحه: HTTP ${response.code}")
                }
                val html = response.body?.string()
                if (html.isNullOrEmpty()) return PageResult(null, "صفحه: پاسخ خالی")
                val json = extractProductJsonFromHtml(html, productId)
                return if (json == null) {
                    Log.w(TAG, "صفحه‌ی محصول $productId دریافت شد ولی قیمت داخلش پیدا نشد")
                    PageResult(null, "صفحه دریافت شد ولی قیمت پیدا نشد\n" + describePage(html, productId))
                } else {
                    PageResult(json, null)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "خطا در دریافت صفحه‌ی محصول $productId", e)
            return PageResult(null, "صفحه: خطای اتصال: ${e.javaClass.simpleName}")
        }
    }


    /** گزارش کوتاهی از ساختار صفحه، برای تشخیص اینکه قیمت کجای HTML است (یا اصلاً صفحه‌ی چالش/بلاک است). */
    private fun describePage(html: String, productId: String): String {
        val title = Regex("<title[^>]*>(.*?)</title>", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)?.trim()?.take(60) ?: "-"

        var nextInfo = "ندارد"
        val nextRaw = nextDataRegex.find(html)?.groupValues?.get(1)
        if (nextRaw != null) {
            nextInfo = try {
                val pageProps = JSONObject(nextRaw).optJSONObject("props")?.optJSONObject("pageProps")
                "دارد، کلیدها: " + (pageProps?.keys()?.asSequence()?.take(8)?.joinToString(",") ?: "-")
            } catch (e: Exception) {
                "دارد ولی JSON نامعتبر"
            }
        }

        val typeRegex = Regex("\"@type\"\\s*:\\s*\"?([A-Za-z]+)")
        val ldTypes = ldJsonRegex.findAll(html)
            .map { m -> typeRegex.find(m.groupValues[1])?.groupValues?.get(1) ?: "?" }
            .toList()
        val sellingCount = Regex("selling_price").findAll(html).count()
        val hasRsc = html.contains("self.__next_f")
        val idCount = Regex("\"id\"\\s*:\\s*" + productId + "\\b").findAll(html).count()

        return "عنوان: $title | طول: ${html.length} | NEXT_DATA: $nextInfo | " +
            "JSON-LD: ${ldTypes.joinToString(",").ifEmpty { "ندارد" }} | " +
            "selling_price: $sellingCount | RSC: ${if (hasRsc) "دارد" else "ندارد"} | id: $idCount"
    }

    /** خروجی را به شکل {"data":{"product":{...}}} برمی‌گرداند تا همان پارسرهای قبلی کار کنند. */
    private fun extractProductJsonFromHtml(html: String, productId: String): String? {
        // روش ۱: داده‌ی Next.js داخل صفحه (__NEXT_DATA__)
        val nextRaw = nextDataRegex.find(html)?.groupValues?.get(1)
        if (nextRaw != null) {
            try {
                val product = findProductObject(JSONObject(nextRaw), productId)
                if (product != null) return wrapProduct(product)
            } catch (e: Exception) {
                Log.w(TAG, "پارس __NEXT_DATA__ ناموفق بود", e)
            }
        }
        // روش ۲: داده‌ی ساختاریافته‌ی JSON-LD (schema.org Product)
        for (m in ldJsonRegex.findAll(html)) {
            try {
                val product = productFromJsonLd(m.groupValues[1].trim())
                if (product != null) return wrapProduct(product)
            } catch (e: Exception) {
                Log.w(TAG, "پارس JSON-LD ناموفق بود", e)
            }
        }
        return null
    }

    private fun wrapProduct(product: JSONObject): String =
        JSONObject().put("data", JSONObject().put("product", product)).toString()

    /** در درخت JSON دنبال آبجکت خودِ محصول (با همین id) می‌گردد، نه محصولات مشابه. */
    private fun findProductObject(node: Any?, productId: String): JSONObject? {
        if (node is JSONObject) {
            if (node.has("default_variant") && node.optLong("id", -1L).toString() == productId) return node
            val keys = node.keys()
            while (keys.hasNext()) {
                val found = findProductObject(node.opt(keys.next()), productId)
                if (found != null) return found
            }
        } else if (node is JSONArray) {
            for (i in 0 until node.length()) {
                val found = findProductObject(node.opt(i), productId)
                if (found != null) return found
            }
        }
        return null
    }

    private fun isProductType(obj: JSONObject): Boolean {
        val t = obj.opt("@type")
        return when (t) {
            is String -> t == "Product"
            is JSONArray -> (0 until t.length()).any { t.optString(it) == "Product" }
            else -> false
        }
    }

    private fun findJsonLdProduct(node: Any?): JSONObject? {
        if (node is JSONObject) {
            if (isProductType(node)) return node
            val graph = node.optJSONArray("@graph")
            if (graph != null) return findJsonLdProduct(graph)
        } else if (node is JSONArray) {
            for (i in 0 until node.length()) {
                val found = findJsonLdProduct(node.opt(i))
                if (found != null) return found
            }
        }
        return null
    }

    /**
     * یک محصول ساده (عنوان، تصویر، قیمت) از روی JSON-LD می‌سازد.
     * اگر واحد پولِ JSON-LD تومان (IRT) باشد ×۱۰ می‌شود تا مثل API به ریال برسد؛ در غیر این
     * صورت ریال فرض می‌شود. حتماً اولین مقدار را با قیمت سایت چک کنید.
     */
    private fun productFromJsonLd(text: String): JSONObject? {
        val parsed: Any = if (text.startsWith("[")) JSONArray(text) else JSONObject(text)
        val ld = findJsonLdProduct(parsed) ?: return null

        val offersRaw = ld.opt("offers")
        val offer: JSONObject = when (offersRaw) {
            is JSONObject -> offersRaw
            is JSONArray -> offersRaw.optJSONObject(0)
            else -> null
        } ?: return null
        val priceValue = offer.opt("price") ?: offer.opt("lowPrice") ?: return null
        val price = priceValue.toString().toDoubleOrNull()?.toLong() ?: return null
        if (price <= 0) return null
        val currency = offer.optString("priceCurrency", "")
        val priceRial = if (currency.equals("IRT", ignoreCase = true)) price * 10 else price

        val product = JSONObject()
        val name = ld.optString("name", "")
        if (name.isNotEmpty()) product.put("title_fa", name)
        product.put(
            "default_variant",
            JSONObject().put("price", JSONObject().put("selling_price", priceRial))
        )

        val imageRaw = ld.opt("image")
        val imageUrl: String? = when (imageRaw) {
            is String -> imageRaw
            is JSONArray -> imageRaw.optString(0, "")
            is JSONObject -> imageRaw.optString("url", "")
            else -> null
        }
        if (!imageUrl.isNullOrEmpty()) {
            product.put(
                "images",
                JSONObject().put("main", JSONObject().put("url", JSONArray().put(imageUrl)))
            )
        }
        return product
    }

    private fun parsePriceRial(priceObj: JSONObject?): Long? {
        if (priceObj == null) return null
        return when {
            priceObj.has("selling_price") -> priceObj.optLong("selling_price")
            priceObj.has("rrp_price") -> priceObj.optLong("rrp_price")
            else -> null
        }
    }

    private fun parseProductDetailsJson(productId: String, raw: String): ProductDetails? {
        return try {
            val root = JSONObject(raw)
            val product = root.optJSONObject("data")?.optJSONObject("product") ?: return null

            val title = when {
                product.has("title_fa") && !product.isNull("title_fa") -> product.getString("title_fa")
                product.has("title_en") && !product.isNull("title_en") -> product.getString("title_en")
                else -> null
            }

            var imageUrl: String? = null
            val images = product.optJSONObject("images")
            val mainImage = images?.optJSONObject("main")
            val urlArray = mainImage?.optJSONArray("url")
            if (urlArray != null && urlArray.length() > 0) {
                imageUrl = urlArray.optString(0, null)
            }
            if (imageUrl == null) {
                imageUrl = mainImage?.optString("url", null)
            }

            val variants = mutableListOf<ProductVariant>()
            val variantsArr = product.optJSONArray("variants")
            if (variantsArr != null) {
                for (i in 0 until variantsArr.length()) {
                    val v = variantsArr.optJSONObject(i) ?: continue
                    val vId = when {
                        v.has("id") -> v.optLong("id", -1L).let { if (it > 0) it.toString() else null }
                        else -> null
                    } ?: continue
                    val colorObj = v.optJSONObject("color")
                    val colorName = when {
                        colorObj != null && colorObj.has("title") && !colorObj.isNull("title") -> colorObj.getString("title")
                        colorObj != null && colorObj.has("name") && !colorObj.isNull("name") -> colorObj.getString("name")
                        else -> null
                    }
                    val priceRial = parsePriceRial(v.optJSONObject("price")) ?: deepFindSellingPrice(v)
                    variants.add(ProductVariant(vId, colorName, priceRial?.let { it / 10 }))
                }
            }

            // اگر آرایه‌ی variants خالی بود یا اصلاً وجود نداشت، یک آیتم تکی (بدون
            // رنگ مشخص) از روی default_variant بساز تا رفتار محصولات تک‌رنگ عوض نشود
            if (variants.isEmpty()) {
                val defaultVariant = product.optJSONObject("default_variant")
                val priceRial = parsePriceRial(defaultVariant?.optJSONObject("price"))
                    ?: parsePriceRial(product.optJSONObject("price"))
                val defaultId = defaultVariant?.optLong("id", -1L)?.let { if (it > 0) it.toString() else null }
                    ?: productId
                variants.add(ProductVariant(defaultId, null, priceRial?.let { it / 10 }))
            }

            ProductDetails(title = title, imageUrl = imageUrl, variants = variants)
        } catch (e: Exception) {
            Log.e(TAG, "خطا در پارس جزئیات محصول: $raw", e)
            null
        }
    }

    private fun parseProductJson(raw: String): ProductInfo? {
        return try {
            val root = JSONObject(raw)
            val product = root.optJSONObject("data")?.optJSONObject("product") ?: return null

            val title = when {
                product.has("title_fa") && !product.isNull("title_fa") -> product.getString("title_fa")
                product.has("title_en") && !product.isNull("title_en") -> product.getString("title_en")
                else -> null
            }

            // قیمت معمولاً داخل default_variant.price قرار دارد
            val variant = product.optJSONObject("default_variant")
            val priceObj = variant?.optJSONObject("price")

            var priceRial: Long? = null
            if (priceObj != null) {
                priceRial = when {
                    priceObj.has("selling_price") -> priceObj.optLong("selling_price")
                    priceObj.has("rrp_price") -> priceObj.optLong("rrp_price")
                    else -> null
                }
            }

            // اگر مسیر بالا جواب نداد، تلاش برای مسیرهای جایگزین احتمالی
            if (priceRial == null) {
                priceRial = product.optJSONObject("price")?.optLong("selling_price")
            }

            val priceToman = priceRial?.let { it / 10 }

            // آدرس تصویر اصلی محصول؛ در API معمولاً data.product.images.main.url یک آرایه است
            var imageUrl: String? = null
            val images = product.optJSONObject("images")
            val mainImage = images?.optJSONObject("main")
            val urlArray = mainImage?.optJSONArray("url")
            if (urlArray != null && urlArray.length() > 0) {
                imageUrl = urlArray.optString(0, null)
            }
            if (imageUrl == null) {
                imageUrl = mainImage?.optString("url", null)
            }

            ProductInfo(title = title, priceToman = priceToman, imageUrl = imageUrl)
        } catch (e: Exception) {
            Log.e(TAG, "خطا در پارس JSON: $raw", e)
            null
        }
    }
}
