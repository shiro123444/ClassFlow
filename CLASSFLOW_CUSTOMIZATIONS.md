# ClassFlow 有意差异清单（对齐审计基准）

> 用途：每次与上游（XingHeYuZhuan/shiguangschedule）merge 后，运行审计命令，
> 对照本清单逐文件确认差异。**清单之外的差异 = 遗漏，需要修复或补录本清单。**

## 审计命令

```bash
# 上游基线（3eb39c2 为本项目当前同步点，merge 后更新为新的上游 HEAD）
git diff 3eb39c2 --stat -- app/src/main/java app/src/main/res | sort -t'|' -k2 -rn | head -50
```

检查要点：
1. 清单内文件：差异点是否仍只是清单所述内容（合并上游新功能时，上游侧的新改动是正常 merge 内容）
2. **清单外文件出现差异 = 遗漏**，必须处理
3. 独有文件（上游不存在）不参与 diff，无需处理

## 零差异文件（已对齐上游，merge 应自动合并，禁止再改）

| 文件 | 说明 |
|---|---|
| `ui/theme/Color.kt` / `Theme.kt` | 已恢复上游逐字版；定制全部在 `ThemeClassFlow.kt` |
| `ui/schedule/components/CourseBlock.kt` | 上游逐字版 |
| `ui/schedule/components/ScheduleGridComponents.kt` | 上游原版 |
| `res/values*/strings.xml` | 上游骨架（仅 `app_name`、zh-rTW `item_personalization` 值定制） |
| `res/values*/classflow_strings.xml` | 独有文件（定制字符串） |

## 有意差异文件（按类别）

### 1. 课表核心
| 文件 | 差异内容 |
|---|---|
| `ui/schedule/WeeklyScheduleScreen.kt` | WBU 同步按钮/弹窗、FloatingCourseBar 接线、`onFloatingModeChange`、毛玻璃背景、Snackbar 定制、布局差异 |
| `ui/schedule/WeeklyScheduleViewModel.kt` | 上游 mergeCourses（子列）+ 拖拽方法；`MergedCourseBlock` 上游版；显示非本周课程开关分支；Sakura 相关字段 |
| `ui/schedule/components/ScheduleGrid.kt` | **仅** `showGlassBorder` 参数 + 玻璃光边修饰（~38 行），其余与上游逐字一致 |
| `ui/schedule/components/FloatingCourseBar.kt` | 上游原版（merge 带入，已接线） |
| `ui/components/CourseTablePickerDialog.kt` | EntryPoint 结构（非上游 hiltViewModel deps）+ 快速新建课表**空名修复**（`val nameToCreate` 先捕获再 launch，防协程内读已清空状态）——修复标记由 audit 脚本守护 |

### 2. 设置页（毛玻璃 UI 体系）
| 文件 | 差异内容 |
|---|---|
| `ui/settings/SettingsScreen.kt` | 毛玻璃卡片体系（SettingsCard/SettingTile）、品牌头部、Sakura 开关 |
| `ui/settings/additional/MoreOptionsScreen.kt` | 毛玻璃、WBU VPN 手动开关、产品愿景卡片、贡献者入口；语言入口改为导航到 `LanguageSettings` 独立页（旧对话框已移除）；**不恢复**「更新适配仓库」入口（有意定制） |
| `ui/settings/additional/LanguageSettingScreen.kt` | 上游移植文件（上游为 `shared/commonMain` + expect/actual，此处为 Android-only 单文件实现） |
| `ui/settings/credentials/` | 账号与凭据管理页：按服务类型（统一认证/教务/图书馆/WebVPN/一卡通/WebDAV）分区，支持进入自动验证会话（可开关）、密码脱敏清除/重设、按服务清会话；独有文件 |
| `ui/settings/style/StyleSettingsScreen.kt` + `Components.kt` + `ViewModel.kt` | 新增设置项：壁纸调整入口（WallpaperAdjust）、玻璃样式预设、字体样式、背景遮罩等；布局与上游不同 |
| `ui/settings/conversion/` | WBU 教务一键同步入口（替换上游多校入口）、ICS 导出定制弹窗（Dialog+Card）、同步到系统日历（复用上游链路） |
| `ui/settings/AppSettingsViewModel.kt` | 追加字段 setter（Sakura、显示非本周课程等） |

### 3. 宿主与导航
| 文件 | 差异内容 |
|---|---|
| `MainActivity.kt` | 悬浮课程时隐藏 Dock（`isFloatingCourseMode`）、onboarding 引导、背景壁纸容器、校园服务路由（成绩/空教室/学业进程/扫一扫）、桌面快捷方式深链（`ACTION_QR_SCAN` / `ACTION_CAMPUS_CARD` + `pendingDeepLink`/`onNewIntent`）、`AppCompatActivity` 宿主（语言切换依赖 AppCompat delegate 生效） |
| `Navigation.kt` | `WallpaperAdjust`、`GradeQuery`、`FreeClassroomQuery`、`AcademicProgress`、`CredentialManagement`、`QrScan`、`LanguageSettings` 目的地 |
| `AndroidManifest.xml` | `CAMERA` 权限；`MainActivity` 追加 `QR_SCAN` 与 `CAMPUS_CARD` intent-filter（与 flavor 独立的 shortcuts.xml 配合定向分发）与 `android.app.shortcuts` meta-data；`AppLocalesMetadataHolderService` + `autoStoreLocales=true`（语言选择持久化，上游同款） |
| `res/values/themes.xml` | `Theme.ClassFlow` 父主题改为 `Theme.AppCompat.DayNight.NoActionBar` + 透明状态栏 + 关闭 Activity 转场（上游 androidApp 同款；AppCompatActivity 必需） |
| `ui/components/NavigationComponents.kt` | 液态玻璃 Dock（`BottomNavigationBar`）+ `DockSafeBottomPadding` |

### 4. 数据层（Room/proto 无法拆文件，追加字段）
| 文件 | 差异内容 |
|---|---|
| `data/db/main/*`（AppSettings/Course/TimeSlot/迁移） | ClassFlow 追加字段（Sakura 开关等）+ 迁移版本；字段带默认值，通常可自动合并 |
| `data/model/ScheduleGridStyle.kt` | proto 映射 + ClassFlow 字段（100+ 编号区段：glass_preset 等） |
| `app/src/main/proto/schedule_style.proto` | ClassFlow 自有字段段（编号 ≥100，与上游区隔） |
| `data/repository/StyleSettingsRepository.kt` | ClassFlow 字段 setter |

### 5. WBU 教务同步与校园服务（独有子系统，上游无此文件）
`data/network/wbu/`、`data/model/wbu/`、`ui/campus/`（成绩查询、空教室查询、学业完成度与课程进程）、`ui/components/WbuAuthBottomSheet.kt`、`WbuLoginSelectorBottomSheet.kt`、`ui/schedule/components/WbuSyncComponents.kt`、`ui/schoolselection/web/`（WebView 注入）、`WbuWebLoginAutofillStore.kt` 等——**上游不存在，merge 零冲突**

- 凭据存储改造（`WbuAuthTransport.kt`）：key 由旧版扁平名改为「服务类型 × 账号」分桶（`<field>@<service>@<account>`，如 `password@ids@primary`、`cookies@jwxt@primary`），Cookie 按服务归属拆分持久化（运行时内存 jar 仍共用）；旧扁平 key 一次性**复制**迁移（`migrateLegacyCredentialKeysOnce`，`MyApplication` 启动调用），**保留旧数据不删**。新增 `data/model/wbu/CredentialService.kt`（服务枚举）、`WbuCredentialRepository.kt`、`CredentialVerifier.kt`。
- 登录编排统一（**上游无此结构**）：`ui/campus/components/WbuCampusAuthSheet.kt` 升级为唯一登录编排宿主（WebVPN 门户登录 / 短信 / 滑块 / 二维码 / 证书异常询问 `SslIssueDialog` / 校园网确认回调 / 验证码回退回调 / `flowTagPrefix`），校园服务、课表页、导入弹窗、账号与凭据页共用；`ui/components/SslIssueDialog.kt` 为抽出的公共弹窗；`ui/components/WbuCourseImportSheet.kt` 瘦身为「导入管线 + 学期/重复课程弹窗」。
- 扫码端（**上游无此结构**）：`ui/campus/qrscan/`（`QrScanScreen` + `QrScanViewModel` + `QrLuminance` + `QrImageDecoder`）以 CameraX 取景，解码引擎可在 ML Kit 与 ZXing 间切换（右上角菜单，选择存于 `wbu_sync_auth` 的 `qr_scan_engine`，经 `WbuAuthTransport.get/setQrScanEngine`）；支持相册选图识别（系统 Photo Picker，按当前引擎解码，无需存储/相机权限）；取景框与状态卡按可用空间自适应横屏/Pad；`CasQrLink.parseUuid` 解析二维码内 uuid；`IdsCasClient.scanPeerQrCode`/`confirmPeerQrCode`（+ `WbuSyncEngine` 门面）实现「置 2 → 置 1」，身份取自本机 `CASTGC`，未登录按 `206302` 判定。
- 仅登录统一认证与 WebVPN / 校园网解耦（**上游无此结构**）：`WbuCampusAuthSheet` / `WbuAuthBottomSheet` 新增 `unifiedAuthOnly`——账号页「统一认证」卡与扫一扫页唤起的 Sheet 只为拿到/续期统一认证会话（`CASTGC`），不再登录教务、也不探测或要求校园网（密码 / 动态码 / 二维码三种方式一致）。「统一认证经过WebVPN」关闭时不显示网络开关并强制直连（`WbuSyncEngine.loginUnifiedAuthOnly` + `IdsCasClient` 的 `authBaseOverride` 覆盖 `WbuAuthTransport.idsPublicBase`），开启时开关保留、关闭态文案为「直连」（`label_direct_connection`）。该开关只作用于本次统一认证，不改写全局「网络接入模式」（`last_use_vpn`）；`WbuAuthTransport.startNewUnifiedAuthLoginSession()` 定向重置 ids 会话（保留教务 / 图书馆 / WebVPN 既有会话），失败经会话快照回滚。

### 6. 其他独有/定制
- `ui/theme/ThemeClassFlow.kt`（Sakura/Afternoon/Evening 色板 + ClassFlowTheme）
- 桌面快捷方式资源：`src/dev/res/xml/shortcuts.xml` 与 `src/prod/res/xml/shortcuts.xml`（显式指定 `targetPackage` 与 `targetClass`，消除 dev/prod 共存时的选择弹窗；扫一扫 `qr_scan` + 一卡通 `campus_card`）、`res/drawable/ic_shortcut_qr_scan.xml`、`res/drawable/ic_shortcut_campus_card.xml`（独有文件）
- 依赖追加：`androidx.camera:camera-{core,camera2,lifecycle,view}` 1.6.2 + `com.google.mlkit:barcode-scanning` 17.3.0（`gradle/libs.versions.toml`、`app/build.gradle.kts`）
- `ui/settings/themesettings/`、`WallpaperAdjustScreen.kt`、`OnboardingOverlay.kt`
- widget `*NativeRenderer.kt` 系列（原生渲染，上游部分有对应文件——差异在渲染实现）
- `tool/UpdateTool.kt`（更新渠道单渠道 + 兼容 API）
- `service/` 系列（闹钟/通知 worker，上游有对应文件——注意差异多为字段/逻辑小改）

## 已知历史教训（避免重蹈）

1. **手工移植必须逐字对比**：数值类差异（1.5dp vs 1dp、虚线间距、padding 双层）最容易漏——组件级对齐一律用 `git diff 上游基线 -- 文件` 验证到 0
2. **merge 只看冲突会漏非冲突差异**：早期引入的差异在非冲突区域潜伏——merge 后必须跑全量 `git diff --stat` 审计
3. **遮盖层位置**：视觉降级遮罩必须在 Box 层（覆盖整个块含 padding），不能放 Column 内容区
4. **textAlign 等"隐式属性"**：文本对齐、maxLines 等不报错但影响体验的属性，对齐时逐 Text 核对
5. **状态读取时序（CourseTablePickerDialog 空名事故）**：移植上游功能时若因本地结构差异改写调用方式（deps → EntryPoint 等），必须保持「同步取参、异步执行」语义不变——`rememberCoroutineScope().launch` 体内不能直接读即将被清空的 `mutableStateOf` 变量，先捕获局部值再启动协程
