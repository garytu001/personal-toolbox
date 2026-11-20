# Toolbox 專案規範

- 個人用模組容器；Kotlin + Android XML Views，不使用 Compose。Package 固定 `com.local.personaltoolbox`，min/target/compile SDK 固定 26/34/34。
- 主畫面只放狀態、版本、操作、模組名稱、開關與數字；禁止歡迎標語、裝飾 Emoji、功能長篇解說與教學文案。只用純黑白灰（RGB 三通道相等），禁止恢復綠色強調色；新數字加 `fontFeatureSettings="tnum"`，保留根佈局 WindowInsets。

## 新增模組

1. 在 `modules/` 建立獨立功能；需要無障礙操作時繼承 `accessibility/ToolboxModule`，提供永久不變的模組 ID、目標 package、偵測與動作。
2. 僅在 `modules/ModuleRegistry.kt` 的 `modules` 註冊 `ModuleDefinition(id, nameRes, ...)`。UI、設定與服務分派都從此表產生；不要在 MainActivity、Adapter 或服務加入依模組 ID 的 when 分支。
3. 無障礙模組提供 `createService`；若需要新目標 App，同時將 package 加入 `res/xml/accessibility_service_config.xml` 的 packageNames。各實例獨立維持 450ms 冷卻，動作成功才計數。
4. 非無障礙模組可不提供 createService；沿用模組卡片，依具體需求擴充自己的操作畫面，避免把業務邏輯寫進容器。
5. 使用者已移除遮罩、入口觸控攔截與輪詢，不要恢復。服務僅依指定視窗事件分派，檢查事件與目前視窗套件一致；保護 Instagram Stories 與其他 App。廣告自動略過模組經評估後暫不加入，未經新需求不要新增。

## DataStore

- 共用單一 `toolbox_preferences` Preferences DataStore，由 Application 持有 `ToolboxPreferences`；不可為相同檔案建立第二個 DataStore。
- ID 僅用小寫英數與底線，發布後不可更名。通用鍵為 `<id>_enabled`（Boolean，預設 true）、`<id>_blocked_count`（Int，預設 0）。其他模組資料用 `<id>_` 前綴；所有計數以 `edit` 原子更新並防止 Int 溢位。
- `module_order` 是逗號分隔 ID 字串。讀取時去除未知 ID 與重複 ID，依使用者已存順序顯示，未出現的新模組附加到末尾。拖曳放開即在 Application scope 寫入，避免 Activity 銷毀取消保存。
- 舊 `youtube_shorts_*`／`instagram_reels_*` 已有一次性 DataMigration，保留開關與計數；新鍵已有資料時不得覆蓋。資料格式變更必須加相容遷移，禁止清空 DataStore。

## 版本、簽章與驗收

- 目前 versionCode 4 / versionName 1.0.3。每次發布 versionCode 必須嚴格增加；修正遞增 patch、新功能遞增 minor，Tag 精確等於 `v<versionName>`。
- 正式更新來源固定為公開倉庫 garytu001/personal-toolbox，不得恢復 YOUR_GITHUB_USERNAME。GITHUB_TOKEN 留空；手機不存放倉庫 Token。
- applicationId 與正式簽章金鑰不可變。Release 優先讀四個 KEYSTORE_* / KEY_* 環境變數，缺少欄位才讀 keystore.properties；金鑰與密碼不得提交版本控制。
- 每次修改完成執行 `gradlew.bat assembleDebug testDebugUnitTest lintDebug`（Unix 用 `./gradlew`），修正必要錯誤，說明未實機驗證的範圍。不能把編譯成功宣稱為實機功能驗證。
- 目前本機路徑含中文字；Windows 使用 Android Studio JBR 21 並加 `'-Dorg.gradle.jvmargs=-Xmx2048m -Dfile.encoding=COMPAT'`，避免 Gradle 8.14 測試工作程序的 classpath 編碼錯誤。保留 android.overridePathCheck=true；CI Linux/JDK17 使用原本標準命令，不加入本機編碼參數。
- GitHub 更新沿用原生 HttpURLConnection / org.json / DownloadManager / FileProvider。保留下載狀態、未知來源授權、APK 套件／版本／簽章驗證。
