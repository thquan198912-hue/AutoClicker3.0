package com.example.autoclicker

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private lateinit var rbTap: RadioButton
    private lateinit var rbSwipe: RadioButton
    private lateinit var rbMacro: RadioButton
    private lateinit var rbSeq: RadioButton
    private lateinit var etInterval: EditText
    private lateinit var etRepeat: EditText
    private lateinit var etSwipeDuration: EditText
    private lateinit var seqList: LinearLayout

    // Bản sao dùng để hiển thị danh sách bước; các ô nhập cùng thứ tự với danh sách.
    private var seqSteps = ArrayList<SeqStep>()
    private val delayFields = ArrayList<EditText>()
    private val durFields = ArrayList<EditText?>()

    // Giá trị lúc hiển thị: chỉ ghi lại ô nào bạn thực sự sửa, để không ghi đè
    // các thay đổi vừa làm bằng pop-up trên màn hình.
    private val delayOrig = ArrayList<Long>()
    private val durOrig = ArrayList<Long>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = Prefs(this)
        status = findViewById(R.id.status)
        rbTap = findViewById(R.id.rbTap)
        rbSwipe = findViewById(R.id.rbSwipe)
        rbMacro = findViewById(R.id.rbMacro)
        rbSeq = findViewById(R.id.rbSeq)
        etInterval = findViewById(R.id.etInterval)
        etRepeat = findViewById(R.id.etRepeat)
        etSwipeDuration = findViewById(R.id.etSwipeDuration)
        seqList = findViewById(R.id.seqList)

        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.btnAppInfo).setOnClickListener {
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }

        findViewById<Button>(R.id.btnApply).setOnClickListener {
            saveSettings()
            val service = AutoClickService.instance
            if (service == null) {
                Toast.makeText(this, R.string.toast_need_service, Toast.LENGTH_LONG).show()
            } else {
                service.showOverlay()
                Toast.makeText(this, R.string.toast_saved, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        loadSettings()
        seqSteps = SeqStore.fromJson(prefs.seqJson)
        renderSeq()
        status.setText(
            if (AutoClickService.instance != null) R.string.status_on else R.string.status_off
        )
    }

    override fun onPause() {
        super.onPause()
        saveSettings()
    }

    private fun loadSettings() {
        when (prefs.mode) {
            Prefs.MODE_SWIPE -> rbSwipe.isChecked = true
            Prefs.MODE_MACRO -> rbMacro.isChecked = true
            Prefs.MODE_SEQ -> rbSeq.isChecked = true
            else -> rbTap.isChecked = true
        }
        etInterval.setText(prefs.intervalMs.toString())
        etRepeat.setText(prefs.repeatCount.toString())
        etSwipeDuration.setText(prefs.swipeDurationMs.toString())
    }

    private fun saveSettings() {
        prefs.mode = when {
            rbSwipe.isChecked -> Prefs.MODE_SWIPE
            rbMacro.isChecked -> Prefs.MODE_MACRO
            rbSeq.isChecked -> Prefs.MODE_SEQ
            else -> Prefs.MODE_TAP
        }
        prefs.intervalMs = (etInterval.text.toString().toLongOrNull() ?: 500L)
            .coerceIn(50L, 3_600_000L)
        prefs.repeatCount = (etRepeat.text.toString().toIntOrNull() ?: 0)
            .coerceAtLeast(0)
        prefs.swipeDurationMs = (etSwipeDuration.text.toString().toLongOrNull() ?: 300L)
            .coerceIn(50L, 10_000L)
        applyFieldEdits()
        AutoClickService.instance?.syncSeq()
    }

    // ------------------------------------------------------------ danh sách bước

    private fun renderSeq() {
        seqList.removeAllViews()
        delayFields.clear()
        durFields.clear()
        delayOrig.clear()
        durOrig.clear()

        if (seqSteps.isEmpty()) {
            seqList.addView(TextView(this).apply {
                setText(R.string.seq_empty)
                textSize = 13f
            })
            return
        }

        for (i in seqSteps.indices) {
            val s = seqSteps[i]
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(10), 0, dp(4))
            }

            val head = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val title = TextView(this).apply {
                text = getString(
                    if (s.swipe) R.string.seq_item_swipe else R.string.seq_item_tap,
                    i + 1
                )
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
            }
            head.addView(
                title,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            val del = Button(this).apply {
                setText(R.string.seq_delete)
                setOnClickListener { deleteSeqStep(i) }
            }
            head.addView(del)
            row.addView(head)

            row.addView(smallLabel(R.string.seq_delay_label))
            val d = numberField(s.delayMs.toString(), 7)
            row.addView(d)
            delayFields.add(d)
            delayOrig.add(s.delayMs)

            if (s.swipe) {
                row.addView(smallLabel(R.string.seq_duration_label))
                val t = numberField(s.durationMs.toString(), 5)
                row.addView(t)
                durFields.add(t)
            } else {
                durFields.add(null)
            }
            durOrig.add(s.durationMs)
            seqList.addView(row)
        }
    }

    /**
     * Ghi thời gian chờ / thời gian vuốt đang nhập vào danh sách mới nhất trong bộ nhớ.
     * Đọc lại từ bộ nhớ để không ghi đè vị trí chấm vừa được kéo ở lớp phủ.
     */
    private fun applyFieldEdits() {
        if (delayFields.isEmpty()) return
        val fresh = SeqStore.fromJson(prefs.seqJson)
        if (fresh.size != delayFields.size) return
        for (i in fresh.indices) {
            val nd = delayFields[i].text.toString().toLongOrNull()
            if (nd != null && nd != delayOrig[i]) {
                fresh[i].delayMs = nd.coerceIn(0L, 3_600_000L)
                delayOrig[i] = nd
            }
            val tf = durFields[i]
            if (tf != null) {
                val nt = tf.text.toString().toLongOrNull()
                if (nt != null && nt != durOrig[i]) {
                    fresh[i].durationMs = nt.coerceIn(50L, 10_000L)
                    durOrig[i] = nt
                }
            }
        }
        prefs.seqJson = SeqStore.toJson(fresh)
    }

    private fun deleteSeqStep(index: Int) {
        applyFieldEdits()
        val fresh = SeqStore.fromJson(prefs.seqJson)
        if (index in fresh.indices) fresh.removeAt(index)
        prefs.seqJson = SeqStore.toJson(fresh)
        seqSteps = fresh
        renderSeq()
        AutoClickService.instance?.syncSeq()
    }

    private fun smallLabel(resId: Int): TextView = TextView(this).apply {
        setText(resId)
        textSize = 12f
        setPadding(0, dp(4), 0, 0)
    }

    private fun numberField(value: String, maxLen: Int): EditText = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        filters = arrayOf(InputFilter.LengthFilter(maxLen))
        setText(value)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}
