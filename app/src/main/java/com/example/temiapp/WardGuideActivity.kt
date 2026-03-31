package com.example.temiapp

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.ceil

class WardGuideActivity : AppCompatActivity() {

    private lateinit var prefixGrid: GridLayout
    private lateinit var tvSelectedWard: TextView

    private var selectedPrefixButton: Button? = null

    private val prefixList = listOf(
        "801", "802", "803", "805", "806",
        "807", "808", "809", "810", "811", "812", "813",
        "815", "816", "817", "818", "819", "820", "821", "822", "823",
        "825", "826", "827"
    )

    private val roomMap: Map<String, List<String>> = mapOf(
        "801" to listOf("801"),
        "802" to listOf("802"),
        "803" to listOf("803"),
        "805" to listOf("805"),
        "806" to listOf("806A", "806B"),
        "807" to listOf("807A", "807B"),
        "808" to listOf("808A", "808B"),
        "809" to listOf("809A", "809B"),
        "810" to listOf("810A", "810B"),
        "811" to listOf("811A", "811B"),
        "812" to listOf("812A", "812B"),
        "813" to listOf("813A", "813B"),
        "815" to listOf("815A", "815B"),
        "816" to listOf("816A", "816B"),
        "817" to listOf("817A", "817B"),
        "818" to listOf("818A", "818B", "818C"),
        "819" to listOf("819A", "819B", "819C"),
        "820" to listOf("820A", "820B", "820C"),
        "821" to listOf("821A", "821B", "821C"),
        "822" to listOf("822A", "822B", "822C"),
        "823" to listOf("823A", "823B", "823C"),
        "825" to listOf("825A", "825B", "825C"),
        "826" to listOf("826A", "826B", "826C"),
        "827" to listOf("827")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ward_guide)

        prefixGrid = findViewById(R.id.prefix_grid)
        tvSelectedWard = findViewById(R.id.tv_selected_ward)

        findViewById<Button>(R.id.btn_back).setOnClickListener { finish() }

        prefixGrid.post { renderPrefixButtons() }
    }

    private fun renderPrefixButtons() {
        prefixGrid.removeAllViews()

        val columns = calculateColumns()
        prefixGrid.columnCount = columns
        prefixGrid.rowCount = ceil(prefixList.size / columns.toDouble()).toInt()

        val isTablet = resources.configuration.smallestScreenWidthDp >= 600
        // ✅ [放大字體] 主畫面的病房號碼字體放大 (平板從 24f 改為 36f)
        val btnTextSize = if (isTablet) 36f else 28f
        val margin = if (isTablet) dp(6) else dp(4)
        val radius = if (isTablet) 18 else 14

        prefixList.forEachIndexed { index, prefix ->
            val row = index / columns
            val col = index % columns

            val btn = Button(this).apply {
                text = prefix
                isAllCaps = false
                setSingleLine(true)
                textSize = btnTextSize
                setTextColor(Color.WHITE)
                background = roundedBg("#6750A4", radius)
                setPadding(0, 0, 0, 0)
                minWidth = 0
                minimumWidth = 0
                minHeight = 0
                minimumHeight = 0
                setOnClickListener { handlePrefixClick(prefix, this) }
            }

            val lp = GridLayout.LayoutParams(
                GridLayout.spec(row, GridLayout.FILL, 1f),
                GridLayout.spec(col, GridLayout.FILL, 1f)
            ).apply {
                width = 0
                height = 0
                setMargins(margin, margin, margin, margin)
            }

            prefixGrid.addView(btn, lp)
        }
    }

    private fun handlePrefixClick(prefix: String, clickedBtn: Button) {
        val rooms = roomMap[prefix].orEmpty()
        if (rooms.isEmpty()) return

        if (rooms.size == 1) {
            applySelection(clickedBtn, rooms[0])
            return
        }

        showRoomButtonDialog(clickedBtn, rooms)
    }

    private fun showRoomButtonDialog(prefixBtn: Button, rooms: List<String>) {
        var dialog: androidx.appcompat.app.AlertDialog? = null

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(16))
        }

        val title = TextView(this).apply {
            text = "請選擇病房"
            // ✅ [放大字體] 彈出視窗標題放大
            textSize = 44f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#212121"))
            setPadding(0, 0, 0, dp(14))
        }
        root.addView(title)

        rooms.forEachIndexed { idx, room ->
            val btn = Button(this).apply {
                text = room
                isAllCaps = false
                // ✅ [放大字體] 彈出視窗內的選項按鈕字體放大
                textSize = 38f
                setTextColor(Color.WHITE)
                background = roundedBg("#6750A4", 20)
                setOnClickListener {
                    dialog?.dismiss()
                    applySelection(prefixBtn, room)
                }
            }

            root.addView(
                btn,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(100) // ✅ 按鈕高度配合字體加大 (從 88dp 增加到 100dp)
                ).apply { topMargin = if (idx == 0) 0 else dp(12) }
            )
        }

        dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(root)
            .setCancelable(true)
            .create()

        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.92).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
    }

    private fun applySelection(prefixBtn: Button, room: String) {
        selectedPrefixButton?.background = roundedBg("#6750A4", 14)
        prefixBtn.background = roundedBg("#2E7D32", 14)
        selectedPrefixButton = prefixBtn

        tvSelectedWard.text = "目前選擇：$room"

        // 去下一頁（衛教/政策選單）
        val i = Intent(this, RoomActionMenuActivity::class.java).apply {
            putExtra(RoomActionMenuActivity.EXTRA_ROOM, room)
        }
        startActivity(i)
    }

    private fun calculateColumns(): Int {
        val widthDp = resources.configuration.screenWidthDp
        return when {
            widthDp >= 1100 -> 6
            widthDp >= 800 -> 6
            else -> 4
        }
    }

    private fun roundedBg(colorHex: String, radiusDp: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(Color.parseColor(colorHex))
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}