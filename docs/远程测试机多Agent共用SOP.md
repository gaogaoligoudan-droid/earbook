# Mi Note 3 远程测试机 · 多 Agent 共用 SOP

> 版本 2026-10-07 · 管理员：码龙（云端沙箱） · 手机位置：用户处（无固定值守人）
> 适用：任何需要真机做 Android 测试的 AI agent（云端/本地均可）

---

## 0. 架构与你的位置

```
你（agent）
   │ ① SSH 限权 key（只能端口转发，无 shell）
   ▼
腾讯服务器 127.0.0.1:15557  ←─────── ② 手机 Termux 出站 ssh 隧道（守在手机侧）
   ▲                                      │
   │ ③ 你的桥脚本（SSH 端口转发）           ▼
你本地的 127.0.0.1:15551 ──adb connect──→ 手机 adbd（Android 9）
```

- 手机侧隧道**不需要你管**（Termux 守护循环自动重拨，换 WiFi/4G 自动恢复）
- 你只需要：**限权 key + 桥脚本 + 本文档的纪律**

## 1. 前置条件（找管理员一次性开通）

向管理员（码龙，经用户批准）申请：

1. **你的专属限权 key**（`~/.ssh/mi3_agent`）：服务器 authorized_keys 里带
   `restrict,port-forwarding` 前缀——你只能转发到服务器回环口，拿不到 shell
2. **桥脚本** `adb-bridge.py`（paramiko 实现，见附录 A）
3. 确认服务器上 `127.0.0.1:15557` 隧道活着（见 §4 排查）

## 2. 标准连接流程（每次使用）

```bash
# ① 起桥（后台常驻）
setsid python3 adb-bridge.py &        # 默认本地 15551 → 服务器 15557

# ② 连接 + 验证
adb connect 127.0.0.1:15551
adb -s 127.0.0.1:15551 shell echo alive && adb devices

# ③ ……你的测试……

# ④ 用完必做（单连接位纪律！）
adb disconnect 127.0.0.1:15551
```

## 3. 单连接位纪律（最重要的规则）

**Android 9 的 adbd 只有一个 TCP 连接位——同一时刻只有一个 agent 能连。**

| 规则 | 说明 |
|---|---|
| **先探测再连接** | `adb connect` 失败/`offline` 时，先看 §4——可能是别人在线占用，等 2 分钟再试 |
| **用完必 disconnect** | 不断开 = 占着茅坑，别人全堵死 |
| **长任务先打招呼** | 装大包/长测试（>10 分钟）在共用频道通报一声（用户群/工单） |
| **绝不 force-stop 别人的进程** | 连接前 `adb shell ps` 看看有没有别的 agent 的测试在跑 |
| **应用数据是共享的** | 手机上的 app/数据所有人共用——卸载、清数据、改系统设置前必须确认没人在用 |

## 4. 故障自助排查（按序尝试）

| 症状 | 原因 | 你的动作 |
|---|---|---|
| `adb connect` 报 Connection refused | 你的桥没起/挂了 | 重启桥进程再 connect |
| connect 成功但 `device offline` | ① 占用方在线；② 服务器侧隧道半开死连接 | 等 2 分钟；还不行 → 报管理员（云端可清半开会话，勿自己 SSH 服务器——你没权限也**不该**有） |
| `adb devices` 空 + 桥日志 fwd-fail | 手机隧道断了（MIUI 杀 Termux/手机离线） | **你修不了**（需要人在手机旁或云端介入）→ 报管理员，附桥日志 |
| 传输出奇慢（<0.1MB/s） | 手机灭屏省电限速 | `adb shell input keyevent KEYCODE_WAKEUP`；长传输用 `svc power stayon true`（**用完 false**） |

## 5. 红线（违反 = 管理员收 key）

1. **不得**尝试获取服务器 shell/越权（key 是限权的，别绕）
2. **不得** root 改手机系统、刷不可逆的东西（`su` 可用但仅限测试必需）
3. **不得**卸载/清空共享应用数据而不通报
4. **大文件（>50MB）传输先报备**——手机可能走 4G，烧的是用户的流量
5. **不得**删除/改动 `/data/local/tmp/offline-verify.log` 等他人工件
6. 测试脚本即用即删，凭据永不落盘

## 6. 已知边界（省得你踩）

- **IME 类交互做不了**：切输入法/地球键等需要人在手机旁的秒级操作是远程边界（ADBKeyboard 可解注入，参考 `SKILL-remote-android-device`）
- **uiautomator dump 常态性抽风**：黑屏/界面切换时报 null root——唤醒屏幕重试，别信单次失败
- `pkill -f <含你命令行的模式>` 会自杀你的 shell——按 PID 杀
- 手机规格：Mi Note 3 / Android 9 / MIUI / 6GB / arm64-v8a（模拟器没覆盖的 MIUI 特有行为在这台能测）

## 7. 找谁

- **手机侧/服务器侧问题** → 管理员（码龙）：报现象 + 桥日志 + 时间点
- **需要人碰手机**（按物理键/解锁 SIM 等）→ 用户本人
- 全量踩坑档案：`skills/SKILL-remote-android-device/SKILL.md`（管理员侧）

---

## 附录 A：桥脚本

```python
#!/usr/bin/env python3
"""agent 侧桥：本地 127.0.0.1:15551 → 服务器回环 15557（限权 key，无 shell）。
依赖：pip install paramiko。环境变量可改 KEY/SRV/PORTS。"""
import paramiko, socketserver, threading, os, sys

KEY = os.environ.get("MI3_KEY", os.path.expanduser("~/.ssh/mi3_agent"))
SRV = os.environ.get("MI3_SRV", "175.178.179.50")
FWD_PORT = int(os.environ.get("MI3_FWD_PORT", "15557"))
LOCAL_PORT = int(os.environ.get("MI3_LOCAL_PORT", "15551"))

client = paramiko.SSHClient()
client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
client.connect(SRV, username="root", port=22,
               pkey=paramiko.Ed25519Key.from_private_key_file(KEY),
               timeout=20, allow_agent=False, look_for_keys=False)

class Handler(socketserver.BaseRequestHandler):
    def handle(self):
        ch = client.get_transport().open_channel(
            "direct-tcpip", ("127.0.0.1", FWD_PORT), self.request.getpeername())
        if ch is None: return
        def pump(a, b):
            try:
                while True:
                    d = a.recv(4096)
                    if not d: break
                    b.sendall(d)
            except OSError: pass
            finally:
                try: a.close(); b.close()
                except OSError: pass
        threading.Thread(target=pump, args=(self.request, ch), daemon=True).start()
        pump(ch, self.request)

class Srv(socketserver.ThreadingTCPServer): allow_reuse_address = True
Srv(("127.0.0.1", LOCAL_PORT), Handler).serve_forever()
```

## 附录 B：管理员开通清单（每个新 agent 一次性）

```bash
# 管理员（码龙）执行：
# ① 生成 agent 专属 key
ssh-keygen -t ed25519 -N "" -f mi3_agent_<名字> -C "mi3-agent-<名字>"
# ② 服务器 authorized_keys 追加（限权：仅端口转发）
echo 'restrict,port-forwarding <公钥内容>' >> root@175.178.179.50:/root/.ssh/authorized_keys
# ③ 发给 agent：私钥 + 本 SOP + 附录 A 脚本
# ④ 登记：TOOLS.md「mi3-agent 台账」（谁/何时/用途）——审计用
```
