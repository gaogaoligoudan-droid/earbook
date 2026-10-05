package com.earbook.app

import android.os.Bundle
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.earbook.app.databinding.ActivitySettingsBinding
import com.earbook.app.playback.ChapterAudioCache

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
        setupApiKeySection()
    }

    // ── 缓存管理 ──────────────────────────────────────────

    private fun setupCacheSection() {
        val cache = ChapterAudioCache(this)
        val prefs = getSharedPreferences("earbook", MODE_PRIVATE)

        fun fmt(mb: Long): String = when {
            mb >= 1024 -> "%.1fGB".format(mb / 1024.0)
            else -> "${mb}MB"
        }

        fun refreshCacheStats() {
            val audioMb = cache.totalBytes() / (1024 * 1024)
            val modelMb = 147L // kokoro int8 常驻模型
            binding.tvCacheStatus.text = "语音缓存：${fmt(audioMb)} ｜ 模型：${fmt(modelMb)}（常驻）"
        }

        val limitMb = prefs.getInt(CACHE_LIMIT_MB, 200)
        binding.sbCacheLimit.min = 100
        binding.sbCacheLimit.max = 300
        binding.sbCacheLimit.progress = limitMb
        binding.tvCacheLimit.text = "${limitMb}MB"

        // 滑块变更即时生效：超限 LRU 静默清理 + toast 反馈（不弹确认框——拉滑块意图明确）
        binding.sbCacheLimit.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.tvCacheLimit.text = "${progress}MB"
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}

            override fun onStopTrackingTouch(sb: SeekBar?) {
                val newLimit = sb?.progress ?: return
                val freed = cache.shrinkTo(newLimit * 1024L * 1024, anchorBookId = null)
                prefs.edit().putInt(CACHE_LIMIT_MB, newLimit).apply()
                refreshCacheStats()
                if (freed > 0) {
                    Toast.makeText(
                        this@SettingsActivity,
                        "已释放 ${freed / (1024 * 1024)}MB",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        })

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
        const val CACHE_LIMIT_MB = "cache_limit_mb"
        const val DEEPSEEK_KEY = "deepseek_key"
    }
}
