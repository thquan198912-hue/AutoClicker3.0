package com.example.autoclicker

import android.content.Context

/** Lưu cài đặt, vị trí các chấm và macro đã ghi. */
class Prefs(context: Context) {

    companion object {
        const val MODE_TAP = 0
        const val MODE_SWIPE = 1
        const val MODE_MACRO = 2
        const val MODE_SEQ = 3
    }

    private val sp = context.applicationContext
        .getSharedPreferences("autoclicker", Context.MODE_PRIVATE)

    /** Kiểu thao tác: chạm, vuốt hoặc macro. (Tương thích bản cũ lưu swipeMode.) */
    var mode: Int
        get() = sp.getInt("mode", if (sp.getBoolean("swipeMode", false)) MODE_SWIPE else MODE_TAP)
        set(v) { sp.edit().putInt("mode", v).apply() }

    /** Khoảng nghỉ giữa hai lần thao tác (ms); với macro là nghỉ giữa hai vòng. */
    var intervalMs: Long
        get() = sp.getLong("intervalMs", 500L)
        set(v) { sp.edit().putLong("intervalMs", v).apply() }

    /** Số lần lặp (số vòng với macro), 0 = lặp mãi. */
    var repeatCount: Int
        get() = sp.getInt("repeatCount", 0)
        set(v) { sp.edit().putInt("repeatCount", v).apply() }

    var swipeDurationMs: Long
        get() = sp.getLong("swipeDurationMs", 300L)
        set(v) { sp.edit().putLong("swipeDurationMs", v).apply() }

    /** Chuỗi các bước chạm/vuốt tự tạo, dạng JSON (xem SeqStore). */
    var seqJson: String
        get() = sp.getString("seqJson", "") ?: ""
        set(v) { sp.edit().putString("seqJson", v).apply() }

    /** Macro đã ghi, dạng JSON (xem MacroStore). */
    var macroJson: String
        get() = sp.getString("macroJson", "") ?: ""
        set(v) { sp.edit().putString("macroJson", v).apply() }

    // Vị trí góc trên-trái của cửa sổ nổi, -1 = chưa đặt.
    var ax: Int
        get() = sp.getInt("ax", -1)
        set(v) { sp.edit().putInt("ax", v).apply() }
    var ay: Int
        get() = sp.getInt("ay", -1)
        set(v) { sp.edit().putInt("ay", v).apply() }
    var bx: Int
        get() = sp.getInt("bx", -1)
        set(v) { sp.edit().putInt("bx", v).apply() }
    var by: Int
        get() = sp.getInt("by", -1)
        set(v) { sp.edit().putInt("by", v).apply() }
    var px: Int
        get() = sp.getInt("px", -1)
        set(v) { sp.edit().putInt("px", v).apply() }
    var py: Int
        get() = sp.getInt("py", -1)
        set(v) { sp.edit().putInt("py", v).apply() }
}
