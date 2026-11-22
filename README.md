# Toolbox

個人模組容器，Kotlin + Android XML Views，不使用 Compose。正式來源：[garytu001/personal-toolbox](https://github.com/garytu001/personal-toolbox)。

手機直接下載：[最新正式 APK](https://github.com/garytu001/personal-toolbox/releases/latest/download/app-release.apk)。已安裝相同簽章正式版時，使用 App 頂部「更新」即可覆蓋安裝，不用傳輸線或解除安裝。舊 Debug 與正式版簽章不同，不能互相覆蓋。

## 建置

| 項目 | 值 |
| --- | --- |
| applicationId | com.local.personaltoolbox |
| 顯示名稱 | Toolbox |
| min / target / compile SDK | 26 / 34 / 34 |
| versionCode / versionName | 10 / 1.5.1 |
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
- 「使用時間限制」卡片含 YouTube 20 分鐘、Instagram／Threads／Facebook／Dcard 各 5 分鐘的獨立開關與退出次數，新增預設開啟。
- 卡片可長按或用握把拖曳，放開保存。唯一註冊表 ModuleRegistry.cards 產生扁平 modules 清單；Activity、Adapter、Service 不依模組 ID 加分支。詳細擴充規範見 AGENTS.md。
- yt_shorts、ig_reels 既有 ID／設定／計數保持原樣；fb_reels 新增預設關閉。合併卡片取舊排序中最早出現成員的位置，整張拖曳後仍以原模組 ID 保存。
- YouTube 依 Shorts 播放頁 ID／選中分頁退出。Instagram 僅辨識 clips 元件及 Reels 分頁，排除 Stories，優先回首頁。各模組獨立 450ms 冷卻。
- Facebook 僅支援 com.facebook.katana；以明確選中 Reels 分頁或 Reels 標題＋全畫面捲動播放器＋右側垂直互動按鈕辨識，排除 Stories，優先點首頁再返回。不以「影片」泛稱、活動類別或猜測數字 ID 攔截。Facebook 混淆與佈局差異大，需要實機核對，可能漏攔截。
- 沒有遮罩、入口攔截或背景輪詢，首頁縮圖仍由原 App 顯示。

## 功能四：使用時間限制

- 計算每次連續前景使用：YouTube 20 分鐘；Instagram、Threads、Facebook、Dcard 各 5 分鐘。切到其他 App、回桌面、鎖屏、關閉該開關或無障礙服務中斷即重算；點擊、滑動、切換同一 App 內頁面不重算。不累計每日用量，不儲存使用歷史。服務重新啟動或重新開啟開關時，從當前 App 重新計時。
- 到時開啟 ASUS 最近使用畫面，長按目標 App 圖示，再點選該 App 選單中的 `Close`，確認卡片消失後回桌面。再次進入 App 會開始新的一次時間。移除卡片對播放或程序的影響由 Android 與目標 App 決定。
- 使用服務 scope 的單次截止計時器，靜止畫面也能到時動作；沒有週期輪詢。截止前再次核對開關、螢幕、鎖屏與前景套件；不跨延遲保存節點。只有確認卡片移除才增加退出次數，失敗不在同一次使用內反覆重送，各實例保留獨立 450ms 冷卻。自身移除引起的前景變化保留成功回報，手動關閉或服務停止仍取消。
- 只要有時間限制開關啟用，服務會透過 Android 的動態套件篩選接收其他 App 的視窗事件，以偵測離開與重算；僅核對前景套件，不掃描其他 App 的頁面內容。全部關閉後恢復註冊表套件篩選，動作派送始終限於註冊模組。無新增權限。
- Threads 支援 `com.instagram.barcelona`，Dcard 支援 `com.sparkslab.dcardreader`；不包含網頁版、Facebook Lite 或其他變體。更新後將 Toolbox 無障礙服務關閉再開啟，載入新模組。
- 尚未實機驗證五個 App 的實際到時退出、跨 App／鎖屏重算、背景影片與多視窗行為；Android 排程或廠商省電可能延後執行。桌面退出不保證影片停止播放。

## 功能三：傳說對決

- 新增獨立 `aov_crystal` 卡片，預設關閉。每天台灣時間 12:00 發送「以 150 寶石購買 1 個魔法水晶」通知；通知不會自行開啟遊戲。
- 在「提醒設定」授予通知及準時提醒權限。Android 12 以上未授予準時提醒時改用非精確鬧鐘，畫面明確顯示「約 12:00」；Android 13 以上需要通知權限。重新開機、更新及時間異動會重新排程；強制停止 App 後需重新開啟 App。
- 通知點入後按「確認並執行（150 寶石）」，只啟動台灣版 `com.garena.game.kgtw`，沿用遊戲的既有登入狀態。登入失效、載入過久、改版或不認得的彈窗會停止，不儲存帳密，不代過驗證。
- 實機核對的路徑為大廳 → 商店 → 道具 → 寶石商店 → 魔法水晶 → 150 寶石詳情 → 購買 → 購買成功 → 今日限購 1/1。僅處理已核對的宣傳彈窗關閉按鈕，不進行活動領取或抽獎。
- Unity 畫面沒有商店按鈕節點，使用 Android 11 以上無障礙截圖與手勢。購買只支援已核對的遊戲 `1.64.1.11`、橫向 `2400 × 1080` 畫面。不同版本或畫面不符即停止；卡片清理使用下節的 ASUS 最近使用畫面流程。
- 只在使用者確認後執行。啟動等待 15 秒，各步動作後只觀察一次畫面並驗證下一狀態，沒有背景輪詢或盲目點擊重試；整段上限 120 秒，遊戲未啟動另有 30 秒看門檢查。請於執行時暫停操作手機。
- 購買前在原有 DataStore 原子保存當日提交紀錄。即使購買後逾時或服務中斷，也不再自動購買第二次。辨識到「購買成功」與魔法水晶後才回報成功計數；辨識到原本已限購 1/1 時只略過購買，不增加計數。過期通知不執行。
- 購買完成後共用畫面移除流程：開啟最近使用，長按遊戲圖示，點 `Close`，確認消失後回桌面。已確認今日買過時，設定頁只清理現有卡片，不重新啟動遊戲、不再次購買；仍需要啟用無障礙服務。卡片不明確或無法顯示時停止並保留購買紀錄。
- 截圖僅於本次流程的記憶體辨識後釋放，不保存或上傳。內附辨識資料僅為文字與按鈕的二值特徵，不含帳號、完整畫面或通知內容。
- 此功能另需 `POST_NOTIFICATIONS`、`SCHEDULE_EXACT_ALARM`、`RECEIVE_BOOT_COMPLETED` 三個權限及無障礙截圖／手勢能力；更新後需重新啟用 Toolbox 無障礙服務以載入新增套件。原有媒體控制不變。

## 共用卡片移除

- 僅處理 YouTube／Instagram／Threads／Facebook／Dcard／台灣版傳說對決六個固定 App。需要 Toolbox 無障礙服務，不需其他連線設定。
- 以已實機取得的 ASUS `overview_panel`／`task_view_single`／`snapshot`／`icon` 節點辨識唯一卡片及圖示；圖示必須完整可見且支援長按。唯一卡片部分露出時，至多要求一次 `ACTION_SHOW_ON_SCREEN`。找不到或有歧義即停止，不猜座標或滑動搜尋，不重新啟動遊戲。
- 圖示直接執行 `ACTION_LONG_CLICK`，選單一出現立即核對同一 App 名稱與 `App info`，再點精確 `Close`（或「關閉」）的可點擊列。不同 App 的選單或 `Clear all`／`Close all` 不會被選中。依實際視窗事件銜接，不加固定等待；整段最多 6 秒，超時不重送。
- 手動點擊其他位置、關閉模組、鎖屏、切到其他 App 或停止服務會中止；自身長按與 Close 事件不誤判為手動中止。動作前重新取得並核對視窗，所有節點立即釋放。驗證卡片消失才回報成功。
- 更新遷移只刪除已退役模組的設定、排序成員及本機憑證；其餘開關、次數、排序、購買紀錄與正式簽章保持相容。已移除配對、連線頁面、命令執行器、相關第三方依賴及測試工作流程。
- 此版依既有 ASUS 節點紀錄及使用者提供的長按選單截圖製作，尚未在手機執行新版流程；選單文字、可存取節點及動畫速度仍需實機確認，不能以編譯通過代替。

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
| aov_crystal | false | 0 |
| yt_usage_limit | true | 0 |
| ig_usage_limit | true | 0 |
| threads_usage_limit | true | 0 |
| fb_usage_limit | true | 0 |
| dcard_usage_limit | true | 0 |

實際鍵為 <id>_enabled、<id>_blocked_count；既有 yt_ad_skip 開關及計數保留。module_order 是逗號分隔模組 ID 字串，首次依 ModuleRegistry.modules 順序建立。未知／重複 ID 會正規化，新模組附加末尾，ModuleCardOrder 投影成卡片順序。舊 youtube_shorts_* / instagram_reels_* 以 DataMigration 遷移且不覆寫新鍵。計數以 edit 原子更新並防止溢位。

## 正式簽章與 GitHub 更新

固定正式憑證 SHA-256：`ecd1392a6cb1e6aa747fe65c75bfcc862ace087cd8700c06fb0406d10de81daf`。

- 現有 release.jks、keystore.properties 必須沿用，請離線備份；不要重新產生或提交。產生腳本會拒絕覆寫。
- Gradle 個別欄位優先讀 KEYSTORE_PATH、KEYSTORE_PASSWORD、KEY_ALIAS、KEY_PASSWORD，缺少才讀本機 keystore.properties。Debug 不需要金鑰；Release 缺少配置會明確失敗。
- Actions Secrets：KEYSTORE_BASE64、KEYSTORE_PASSWORD、KEY_ALIAS、KEY_PASSWORD。KEYSTORE_PATH 由 Runner 暫存路徑決定，不必設 Secret。
- Tag 必須精確等於 v<versionName>，例如 v1.5.1；workflow 用 JDK17 還原金鑰，assembleRelease + testDebugUnitTest + lintDebug、驗證簽章並發布 app-release.apk。每次 versionCode 嚴格遞增，下一修正版例如 11 / 1.5.2。
- UpdateConfig 固定 garytu001/personal-toolbox，Token 留空。HttpURLConnection / org.json 查最新 Release，DownloadManager 下載，核對套件／版本／簽章與可用的 SHA-256，FileProvider 喚起安裝；保留未知來源授權及續裝流程。
- v1.2.0 只有 INTERNET、REQUEST_INSTALL_PACKAGES 兩個 uses-permission；v1.3.0 功能三新增權限見上節。無障礙／通知服務分別以 BIND_ACCESSIBILITY_SERVICE、BIND_NOTIFICATION_LISTENER_SERVICE 保護；沒有儲存或懸浮視窗權限。

## 驗收與範圍

本機驗證執行 assembleDebug、testDebugUnitTest、lintDebug，正式發布另執行 assembleRelease。JVM 測試涵蓋排序、資料遷移保留、媒體切換、購買防重複、使用時間截止與取消，以及目標圖示唯一性、選單 App 身分、精確 Close、其他卡片／清除全部保護。編譯與單元測試不能取代實機驗證。

仍需手機核對：通知存取授權、YouTube 廣告辨識與媒體資訊、切回影片與播放進度、Facebook Reels 與 Stories、卡片拖曳、既有資料，以及正式 OTA 安裝。

2026-11-21 功能三實機驗證（ASUSAI2501C／Android 16／遊戲 1.64.1.11）：

- USB 校準時實際購買 1 個魔法水晶，寶石 8,750 → 8,600，出現「購買成功」與今日限購 1/1。這次購買由 ADB 操作校準畫面，不列入 App 自動購買計數。
- 原簽章 v1.3.0 已覆蓋安裝，YouTube 攔截 16 次與廣告略過 1 次等既有資料仍在。每日提醒已開啟，通知與準時鬧鐘權限已授予，系統排程為 2026-11-22 12:00（台灣時間）。
- App 已自行走完大廳 → 商店 → 道具 → 寶石商店 → 魔法水晶，辨識已購買後沒有重購或增加成功次數。初輪彈窗與轉場辨識失敗均安全停止；已補齊共用彈窗控制與大廳文字容差。
- 舊版 App 已成功使用 ASUS 遊戲卡片提供的 `Close` 無障礙動作（本版長按圖示後點 Close 尚待另測），核對遊戲卡片消失並回桌面；設定頁顯示「今日已購買，已移除遊戲卡片」。
- 完整 App 自動提交購買及中午實際通知送達仍待後續日期實測；當天限購已用於校準，不為驗收而再購買。重新開機後排程、登入失效與遊戲改版等情況仍未逐一實機測試。

技術參考：[Android 媒體控制器](https://developer.android.com/reference/android/media/session/MediaController)、[媒體工作階段與授權](https://developer.android.com/reference/android/media/session/MediaSessionManager)、[Facebook 元件混淆研究](https://github.com/sfsakhawat999/ReelsBlock/blob/main/docs/facebook-reels.md)（該專案使用 Xposed；本專案僅參考平台限制，不依賴 root／Xposed）。
