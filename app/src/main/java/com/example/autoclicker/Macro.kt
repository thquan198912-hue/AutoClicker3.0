package com.example.autoclicker

import org.json.JSONArray
import org.json.JSONObject

/**
 * Một thao tác đã ghi: chờ [delayMs] (tính từ khi thao tác trước kết thúc),
 * rồi đi theo đường [xs]/[ys] trong [durationMs].
 * Một điểm duy nhất = chạm (hoặc nhấn giữ nếu durationMs lớn).
 */
class MacroStep(
    val delayMs: Long,
    val durationMs: Long,
    val xs: FloatArray,
    val ys: FloatArray
)

/** Chuyển danh sách thao tác qua lại với chuỗi JSON để lưu vào SharedPreferences. */
object MacroStore {

    fun toJson(steps: List<MacroStep>): String {
        val arr = JSONArray()
        for (s in steps) {
            val pts = JSONArray()
            for (i in s.xs.indices) {
                pts.put(s.xs[i].toDouble())
                pts.put(s.ys[i].toDouble())
            }
            arr.put(
                JSONObject()
                    .put("d", s.delayMs)
                    .put("t", s.durationMs)
                    .put("p", pts)
            )
        }
        return arr.toString()
    }

    fun fromJson(json: String): List<MacroStep> {
        if (json.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            val out = ArrayList<MacroStep>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val p = o.getJSONArray("p")
                val n = p.length() / 2
                if (n < 1) continue
                val xs = FloatArray(n) { p.getDouble(it * 2).toFloat() }
                val ys = FloatArray(n) { p.getDouble(it * 2 + 1).toFloat() }
                out.add(MacroStep(o.getLong("d"), o.getLong("t"), xs, ys))
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }
}
