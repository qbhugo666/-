# YanChuFaSui (言出法随)

<p align="center">
  <b>System-level · Fully offline · Zero data upload · Chinese-native</b><br>
  Voice control for Android — you speak, the phone obeys.
</p>

**Language / Language**: English | [简体中文](README.md)

---

## 1. The problem it solves

Users with motor disabilities such as SMA (spinal muscular atrophy) cannot operate a touchscreen — and the gap between "can't type" and "can't tap" cuts off the entire digital life.

Meanwhile, the Chinese Android ecosystem has almost no vendor investment in system-level voice control; the only major attempt (Xiaomi's) has long been unmaintained.

**YanChuFaSui** returns control to the voice, built on the Accessibility service + an offline speech recognition engine: no internet required, no special hardware, no PC companion — an ordinary Android phone, operated entirely by speech.

## 2. Features

- **Number overlay**: say "show numbers" and every tappable element gets labeled — say "tap 18" to tap it precisely
- **Grid overlay**: full-screen 12-cell grid, drill down up to 5 levels to reach any pixel
- **Swipe & precise nudge**: full-page scrolling, plus short precise adjustments
- **Two-finger pinch zoom**: for photos and web pages
- **Voice dictation**: say "输入" (input) → speak → pause → text lands in the focused input box (WeChat, etc.)
- **Text editing**: delete character by character, move the cursor, or "把A替换成B" (replace A with B) by voice
- **Custom commands**: bind your own phrase to any action — voice enrollment, zero typing
- **Personal dictionary**: names and places recognized preferentially (e.g. 黄信豪)
- **Device control**: volume (80% safety cap), mute, lock screen, notification shade, quick settings
- **Fully offline**: SenseVoice on-device Chinese ASR — no internet, zero data upload
- **Safety by design**: session-based microphone with watchdog auto-release, silence detection, misfire circuit breaker, and silent self-healing of the accessibility switch

<p align="center">
  <img src="docs/images/home.png" width="270" alt="Home" />
  <img src="docs/images/settings.png" width="270" alt="Settings" />
</p>

## 3. Installation

### Users

1. Grab the latest APK from the [Releases](../../releases) page (if GitHub is slow in your region, contact the author via QQ / cloud drive)
2. Install (allow installing from unknown sources)
3. Follow the first-run guide: battery optimization exemption → autostart permission → enable the Accessibility service
4. Recommended: lock the app in Recents so system cleanup never kills it

Requires Android 7.0+.

### Building from source

```bash
# 1. Clone
git clone https://github.com/qb200310-hash/YanChuFaSui.git

# 2. Download the recognition models (~240MB, kept out of the repo)
powershell -ExecutionPolicy Bypass -File scripts/download_models.ps1

# 3. Open in Android Studio, or build from the CLI
gradlew assembleDebug
```

## 4. Usage

Open the app → tap "开始控制" (start control) → the capsule turns blue and listens → speak a command → the phone executes it. Say 「退出」 (exit) at any time to end the session and release the microphone.

Command quick reference (the app understands Mandarin; commands are listed in Chinese with meanings):

| Category | Commands |
|---|---|
| Browse | 向上滑动 / 向下滑动 / 向左滑动 / 向右滑动 (swipe up / down / left / right) |
| Target | 显示编号 (show numbers) → 点击 18 (tap 18) ｜ 显示网格 (show grid) → 点击 5 (tap cell 5) |
| Act | 轻点 (tap) ｜ 双击 (double-tap) ｜ 长按 → 3 (long-press, then 3) ｜ 长按抖音 (long-press "Douyin") |
| Navigate | 返回 (back) ｜ 前往主屏幕 (home) ｜ 打开 App 切换器 (recents) |
| Fine-tune | 向上摇移 / 向左摇移 (precise nudge) ｜ 双指放大 / 双指缩小 (pinch zoom) |
| Text | 输入 → 说出内容 (dictate) ｜ 删除 (delete char) ｜ 光标左移 / 右移 (cursor) ｜ 把 A 替换成 B (replace A with B) |
| Devices | 增加音量 / 降低音量 / 静音 (volume, 80% cap) ｜ 锁屏 (lock screen) ｜ 通知中心 / 控制中心 |
| End | 退出 (exit session) |

The settings page offers: recognition sensitivity (tune for soft or slurred speech), custom commands, personal dictionary, dark mode, and haptic feedback.

## 5. Input / output examples

| You say | The phone does |
|---|---|
| 「显示编号」 | Every tappable element is labeled with a number |
| 「点击 18」 | Taps the element labeled 18 |
| 「向上滑动」 | The content scrolls up one page |
| 「输入」→「今天天气不错」 | The text lands in the focused input box |
| 「把不错替换成很好」 | "不错" in the input box is replaced with "很好" |
| 「增加音量」 | Media volume +1 step (hard-capped at 80%) |
| 「退出」 | The session ends and the microphone returns to the system |

## Privacy

Recognition is fully offline — speech never leaves the phone. Screen content read by the Accessibility service is used on-device for command parsing only. The app collects, uploads, and stores no personal data.

## Support & Contact

- The app name 「言出法随」 and its brand belong to **黄信豪 (Hugo)**
- Support the author: [Afdian · afdian.com/a/hugoqb](https://afdian.com/a/hugoqb)

## License

[Apache-2.0](LICENSE) © 2026 黄信豪 (Hugo)

## Acknowledgements

- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) & [SenseVoice](https://github.com/FunAudioLLM/SenseVoice) — offline Chinese ASR engine
- [pinyin4j](https://github.com/belerweb/pinyin4j) — pinyin conversion
