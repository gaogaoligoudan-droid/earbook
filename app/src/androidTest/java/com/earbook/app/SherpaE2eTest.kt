package com.earbook.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.earbook.app.tts.ModelManager
import com.earbook.app.tts.SherpaTtsEngine
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * sherpa 引擎端到端（CI 模拟器 x86_64）：
 * 模型下载→解包→引擎初始化→中文合成→RTF 基准（方案 A 级假设验证）。
 *
 * RTF = 合成耗时 / 音频时长：<1.0 即实时可用（模拟器 CPU 与手机不可直接换算，
 * 但「跑通+量级」是硬证据；手机端基准待真机验收 F4/F5 补充）。
 */
@RunWith(AndroidJUnit4::class)
class SherpaE2eTest {

    companion object {
        private const val TAG = "SherpaE2E"
    }

    @Test
    fun modelDownload_unpackage_synthesize() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext

        // 1. 模型获取（CI 网络：下载+解包；幂等）
        val t0 = System.currentTimeMillis()
        val dir = ModelManager.downloadIfNeeded(ctx)
        val downloadMs = System.currentTimeMillis() - t0
        assertTrue("模型就绪标记未落", ModelManager.isReady(ctx))
        assertTrue("模型文件缺失", dir.resolve("model.int8.onnx").exists())
        assertTrue("词典缺失", dir.resolve("dict").isDirectory)
        Log.i(TAG, "模型下载+解包耗时 ${downloadMs / 1000}s")

        // 2. 引擎初始化 + 中文合成（含引擎内 TN：数字转读）
        val engine = SherpaTtsEngine(ctx, dir)
        val t1 = System.currentTimeMillis()
        val pcm = engine.synthesizeFull("你好，欢迎使用听书应用，现在是3点5分。")
        val synthMs = System.currentTimeMillis() - t1

        // 3. 断言有实际音频 + RTF 基准
        val sampleRate = engine.sampleRate()
        assertTrue("采样率异常: $sampleRate", sampleRate in 8000..48000)
        assertTrue("合成 PCM 过短: ${pcm.size}", pcm.size > sampleRate) // >1 秒音频
        val audioSec = pcm.size.toDouble() / sampleRate
        val rtf = synthMs / 1000.0 / audioSec
        Log.i(TAG, "合成 ${synthMs}ms / 音频 ${"%.1f".format(audioSec)}s → RTF=${"%.2f".format(rtf)}（<1.0=实时可用）")
        Log.i(TAG, "PCM 抽样: max=${pcm.max()} min=${pcm.min()}（非静音验证）")
        assertTrue("PCM 全静音", pcm.any { Math.abs(it) > 0.01f })

        engine.release()
    }
}
