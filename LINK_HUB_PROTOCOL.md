# 通用链接节点协议（`/url/{code}`、短别名 `/u/{code}`）

> 面向服务端与 App 双方的契约文档。App 侧实现见
> `data/network/link/LinkHubUrl.kt`、`data/network/link/LinkHubCompactCodec.kt`、
> `ui/link/`；仓库代码不写死真实域名，hub host 由 `CLASSFLOW_LINK_HUB_HOST` 私有注入。

## 1. 定位

一个命名空间承载所有「用二维码 / NFC / 链接分享出去的小配置」：

| 用途 | 形态 | 说明 |
|---|---|---|
| 布局插件分享 | 服务端短码（大配置） | 载荷只有清单地址，哈希/作者等元数据由服务端清单提供 |
| 网络代理分享 | 内嵌（小配置）优先 | 口令不进服务端日志；塞不下时回退服务端短码 |
| 任意网页 / 文本分享 | 内嵌 | 现成可用的最小闭环 |
| **设备直达**（U净 饮水机 / 洗衣机 / 吹风机、洗浴控水器） | **不进本协议**，走 `<hub>` 的设备命名空间 | 链接内容即设备身份，见 §2.1 |

入口三条，最终都进入 App 的节点确认页：

1. **NFC 触碰**：标签写 `NDEF URI 记录`；App 在后台由 `NDEF_DISCOVERED` 过滤器接住，在前台由 `enableForegroundDispatch` 接住（不要求 `assetlinks.json`）。
2. **外部链接（VIEW）**：hub host 的 `autoVerify` 过滤器注册 `/url/`、`/u/`（通用节点）与 `/w/`、`/wm/`、`/hd/`、`/s/`（hub 站同时也是设备直达站点，下载页 `/w/download` 就在这儿）；U净 host 单独一个 filter 注册 `/w/`、`/wm/`、`/hd/`（域名验证按 host 逐个进行，互不牵连）。两个 host 的 `.well-known/assetlinks.json` 都含 `com.shiro.classflow` 与 `com.shiro.classflow.dev`，真机 `pm get-app-links` 显示均为 `verified`：**直接导航**到这些链接（系统浏览器地址栏、聊天软件点链接、扫码）能进 App。dev 与 prod 同时安装时，系统按惯例可能弹一次选择器。
   ⚠️ 但落地页上的按钮**不能只靠 App Links**：落地页与深链同在 hub 域下，Chrome 不会把**同域**导航交给系统（详见 §6.1），所以落地页在用户点击时改用 `intent://` 显式唤起。
3. **App 内扫一扫**：相机或相册选图解码后走同一套路由。

## 2. URL 形态与严格匹配

```
服务端短码：  https://<hub>/url/{code}
短别名：      https://<hub>/u/{code}          # 与 /url/ 完全等价，省 2 字节
内嵌：        https://<hub>/url/{code}#<紧凑载荷>
内嵌（省码）：https://<hub>/u/#<紧凑载荷>      # 极小标签用，code 可省略
```

> **debug 构建的本地联调例外**：dev 构建可通过 `CLASSFLOW_LINK_HUB_DEBUG_BASE`（如 `http://127.0.0.1:8090`）
> 额外接受一个来源，用于不部署公网就联调读侧（配合 `adb reverse tcp:8090 tcp:8090`）。
> 请求时按**链接自身的 origin** 走，因此本地链接请求本地、线上链接仍请求线上，互不干扰；release 构建不存在这个例外。

匹配规则（不满足即交回浏览器，App 不处理）：

- 必须是 `https`；
- host 精确等于构建时注入的 hub host（大小写不敏感）；
- 端口必须是 443（缺省），不接受显式其它端口；
- 不允许 `user@host` 形式；
- 路径只允许 `/url`、`/url/{code}`、`/u`、`/u/{code}`，其中 `code` 为 `1~64` 位 `[A-Za-z0-9_-]`；
- fragment 为空，或为紧凑载荷（**不允许包含 `=`**：`#k=v` 形式留给未来扩展）；
- `code` 与内嵌载荷至少有一个存在。

### 2.1 设备直达命名空间（不经过本协议的节点确认流程）

设备类链接**内容即设备身份**，动作边界与「直接扫设备贴纸」完全等价，因此不需要节点确认、
也不进服务端：App 解析出来就直接落到对应功能。域名同样不校验 —— 能进 App 的链接已经由
`AndroidManifest.xml` 的 intent-filter 限定在站点域名上，这样站点换域名或本地联调都不用重写标签。

```
饮水机：      https://<域名>/w/{设备码}
洗衣机：      https://<域名>/wm/{设备码}
吹风机：      https://<域名>/hd/{设备码}
洗浴 · 1栋：  https://<域名>/s/y/{机号}            # 智能控水（yktxyyy），机号为 1~5 位数字
洗浴 · 2-3栋: https://<域名>/s/l/{设备号}          # lifeService（水表 51）
              https://<域名>/s/l/{设备号}/{端口}    # 多路控水器，端口 1~8 位数字
```

`/s/` 的取值约定：

| 段 | 规则 |
|---|---|
| 系统 | `y` = 1 栋智能控水（yktxyyy）；`l` = 2-3 栋 lifeService。**两个字母是系统标识的首字母缩写，只作路由用**，人可读的说明写在文档与生成器 UI 里 |
| 设备号 | 按 URI 段编码（`encodeURIComponent`），因此设备号可以是整条厂商链接（如 `4gsk.shuibiao51.com?id=123` → `4gsk.shuibiao51.com%3Fid%3D123`） |
| 端口 | 仅 `l` 可带，可省略 |
| 保留段 | `download`（官网下载页）不作设备号处理 |

> 为什么不把洗浴也塞进内嵌紧凑载荷（`/u/#载荷`）：base64 会把每个字节膨胀 1.33 倍，
> 而洗浴只有「系统 + 设备号」两个字段 —— 1 栋 5 位机号打包成二进制后反而**比明文更长**，
> 2-3 栋的设备号更是凭空多出 8 个字符。`/s/...` 既更短，也人可读：贴纸上的链接一眼能看懂，
> 任何扫码工具都能直接解析，不必自带 base64 解码器。

## 3. 线格式 A：服务端 JSON 信封

**两个地址，各管一头**（这条分工很关键，能避免「App 拿到落地页 HTML」这类坑）：

| 地址 | 谁用 | 要求 |
|---|---|---|
| `https://<hub>/url/{code}`（`/u/{code}`） | **浏览器 / 扫码 / NFC 标签**：写进二维码与标签的就是它 | 必须能显示「打开 App / 下载 App」的落地页；通常直接由静态页接管 |
| `GET https://<hub>/api/v1/nodes/{code}` | **App 读取节点内容** | 必须落到服务端程序，返回 JSON 信封 |

App 请求：

```http
GET /api/v1/nodes/{code} HTTP/1.1
Host: <hub>
Accept: application/json
User-Agent: ClassFlow/<versionName> (<platform>)
```

服务端返回（`Content-Type: application/json`）：

```json
{
  "v": 1,
  "type": "proxy",
  "title": "宿舍代理（备用线路）",
  "description": "仅用于校内资源访问",
  "minAppVersion": 18,
  "expiresAt": 1760000000,
  "payload": { "kind": "socks5", "host": "1.2.3.4", "port": 1080,
               "username": "abc", "password": "xyz" }
}
```

字段：

| 字段 | 必填 | 说明 |
|---|---|---|
| `v` | 建议 | 协议版本，缺省按 1 处理；App 不认识则提示更新，不下发动作 |
| `type` | 必填 | 动作类型，小写，见表 |
| `title` / `description` | 否 | 确认卡片上的标题与说明 |
| `minAppVersion` | 否 | 要求的最低 `versionCode`；低于它的客户端只提示更新 |
| `expiresAt` | 否 | Unix 秒；过期后只提示已失效 |
| `payload` | 否 | 动作自定义字段（缺省 `{}`） |

为什么 App 不直接读 `/url/{code}`：浏览器侧那个地址经常被站点用**静态落地页**接走（为了给没装 App 的人提示下载），
静态页做不了 `Accept` 内容协商，App 就永远只能拿到 HTML。放在 `/api/` 下（写入接口本来就必须落到服务端程序）
最稳：**浏览器侧怎么改都不会影响 App**。

行为约定：

- 若部署上仍让 `/url/{code}` 落到服务端程序，服务端**可以**额外按 `Accept` 协商（JSON 给 App、HTML 给浏览器），
  这属于兼容能力，App 不依赖它；
- 未知字段一律忽略（前后兼容）；
- `404` / `410` → 「链接无效」/「已失效」；`5xx`、超时、断网 → 可重试的失败态；
- 重定向只允许停留在同一 host（禁止跳到第三方或内网地址）；
- 同一 URL 在浏览器访问时应返回 H5 兜底页（用于未安装 App 的场景），JSON 与 HTML 通过 `Accept` 协商。

### 3.1 动作类型 payload 契约

#### `campus_shower`：校园洗浴控水设备（免确认）

**首选的对外形态是 §2.1 的 `/s/y/{机号}`、`/s/l/{设备号}[/{端口}]`**：更短、人可读、离线可用。
本类型是它的服务端形态 —— 短码可撤销、可改内容，适合「设备换位置/换编号后还能改」的场景。
两种形态都**免确认直接落入一卡通洗浴流程**（见第 6 节），走的是同一个落地逻辑。
`payload.system` 决定设备系统：

| system | 必填 | 可选 | 说明 |
|---|---|---|---|
| `yktxyyy` | `posno`：1~5 位数字机号 | — | 马影河 1 栋「智能控水」；App 先调水控 `CheckKsPos` 正证，再取一次性 ticket 深链 |
| `life_service` | `imei`：水表设备号（非空、无空白/`$`/`#`，≤256 字节） | `port`：1~8 位数字 | 马影河 2-3 栋 lifeService（水表 51），App 拼 `scanResult` 深链 |

创建示例（写入 API）：

```json
{ "v": 1, "type": "campus_shower", "title": "马影河1栋 301",
  "payload": { "system": "yktxyyy", "posno": "10101" } }
```

```json
{ "v": 1, "type": "campus_shower", "title": "马影河2-3栋 淋浴",
  "payload": { "system": "life_service", "imei": "1234567890", "port": "2" } }
```

约束：

- 支持 CFN1 内嵌（第 4 节 `0x15`）与服务端短码两种形态：内嵌离线可用、无需服务器；短码可改内容 / 可撤销；
- 两种形态均免确认直达（处理器声明 `autoApply` + `autoApplyInline`，与直接扫原设备二维码等价）；
- 设备是否真实存在仍由各自后端裁决（1 栋 `CheckKsPos`、2-3 栋 lifeService），失败只提示、不误跳；
- 浏览器侧仍显示「打开 ClassFlow / 下载」落地页或信息页，不做任何自动跳转。

> **U净 设备（饮水机 / 洗衣机 / 吹风机）不走通用链接节点。** 它们的动作边界与「直接扫设备贴纸」完全等价，
> 没必要再包一层可撤销的节点（`/u/` 链接也更长、标签占用更大），因此改用设备命名空间的直达链接
> `/w/{cd}`、`/wm/{uuid}`、`/hd/{cd}`（见第 2.1 节与第 9 节），由 App 的深链路由直接落地。
> 通用链接节点不再提供 `ujing_device` 类型，`0x16` 也不再分配。
>
> 洗浴同理，首选 `/s/y/...`、`/s/l/...`（第 2.1 节）；`campus_shower` 节点类型保留，
> 只作为「需要可撤销 / 可改内容」时的服务端形态。

## 4. 线格式 B：内嵌紧凑二进制（CFN1）

```
fragment = base64url( CFN1 字节串 )     # 无 padding；fragment 不含 '='
```

字节布局（首字节 `0x高nibble=版本 | 低nibble=类型`，当前版本 1）：

| 类型 | 首字节 | 布局 |
|---|---|---|
| `open` | `0x11` | `flags(1B)` + URL 剩余字节；`flags.bit0=1` 表示已省略 `https://` |
| `text` | `0x12` | UTF-8 原文（≤1024B） |
| `proxy` | `0x13` | `kind(1B: 0=http,1=socks5)` + `port(u16 BE)` + `host(1B 长度前缀)` + `user(1B 长度前缀)` + `pass(1B 长度前缀)` |
| `layout_plugin` | `0x14` | `flags(1B)` + 清单 URL（必须 https；哈希等元数据由服务端清单提供） |
| `campus_shower` | `0x15` | `system(1B: 0=智能控水,1=lifeService)`；智能控水：`posno(u24 BE)`；lifeService：`flags(1B,bit0=有端口)` + 可选 `port(u32 BE)` + `imei(1B 长度前缀)`。**兼容形态**：对外生成洗浴链接请用 §2.1 的 `/s/...`，本类型只保证「已写好的内嵌载荷仍然能解码」 |
| 逃生口 | `0x1F` | 其后为 UTF-8 JSON 信封（线格式 A 的 JSON），需要 `title`/有效期时就地升级 |

> `campus_shower`（`0x15`）是洗浴的**内嵌兼容形态**（对外形态见 §2.1 的 `/s/...`）；
> 它与服务端短码两种形态均免确认直达（设备动作与直接扫原设备二维码等价）。

限制与守卫：

- fragment 长度 ≤ 2048 字符，解码后 ≤ 1024 字节；
- 解码严格：未知类型 nibble → 「需要更新 App」；未知版本 nibble → 同上；结构不合法（长度越界、端口非法、host 为空、proxy 尾部有多余字节）→ 无效链接；
- 不使用 deflate：短文本下 zlib 头 + base64 的 33% 膨胀是负收益，直接紧凑二进制；
- `title` / `description` / `minAppVersion` / `expiresAt` 只存在于线格式 A；需要它们时用 `0x1F` 逃生口。

## 5. 字节预算与标签选型（实测）

NDEF 占用 = TLV(3B) + 记录头(4B) + URI 前缀码(1B，`0x04` 已把 `https://` 省掉) + 其余字节。

| 形态 | URL 字符 | NDEF | 48B 标签（MIFARE Ultralight） | NTAG213(144B) |
|---|---|---|---|---|
| 服务端短码 `/url/ab12c` | 32 | ≈32B | ✅ | ✅ |
| 服务端短码别名 `/u/ab12c` | 30 | ≈30B | ✅ | ✅ |
| 内嵌 open 带 code | 54 | ≈54B | ❌ | ✅ |
| 内嵌 open 不带 code | 46 | ≈46B | ✅ | ✅ |
| 内嵌 proxy 带 code | 61 | ≈61B | ❌ | ✅ |
| 内嵌 proxy 不带 code | 53 | ≈53B | ❌ | ✅ |
| ~~JSON+deflate+base64（旧方案）~~ | 157 | ≈157B | ❌ | ❌ |

结论：

- **极小标签**（16 页 / 48B）→ 服务端短码，或「无 code 的内嵌 open」；
- **含口令的 proxy 内嵌** → NTAG213 起；
- **不做 AAR**：单条 AAR ≈ +45B，dev/prod 双包名要写两条 ≈90B，直接吃光小标签；靠 NDEF 过滤器 + 前台派发保证进 App 即可；
- 二维码同步受益：50~60 字符链接约为 QR 版本 3（ECC M），157 字符要版本 7。

## 6. 无效与兜底矩阵（App 行为）

| 情况 | 行为 |
|---|---|
| host/路径不匹配 | 不处理，交回浏览器 |
| fragment 结构非法 | 「链接无效或已损坏」 |
| 内嵌版本/类型不认识 | 「需要更新 App 后才能使用该内容」 |
| 类型已冻结但本版本未实现（`proxy`、`layout_plugin`） | 「该类型需要更新 App 后才能使用」 |
| 类型未知（服务端新造的类型） | 「无法识别的分享类型：xxx」+ 复制链接 |
| 已过期 / `minAppVersion` 过高 | 对应提示，不落地 |
| 解析失败（网络/服务端 5xx、超时） | 「网络异常」+ 重试 |
| 读接口返回 HTML / 非 JSON（`/api/` 未落到服务端程序） | 「该分享内容暂时打不开」+ 重试 + 用浏览器打开 |

默认任何动作都必须由用户在确认卡片上点「应用」。例外（免确认类型解析成功即跳转，此时页面只显示品牌过渡动画，
不显示「分享的内容」标题与确认卡片）：

- **服务端短码节点**上声明 `autoApply` 的类型（如 `campus_shower`）免确认；
- **内嵌载荷**默认仍需确认；仅当处理器同时声明 `autoApplyInline`（当前为 `campus_shower`，与直接扫原设备二维码等价）才免确认。

### 为什么 HTML 响应不塞进内置 WebView

这类页面（例如站点原有的「打开 ClassFlow」跳转页）通常自己会 `location.href = 'intent://...#Intent;...;end'`。
WebView 不认识 `intent://`，只会渲染出「网页无法打开 / `net::ERR_UNKNOWN_URL_SCHEME`」——
在 App 里表现为一张莫名其妙的错误页（真机复现过）。所以：**服务端必须给 App 返回 JSON**；
拿到 HTML 时只提示 + 重试，并把「用浏览器打开」留给系统浏览器（那里 `intent://` 与 App Links 行为才正常）。

另外 `WebViewScreen` 已拦截非 http(s) 跳转：解析 `intent://` 内层 http(s) 地址，
命中校园深链规范（`/w/`、`/wm/`、`/hd/`、`/url/`、`/u/`）就走原生页面，其余交给系统，
因此任何页面在 App 内置 WebView 里都不会再出现那张 `ERR_UNKNOWN_URL_SCHEME` 错误页。

### 6.1 浏览器里点按钮怎么进 App（同域 App Links 不生效）

真机实测（Chrome 151 / Android 16；`autoVerify` 已验证、系统里该域的「打开支持的链接」也已启用）：
**从 `<hub>` 上的页面点一条 `<hub>` 上的 https 深链，Chrome 不会交给系统**，只当成一次普通同域导航
把落地页重新加载一遍 —— 用户看到的就是「点了没反应，页面自己刷新了」。
（落地页与深链同域是这里既定的部署形态；同样两条链接放在**跨域**页面里点则正常。）
`am start -a android.intent.action.VIEW -d 'https://<hub>/u/xxx'` 这种直接导航不受影响，
所以 NFC / 扫码 / 聊天软件里点链接都正常，只有「落地页上的按钮」需要特殊处理。

于是落地页（`pages/`，纯静态、无 PHP）在 Android 上把按钮的 `href` 换成显式唤醒：

```
intent://<host><path>[#载荷]#Intent;scheme=https;action=android.intent.action.VIEW;category=android.intent.category.BROWSABLE;S.browser_fallback_url=<编码后的 https 深链>;end
```

实测要点（都踩过）：

| 要点 | 说明 |
|---|---|
| 必须由**用户手势**触发 | 页面加载时自动 `location.href = 'intent://…'` 会被 Chrome 判成 `ERR_UNKNOWN_URL_SCHEME`；放进 `<a href>`（点击）就正常 |
| 仍要被 intent-filter 命中 | `intent://` 只是「显式唤起」，系统依旧按 `ACTION_VIEW` + https data 解析：路径没在清单里注册（例如 `/w/`、`/wm/`、`/hd/` 一度只注册在 U净 host）就会解析失败，浏览器只能退回 `browser_fallback_url` —— 表现同样是「点了没反应」。hub host 现已把 `/url/`、`/u/`、`/w/`、`/wm/`、`/hd/` 一起注册（`/plugin/` 没有 App 路由，落地页对它不给深链） |
| 载荷写在 `#` 后面 | `Intent.parseUri` 以**最后一个** `#` 分隔 intent 规格，所以 `intent://<hub>/u/#Emhp#Intent;…;end` 送到 App 的数据就是 `https://<hub>/u/#Emhp` |
| 别把 `#` 编码成 `%23` | `%23` 不会还原成 fragment，App 拿到的载荷会丢（实测 App 只收到 `…/u/%23Emhp`） |
| 没装 App 时 | `S.browser_fallback_url` 让浏览器退回普通 https 落地页，下载入口在那儿 |
| 微信 / QQ 内置浏览器 | App Links 与 `intent://` 都被拦，只能提示「右上角 → 在浏览器打开」 |
| 桌面浏览器 | 保持普通 https 深链（`intent://` 无意义） |

> App 侧要做的只是**把路径注册齐**：`intent://` 送过来的就是一条普通的 `ACTION_VIEW` + `https` data，
> 与 App Links 到达时的 Intent 完全一致（真机 `dumpsys activity` 核对过 `dat=`），路由逻辑不必区分两者。

## 7. 安全约束

1. 只认构建时注入的 hub host 的 `https` 链接；host 精确匹配、不跟随离站重定向；
2. 载荷内容不写日志、不进崩溃上报（可能含代理口令）；
3. 内嵌来源在 UI 上降级标注「内嵌内容 · 未经服务端校验」，并说明服务端无法撤销；
4. 内嵌载荷等同于明文（拍照/贴纸即可读），**含口令的代理配置需要加密方案**（口令短语或密钥对）——尚未实现；
5. 载荷签名（服务端私钥、App 内置公钥）尚未实现；在此之前，内嵌形态的信任边界等同「物理接触到该标签/二维码的人」。

## 8. 版本与扩展规则

- 新增动作类型：本文档登记 `type` 与 payload 字段 → App 实现 handler（`LinkHubActionHandler`）并注册到 `LinkHubHandlers`；
- 线格式 B 新增类型：占用一个新的低 nibble（当前 1~4，`0xF` 已被逃生口占用）；
- 线格式 B 字段扩展：新字段一律追加在尾部，解码端对多余字节按「非法」处理（proxy 已如此），因此**不兼容扩展必须换 nibble**；
- 协议版本升级：`v` 或高 nibble +1，旧 App 提示更新而不是猜测语义。

## 9. 实现状态

| 部分 | 状态 |
|---|---|
| URL 解析 / 严格匹配（`/url/`、`/u/`） | ✅ 已实现 |
| 内嵌 CFN1 编解码（open / text / proxy / plugin / JSON 逃生口） | ✅ 已实现（`open`、`text` 已接线） |
| NFC / 扫一扫 / 一卡通链接路由进 `Destination.LinkHub` | ✅ 已实现 |
| 节点确认页 + handler 注册表 | ✅ 已实现（内置 `open`、`text`、`campus_shower`） |
| `/s/` 洗浴设备直达链接（第 2.1 节） | ✅ 已实现：`/s/y/{机号}`、`/s/l/{设备号}[/{端口}]` → 独立落地页，**无确认页、无标题栏**，与目标页首屏同款过渡且硬切（`isSeamlessHandoff`）；扫码与 NFC/VIEW 两条入口共用同一个解析器 |
| `campus_shower` 校园洗浴（第 3.1 节） | ✅ 已实现：服务端短码与 `0x15` 内嵌均**免确认**直达一卡通洗浴（1 栋 `CheckKsPos` 正证、2-3 栋 lifeService） |
| 服务端 JSON 解析（线格式 A，App 侧） | ✅ 已完成：`LinkHubClient` 取信封、按 origin 请求、404/410/网络异常分类与重试、HTML 兜底交内置 WebView |
| hub host 的 VIEW / `autoVerify` 过滤器 | ✅ 已注册并验证通过：`/url/`、`/u/` + 设备命名空间 `/w/`、`/wm/`、`/hd/`（下载页 `/w/download` 就靠它进 App）；assetlinks.json 含 prod/dev 包名，真机 `pm get-app-links` = verified |
| 落地页按钮 → App（`intent://`，`web/` 不入库） | ✅ 已实现并在真机（Chrome 151 / Android 16）验证：`/u/{code}` → 节点确认卡片、`/u/#载荷` → 内嵌确认卡片、`/w/download` → 下载入场动画 |
| 免确认节点不闪确认页 | ✅ 已实现：页面外壳（标题栏 + 卡片）只在「需要用户读内容」时才出现；免确认节点从第一帧到跳转全程只有一层与目标页首屏同款的品牌过渡 |
| 服务端实现 | ✅ 已在私有环境落地（`GET /api/v1/nodes/{code}` 给 App 读；`/url/{code}` 由落地页接管；`POST/PUT/DELETE /api/v1/nodes[/{code}]` 提供创建、改内容、撤销） |
| URL 生成器（本地小工具，不入库） | ✅ 已实现：本地算内嵌载荷 + 本地渲染二维码 + 与 App 字节自检；U净 设备直接生成 `/w/` `/wm/` `/hd/` 直达链接（域名可改），不走节点 |
| 分享侧（App 内上传换 code、写 NFC 标签） | ⏳ 未开始（服务端写入 API 与生成器页面已可用） |
| `proxy` / `layout_plugin` 功能本体 | ⏳ 未开始（字段契约已冻结，见第 3、4 节） |
| 载荷签名 / 加密 | ⏳ 未开始 |
