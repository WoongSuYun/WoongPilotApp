package kr.co.tesla.cameraalert.data

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import kr.co.tesla.cameraalert.model.SpeedCamera
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

class CameraRepository(private val context: Context) {
    companion object {
        private const val API_URL = "https://api.data.go.kr/openapi/tn_pubr_public_unmanned_traffic_camera_api"
        private const val MAX_BYTES = 20 * 1024 * 1024
    }
    private val file get() = AtomicFile(File(context.filesDir, "cameras.json"))
    fun load(): List<SpeedCamera> {
        if (!file.baseFile.exists()) return emptyList()
        return parseJson(file.openRead().bufferedReader().use { it.readText() })
    }
    fun import(uri: Uri): Int {
        val text = context.contentResolver.openInputStream(uri)?.use { stream ->
            val bytes = stream.readBytesLimited(MAX_BYTES)
            val utf = bytes.toString(Charsets.UTF_8)
            (if ('\uFFFD' in utf) bytes.toString(charset("MS949")) else utf).removePrefix("\uFEFF")
        } ?: error("파일을 열 수 없습니다")
        val cameras = if (text.trimStart().startsWith("[")) parseJson(text) else parseCsv(text)
        require(cameras.isNotEmpty()) { "유효한 과속카메라가 없습니다. 단속구분·좌표·제한속도를 확인하세요" }
        val array = JSONArray()
        cameras.forEach { c -> array.put(JSONObject().put("id", c.id).put("latitude", c.latitude)
            .put("longitude", c.longitude).put("limitKph", c.limitKph).put("roadName", c.roadName)) }
        write(cameras)
        return cameras.size
    }

    /** Refreshes the local fallback from the official data.go.kr API. */
    fun refresh(serviceKey: String): Int {
        require(serviceKey.isNotBlank()) { "공공데이터포털 서비스 키가 설정되지 않았습니다" }
        val cameras = buildList {
            var page = 1
            var total = Int.MAX_VALUE
            // totalCount is the count of all camera records. `size` is smaller because we
            // retain only speed cameras, so it must not control pagination.
            while (page <= 1000 && (total == Int.MAX_VALUE || (page - 1) * 1000 < total)) {
                val query = listOf(
                    // The portal displays an already encoded key. Decode once and encode once
                    // so either portal form (Encoding or Decoding) produces a valid URL value.
                    "serviceKey=${URLEncoder.encode(URLDecoder.decode(serviceKey, "UTF-8"), "UTF-8")}",
                    "pageNo=$page", "numOfRows=1000", "type=json"
                ).joinToString("&")
                val connection = (URL("$API_URL?$query").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"; connectTimeout = 15_000; readTimeout = 25_000
                }
                val text = try {
                    require(connection.responseCode in 200..299) { "공공데이터 서버 HTTP ${connection.responseCode}" }
                    connection.inputStream.use { it.readBytesLimited(MAX_BYTES).toString(Charsets.UTF_8) }
                } finally { connection.disconnect() }
                val root = JSONObject(text)
                // data.go.kr standard-data APIs currently return header/body at the root,
                // while some older gateway responses wrap them inside "response".
                val response = root.optJSONObject("response") ?: root
                val header = response.optJSONObject("header")
                require(header?.optString("resultCode", "00") == "00") {
                    header?.optString("resultMsg", "공공데이터 API 오류") ?: "공공데이터 API 오류"
                }
                val body = response.optJSONObject("body") ?: break
                total = body.optInt("totalCount", size)
                val items = when (val rawItems = body.opt("items")) {
                    is JSONArray -> rawItems
                    is JSONObject -> rawItems.optJSONArray("item") ?: JSONArray().apply { put(rawItems) }
                    else -> JSONArray()
                }
                for (i in 0 until items.length()) parseApiItem(items.getJSONObject(i))?.let(::add)
                if (items.length() == 0) break
                page++
            }
        }.let(::validate)
        require(cameras.isNotEmpty()) { "공공데이터 API에서 과속카메라를 찾지 못했습니다" }
        write(cameras)
        return cameras.size
    }

    private fun parseApiItem(item: JSONObject): SpeedCamera? {
        val type = item.optString("regltSe").trim()
        if (type !in listOf("1", "01", "과속", "과속단속")) return null
        val lat = item.optString("latitude").toDoubleOrNull() ?: return null
        val lon = item.optString("longitude").toDoubleOrNull() ?: return null
        val limit = item.optString("lmttVe").toIntOrNull() ?: return null
        // A few source rows contain missing/incorrect coordinates. Ignore only those rows;
        // the rest of the nationwide dataset remains usable.
        if (lat !in 33.0..39.5 || lon !in 124.0..132.0 || limit !in 10..130) return null
        return SpeedCamera(item.optString("mnlssRegltCameraManageNo", "api_camera"), lat, lon,
            limit, item.optString("roadRouteNm").ifBlank { item.optString("itlpc", "과속카메라") })
    }

    private fun write(cameras: List<SpeedCamera>) {
        val array = JSONArray()
        cameras.forEach { c -> array.put(JSONObject().put("id", c.id).put("latitude", c.latitude)
            .put("longitude", c.longitude).put("limitKph", c.limitKph).put("roadName", c.roadName)) }
        val output = file.startWrite()
        try { output.write(array.toString().toByteArray()); file.finishWrite(output) }
        catch (e: Exception) { file.failWrite(output); throw e }
    }
    private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) { val count = read(buffer); if (count < 0) break
            require(out.size() + count <= limit) { "파일은 20MB 이하로 가져오세요" }; out.write(buffer, 0, count) }
        return out.toByteArray()
    }
    private fun validate(cameras: List<SpeedCamera>): List<SpeedCamera> {
        require(cameras.size <= 100_000) { "카메라는 최대 10만 건까지 지원합니다" }
        require(cameras.all { it.id.isNotBlank() && it.latitude.isFinite() && it.longitude.isFinite()
            && it.latitude in 33.0..39.5 && it.longitude in 124.0..132.0 && it.limitKph in 10..130 }) {
            "카메라 좌표(대한민국) 또는 제한속도가 올바르지 않습니다"
        }
        return cameras.distinctBy { Triple(it.latitude, it.longitude, it.limitKph) }
            .mapIndexed { i, c -> c.copy(id = "camera_$i") }
    }
    private fun parseJson(text: String): List<SpeedCamera> {
        val array = JSONArray(text)
        return validate((0 until array.length()).map { i -> array.getJSONObject(i).run {
            SpeedCamera(optString("id", "camera_$i"), getDouble("latitude"), getDouble("longitude"),
                getInt("limitKph"), optString("roadName", "과속카메라"))
        } })
    }
    private fun parseCsv(text: String): List<SpeedCamera> {
        val rows = Csv.parse(text)
        require(rows.isNotEmpty()) { "빈 CSV 파일입니다" }
        val header = rows.first().map { it.trim().removePrefix("\uFEFF") }
        fun index(name: String) = header.indexOf(name).also { require(it >= 0) { "CSV에 '$name' 열이 없습니다" } }
        val lat = index("위도"); val lon = index("경도"); val speed = index("제한속도"); val type = index("단속구분")
        val road = header.indexOf("도로노선명")
        val cameras = rows.drop(1).mapIndexedNotNull { i, row ->
            fun cell(n: Int) = row.getOrNull(n)?.trim().orEmpty()
            // Standard code 01/1 = speed enforcement. Do not label other enforcement as speed.
            if (cell(type) !in listOf("1", "01", "과속", "과속단속")) return@mapIndexedNotNull null
            val latitude = cell(lat).toDoubleOrNull() ?: return@mapIndexedNotNull null
            val longitude = cell(lon).toDoubleOrNull() ?: return@mapIndexedNotNull null
            val limit = cell(speed).toIntOrNull() ?: return@mapIndexedNotNull null
            if (latitude !in 33.0..39.5 || longitude !in 124.0..132.0 || limit !in 10..130) return@mapIndexedNotNull null
            SpeedCamera("csv_$i", latitude, longitude, limit, cell(road).ifBlank { "과속카메라" })
        }
        return validate(cameras)
    }
}

object Csv {
    fun parse(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '"' -> if (quoted && i + 1 < text.length && text[i + 1] == '"') {
                    cell.append('"'); i++
                } else { quoted = !quoted }
                c == ',' && !quoted -> { row.add(cell.toString()); cell.setLength(0) }
                (c == '\n' || c == '\r') && !quoted -> {
                    row.add(cell.toString()); cell.setLength(0)
                    if (row.any { it.isNotBlank() }) rows.add(row)
                    row = mutableListOf()
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                }
                else -> cell.append(c)
            }
            i++
        }
        require(!quoted) { "CSV 따옴표가 닫히지 않았습니다" }
        row.add(cell.toString())
        if (row.any { it.isNotBlank() }) rows.add(row)
        return rows
    }
}

