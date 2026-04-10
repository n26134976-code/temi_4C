package com.example.temiapp

import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.Window
import android.widget.Button
import android.widget.ImageView
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import android.media.MediaPlayer
import com.robotemi.sdk.Robot
import com.robotemi.sdk.listeners.OnGoToLocationStatusChangedListener
import com.robotemi.sdk.listeners.OnRobotReadyListener

class NavigationActivity : AppCompatActivity(), OnRobotReadyListener {

    companion object {
        const val EXTRA_TARGET_LOCATION = "extra_target_location"
        const val EXTRA_SOURCE_QUERY = "extra_source_query"
        const val EXTRA_START_VIDEO_ON_ARRIVAL = "extra_start_video_on_arrival"
        const val EXTRA_VIDEO_MODE = "extra_video_mode"
        const val EXTRA_VIDEO_KEY = "extra_video_key"
        const val EXTRA_START_FULL_TOUR = "extra_start_full_tour"
        private const val TAG = "NavigationActivity"
    }

    private var pendingAutoTarget: String? = null
    private var startVideoOnArrival: Boolean = false
    private var videoMode: String = VideoActivity.MODE_HEALTH_EDU
    private var videoKey: String = ""
    private var pendingStartFullTour: Boolean = false

    private var activeTarget: String? = null
    private var arrivalConsumed: Boolean = false
    private var isRobotReady: Boolean = false

    private lateinit var robot: Robot
    private lateinit var speechManager: SpeechManager
    private var isTouring = false
    private var isReturningToStart = false

    private var currentDialog: Dialog? = null

    private var mediaPlayer: MediaPlayer? = null

    private lateinit var layoutOverlay: RelativeLayout
    private lateinit var imgOverlay: ImageView
    private lateinit var txtSubtitle: TextView

    private val handler = Handler(Looper.getMainLooper())

    //移動播音樂
    private fun startMovingMusic() {
        if (mediaPlayer == null) {
            mediaPlayer = MediaPlayer.create(this, R.raw.moving_music) // 音樂檔放 raw
            mediaPlayer?.isLooping = true
            mediaPlayer?.setVolume(0.3f, 0.3f)
        }
        if (mediaPlayer?.isPlaying != true) {
            mediaPlayer?.start()
        }
    }

    private fun stopMovingMusic() {
        mediaPlayer?.pause()
        mediaPlayer?.seekTo(0)
    }

    private fun normNoSpace(s: String): String =
        s.replace(Regex("[\\s\\u3000]+"), "").lowercase()

    private fun resolveGoToName(internalKey: String): String {
        val locs = try {
            robot.locations
        } catch (_: Exception) {
            emptyList<String>()
        }

        if (locs.isEmpty()) return internalKey
        if (locs.contains(internalKey)) return internalKey

        val m = Regex("^(8\\d{2})([a-z])$").find(internalKey)
        if (m != null) {
            val prefix = m.groupValues[1]
            val suf = m.groupValues[2]
            val cand1 = "$prefix $suf"
            val cand2 = "$prefix ${suf.uppercase()}"
            if (locs.contains(cand1)) return cand1
            if (locs.contains(cand2)) return cand2
        }

        val hit = locs.firstOrNull { normNoSpace(it) == normNoSpace(internalKey) }
        return hit ?: internalKey
    }

    private fun isWardLocation(name: String): Boolean {
        val key = name.replace(Regex("[\\s\\u3000]+"), "").uppercase()
        return Regex("^8\\d{2}[ABC]?$").matches(key)
    }

    private fun wardKey(name: String): String =
        name.replace(Regex("[\\s\\u3000]+"), "").lowercase()

    private val nursingStationText =
        "這裡是護理站和諮詢站，若您有任何醫療需求，請諮詢護理站人員；若您需要辦理出院或查詢住院費用請至諮詢站諮詢書記。"
    private val dirtyRoomText =
        "這裡是污物室，請依垃圾分類標示丟棄正確物品、衣服棉被請放入藍色污衣桶、尿布請丟棄至洗手台旁尿布垃圾桶，非醫療廢棄物請至配膳室執行垃圾分類。"
    private val treatmentRoomText =
        "這裡是治療室，提供各項醫療處置與治療服務，請依照醫護人員指示進入並配合相關流程。"
    private val laundryText =
        "這裡是洗衣烘乾室，提供住院期間衣物清洗與烘乾服務，請依照使用規範操作設備。"
    private val entranceText =
        "這裡是門口，為出入病房的主要通道，請留意進出安全並配合相關訪客管理規定。"
    private val pantryRoomText =
        "這裡是配膳室，為了愛護地球，請您依垃圾分類標示完成垃圾分類，廚餘請倒入廚餘桶；這裡也有製冰機，僅供冰敷或冰枕使用，不可以食用；而飲水機半夜會有消毒時間，取用時請注意時間。"
    private val wheelchairCartText =
        "這裡是輪椅推車放置處，若您需要使用輪椅推車時，請自行取用，使用完畢請主動歸位。"


    private val goToLocationStatusListener = object : OnGoToLocationStatusChangedListener {
        override fun onGoToLocationStatusChanged(
            location: String,
            status: String,
            descriptionId: Int,
            description: String
        ) {
            if (!isTouring && !layoutOverlay.isShown) return

            if (status.equals("complete", ignoreCase = true)) {
                stopMovingMusic()
                handleArrivalLogic(location.trim())
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_navigation)

        startVideoOnArrival = intent.getBooleanExtra(EXTRA_START_VIDEO_ON_ARRIVAL, false)
        videoMode = intent.getStringExtra(EXTRA_VIDEO_MODE) ?: VideoActivity.MODE_HEALTH_EDU
        videoKey = intent.getStringExtra(EXTRA_VIDEO_KEY).orEmpty()
        arrivalConsumed = false

        robot = Robot.getInstance()
        speechManager = SpeechManager(this, robot)

        layoutOverlay = findViewById(R.id.layout_overlay)
        imgOverlay = findViewById(R.id.img_overlay_location)
        txtSubtitle = findViewById(R.id.txt_overlay_subtitle)

        setupNavigationButtons()
        handleAutoNavigationFromIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        startVideoOnArrival = intent.getBooleanExtra(EXTRA_START_VIDEO_ON_ARRIVAL, false)
        videoMode = intent.getStringExtra(EXTRA_VIDEO_MODE) ?: VideoActivity.MODE_HEALTH_EDU
        videoKey = intent.getStringExtra(EXTRA_VIDEO_KEY).orEmpty()
        arrivalConsumed = false

        handleAutoNavigationFromIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        robot.addOnRobotReadyListener(this)
        robot.addOnGoToLocationStatusChangedListener(goToLocationStatusListener)
    }

    override fun onStop() {
        robot.removeOnRobotReadyListener(this)
        robot.removeOnGoToLocationStatusChangedListener(goToLocationStatusListener)
        forceStopEverything()
        super.onStop()
    }

    override fun onDestroy() {
        mediaPlayer?.release()
        mediaPlayer = null
        speechManager.shutdown()
        super.onDestroy()
    }

    override fun onRobotReady(isReady: Boolean) {
        isRobotReady = isReady
        if (!isReady) return

        if (pendingStartFullTour) {
            startFullTour()
            pendingStartFullTour = false
        }

        pendingAutoTarget?.let { target ->
            startGoToLocation(target, false)
            pendingAutoTarget = null
        }
    }

    private fun handleAutoNavigationFromIntent(intent: Intent?) {
        val i = intent ?: return

        if (i.getBooleanExtra(EXTRA_START_FULL_TOUR, false)) {
            if (isRobotReady || robot.isReady) {
                startFullTour()
            } else {
                pendingStartFullTour = true
                Toast.makeText(this, "已收到指令：開始全區導覽", Toast.LENGTH_SHORT).show()
            }
            return
        }

        val raw = i.getStringExtra(EXTRA_TARGET_LOCATION)?.trim().orEmpty()
        if (raw.isBlank()) return

        val target = normalizeTargetName(raw)
        val sourceQuery = i.getStringExtra(EXTRA_SOURCE_QUERY).orEmpty()
        Log.d(TAG, "auto target=$target, query=$sourceQuery")

        activeTarget = target

        if (isRobotReady || robot.isReady) {
            startGoToLocation(target, false)
        } else {
            pendingAutoTarget = target
            Toast.makeText(this, "已收到指令：$target", Toast.LENGTH_SHORT).show()
        }
    }

    private fun normalizeTargetName(raw: String): String {
        val trimmed = raw.trim().replace("汙物室", "污物室")

        val lowerNoSpace = trimmed
            .replace(Regex("[\\s\\u3000]+"), "")
            .lowercase()

        if (trimmed == "充電座" || lowerNoSpace == "homebase") {
            return "home base"
        }

        val clean = trimmed.replace(Regex("[\\s\\u3000]+"), "")
        val upper = clean.uppercase()
        if (Regex("^8\\d{2}[ABC]?$").matches(upper)) return upper.lowercase()

        return clean
    }

    private fun forceStopEverything() {
        stopMovingMusic()
        robot.stopMovement()
        speechManager.stop()
        handler.removeCallbacksAndMessages(null)

        isTouring = false
        isReturningToStart = false

        hideOverlayUI()

        currentDialog?.dismiss()
        currentDialog = null
    }

    private fun setupNavigationButtons() {
        // 注意:button的xml裡的id還沒改 (改了)
        val btnNursing =        findViewById<Button>(R.id.btn_loc_nursing)
        val btnTreatment =      findViewById<Button>(R.id.btn_loc_treatment)
        val btnDirty =          findViewById<Button>(R.id.btn_loc_dirty)
        val btnLaundry =        findViewById<Button>(R.id.btn_loc_laundry)
        val btnPantry =         findViewById<Button>(R.id.btn_loc_pantry)
        val btnWheelchairCart = findViewById<Button>(R.id.btn_loc_wheelchair_cart)
        val btnEntrance =       findViewById<Button>(R.id.btn_loc_entrance)
//        val btnNursing = findViewById<Button>(R.id.btn_loc_nursing)
//        val btnPantry = findViewById<Button>(R.id.btn_loc_pantry)
//        val btnDirty = findViewById<Button>(R.id.btn_loc_dirty)
//        val btnLinen = findViewById<Button>(R.id.btn_loc_linen)
//        val btnWheelchair = findViewById<Button>(R.id.btn_loc_wheelchair)
//        val btnScale = findViewById<Button>(R.id.btn_loc_scale)
        val btnCharge =         findViewById<Button>(R.id.btn_go_charge)
        val btnFullTour =       findViewById<Button>(R.id.btn_full_tour)
        val btnBack =           findViewById<Button>(R.id.btn_back)
        val btnSkip =           findViewById<Button>(R.id.btn_skip)

        btnSkip.setOnClickListener { forceStopEverything() }

//        btnNursing.setOnClickListener { startGoToLocation("護理站", false) }
//        btnPantry.setOnClickListener { startGoToLocation("配膳室", false) }
//        btnDirty.setOnClickListener { startGoToLocation("污物室", false) }
//        btnLinen.setOnClickListener { startGoToLocation("被服車", false) }
//        btnWheelchair.setOnClickListener { startGoToLocation("輪椅區", false) }
//        btnScale.setOnClickListener { startGoToLocation("體重計", false) }
        btnNursing.setOnClickListener           { startGoToLocation("護理站", false) }
        btnTreatment.setOnClickListener         { startGoToLocation("治療室", false) }
        btnDirty.setOnClickListener             { startGoToLocation("污物室", false) }
        btnLaundry.setOnClickListener           { startGoToLocation("洗衣烘乾室", false) }
        btnPantry.setOnClickListener            { startGoToLocation("配膳室", false) }
        btnWheelchairCart.setOnClickListener    { startGoToLocation("輪椅推車區", false) }
        btnEntrance.setOnClickListener          { startGoToLocation("門口", false) }
        btnCharge.setOnClickListener            { startGoToLocation("充電座", false) }

        btnFullTour.setOnClickListener {
            startFullTour()
        }

        btnBack.setOnClickListener { finish() }
    }

    private fun startFullTour() {
        if (!robot.isReady) return
        isTouring = true
        isReturningToStart = false
        showOverlayUI("開始全區導覽，前往護理站...", R.drawable.nursing_station_img)
        // ✅ 統一走這裡（會自動播音樂）
        startGoToLocation("護理站", true)
    }

    private fun startGoToLocation(locationName: String, tourMode: Boolean) {
        handler.removeCallbacksAndMessages(null)
        if (!robot.isReady) return

        isTouring = tourMode
        if (!tourMode) isReturningToStart = false

        val normalizedLocation = normalizeTargetName(locationName)
        activeTarget = normalizedLocation

        val goToName = resolveGoToName(normalizedLocation)
        val displayName = if (normalizedLocation == "home base") "充電座" else normalizedLocation

        if (!tourMode) {
            showOverlayUI("正在前往：$displayName...", R.drawable.nursing_station_img)
        }

        speechManager.speak("現在前往$displayName") {
            startMovingMusic()
        }
        robot.goTo(goToName)
        Toast.makeText(this, "前往 $displayName", Toast.LENGTH_SHORT).show()
    }

    private fun showWardQuestionDialog(roomKey: String) {
        if (isFinishing || isDestroyed) return

        val dialog = Dialog(this)
        currentDialog = dialog
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_question)
        dialog.setCancelable(false)

        val btnYes = dialog.findViewById<Button>(R.id.btn_yes)
        val btnNo = dialog.findViewById<Button>(R.id.btn_no)

        btnYes.setOnClickListener {
            speechManager.speak("請問有什麼需要幫忙的呢？")
            dialog.dismiss()
            currentDialog = null
            startActivity(
                Intent(this, RoomActionMenuActivity::class.java).apply {
                    putExtra(RoomActionMenuActivity.EXTRA_ROOM, roomKey)
                }
            )
            finish()
        }

        btnNo.setOnClickListener {
            speechManager.speak("好的，謝謝您，我現在回充電座。")
            dialog.dismiss()
            currentDialog = null

            val i = Intent(this, NavigationActivity::class.java).apply {
                putExtra(EXTRA_TARGET_LOCATION, "充電座")
                putExtra(EXTRA_SOURCE_QUERY, "return_charge")
                putExtra(EXTRA_START_VIDEO_ON_ARRIVAL, false)
            }
            startActivity(i)
            finish()
        }

        dialog.show()
        speechManager.speak("請問是否還有其他問題？")
    }

    private fun handleArrivalLogic(location: String) {
        if (isTouring && location == "護理站" && isReturningToStart) {
            isTouring = false
            isReturningToStart = false
            runOnUiThread {
                Toast.makeText(this, "全區導覽結束", Toast.LENGTH_SHORT).show()
                hideOverlayUI()
                showCustomDialog()
            }
            return
        }

        if (startVideoOnArrival && !arrivalConsumed) {
            val target = activeTarget?.trim().orEmpty()
            if (target.isNotEmpty() && normNoSpace(location) == normNoSpace(target)) {
                arrivalConsumed = true
                val i = Intent(this, VideoActivity::class.java).apply {
                    putExtra(VideoActivity.EXTRA_MODE, videoMode)
                    putExtra(VideoActivity.EXTRA_ROOM, target)
                    putExtra(VideoActivity.EXTRA_AUTOPLAY_KEY, videoKey)
                    putExtra(VideoActivity.EXTRA_AFTER_ASK_AND_CHARGE, true)
                }
                startActivity(i)
                finish()
                return
            }
        }

        val onActionComplete = { checkNextMove(location) }

        val locationData = when (location) {
            "護理站" ->             Pair(nursingStationText, R.drawable.nursing_station_img)
            "治療室" ->             Pair(treatmentRoomText, R.drawable.treatment_room_img)
            "污物室", "汙物室" ->   Pair(dirtyRoomText, R.drawable.dirty_room_img)
            "洗衣烘乾室" ->         Pair(laundryText, R.drawable.laundry_img)
            "配膳室" ->             Pair(pantryRoomText, R.drawable.pantry_img)
            "輪椅推車區" ->         Pair(wheelchairCartText, R.drawable.wheelchaircart_img)
            "門口" ->               Pair(entranceText, R.drawable.entrance_img)

//            "護理站" -> Pair(nursingStationText, R.drawable.nursing_station_img)
//            "體重計" -> Pair(scaleText, R.drawable.scale_img)
//            "被服車" -> Pair(linenText, R.drawable.linen_img)
//            "污物室", "汙物室" -> Pair(dirtyRoomText, R.drawable.dirty_room_img)
//            "配膳室" -> Pair(pantryRoomText, R.drawable.pantry_img)
//            "輪椅區" -> Pair(wheelchairText, R.drawable.wheelchair_img)
            // 注意:圖片還沒改 (改了)
            else -> null
        }

        if (locationData != null) {
            val (speakText, imageResId) = locationData
            showOverlayUI(speakText, imageResId)
            speakWithCallback(speakText, onActionComplete)
        } else {
            if (location == "home base" || location == "充電座") {
                speechManager.speak("很高興為您服務，我現在要充電了。")
            } else {
                if (!isTouring && isWardLocation(location)) {
                    hideOverlayUI()
                    showWardQuestionDialog(wardKey(location))
                    return
                }

                speechManager.speak("$location 到了。")
            }
            if (!isTouring) hideOverlayUI()
        }
    }

    private fun speakWithCallback(text: String, onComplete: () -> Unit) {
        speechManager.speak(text) {
            runOnUiThread {
                Handler(Looper.getMainLooper()).postDelayed({
                    if (isTouring || layoutOverlay.visibility == View.VISIBLE) {
                        onComplete()
                    }
                }, 500)
            }
        }
    }

    private fun checkNextMove(currentLocation: String) {
        if (isTouring) {
            val nextLocation = getNextTourLocation(currentLocation)
            if (nextLocation != null) {
                if (nextLocation == "護理站") isReturningToStart = true

                runOnUiThread {
                    txtSubtitle.text = "即將前往：$nextLocation..."
                    Toast.makeText(this, "導覽繼續，2秒後前往：$nextLocation", Toast.LENGTH_SHORT).show()
                }

                handler.postDelayed({
                    // ✅ 改這裡：統一走導航方法
                    startGoToLocation(nextLocation, true)
                }, 2000)
            } else {
                isTouring = false
                hideOverlayUI()
            }
        } else {
            hideOverlayUI()
            if (currentLocation == "出口") {
                runOnUiThread { showCustomDialog() }
            }
        }
    }

    private fun getNextTourLocation(current: String): String? {
        return when (current) {
            "護理站" -> "治療室"
            "治療室" -> "污物室"
            "污物室", "汙物室" -> "洗衣烘乾室"
            "洗衣烘乾室" -> "配膳室"
            "配膳室" -> "輪椅推車區"
            "輪椅推車區" -> "門口"
//            "門口" -> "護理站"


//            "護理站" -> "體重計"
//            "體重計" -> "污物室"
//            "污物室", "汙物室" -> "被服車"
//            "被服車" -> "配膳室"
//            "配膳室" -> "輪椅區"
//            "輪椅區" -> "護理站"
            else -> null
        }
    }

    private fun showOverlayUI(subtitle: String, imageResId: Int) {
        runOnUiThread {
            txtSubtitle.text = subtitle
            imgOverlay.setImageResource(imageResId)
            layoutOverlay.visibility = View.VISIBLE
        }
    }

    private fun hideOverlayUI() {
        runOnUiThread { layoutOverlay.visibility = View.GONE }
    }

    private fun showCustomDialog() {
        if (isFinishing || isDestroyed) return

        val dialog = Dialog(this)
        currentDialog = dialog

        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_question)
        dialog.setCancelable(false)

        val btnYes = dialog.findViewById<Button>(R.id.btn_yes)
        val btnNo = dialog.findViewById<Button>(R.id.btn_no)

        btnYes.setOnClickListener {
            speechManager.speak("請問有什麼需要幫忙的呢？")
            dialog.dismiss()
            currentDialog = null
        }

        btnNo.setOnClickListener {
            speechManager.speak("好的，謝謝您")
            dialog.dismiss()
            currentDialog = null
        }

        dialog.show()
        speechManager.speak("請問是否還有其他問題？")
    }
}
