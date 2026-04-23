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

    // FIX2: timeout + 卡住偵測
    private val NAV_TIMEOUT = 30000L
    private var navTimeoutRunnable: Runnable? = null
    private var lastPosition: com.robotemi.sdk.navigation.model.Position? = null
    private var stuckStartTime: Long = 0


    //儲存 / 讀取導覽進度
    // FIX: retry 機制
    private var retryCount = 0
    private val MAX_RETRY = 2

    // FIX: 導覽進度
    private var tourIndex = 0
    private val tourList = listOf(
        "護理站",
        "治療室",
        "污物室",
        "晴空樹屋",
        "佈告欄",
        "洗衣烘乾室",
        "電子佈告欄",
        "配膳室",
        "輪椅推車區",
        "門口",
        "諮詢站"
    )

//    // FIX2: 判斷是否卡住（5秒幾乎沒動），原版
//    private fun isStuck(): Boolean {
//        val current = try {
//            robot.getPosition()
//        } catch (e: Exception) {
//            return false
//        }
//
//        val last = lastPosition
//
//        if (last != null) {
//            val dx = current.x - last.x
//            val dy = current.y - last.y
//            val dist = Math.sqrt((dx * dx + dy * dy).toDouble())
//
//            if (dist < 0.15) { // 幾乎沒動（15cm）
//                if (stuckStartTime == 0L) {
//                    stuckStartTime = System.currentTimeMillis()
//                }
//
//                val stuckTime = System.currentTimeMillis() - stuckStartTime
//
//                if (stuckTime > 5000) {
//                    Log.e(TAG, "判定卡住 (5秒沒動)")
//                    return true
//                }
//            } else {
//                // 有在動 → reset
//                stuckStartTime = 0
//            }
//        }
//
//        lastPosition = current
//        return false
//    }

    // FIX2: 卡住偵測（升級版：降頻 + 防抖 + 5秒無移動判定）
    private var lastCheckTime = 0L
    private fun isStuck(): Boolean {

        // ✔ FIX2: 降低取樣頻率（500ms 檢查一次）
        val now = System.currentTimeMillis()
        if (now - lastCheckTime < 500) {
            return false
        }
        lastCheckTime = now

        val current = try {
            robot.getPosition()
        } catch (e: Exception) {
            return false
        }

        val last = lastPosition

        if (last != null) {

            val dx = current.x - last.x
            val dy = current.y - last.y
            val dist = kotlin.math.sqrt((dx * dx + dy * dy).toDouble())

            // ✔ FIX2: 幾乎沒移動（< 15cm）
            if (dist < 0.15) {

                // 開始計時卡住時間
                if (stuckStartTime == 0L) {
                    stuckStartTime = now
                }

                val stuckTime = now - stuckStartTime

                // ✔ FIX2: 連續 5 秒幾乎沒動 → 判定卡住
                if (stuckTime > 5000) {
                    Log.e(TAG, "FIX2: 判定卡住（5秒無有效移動）")

                    // reset 避免重複觸發
                    stuckStartTime = 0L
                    lastPosition = current

                    return true
                }

            } else {
                // ✔ 有明顯移動 → reset
                stuckStartTime = 0L
            }

        } else {
            // 第一次初始化位置
            stuckStartTime = 0L
        }

        lastPosition = current
        return false
    }

    // FIX: 根據地點取得圖片
    private fun getLocationImage(location: String): Int {
        return when (location) {
            "護理站" -> R.drawable.nursing_station_img
            "治療室" -> R.drawable.treatment_room_img
            "污物室", "汙物室" -> R.drawable.dirty_room_img
            "洗衣烘乾室" -> R.drawable.laundry_img
            "配膳室" -> R.drawable.pantry_img
            "輪椅推車區" -> R.drawable.wheelchaircart_img
            "門口" -> R.drawable.entrance_img
            "晴空樹屋" -> R.drawable.treehouse_img
            "佈告欄" -> R.drawable.bulletin_board_img
            "電子佈告欄" -> R.drawable.digital_bulletin_board_img
            "諮詢站" -> R.drawable.information_desk_img
            else -> R.drawable.nursing_station_img // 預設圖（避免 crash）
        }
    }

    // FIX
    private fun saveTourProgress() {
        val sp = getSharedPreferences("tour_progress", MODE_PRIVATE)
        sp.edit().putInt("tour_index", tourIndex).apply()
    }

    // FIX
    private fun loadTourProgress(): Int {
        val sp = getSharedPreferences("tour_progress", MODE_PRIVATE)
        return sp.getInt("tour_index", 0)
    }

    // FIX
    private fun clearTourProgress() {
        val sp = getSharedPreferences("tour_progress", MODE_PRIVATE)
        sp.edit().clear().apply()
    }


    //修改音樂
    private val musicList = listOf(
        R.raw.moving_music,
        R.raw.moving_music_2,
        R.raw.moving_music_3
    )
    private var currentMusicIndex = 0

    //移動播音樂
    private fun startMovingMusic() {
        if (mediaPlayer == null) {
            mediaPlayer = MediaPlayer.create(this, musicList[currentMusicIndex]) // 音樂檔放 raw
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

    //選擇音樂
    private fun showMusicDialog() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_music_select)
        dialog.setCancelable(true)

        val btn1 = dialog.findViewById<Button>(R.id.btn_music1)
        val btn2 = dialog.findViewById<Button>(R.id.btn_music2)
        val btn3 = dialog.findViewById<Button>(R.id.btn_music3)

        btn1.setOnClickListener {
            switchMusic(0)
            dialog.dismiss()
        }
        btn2.setOnClickListener {
            switchMusic(1)
            dialog.dismiss()
        }
        btn3.setOnClickListener {
            switchMusic(2)
            dialog.dismiss()
        }
        dialog.show()
    }

    //****
    private fun saveMusicIndex(index: Int) {
        val sp = getSharedPreferences("app_settings", MODE_PRIVATE)
        sp.edit().putInt("music_index", index).apply()
    }

    private fun loadMusicIndex() {
        val sp = getSharedPreferences("app_settings", MODE_PRIVATE)
        currentMusicIndex = sp.getInt("music_index", 0)
    }

    //******

    private fun switchMusic(index: Int) {
        currentMusicIndex = index
        saveMusicIndex(index)   // ✅ 加這行***

        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null

        mediaPlayer = MediaPlayer.create(this, musicList[currentMusicIndex])
        mediaPlayer?.isLooping = true
        mediaPlayer?.setVolume(0.3f, 0.3f)
        mediaPlayer?.start()

        Toast.makeText(this, "已切換為 Music ${index + 1}", Toast.LENGTH_SHORT).show()

        // 5秒後停止音樂
        handler.postDelayed({
            mediaPlayer?.let { mp ->
                if (mp.isPlaying) {
                    mp.stop()
                    mp.release()
                    mediaPlayer = null
                }
            }
        }, 5000)
    }

    //根據今天日期更改音樂
    private fun getTodayFestivalMusic(): Int? {
        val today = java.text.SimpleDateFormat("MMdd", java.util.Locale.getDefault())
            .format(java.util.Date())

        return when (today) {
            "1225" -> 1   // 🎄 聖誕節 → musicList[1]
            "0415" -> 2   // 🎆 元旦 → musicList[2]
            "0214" -> 0   // ❤️ 情人節 → musicList[0]
            else -> null  // 平常日
        }
    }

    //測試日期更改音樂，使用分鐘做變化
//    private fun getTodayFestivalMusic(): Int? {
//        val calendar = java.util.Calendar.getInstance()
//        val minute = calendar.get(java.util.Calendar.MINUTE)
//
//        // 👉 每分鐘輪流測試不同音樂
//        return when (minute % 3) {
//            0 -> 0   // 模擬「情人節」
//            1 -> 1   // 模擬「聖誕節」
//            2 -> 2   // 模擬「元旦」
//            else -> null
//        }
//    }


    private fun applyFestivalMusicIfNeeded() {
        val festivalIndex = getTodayFestivalMusic() ?: return

        val sp = getSharedPreferences("app_settings", MODE_PRIVATE)
        val lastDate = sp.getString("festival_date", "")

        val today = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault())
            .format(java.util.Date())

        //測試日期更改音樂，使用分鐘做變化
//        val today = java.text.SimpleDateFormat("yyyyMMddHHmm", java.util.Locale.getDefault())
//            .format(java.util.Date())

        // ✅ 同一天不要重複切
        if (lastDate == today) return

        currentMusicIndex = festivalIndex

        sp.edit()
            .putInt("music_index", festivalIndex)
            .putString("festival_date", today)
            .apply()

        Log.d(TAG, "節日音樂啟用 index=$festivalIndex")
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
        "這裡是護理站，新病人報到、需要護理師協助或量身高體重可直接來這裡"
    private val dirtyRoomText =
        "這裡是污物室，髒衣物更換後需要清洗請拿到這裡，放進藍色污衣桶。旁邊推車上面放的是乾淨的枕頭套及床單，需要更換可以自己拿取，提醒!!若需要更換棉被或病人服，請找護理師，依院方規定請不要囤積被服"
    private val treatmentRoomText =
        "這裡是治療室，需要打針、抽血、放管路、做治療處置時，請將小孩帶到這裡"
    private val laundryText =
        "這裡是洗衣間，需要洗衣烘衣可以投幣使用"
    private val entranceText =
        "這裡是4C病房大門，我們設有門禁管控，旁邊有感應機器，提醒!!進入請使用陪病證感應"
    private val pantryRoomText =
        "這裡是配膳室，可裝飲用水，裡面有廚餘桶、垃圾桶、資源回收處，請記得分類丟棄，提醒!!清洗餐具時請勿丟入雜物到排水管內"
    private val wheelchairCartText =
        "這裡是放置輪椅/推車的地方，有需要使用可以自己推走，提醒!!使用完畢後請推至污物室清潔"
    private val treehouseText =
        "這裡是一個安靜漂亮的小空間，邀請你在出院的日子一起來做親子共讀，享受舒服的氛圍~"
    private val BulletinBoardText =
        "這裡有我們日常的活動日程表及宣導事項，來走路散步的時候可以看看喔!"
    private val DigitalBulletinBoardText =
        "這裡有我們兒童樂園的成員介紹、相關疾病的衛教、宣導影音，走過路過千萬別錯過，建議停下來欣賞欣賞喔~"
    private val InformationDeskText =
        "這裡是護理站也是諮詢站，書記會在此處，若您需要辦理出院或查詢住院費用請諮詢書記"


    private var isHandlingFailure = false // FIX: 防止重複觸發

    private val goToLocationStatusListener = object : OnGoToLocationStatusChangedListener {
        override fun onGoToLocationStatusChanged(
            location: String,
            status: String,
            descriptionId: Int,
            description: String
        ) {
            if (!isTouring && !layoutOverlay.isShown) return //如果temi到定點不會說話刪除這行

            // =========================
            // FIX2: 卡住偵測（只在 moving 狀態檢查）
            // =========================
            if (status.equals("going", true)) {
                if (isStuck()) {
                    Log.e(TAG, "偵測到卡住 → 原地導覽")

                    robot.stopMovement()

                    speechManager.speak("前方無法通行，我在這裡為您介紹")

                    navTimeoutRunnable?.let { handler.removeCallbacks(it) }

                    handleArrivalLogic(location)
                    return
                }
            }

            if (status.equals("complete", ignoreCase = true)) {
                // FIX2: 記得取消 timeout
                navTimeoutRunnable?.let { handler.removeCallbacks(it) }

                isHandlingFailure = false // FIX
                retryCount = 0 // FIX: 成功後重置
                stopMovingMusic()
                handleArrivalLogic(location.trim())
                return  //FIX
            }

            // FIX: 導航失敗處理
            if (status.equals("abort", true) || status.equals("fail", true)) {
                // FIX2
                navTimeoutRunnable?.let { handler.removeCallbacks(it) }

                // FIX: 避免連續觸發
                if (isHandlingFailure) return
                isHandlingFailure = true

                Log.e(TAG, "導航失敗: $location")
                speechManager.speak("導航失敗")

                val target = activeTarget ?: location // FIX: 用正確目標

                if (retryCount < MAX_RETRY) {
                    retryCount++
                    runOnUiThread {
                        Toast.makeText(this@NavigationActivity,"前往 $target 失敗，重試第 $retryCount 次",Toast.LENGTH_SHORT).show()
                    }

                    // ✅ 語音（只講一次，不要每次 fail 都講）
                    if (retryCount == 1) {
                        speechManager.speak("前方路線受阻，正在重新嘗試")
                    }

                    handler.postDelayed({
                        isHandlingFailure = false // FIX: 允許下一次 retry
                        startGoToLocation(location, isTouring)
                    }, 1500)

                } else {
                    Log.e(TAG, "重試失敗，改為原地導覽")
                    speechManager.speak("重試失敗，改為原地導覽")
                    retryCount = 0
                    isHandlingFailure = false
                    // 👉 直接觸發到達邏輯（原地講解）
                    handleArrivalLogic(location)
                }
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
        applyFestivalMusicIfNeeded() //根據日期更改音樂
        loadMusicIndex() //***

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
        // FIX2
        navTimeoutRunnable?.let { handler.removeCallbacks(it) }

        // FIX: 儲存目前進度
        if (isTouring) {
            saveTourProgress()
        }

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
        val btnTreehouse =            findViewById<Button>(R.id.btn_loc_treehouse)
        val btnBulletinBoard =        findViewById<Button>(R.id.btn_loc_bulletin_board)
        val btnDigitalBulletinBoard = findViewById<Button>(R.id.btn_loc_digital_bulletin_board)
        val btnInformationDesk =      findViewById<Button>(R.id.btn_loc_information_desk)
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
        //改音樂按鈕
        val btnMusic = findViewById<Button>(R.id.btn_music)

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
        btnTreehouse.setOnClickListener            { startGoToLocation("晴空樹屋", false) }
        btnBulletinBoard.setOnClickListener        { startGoToLocation("佈告欄", false) }
        btnDigitalBulletinBoard.setOnClickListener { startGoToLocation("電子佈告欄", false) }
        btnInformationDesk.setOnClickListener      { startGoToLocation("諮詢站", false) }
        btnCharge.setOnClickListener            { startGoToLocation("充電座", false) }

        btnFullTour.setOnClickListener {
            startFullTour()
        }
        btnMusic.setOnClickListener {
            showMusicDialog()
        }

        btnBack.setOnClickListener { finish() }
    }

    private fun startFullTour() {
//        startMovingMusic()
        if (!robot.isReady) return

        val lastIndex = loadTourProgress() // FIX

        if (lastIndex > 0) {
            showResumeDialog(lastIndex) // FIX
            return
        }

        isTouring = true
        tourIndex = 0 // FIX
        saveTourProgress() // FIX，可能可以刪除
        isReturningToStart = false
        showOverlayUI("開始全區導覽，前往護理站...", R.drawable.nursing_station_img)
        speechManager.speak("你好，我是temi，我是導覽小幫手，接下來由我來幫您介紹4C兒童樂園的整體環境")
        // ✅ 統一走這裡（會自動播音樂）
        //startGoToLocation("護理站", true)  //原版
        {
            startGoToLocation(tourList[tourIndex], true) // FIX
        }
    }

    private fun startGoToLocation(locationName: String, tourMode: Boolean) {
//        startMovingMusic()
        handler.removeCallbacksAndMessages(null)
        if (!robot.isReady) return

        isTouring = tourMode
        if (!tourMode) isReturningToStart = false

        val normalizedLocation = normalizeTargetName(locationName)
        activeTarget = normalizedLocation

        val goToName = resolveGoToName(normalizedLocation)
        val displayName = if (normalizedLocation == "home base") "充電座" else normalizedLocation

        // FIX: 取得對應圖片
        val imageRes = getLocationImage(displayName)

        if (!tourMode) {
//            showOverlayUI("正在前往：$displayName...", R.drawable.nursing_station_img)
            showOverlayUI("正在前往：$displayName...",imageRes)
        }

        speechManager.speak("現在前往$displayName") {
            startMovingMusic()
        }

        robot.goTo(goToName)
        Toast.makeText(this, "前往 $displayName", Toast.LENGTH_SHORT).show()

        // FIX2: 啟動 timeout 機制
        navTimeoutRunnable?.let { handler.removeCallbacks(it) }
        navTimeoutRunnable = Runnable {
            Log.e(TAG, "導航逾時 → fallback")
            robot.stopMovement()
            speechManager.speak("路線受阻，我在這裡為您介紹")
            handleArrivalLogic(locationName)
        }
        handler.postDelayed(navTimeoutRunnable!!, NAV_TIMEOUT)
        //


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
        if (isTouring && location == "諮詢站" && isReturningToStart) {
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
            "晴空樹屋" ->           Pair(treehouseText, R.drawable.treehouse_img)
            "佈告欄" ->             Pair(BulletinBoardText, R.drawable.bulletin_board_img)
            "電子佈告欄" ->         Pair(DigitalBulletinBoardText, R.drawable.digital_bulletin_board_img)
            "諮詢站" ->             Pair(InformationDeskText, R.drawable.information_desk_img)

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
            // FIX: index 控制 instead of 原本 mapping
            tourIndex++

            if (tourIndex < tourList.size) {
                saveTourProgress() // FIX
                val nextLocation = tourList[tourIndex]
                if (nextLocation == "諮詢站") isReturningToStart = true
                runOnUiThread {
                    txtSubtitle.text = "即將前往：$nextLocation..."
                    Toast.makeText(this, "導覽繼續，2秒後前往：$nextLocation", Toast.LENGTH_SHORT).show()
                }
                handler.postDelayed({
                    startGoToLocation(nextLocation, true)
                }, 2000)
            } else {
                // FIX: 完成導覽
                clearTourProgress()

                isTouring = false
                hideOverlayUI()
            }
        } else {
            hideOverlayUI()
            if (currentLocation == "諮詢站") {
                runOnUiThread { showCustomDialog() }
            }
        }
    }

    private fun getNextTourLocation(current: String): String? {
        return when (current) {
            "護理站" -> "治療室"
            "治療室" -> "污物室"
            "污物室", "汙物室" -> "晴空樹屋"
            "晴空樹屋" -> "佈告欄"
            "佈告欄", "布告欄"   -> "洗衣烘乾室"
            "洗衣烘乾室" -> "電子佈告欄"
            "電子佈告欄" -> "配膳室"
            "配膳室" -> "輪椅推車區"
            "輪椅推車區" -> "門口"
            "門口" -> "諮詢站"


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

    //新增「是否繼續播」Dialog
    // FIX
    private fun showResumeDialog(lastIndex: Int) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_resume)

        val btnYes = dialog.findViewById<Button>(R.id.btn_yes)
        val btnNo = dialog.findViewById<Button>(R.id.btn_no)

        btnYes.setOnClickListener {
            dialog.dismiss()

            isTouring = true
            tourIndex = lastIndex

            startGoToLocation(tourList[tourIndex], true)
        }

        btnNo.setOnClickListener {
            dialog.dismiss()

            clearTourProgress()

            isTouring = true
            tourIndex = 0

            startFullTour()
        }

        dialog.show()
    }

}
