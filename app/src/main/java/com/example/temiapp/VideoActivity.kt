package com.example.temiapp

import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.Window
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import com.robotemi.sdk.Robot

class VideoActivity : AppCompatActivity() {

    companion object {
        const val MODE_HEALTH_EDU = "health_edu"

        const val EXTRA_MODE = "extra_mode"
        const val EXTRA_ROOM = "extra_room"
        const val EXTRA_AUTOPLAY_KEY = "extra_autoplay_key"
        const val EXTRA_AFTER_ASK_AND_CHARGE = "extra_after_ask_and_charge"

        const val KEY_WARD_NOTICE = "ward_notice"
        const val KEY_ORAL_CLEAN = "oral_clean"
        const val KEY_CHEMO_TUBE = "chemo_tube"
        const val KEY_SURGERY_NOTICE = "surgery_notice"
    }

    data class Slide(val imageResId: Int, val textToSpeak: String)

    private lateinit var robot: Robot
    private lateinit var speechManager: SpeechManager

    private lateinit var layoutMenu: LinearLayout
    private lateinit var layoutFullscreen: View
    private lateinit var btnBack: Button

    private lateinit var videoView: VideoView
    private lateinit var imgSlideshow: ImageView
    private lateinit var tvSubtitle: TextView

    private lateinit var btnPauseResume: Button
    private lateinit var btnExitMedia: Button

    private var mode: String = MODE_HEALTH_EDU
    private var room: String = ""
    private var autoplayKey: String = ""
    private var afterAskAndCharge: Boolean = false

    private var isPlayingSlideshow = false
    private var isMediaPaused = false
    private var currentSlideIndex = 0
    private var currentSlidesList: List<Slide> = emptyList()

    private val surgerySlides = listOf(
        Slide(R.drawable.slide_1, "大多數病人及家屬在得知將進行手術治療時，都非常緊張，這是一種正常的反應，為了讓您減少緊張的情緒且了解一般手術的準備過程，請您詳閱以下有關手術前及手術後注意事項："),
        Slide(R.drawable.slide_2, "手術前 1.提醒您若有慢性病應事先告知醫師服用的藥物，評估是否繼續服用。 2. 清潔 手術前一晚請先洗淨身體，包括洗頭、刮鬍子、剪指甲，腹部手術者需特別注意肚臍的清潔。 3. 住院手術前，請去除指甲油(包含光療指甲及水晶指甲) 4. 手術前各項檢查及準備 您在入院後，會做一些例行性的檢查(如心電圖、X 光、抽血、尿液等)。"),
        Slide(R.drawable.slide_3, "住院當日 1.填寫手術及麻醉同意書 請詳讀同意書內容後簽名。若未滿18歲，請法定代理人填寫。 2.醫師會向您確認手術部位並劃上記號，不可移除。 3. 灌腸 腹部或腸道手術者手術前二天就開始清潔腸子(灌腸)，吃低渣飲食，目的是使腸子內清潔乾淨，減少手術後傷口感染。 4.練習深呼吸、咳嗽及翻身活動 手術前護理師會教您練習深呼吸咳嗽，請您務必勤加練習，因為手術後您可能因全身麻醉會有痰，須靠此技巧將痰咳出，以防肺炎發生。"),
        Slide(R.drawable.slide_4, "手術當日 1.手術前一晚(午夜12點起)開始禁食，包括開水都不能喝，因為麻醉中可能會嘔吐導致吸入性肺炎及呼吸道阻塞。 2.送至開刀房前會為您接上靜脈點滴，並確定點滴順暢。 3.去除身上飾品：如活動假牙、隱形眼鏡、項鍊、手錶、戒指或髮夾等，貴重物品請家人保管，若無家人陪同，可請護理站代為保管。 4. 當護理師通知要送您去手術房時，請先上廁所排空膀胱，並換上手術衣，勿穿內衣褲(女性胸罩)手術衣請反穿，以套入方式穿著，帶子綁在背後。 5.手術時，工作人員會送您進入3 樓手術室，請家屬陪同前往，並在等候室等候，手術後恢復室專人照顧您直到清醒，再送回病房。"),
        Slide(R.drawable.slide_5, "手術結束後，全身麻醉及腰椎麻醉的病人會先送到恢復室休息，等清醒後再送回病房。 手術後可能發生之問題有 1. 喉嚨痛：因手術時喉內插氣管內管之故，約 1～2 天會改善。 2. 傷口痛：傷口疼痛時請告知醫師或護理師，我們會幫忙病人處理。 3. 嘔吐：若有嘔吐，請告知護理師，並將頭偏一側，可利用塑膠袋接嘔吐物。 4. 傷口引流管：手術後若有引流管，請勿移動它。 5. 更換傷口敷料：醫師會訂定更換傷口敷料的頻率及方式，醫師或護理師會為病人進行換藥。 6. 翻身、咳嗽及深呼吸：為了病人術後肺功能之正常及早日康復，請多翻身及拍背咳痰。 最後手術後醫護人員依照您的恢復狀況來做更完善的手術後治療與照顧，且會循序漸進安排您的手術後活動，最重要的必須您本身的配合才能使得手術後的不適減至最低且迅速恢復。成大醫院關心您!!")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video)

        robot = Robot.getInstance()
        speechManager = SpeechManager(this, robot)

        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_HEALTH_EDU
        room = intent.getStringExtra(EXTRA_ROOM).orEmpty().trim()
        autoplayKey = intent.getStringExtra(EXTRA_AUTOPLAY_KEY).orEmpty().trim()
        afterAskAndCharge = intent.getBooleanExtra(EXTRA_AFTER_ASK_AND_CHARGE, false)

        layoutMenu = findViewById(R.id.layout_menu)
        layoutFullscreen = findViewById(R.id.layout_fullscreen_media)
        btnBack = findViewById(R.id.btn_back)

        videoView = findViewById(R.id.video_view)
        imgSlideshow = findViewById(R.id.img_slideshow)
        tvSubtitle = findViewById(R.id.tv_subtitle)

        btnPauseResume = findViewById(R.id.btn_pause_resume)
        btnExitMedia = findViewById(R.id.btn_exit_media)

        findViewById<Button>(R.id.btn_play_all).setOnClickListener { onPick(KEY_WARD_NOTICE) }
        findViewById<Button>(R.id.btn_video_1).setOnClickListener { onPick(KEY_ORAL_CLEAN) }
        findViewById<Button>(R.id.btn_video_2).setOnClickListener { onPick(KEY_CHEMO_TUBE) }
        findViewById<Button>(R.id.btn_video_3).setOnClickListener { onPick(KEY_SURGERY_NOTICE) }

        btnBack.setOnClickListener { finish() }
        videoView.setOnCompletionListener { onPlaybackFinished() }
        btnExitMedia.setOnClickListener { stopEverything() }

        btnPauseResume.setOnClickListener {
            if (isMediaPaused) {
                isMediaPaused = false
                btnPauseResume.text = "暫停播放"
                btnPauseResume.setBackgroundColor(android.graphics.Color.parseColor("#FF9800"))

                if (isPlayingSlideshow) {
                    playCurrentSlide()
                } else {
                    videoView.start()
                }
            } else {
                isMediaPaused = true
                btnPauseResume.text = "繼續播放"
                btnPauseResume.setBackgroundColor(android.graphics.Color.parseColor("#4CAF50"))

                if (isPlayingSlideshow) {
                    speechManager.stop()
                } else {
                    videoView.pause()
                }
            }
        }

        if (autoplayKey.isNotEmpty()) {
            playByKey(autoplayKey)
        }
    }

    override fun onDestroy() {
        stopEverything()
        speechManager.shutdown()
        super.onDestroy()
    }

    private fun onPick(key: String) {
        if (room.isNotEmpty() && autoplayKey.isEmpty()) {
            val i = Intent(this, NavigationActivity::class.java).apply {
                putExtra(NavigationActivity.EXTRA_TARGET_LOCATION, room)
                putExtra(NavigationActivity.EXTRA_SOURCE_QUERY, "video_select")
                putExtra(NavigationActivity.EXTRA_START_VIDEO_ON_ARRIVAL, true)
                putExtra(NavigationActivity.EXTRA_VIDEO_MODE, mode)
                putExtra(NavigationActivity.EXTRA_VIDEO_KEY, key)
            }
            startActivity(i)
            finish()
        } else {
            playByKey(key)
        }
    }

    private fun playByKey(key: String) {
        layoutMenu.visibility = View.GONE
        btnBack.visibility = View.GONE
        layoutFullscreen.visibility = View.VISIBLE

        isMediaPaused = false
        btnPauseResume.text = "暫停播放"
        btnPauseResume.setBackgroundColor(android.graphics.Color.parseColor("#FF9800"))

        if (key == KEY_SURGERY_NOTICE) {
            startSlideshow(surgerySlides)
        } else {
            val rawName = when (key) {
                KEY_WARD_NOTICE -> "safety_guide"
                KEY_ORAL_CLEAN -> "oral_hygiene"
                KEY_CHEMO_TUBE -> "care"
                else -> ""
            }

            if (rawName.isBlank()) return

            val resId = resources.getIdentifier(rawName, "raw", packageName)
            if (resId != 0) {
                isPlayingSlideshow = false
                imgSlideshow.visibility = View.GONE
                tvSubtitle.visibility = View.GONE
                videoView.visibility = View.VISIBLE

                val uri = Uri.parse("android.resource://$packageName/$resId")
                videoView.setVideoURI(uri)
                videoView.start()
            } else {
                Toast.makeText(this, "找不到影片檔", Toast.LENGTH_SHORT).show()
                stopEverything()
            }
        }
    }

    private fun startSlideshow(slides: List<Slide>) {
        if (slides.isEmpty()) return
        currentSlidesList = slides
        currentSlideIndex = 0
        isPlayingSlideshow = true

        videoView.visibility = View.GONE
        imgSlideshow.visibility = View.VISIBLE
        tvSubtitle.visibility = View.VISIBLE
        playCurrentSlide()
    }

    private fun playCurrentSlide() {
        if (!isPlayingSlideshow || isMediaPaused) return
        val slide = currentSlidesList[currentSlideIndex]
        imgSlideshow.setImageResource(slide.imageResId)
        tvSubtitle.text = slide.textToSpeak

        speechManager.speak(slide.textToSpeak) {
            if (!isPlayingSlideshow || isMediaPaused) return@speak

            currentSlideIndex++
            runOnUiThread {
                if (currentSlideIndex < currentSlidesList.size) {
                    playCurrentSlide()
                } else {
                    onPlaybackFinished()
                }
            }
        }
    }

    private fun onPlaybackFinished() {
        stopEverything()
        if (afterAskAndCharge) {
            showQuestionDialog()
        }
    }

    private fun stopEverything() {
        try {
            if (videoView.isPlaying) videoView.stopPlayback()
        } catch (_: Exception) {
        }
        isPlayingSlideshow = false
        isMediaPaused = false
        speechManager.stop()

        layoutFullscreen.visibility = View.GONE
        videoView.visibility = View.GONE
        imgSlideshow.visibility = View.GONE
        tvSubtitle.visibility = View.GONE
        layoutMenu.visibility = View.VISIBLE
        btnBack.visibility = View.VISIBLE
    }

    private fun showQuestionDialog() {
        if (isFinishing || isDestroyed) return

        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_question)
        dialog.setCancelable(false)

        val btnYes = dialog.findViewById<Button>(R.id.btn_yes)
        val btnNo = dialog.findViewById<Button>(R.id.btn_no)

        btnYes.setOnClickListener {
            speechManager.speak("請問有什麼需要幫忙的呢？")
            dialog.dismiss()
            if (room.isNotEmpty()) {
                startActivity(Intent(this, RoomActionMenuActivity::class.java).apply {
                    putExtra(RoomActionMenuActivity.EXTRA_ROOM, room)
                })
            }
            finish()
        }

        btnNo.setOnClickListener {
            speechManager.speak("好的，謝謝您，我現在回充電座。")
            dialog.dismiss()

            val i = Intent(this, NavigationActivity::class.java).apply {
                putExtra(NavigationActivity.EXTRA_TARGET_LOCATION, "充電座")
                putExtra(NavigationActivity.EXTRA_SOURCE_QUERY, "return_charge")
                putExtra(NavigationActivity.EXTRA_START_VIDEO_ON_ARRIVAL, false)
            }
            startActivity(i)
            finish()
        }

        dialog.show()
        speechManager.speak("請問是否還有其他問題？")
    }
}
