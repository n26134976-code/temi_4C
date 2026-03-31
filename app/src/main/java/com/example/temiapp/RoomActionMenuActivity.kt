package com.example.temiapp

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * 病房動作選單：
 * 病床選好後先停在這頁，讓使用者先決定要「衛教宣導」或「政策宣導」。
 *
 * ✅ 衛教宣導：先進 VideoActivity 選影片；選完影片才去病房，抵達後自動播放。
 * ✅ 政策宣導：進 BroadcastActivity，並帶入目標病房。
 */
class RoomActionMenuActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ROOM = "extra_room"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_room_action_menu)

        val room = intent.getStringExtra(EXTRA_ROOM).orEmpty()
        findViewById<TextView>(R.id.tv_room_title).text = "目前病房：$room"

        findViewById<Button>(R.id.btn_health_edu).setOnClickListener {
            val i = Intent(this, VideoActivity::class.java).apply {
                putExtra(VideoActivity.EXTRA_MODE, VideoActivity.MODE_HEALTH_EDU)
                putExtra(VideoActivity.EXTRA_ROOM, room)
                putExtra(VideoActivity.EXTRA_AFTER_ASK_AND_CHARGE, true)
            }
            startActivity(i)
        }

        findViewById<Button>(R.id.btn_policy).setOnClickListener {
            val i = Intent(this, BroadcastActivity::class.java).apply {
                putExtra(BroadcastActivity.EXTRA_TARGET_ROOM, room)
            }
            startActivity(i)
        }

        findViewById<Button>(R.id.btn_back).setOnClickListener {
            finish()
        }
    }
}