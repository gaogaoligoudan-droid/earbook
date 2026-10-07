#!/system/bin/sh
# EarBook 0.2.0 零网络验证（自愈式：断网→验证→自动恢复网络→落日志）
# 背景：隧道本身走手机 wifi，「先断网再远程验证」会切断自己的 lifeline。
# 方案：脚本在手机侧 detached 运行，全程不依赖远程连接，跑完自动恢复网络。
LOG=/data/local/tmp/offline-verify.log

echo "=== $(date) offline verify start" > $LOG
# 1. 断网（wifi+data 全关）
svc wifi disable
svc data disable
sleep 3
echo "airplane=$(settings get global airplane_mode_on) wifi_on=$(settings get global wifi_on)" >> $LOG

# 2. E2E：模型从内置 assets 解包 + 引擎初始化 + 中文合成断言（无 UI 依赖）
echo "--- am instrument SherpaE2eTest" >> $LOG
am instrument -w -e class com.earbook.app.SherpaE2eTest \
  com.earbook.app.test/androidx.test.runner.AndroidJUnitRunner >> $LOG 2>&1
echo "instrument_rc=$?" >> $LOG

# 3. 落盘证据：模型目录 + .ok 标记
echo "--- model files:" >> $LOG
run-as com.earbook.app ls -la files/kokoro-int8-multi-lang-v1_1/ >> $LOG 2>&1
echo "marker=$(run-as com.earbook.app ls files/kokoro-int8-multi-lang-v1_1/.ok 2>&1)" >> $LOG
run-as com.earbook.app md5sum files/kokoro-int8-multi-lang-v1_1/model.int8.onnx files/kokoro-int8-multi-lang-v1_1/voices.bin >> $LOG 2>&1

# 4. 恢复网络（自愈）
svc wifi enable
svc data enable
echo "=== network restored, done" >> $LOG
