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

入口三条，最终都进入 App 的节点确认页：

1. **NFC 触碰**：标签写 `NDEF URI 记录`；App 在后台由 `NDEF_DISCOVERED` 过滤器接住，在前台由 `enableForegroundDispatch` 接住（不要求 `assetlinks.json`）。
2. **外部链接（VIEW）**：已声明 hub host 的 `autoVerify` 过滤器（`/url/`、`/u/`），与 U净 host 分成两个 filter（域名验证按 host 逐个进行，互不牵连）；该 host 的 `.well-known/assetlinks.json` 含 `com.shiro.classflow` 与 `com.shiro.classflow.dev`，真机 `pm get-app-links` 显示 `wbu.pennote.cn: verified`，浏览器/聊天软件里点链接可直接进 App。dev 与 prod 同时安装时，系统按惯例可能弹一次选择器。
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

## 3. 线格式 A：服务端 JSON 信封

App 请求：

```http
GET /url/{code} HTTP/1.1
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

行为约定：

- 未知字段一律忽略（前后兼容）；
- `404` / `410` → 「链接无效」/「已失效」；`5xx`、超时、断网 → 可重试的失败态；
- 重定向只允许停留在同一 host（禁止跳到第三方或内网地址）；
- 同一 URL 在浏览器访问时应返回 H5 兜底页（用于未安装 App 的场景），JSON 与 HTML 通过 `Accept` 协商。

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
| 逃生口 | `0x1F` | 其后为 UTF-8 JSON 信封（线格式 A 的 JSON），需要 `title`/有效期时就地升级 |

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
| 解析失败（网络/服务端） | 失败态（Stage B 提供重试） |

任何情况下都**不会自动落地**：必须由用户在确认卡片上点「应用」。

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
| 节点确认页 + handler 注册表 | ✅ 已实现（内置 `open`、`text`） |
| 服务端 JSON 解析（线格式 A，App 侧） | ✅ 已完成：`LinkHubClient` 取信封、按 origin 请求、404/410/网络异常分类与重试、HTML 兜底交内置 WebView |
| hub host 的 VIEW / `autoVerify` 过滤器 | ✅ 已注册并验证通过（assetlinks.json 含 prod/dev 包名，真机 `pm get-app-links` = verified） |
| 服务端示例（`server/linkhub.php`，Python 版等价） | ✅ 已实现：`GET /url/{code}`、`GET /u/{code}`、`POST/GET/PUT/DELETE /api/v1/nodes[/{code}]`、`GET /api/v1/health`；`/api/` 带 CORS；写接口 `Authorization: Bearer <TOKEN>` |
| 生成器页面（`server/linkhub-demo.html`） | ✅ 已实现：本地算内嵌载荷 + 调服务端换短码 + 本地渲染二维码 + 内置自检 |
| 分享侧（App 内上传换 code、写 NFC 标签） | ⏳ 未开始（服务端写入 API 与生成器页面已可用） |
| `proxy` / `layout_plugin` 功能本体 | ⏳ 未开始（字段契约已冻结，见第 3、4 节） |
| 载荷签名 / 加密 | ⏳ 未开始 |
