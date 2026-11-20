# Toolbox

個人模組容器，Kotlin + Android XML Views，不使用 Compose。正式來源：[garytu001/personal-toolbox](https://github.com/garytu001/personal-toolbox)。

手機直接下載：[最新正式 APK](https://github.com/garytu001/personal-toolbox/releases/latest/download/app-release.apk)。已安裝相同簽章正式版時，使用 App 頂部「更新」即可覆蓋安裝，不用傳輸線或解除安裝。舊 Debug 與正式版簽章不同，不能互相覆蓋。

## 建置

| 項目 | 值 |
| --- | --- |
| applicationId | com.local.personaltoolbox |
| 顯示名稱 | Toolbox |
| min / target / compile SDK | 26 / 34 / 34 |
| versionCode / versionName | 6 / 1.2.0 |
| Gradle / AGP / Kotlin | 8.14.3 / 8.13.2 / 2.1.20 |
| JVM 目標 / CI JDK | 17 / 17 |

Windows 使用 Android Studio JBR 21，執行 setup_local.ps1 偵測 SDK；本機中文路徑保留 android.overridePathCheck=true 及 COMPAT 參數：

```powershell
.\setup_local.ps1
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug '-Dorg.gradle.jvmargs=-Xmx2048m -Dfile.encoding=COMPAT'
```

macOS/Linux 執行 setup_local.sh 後使用 ./gradlew assembleDebug testDebugUnitTest lintDebug。需 SDK Platform 34、Build-Tools 35.0.0；CI Linux/JDK17 不加本機編碼參數。

Debug APK：app/build/outputs/apk/debug/app-debug.apk。正式 APK：app/build/outputs/apk/release/app-release.apk。

## 模組與介面

- 純黑白灰：背景 #000000、卡片 #121212、邊框 #303030、文字 #F5F5F5 / #9E9E9E、強調 #FFFFFF。12dp 圓角、1dp 邊框、8dp 間距、無陰影。
- 固定頂部狀態／版本／無障礙／電池／更新；WindowInsets 避開狀態列及導覽列，數字採 tnum。
- 「短影音攔截」卡片整合 YouTube、Instagram、Facebook 三個獨立開關與計數。
- 「YouTube 廣告略過」卡片含獨立開關、成功次數及媒體控制授權入口。
- 卡片可長按或用握把拖曳，放開保存。唯一註冊表 ModuleRegistry.cards 產生扁平 modules 清單；Activity、Adapter、Service 不依模組 ID 加分支。詳細擴充規範見 AGENTS.md。
- yt_shorts、ig_reels 既有 ID／設定／計數保持原樣；fb_reels 新增預設關閉。合併卡片取舊排序中最早出現成員的位置，整張拖曳後仍以原模組 ID 保存。
- YouTube 依 Shorts 播放頁 ID／選中分頁退出。Instagram 僅辨識 clips 元件及 Reels 分頁，排除 Stories，優先回首頁。各模組獨立 450ms 冷卻。
- Facebook 僅支援 com.facebook.katana；以明確選中 Reels 分頁或 Reels 標題＋全畫面捲動播放器＋右側垂直互動按鈕辨識，排除 Stories，優先點首頁再返回。不以「影片」泛稱、活動類別或猜測數字 ID 攔截。Facebook 混淆與佈局差異大，需要實機核對，可能漏攔截。
- 沒有遮罩、入口攔截或背景輪詢，首頁縮圖仍由原 App 顯示。

## v1.2.0 的廣告略過方式

已改為媒體控制「下一部 → 上一部」，移除座標手勢與按鈕點擊。

1. App 內按「更新」安裝 v1.2.0；排序、開關、計數保留。
2. 到系統無障礙設定將 Toolbox 關閉再開啟，載入新增 Facebook 監聽套件。
3. 廣告卡片按「授權媒體控制」，在 Android「通知存取」啟用「Toolbox 媒體控制」，再回 App 開啟廣告略過。
4. Facebook 開關預設關閉，需要時個別開啟。

通知存取是 Android 允許讀取其他 App 媒體工作階段的特殊授權，系統提示可能涵蓋廣泛通知能力。本實作不讀取、儲存或上傳通知內容；只透過 MediaSessionManager 取得 YouTube 的媒體控制器，逐次核對 package 及固定 session token。絕不向全系統發送可能控制 Spotify 等其他 App 的媒體按鍵；未授權不操作。

- 只以播放器中可見的 ad_progress_text／廣告略過控制元件判斷廣告，不以首頁 Sponsored 文字觸發；不處理 Shorts。
- 每個指令前採 N(50ms,(20ms)²) 截斷至 0–100ms 的延遲，以拒絕重抽避免邊界堆積。指令間仍等待實際影片切換完成，不盲目在 100ms 內連發。
- 只有 YouTube 工作階段有有效媒體識別、正在播放且支援 next / previous 才嘗試；播放器尚未準備好時不送出指令。
- 保存原媒體識別，下一部後用 MediaController.Callback 等到不同影片，才送上一部；每階段最多等 3 秒，不輪詢。確認回到原片、再等 600ms 且廣告元件消失才計數。
- 無媒體 ID 時以標題／作者／長度回退判斷，不能保證不同同名影片完全可區分，也不能保證回到原進度、避開所有廣告或各版 YouTube 相容。
- 一次廣告最多發起一次切換，兩次嘗試至少隔 10 秒。關閉模組、停止服務、手動點擊、待執行期間捲動會取消；切換自身產生的捲動不取消回程。無法保證觀察到所有耳機或手動操作。
- 此功能可能切到下一片後因不支援上一部、逾時或使用者中斷而停在下一片；不以額外切換重試造成循環。隨機延遲不保證防偵測或帳號零風險。

## DataStore

共用單一 toolbox_preferences Preferences DataStore，由 Application 持有。

| 穩定 ID | enabled 預設 | blocked_count 預設 |
| --- | --- | --- |
| yt_shorts | true | 0 |
| ig_reels | true | 0 |
| fb_reels | false | 0 |
| yt_ad_skip | false | 0 |

實際鍵為 <id>_enabled、<id>_blocked_count；既有 yt_ad_skip 開關及計數保留。module_order 是逗號分隔模組 ID 字串，首次為 yt_shorts,ig_reels,fb_reels,yt_ad_skip。未知／重複 ID 會正規化，新模組附加末尾，ModuleCardOrder 投影成卡片順序。舊 youtube_shorts_* / instagram_reels_* 以 DataMigration 遷移且不覆寫新鍵。計數以 edit 原子更新並防止溢位。

## 正式簽章與 GitHub 更新

固定正式憑證 SHA-256：`ecd1392a6cb1e6aa747fe65c75bfcc862ace087cd8700c06fb0406d10de81daf`。

- 現有 release.jks、keystore.properties 必須沿用，請離線備份；不要重新產生或提交。產生腳本會拒絕覆寫。
- Gradle 個別欄位優先讀 KEYSTORE_PATH、KEYSTORE_PASSWORD、KEY_ALIAS、KEY_PASSWORD，缺少才讀本機 keystore.properties。Debug 不需要金鑰；Release 缺少配置會明確失敗。
- Actions Secrets：KEYSTORE_BASE64、KEYSTORE_PASSWORD、KEY_ALIAS、KEY_PASSWORD。KEYSTORE_PATH 由 Runner 暫存路徑決定，不必設 Secret。
- Tag 必須精確等於 v<versionName>，例如 v1.2.0；workflow 用 JDK17 還原金鑰，assembleRelease + testDebugUnitTest + lintDebug、驗證簽章並發布 app-release.apk。每次 versionCode 嚴格遞增，下一修正版例如 7 / 1.2.1。
- UpdateConfig 固定 garytu001/personal-toolbox，Token 留空。HttpURLConnection / org.json 查最新 Release，DownloadManager 下載，核對套件／版本／簽章與可用的 SHA-256，FileProvider 喚起安裝；保留未知來源授權及續裝流程。
- Manifest 仍只有 INTERNET、REQUEST_INSTALL_PACKAGES 兩個 uses-permission。無障礙／通知服務分別以 BIND_ACCESSIBILITY_SERVICE、BIND_NOTIFICATION_LISTENER_SERVICE 保護；沒有儲存或懸浮視窗權限。

## 驗收與範圍

本機執行 assembleDebug、testDebugUnitTest、lintDebug；42 項 JVM 測試覆蓋排序與群組投影、DataStore 升級保留、語意版本、截斷常態分佈、媒體切換順序／逾時／取消／回程驗證／防重複，以及 Facebook 辨識條件。測試與編譯不能取代實機驗證。

仍需手機核對：通知存取授權、YouTube 廣告辨識與媒體資訊、切回影片與播放進度、Facebook Reels 與 Stories、卡片拖曳、既有資料，以及正式 OTA 安裝。

技術參考：[Android 媒體控制器](https://developer.android.com/reference/android/media/session/MediaController)、[媒體工作階段與授權](https://developer.android.com/reference/android/media/session/MediaSessionManager)、[Facebook 元件混淆研究](https://github.com/sfsakhawat999/ReelsBlock/blob/main/docs/facebook-reels.md)（該專案使用 Xposed；本專案僅參考平台限制，不依賴 root／Xposed）。
