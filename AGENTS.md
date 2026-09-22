# 言出法随 —— AI 接手必读（AGENTS.md）

> **本文件由 ZCode 在每次会话开始时自动加载。动任何代码之前，先读完本文件 + 下列四件套。**
> 2026-09-22 事故教训：新会话没读文档直接操作，把无障碍伪装条目当畸形清掉、重启了用户手机——
> 靠「@旧会话」传上下文必漏知识，本文件就是治这个的。

## 项目一句话

安卓离线中文语音控制 App「言出法随」（Kotlin，`E:\VoiceControl`）。
**用户是 SMA 患者，无法手部操作，此 App 是他独立操作手机与紧急求救的生命线**——设备与安全红线绝对优先。

## 必读四件套（按序读完再动手）

1. `internal-docs/HANDOFF.md` —— 项目现状 + 装机/恢复命令 + 血泪教训（**开头几行就是无障碍恢复命令，必看**）
2. `internal-docs/FEATURES.md` —— 功能全清单（提任何新建议前先查重）
3. `internal-docs/GUARDRAILS.md` —— 怎么干活：流程关卡/代码红线/测试纪律
4. `CHANGELOG.md` —— 变更历史

## 五条最容易翻车的红线（每条都有真实事故）

1. **无障碍启用列表必须写「伪装组件」**：`com.voicecontrol.app/com.google.android.accessibility.selecttospeak.SelectToSpeakService`
   （本包名+随选朗读类名，v0.47 方案，唯一来源 `AccessibilityHelper.SERVICE_COMPONENT`）。
   它**看起来像畸形条目，但它是正确的**——别"修复"它！写真实类名 `com.voicecontrol.app.VoiceControlService` 会被系统静默清除。
   装机后完整恢复流程见 HANDOFF.md 开头（pm grant → 先清空 → 隔 1s → 写双组件 → `am force-stop com.miui.home` 刷图标）。
   现成脚本：`_test\rebind_final.ps1`。
2. **未经用户同意不得重启手机 / 不得批量操作**：手机是用户的日常机+求救生命线。
   重启=用户要重新解锁、语音链路中断。真机自动化逐次授权（GUARDRAILS D 节）。
3. **用户正在使用手机（语音会话聆听中）时，禁止 UI 自动化点按/滑动**——会和他的语音操作撞车误触。
4. **含中文的文件只许用 Edit/Write 工具改**，PowerShell 管道/重定向改写必乱码；.ps1 脚本含中文必须带 BOM。
5. **协作方式**：小步走，方向性节点先给 2~3 个选项让用户拍板；用户是技术小白，交付要「下载→点安装」级傻瓜化。

## 构建 / 装机 / 测试速查

```cmd
:: 编译（离线）
set JAVA_HOME=D:\AndroidToolchain\jdk\jdk-17.0.20.1+1&& D:\AndroidToolchain\gradle\gradle-8.5\bin\gradle.bat assembleDebug -p E:\VoiceControl
:: 装机（重装后必跑无障碍恢复，见红线1）
D:\AndroidToolchain\sdk\platform-tools\adb.exe -s USQ4UO6HHA7PNFVC install -r E:\VoiceControl\app\build\outputs\apk\debug\app-debug.apk
:: 语音命令注入测试（不需真人说话；cmd 下先 chcp 65001 防中文乱码）
adb.exe shell am start-foreground-service -n com.voicecontrol.app/.VoiceService -a com.voicecontrol.app.action.SIMULATE --es com.voicecontrol.app.extra.TEXT 向上滑动
:: 日志
adb.exe shell "logcat -d -s VoiceControl:V | tail -30"
```

完成实质性任务后：更新 HANDOFF/FEATURES + 弹 Toast（`scripts\notify.ps1`）+ 按项目规矩 git 提交需用户同意。
