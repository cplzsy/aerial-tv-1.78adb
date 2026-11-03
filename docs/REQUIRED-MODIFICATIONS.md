# Aerial 修改需求说明（用户需求 → 源码改动，供迁移到新版本）

> 用途：把用户从本项目工作开始以来提出的**全部代码修改需求**，重放到新版本的开源代码上。
> 本文件面向 AI 智能体（或熟悉该项目的开发者）编写：每个需求给出**用户原话要点、行为定义、实现文件与定位锚点、精确改动、字符串资源、迁移步骤与验收清单**。
> 实现基线：Aerial 1.74 / versionCode 94（Kotlin + Jetpack Compose，包名 `app.orchard.tv`）。

---

## 0. 迁移者先读（项目机制速览）

读源码前按此顺序：`docs/HANDOFF.md` → `docs/ARCHITECTURE.md` → `docs/LITE.md`（只改完整版时 Lite 可后置，见"通用红线"）。

本项目与本次需求直接相关的**现成机制**：

| 机制 | 位置 | 说明 |
|---|---|---|
| 家长密码 | `settings/AerialSettings.kt` | `pinSet`/`pinHash`/`locked`/`unlock`/`relock`；prefs 文件 `"settings"`；PIN 存 SHA-256；解锁有效期 10 分钟 |
| 解锁后执行通道 | `HomeViewModel.whenUnlocked(action)` | 已解锁直接执行；锁定则弹 `Overlay.Pin(PinMode.Unlock)`，验证通过后执行 action —— 这是"修改设置需验证 PIN"与"打开 App 需验证 PIN"的共用通道 |
| App 启动统一出口 | `MainActivity.launch(app, bounds)` | 主屏磁贴（`HomeScreen.onLaunch`）与文件夹内 App（`FolderView.onLaunch`）都汇聚到此；已有先例：`AerialSettings.locked && SystemProbe.isSettingsApp(...)` 时拦截电视设置 App 并要求 PIN |
| 个性化面板 | `ui/wallpaper/WallpaperSheet.kt` | 即用户口中的"个性化"；PIN 设置行在 `Group(stringResource(R.string.st_pin_hint))` 组内 |
| 控制中心 | `ui/control/ControlCenter.kt` | 即"设置"面板；"个性化"入口是 `MenuRow(Glyph.Photo, cc_personalize, ...)` |

**界面文字红线**：所有新增文案必须同时加入 `res/values`（英文）、`values-zh`、`values-ru`、`values-uk`、`values-kk` 五份 `strings.xml`，占位符一致。

---

## 需求一：受 PIN 保护的 App（指定 App，打开前验证家长密码）

### 1.1 用户原话要点

1. "对没有隐藏的指定 app 生效" —— 只作用于**未隐藏**的 App。
2. "增加一个指定 app 的设置项，可以选择哪些 app 为指定 app"。
3. "选择这些 app 后需要修改时还需要验证一次密码" —— 进入该设置/修改列表前需验证 PIN。
4. "在主界面打开某个指定 app 时也需要验证家长密码"。

### 1.2 行为定义

- 家长密码**未开启**：设置项不出现，一切照旧。
- 家长密码**开启**后：个性化面板（WallpaperSheet）的 PIN 组内、「家长密码」行下方出现「受 PIN 保护的 App」行，右侧 ActionChip 显示「无」或已选数量。
- 点击该行：若锁定则先弹 PIN（复用 `whenUnlocked`），验证通过后打开选择面板；解锁期内（10 分钟）修改不再逐步验证。
- 选择面板：候选 = 主屏 `apps` + 文件夹 `folderApps` 内所有 **`folder == null && source == null`** 的去重 AppEntry（真实可打开的 App；**隐藏 App 不提供**；文件夹磁贴、信号源磁贴不提供；文件夹内 App 可见所以包含——打开时同样走 `launch()` 拦截）。
- 勾选后：主界面或文件夹内打开该 App 时，若家长密码处于锁定态，先弹 PIN 验证，通过才启动。
- **开机自启兜底**：被保护的 App 若被设为「开机时打开」（`AerialSettings.bootApp`），不再自动打开（否则重启电视即绕过限制），留在主界面等主动打开时验 PIN。

### 1.3 文件改动（6 个 Kotlin 文件 + 5 个 strings.xml）

#### ① `app/src/main/java/app/orchard/tv/settings/AerialSettings.kt`

- **锚点**：`var pinSet by mutableStateOf(false); private set` 的声明之前。
- **新增状态**（含 KDoc 说明）：

```kotlin
var restrictedApps by mutableStateOf<Set<String>>(emptySet()); private set
```

- **锚点**：`fun init(context: Context)` 内 `pinArrows = prefs.getBoolean("pin_arrows", false)` 之后新增：

```kotlin
restrictedApps = prefs.getStringSet("restricted_apps", null)?.toSet() ?: emptySet()
```

- **锚点**：`fun relock()` 之后新增写回方法（`edit {}` 来自 `androidx.core.content.edit`，项目已在用）：

```kotlin
fun setRestricted(pkg: String, on: Boolean) {
    restrictedApps = if (on) restrictedApps + pkg else restrictedApps - pkg
    app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putStringSet("restricted_apps", restrictedApps) }
}
```

- 新增 prefs key：`"settings"` 内的 `restricted_apps`（StringSet，存包名）。

#### ② `app/src/main/java/app/orchard/tv/HomeViewModel.kt`

- **锚点**：`sealed interface Overlay` 中 `data object HiddenApps` 与 `data object Wallpaper` 之间新增：

```kotlin
data object RestrictedApps : Overlay
```

- **锚点**：`fun entry(key: String)` 之后新增候选列表方法（Home 顺序；`apps` 与 `folderApps` 均为该 ViewModel 公开只读属性）：

```kotlin
fun restrictedChoices(): List<AppEntry> {
    val seen = HashSet<String>()
    return buildList {
        (apps + folderApps.values.flatten()).forEach { e ->
            if (e.folder == null && e.source == null && seen.add(e.key)) add(e)
        }
    }
}
```

> 说明：`AppEntry.key` 对真实 App 等于 `packageName`（`Apps.kt` 中 `val key get() = packageName`）；source 磁贴 key 带 `input:` 前缀、folder 磁贴 key 为文件夹 key，故以 `key` 判重安全。

#### ③ `app/src/main/java/app/orchard/tv/ui/home/Overlays.kt`

- **新增 import**：`androidx.compose.foundation.shape.CircleShape`。
- **锚点**：`HiddenAppsSheet` composable 之后新增 `RestrictedAppsSheet`，版式完全复刻 `HiddenAppsSheet`（居中 660dp 玻璃面板、`chunked(4)` 网格、MiniTile 132×74、`liquidGlass(28.dp)`、`Motion.EaseOut/Drawer` 进出场、`dismissOnTap` + `BackHandler` 关闭）。差异点：
  - 参数：`(visible, apps: List<AppEntry>, restricted: Set<String>, onToggle: (String) -> Unit, onDismiss: () -> Unit)`。
  - 每格 `Pressable(onClick = { onToggle(app.key) })`，OK 切换勾选。
  - 选中态：磁贴右上角 `Box(size 22.dp).clip(CircleShape).background(Palette.Focus)` 内画 `GlyphIcon(Glyph.Check, Palette.OnFocus, 13.dp)`（与壁纸卡片选中角标一致）。
  - 标题/说明/空态分别用 `R.string.pin_apps_title / pin_apps_hint / pin_apps_empty`。

#### ④ `app/src/main/java/app/orchard/tv/ui/wallpaper/WallpaperSheet.kt`

- **锚点**：函数签名，在 `onHiddenApps: () -> Unit` 之后新增参数：

```kotlin
restrictedCount: Int,
onRestrictedApps: () -> Unit,
```

- **锚点**：PIN 组 `Group(stringResource(R.string.st_pin_hint)) { note -> ... }` 内、`SettingRow(R.string.st_pin, ...) { PinChoice(...) }` 之后新增条件行（`AerialSettings` 已在文件内使用）：

```kotlin
if (AerialSettings.pinSet) SettingRow(R.string.st_pin_apps, stringResource(R.string.st_pin_apps_hint), note) {
    ActionChip(
        if (restrictedCount == 0) stringResource(R.string.st_pin_apps_none)
        else stringResource(R.string.st_pin_apps_count, restrictedCount),
        Glyph.ChevronRight,
        onRestrictedApps,
    )
}
```

#### ⑤ `app/src/main/java/app/orchard/tv/ui/home/HomeScreen.kt`

- **锚点**：`WallpaperSheet(...)` 调用处，`onHiddenApps = { vm.whenUnlocked { vm.overlay = Overlay.HiddenApps } }` 之后新增两行：

```kotlin
restrictedCount = AerialSettings.restrictedApps.size,
onRestrictedApps = { vm.whenUnlocked { vm.overlay = Overlay.RestrictedApps } },
```

- **锚点**：`HiddenAppsSheet(...)` 挂载块之后新增挂载：

```kotlin
RestrictedAppsSheet(
    visible = overlay is Overlay.RestrictedApps,
    apps = vm.restrictedChoices(),
    restricted = AerialSettings.restrictedApps,
    onToggle = { pkg -> AerialSettings.setRestricted(pkg, pkg !in AerialSettings.restrictedApps) },
    onDismiss = { vm.overlay = Overlay.Wallpaper },   // Back 返回个性化
)
```

- `AerialSettings` import 已存在（同文件在用）；`RestrictedAppsSheet` 与 `HiddenAppsSheet` 同包（`app.orchard.tv.ui.home`），无需 import。

#### ⑥ `app/src/main/java/app/orchard/tv/MainActivity.kt`

- **锚点 A**：`private fun launch(app: AppEntry, bounds: Rect?)` 内、电视设置 App 的 PIN 拦截（`SystemProbe.isSettingsApp` 块）**之后**新增：

```kotlin
if (AerialSettings.locked && app.packageName in AerialSettings.restrictedApps) {
    vm.whenUnlocked { launch(app, bounds) }
    return
}
```

- **锚点 B（开机自启兜底）**：`private fun openBootSource(reason: String, again: Boolean = false)` 内、`if (id == null && pkg == null) return` 之后新增：

```kotlin
if (pkg != null && pkg in AerialSettings.restrictedApps) {
    android.util.Log.i("Aerial", "$reason: $pkg is PIN-protected; not opening it on boot")
    return
}
```

> `openBootSource` 打开 `bootApp` 走的是直接 `startActivity`（不经 `launch()`），必须单独拦截，否则重启电视即绕过限制。

### 1.4 字符串资源（7 个 key × 5 语言）

插入位置：各 `strings.xml` 中 `st_pin_hint` 之后、`pin_enter` 之前。

| key | EN | ZH | RU | UK | KK |
|---|---|---|---|---|---|
| `st_pin_apps` | PIN-Protected Apps | 受 PIN 保护的 App | Приложения с PIN-кодом | Захищені PIN-кодом застосунки | PIN-қорғалған қолданбалар |
| `st_pin_apps_hint` | Apps that ask for the PIN before opening. | 打开前需要输入家长密码的 App。 | Приложения, которые запрашивают PIN-код перед открытием. | Застосунки, які запитують PIN-код перед відкриттям. | Ашар алдында PIN-кодты сұрайтын қолданбалар. |
| `st_pin_apps_none` | None | 无 | Нет | Немає | Жоқ |
| `st_pin_apps_count` | %d | %d | %d | %d | %d |
| `pin_apps_title` | PIN-Protected Apps | 受 PIN 保护的 App | Приложения с PIN-кодом | Захищені PIN-кодом застосунки | PIN-қорғалған қолданбалар |
| `pin_apps_hint` | Select apps; they ask for the PIN before opening. | 选择后，打开这些 App 前需要输入家长密码。 | Выберите приложения: они запросят PIN-код перед открытием. | Виберіть застосунки: вони запитуватимуть PIN-код перед відкриттям. | Қолданбаларды таңдаңыз: олар ашар алдында PIN-кодты сұрайды. |
| `pin_apps_empty` | No apps to choose | 没有可选择的 App | Нет приложений для выбора | Немає застосунків для вибору | Таңдауға қолданба жоқ |

> 参考既有文案（确认存在）：`st_pin`=Parental PIN/家长密码；`st_hidden_count`=`%d`（chip 数字模式先例）；`hidden_title/hidden_hint/hidden_empty` 系列。

### 1.5 迁移步骤清单（供 AI 智能体按序执行）

1. 在 `AerialSettings.kt` 加状态 → init 读取 → `setRestricted`。
2. 在 `HomeViewModel.kt` 加 `Overlay.RestrictedApps` → `restrictedChoices()`。
3. 在 `Overlays.kt` 加 `CircleShape` import → 新增 `RestrictedAppsSheet`。
4. 在 `WallpaperSheet.kt` 加参数 → PIN 组条件行。
5. 在 `HomeScreen.kt` 传参 → 挂载面板。
6. 在 `MainActivity.kt` 加 `launch()` 拦截 + `openBootSource()` 开机拦截。
7. 五份 `strings.xml` 各加 7 个 key。
8. 编译（见附录命令）并静态核对：`Overlay` 新增成员不影响任何 `when(overlay)` 穷尽（本项目对 Overlay 只用 `is` 判断，无穷尽 `when`）。

### 1.6 验收清单（真机）

- [ ] 未设 PIN：个性化无「受 PIN 保护的 App」行。
- [ ] 设 PIN 后：行出现，右侧「无」；数量随勾选变化。
- [ ] 点该行弹 PIN，验证后进入选择面板；隐藏的 App 不在候选；文件夹内 App 在候选。
- [ ] 勾选某 App 后：主屏磁贴打开它先弹 PIN；文件夹内打开它同样弹 PIN；未勾选 App 不弹。
- [ ] 被保护 App 设为「开机时打开」后重启电视：不再自动打开。
- [ ] 解锁期内（10 分钟）改勾选不再逐步验证；Home 键/离开屏幕后重新锁定。

---

## 需求二：去除控制中心的「更新提醒」

### 2.1 用户原话

"该应用的设置中，当有更新时在菜单'个性化'下方就会有一个更新提醒，能否去除这个提醒，不再提示要去更新？"

### 2.2 行为定义

控制中心（设置面板）主菜单中，「个性化」行下方在有新版本时会显示一行「New Version: 版本号」（点击进入更新页）。**删除该提醒行**；任何情况下不再显示更新提示。后台版本检查（`Updates.checkIfStale`）可保留（只查不显示，对界面零影响）；更新页代码保留但无入口（不可达，无害）。

### 2.3 文件改动（1 个 Kotlin 文件）

`app/src/main/java/app/orchard/tv/ui/control/ControlCenter.kt`

- **锚点**：主菜单 `MenuRow(Glyph.Power, cc_boot, ...)`（开机目标行）之后、`if (SelfCompile.offered) { ... }` 之前，删除整个块：

```kotlin
Updates.available?.let { latest ->
    MenuRow(
        Glyph.Info,
        stringResource(R.string.cc_update_available, latest),
        onClick = { vm.overlay = Overlay.ControlCenter(CcPage.Update) },
    )
}
```

- `Updates` import 仍被其他位置使用（`Updates.current`、`Updates.install` 等），**不要删 import**。
- 字符串 `cc_update_available` 可保留（其余语言文件同步保留即可，无需改动）。

### 2.4 验收

- [ ] 有可用新版本时，控制中心「个性化」下方不再出现新版本行。
- [ ] 其余控制中心功能正常。

---

## 需求三：依赖仓库改为阿里云镜像优先（国内构建加速）

### 3.1 用户原话

"改为阿里云的镜像来下载依赖应该更快一些"（背景：官方仓库在境内连续报 429 限流与 TLS 中断，换镜像后构建成功）。

### 3.2 文件改动（1 个文件）

`settings.gradle.kts`

- **锚点**：`pluginManagement.repositories` 与 `dependencyResolutionManagement.repositories` 内，在官方仓库**之前**插入阿里云镜像（官方保留作兜底，未同步的依赖仍能解析）：

```kotlin
pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
    }
}
```

### 3.3 验收

- [ ] `./gradlew assembleStandardRelease` 依赖解析不出现 429/TLS 错误，构建完成。

---

## 附录 A：本地构建环境（未执行待办）

用户提出过：「把构建需要用到的所有环境和依赖全部放到项目文件夹下的 `IDE` 文件夹下，以后构建直接从此位置调用」。**该需求因会话额度中断尚未执行**，当前构建工具仍在项目外的 `C:\Users\chapl\.aerial-build`（便携 JDK 17 + Android SDK）。迁移源码时无需处理；如后续要落地，把该目录整体移入 `IDE/` 并在构建命令中引用即可（注意 `local/` 不入库的红线同样适用于 `IDE/` 目录）。

## 附录 B：构建与验证命令（Windows 本机参考）

```powershell
$env:JAVA_HOME = "C:\Users\chapl\.aerial-build\jdk\jdk-17.0.20.1+1"
$env:ANDROID_HOME = "C:\Users\chapl\.aerial-build\sdk"
& "D:\My Flies\Desktop\aerial-tv-db\gradlew.bat" -p "D:\My Flies\Desktop\aerial-tv-db" assembleStandardRelease
# 产物：app\build\outputs\apk\standard\release\app-standard-release.apk
# 签名验证：& "$env:ANDROID_HOME\build-tools\36.0.0\apksigner.bat" verify --print-certs <apk>
```

---

## 通用红线（迁移时一并遵守，源自 AGENTS.md）

1. 界面文案五语言同步，占位符一致。
2. Lite 模块（`lite/`，Android 4.x 原生 View）的个性化外观改动按 `docs/LITE.md` 约定需手动同步；本次需求一、二若需在 Lite 生效，需另行移植（用户当前只要求完整版，未做 Lite 同步）。
3. 不引入第三方依赖/下载/推荐其他 App；不停用系统应用。
4. 没有在真机上验证过的功能，不要说「已完成」。
5. 发布正式包前确认签名（`local/signing.properties`），发 TCL 版本前 `versionCode` +1。

---

# 需求四 ~ 需求十五：本文件基线（需求一~三）之后新增的需求修改

> **范围说明**：以下需求为本文件基线（需求一~三 + 附录）**之后**、自 2026-11-01 起用户陆续提出的全部新需求修改，按提出顺序编号。原有内容（需求一~三、附录、通用红线）保持原文未动。各需求均记录用户原话要点、行为定义、实现文件与锚点、字符串资源、验收清单与当前状态，供迁移时与需求一~三同等对待。迁移基线仍为 Aerial 1.74 / versionCode 94，最新实现在 1.77 / versionCode 99（需求十四~十五亦在此版本）。

## 需求四：网页端「更多」模块增加「备份还原」（扫描二维码打开的网页端）

### 4.1 用户原话要点

1. "在程序的设置--个性化 中扫描二维码打开的网页端的'更多'功能模块增加如下功能"。
2. "在'反馈问题'上方增加一个同级别的'备份还原'项目"。
3. "项目子级增加'导出备份'和'导入备份'两个项目，导出需要选择配置文件保存位置，导入需要选择配置文件所在位置"。
4. "点击'导出备份'后，确定好配置文件保存位置后，程序将应用的设置（包括桌面应用图标位置，各项设置参数，如天气，家长密码，隐藏app，屏保，受PIN保护的App等）生成可读取的配置文件保存在已指定的位置"。
5. "点击'导入备份'后，选择好指定配置文件后，程序直接读取里面保存的内容，将相关设置参数直接恢复的应用中，包括但不限于桌面图标位置，各项设置参数，如天气，家长密码，隐藏app，屏保，壁纸，受PIN保护的App等"。
6. "恢复成功直接在应用启用"。

### 4.2 行为定义

- 网页端（`assets/web/index.html`，由「个性化」二维码打开）「更多」页：在「反馈问题」**上方**新增同级的「备份还原」区。
- 「导出备份」：浏览器下载 JSON 备份文件，**保存位置由浏览器/用户自选**。
- 「导入备份」：浏览器选择本地 JSON 备份文件上传；导入即写回全部设置并**立即生效**（同一进程内 reload）。
- 备份内容 = 桌面布局 + 全部设置项（天气、家长密码、隐藏 App、屏保、壁纸、受 PIN 保护的 App 等），不含设备状态与图片。

### 4.3 文件改动与锚点

- `app/src/main/java/app/orchard/tv/wallpaper/PhoneServer.kt`
  - 路由（约 L99-100）：`GET /api/backup/export -> backupExport()`；`POST /api/backup/import -> backupImport(session)`。
  - `backupExport()`（约 L394）：返回 `BackupStore.buildJson(context)`，`Content-Disposition: attachment; filename="aerial-backup-<stamp>.json"`。
  - `backupImport(session)`（约 L406）：解析 multipart 字段 `backup`（文件大小有上限，防撑爆存储），`BackupStore.restoreJson(context, text)` 校验并写回。
- `app/src/main/assets/web/index.html`
  - KEYS 表新增：`backup_h`、`backup_export`、`backup_import`、`backup_hint`、`toast_exported`、`toast_export_fail`、`toast_imported`、`toast_import_bad`、`toast_import_fail`；TEXTS 表五语言（EN/ZH/RU/UK/KK）同步。
  - 「更多」页结构：备份还原区在「反馈问题」上方；导出/导入按钮 + 隐藏 file input（`#backupFile`）。
- 各设置单例新增 `reload()`（导入后刷新用）：`AerialSettings`、`AppNames`、`SourceLooks`、`Wallpapers`、`Weather`。

### 4.4 验收

- [ ] 网页端「更多」页可见「备份还原」，位置在「反馈问题」上方。
- [ ] 导出得到合法 JSON（format=1），内容含 5 组 prefs。
- [ ] 导入该文件后：图标位置/天气/密码/隐藏 App/壁纸/PIN 保护等立即恢复。

### 4.5 状态

已完成并随旧版 APK 构建验证；后续被需求五（TV 端）复用同一 `BackupStore` 核心。

---

## 需求五：TV 端「备份与还原」移植到应用设置（控制中心）

### 5.1 用户原话要点

1. "把前面开发的网页端的备份还原功能移植到应用设置中，菜单名叫'备份与还原'，入口放到个性化的下方"。
2. "当点击备份时将备份的配置文件导出写入到电视的指定文件夹内（创建一个专属的文件夹）"。
3. "导出之后检测一下文件夹内的配置文件数量，如果有多个的时候则删除老文件，最多只保留两份配置文件，文件名带上时间以便识别"。
4. "点击还原时则取这个指定文件夹内获取备份配置文件，但有多个文件时弹出一个选择列表，选择哪个文件就以哪个文件来恢复设置"。
5. "特别注意的是这个配置文件不会因为应用的卸载而删除"。

后续指令（存储与目录，按提出顺序）：
- "那你就固定写到/sdcard/Documents文件夹下吧"（替代初版 `Download/AerialBackup`——该小米电视存储顶层**无 Download 目录**，adb 实测 `ls /sdcard/Download/` 报 No such file or directory；同时修正 MediaStore `RELATIVE_PATH` 必须带尾斜杠 `"Documents/AerialBackup/"`）。
- "当这个目录不存在的时候，你就自动创建一个这个目录"。
- 卸载重装后**仍要能读到备份**（用户两次实测反馈"卸载后重装读不到/还原列表空"）。

缺陷修复（用户实操反馈，2026-11-01）：
- **还原列表读不到文件**：根因 = MediaStore 记录存在（`owner_package_name=NULL`）但 Android 卸载时清除应用对其**非媒体文件**的所有权，Scoped Storage 下应用查询非自身所有的非媒体文件恒为空（adb shell 特权可见、应用不可见）。修复：**Android 11+ 改为文件系统直接读写** `/sdcard/Documents/AerialBackup/` + `MANAGE_EXTERNAL_STORAGE`（一次性授权，卸载重装后照常可读）；Android 10 保留 MediaStore Files；Android 5-9 文件系统 + `WRITE_EXTERNAL_STORAGE`。
- **还原图标**："还原功能没有对应的图标，应该要把备份的图标之间上下颠倒一下放在还原功能上" → 新增 `Glyph.Upload`（`Glyph.Download` 的垂直镜像，变换 y′ = −960−y），还原文件列表行使用。

### 5.2 行为定义

- 控制中心（设置面板）主菜单「个性化」行**下方**新增「备份与还原」行（Glyph.Download），进入 BackupPage。
- 存储位置固定 `/sdcard/Documents/AerialBackup/`（共享外部存储，**卸载不删**）；目录不存在自动创建。
- 备份文件名 `aerial-backup-yyyyMMdd-HHmmss.json`（时间戳**到秒**，避免同分钟连续备份被 MediaStore 加 `(1)(2)` 后缀）；导出后**最多保留最近 2 份**，删除更旧者。
- 备份内容：`settings`、`layout`、`wallpaper`、`weather`、`icon_library` 五个 SharedPreferences 的全部键值 → JSON（`format=1`，含 app 版本、导出时间、设备型号）。
- 还原：列出目录内备份（**多个时逐行列出供选择**，选中即以该文件恢复）；`restoreJson` 校验 `format` → 清空并写回 5 个 prefs → 主线程 reload（`AerialSettings/AppNames/SourceLooks/Wallpapers/Weather` + `AppCatalog.restoreHome`）→ **立即生效**。
- API 30+ 未授权 `MANAGE_EXTERNAL_STORAGE` 时：BackupPage 顶部显示授权引导行（点击直达系统设置「所有文件访问」），授权返回后自动刷新列表。

### 5.3 文件改动与锚点

- **新建** `app/src/main/java/app/orchard/tv/settings/BackupStore.kt`（核心，网页端与 TV 共用）：
  - 常量：`DIR="AerialBackup"`、`MEDIA_ROOT = Documents/AerialBackup/`（尾斜杠必需）、`PREFS=[settings,layout,wallpaper,weather,icon_library]`、`FORMAT=1`、`MAX_FILE=1MB`、`KEEP=2`。
  - `buildJson/restoreJson`（共用序列化/反序列化）；`export()`（平台三分支）、`list()`（平台三分支）、`restore()`、`trimMedia/trimFile`（保留 KEEP 份）、`ensureFolder()`（`mkdirs` 兜底 + MediaStore insert 自动建目录双保险）。
  - 平台分支：API 30+ = 文件系统 + `Environment.isExternalStorageManager()` 校验；API 29 = MediaStore Files；API 21-28 = 文件系统。
- `app/src/main/java/app/orchard/tv/wallpaper/PhoneServer.kt`：`backupExport/backupImport` 改为复用 `BackupStore`（网页端行为不变）。
- `app/src/main/java/app/orchard/tv/HomeViewModel.kt`：`CcPage` 枚举加 `Backup`（约 L66）。
- `app/src/main/java/app/orchard/tv/ui/control/ControlCenter.kt`：
  - 主菜单「个性化」下方：`MenuRow(Glyph.Download, cc_backup, onClick={ overlay = ControlCenter(CcPage.Backup) })`（约 L308-312）。
  - 新增 `BackupPage`（约 L477）：返回行 + 「立即备份」行 + 状态提示 + 还原文件列表（行图标 **Glyph.Upload**，trailing「还原 ›」）+ 帮助文案（写明路径）+ API30+ 授权引导行；`ensurePerm` 三分支（All Files → legacy WRITE → 直接执行）；`doExport/doRestore` 均在 IO 线程；BackHandler 分支齐。
- `app/src/main/java/app/orchard/tv/ui/theme/Glyphs.kt` + `GlyphPaths.kt`：`Glyph` 枚举加 `Upload`（在 `Download` 之后）；`GlyphPaths.UPLOAD` = DOWNLOAD 的垂直镜像 path。
- `app/src/main/AndroidManifest.xml`：新增 `<uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" tools:ignore="ScopedStorage" />`；`WRITE_EXTERNAL_STORAGE` 保持 `maxSdkVersion=28`（注释同步更新）。
- `res/values*/strings.xml` ×5：新增 11 个 key —— `cc_backup`、`cc_backup_export`、`cc_backup_exported`、`cc_backup_export_failed`、`cc_backup_restore_title`、`cc_backup_restore`、`cc_backup_none`、`cc_backup_restored`、`cc_backup_restore_failed`、`cc_backup_hint`、`cc_backup_perm`；占位符 `%1$s` 一致（EN/ZH/RU/UK/KK）。

### 5.4 验收清单（真机，94 / Android 14）

- [ ] 控制中心「个性化」下方出现「备份与还原」。
- [ ] 备份后 `/sdcard/Documents/AerialBackup/` 出现 `aerial-backup-*.json`；连续备份只留最近 2 份，旧的被删。
- [ ] **卸载重装后**：首次使用先授权「所有文件访问」（设置 → 应用 → Aerial），还原列表仍能列出备份并恢复成功；恢复后图标位置/天气/密码/隐藏 App/壁纸/PIN 保护立即生效。
- [ ] 还原行图标为向上箭头（Upload，备份图标的上下颠倒）。
- [ ] 页面帮助文案写明 `/sdcard/Documents/AerialBackup/`。

### 5.5 状态

已实现并构建安装（2026-11-01，versionName 1.76 / versionCode 97，固化签名）；还原列表修复（文件系统方案）与 Upload 图标已包含在最新版。**用户实操验收中**（未最终确认前不得宣称全部完成）。

---

## 需求六：签名固化

### 6.1 用户原话

"将现在项目的debug签名固化为以后的正式版签名保存在项目对应目录中，以后所有构建操作都用这个签名"。

### 6.2 行为定义

以后所有 release 构建统一使用固化签名；对用该签名装过的电视 `adb install -r` 覆盖安装不再报签名不符。

### 6.3 文件改动

- `local/debug.keystore`（2618 字节）+ `local/signing.properties`（不入库红线同 `local/`）。
- APK 证书 SHA-256：`8bd2c20b0d3eaef4faa8c620e023402540064cf789f4839d0d46fc1e74cb8bc5`（CN=Android Debug）。

### 6.4 状态

已完成；94 覆盖安装（install -r Success）验证通过。

---

## 需求七：构建策略

### 7.1 用户原话（按时间顺序）

1. "不要再主动构建，只有我让你构建再进行"。
2. "以后构建只需要Standard，其他不需要，lite版本的代码可以删了" → "算了 还是不删吧 构建默认只需要standard，除非我明确告知你需要别的版本"。

### 7.2 行为定义

- 构建默认只 `assembleStandardRelease`；**除非用户明确要求**才构建其他版本。
- `lite/` 目录**保留不删**（曾要求删除、随即撤回）。
- **不主动构建**：只有用户明确下令才执行构建。

### 7.3 实现

- `D:\DobaoDebg\IDE\build-aerial.ps1` 默认 `Tasks=@("assembleStandardRelease")`；构建命令 `powershell -ExecutionPolicy Bypass -File D:\DobaoDebg\IDE\build-aerial.ps1 -Tasks assembleStandardRelease`。

### 7.4 状态

已落实（2026-11-01 起所有构建均按此执行）。

---

## 需求八：构建环境与产物目录

### 8.1 用户原话

1. "将项目构建所需的环境和依赖下载到D:\DobaoDebg\IDE中，后续项目需要构建时就从此位置获取环境和依赖"（"下载限流时从阿里云镜像获取"）。
2. "构建完成保存到项目文件夹的apk文件夹下"。

### 8.2 实现

- 构建环境固定于 `D:\DobaoDebg\IDE\`：`jdk\jdk-17.0.20.1+1`、`android-sdk`（build-tools 36.0.0、platform-tools）、`gradle\gradle-8.14.3`、`gradle-user-home`（依赖缓存；`settings.gradle.kts` 已配阿里云镜像优先，见需求三）。
- 构建脚本 `build-aerial.ps1`（JAVA_HOME/ANDROID_HOME/GRADLE_USER_HOME 固定指向 IDE；`--no-daemon`；R8 + lintVital 通过即成功）。
- 产物复制到 `D:\DobaoDebg\aerial-tv-1.76\apk\app-standard-release.apk`。
- 附录 A 的「IDE 环境未执行待办」已在本会话落地（执行细节以此需求为准；附录 A 原文保留未动）。

### 8.3 状态

已完成。

---

## 需求九：时钟大小=小 时显示完整信息（日期+时间+天气，一行）

### 9.1 用户原话要点

1. "我现在的需求其实就是即便时钟大小设置的是'小'时，也要显示跟'大'一样的完整信息，一行显示在设置的位置上。"
2. 背景：1.77/99 安装到 94 电视后，用户连续截图对比——大时钟（ShelfClock）显示日期/大时间/天气三行完整信息，小时钟（CornerClock）原先只显示一行 `时间 地点 温度°`（如 `18:41 合肥 15°`），信息量不足。
3. 迭代调整："在'小'模式下时间日期星期的字号与天气的字号不一致啊，全改成与天气一样的字号字体，然后天气换行显示。"

### 9.2 行为定义

- 时钟大小=小（`AerialSettings.largeClock = false`）时，小时钟显示与大时钟**相同的信息内容**（日期含农历 + 时间 + 天气详情），分两行：
  - 第一行：日期（中文环境带农历）+ 时间；
  - 第二行：天气详情（温度 + 地点·天气状况·今日最高/最低）。
- **两行统一字号与字体**：均为 `Type.Headline`（InterText W600 15sp，白 0.92 + 阴影），与天气行一致；不再使用大时钟的 ClockDate/Title 字号。
- **两行居中对齐**：`Column(horizontalAlignment = Alignment.CenterHorizontally)`，以较宽的天气行为中轴居中（用户指出左对齐"很别扭"，要求"剧中对齐"）。
- 显示位置不变：跟随 `clockRight` 设置（左上角，或右上角状态胶囊旁）。
- 进入屏保的动效保持：仅时间移动/放大到屏保位置（`toSaverClock`，基准字号随之改为 Headline），日期与天气随屏保淡出（`aside`）。
- 大时钟（`largeClock = true` 的 ShelfClock）与屏保时钟不受影响。

### 9.3 文件改动

- `app/src/main/java/app/orchard/tv/ui/home/HomeChrome.kt` —— `CornerClock` composable（约 L763）：
  - 外层由单行 `Row(height = 34.dp)` 改为 `Column(verticalArrangement = Arrangement.spacedBy(4.dp))` 两行。
  - 第一行 `Row`：日期段 `dateText(now)`（系统语言为中文时追加农历 `Lunar.text`，同 ShelfClock 逻辑）+ 时间段 `timeText(now)`，两者均用 `Type.Headline.copy(color = dim, shadow = shadow)`；时间仍走 `toSaverClock(toSaver, Type.Headline.fontSize, ...)`。
  - 第二行 `Row`：天气图标（`GlyphIcon` 18dp）+ `温度°` + detail（`place · 天气状况 · weather_high_low`，`conditionName` 映射，`SpanStyle(W500)` 分隔），样式 `Type.Headline`。
  - `aside`（屏保淡出）应用于第一行日期与第二行天气。
  - 无新增字符串资源（复用 `weather_high_low` 与既有天气状况文案）。

### 9.4 验收清单（真机）

- [x] 94 电视（时钟大小=小、位置=左上）第一版一行：顶部一行显示 `11月1日 星期日 九月廿三 18:54 14° 合肥·雨·最高20°最低14°`（2026-11-01 实机截图确认）。
- [x] 94 电视迭代版两行（2026-11-01 实机截图确认，19:07）：第一行 `11月1日 星期日 九月廿三 19:07`，第二行 `14° 合肥·雨·最高20°最低14°`，两行同字号同字体。
- [ ] 时钟大小=大：ShelfClock 三行信息栏行为不变。
- [ ] 位置=靠右：两行完整信息显示在右上角状态胶囊旁。

### 9.5 状态

已实现并构建安装（2026-11-01，versionName 1.77 / versionCode 99，固化签名）；94 电视 `install -r` + `cmd package compile -m speed` 后实机截图验证通过。

---

## 需求十：ADB 连接能力移植（skyadb → Aerial，纯后端）

### 10.1 用户原话要点

1. "参考项目skyadb，将其实现adb连接的能力移植到本项目中"。
2. "移植过来的skyadb在aerial中不需要UI界面的，只需要在后端接受功能调用即可"（两轮强调后端无 UI，后改口要 UI——见需求十二；两轮均为用户指令）。
3. "移植完成就好，别现在就构建啊"（构建节奏：只有用户明确下令才构建，与需求七一致）。
4. 范围裁定：移植 = 连接 + 核心管理（不含 USB OTG / fastboot / 投屏 / 文件浏览）。
5. **项目红线覆盖**：AGENTS.md 原「不要加连 adbd 的 shell 通道/第三方 SDK」被用户（owner）明确覆盖——用户要求 ADB 功能，以用户指令为准。

### 10.2 行为定义

- 后端能力：无线**直连**（无配对码）、会话管理（单活跃会话）、`shell`、`install`（流式进度）、最近设备、发现（mDNS 自实现）。
- 无 UI 纯后端：门面 `AdbService.init(context)`（幂等）+ 广播入口 `AdbCommandReceiver`（action `app.orchard.tv.ADB`）。
- 运行时门控：`supported() = SDK_INT >= M`（kadb AAR 的 minSdk 23，`tools:overrideLibrary` 强制合并后由代码门控，低版本不启用、不崩溃）。
- **直连模式**（配对码方案被用户否决）：本机 IP → mDNS `_adb._tcp.`（Legacy）端口，否则 5555 → 连接等待电视 RSA 授权框（用户点「记住/允许」）。

### 10.3 文件改动与锚点

#### 后端源码（13 个 Kotlin 文件，新建于 `app/src/main/java/app/orchard/tv/adb/`）

| 文件 | 职责 / 关键实现 |
|------|-----------------|
| `AdbModels.kt` | 模型 + `operationCatching`（inline `Result` 包装）；`AdbOperationResult<T>`（`Success/Failure`） |
| `AdbManager.kt` | 会话核心：`sessionMutex` 单活跃会话；`probeConnection = echo kadb_ready`；`shellQuote`；**关键签名**：`connect(host, port, connectTimeoutMillis=10_000, socketTimeoutMillis=30_000): AdbOperationResult<String>`、`pair(host, port, pairingCode, name="aerial")`、`install(apkFile: File, onProgress: ((Long,Long)->Unit)?)`、`shell(command)`、`sessionKind()`、`currentEndpoint()` |
| `CountingSource.kt` | okio `install` 进度包装（流式安装进度） |
| `AdbRecentDevices.kt` | SharedPreferences 最近设备（管道分隔转义） |
| `AdbDiscovery.kt` | 扫描（并发 48 / 600ms）/ 范围解析 / 协议探测（按 `A_CNXN/A_AUTH/A_STLS` 判定） |
| `AdbMdns.kt` | NsdManager 自实现 mDNS（直连模式仅用 Legacy `_adb._tcp.` 发现；**kadb-mdns 弃用**） |
| `AdbService.kt` | 门面 + op 命令表 + `serializePrivilegeReport(report, keepAdb)`（**internal**） |
| `AdbCommandReceiver.kt` | 广播入口（action `app.orchard.tv.ADB`） |
| `AdbPrivilegeResult.kt` / `AdbPrivilegeCommands.kt` / `AdbPrivilege.kt` | 提权后端（见需求十一） |
| `AdbLocalConnect.kt` | 直连版：`connectLocal(): AdbOperationResult<String>`；本机 IP 取 `NetworkInfoProvider.currentLocalNetworks("local")`；端口优先 mDNS Legacy 否则 5555；`connectTimeout 20_000` |
| `AdbDramaUpgrade.kt` | 短剧之家升级后端（见需求十三） |

#### 依赖与构建配置（4 个配置文件 + 依赖锁定原因）

- `settings.gradle.kts`：新增 `maven("https://jitpack.io")`——传递依赖 `spake2-java:1.0.5` **仅存在于 JitPack**（maven central 404/429）。
- `app/build.gradle.kts`：`implementation("com.flyfishxu:kadb:2.1.1")` + `packaging { exclude("META-INF/versions/9/OSGI-INF/MANIFEST.MF") }`（bouncycastle OSGI 冲突条目）。
- `app/src/main/AndroidManifest.xml`：
  - `<uses-permission android:name="android.permission.CHANGE_WIFI_MULTICAST_STATE" />`；
  - `<uses-sdk tools:overrideLibrary="com.flyfishxu.kadb" />`（kadb AAR minSdk 23 > 本项目 minSdk 21）；
  - `<receiver android:name=".adb.AdbCommandReceiver" android:exported="true">` + intent-filter action `app.orchard.tv.ADB` + `<uses-permission android:name="android.permission.WRITE_SECURE_SETTINGS" />`。
- `app/proguard-rules.pro`：`-keep class com.flyfishxu.** { *; }` + `-dontwarn com.flyfishxu.**`。
- **依赖锁定原因（勿升级）**：kadb 2.1.2~2.1.5 与 kadb-mdns 2.1.3/2.1.4 均为 **Kotlin 2.4 编译**（metadata `[2,4,0]`），Aerial 的 Kotlin 2.2.10 读不了；**仅 `2.1.1`**（metadata 2.3.0，公开 API 与 2.1.5 逐一对得上）可用。kadb-mdns 弃用，mDNS 用系统 NsdManager 自实现。

### 10.4 迁移步骤清单

1. 复制 `adb/` 包 13 个文件（含需求十一/十三文件，一并携带）。
2. 加依赖 `kadb:2.1.1` + jitpack 仓库 + packaging exclude。
3. Manifest 加权限 / overrideLibrary / AdbCommandReceiver 声明。
4. proguard 加 keep + dontwarn。
5. `AdbService.init(context)` 在应用启动时调用（幂等，可多次）。
6. 五份 `strings.xml` 加 ADB 字符串（清单见需求十二）。
7. 编译（附录 B 命令）并静态核对：`supported()` 在低版本设备不崩。

### 10.5 验收清单（真机）

- [ ] release 构建通过（assembleStandardRelease），R8 后 `adb/` 类未被裁剪（proguard keep 生效）。
- [ ] 电视无线调试开启后：ADB 管理页发起连接，电视弹「允许 USB 调试」框，点记住/允许后连接成功。
- [ ] SDK_INT < 23 设备：功能不崩溃（`supported()=false` 不启用）。
- [ ] 广播 action `app.orchard.tv.ADB` 可触发后端命令。

### 10.6 状态

已完成并随 1.77 / versionCode 99 构建交付；依赖与配置见 §10.3。

---

## 需求十一：ADB 提权后端（等待调用，无 UI）

### 11.1 用户原话要点

1. ""D:\DobaoDebg\aerial-tv-adb\docs\ADB提权功能需求说明.md"根据此说明文件在应用后端实现一个ADB提权功能等待调用"。
2. "把这个功能会执行的adb语句输出给我"（已完整输出，非产物；执行语句 = docs/ADB提权功能需求说明.md §5 的 13 条授权 + 7 组流水线命令）。

### 11.2 行为定义

按 `docs/ADB提权功能需求说明.md` 实现后端流水线（无 UI，等待调用）：
- **7 组命令流水线**：① 授权（13 条逐条 `pm grant`/`appops set`/`cmd notification`，各自独立判定成败）→ ② 冷启动（`am start -W -n MainActivity`）+ `delay(5s)` + 编译（`cmd package compile -m speed -f`）→ ③ 屏保（先查 `screensaver_enabled`，输出为 `"1"` 才执行，否则**整组 SKIPPED**）→ ④ `keep_adb` 广播（用户开关关闭时**整组 SKIPPED**）→ ⑤ 启动 Aerial（`requireStatusOk`：exitCode==0 且输出含 `"Status: ok"`）→ ⑥ 默认主页（主命令失败追加回退 `set-home-activity`）→ ⑦ 无障碍（读 → 合并 → 写回，冒号追加不清空既有服务）。
- 命令常量指向 Aerial 五组件：`MainActivity.kt`、`guard/ConfigReceiver.kt`、`guard/RevivalListener.kt`、`dream/AerialDream.kt`、`guard/HomeGuardService.kt`（均已存在，勿改名）。
- 执行完成后统计 `failedCount > 0` 时写诊断日志（`system/DiagLog.kt`，TAG="AerialDiag"）；无论成败返回报告。

### 11.3 文件改动与锚点

- 新建 `adb/AdbPrivilegeResult.kt`：`PrivilegeStep`/`PrivilegeCommandState`/`PrivilegeCommandResult`/`PrivilegeStepResult`/`AdbPrivilegeReport`（对齐说明文档 §6.1–6.3）。
- 新建 `adb/AdbPrivilegeCommands.kt`：7 组命令清单 + 6 个组件常量（`TARGET_PACKAGE=app.orchard.tv` 等，值见说明文档 §5）。
- 新建 `adb/AdbPrivilege.kt`：`runAdbPrivilege(keepAdb)` 流水线 + 单命令执行器（`requireStatusOk`、输出 `take(300)` 截断、失败不中断）。
- `adb/AdbService.kt`：`serializePrivilegeReport(report, keepAdb)`（internal）——报告序列化供 UI/广播消费。
- 实现位置与 skyadb 原版（`repository/AdbRepository.kt` + `DeviceViewModel`）不同：Aerial 全部集中在 `adb/` 包，无 ViewModel。

### 11.4 验收清单（真机）

- [ ] 直连成功后调用流水线：13 条授权逐条执行、单条失败不中断。
- [ ] 屏保未启用 / keep_adb 关闭：对应整组 SKIPPED 并附原因。
- [ ] 默认主页主命令失败：自动追加 `set-home-activity` 回退。
- [ ] 无障碍写回不清空既有服务（读→冒号追加→写回）。

### 11.5 状态

后端已实现并随 1.77 / 99 交付；真机完整提权流程待验收（依赖需求十二 UI 触发）。

---

## 需求十二：ADB 管理 UI（控制中心子页面，非弹窗）

### 12.1 用户原话要点

1. "在aerial的设置--个性化下方增加'ADB管理'菜单，点击该菜单后弹出一个弹窗层（样式类似家长密码的哪个框），在这个层分行居中显示按钮'短剧之家升级'，'ADB提权增强'两项功能；'短剧之家升级'为空功能按钮，后续在做开发；点击'ADB提权增强'后，程序发起对电视本机的ADB连接申请，等待用户操作允许同意后连接成功就直接调用后端的ADB提权功能执行，完成后反馈执行结果日志"。
2. "ADB连接不是配对码模式，是直连，只需要用户在电视上点击记住、允许就行"（**否决 mDNS 配对码，改无线直连 + RSA 授权框**）。
3. "排查下这两个功能中的ADB连接是不是都调用了之前从skyadb移植过来的adb能力，避免空有功能界面而无实际能力的情况"（结论：两功能均经 `AdbService.manager` / `AdbLocalConnect.connectLocal()` 调用移植层，无空壳）。
4. "这两个功能不搞弹窗层了，就搞'备份与还原'一样的模式即可，少一些UI上的不一致性"（**否决弹窗层，改控制中心子页面**）。

### 12.2 行为定义

- 控制中心主菜单「个性化」行**下方**新增「ADB 管理」行（`Glyph.Usb`，`cc_adb`），点击进入 **`CcPage.Adb` 子页面**（与 BackupPage 同模式，非弹窗）。
- 页面结构：顶部返回行 `MenuRow(ChevronLeft, adb_panel_title)`（Back 返回控制中心主菜单）→ 功能行（**自上而下：「Aerial 升级安装」→「ADB 提权增强」→「短剧之家升级」**——短剧之家升级放最下一行，优先位给 Aerial 升级与提权增强，2026-11-02 用户要求调整）→（升级场景）APK 列表行 → 日志区（`Caption`，`heightIn(max=470.dp)` + `verticalScroll`）。
- 直连流程（两功能共用）：点击功能行 → `AdbLocalConnect.connectLocal()` 本机直连 → 电视弹「允许 USB 调试？」框 → 用户点「允许/记住」→ 连接成功 → 执行对应后端。
- 提权增强：连接成功 → `AdbPrivilege.runAdbPrivilege` → 页面内日志反馈执行结果。
- 日志更新统一经**主线程 Handler 排队**（进度回调跑后台线程，先清再写，防旧进度行回写）。
- 弹窗版 `AdbPanel.kt` **已删除，勿恢复**；Overlay 无 `Adb`（子页面由 `ControlCenter(page)` 承载）。

### 12.3 文件改动与锚点

- `app/src/main/java/app/orchard/tv/HomeViewModel.kt`：`CcPage` 枚举加 `Adb`（`Backup` 之后，约 L66 区）；**删除** `Overlay.Adb`（弹窗遗留）。
- `app/src/main/java/app/orchard/tv/ui/control/ControlCenter.kt`：
  - 主菜单「个性化」后新增 `MenuRow(Glyph.Usb, cc_adb, onClick = { vm.overlay = Overlay.ControlCenter(CcPage.Adb) })`；
  - 新增 `AdbPage` composable（仿 BackupPage：返回行 + 功能行 + APK 列表行 + 日志区）；`when(page)` 分发加 `CcPage.Adb`；`BackHandler` 中 `CcPage.Adb -> Main`。
  - 下载/安装进度节流变量用 **Int**（勿 `-1L` Long 与 Int 比较——已踩坑修复）。
- `app/src/main/java/app/orchard/tv/ui/home/HomeScreen.kt`：删除 AdbPanel 分发（弹窗遗留清理）。
- 删除 `app/src/main/java/app/orchard/tv/ui/home/AdbPanel.kt`（**已删，勿恢复**）。
- `MenuRow` 位于 `ui/home/Overlays.kt`（44dp 高、`BasicText maxLines=1` + ellipsis、trailing `widthIn(max=200.dp)`，长文件名安全，无需改动）。

### 12.4 字符串资源（28 个 key × 5 语言）

插入位置：各 `strings.xml` 控制中心文案区之后。占位符一致（`%1$s` / `%1$d%%`）。

| key | 用途 |
|-----|------|
| `cc_adb` | 主菜单「ADB 管理」 |
| `adb_panel_title` / `adb_panel_body` | 页面标题 / 说明（body 为弹窗遗留文案，保留未用） |
| `adb_drama_upgrade` / `adb_drama_upgrade_desc` | 短剧之家升级行 / 描述 |
| `adb_privilege_boost` / `adb_privilege_boost_desc` | ADB 提权增强行 / 描述 |
| `adb_close` / `adb_back` | 关闭 / 返回 |
| `adb_discovering` / `adb_no_wireless_debug` | 发现中 / 未找到无线调试 |
| `adb_pairing_hint` / `adb_pairing_progress` | 配对码文案（直连后未用，保留） |
| `adb_connecting_progress` / `adb_authorize_hint` / `adb_no_connect_service` | 连接中 / 授权提示 / 无连接服务 |
| `adb_privileging_progress` / `adb_privilege_done` / `adb_failed` | 提权执行中 / 完成 / 失败 |
| `adb_upgrade_fetching` / `adb_upgrade_no_assets` | 拉取中 / 无可用 APK |
| `adb_upgrade_arm64` / `adb_upgrade_armv7` | 「64位，客厅电视安装」/「32位，主卧电视安装」 |
| `adb_upgrade_downloading`（`%1$s`）/ `adb_upgrade_download_pct`（`%1$d%%`） | 下载中 / 下载进度 |
| `adb_upgrade_installing` / `adb_upgrade_install_pct` | 安装中 / 安装进度 |
| `adb_upgrade_done` / `adb_upgrade_progress` | 安装完成 / 升级进行中 |

### 12.5 迁移步骤清单

1. `HomeViewModel`：CcPage 加 `Adb`（删 Overlay.Adb）。
2. `ControlCenter`：主菜单行 + AdbPage + 分发 + BackHandler。
3. `HomeScreen`：删 AdbPanel 分发；删 `AdbPanel.kt` 文件。
4. 五份 `strings.xml` 加 28 个 key。
5. 编译核对：`when(CcPage)` / `when(page)` 穷尽无遗漏。

### 12.6 验收清单（真机）

- [ ] 控制中心「个性化」下方出现「ADB 管理」；页面为子页面模式（顶部返回行，非弹窗）。
- [ ] 电视 35 实机可打开页面（2026-11-01 截图确认）。
- [ ] 点「ADB 提权增强」：直连 → 电视授权 → 流水线执行 → 页面日志反馈。
- [ ] 两功能均真实调用移植层（无空壳界面）。

### 12.7 状态

已实现并装机（电视 35 页面可达已截图确认）；提权执行结果待完整验收。

---

## 需求十三：短剧之家升级（GitHub release → 直连 → 下载 → ADB 安装）

### 13.1 用户原话要点

"现在对'短剧之家升级'按钮功能进行实现，点击该按钮后到https://github.com/jackherex/duanjuzhijia-down-tv/releases/latest获取.apk文件的下载地址，以弹窗层的形式显示文件列表，每个文件是一个按钮，与之前的ADB提权类似，文件名包含'arm64-v8a.apk'的在按钮上显示'64位，客厅电视安装'，文件名包含'armeabi-v7a.apk'的在按钮上显示'32位，主卧电视安装'，点击任一按钮后进行一次本机的adb连接（端口模式），然后开始下载对应apk文件，下载完成后通过adb完成安装"。

> UI 载体随后随需求十二改为子页面模式（列表行同样适用，弹窗按钮描述不变）。

### 13.2 行为定义

- 点击「短剧之家升级」→ `fetchLatestAssets()` 拉 GitHub API `releases/latest` 的 `.apk` 资产列表。
- 列表行文案：文件名含 `arm64-v8a.apk` → 「64位，客厅电视安装」；含 `armeabi-v7a.apk` → 「32位，主卧电视安装」；其余显示文件名。**列表行不显示文件名 trailing**（2026-11-02 用户要求：文件列表只显示对应按钮，文件名会覆盖按钮导致按钮名看不全；Drama 分支 `MenuRow` 已去掉 `trailing = asset.name.take(24)`）。
- **HGTV 升级（2026-11-02 用户新增）**：短剧之家文件列表**最下方**新增「HGTV升级」行（`adb_hgtv_upgrade`，5 语言）→ `fetchHgAssets()` 拉 `api.github.com/repos/pan8664716/HGTV/releases/latest` 的 `.apk` 资产：**仅 1 个 APK 直接下载并 ADB 安装**（`runUpgrade` 直连）；**多个则以列表形式展示**（复用 `assets` 状态 + List 阶段），选中哪个就下载并 ADB 安装哪个；空列表/网络失败显示失败日志（`operationCatching` 兜底，同短剧之家）。
- 点击任一行 → 本机直连（端口模式，同需求十二）→ 下载到 `<files>/adb-upgrades/`（`.part` 临时文件，下载进度节流 10% 回调）→ `manager.install`（CountingSource 流式安装进度）。

### 13.3 文件改动与锚点

- 新建 `app/src/main/java/app/orchard/tv/adb/AdbDramaUpgrade.kt`：
  - `fetchLatestAssets()` / `fetchHgAssets()` 均走私有 `fetchAssets(repo, label)`——`api.github.com/repos/{repo}/releases/latest`（短剧仓库 `jackherex/duanjuzhijia-down-tv`，HGTV 仓库 `pan8664716/HGTV`）；UA `"Aerial/1.77"`；`Accept: application/vnd.github+json`；connect 15s / read 30s；过滤 `endsWith(".apk")`。
  - `downloadApk(appContext, asset, onProgress): AdbOperationResult<File>`——落 `<files>/adb-upgrades/`，`.part` 临时文件，完成后 rename；空下载报错并清理 `.part`。
  - **踩坑记录（勿复犯）**：`operationCatching { ... }` 块内必须**直接返回 `AdbOperationResult.Success(...)`**；返回裸值（`List`/`File`）会与 `AdbOperationResult.Failure` 求 LUB 回退 `Any`，编译报 `Return type mismatch: expected 'AdbOperationResult<X>', actual 'Any'`。
- `ui/control/ControlCenter.kt`（AdbPage）：UpgradeList 步骤（fetch → 列表行 → 直连 → 下载进度 → 安装进度）；进度节流变量用 Int（勿 Long 混比）。Drama 列表最下方 `MenuRow(Glyph.Download, sHgUpgrade, onClick = { startHgUpgrade() })`；`startHgUpgrade()`：单文件 `running=false` 后直连 `runUpgrade`，多文件置 `assets` 进 List。

### 13.4 验收清单与已知限制（真机）

- [ ] 列表按 64位/32位/原名显示正确。
- [ ] 真机 35（2026-11-01 截图）：点击后报 **"HTTP 403 from GitHub"**——**当前网络无法直连 GitHub API**（本机同网段 curl SSL 握手失败 exit 35；常见加速镜像实测不可用：ghproxy.cn 域名已停售返回 for-sale 页、gh.ddlc.top 429、ghproxy.com/gitmirror 系不通）。**结论：网络限制而非代码缺陷**；代码无需改动，网络允许直连后即可工作。
- [ ] 下载完成 → ADB 安装成功（**未验证**，依赖网络可达 GitHub；网络通后按此验收）。

### 13.5 状态

功能已实现并随 1.77 / 99 交付；真机验证确认网络无法访问 GitHub API（403），下载/安装链路待网络允许后验收。

---

## 需求十四：ADB 连接提前（进入「ADB 管理」页自动发起本机连接）

### 14.1 用户原话要点

"1、将'短剧之家升级'、'ADB提权增强'两处的Adb连接操作提前到点击'ADB管理'就自动进行"。

### 14.2 行为定义

- 进入 ADB 管理子页面（`CcPage.Adb`）**立即**自动发起本机直连（`connectLocal()`），无需等点击任一功能；首次电视弹 RSA「允许」框，点允许即连上。
- 页面返回行下方**常驻一行连接状态**：连接中 / 已连接 endpoint / 失败原因，全程可见。
- 三个功能（短剧之家升级、Aerial 升级安装、ADB 提权增强）执行前统一走 `ensureConnected()`：**已有活跃会话（`manager.currentEndpoint() != null`）直接复用，不再重复 `connectLocal()`**——`AdbManager.connect` 在 `sessionMutex` 内会先 `disconnectLocked()` 再重建会话，重复调用会重置会话并让电视重复弹授权框。
- 自动连接失败时功能仍可触发，执行时兜底重连（`ensureConnected` 内再 `connectLocal()`）。

### 14.3 文件改动与锚点

- `app/src/main/java/app/orchard/tv/ui/control/ControlCenter.kt`（`AdbPage`，约 L603-L982）：
  - 新增状态 `var connLine: String?`；UI 在返回行（`adb_panel_title`）与功能行之间展示（`Type.Caption` / `Palette.SecondaryLabel`，最多 2 行）。
  - 新增 `LaunchedEffect(Unit)`（focus effect 之后）：`AdbService.manager.currentEndpoint()` 非空 → `connLine = 已连接(endpoint)`；否则 `connLine = 连接中` → `local.connectLocal()` → Success/Failure 更新 `connLine`。
  - 新增 `suspend fun ensureConnected(onLog: (String) -> Unit): Boolean`：复用会话或兜底 `connectLocal()`；失败时写 `failureLine` 并返回 false。
  - `startPrivilege()` / `runUpgrade()` / `runAerial()` 开头删除各自的 `sConnecting + sAuthorize + connectLocal` 段，改为 `if (!ensureConnected(::append)) { phase = AdbPhase.Failed; return@launch }`。

### 14.4 字符串资源（1 个 key × 5 语言）

| key | 用途 | 占位符 |
|-----|------|--------|
| `adb_connected_format` | 已连接状态行 | `%1$s`（endpoint） |

### 14.5 迁移步骤清单

1. `AdbPage` 加 `connLine` 状态 + 展示行。
2. 加 `LaunchedEffect(Unit)` 自动连接（复用现有会话则跳过）。
3. 加 `ensureConnected()`（会话复用 + 兜底重连）。
4. 三个功能入口（提权 / 短剧升级 / Aerial 升级）改走 `ensureConnected`。
5. 五份 `strings.xml` 加 `adb_connected_format`。

### 14.6 验收清单（真机）

- [ ] 进入「ADB 管理」页即弹电视 RSA 授权框（首次）/ 直接显示「已连接」且不再弹框（已授权过的设备）。
- [ ] 点任一功能不再重复触发授权框（会话复用）。
- [ ] 自动连接失败时（如电视未开无线调试）页面显示失败原因，点功能仍会重试连接。

### 14.7 状态

已实现（2026-11-02），随 1.77 / 99 构建验证中。

---

## 需求十五：Aerial 升级安装（NAS 定制版双通道 + GitHub 官方版 → 选文件 → 下载 → ADB 安装）

### 15.1 用户原话要点

"2、'D:\DobaoDebg\skyadb\Aerial安装功能开发说明.md'根据此说明文档，在'短剧之家升级'下方增加一个'Aerial升级安装'的功能"。

### 15.2 行为定义

- 「短剧之家升级」行**下方**新增「Aerial 升级安装」行（`Glyph.Info`）。
- 点击 → 来源选择页：**NAS定制版** / **GitHub官方版** + 返回行。
- **NAS定制版 = 双通道回退**：① File Browser 外网分享 `http://www.890720.xyz:18907/share/XvVjA_os`（免认证，`GET /api/public/share/{hash}` 列文件 → `GET /api/public/dl/{hash}/{encodedPath}` 直下）；失败或列表为空 → ② 回退飞牛内网 NAS `http://192.168.5.218:5666/s/41412d18807c4e8499`。
- **GitHub官方版**：`api.github.com/repos/pengchujin/aerial-tv/releases/latest`（与短剧之家仓库不同实体），过滤 `.apk` 资产。
- **GitHub 官方版确认框（用户后加要求）**：点击「GitHub官方版」**先弹确认框**，正文「如您当前安装的是定制版，升级官方版会因为签名不一致问题而无法覆盖安装，需要先卸载定制版后再安装官方版，官方版不具备定制版相关功能，请知悉！」+「确认升级 / 取消」两按钮；**确认后**才进入 fetch → 下载 → 安装；**取消则直接关闭，不进行任何操作**（停留来源选择页）。
- **GitHub 官方版卸载先行（用户后加要求）**：确认升级后，安装前**必须先通过 ADB 卸载当前安装的 Aerial（`app.orchard.tv`）**，否则签名不一致导致覆盖安装失败。由于卸载对象是本应用自身（卸载即杀本进程、删 `files/` 目录），流水线必须为：取列表 → 下载 APK（`<files>/aerial-upgrades/`，进程存活时完成）→ **`push` 到 `/data/local/tmp/aerial-upgrade.apk`**（卸载后仍存在）→ **先调度后卸载**：一条 shell 命令内先以 `setsid` 脱离会话后台调度安装任务（轮询等包数据目录 `/data/data/app.orchard.tv` 消失即卸载完成，最多 30s，随后 `pm install -r /data/local/tmp/aerial-upgrade.apk`），再执行 `pm uninstall app.orchard.tv`。**实测教训：`pm uninstall … && pm install …` 链会在卸载杀掉本进程、adb socket 断开、adbd 终止 shell 后中断，安装永远不执行**（TV 35 实测：卸载成功、官方版未装）；必须让安装进程脱离 adb 会话（setsid 独立进程组）。NAS 定制版签名一致，维持直接覆盖安装、**不卸载**。
- 列表行 = 按钮 label 即文件名（无自定义文案时直接显示文件名），**不显示文件名 trailing**（与短剧之家一致，2026-11-02 用户要求：不多显示一遍文件名，避免覆盖按钮名）；点击 → 复用已连接会话（`ensureConnected`）→ 下载到 `<files>/aerial-upgrades/`（`.part`+rename，进度节流 10%）→ `manager.install` 流式安装 → 日志区反馈。

### 15.3 文件改动与锚点

- **新建** `app/src/main/java/app/orchard/tv/adb/AerialUpgrade.kt`（`object AerialUpgrade`，纯 HttpURLConnection 重写，工程无 OkHttp 依赖）：
  - `data class AerialFile(name, url, cookie=null)`；`enum class AerialSource { Nas, Github }`。
  - `fetch(source): AdbOperationResult<List<AerialFile>>`；`download(appContext, file, onProgress): AdbOperationResult<File>`（与 `AdbDramaUpgrade.downloadApk` 同模式，支持 Cookie 头）。
  - 飞牛协议（与 skyadb `FnosShareClient.kt` **逐行比对一致**）：GET 分享页 HTML → 正则 `<script[^>]*id=["']share-data["'][^>]*>(.*?)</script>` → `JSONObject(...).optJSONObject("data")?.optString("token")`；`cookie = "$shareId=$token"`；POST `/s/$shareId/api/v1/share/list`（body `{shareId,path,fileId?}`，header `Content-Type: application/json` / `Auth: token` / `authx` / `Cookie`）递归收集（深度 ≤3，`entry.optString("file")`、`optInt("isDir")==1` 递归、`optLong("fileId",-1L)`）；逐个 POST `/s/$shareId/api/v1/share/download`（body `{files:[{path,fileId}],shareId,downloadFilename}`）换真实地址 `data.path` → `origin+path`；`authx = md5("${SIGN_SECRET}_${apiPath}_${nonce}_${timestamp}_${md5(body)}_$API_KEY")` 返回 `nonce=&timestamp=&sign=`；`nonce=(Random().nextInt(900_000)+100_000)`；常量 `SIGN_SECRET="NDzZTVxnRKP8Z0jXg1VAMonaG8akvh"`、`API_KEY="814&d6470861a4cfbbb4fe2fd3f\$6581f6"`（**`\$` 转义必须保留**）；响应 `code==0` 才算成功。
  - FileBrowser 协议：`origin=shareUrl.substringBefore("/share/")`、`hash=substringAfterLast("/share/")`；`items[]` 过滤 `isDir==false && name.endsWith(".apk")`；下载地址 `origin/api/public/dl/{hash}/{path 逐段 URLEncoder("UTF-8") 且 "+"→"%20"}`。
- `app/src/main/java/app/orchard/tv/adb/AdbManager.kt`：新增 `push(localFile, remotePath, onProgress)`（照 skyadb `KadbManager.push`：`CountingSource` 包装 + `kadb.push(source, remotePath, mode=420, lastModified)`），供 GitHub 官方版安装前把 APK 推到卸载后仍存在的 `/data/local/tmp/`。
- `app/src/main/res/xml/network_security_config.xml`：新增 `domain-config cleartextTrafficPermitted="true"` 放行 `192.168.5.218`（飞牛内网 NAS）与 `www.890720.xyz`（FileBrowser 外网分享）——NAS 两通道均为明文 HTTP，Android 9+ 默认拒绝 cleartext（真机报「Cleartext HTTP traffic to 192.168.5.218 not permitted」）。若未来 NAS 换 IP/域名须同步加白名单。
- `ui/control/ControlCenter.kt`（`AdbPage`）：
  - `AdbPhase` 加 `AerialSource`；新增 `enum class AdbMode { Privilege, Drama, AerialNas, AerialGithub }` **替代 `upgradeMode: Boolean`**（`titleRes` 按 `mode` 分支，`AerialNas/AerialGithub` 合并用同一组 Aerial 文案）。
  - 新增 `startAerial()`（→ AerialSource 页）、`fetchAerial(source)`（fetch → 列表，按 source 置 `AdbMode.AerialNas/AerialGithub`）、`runAerial(file)`（ensureConnected → 下载 → 安装，进度节流 Int）。
  - **`runAerial` 分支**：`mode == AdbMode.AerialGithub` 时下载完成后走「push → 卸载+安装」链（见 §15.2）；否则走原 `manager.install` 流式安装。
  - UI `when(phase)`：`AerialSource` 分支（NAS 行 `Glyph.Folder` / GitHub 行 `Glyph.Globe` / 返回行）；`List` 分支按 `mode` 区分 Drama（arm 文案）与 Aerial（文件名）行；默认分支在短剧之家行下方插入 Aerial 行。
  - **GitHub 确认框**：新增状态 `confirmGithub: Boolean`；GitHub 行 `onClick = { confirmGithub = true }`；`AdbPage` 外包 `Box`，`confirmGithub` 时叠加 `GithubConfirmSheet`（居中玻璃面板 `liquidGlass(28.dp)`、`dismissOnTap` + `BackHandler` 取消、`ConfirmAction` 全宽文字按钮 ×2，「确认升级」默认焦点）；确认 → `fetchAerial(AerialSource.Github)`，取消 → 仅关闭。
  - 日志区标题对 `AdbMode.Aerial` 用 `adb_aerial_done` / `adb_aerial_fetching`。

### 15.4 字符串资源（7 个 key × 5 语言）

| key | 用途 | 占位符 |
|-----|------|--------|
| `adb_aerial_upgrade` | 「Aerial升级安装」行 |
| `adb_aerial_source_nas` | 来源「NAS定制版」 |
| `adb_aerial_source_github` | 来源「GitHub官方版」 |
| `adb_aerial_fetching` | 拉取中 |
| `adb_aerial_no_assets` | 该来源无 Aerial APK |
| `adb_aerial_done` | 安装完成 |
| `adb_connected_format`（需求十四） | 已连接状态行 | `%1$s` |
| `adb_aerial_confirm_body` | GitHub 官方版确认框正文 |
| `adb_aerial_confirm_ok` / `adb_aerial_confirm_cancel` | 确认升级 / 取消 |
| `adb_aerial_pushing` / `adb_aerial_uninstalling` | 推送安装包中 / 卸载旧版中（GitHub 官方版流程） |

### 15.5 迁移步骤清单

1. 新建 `adb/AerialUpgrade.kt`（三来源 + 下载，常量与协议见 §15.3）。
2. `AdbPage`：`AdbPhase`/`AdbMode` 改造，`startAerial`/`fetchAerial`/`runAerial`，`when(phase)` 三分支。
3. 五份 `strings.xml` 加 7 个 key（`adb_connected_format` 与需求十四共用，勿重复添加）。
4. 编译核对：`when(phase)` / `when(mode)` / `when(titleRes)` 穷尽无遗漏。

### 15.6 验收清单与已知限制（真机）

- [ ] 「短剧之家升级」下方出现「Aerial 升级安装」，点击出现两来源选择。
- [ ] NAS定制版：外网 FileBrowser 可达时直接列文件；不可达时回退内网飞牛 NAS（192.168.5.218 需与电视同网段）列文件。
- [ ] 列表行=文件名；点击 → 复用已连接会话 → 下载（`<files>/aerial-upgrades/`）→ ADB 安装 → 日志反馈。
- [ ] 点「GitHub官方版」**先弹确认框**；确认后继续获取列表，取消则停留来源页不做任何操作。
- [ ] NAS 双通道获取不报「Cleartext HTTP traffic … not permitted」（依赖 network_security_config 白名单，见 §15.3）。
- [ ] GitHub 官方版确认后：下载 → 推送 `/data/local/tmp/aerial-upgrade.apk` → 自动卸载旧版（`app.orchard.tv`）→ 安装官方版；卸载后本应用进程退出属预期（adbd 继续执行安装）。
- [ ] GitHub官方版同样受需求十三网络限制（api.github.com 403 / SSL 握手失败实测），网络允许直连后验收。

### 15.7 状态

已实现（2026-11-02），随 1.77 / 99 构建验证中。

---

## 需求十六：HGTV 升级入口移至 ADB 管理主页（与「短剧之家升级」同级）

### 16.1 用户原话要点

"将'HGTV升级'移动到与'短剧之家升级'同一级，放在'短剧之家升级'的下一行，功能完全不变；修改内容按照规范记录到 'docs/REQUIRED-MODIFICATIONS.md'"。

### 16.2 行为定义

- **改动前**：HGTV 升级入口在「短剧之家升级」的 APK 列表页（`AdbPhase.List` / `AdbMode.Drama`）**最下方**（即需求十三 §13.2/§13.3 所述位置）——需先点「短剧之家升级」进入其列表才能看到。
- **改动后**：ADB 管理主页（`AdbPhase.Idle`）在「短剧之家升级」行**正下方**新增同级「HGTV升级」行（`Glyph.Download`，`adb_hgtv_upgrade`，5 语言已有）；「短剧之家升级」的 APK 列表页底部不再出现 HGTV 行。
- **功能完全不变**：点击「HGTV升级」仍走 `startHgUpgrade()` → `AdbDramaUpgrade.fetchHgAssets()` 拉 `pan8664716/HGTV` 的 `releases/latest`：仅 1 个 APK → `running=false` 后直接 `runUpgrade()` 下载并 ADB 安装；多个 → 置 `assets` 进 `AdbPhase.List`（mode 仍为 `Drama`，复用同一渲染分支与返回行）；空列表/网络失败 → 失败日志（`operationCatching` 兜底）。短剧之家升级链路（`startUpgrade()` / `runUpgrade()`）不受影响。

### 16.3 文件改动与锚点

- `app/src/main/java/app/orchard/tv/ui/control/ControlCenter.kt`（`AdbPage`，约 L996-L1030）：
  - `AdbPhase.List` / `AdbMode.Drama` 分支：删除原列表底部的 `Spacer(Modifier.height(8.dp))` + `MenuRow(Glyph.Download, sHgUpgrade, onClick = { startHgUpgrade() })`。
  - `else`（Idle）分支：「短剧之家升级」行（`adb_drama_upgrade`）之后新增：

```kotlin
Spacer(Modifier.height(8.dp))
MenuRow(Glyph.Download, sHgUpgrade, onClick = { startHgUpgrade() })
```

- 无新增字符串（`adb_hgtv_upgrade` 五语言已存在）；无后端改动（`AdbDramaUpgrade` 不动）。

### 16.4 验收清单（真机）

- [ ] ADB 管理主页：「短剧之家升级」下一行出现「HGTV升级」，点击行为与改动前一致（单 APK 直装 / 多 APK 列表 / 失败日志）。
- [ ] 「短剧之家升级」APK 列表页底部不再有「HGTV升级」行。
- [ ] 短剧之家升级链路不受影响。

### 16.5 状态

已实现（2026-11-02），随 1.77 / 99 构建验证中。

---

## 需求十七：短剧之家升级改为自动按电视架构选择安装包（取消手动列表选择）

### 17.1 用户原话要点

"短剧之家升级功能做如下调整：将原有的下载列表需要用户选择对应文件来下载的功能取消，改为程序自动判断当前电视的是32位还是64位架构，当是64位架构时自动下载文件名包含arm64-v8a.apk的文件来执行后续的操作，当是32位架构时自动下载文件名包含armeabi-v7a.apk的文件来执行后续的操作；修改完毕后将改动添加到之前指定的说明文档中，然后等待构建指令"。

### 17.2 行为定义

- **改动前**（需求十三 §13.2）：点击「短剧之家升级」→ `fetchLatestAssets()` 拉取 `jackherex/duanjuzhijia-down-tv` 最新 release 的 APK 列表 → 进入 `AdbPhase.List` 子页面，逐行列出 APK 让用户手动选择（arm64-v8a 标"64位"，armeabi-v7a 标"32位"）→ 点击某行后才 `runUpgrade(asset)` 下载安装。
- **改动后**：取消列表选择环节。点击「短剧之家升级」→ 仍 `fetchLatestAssets()` 拉取列表 → 程序按本机 ABI 自动挑选：
  - 本机支持 `arm64-v8a`（`Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }`，即 64 位架构）→ 自动选中文件名包含 `arm64-v8a`（`ApkAsset.isArm64`）的 APK；
  - 否则（32 位架构，仅 `armeabi-v7a`）→ 自动选中文件名包含 `armeabi-v7a`（`ApkAsset.isArmV7`）的 APK。
  - 选中后直接走 `runUpgrade(asset)`：连接本机 ADB（端口模式）→ 下载 → ADB 安装，全程无用户选择环节。
- **无匹配时的行为**：列表为空 → 原 `adb_upgrade_no_assets` 上屏；列表非空但没有对应本机架构的文件 → 上屏新字符串 `adb_upgrade_no_abi_match`（"最新发布中没有适配本电视架构（%1$s）的 APK"，%1$s = `arm64-v8a` / `armeabi-v7a`），`phase = Failed`，不进入列表、不崩溃。
- **HGTV 升级不受影响**：`startHgUpgrade()` 保持原行为（单 APK 直装 / 多 APK 列表选择），复用同一 `AdbPhase.List` 渲染分支。

### 17.3 文件改动与锚点

- `app/src/main/java/app/orchard/tv/ui/control/ControlCenter.kt`（`AdbPage`）：
  - `startUpgrade()`（约 L822 起，位于 `runUpgrade()` 之后）：删除原 `assets = fetched.data; running = false; phase = AdbPhase.List` 分支；改为 ABI 判定 + `firstOrNull` 挑选 + `running = false; runUpgrade(asset)` 直装；无匹配时 `append(context.getString(R.string.adb_upgrade_no_abi_match, abiMarker)); phase = AdbPhase.Failed`；函数 KDoc 同步更新。**注意：`startUpgrade`/`runUpgrade` 是 `AdbPage` 内的局部函数，Kotlin 不支持前向引用，调用 `runUpgrade` 的 `startUpgrade` 必须声明在其之后（2026-11-02 构建踩坑：声明在 `runUpgrade` 前报 `Unresolved reference 'runUpgrade'`，已调整顺序）。**
  - `AdbPhase.List` / `AdbMode.Drama` 渲染分支与 `assets` 状态**保留**（HGTV 多 APK 列表仍使用）。
- `app/src/main/res/values*/strings.xml`（5 语言）新增 `adb_upgrade_no_abi_match`（含 `%1$s` 占位符 = ABI 标记 `arm64-v8a` / `armeabi-v7a`）：
  - values："The latest release has no APK for this TV\'s architecture (%1$s)"
  - values-zh："最新发布中没有适配本电视架构（%1$s）的 APK"
  - values-ru / values-uk / values-kk：对应翻译（见各文件）。
- 无后端改动：`AdbDramaUpgrade`（`fetchLatestAssets` / `downloadApk` / `ApkAsset.isArm64` / `isArmV7`）不动。

### 17.4 验收清单（真机）

- [ ] 64 位电视（如 192.168.31.94，Amlogic s7d）：点击「短剧之家升级」不再出现文件列表，自动下载文件名含 `arm64-v8a` 的 APK 并完成安装。
- [ ] 32 位电视（如适用）：自动下载文件名含 `armeabi-v7a` 的 APK。
- [ ] 发布中无匹配架构文件时：上屏 `adb_upgrade_no_abi_match`（含对应 ABI 标记），不进入列表、不崩溃。
- [ ] 「HGTV升级」多 APK 列表选择功能不受影响。

### 17.5 状态

已实现（2026-11-02，代码 + 5 语言字符串 + 文档），**尚未构建**——等待用户构建指令（偏好 p1_AQEC4S8b6B0），随下次 1.77/99 构建验证中。

---

## 需求十八：隐藏设置页「亮度调节」行（个性化上方）

### 18.1 用户原话要点

"设置中的个性化上方的亮度调节项目隐藏掉不需要他显示了"。

### 18.2 行为定义

- **改动前**：控制中心设置主页在「个性化」（`cc_personalize`）行的正上方渲染 `BrightnessRow(vm)`（亮度调节：本机能调时显示滑杆，不能调时显示跳转电视画质设置的行）。
- **改动后**：该行不再渲染（隐藏）。「个性化」上方的亮度调节项目消失；音量行（`VolumeRow`）之后直接进入「个性化」等菜单行，间距与原来一致（原两个 6dp spacer 合并保留一个）。
- `BrightnessRow` 私有函数及其全部逻辑（`SystemProbe.xgimiBrightnessLevel`、`vm.refreshBrightness()`、`vm.adjustBrightness`、`Brightness.adjust` 步进、`cc_brightness`/`cc_brightness_in_settings` 字符串）**保留未删除**——仅移除调用点，便于以后恢复；无字符串改动。

### 18.3 文件改动与锚点

- `app/src/main/java/app/orchard/tv/ui/control/ControlCenter.kt`：
  - 设置主页（约 L312-L317）：删除 `Spacer(Modifier.height(6.dp))` + `BrightnessRow(vm)` 两行（即亮度调节行），保留其后到「个性化」的间距；新增中文注释说明隐藏原因（需求十八）。
  - `BrightnessRow`（约 L1256 起）及 `AdjustRow`/`MenuRow` 调用链**不动**。

### 18.4 验收清单（真机）

- [ ] 设置主页：音量下方不再出现「亮度调节」行，「个性化」直接位于音量之后。
- [ ] 无崩溃、无编译错误（`BrightnessRow` 未使用仅为无害 warning）。

### 18.5 状态

已实现（2026-11-02，代码改动），**尚未构建**——等待用户构建指令（偏好 p1_AQEC4S8b6B0）。

---

## 需求十九：隐藏设置页「音量」行（紧随需求十八）

### 19.1 用户原话要点

"音量设置项目也隐藏"。

### 19.2 行为定义

- **改动前**：控制中心设置主页顶部渲染 `VolumeRow(vm, cameFrom)`（音量调节行，位于亮度调节行上方、网络/设置按钮行下方）。
- **改动后**：音量行不再渲染（隐藏）。设置主页从网络/设置按钮行直接进入「个性化」等菜单行，顶部 12dp 间距保留。
- `VolumeRow` 私有函数及 `AdjustRow` 共享逻辑（音量滑杆、`vm.adjustVolume` 等）**保留未删除**——仅移除调用点，便于以后恢复；无字符串改动。`cameFrom` 若仅被音量行使用则一并失去唯一调用者（保留声明，无害）。

### 19.3 文件改动与锚点

- `app/src/main/java/app/orchard/tv/ui/control/ControlCenter.kt`（设置主页，约 L312-L316）：删除 `VolumeRow(vm, cameFrom)` 与其后 `Spacer(Modifier.height(6.dp))` 两行；新增中文注释说明隐藏原因（需求十九）。`VolumeRow`（定义处）及 `AdjustRow` 调用链**不动**。

### 19.4 验收清单（真机）

- [ ] 设置主页：音量、亮度两行均不再显示，网络/设置按钮行之后直接是「个性化」。
- [ ] 无崩溃、无编译错误（`VolumeRow`/`BrightnessRow` 未使用仅为无害 warning）。

### 19.5 状态

已实现（2026-11-02，代码改动），**尚未构建**——等待用户构建指令（偏好 p1_AQEC4S8b6B0）。

---

## 需求二十：代码审查发现问题的修复（P1-P4）

### 20.1 来源

2026-11-02 对 ADB/控制中心子系统 + 近期改动 + 构建配置的代码审查（审查范围：`ControlCenter.kt`、`HomeViewModel.kt`、`adb/` 包、构建配置、5 语言字符串）发现 4 个问题，用户指示「4个问题全部修复」。本需求记录修复内容，属**缺陷修复**（非新功能），不改变任何既有行为入口。

### 20.2 P1（功能，中）：ADB 页 `running` 标志在 Done/Failed 终态不复位

- **问题**：`AdbPage` 的 `running` 只在进入列表/下一步前释放（`startUpgrade`→`runUpgrade`、`startHgUpgrade` 单/多 APK、`startAerial` 置 `AerialSource`、`fetchAerial` 进列表），**所有 `phase = AdbPhase.Done` / `AdbPhase.Failed` 终态均不复位**。一次失败（如 GitHub 403）或一次成功后，页面按钮全部被 `if (running) return` 挡住，必须退出 ADB 页重进才能重试。
- **修复**：在全部 20 个终态赋值（`Failed`×16 + `Done`×4，覆盖 `startPrivilege`/`runUpgrade`/`startUpgrade`/`startHgUpgrade`/`fetchAerial`/`runAerial`）的下一行补 `running = false`。运行中检查 `if (running) return` 与 `phase = AdbPhase.Working` 语义保持不变。

### 20.3 P2（功能，低）：广播通道 `EXTRA_KEYS` 缺 `keep_adb`

- **问题**：`AdbCommandReceiver.kt` companion `EXTRA_KEYS`（host/port/code/name/command/file/pkg）缺 `keep_adb`，外部 `am broadcast` 传 `--es keep_adb false` 被丢弃，privilege 永远默认 keep-adb=true（app 内 `startPrivilege` 固定传 true，故无实际影响）。
- **修复**：`EXTRA_KEYS` 增加 `"keep_adb"`。`AdbService.runOp` "privilege" 分支已有 `params["keep_adb"]?.let { it == "true" || it == "1" } ?: true` 解析，无需改动。

### 20.4 P3（逻辑，低）：页面进入自动连接与操作时 `ensureConnected` 可能并发二次连接

- **问题**：页面进入 `LaunchedEffect` 自动发起 `connectLocal()`（20s 超时），用户随即点操作时 `currentEndpoint()` 为空，`ensureConnected` 再发起一次；`AdbManager.connect` 互斥串行（先 disconnect 再连），电视可能重复弹「允许 USB 调试」授权框。
- **修复**：新增共享连接任务 `connectJob`（`remember { mutableStateOf<Job?>(null) }`）与 `connectOnce(onLog)` 局部函数——页面进入启动它；`ensureConnected` 在无现成端点时先 `connectJob.join()` 等待页面进入的那次连接，成功即复用，失败（或不在飞行中）才发起新连接。`AdbOperationResult.Failure` 仍上屏 `failureLine`。

### 20.5 P4（功能，低）：`runAerial` GitHub 分支 setsid 后台安装无结果回读

- **问题**：GitHub 源升级 `(setsid sh -c '…; pm install -r …' &) ; pm uninstall` 单链把安装交给后台进程，页面直接置 `Done`，install 失败时用户误以为已装好（需求十三 §15.2/补充说明第 12 条排错三已记录此设计取舍：自杀式卸载后必须 setsid 独立进程）。
- **修复**：
  1. setsid 后台安装输出重定向落盘：`pm install -r $remote >/data/local/tmp/aerial-upgrade.result 2>&1`；
  2. 置 `Done` 前追加日志提示「GitHub install is running in background; its result is shown when the ADB page reopens.」（技术性日志，非界面文案）；
  3. 新增 `reportPendingGithubInstall()`：下次进入 ADB 页（已有连接或连接成功后）`cat` 该结果文件，非空则追加 `last GitHub install: <pm install 输出>` 到日志并 `rm` 清理。

### 20.6 文件改动与锚点

- `app/src/main/java/app/orchard/tv/ui/control/ControlCenter.kt`（`AdbPage`）：
  - 全部 20 个终态赋值下一行补 `running = false`（P1）。
  - `connectJob` 状态声明 + `reportPendingGithubInstall()`（新增，位于 `connectOnce` 之前）+ `connectOnce(onLog)`（新增）+ `ensureConnected` 改写为「复用页面进入连接、等待 join、失败才重连」+ 页面进入 `LaunchedEffect` 改调 `connectOnce()`（P3/P4）。
  - `runAerial` GitHub 分支：setsid 命令加 `>/data/local/tmp/aerial-upgrade.result 2>&1`；`phase = Done` 前追加后台安装提示日志（P4）。
  - import 新增 `kotlinx.coroutines.Job`。
- `app/src/main/java/app/orchard/tv/adb/AdbCommandReceiver.kt`：`EXTRA_KEYS` 增加 `"keep_adb"`（P2）。
- 无字符串改动（技术性日志为英文，不进 strings.xml；如后续本地化再补 5 语言）。

### 20.7 验收清单（真机，待下次构建验证）

- [ ] P1：ADB 页任一次升级/提权失败或成功后，无需退出页面即可再次点击操作（`running` 已复位）。
- [ ] P2：`adb shell am broadcast -a app.orchard.tv.ADB --es command privilege --es keep_adb false`（host/port/code 正确时）提权后不再保持 adb 服务（与 `keep_adb=true` 行为不同）。
- [ ] P3：冷启动进入 ADB 页后立即点击「短剧之家升级」，电视只弹一次「允许 USB 调试」。
- [ ] P4：GitHub 源升级完成后重新进入 ADB 页，日志显示 `last GitHub install: Success` 或实际失败输出；结果文件被清理。

### 20.8 状态

已实现（2026-11-02，代码改动），**尚未构建**——等待用户构建指令（偏好 p1_AQEC4S8b6B0），随下次构建验证中。

---

## 补充说明（新增需求与既有内容的关系，避免歧义）

1. 需求一~三（含附录 A/B、通用红线）为**基线**，原文未改动；需求四~二十为基线之后新增的全部需求修改（其中需求二十为审查问题的缺陷修复）。
2. 需求四（网页端备份还原）与需求五（TV 端备份与还原）**共用同一 `BackupStore` 核心**（序列化/恢复逻辑一致），迁移时两者必须一起携带。
3. 需求五的「还原列表读不到文件」修复引入了新权限 `MANAGE_EXTERNAL_STORAGE`（API 30+）与 BackupPage 的授权引导逻辑，迁移时 Manifest 与 ControlCenter 必须一并携带；Android 10（API 29）仍走 MediaStore，Android 5-9 仍走文件系统 + WRITE_EXTERNAL_STORAGE。
4. 需求五的备份时间戳到秒、最多保留 2 份、目录自动创建、卸载不删，均为用户明确的最终行为，后续需求不得回退。
5. 版本演进：需求一~三基线为 1.74/94；需求四~八当前实现为 1.76/97（固化签名）；需求九实现于 1.77/99（固化签名）；需求十~十三同样实现于 1.77/99（固化签名）；需求十四~十五同样实现于 1.77/99（固化签名）。
6. **需求十~十三为「ADB 功能族」，必须全部一起携带**：需求十（连接能力）是需求十一（提权后端）、十二（ADB 管理 UI）、十三（短剧之家升级）的**共同底层**——两处 UI 功能均经 `AdbService.manager` / `AdbLocalConnect.connectLocal()` 调用移植层，缺需求十则十一~十三全部空转。skyadb 参考实现位于 `D:\DobaoDebg\skyadb\app\src\main\java\com\sky22333\skyadb\adb\`（KadbManager.kt 等），Aerial 移植版在 `app/src/main/java/app/orchard/tv/adb/`。
7. **弹窗版 `AdbPanel.kt` 已废弃删除，迁移时勿恢复**；ADB 管理一律为控制中心子页面模式（`CcPage.Adb`，仿 BackupPage），Overlay 中不存在 `Adb`。
8. ADB 依赖锁定（`kadb:2.1.1` + jitpack + `overrideLibrary` + proguard keep，见需求十 §10.3）与需求三（阿里云镜像优先）、需求八（IDE 构建环境、产物归档 `apk\` 文件夹）配合生效；新版 Aerial 若升级 Kotlin 版本，需重新评估 kadb 可用版本（2.1.2+ 为 Kotlin 2.4 编译）。
9. **需求十四（连接提前）是需求十二 AdbPage 的连接时机改造**，复用需求十的 `AdbLocalConnect` / `AdbService.manager`，必须与 ADB 功能族一起携带；**需求十五（Aerial 升级安装）是独立来源客户端**（协议源自 `D:\DobaoDebg\skyadb\Aerial安装功能开发说明.md` 与 skyadb `download/` 包，三来源 URL/签名常量见 §15.3），它复用需求十连接层与需求十三的下载安装模式，但**不引入任何新依赖**（OkHttp 已用 HttpURLConnection 重写）。
10. **需求十六（HGTV 入口位置调整）覆盖需求十三 §13.2/§13.3 中 HGTV 行的位置描述**：HGTV 升级入口从「短剧之家升级」列表页最下方移到 ADB 管理主页「短剧之家升级」下方（同级），功能与后端（`fetchHgAssets`/`startHgUpgrade`）不变；迁移时以需求十六的锚点为准，需求十三中该行位置描述不再适用。
11. **需求十七（短剧之家自动按架构选择）覆盖需求十三 §13.2 中短剧之家升级的"列表选择"行为描述**：短剧之家升级不再展示文件列表让用户选择，改为按本机 ABI 自动选择 `arm64-v8a`/`armeabi-v7a` 并直接下载安装（见需求十七 §17.2/§17.3）；需求十三中该描述不再适用。同时取代需求十六 §16.2 中"短剧之家升级链路不受影响"一句的时效（该句原指 HGTV 移动不改短剧之家；现短剧之家由需求十七另行调整）。HGTV 升级的多 APK 列表选择行为仍按需求十三/需求十六保留。
12. **本轮构建排错记录（2026-11-02，随 1.77/99 构建时实测）**：
    - **排错一：`values-kk/strings.xml` 某 `<string>` 缺 `</string>` 闭合** → `merge/packageStandardReleaseResources` 报「元素类型 "string" 必须由匹配的结束标记 `</string>` 终止」且行号指向文件末尾 `</resources>`（解析器失败位置，非真正出错行）。成因：哈萨克语（西里尔）字符串编辑时结尾标点后漏写闭合标签。修复：按行号往前查每个 `<string>` 是否成对闭合。**教训：非拉丁语系字符串每行必查闭合；构建失败行号 ≠ 错误行号。**
    - **排错二：`ControlCenter.kt` 需求十四的 `sConnectedFmt` 被误从"带参局部函数"改成"val 字符串"** → `compileStandardReleaseKotlin` 报 `Unresolved reference 'sConnectedFmt'`（调用处 `sConnectedFmt(endpoint)` 是函数调用）。修复：恢复为 `fun sConnectedFmt(endpoint: String) = context.getString(R.string.adb_connected_format, endpoint)`。**教训：`adb_connected_format` 含 `%1$s` 占位符，必须经 `context.getString` 传参格式化，不能 `stringResource` 直接取值后当函数调用；局部函数与局部 val 同名声明会互相遮蔽。**
    - **排错三（运行期，TV 35 实测）：GitHub 官方版「先卸载再安装」用 `pm uninstall app.orchard.tv && pm install -r /data/local/tmp/aerial-upgrade.apk` 一条链，卸载成功但官方版未装上**——根因：卸载本应用杀进程 → adb socket 断开 → adbd 终止 shell → `pm install` 未执行（非 APK 问题，手动 `pm install` 单独执行 Success）。修复：改为先 `setsid sh -c '轮询等 /data/data/app.orchard.tv 消失（≤30s）; pm install -r …' &` 后台调度安装（独立进程组，脱离 adb 会话），再执行 `pm uninstall`（详见 §15.2）。**教训：adb shell 命令的后续步骤会随 socket 断开被终止，自杀式卸载后必须把后续动作放进 setsid 独立进程。**
    - **排错四（运行期，TV 35 crash buffer 五次铁证）：点击「确认升级」后程序闪退、主屏权限掉**——crash 堆栈 `FATAL EXCEPTION: main / java.lang.IllegalStateException: HTTP 403 from GitHub / at E2.G.invokeSuspend(r8…:387)`，即 `AerialUpgrade.fetchGithub()` 内 `error("HTTP ${c.responseCode} from GitHub")` 抛异常后**无任何 `operationCatching`/try-catch 兜底**，协程异常冒泡到 main 崩溃（08:48–08:52 连续 5 次）。对照：同文件的 `download`/`fetchFileBrowser`/`fetchFnos` 与 `AdbDramaUpgrade` 全程有 `operationCatching`，403 只显示「执行失败」不崩——唯独 `fetchGithub` 裸奔。修复：`fetchGithub` 包 `operationCatching`（块内直接 `AdbOperationResult.Success(files)`，Failure 分支给 message/suggestion/cause），网络错误一律转 Failure 上屏。**主屏权限掉的逻辑链**：Aerial 本是默认 Home；官方版升级流程卸载 Aerial 后系统将默认主屏交回小米原生 `com.mitv.tvhome`，重装不回自动恢复；且 **MIUI 锁死 HOME role（`cmd role add-role-holder android.app.role.HOME --user 0 app.orchard.tv` 返回 Failed；`cmd package set-home-activity` Success 但 resolve 仍为 com.mitv.tvhome）**，非系统应用无法经 adb 恢复默认桌面，需用户在电视设置（默认应用/桌面）手动切回 Aerial。**教训：自卸载升级类功能会连带清默认 Home 授权，须提前告知用户恢复方式；R8 混淆后按消息文本（Kotlin 字面量）即可定位到 `AerialUpgrade.fetchGithub`。**
13. **电视停用应用清单（2026-11-02 快照，35 / 94；当日二次停用已并入）**：随 Aerial 部署过程中经 `adb` 停用的系统/预装应用，**供以后复用**（查已停用：`adb shell pm list packages -d`；停用：`adb shell pm disable-user --user 0 <pkg>`；启用：`adb shell pm enable <pkg>`）。**红线：`com.mitv.tvhome`（桌面）绝不停用；未知用途系统包先查询再动。**
    - **电视 35（`192.168.31.35`，小米 MiTV，Android 14）已停用（20）**：
      `com.xiaomi.mitv.karaoke.service`（K歌服务）、`com.xiaomi.aitranslate`（AI翻译）、`com.mitv.alarmcenter`（闹钟中心）、`com.miui.analytics`（小米分析）、`com.mitv.shoplugin`（商店插件）、`com.miui.tv.analytics`（电视分析）、`com.xiaomi.mitv.healthdiagnosis`（健康诊断）、`com.xiaomi.mitv.calendar`（日历）、`com.xiaomi.mitv.shop`（小米商城）、`com.xiaomi.mitv.tvpush.tvpushservice`（推送服务）、`com.xiaomi.screenrecorder`（录屏）、`com.xiaomi.mitv.health`（健康）、`com.xm.webcontent`（网页内容）、`com.xiaomi.gamecenter.sdk.service.mibox`（游戏中心）、`com.xiaomi.tv.gallery`（图库）、`com.xiaomi.mitv.appstore`（应用商店）、`com.xiaomi.tvqs`（短视频/内容流）、`com.mitv.cloudcontrol`（云控制）、`com.android.printspooler`（打印服务）、`com.xiaomi.tweather`（天气）。
    - **电视 94（`192.168.31.94`，小米，Android 14 / Amlogic 平台）已停用（17）**：
      `com.duokan.videodaily`（多看视频日报）、`com.mitv.shoplugin`（商店插件）、`com.miui.tv.analytics`（电视分析）、`com.xiaomi.mitv.healthdiagnosis`（健康诊断）、`com.xiaomi.mitv.calendar`（日历）、`com.xiaomi.mitv.shop`（小米商城）、`com.xiaomi.mitv.tvpush.tvpushservice`（推送服务）、`com.xiaomi.screenrecorder`（录屏）、`com.xm.webcontent`（网页内容）、`com.xiaomi.gamecenter.sdk.service.mibox`（游戏中心）、`com.xiaomi.tv.gallery`（图库）、`com.mitv.gallery`（多看图库）、`com.xiaomi.mitv.appstore`（应用商店）、`com.xiaomi.tvqs`（短视频/内容流）、`com.mitv.cloudcontrol`（云控制）、`com.android.printspooler`（打印服务）、`com.xiaomi.tweather`（天气）。
    - **当日新增停用（用户 2026-11-02 拍板）**：35 增 `com.xiaomi.screenrecorder`、`com.android.printspooler`、`com.xiaomi.tweather`、`com.xiaomi.mitv.calendar`、`com.xiaomi.mitv.appstore`；94 增 `com.xiaomi.tweather`、`com.xiaomi.mitv.calendar`、`com.xiaomi.mitv.appstore`（录屏/打印 94 已有）。
    - 两机共有停用交集（13）：shop/healthdiagnosis/tvpush/webcontent/gamecenter/tvgallery/tvqs/cloudcontrol/shoplugin/tv.analytics/tweather/calendar/appstore——迁移到新电视时可参照此集合。

14. **1.78/101 正式版构建排错记录（2026-11-02，随 1.78「审核→构建」流程实测）**：三处问题均为「以 1.77 基参照版（aerial-tv-adb）合入官方 1.78 时，官方 1.78 新增物或新增代码所需 import 被漏掉」同族迁移遗漏；修复后 `assembleStandardRelease` 构建成功（8m26s），APK 归档 `apk\app-standard-release.apk`，aapt2 / apksigner 验证通过。
    - **排错五（审查静态交叉检查定位，P0，编译必失败）：官方 1.78 新增的两个字符串 key 在 5 语言全部缺失**——`cc_update_notes`（ControlCenter 更新日志标题）与 `tile_xiaowei_call`（Apps 索尼「小微畅聊」磁贴名），代码有引用但 5 份 strings.xml 均无定义。成因：迁移以 1.77 基的 adb 版字符串为底合并 61 个需求 key，官方 1.78 新增 key 未随代码合入而丢失；1.76/1.77/adb 版代码均无这两处引用，参照比对发现不了。修复：用脚本交叉比对代码 `R.string` 引用与 5 语言定义集合（276 引用 × 5 语言）定位缺失，再从官方仓库 `https://raw.githubusercontent.com/pengchujin/aerial-tv/master/app/src/main/res/values{,zh,ru,uk,kk}/strings.xml` 取回 5 语言精确官方值补回（en "New in %s" / "Xiaowei Call"；zh "%s 更新内容" / "小微畅聊"；ru/uk/kk 为官方对应译文），插入位置与官方一致（`cc_update_title` 后、`tile_kids` 前）。**教训：跨版本合入 strings.xml 时仅与参照版比对看不出官方新增 key，必须用脚本按「代码 `R.string` 引用 × 各语言定义集合」交叉校验。**
    - **排错六（`compileStandardReleaseKotlin` 报错）：`AerialSettings.kt` `Unresolved reference 'SharedPreferences'`（readAll 参数类型 + 全部 get 调用共 26 处）**。成因：需求一 init 拆 readAll + reload 时新增函数签名 `readAll(prefs: SharedPreferences)`，而官方 1.78 该文件原本不直接使用 `SharedPreferences` 类型（无需 import）→ 合入时未补 import。修复：对照 adb 版补 `import android.content.SharedPreferences`。**教训：合入新增函数/代码若引用 android.content 类型，官方基线没有对应 import 时必须显式补上，Kotlin 不会自动解析包成员。**
    - **排错七（`compileStandardReleaseKotlin` 报错）：`ControlCenter.kt` `Unresolved reference 'Intent'`（BackupPage `openAllFilesSettings()` 构造 `Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, …)`，L607/609）**。成因：官方 1.78 该文件内 Intent 均写全限定 `android.content.Intent`（如 MainPage 参数），没有 `import android.content.Intent` → 需求五 BackupPage 整段合入时未补 import。修复：对照 adb 版补 `import android.content.Intent`。**教训：整段函数体逐字合入时，须同时核对段内引用的类型在本文件是否有 import 或全限定约定；缺则补 import（或改全限定），不能只看函数体逐字一致。**
