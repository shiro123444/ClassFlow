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
| `MainActivity.kt` | 悬浮课程时隐藏 Dock（`isFloatingCourseMode`）、onboarding 引导、背景壁纸容器、校园服务路由（成绩/空教室/学业进程/扫一扫/付款码）、桌面快捷方式深链（`ACTION_QR_SCAN` / `ACTION_CAMPUS_CARD` / `ACTION_PAY_CODE` + `pendingDeepLink`/`onNewIntent`）、`AppCompatActivity` 宿主（语言切换依赖 AppCompat delegate 生效） |
| `Navigation.kt` | `WallpaperAdjust`、`GradeQuery`、`FreeClassroomQuery`、`AcademicProgress`、`CredentialManagement`、`QrScan`、`CampusCardPayCode`（付款码）、`LanguageSettings` 目的地 |
| `AndroidManifest.xml` | `CAMERA` 权限；`MainActivity` 追加 `QR_SCAN`、`CAMPUS_CARD` 与 `PAY_CODE` intent-filter（与 flavor 独立的 shortcuts.xml 配合定向分发）与 `android.app.shortcuts` meta-data；`AppLocalesMetadataHolderService` + `autoStoreLocales=true`（语言选择持久化，上游同款） |
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
- 扫码端（**上游无此结构**）：`ui/campus/qrscan/`（`QrScanScreen` + `QrScanViewModel` + `QrLuminance` + `QrImageDecoder`）以 CameraX 取景，解码引擎可在 ZXing-C++ 与 ZXing 间切换（右上角菜单，选择存于 `wbu_sync_auth` 的 `qr_scan_engine`，经 `WbuAuthTransport.get/setQrScanEngine`，默认 zxing-cpp；旧版本存过 ML_KIT 的取值会在 `valueOf` 失败后回落到默认值）；支持相册选图识别（系统 Photo Picker，按当前引擎解码，无需存储/相机权限）；取景框与状态卡按可用空间自适应横屏/Pad；`CasQrLink.parseUuid` 解析二维码内 uuid；`IdsCasClient.scanPeerQrCode`/`confirmPeerQrCode`（+ `WbuSyncEngine` 门面）实现「置 2 → 置 1」，身份取自本机 `CASTGC`，未登录按 `206302` 判定。
- 洗衣机可用性判定（**上游无此结构**）：`data/network/wbu/WasherAvailability.kt` 把 U净 `devices/scanWasherCode` 的 `result.createOrderEnabled` 与 `result.status`（1 正在运行中 / 11 已被他人预约 / 2 故障中 / 8 已离线 / 7、9 未开通停用，与官方 washer-h5 扫码异常页同一套编码）收敛成 `Ready` / `Unavailable(reason)`——`orderId` 非空（自己已有未完成订单）按可下单处理，交给 H5 跳订单详情；扫码分流（`QrScanViewModel`）与 `/wm/{uuid}` 深链（`WasherEntryResolver`）都按 reason 出提示，文案集中在 `ui/campus/components/WasherNoticeDialog.kt`（离线 / 使用中 / 已被预约 / 故障 / 停用 / 码不存在 / 暂不可下单，服务端下发 `mobile` 时附商家电话），修复「不存在 / 被占用 / 故障都提示设备离线」；映射含手写单测 `WasherAvailabilityTest`。
- 取水「同一台设备被重复触发」的处理（**上游无此结构**）：U净 一个账号同一时间只允许一个出水点，所以同一台饮水机在出水进行中再碰 NFC / 再扫码都不再开一单 —— 取水页已经开着时由 `MainActivity` 的 `navBridge` 直接拦下（只弹 `toast_water_dispensing_in_progress`，不重进页面、不打断正在跑的流程）；页面不在前台时重新打开取水页接着看那一单（`UjingWaterViewModel.start` 复用内存里的会话，订单号 / 取水点都在其中，**不落盘**）。进程被系统杀掉后由保存状态恢复出来的旧取水页拿不到任何订单信息（会话只在内存里），`UjingWaterScreen` 什么都不渲染、直接退回上一页，既不重新下单也不停在「正在连接饮水机…」；退出取水页不再丢弃会话（只是不看，不等于订单结束），订单到终态 / 用户点「再接一杯」才清掉。顺带把订单状态轮询改成「拉不到就放慢重试」，不再连续失败几次就彻底停下，避免页面卡在「正在取水」上再也不动。
- 通用链接节点（**上游无此结构**）：`data/network/link/`（`LinkHubUrl` 严格校验 `/url/{code}` 与短别名 `/u/{code}`，`LinkHubCompactCodec` 内嵌 CFN1 紧凑二进制编解码）、`data/model/link/`（信封与 handler 接口）、`data/link/`（handler 注册表 + 内置 `open`/`text`）、`ui/link/`（节点确认页）；路由接入 `CampusLinkRouter`、`Destination.LinkHub`、`QrScanViewModel` 与 NFC / VIEW 过滤器（hub host 经 `CLASSFLOW_LINK_HUB_HOST` 注入，未配置时回落 U净 域名；VIEW 的 `autoVerify` 单独一个 filter，assetlinks.json 已验证通过）；协议契约见根目录 `LINK_HUB_PROTOCOL.md`。顺带两处轻量定制：`ui/schoolselection/web/WebViewScreen.kt` 新增 `hideImportBar` 参数（通用节点等非 WBU 场景隐藏底部「导入课程」引导栏）与 `handleNonHttpNavigation`（拦截 WebView 里的 `intent://` 等非 http(s) 跳转：命中校园深链走原生页，其余交系统，避免渲染出 `ERR_UNKNOWN_URL_SCHEME` 错误页）。
- 仅登录统一认证与 WebVPN / 校园网解耦（**上游无此结构**）：`WbuCampusAuthSheet` / `WbuAuthBottomSheet` 新增 `unifiedAuthOnly`——账号页「统一认证」卡与扫一扫页唤起的 Sheet 只为拿到/续期统一认证会话（`CASTGC`），不再登录教务、也不探测或要求校园网（密码 / 动态码 / 二维码三种方式一致）。「统一认证经过WebVPN」关闭时不显示网络开关并强制直连（`WbuSyncEngine.loginUnifiedAuthOnly` + `IdsCasClient` 的 `authBaseOverride` 覆盖 `WbuAuthTransport.idsPublicBase`），开启时开关保留、关闭态文案为「直连」（`label_direct_connection`）。该开关只作用于本次统一认证，不改写全局「网络接入模式」（`last_use_vpn`）；`WbuAuthTransport.startNewUnifiedAuthLoginSession()` 定向重置 ids 会话（保留教务 / 图书馆 / WebVPN 既有会话），失败经会话快照回滚。
- 一卡通付款码（**上游无此结构**）：`data/model/wbu/PayCodeModels.kt`（`CampusPayMethod` / `PayCodeBatch` / `PayCodeQueue`——平台一次下发一批码，页面展示队首、按 `min(60s, expires)` 翻码并跳过已失效的前缀，纯逻辑含单测）、`data/network/wbu/WbuPayCodeClient.kt`（`codebarPayinfo` 取支付方式与余额、`batchGetBarCodeGet` 取码；401 → `WbuSessionExpiredException` 交给续期，业务拒绝 → `PayCodeUnavailableException` 携带服务端原话）、`ui/campus/paycode/`（原生取码页：ZXing 本地渲染二维码 / 条码 / 数字码三种呈现，倒计时进度、展示期间屏幕常亮 + 亮度拉满、支付方式切换、失败或脱机码时回落官方页面；**条码复用已有的 `com.google.zxing:core` 渲染 CODE128，与平台 H5 的 `vue-barcode` 默认格式一致，未新增依赖**）。`WbuCampusCardClient.buildLaunchUrl` 追加 `path` 参数，用于直达平台内页（history 路由 `/plat/{path}`，付款码即 `campusCode`）。

### 6. 其他独有/定制

- `ui/theme/ThemeClassFlow.kt`（Sakura/Afternoon/Evening 色板 + ClassFlowTheme）
- 线上网页与短链服务端（**不入库**，本机维护、手动部署，全在 `web/` 下，已加入 `.gitignore`）：`web/site/` 官网静态站、`web/pages/` 「打开 App / 下载」落地页、`web/api/linkhub.php` 单文件短链后端（节点数据也在服务器上）、`web/tools/` 本机调试页；协议契约见 `LINK_HUB_PROTOCOL.md`。
- 桌面快捷方式资源：`src/dev/res/xml/shortcuts.xml` 与 `src/prod/res/xml/shortcuts.xml`（显式指定 `targetPackage` 与 `targetClass`，消除 dev/prod 共存时的选择弹窗；扫一扫 `qr_scan` + 一卡通 `campus_card` + 付款码 `pay_code`）、`res/drawable/ic_shortcut_qr_scan.xml`、`res/drawable/ic_shortcut_campus_card.xml`、`res/drawable/ic_shortcut_pay_code.xml`（独有文件）
- 设备直达 / 网页入口的静默登录：`/w/`（`UjingWaterViewModel`）与 `/wm/`、一卡通等 WebApp 入口（`WebAppViewModel`）在 **CASTGC 缺失或失效**时先复用仍有效的会话，不行才用**保存的统一认证密码静默登录一次**：没保存 WebVPN 密码就先弹小窗（`ui/components/WbuAuthPrompt.kt` 的 `WbuAuthPromptDialogs`）补 WebVPN 密码 → 再按需弹短信验证码；补不上或用户取消才回落到 `WbuCampusAuthSheet`。是否牵扯 WebVPN 只由「统一认证经过 WebVPN」决定（只有内容本身必须走 WebVPN、即 TWFID 门禁失效时才强制走），所以直连服务（一卡通、U净）在该开关关闭时完全不经 WebVPN。每次进入只尝试一次，避免失败后反复重试成环。付款码原生页（`PayCodeViewModel`）复用同一套 `silentUnifiedAuthLogin` + 全局补输入弹窗，同样是「续期 refresh_token → 静默重登一次 → 才弹 Sheet」
- 「自动使用保存的密码登录」开关与弹窗契约（**上游无此结构**）：账号与凭据页「验证」卡片新增开关（`wbu_sync_auth` 的 `auto_login_with_saved_password`，默认**开**，`WbuAuthTransport.is/setAutoLoginWithSavedPasswordEnabled` + `WbuCredentialRepository` 门面 + `CredentialManagementViewModel.setAutoLoginWithSavedPassword`）。语义刻意做成**只增不减**：关掉它 = 回到加开关之前的行为（U净、网页应用、付款码、图书/成绩/选课等既有静默路径照旧），打开才让「以前会直接甩登录面板 / 直接说未登录」的场景也先静默登录一次 —— 新增场景统一经 `ui/components/WbuCampusAccess.kt` 的 `shouldAttemptSavedPasswordLogin`（开关开 **且本机确实存着统一认证密码**，没存密码就不打扰）与 `prepareWbuImportWithSavedPassword` 两个闸门进入。覆盖场景：课表「一键同步」与「导入课程」（`WeeklyScheduleScreen` / `WbuCourseImportSheet` 的两处入口，进面板前先静默建好登录态，面板里只差用户点确认）、扫一扫（`QrScanViewModel.init`）、洗浴设备直达与通用链接节点（`ShowerDirectViewModel` / `LinkHubViewModel`，`LinkHubApplyResult.Failed` 新增 `needsLogin`）、选课中途失效（`CourseSelectionViewModel.handleFailure`，读操作静默重建后重载、写操作提示「已恢复，请再点一次」以避免重复提交）、账号与凭据页进入（`CredentialManagementViewModel.rebuildSessionIfNeeded`）、WebView 手动登录页填充（`WebViewScreen.maybeAutofillWbuCredentials` 在一次性草稿之外补上「保存的凭据」来源，**仍然只填不提交**）。
  弹窗契约（收敛「什么时候该弹什么」）：**只有会话失效 / 凭据被拒才弹 `WbuCampusAuthSheet`**；缺密码 / 门禁密码 / 短信验证码 / 图形验证码 / 滑块一律走进程级就地小窗（`WbuAuthPromptBus` 新增 `askSlider` / `askPortalCaptcha` 与 `WbuAuthPromptChallenge`，由 App 根部的 `WbuAuthPromptHost` 渲染 `SliderCaptchaDialog` / `PortalCaptchaDialog`）；网络不通 / 服务端异常 / 用户取消**不弹**登录面板，只给内联重试或一条可重试说明。为此把判据层拆细：`LocalLoginFailure` 由 `{CAPTCHA, CREDENTIALS}` 拆成 `{CAPTCHA, CREDENTIALS, NETWORK, PROTOCOL, VPN_SESSION}`（「网络异常」「跳转异常」「拿不到登录参数」过去全被归成「账号密码不对」），`PortalLoginStep.Error` 拆出 `PortalFailureKind{Rejected, Captcha, Network, Protocol, Cancelled}`（按门户 `ErrorCode` 判：`20004` 才是密码错，`20023/20041/20042/20043/20268` 是图形码/风控，`20053` = 验证码开着且密码也不对，直接按密码错上报、不再拿同一个错密码重试烧失败次数）；映射集中在 `AccessFailure.kt` 的 `casLoginFailure` / `portalLoginFailure` / `classifyCredentialRejection`（中英文实测文案），单测见 `AccessFailureClassificationTest`。清密码规则：**只有 `confirmedPasswordRejectionLayer` 非空（服务端点名密码错）才 `WbuAuthTransport.forgetRejectedPassword`** —— 逐值比对，只清「这次真正提交过的那份」，并关掉对应槽位的「记住密码」，让随后弹出的面板密码框为空（否则面板会拿占位符里的旧密码再提交一遍）。还有一处必须做全的收尾：静默登录拿到 `CASTGC` 之后要按**目标服务**把它自己的会话也建起来（`establishServiceSession`，由 `silentUnifiedAuthLogin(service = …)` 触发）——教务走 `exchangeCastgcForJwxtSession`（`CASTGC → jw_uf` + 引导），图书馆用 `WbuQueryClient.ensureOpacSession(forceRefresh = true)` 强制换一个新的 `PHPSESSID`；少了这一步就会出现「静默登录明明成功，成绩 / 学业进程 / 空教室还是提示登录已过期，最后把用户顶进登录面板」。
- 「自动校园网探测」与「单击直连、长按登录」（**上游无此结构**）：账号与凭据页「验证」卡片新增「自动校园网探测」开关（`wbu_sync_auth` 的 `auto_campus_probe`，默认**开**，仅「使用 WebVPN」开启时显示；`WbuAuthTransport.is/setAutoCampusProbeEnabled` + `WbuCredentialRepository` 门面 + `CredentialManagementViewModel.setAutoCampusProbe`）。判据收敛成两个纯函数（`ui/components/WbuCampusAccess.kt`）：`shouldProbeCampusForAccess(savedUseVpn, autoProbeEnabled, skipCampusCheck)`（三者同时成立才探测：WebVPN 模式 + 开关开 + 未跳过校园网检测）与 `useVpnAfterCampusProbe(onCampus)`（校内直连 / 校外 WebVPN），真值表见单测 `CampusChannelDecisionTest`；对外入口 `resolveCampusUseVpn(context, savedUseVpn)`，已接入 `campusAccess`（成绩 / 学业进程 / 空教室 / 图书馆 / 选课）、`prepareWbuImportWithSavedPassword`（课表同步与导入）、`WeeklyScheduleScreen.performDirectSync`、`WbuCourseImportSheet` 的「单击导入」分支、`WebAppViewModel`（WebVPN 模式下先探一次，在校内直接直连）与 `WebViewScreen` 首屏探测。`WbuNetworkProbe` 新增**快速探测** `fastRefresh(context)` 与按开关分派的 `probeForCampusFlow()`：**发一次短超时 HTTP**（连接 / 读各 500ms、总 700ms；响应码只认 2xx/3xx，判据 `isCampusPortalResponseCode` 有单测 `WbuNetworkProbeTest`）、结果缓存 60s、网络一变（`ConnectivityManager.registerDefaultNetworkCallback`，权限已在 manifest）立刻作废、**不**把 `campusState` 置成「检测中」（免得后台流程让正在看的登录面板提示闪一下）；并且在不可能是校园网的网络上（蜂窝 / 无网，`TRANSPORT_WIFI/ETHERNET/VPN` 之外）直接判校外 —— 校外到认证页的连接是黑洞（SYN 丢包、不回 RST），不这样判每次探测都得等满超时。**快速探测必须真的走完一次 HTTP，不能只连 TCP**：本机 tun 代理（如 FlClash）会把 TCP 连接自己接管 —— 实测代理在跑时连 `10.255.255.1:80` 都是 10ms 就「连上」，只连 TCP 的探测于是把校外用户判成「在校园网」，流程便跑去直连一个根本到不了的教务地址（一次假成功换来 9 秒白等 + 一句看不懂的失败）。同理 4xx/5xx 也不算证据：那更像代理 / 网关自己的错误页 —— 判错方向宁可偏向「不在校园网」，判成校外只会改走 WebVPN，判成校内却是硬失败。`refresh()`（HTTP、4s 超时、不缓存）仍是登录面板里切换直连 / WebVPN 时那次探测，`WbuAuthBottomSheet` 不受影响。
  单击同步 / 单击导入的**会话兜底**：`prepareJiaowuSessionForSync(context, engine, flowTag)`（单击路径现在统一从 `prepareWbuSyncSession` 进入，它只是其中一步）—— 先试现有会话（`ensureJwxtSessionWithExistingCredentials`），不行就用保存的密码 `silentUnifiedAuthLogin(onlyWithSavedPassword = true, service = JIAOWU)` **静默登录一次**再继续。WebVPN 模式下「本地有 CASTGC/jw_uf/TWFID」只代表曾经登录过，门禁 TWFID 过期是常态，过去据此判断会话可用 → 用户等几秒后被叫去登录面板。等待期间有反馈：`WbuSyncActionButton` 新增 `loading` 转圈、`CourseTableConversionScreen` 的「教务导入」卡片徽标旁转圈、`ManageCourseTablesScreen` 的导入按钮变转圈。同样接入的还有**不走 `campusAccess` 的零散请求**：空教室页的校区列表 / 单周日期 / 教室整周课表（`FreeClassroomViewModel` 的 `queryClient()`）、图书馆的续借 / 详情（`LibraryViewModel.queryClient()`）、账号与凭据页的会话核验（`CredentialVerifier.verify`，先按探测结果选通道再核验各服务，口径与页面实际访问一致）；判据统一放在数据层 `data/network/wbu/WbuCampusChannel.kt`（`shouldProbeCampusForAccess` / `useVpnAfterCampusProbe` / `resolveCampusUseVpn`，单测 `CampusChannelDecisionTest`）。一卡通 / U净 / 付款码 / `directOnly` 的网页应用**不接入**，任何情况下都不探测。「使用 WebVPN」(`last_use_vpn`) 由「未设置即视为关」改成**未设置即默认开**（`WbuAuthTransport.getSavedUseVpn` 改非空返回、默认 true，调用点的 `?: false` 一并去掉；`last_use_vpn_set` 仍在写，供需要区分默认值与用户选择的地方使用），好让新用户不必先找到这个开关就得到「校外 WebVPN / 校内直连」的自动切换；**直连失败后重算通道**（判据过期的兜底）：`WbuCampusChannel.kt` 新增 `resolveCampusChannel()`（通道 + 「这个通道是不是探测定的」）与 `replanCampusChannel()`（先 `invalidateFastCache()` 再重算），`AccessFailure.unreachableOnDirectChannel` 给出「直连一个响应都没拿到」的判据；`campusAccess` 与新增的 `prepareWbuSyncSession(context, flowTag, onLoginStart): WbuSyncSession.Ready/Failed`（单击同步 / 单击导入共用，把「通道 + 教务会话」一次办齐）在命中该判据且通道是探测定的时，**作废缓存重算一次通道**并在翻成 WebVPN 后重来一遍（只重算一次）—— 「刚走出校园网 WiFi、短缓存里还写着在校内」这类过期判据不再变成一次硬失败。登录面板（`WbuCampusAuthSheet`）的初始通道也按 `resolveCampusUseVpn` 探测一次（用户手动改过就以手动为准，`useVpnChosenByUser` 标记），`WbuAuthBottomSheet` 里那次校园网提示探测改用 `probeForCampusFlow`（开关开着时复用刚探测的缓存结果、不再白等 4 秒；关掉时仍是原来的较长超时探测）。
  交互层：`ui/schedule/components/WbuSyncComponents.kt` 的 `WbuSyncActionButton` 增加可选 `onLongClick`（走 `combinedClickable`），上游共用的 `ui/settings/SettingsScreen.kt` `SettingTile` 增加可选 `onLongClick`（不传时仍是原来的 `clickable`，行为不变），课表页与两个导入入口（`CourseTableConversionScreen` 的「教务导入」卡片、`ManageCourseTablesScreen` 的导入 FAB —— 后者改用 `Surface` + `combinedClickable`，因为 `FloatingActionButton` 只吃单击）统一为「**单击 = 直接用已保存登录态同步 / 导入，全程不弹登录面板；长按 = 打开登录面板**」。需要重新登录时用 `needLoginHintText`（`ui/components/WbuFailureText.kt`：失败原因 +「长按按钮可打开登录面板」）提示一句（用户自己取消则安静收场），登录面板仍是唯一的重新登录入口；但「长按」只对 `AccessFailure.needsRelogin`（会话失效 / 凭据被拒）的失败附上 —— 网络不通、返回内容不符预期这些情况把人引到登录面板解决不了任何问题。
**「失败原因」不许替服务端编原因**（**上游无此结构**的一部分，实测踩过坑）：① `fail_unexpected` 的文案从「操作没成功，可能是学校系统有变动，请稍后重试」改成「操作没成功，学校系统返回的内容不符预期，请稍后重试」（4 语言同步）—— 旧文案断言了一个具体原因，结果代理 / VPN 出问题时也弹它，用户就照着「等学校修好」去等；② WebVPN 门禁探活做不成（`TwfidState.UNKNOWN`：网络 / 门户不可达）在 `WbuSyncEngine` 的 `ensureVpnTunnelReady` 与 `loginWebVpnPortalOnly` 里改判 `AccessFailure.Unreachable(AccessLayer.WebVpnPortal)`（网络问题，文案「连不上 WebVPN，请检查网络后重试」，且不会带「长按登录」指引、更不会动保存的密码），而不是原来那句「无法确认 WebVPN 会话状态」的 `Unexpected`（渲染出来就是「学校系统有变动」）。手动 TWFID 探活失败仍是「保留并继续走门户登录」，两处口径不同是有意的。
- 依赖追加：`androidx.camera:camera-{core,camera2,lifecycle,view}` 1.6.2 + `com.google.zxing:core` 3.5.3 + `io.github.zxing-cpp:android` 3.1.1（Maven Central 上的 zxing-cpp 官方封装，POM 自带 Apache-2.0 许可信息，无需额外仓库）（`gradle/libs.versions.toml`、`app/build.gradle.kts`）
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
6. **「浏览器里点按钮没反应」有两层原因，缺一都不通**：① Chrome **不会把同域导航交给系统** —— 落地页与深链同在 hub 域下时，`<a href>` 指回 https 深链只会原地刷新，必须用**用户手势**触发的 `intent://`（自动跳会被判 `ERR_UNKNOWN_URL_SCHEME`）；② `intent://` 只是「显式唤起」，仍要被 App 的 intent-filter 命中 —— `/w/`、`/wm/`、`/hd/` 只注册在 U净 host 时，hub host 上同路径的按钮永远打不开。详见 `LINK_HUB_PROTOCOL.md` §6.1
7. **「没有数据」和「没登录」必须分得开（图书馆空列表事故）**：WebVPN 门禁（TWFID）失效时，Sangfor 网关会把请求**接管**到门户页 —— 跟随重定向后是 **HTTP 200 + 门户 HTML**，既不是 302、URL 里也没有 `login` 关键字。旧校验只判 `Location.contains("login")`，门户页被当成读者页解析成 0 条记录，界面于是显示「当前暂无在借图书」，用户以为是自己没借书、其实是会话早就掉了。防御写法：① 逐响应校验 `code == 200 && Location 为空 && request.url.host != "webvpn.wbu.edu.cn"`（注意 WebVPN 正常访问时落点本身是 `opac-xxx.webvpn.wbu.edu.cn` 代理子域，**必须精确比较 host，不能用 substring**，否则真·代理子域会被误判）；② 换票/落地跳转每一跳都要检查是否被门户接管，绝不能在拿不到新 `PHPSESSID` 时回退复用 Cookie 库里的旧值；③ 解析不出表格的兜底分支加门户特征（`redirect_uri=` / `login_psw.csp`）判断。总之：**凡是「空数据」分支，都要先能证明自己拿到的确实是业务页**
