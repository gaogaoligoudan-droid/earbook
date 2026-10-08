package com.earbook.app

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.earbook.app.book.Book
import com.earbook.app.databinding.ActivityWizardBinding
import com.earbook.app.playback.RenderPrefs
import com.earbook.app.tts.ModelManager
import com.earbook.app.tts.SherpaTtsEngine
import com.earbook.app.tts.VoicePrefs

/**
 * 首次单一向导（M3，R6+R8b+R1+R11，鼓励式）：
 * 欢迎 → 新书默认模式 → 音色试听 → 后台授权+存储说明 → 完成。
 * 四步一次走完（用户拍板「单一向导」，不再分散弹窗）；此后各选择均可改
 * （模式/音色在书籍管理页，授权在重装前一次为准）。
 */
class WizardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWizardBinding
    private var step = 0

    // 试听引擎（懒加载；切音色 sid 变化时重建）
    private var previewEngine: SherpaTtsEngine? = null
    private var previewSid = -1
    private var previewThread: Thread? = null
    @Volatile private var previewBusy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWizardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnNext.setOnClickListener { onNext() }
        binding.btnBack.setOnClickListener { onBack() }
        binding.btnPreviewVoice.setOnClickListener { previewVoice() }
    }

    private fun onNext() {
        when (step) {
            1 -> { /* 模式选择无必填校验 */ }
            3 -> { finishWizard(); return }
        }
        step++
        binding.flipper.displayedChild = step
        binding.btnBack.visibility = if (step == 0) View.GONE else View.VISIBLE
        binding.btnNext.text = if (step == 3) "开始使用" else "下一步"
    }

    private fun onBack() {
        if (step == 0) return
        step--
        binding.flipper.displayedChild = step
        binding.btnBack.visibility = if (step == 0) View.GONE else View.VISIBLE
        binding.btnNext.text = "下一步"
    }

    private fun selectedMode(): String =
        if (binding.rbModeSystem.isChecked) Book.MODE_SYSTEM else Book.MODE_NEURAL

    private fun selectedVoice(): String =
        if (binding.rbVoiceMale.isChecked) Book.VOICE_MALE else Book.VOICE_FEMALE

    /** 试听：当前所选音色合成一句并播放（引擎按 sid 懒加载复用） */
    private fun previewVoice() {
        if (!ModelManager.isReady(this)) {
            Toast.makeText(this, "语音模型准备中，稍后再试", Toast.LENGTH_SHORT).show()
            return
        }
        if (previewBusy) return
        val sid = VoicePrefs.sid(this).let {
            // 向导页音色是「新书默认」，试听跟随本页所选（不写全局 prefs）
            if (binding.rbVoiceMale.isChecked) SherpaTtsEngine.SPEAKER_MALE
            else SherpaTtsEngine.SPEAKER_FEMALE
        }
        previewBusy = true
        binding.tvPreviewState.visibility = View.VISIBLE
        binding.tvPreviewState.text = "正在准备试听…（首次约几秒）"
        previewThread = Thread({
            try {
                val engine = obtainEngine(sid)
                val pcm = engine.synthesizeFull("你好，我是你的听书声音。")
                playPcm(engine.sampleRate(), pcm)
                runOnUiThread {
                    binding.tvPreviewState.visibility = View.GONE
                }
            } catch (e: Exception) {
                runOnUiThread {
                    binding.tvPreviewState.text = "试听失败：${e.message}"
                }
            } finally {
                previewBusy = false
            }
        }, "wizard-preview").also { it.start() }
    }

    private fun obtainEngine(sid: Int): SherpaTtsEngine {
        val existing = previewEngine
        if (existing != null && previewSid == sid) return existing
        existing?.release()
        val e = SherpaTtsEngine(this, ModelManager.modelDir(this), sid)
        previewEngine = e
        previewSid = sid
        return e
    }

    /** 试听短句直接播（向导生命周期短，不做焦点/断点管理） */
    private fun playPcm(sampleRate: Int, pcm: FloatArray) {
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(
                (AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_FLOAT) * 4).coerceAtLeast(16384))
            .build()
        track.setVolume(1f)
        track.play()
        track.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
        // 排空等待播完
        val written = pcm.size
        var head = 0
        while (head < written) {
            Thread.sleep(50)
            head = track.playbackHeadPosition
        }
        Thread.sleep(120)
        runCatching { track.stop() }
        track.release()
    }

    private fun finishWizard() {
        val prefs = getSharedPreferences("earbook", Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean("wizard_done", true)
            .putString("default_mode", selectedMode())
            .putString("default_voice", selectedVoice())
            .apply()
        RenderPrefs.setAuth(
            this,
            if (binding.rbAuthCharging.isChecked) RenderPrefs.CHARGING else RenderPrefs.BATTERY
        )
        releasePreview()
        Toast.makeText(this, "设置完成，导入一本书开始吧！", Toast.LENGTH_LONG).show()
        finish()
    }

    private fun releasePreview() {
        previewThread?.interrupt()
        previewEngine?.release()
        previewEngine = null
    }

    override fun onBackPressed() {
        AlertDialog.Builder(this)
            .setTitle("跳过设置？")
            .setMessage("可以随时在设置里重新调整。")
            .setPositiveButton("继续设置", null)
            .setNegativeButton("跳过") { _, _ ->
                getSharedPreferences("earbook", Context.MODE_PRIVATE)
                    .edit().putBoolean("wizard_done", true).apply()
                releasePreview()
                finish()
            }
            .show()
    }

    override fun onDestroy() {
        releasePreview()
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, WizardActivity::class.java))
        }
    }
}
