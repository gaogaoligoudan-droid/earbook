package com.earbook.app

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.earbook.app.databinding.ActivitySettingsBinding
import com.earbook.app.playback.ChapterAudioCache
import com.earbook.app.service.ReadAloudService

/**
 * 设置页（M2-5 缓存管理 UI）：
 * - 缓存上限滑块（100-300MB，变更即时生效：超限即时 LRU 静默清理+toast）
 * - 当前缓存占用 + 总占用叙事（含模型体积，防「滑块 300 实际 500MB」的被骗感）
 * - DeepSeek API Key（M3 AI 优化层 BYOK 预埋位，flag 关闭时隐藏）
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        title = "设置"

        setupCacheSection()
        setupVoiceSection()
        setupApiKeySection()
    }

    // ── 音色（试听拍板：7 号女声 zf sid=30 / 10 号男声 zm sid=70） ──

    private fun setupVoiceSection() {
        val prefs = getSharedPreferences("earbook", MODE_PRIVATE)
        fun refresh() {
            val male = prefs.getString("voice", "female") == "male"
            binding.tvVoiceHint.text =
                if (male) "新书默认：男声（10 号）｜每本书可在书籍管理页单独切换"
                else "新书默认：女声（7 号）｜每本书可在书籍管理页单独切换"
            binding.btnVoiceFemale.isEnabled = !male
            binding.btnVoiceMale.isEnabled = male
        }
        binding.btnVoiceFemale.setOnClickListener {
            prefs.edit().putString("voice", "female").apply(); refresh()
            Toast.makeText(this, "新书默认音色：女声", Toast.LENGTH_SHORT).show()
        }
        binding.btnVoiceMale.setOnClickListener {
            prefs.edit().putString("voice", "male").apply(); refresh()
            Toast.makeText(this, "新书默认音色：男声", Toast.LENGTH_SHORT).show()
        }
        refresh()
    }

    // ── M4 R5 播放速度 ─────────────────────────────────────

    private fun setupSpeedSection() {
        refreshSpeed()
        binding.btnSpeed.setOnClickListener {
            // 正在播放 → 发给 Service 即时应用；未播放 → 本地循环写偏好
            if (ReadAloudService.currentBookId != null) {
                ReadAloudService.send(this, ReadAloudService.ACTION_CYCLE_SPEED)
            } else {
                ReadAloudService.cycleSpeedPref(this)
            }
            refreshSpeed()
        }
    }

    private fun refreshSpeed() {
        val speed = getSharedPreferences("earbook", MODE_PRIVATE).getFloat("play_speed", 1f)
        val label = if (speed == speed.toInt().toFloat()) "${speed.toInt()}x" else "${speed}x"
        binding.tvSpeedHint.text = "当前：$label（0.8~2.0x 保音高，不重新合成；播放中切换即时生效）"
    }

    // ── 缓存管理（M1：无上限无 LRU，R11 拍板；滑块已移除） ──

    private fun setupCacheSection() {
        val cache = ChapterAudioCache(this)

        fun fmt(mb: Long): String = when {
            mb >= 1024 -> "%.1fGB".format(mb / 1024.0)
            else -> "${mb}MB"
        }

        fun refreshCacheStats() {
            val audioMb = cache.totalBytes() / (1024 * 1024)
            val modelMb = 147L // kokoro int8 常驻模型
            binding.tvCacheStatus.text =
                "语音缓存：${fmt(audioMb)}（不设上限，按书管理） ｜ 模型：${fmt(modelMb)}（常驻）"
        }

        binding.btnStorage.setOnClickListener {
            startActivity(android.content.Intent(this, StorageActivity::class.java))
        }

        binding.btnClearCache.setOnClickListener {
            cache.clearAll()
            refreshCacheStats()
            Toast.makeText(this, "已清空全部语音缓存", Toast.LENGTH_SHORT).show()
        }

        refreshCacheStats()
    }

    // ── AI 优化（M3 预埋） ────────────────────────────────

    private fun setupApiKeySection() {
        val prefs = getSharedPreferences("earbook", MODE_PRIVATE)
        binding.etApiKey.setText(prefs.getString(DEEPSEEK_KEY, ""))
        binding.btnSaveApiKey.setOnClickListener {
            val key = binding.etApiKey.text.toString().trim()
            prefs.edit().putString(DEEPSEEK_KEY, key).apply()
            Toast.makeText(this, if (key.isEmpty()) "已清除，AI 优化关闭" else "已保存，AI 优化开启", Toast.LENGTH_SHORT).show()
        }
        // M3 未落地前文案如实：仅保存，优化管线尚未启用
        binding.tvApiKeyHint.text = "保存后用于导入时的文本优化（AI 增强层开发中，暂未生效）"
    }

    companion object {
        const val DEEPSEEK_KEY = "deepseek_key"
    }
}
