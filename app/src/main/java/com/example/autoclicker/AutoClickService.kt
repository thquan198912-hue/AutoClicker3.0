package com.example.autoclicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Dịch vụ trợ năng: hiện thanh điều khiển nổi, rồi
 *  - Chạm / Vuốt: lặp lại thao tác tại chấm A (và B) bằng dispatchGesture().
 *  - Macro: ghi lại các thao tác của bạn rồi phát lại.
 *  - Chuỗi: nhiều bước chạm/vuốt đánh số 1, 2, 3..., mỗi bước có thời gian chờ riêng.
 *
 * Dừng nhanh: nút Dừng phản hồi ngay khi vừa chạm xuống (không chờ nhả tay),
 * và có thể dừng bằng phím âm lượng.
 */
class AutoClickService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: AutoClickService? = null

        private const val MIN_INTERVAL_MS = 50L
        private const val MIN_STEP_GAP_MS = 30L
        private const val MAX_SEQ_STEPS = 50
    }

    private class SeqMarker(val view: TextView, val params: WindowManager.LayoutParams)

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private lateinit var wm: WindowManager

    // Thanh điều khiển
    private var panel: LinearLayout? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var toolsRow: LinearLayout? = null
    private var btnStart: TextView? = null
    private var btnRec: TextView? = null
    private var btnMode: TextView? = null

    // Chấm đánh dấu vị trí (chế độ Chạm / Vuốt)
    private var markerA: TextView? = null
    private var markerAParams: WindowManager.LayoutParams? = null
    private var markerB: TextView? = null
    private var markerBParams: WindowManager.LayoutParams? = null

    // Chuỗi các bước (chế độ Chuỗi): seqStart/seqEnd cùng thứ tự với seqSteps
    private var seqSteps = ArrayList<SeqStep>()
    private val seqStart = ArrayList<SeqMarker>()
    private val seqEnd = ArrayList<SeqMarker?>()

    // Pop-up chỉnh nhanh một bước (hiện khi nhấn giữ chấm)
    private var popup: LinearLayout? = null

    // Trạng thái chạy
    private var running = false
    private var doneCount = 0
    private var gSwipe = false
    private var gX1 = 0f
    private var gY1 = 0f
    private var gX2 = 0f
    private var gY2 = 0f
    private var gInterval = 500L
    private var gDuration = 300L
    private var gMax = 0

    // Phát lại macro / chuỗi
    private var macroSteps: List<MacroStep> = emptyList()
    private var macroIndex = 0
    private var macroLoop = 0
    private var pendingStep: MacroStep? = null

    // Ghi macro
    private var recording = false
    private var dispatching = false
    private var recordView: View? = null
    private var recordParams: WindowManager.LayoutParams? = null
    private val recordedSteps = ArrayList<MacroStep>()
    private val curX = ArrayList<Float>()
    private val curY = ArrayList<Float>()
    private var downAt = 0L
    private var lastEnd = 0L

    private var volumeConsumed = false

    private val fireRunnable = Runnable { fire() }
    private val macroRunnable = Runnable { macroNext() }
    private val macroFireRunnable = Runnable { pendingStep?.let { macroFire(it) } }

    // ---------------------------------------------------------------- vòng đời

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs(this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        instance = this
        loadSeq()
        showOverlay()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Không cần xử lý sự kiện nào.
    }

    override fun onInterrupt() {
        stopRunning()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        stopRunning()
        if (::wm.isInitialized) removeOverlayViews()
        instance = null
        return super.onUnbind(intent)
    }

    /** Phím âm lượng dừng ngay khi đang chạy (phòng khi không bấm được nút Dừng). */
    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event == null) return false
        val code = event.keyCode
        if (code != KeyEvent.KEYCODE_VOLUME_DOWN && code != KeyEvent.KEYCODE_VOLUME_UP) return false
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (running) {
                volumeConsumed = true
                stopRunning()
                toast(getString(R.string.toast_stopped_key))
                return true
            }
            return false
        }
        if (event.action == KeyEvent.ACTION_UP && volumeConsumed) {
            volumeConsumed = false
            return true
        }
        return false
    }

    // ------------------------------------------------------------ giao diện nổi

    /** Hiện thanh điều khiển (hoặc cập nhật nếu đã hiện). */
    fun showOverlay() {
        if (panel != null) {
            syncSeq()
            applyModeUi()
            return
        }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(0xE6212121.toInt())
            }
        }
        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val handle = TextView(this).apply {
            text = "≡"
            setTextColor(Color.WHITE)
            textSize = 24f
            setPadding(dp(6), 0, dp(8), 0)
        }
        // Nút Bắt đầu/Dừng phản hồi ngay khi chạm xuống, vì khi chạy rất nhanh
        // các thao tác tự động có thể làm hệ thống huỷ cú chạm của bạn trước khi nhả tay.
        val start = pill(getString(R.string.bar_start), 0xFF2E7D32.toInt()).apply {
            setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN) toggleRun()
                true
            }
        }
        val rec = pill(getString(R.string.bar_rec), 0xFF6A1B9A.toInt()).apply {
            setOnClickListener { toggleRec() }
            visibility = View.GONE
        }
        val mode = pill("", 0xFF1565C0.toInt()).apply {
            setOnClickListener { toggleMode() }
        }
        val close = pill("✕", 0xFFC62828.toInt()).apply {
            setOnClickListener { hideOverlay() }
        }
        row1.addView(handle)
        row1.addView(start, childParams())
        row1.addView(rec, childParams())
        row1.addView(mode, childParams())
        row1.addView(close, childParams())

        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        val addTap = pill(getString(R.string.bar_add_tap), 0xFF2E7D32.toInt()).apply {
            setOnClickListener { addSeqStep(false) }
        }
        val addSwipe = pill(getString(R.string.bar_add_swipe), 0xFF1565C0.toInt()).apply {
            setOnClickListener { addSeqStep(true) }
        }
        val undo = pill(getString(R.string.bar_undo), 0xFF757575.toInt()).apply {
            setOnClickListener { undoSeqStep() }
        }
        row2.addView(addTap, childParams())
        row2.addView(addSwipe, childParams())
        row2.addView(undo, childParams())

        box.addView(row1)
        box.addView(
            row2,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        )

        val pp = overlayParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        ).apply {
            x = if (prefs.px >= 0) prefs.px else dp(8)
            y = if (prefs.py >= 0) prefs.py else dp(80)
        }
        wm.addView(box, pp)
        dragify(handle, box, pp) {
            prefs.px = pp.x.coerceAtLeast(0)
            prefs.py = pp.y.coerceAtLeast(0)
        }

        panel = box
        panelParams = pp
        toolsRow = row2
        btnStart = start
        btnRec = rec
        btnMode = mode
        applyModeUi()
    }

    /** Người dùng nhấn ✕: ẩn giao diện nổi (dịch vụ vẫn bật). */
    fun hideOverlay() {
        removeOverlayViews()
        toast(getString(R.string.toast_hidden))
    }

    private fun removeOverlayViews() {
        stopRunning()
        endRecording(false)
        panel?.let { safeRemove(it) }
        panel = null
        panelParams = null
        toolsRow = null
        btnStart = null
        btnRec = null
        btnMode = null
        detachMarkerA()
        detachMarkerB()
        clearSeqMarkers()
    }

    /** Cập nhật nhãn, nút và chấm theo kiểu thao tác hiện tại. */
    private fun applyModeUi() {
        val mode = prefs.mode
        btnMode?.text = getString(
            when (mode) {
                Prefs.MODE_SWIPE -> R.string.bar_mode_swipe
                Prefs.MODE_MACRO -> R.string.bar_mode_macro
                Prefs.MODE_SEQ -> R.string.bar_mode_seq
                else -> R.string.bar_mode_tap
            }
        )
        btnRec?.visibility = if (mode == Prefs.MODE_MACRO) View.VISIBLE else View.GONE
        toolsRow?.visibility = if (mode == Prefs.MODE_SEQ) View.VISIBLE else View.GONE

        val wantA = mode == Prefs.MODE_TAP || mode == Prefs.MODE_SWIPE
        val wantB = mode == Prefs.MODE_SWIPE
        var attached = false
        if (wantA && markerA == null) {
            attachMarkerA()
            attached = true
        }
        if (!wantA && markerA != null) detachMarkerA()
        if (wantB && markerB == null) {
            attachMarkerB()
            attached = true
        }
        if (!wantB && markerB != null) detachMarkerB()
        markerA?.text = if (mode == Prefs.MODE_SWIPE) "A" else "+"

        if (mode == Prefs.MODE_SEQ) {
            if (seqStart.size != seqSteps.size) rebuildSeqMarkers()
        } else {
            clearSeqMarkers()
        }

        if (attached) bringPanelToFront()
        updateUi()
    }

    /** Đưa thanh điều khiển lên trên cùng để không bị chấm hay lớp phủ che. */
    private fun bringPanelToFront() {
        // Hoãn sang vòng lặp sau để không gỡ view ngay trong lúc nó đang xử lý cú chạm.
        handler.post {
            val p = panel ?: return@post
            val pp = panelParams ?: return@post
            try {
                wm.removeViewImmediate(p)
                wm.addView(p, pp)
            } catch (e: Exception) {
                // Bỏ qua: thanh vẫn hiển thị ở vị trí cũ.
            }
        }
    }

    private fun attachMarkerA() {
        val dm = resources.displayMetrics
        val size = dp(56)
        val a = makeMarker("+", 0xFF2E7D32.toInt())
        val pa = overlayParams(size, size).apply {
            x = if (prefs.ax >= 0) prefs.ax else dm.widthPixels / 2 - size / 2
            y = if (prefs.ay >= 0) prefs.ay else dm.heightPixels / 2 - size / 2
        }
        wm.addView(a, pa)
        dragify(a, a, pa) {
            prefs.ax = pa.x.coerceAtLeast(0)
            prefs.ay = pa.y.coerceAtLeast(0)
        }
        markerA = a
        markerAParams = pa
    }

    private fun detachMarkerA() {
        markerA?.let { safeRemove(it) }
        markerA = null
        markerAParams = null
    }

    private fun attachMarkerB() {
        val dm = resources.displayMetrics
        val size = dp(56)
        val b = makeMarker("B", 0xFFC62828.toInt())
        val pb = overlayParams(size, size).apply {
            x = if (prefs.bx >= 0) prefs.bx else dm.widthPixels / 2 - size / 2
            y = if (prefs.by >= 0) prefs.by else dm.heightPixels / 2 - size / 2 + dp(180)
        }
        wm.addView(b, pb)
        dragify(b, b, pb) {
            prefs.bx = pb.x.coerceAtLeast(0)
            prefs.by = pb.y.coerceAtLeast(0)
        }
        markerB = b
        markerBParams = pb
    }

    private fun detachMarkerB() {
        markerB?.let { safeRemove(it) }
        markerB = null
        markerBParams = null
    }

    private fun updateUi() {
        if (!::prefs.isInitialized) return
        val mode = prefs.mode
        val playMode = mode == Prefs.MODE_MACRO || mode == Prefs.MODE_SEQ

        btnStart?.let { start ->
            start.text = getString(
                when {
                    running -> R.string.bar_stop
                    playMode -> R.string.bar_play
                    else -> R.string.bar_start
                }
            )
            (start.background as? GradientDrawable)
                ?.setColor(if (running) 0xFFC62828.toInt() else 0xFF2E7D32.toInt())
            start.alpha = if (recording) 0.4f else 1f
        }
        btnRec?.let { rec ->
            rec.text = getString(if (recording) R.string.bar_rec_stop else R.string.bar_rec)
            (rec.background as? GradientDrawable)
                ?.setColor(if (recording) 0xFFC62828.toInt() else 0xFF6A1B9A.toInt())
            rec.alpha = if (running) 0.4f else 1f
        }
        btnMode?.alpha = if (running || recording) 0.4f else 1f
        toolsRow?.alpha = if (running) 0.4f else 1f
    }

    /** Khi đang chạy, các chấm phải "xuyên thấu" để thao tác lọt xuống ứng dụng bên dưới. */
    private fun applyTouchable(touchable: Boolean) {
        val all = ArrayList<Pair<View?, WindowManager.LayoutParams?>>()
        all.add(markerA to markerAParams)
        all.add(markerB to markerBParams)
        for (m in seqStart) all.add(m.view to m.params)
        for (m in seqEnd) if (m != null) all.add(m.view to m.params)

        for ((v, p) in all) {
            if (v == null || p == null || !v.isAttachedToWindow) continue
            p.flags = if (touchable) {
                p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            } else {
                p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }
            v.alpha = if (touchable) 0.85f else 0.35f
            try {
                wm.updateViewLayout(v, p)
            } catch (e: Exception) {
                // View đã bị gỡ.
            }
        }
    }

    // ------------------------------------------------------------ chế độ Chuỗi

    private fun loadSeq() {
        seqSteps = SeqStore.fromJson(prefs.seqJson)
    }

    private fun saveSeq() {
        prefs.seqJson = SeqStore.toJson(seqSteps)
    }

    /** Nạp lại chuỗi từ bộ nhớ (sau khi sửa ở màn hình cài đặt) và vẽ lại các chấm. */
    fun syncSeq() {
        if (!::prefs.isInitialized) return
        if (running || recording) return
        loadSeq()
        if (panel != null && prefs.mode == Prefs.MODE_SEQ) {
            rebuildSeqMarkers()
        } else {
            clearSeqMarkers()
        }
    }

    private fun clearSeqMarkers() {
        closePopup()
        for (m in seqStart) safeRemove(m.view)
        for (m in seqEnd) if (m != null) safeRemove(m.view)
        seqStart.clear()
        seqEnd.clear()
    }

    private fun rebuildSeqMarkers() {
        clearSeqMarkers()
        if (panel == null || prefs.mode != Prefs.MODE_SEQ) return
        for (i in seqSteps.indices) addMarkersFor(i)
        bringPanelToFront()
    }

    /** Tạo chấm đánh số cho bước thứ [i]: chạm có 1 chấm, vuốt có 2 chấm (xanh dương = đầu, đỏ = cuối). */
    private fun addMarkersFor(i: Int) {
        val s = seqSteps[i]
        val size = dp(48)

        val a = makeMarker("", if (s.swipe) 0xFF1565C0.toInt() else 0xFF2E7D32.toInt(), 15f)
        setMarkerLabel(a, markerLabel(i, s, false))
        val pa = overlayParams(size, size).apply {
            x = (s.x1 - size / 2f).toInt()
            y = (s.y1 - size / 2f).toInt()
        }
        wm.addView(a, pa)
        dragify(a, a, pa, onLongPress = { showStepPopup(s, a) }) {
            val c = centerOf(a)
            s.x1 = c.first
            s.y1 = c.second
            saveSeq()
        }
        seqStart.add(SeqMarker(a, pa))

        if (s.swipe) {
            val b = makeMarker("", 0xFFC62828.toInt(), 15f)
            setMarkerLabel(b, markerLabel(i, s, true))
            val pb = overlayParams(size, size).apply {
                x = (s.x2 - size / 2f).toInt()
                y = (s.y2 - size / 2f).toInt()
            }
            wm.addView(b, pb)
            dragify(b, b, pb, onLongPress = { showStepPopup(s, b) }) {
                val c = centerOf(b)
                s.x2 = c.first
                s.y2 = c.second
                saveSeq()
            }
            seqEnd.add(SeqMarker(b, pb))
        } else {
            seqEnd.add(null)
        }
    }

    /** Nhãn của chấm: "3" (chạm), "3×5" (chạm 5 lần), "3A"/"3B" (vuốt, đầu/cuối). */
    private fun markerLabel(i: Int, s: SeqStep, end: Boolean): String {
        val base = (i + 1).toString() + if (s.swipe) (if (end) "B" else "A") else ""
        return if (!end && s.count > 1) base + "×" + s.count else base
    }

    private fun setMarkerLabel(v: TextView, label: String) {
        v.text = label
        v.textSize = if (label.length > 3) 12f else 15f
    }

    private fun refreshLabels(s: SeqStep) {
        val i = seqSteps.indexOf(s)
        if (i < 0) return
        seqStart.getOrNull(i)?.let { setMarkerLabel(it.view, markerLabel(i, s, false)) }
        seqEnd.getOrNull(i)?.let { setMarkerLabel(it.view, markerLabel(i, s, true)) }
    }

    private fun addSeqStep(swipe: Boolean) {
        if (running || recording) return
        if (seqSteps.size >= MAX_SEQ_STEPS) {
            toast(getString(R.string.toast_seq_max, MAX_SEQ_STEPS))
            return
        }
        val dm = resources.displayMetrics
        val n = seqSteps.size
        val off = (n % 6) * dp(28)
        val cx = dm.widthPixels / 2f - dp(80) + off
        val cy = dm.heightPixels / 3f + off
        seqSteps.add(
            SeqStep(
                swipe, cx, cy, cx, cy + dp(160),
                prefs.intervalMs.coerceAtLeast(MIN_INTERVAL_MS),
                prefs.swipeDurationMs
            )
        )
        saveSeq()
        if (seqStart.size == seqSteps.size - 1) {
            addMarkersFor(seqSteps.size - 1)
            bringPanelToFront()
        } else {
            rebuildSeqMarkers()
        }
        toast(getString(R.string.toast_seq_added, seqSteps.size))
    }

    private fun undoSeqStep() {
        if (running || recording) return
        if (seqSteps.isEmpty()) return
        seqSteps.removeAt(seqSteps.size - 1)
        saveSeq()
        rebuildSeqMarkers()
    }

    private fun startSeq() {
        if (seqSteps.isEmpty()) {
            toast(getString(R.string.toast_no_seq))
            return
        }
        if (seqStart.size != seqSteps.size) rebuildSeqMarkers()
        val steps = ArrayList<MacroStep>()
        for (i in seqSteps.indices) {
            val s = seqSteps[i]
            val av = seqStart.getOrNull(i)?.view ?: return
            val a = centerOf(av)
            val reps = s.count.coerceIn(1, 999)
            var bx = 0f
            var by = 0f
            if (s.swipe) {
                val bv = seqEnd.getOrNull(i)?.view ?: return
                val b = centerOf(bv)
                bx = b.first
                by = b.second
            }
            // Lần đầu chờ [delayMs] (thời gian chuyển từ bước trước), các lần sau nghỉ [gapMs].
            for (r in 0 until reps) {
                val wait = if (r == 0) s.delayMs else s.gapMs
                if (s.swipe) {
                    steps.add(
                        MacroStep(
                            wait, s.durationMs.coerceIn(50L, 10_000L),
                            floatArrayOf(a.first, bx), floatArrayOf(a.second, by)
                        )
                    )
                } else {
                    steps.add(MacroStep(wait, 40L, floatArrayOf(a.first), floatArrayOf(a.second)))
                }
            }
        }
        runSteps(steps)
    }

    // ------------------------------------------------------------- điều khiển

    private fun toggleRun() {
        if (recording) return
        if (running) stopRunning() else startRunning()
    }

    private fun toggleMode() {
        if (running || recording) return
        prefs.mode = (prefs.mode + 1) % 4
        applyModeUi()
    }

    private fun toggleRec() {
        if (running) return
        if (recording) endRecording(true) else startRecording()
    }

    private fun startRunning() {
        closePopup()
        when (prefs.mode) {
            Prefs.MODE_MACRO -> {
                startMacro()
                return
            }
            Prefs.MODE_SEQ -> {
                startSeq()
                return
            }
        }

        val a = markerA ?: return
        gSwipe = prefs.mode == Prefs.MODE_SWIPE

        val ca = centerOf(a)
        gX1 = ca.first
        gY1 = ca.second
        if (gSwipe) {
            val b = markerB ?: return
            val cb = centerOf(b)
            gX2 = cb.first
            gY2 = cb.second
        }

        gInterval = prefs.intervalMs.coerceAtLeast(MIN_INTERVAL_MS)
        gDuration = prefs.swipeDurationMs.coerceIn(50L, 10_000L)
        gMax = prefs.repeatCount
        doneCount = 0
        running = true
        applyTouchable(false)
        updateUi()
        handler.postDelayed(fireRunnable, 500L)
    }

    fun stopRunning() {
        running = false
        handler.removeCallbacks(fireRunnable)
        handler.removeCallbacks(macroRunnable)
        handler.removeCallbacks(macroFireRunnable)
        applyTouchable(true)
        updateUi()
    }

    private fun fire() {
        if (!running) return
        val path = Path().apply {
            moveTo(gX1, gY1)
            if (gSwipe) lineTo(gX2, gY2)
        }
        val duration = if (gSwipe) gDuration else 40L
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, duration))
            .build()

        val accepted = dispatchGesture(
            gesture,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    afterGesture()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    afterGesture()
                }
            },
            null
        )
        if (!accepted) {
            stopRunning()
            toast(getString(R.string.toast_gesture_failed))
        }
    }

    private fun afterGesture() {
        if (!running) return
        doneCount++
        if (gMax > 0 && doneCount >= gMax) {
            val n = doneCount
            stopRunning()
            toast(getString(R.string.toast_done, n))
            return
        }
        handler.postDelayed(fireRunnable, gInterval)
    }

    // ------------------------------------------------- phát lại macro / chuỗi

    private fun startMacro() {
        val steps = MacroStore.fromJson(prefs.macroJson)
        if (steps.isEmpty()) {
            toast(getString(R.string.toast_no_macro))
            return
        }
        runSteps(steps)
    }

    /** Phát lần lượt các bước, hết một vòng thì nghỉ rồi lặp lại theo số vòng đã cài. */
    private fun runSteps(steps: List<MacroStep>) {
        macroSteps = steps
        macroIndex = 0
        macroLoop = 0
        gInterval = prefs.intervalMs.coerceAtLeast(MIN_INTERVAL_MS)
        gMax = prefs.repeatCount
        running = true
        applyTouchable(false)
        updateUi()
        handler.postDelayed(macroRunnable, 500L)
    }

    /** Lên lịch thao tác kế tiếp, hoặc kết thúc một vòng. */
    private fun macroNext() {
        if (!running) return
        if (macroIndex >= macroSteps.size) {
            macroLoop++
            if (gMax > 0 && macroLoop >= gMax) {
                val n = macroLoop
                stopRunning()
                toast(getString(R.string.toast_done, n))
                return
            }
            macroIndex = 0
            handler.postDelayed(macroRunnable, gInterval)
            return
        }
        val step = macroSteps[macroIndex]
        pendingStep = step
        handler.postDelayed(macroFireRunnable, step.delayMs.coerceAtLeast(MIN_STEP_GAP_MS))
    }

    private fun macroFire(step: MacroStep) {
        if (!running) return
        val accepted = dispatchGesture(
            buildGesture(step),
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    afterMacroStep()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    afterMacroStep()
                }
            },
            null
        )
        if (!accepted) {
            stopRunning()
            toast(getString(R.string.toast_gesture_failed))
        }
    }

    private fun afterMacroStep() {
        if (!running) return
        macroIndex++
        macroNext()
    }

    private fun buildGesture(step: MacroStep): GestureDescription {
        val path = Path().apply {
            moveTo(step.xs[0], step.ys[0])
            for (i in 1 until step.xs.size) {
                lineTo(step.xs[i], step.ys[i])
            }
        }
        val duration = step.durationMs.coerceIn(10L, 60_000L)
        return GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, duration))
            .build()
    }

    // ----------------------------------------------------------------- ghi macro

    private fun startRecording() {
        recordedSteps.clear()
        curX.clear()
        curY.clear()
        lastEnd = 0L
        dispatching = false

        val v = View(this)
        v.setBackgroundColor(0x22FF0000)
        v.setOnTouchListener { _, e ->
            onRecordTouch(e)
            true
        }
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        wm.addView(v, p)
        recordView = v
        recordParams = p
        recording = true
        bringPanelToFront()
        updateUi()
        toast(getString(R.string.toast_recording))
    }

    private fun endRecording(save: Boolean) {
        if (!recording) return
        recording = false
        dispatching = false
        recordView?.let { safeRemove(it) }
        recordView = null
        recordParams = null
        curX.clear()
        curY.clear()
        if (save) {
            if (recordedSteps.isEmpty()) {
                toast(getString(R.string.toast_rec_empty))
            } else {
                prefs.macroJson = MacroStore.toJson(recordedSteps)
                toast(getString(R.string.toast_rec_saved, recordedSteps.size))
            }
        }
        updateUi()
    }

    private fun onRecordTouch(e: MotionEvent) {
        if (!recording || dispatching) return
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                curX.clear()
                curY.clear()
                curX.add(e.rawX)
                curY.add(e.rawY)
                downAt = SystemClock.uptimeMillis()
            }
            MotionEvent.ACTION_MOVE -> {
                if (curX.isNotEmpty()) {
                    curX.add(e.rawX)
                    curY.add(e.rawY)
                }
            }
            MotionEvent.ACTION_UP -> {
                if (curX.isNotEmpty()) {
                    curX.add(e.rawX)
                    curY.add(e.rawY)
                    finishGesture(SystemClock.uptimeMillis())
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                curX.clear()
                curY.clear()
            }
        }
    }

    private fun finishGesture(upAt: Long) {
        val duration = (upAt - downAt).coerceAtLeast(30L)
        val delay = if (recordedSteps.isEmpty()) 0L else (downAt - lastEnd).coerceAtLeast(0L)
        lastEnd = upAt
        val (xs, ys) = simplify(curX, curY)
        val step = MacroStep(delay, duration, xs, ys)
        recordedSteps.add(step)
        curX.clear()
        curY.clear()
        replayToApp(step)
    }

    /** Gộp chuyển động nhỏ thành một lần chạm và giảm số điểm của đường vuốt. */
    private fun simplify(px: List<Float>, py: List<Float>): Pair<FloatArray, FloatArray> {
        val x0 = px[0]
        val y0 = py[0]
        var maxD = 0f
        for (i in px.indices) {
            val dx = px[i] - x0
            val dy = py[i] - y0
            val d = sqrt(dx * dx + dy * dy)
            if (d > maxD) maxD = d
        }
        if (maxD < dp(8)) {
            return Pair(floatArrayOf(x0), floatArrayOf(y0))
        }
        val n = px.size
        val maxPts = 40
        val idx: List<Int> = if (n <= maxPts) {
            (0 until n).toList()
        } else {
            (0 until maxPts).map { it * (n - 1) / (maxPts - 1) }
        }
        return Pair(
            FloatArray(idx.size) { px[idx[it]] },
            FloatArray(idx.size) { py[idx[it]] }
        )
    }

    /**
     * Gửi thao tác vừa ghi xuống ứng dụng bên dưới. Lớp phủ phải tạm "xuyên thấu"
     * để thao tác không rơi lại vào chính nó.
     */
    private fun replayToApp(step: MacroStep) {
        dispatching = true
        setRecordTouchable(false)
        handler.postDelayed({
            val accepted = dispatchGesture(
                buildGesture(step),
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        endDispatch()
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        endDispatch()
                    }
                },
                null
            )
            if (!accepted) endDispatch()
        }, 80L)
    }

    private fun endDispatch() {
        dispatching = false
        setRecordTouchable(true)
    }

    private fun setRecordTouchable(touchable: Boolean) {
        val v = recordView ?: return
        val p = recordParams ?: return
        if (!v.isAttachedToWindow) return
        p.flags = if (touchable) {
            p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        wm.updateViewLayout(v, p)
    }

    // ---------------------------------------------------------------- tiện ích

    private fun centerOf(v: View): Pair<Float, Float> {
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        return Pair(loc[0] + v.width / 2f, loc[1] + v.height / 2f)
    }

    private fun overlayParams(w: Int, h: Int): WindowManager.LayoutParams {
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        return WindowManager.LayoutParams(
            w,
            h,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
    }

    private fun makeMarker(label: String, color: Int, textSp: Float = 20f): TextView =
        TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = textSp
            alpha = 0.85f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
                setStroke(dp(3), Color.WHITE)
            }
        }

    private fun pill(label: String, color: Int): TextView = TextView(this).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 14f
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(8), dp(10), dp(8))
        background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(color)
        }
    }

    private fun childParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(5) }

    /**
     * Cho phép kéo [target] bằng cách chạm và kéo [handle].
     * Nếu có [onLongPress]: nhấn giữ yên khoảng nửa giây sẽ gọi nó (và không kéo nữa).
     */
    private fun dragify(
        handle: View,
        target: View,
        params: WindowManager.LayoutParams,
        onLongPress: (() -> Unit)? = null,
        onMoved: () -> Unit
    ) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        var longFired = false
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        val longRunnable = Runnable {
            if (!moved) {
                longFired = true
                handle.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                onLongPress?.invoke()
            }
        }
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    longFired = false
                    if (onLongPress != null) handler.postDelayed(longRunnable, 500L)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!longFired) {
                        if (!moved && (abs(e.rawX - downX) > slop || abs(e.rawY - downY) > slop)) {
                            moved = true
                            handler.removeCallbacks(longRunnable)
                        }
                        if (moved) {
                            params.x = startX + (e.rawX - downX).toInt()
                            params.y = startY + (e.rawY - downY).toInt()
                            wm.updateViewLayout(target, params)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longRunnable)
                    if (!longFired) onMoved()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longRunnable)
                    true
                }
                else -> false
            }
        }
    }

    // ------------------------------------------------- pop-up chỉnh nhanh một bước

    private fun closePopup() {
        popup?.let { safeRemove(it) }
        popup = null
    }

    /** Hiện pop-up nhỏ cạnh chấm [anchor] để chỉnh số lần ấn và các thời gian của bước [s]. */
    private fun showStepPopup(s: SeqStep, anchor: View) {
        if (running || recording) return
        val index = seqSteps.indexOf(s)
        if (index < 0) return
        closePopup()

        val dm = resources.displayMetrics
        val width = dp(250)

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(0xF2212121.toInt())
            }
        }
        box.addView(TextView(this).apply {
            text = getString(
                if (s.swipe) R.string.popup_title_swipe else R.string.popup_title_tap,
                index + 1
            )
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })

        box.addView(
            stepperRow(getString(R.string.popup_count), s.count.toLong(), 1L, 1L, 999L, "×") {
                s.count = it.toInt()
                saveSeq()
                refreshLabels(s)
            }
        )
        box.addView(
            stepperRow(getString(R.string.popup_delay), s.delayMs, 100L, 0L, 600_000L, " ms") {
                s.delayMs = it
                saveSeq()
            }
        )
        box.addView(
            stepperRow(getString(R.string.popup_gap), s.gapMs, 10L, 30L, 60_000L, " ms") {
                s.gapMs = it
                saveSeq()
            }
        )
        if (s.swipe) {
            box.addView(
                stepperRow(getString(R.string.popup_duration), s.durationMs, 50L, 50L, 10_000L, " ms") {
                    s.durationMs = it
                    saveSeq()
                }
            )
        }

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        val done = pill(getString(R.string.popup_done), 0xFF2E7D32.toInt()).apply {
            setOnClickListener { closePopup() }
        }
        val del = pill(getString(R.string.popup_delete), 0xFFC62828.toInt()).apply {
            setOnClickListener {
                closePopup()
                val idx = seqSteps.indexOf(s)
                if (idx >= 0) {
                    seqSteps.removeAt(idx)
                    saveSeq()
                    rebuildSeqMarkers()
                }
            }
        }
        buttons.addView(done)
        buttons.addView(del, childParams())
        box.addView(buttons)

        val c = centerOf(anchor)
        val px = (c.first - width / 2f).toInt()
            .coerceIn(dp(8), (dm.widthPixels - width - dp(8)).coerceAtLeast(dp(8)))
        val py = if (c.second > dm.heightPixels / 2f) {
            (c.second - dp(30) - dp(330)).toInt().coerceAtLeast(dp(8))
        } else {
            (c.second + dp(34)).toInt()
        }
        val pp = overlayParams(width, WindowManager.LayoutParams.WRAP_CONTENT).apply {
            x = px
            y = py
        }
        wm.addView(box, pp)
        popup = box
    }

    /**
     * Một dòng chỉnh giá trị bằng nút −/+: chạm để đổi một nấc, giữ để tăng/giảm nhanh dần.
     * (Cửa sổ nổi không mở được bàn phím, nên dùng nút thay cho ô gõ số.)
     */
    private fun stepperRow(
        label: String,
        initial: Long,
        step: Long,
        min: Long,
        max: Long,
        unit: String,
        onValue: (Long) -> Unit
    ): LinearLayout {
        var value = initial
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        col.addView(TextView(this).apply {
            text = label
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 12f
        })
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val tv = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            minWidth = dp(96)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        tv.text = value.toString() + unit

        val change = { dir: Int ->
            value = (value + dir * step).coerceIn(min, max)
            tv.text = value.toString() + unit
            onValue(value)
        }
        val minus = pill("−", 0xFF757575.toInt())
        val plus = pill("＋", 0xFF1565C0.toInt())
        holdRepeat(minus) { mult -> change(-mult) }
        holdRepeat(plus) { mult -> change(mult) }

        row.addView(minus)
        row.addView(tv)
        row.addView(plus)
        col.addView(row)
        return col
    }

    /** Chạm: gọi [action] một lần. Giữ: gọi lặp lại và tăng dần bội số (1 → 4 → 10 nấc mỗi lần). */
    private fun holdRepeat(v: View, action: (Int) -> Unit) {
        var ticks = 0
        val repeat = object : Runnable {
            override fun run() {
                ticks++
                val mult = if (ticks > 20) 10 else if (ticks > 8) 4 else 1
                action(mult)
                handler.postDelayed(this, 100L)
            }
        }
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    ticks = 0
                    action(1)
                    handler.postDelayed(repeat, 450L)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(repeat)
                }
            }
            true
        }
    }

    private fun safeRemove(v: View) {
        try {
            wm.removeView(v)
        } catch (e: Exception) {
            // View đã bị gỡ trước đó.
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
