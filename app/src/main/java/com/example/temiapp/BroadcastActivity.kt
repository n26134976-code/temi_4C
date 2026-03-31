package com.example.temiapp

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.robotemi.sdk.Robot
import com.robotemi.sdk.listeners.OnGoToLocationStatusChangedListener
import com.robotemi.sdk.listeners.OnRobotReadyListener

class BroadcastActivity : AppCompatActivity(),
    OnRobotReadyListener,
    OnGoToLocationStatusChangedListener {

    private lateinit var robot: Robot
    private lateinit var speechManager: SpeechManager
    private lateinit var listView: ListView
    private var selectedText: String? = null

    private var isPatrolling = false

    private val patrolRoute = listOf(
        "護理站",
        "廣播a",
        "廣播b",
        "廣播c",
        "廣播d",
        "護理站"
    )

    private var currentRouteIndex = 0

    private val broadcastTopics = mapOf(
        "領口罩須知" to "提醒您，進入本院區請全程配戴口罩，保護您我安全。",
        "安全針具定義" to "安全針具定義為：醫療機構對於所屬醫事人員執行直接接觸病人體液或血液之醫療處置時，所使用之防護器具。",
        "狂犬病宣導" to "狂犬病是由病毒引起的神經性疾病，請務必定期為家中寵物施打疫苗。",
        "住院安寧廣播" to "現在時間晚上九點，請降低音量，給予病患安靜的休息空間。"
    )

    companion object {
        const val EXTRA_TARGET_ROOM = "extra_target_room"
    }

    private var targetRoom: String = ""
    private var isRoomRouteBroadcast = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_broadcast)

        robot = Robot.getInstance()
        speechManager = SpeechManager(this, robot)
        listView = findViewById(R.id.listview_broadcast_topics)

        targetRoom = intent.getStringExtra(EXTRA_TARGET_ROOM).orEmpty().trim()
        isRoomRouteBroadcast = targetRoom.isNotEmpty()

        setupListView()
        setupButtons()
    }

    override fun onStart() {
        super.onStart()
        robot.addOnRobotReadyListener(this)
        robot.addOnGoToLocationStatusChangedListener(this)
    }

    override fun onStop() {
        robot.removeOnRobotReadyListener(this)
        robot.removeOnGoToLocationStatusChangedListener(this)
        speechManager.stop()
        super.onStop()
    }

    override fun onDestroy() {
        speechManager.shutdown()
        super.onDestroy()
    }

    override fun onRobotReady(isReady: Boolean) = Unit

    private fun setupListView() {
        val adapter = ArrayAdapter(
            this,
            R.layout.list_item_large,
            broadcastTopics.keys.toList()
        )

        listView.adapter = adapter
        listView.choiceMode = ListView.CHOICE_MODE_SINGLE
        listView.setOnItemClickListener { _, _, position, _ ->
            val topicTitle = adapter.getItem(position)
            selectedText = broadcastTopics[topicTitle]
            Toast.makeText(this, "已選擇：$topicTitle", Toast.LENGTH_SHORT).show()
        }
    }

    private fun normNoSpace(s: String): String =
        s.replace(Regex("[\\s\\u3000]+"), "").lowercase()
    private fun setupButtons() {
        findViewById<Button>(R.id.btn_start_broadcast).setOnClickListener { startBroadcast() }

        findViewById<Button>(R.id.btn_stop_broadcast).setOnClickListener {
            stopBroadcast()
            finish()
        }

        findViewById<Button>(R.id.btn_back).setOnClickListener {
            stopBroadcast()
            finish()
        }
    }

    private fun startBroadcast() {
        if (selectedText == null) {
            Toast.makeText(this, "請先選擇要廣播的內容", Toast.LENGTH_SHORT).show()
            return
        }
        if (!robot.isReady) return

        isPatrolling = true
        currentRouteIndex = 0

        if (isRoomRouteBroadcast) {
            val goToTarget = normalizeRoomTarget(targetRoom)
            robot.goTo(goToTarget)
            Toast.makeText(this, "開始政策宣導，前往：$goToTarget（途中持續播報）", Toast.LENGTH_SHORT).show()
            speakLoop()
            return
        }

        val firstLocation = patrolRoute[0]
        robot.goTo(firstLocation)
        Toast.makeText(this, "開始巡邏廣播，前往：$firstLocation", Toast.LENGTH_SHORT).show()
        speakLoop()
    }

    private fun speakLoop() {
        val text = selectedText ?: return
        if (!isPatrolling) return

        speechManager.speak(text) {
            if (isPatrolling) {
                speakLoop()
            }
        }
    }

    override fun onGoToLocationStatusChanged(
        location: String,
        status: String,
        descriptionId: Int,
        description: String
    ) {
        if (!isPatrolling) return

        if (isRoomRouteBroadcast) {
            if (status.equals("complete", ignoreCase = true) &&
                normNoSpace(location) == normNoSpace(targetRoom)
            ) {
                Toast.makeText(this, "已抵達 $location，政策宣導結束", Toast.LENGTH_SHORT).show()
                stopBroadcast()
                return
            }

            if (status.equals("abort", ignoreCase = true)) {
                Toast.makeText(this, "導航中斷，停止政策宣導", Toast.LENGTH_LONG).show()
                stopBroadcast()
            }
            return
        }

        if (status.equals("complete", ignoreCase = true)) {
            currentRouteIndex++
            if (currentRouteIndex < patrolRoute.size) {
                val nextLocation = patrolRoute[currentRouteIndex]
                Toast.makeText(this, "抵達 $location，轉往：$nextLocation", Toast.LENGTH_SHORT).show()
                robot.goTo(nextLocation)
            } else {
                Toast.makeText(this, "巡邏結束，已回到護理站！", Toast.LENGTH_LONG).show()
                stopBroadcast()
            }
        } else if (status.equals("abort", ignoreCase = true)) {
            Toast.makeText(this, "導航中斷，停止廣播", Toast.LENGTH_LONG).show()
            stopBroadcast()
        }
    }

    private fun normalizeRoomTarget(raw: String): String {
        val clean = raw.trim()
        val upper = clean.uppercase()
        return if (Regex("^8\\d{2}[ABC]?$").matches(upper)) upper.lowercase() else clean
    }

    private fun stopBroadcast() {
        isPatrolling = false
        currentRouteIndex = 0
        speechManager.stop()
        robot.stopMovement()
    }
}
