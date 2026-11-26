# Toolbox 專案規範

- 個人用模組容器；Kotlin + Android XML Views，不使用 Compose。Package 固定 `com.local.personaltoolbox`，min/target/compile SDK 固定 26/34/34。
- 主畫面只放狀態、版本、操作、模組名稱、開關與數字；禁止歡迎標語、裝飾 Emoji、功能長篇解說與教學文案。只用純黑白灰（RGB 三通道相等），禁止恢復綠色強調色；新數字加 `fontFeatureSettings="tnum"`，保留根佈局 WindowInsets。

## 新增模組

1. 在 `modules/` 建立獨立功能；需要無障礙操作時繼承 `accessibility/ToolboxModule`，提供永久不變的模組 ID、目標 package、偵測與動作。
2. 唯一註冊點為 `modules/ModuleRegistry.kt` 的 `cards`：`ModuleCard(id, nameRes, modules)` 內登記 `ModuleDefinition(id, nameRes, ...)`。UI、設定與服務從此表產生，不要在 MainActivity、Adapter 或服務依模組 ID 分支。獨立設定入口用 ModuleAction 描述。
3. 無障礙模組提供 `createService`；新目標 App 同時加入 `res/xml/accessibility_service_config.xml` 的 packageNames。各實例獨立維持 450ms 冷卻，動作成功才計數。
4. 非無障礙模組可不提供 createService；沿用卡片，依需求擴充自己的操作畫面，避免把業務邏輯寫進容器。
5. 使用者已移除遮罩、入口觸控攔截與輪詢，不要恢復。檢查事件與目前視窗套件一致；保護 Instagram Stories 與其他 App。
6. yt_ad_skip 已依新授權改為媒體控制「下一部 → 上一部」，不再點擊廣告按鈕或取樣座標。每個指令前延遲為 N(50ms,(20ms)²) 截斷至 0–100ms；等待影片切換完成屬必要的狀態同步，不能以固定延遲盲送上一部。不得宣稱防偵測或零帳號風險。
7. 媒體控制需要使用者自行授予「Toolbox 媒體控制」通知存取，再以 MediaSessionManager 取得 YouTube 的 session token。所有指令只送該 session，禁止以 AudioManager 全域發送而誤控其他 App。不讀、存或上傳通知內容；不在 App 存放 Token。媒體模組不新增 uses-permission；功能三另需通知／準時鬧鐘／開機排程權限。通知服務以 BIND_NOTIFICATION_LISTENER_SERVICE 保護。
8. 廣告辨識只使用可見播放器廣告元件，不以首頁「Sponsored」文字觸發，排除 Shorts。先保存媒體識別；確認切至不同影片後才送上一部，切回原片且廣告消失後才計數。不支援 next/previous 或缺少媒體資訊時不操作；單階段逾時 3 秒，同一廣告最多發起一次切換且相鄰嘗試至少隔 10 秒。不要宣稱保證恢復原進度。
9. 非同步模組使用 ModuleEnvironment 的服務 scope／設定／成功回報與 ToolboxModule hooks。關閉模組、手動點擊、停止服務時取消；服務 scope 必須取消，不跨延遲保留 AccessibilityNodeInfo。切換自身可能觸發捲動事件，不可因此取消自己的回程；等待中的一般捲動會取消。
10. 短影音卡片包含 yt_shorts、ig_reels、fb_reels，各自開關與計數；FB 新增預設 false。Facebook 只偵測明確選中的 Reels 分頁或同時具備 Reels 標題、全畫面捲動播放器與右側垂直互動按鈕，排除 Stories；不要因「影片」文字或 ImmersiveActivity 本身就攔截，也不要猜數字 ID。新版本仍需實機核對。
11. 功能三 `aov_crystal` 預設 false，每日台灣時間 12:00 通知、使用者確認後才啟動。只購買 1 個 150 寶石魔法水晶，不抽獎。僅沿用登入，不存帳密。遊戲版本與畫面尺寸須符合實機核對的辨識檔，畫面不符即停止；不得加入背景輪詢。每個手勢前核對前景套件，每步後只截圖一次驗證下一狀態；截圖不保存／上傳。購買前原子保存當日提交紀錄，結果不明也不得重購；只有辨識到成功才計數。依使用者最新指示取消全部卡片移除；購買完成或確認今日已購買即結束並停留遊戲，不開啟最近使用、不返回桌面、不提供清理按鈕。今日已確認成功時不重新啟動遊戲或購買。
12. 使用時間限制卡片含 `yt_usage_limit` 20 分鐘與 `ig_usage_limit`／`threads_usage_limit`／`fb_usage_limit`／`dcard_usage_limit` 各 5 分鐘，皆預設 true。計算每次連續前景使用，離開／鎖屏／關閉／服務停止即重算，點擊與捲動不重算。用滿後立即開始該 App 獨立 60 秒禁止使用期，當下及期限內每次進入都只送 GLOBAL_ACTION_HOME；不開最近使用、不移除卡片、不強制停止、不新增遮罩。禁止期限不因離開／鎖屏／再次進入而重設或延長，關閉模組時不執行，重新啟用只恢復剩餘時間；服務／App 重啟從原 DataStore 的 `<id>_block_started_at`／`<id>_blocked_until` 恢復，缺少／已過期／時鐘倒退的期限視為無限制，單次最多恢復 60 秒。期限內使用 monotonic 時鐘，不保存使用歷史。
13. 使用服務 scope 的單次使用截止與禁止期截止計時，不輪詢。禁止期只在事件與目前視窗一致時檢查並退出；每次 HOME 前重驗前景、螢幕、鎖屏與開關。各實例保留 450ms 冷卻，快速重入可由該事件安排一次剩餘冷卻的延後退出，離開／關閉／服務停止即取消，不做週期重試。只在 HOME 被接受時計數；禁止期到期後放行並開始新的一次使用。透過通用 foreground hooks 追蹤離開，只有有啟用觀察模組時動態接收所有套件事件；不讀其他 App 內容，動作仍依註冊表派送。所有最近使用選單／長按圖示／Close 流程已移除，舊退役模組資料只保留相容遷移清除，不得重用其 ID。不能宣稱強制閃退或停止背景播放。

## DataStore

- 共用單一 `toolbox_preferences` Preferences DataStore，由 Application 持有 `ToolboxPreferences`；不可為同一檔案建立第二個 DataStore。
- ID 僅用小寫英數與底線，發布後不可更名。通用鍵為 `<id>_enabled`（Boolean，預設 true，可個別覆寫）、`<id>_blocked_count`（Int，預設 0）。其他資料用 `<id>_` 前綴；計數以 edit 原子更新並防 Int 溢位。
- `module_order` 保持逗號分隔的模組 ID，不改成卡片 ID。ModuleCardOrder 取舊順序中最先出現的成員作為群組位置；合併卡片不變更啟用狀態與計數鍵。拖曳卡片後按整組成員 ID 展開保存，重啟／更新保持卡片順序。
- 讀取時去除未知與重複 ID，新增模組附加末尾。拖曳放開即在 Application scope 寫入，避免 Activity 銷毀取消保存。
- 舊 youtube_shorts_* / instagram_reels_* 已有一次性 DataMigration；新鍵存在不得覆蓋。資料格式變更必須加相容遷移，禁止清空 DataStore。

## 版本、簽章與驗收

- 目前 versionCode 11 / versionName 1.6.0。每次發布 versionCode 嚴格增加；修正遞增 patch、新功能遞增 minor，Tag 精確等於 v<versionName>。
- 正式更新來源固定公開倉庫 garytu001/personal-toolbox，不得恢復 YOUR_GITHUB_USERNAME。GITHUB_TOKEN 留空，手機不存放倉庫 Token。
- applicationId 與正式簽章金鑰不可變。Release 優先讀 KEYSTORE_PATH、KEYSTORE_PASSWORD、KEY_ALIAS、KEY_PASSWORD 環境變數，缺少欄位才讀 keystore.properties；金鑰與密碼不得提交。
- 修改完成執行 gradlew.bat assembleDebug testDebugUnitTest lintDebug（Unix 用 ./gradlew），修正必要錯誤，說明未實機驗證範圍。編譯成功不是實機驗證。
- 中文 Windows 路徑使用 Android Studio JBR 21，加入 '-Dorg.gradle.jvmargs=-Xmx2048m -Dfile.encoding=COMPAT'；保留 android.overridePathCheck=true。CI Linux/JDK17 保持標準命令。
- GitHub 更新沿用 HttpURLConnection / org.json / DownloadManager / FileProvider；保留下載狀態、未知來源授權及 APK 套件／版本／簽章驗證。
