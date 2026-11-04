package com.example.autoclicker

import org.json.JSONArray
import org.json.JSONObject

/**
 * Một bước trong chuỗi: chạm tại (x1, y1) hoặc vuốt từ (x1, y1) đến (x2, y2).
 * [delayMs]: thời gian chờ trước khi thực hiện bước này (tức thời gian chuyển từ bước trước sang).
 * [durationMs]: thời gian của cú vuốt (bỏ qua với chạm).
 * [count]: số lần ấn (hoặc vuốt) liên tiếp tại bước này.
 * [gapMs]: thời gian nghỉ giữa các lần ấn liên tiếp trong cùng một bước.
 */
class SeqStep(
    var swipe: Boolean,
    var x1: Float,
    var y1: Float,
    var x2: Float,
    var y2: Float,
    var delayMs: Long,
    var durationMs: Long,
    var count: Int = 1,
    var gapMs: Long = 150L
)

object SeqStore {

    fun toJson(steps: List<SeqStep>): String {
        val arr = JSONArray()
        for (s in steps) {
            arr.put(
                JSONObject()
                    .put("s", if (s.swipe) 1 else 0)
                    .put("x1", s.x1.toDouble())
                    .put("y1", s.y1.toDouble())
                    .put("x2", s.x2.toDouble())
                    .put("y2", s.y2.toDouble())
                    .put("d", s.delayMs)
                    .put("t", s.durationMs)
                    .put("n", s.count)
                    .put("g", s.gapMs)
            )
        }
        return arr.toString()
    }

    fun fromJson(json: String): ArrayList<SeqStep> {
        val out = ArrayList<SeqStep>()
        if (json.isBlank()) return out
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    SeqStep(
                        o.getInt("s") == 1,
                        o.getDouble("x1").toFloat(),
                        o.getDouble("y1").toFloat(),
                        o.getDouble("x2").toFloat(),
                        o.getDouble("y2").toFloat(),
                        o.getLong("d"),
                        o.getLong("t"),
                        o.optInt("n", 1).coerceIn(1, 999),
                        o.optLong("g", 150L).coerceIn(30L, 60_000L)
                    )
                )
            }
        } catch (e: Exception) {
            out.clear()
        }
        return out
    }
}
