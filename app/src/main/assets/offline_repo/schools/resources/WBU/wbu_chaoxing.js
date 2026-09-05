// 适配：武汉商学院-超星综合教学管理系统（WebView 导入）
// 逻辑对齐 school.js：
// 0) 询问导出学期（#xnxq1 下拉，分页）；选非当前学期用页面已有 xhid/xqdm 直接请求该学期
// 1) 统一走 sdpkkbList 拉课程 JSON；getZclistByXnxq 取开学日期/总周数/节次时间
// 2) 按 天+节次 单元格分组并周次 -> 连续节次合并 -> 同节次不同周次合并 -> 去重
// 3) 接口失败且当前在课表页时，回退解析已渲染 DOM（td.cell）
// 4) 校区：多校区且配置不一致才弹窗询问；一致则用默认
// 5) 教师工号/重复课程由 Android 端按设置统一处理（本脚本不做提示）

(() => {
    const DEFAULT_HEADERS = {
        "X-Requested-With": "XMLHttpRequest"
    };

    const WBU_TIME_SLOTS_FALLBACK = [
        { number: 1, startTime: "08:30", endTime: "09:15" },
        { number: 2, startTime: "09:20", endTime: "10:05" },
        { number: 3, startTime: "10:25", endTime: "11:10" },
        { number: 4, startTime: "11:15", endTime: "12:00" },
        { number: 5, startTime: "14:00", endTime: "14:45" },
        { number: 6, startTime: "14:50", endTime: "15:35" },
        { number: 7, startTime: "15:55", endTime: "16:40" },
        { number: 8, startTime: "16:45", endTime: "17:30" },
        { number: 9, startTime: "18:30", endTime: "19:15" },
        { number: 10, startTime: "19:20", endTime: "20:05" },
        { number: 11, startTime: "20:10", endTime: "20:55" },
        { number: 12, startTime: "21:00", endTime: "21:45" }
    ];

    const SEMESTER_PAGE_SIZE = 4;
    const CANCELED = "CANCELED";

    function showToast(message) {
        try {
            window.AndroidBridge?.showToast(message);
        } catch (error) {
            console.log("[WBU] toast failed", error);
        }
    }

    function sleep(ms) {
        return new Promise((resolve) => setTimeout(resolve, ms));
    }

    function cleanText(value) {
        if (!value) return "";
        return String(value)
            .replace(/<[^>]+>/g, "")
            .replace(/&nbsp;/g, " ")
            .replace(/&amp;/g, "&")
            .replace(/&lt;/g, "<")
            .replace(/&gt;/g, ">")
            .replace(/\s+/g, " ")
            .trim();
    }

    function parseNumber(value) {
        const n = Number(String(value ?? "").trim());
        return Number.isFinite(n) ? n : null;
    }

    function uniqueSortedNumbers(list) {
        const s = list.filter((n) => Number.isFinite(n));
        return Array.from(new Set(s)).sort((a, b) => a - b);
    }

    function expandRangeToken(token) {
        const t = token.trim();
        if (!t) return [];
        const single = parseNumber(t);
        if (single !== null) return [single];
        const m = t.match(/^(\d+)\s*[-~]\s*(\d+)$/);
        if (!m) return [];
        let start = Number(m[1]);
        let end = Number(m[2]);
        if (start > end) [start, end] = [end, start];
        const out = [];
        for (let w = start; w <= end; w += 1) out.push(w);
        return out;
    }

    function parseWeekText(text) {
        if (!text) return [];
        let s = String(text).trim();
        if (!s) return [];
        let oddOnly = false;
        let evenOnly = false;
        if (s.includes("单")) oddOnly = true;
        if (s.includes("双")) evenOnly = true;
        s = s.replace(/周/g, "").replace(/\s+/g, "");
        s = s.replace(/\(.*?\)/g, "").replace(/（.*?）/g, "");
        s = s.replace(/[（(]?单[）)]?/g, "").replace(/[（(]?双[）)]?/g, "");
        s = s.replace(/改$/g, "");
        const weeks = [];
        s.split(",").map((x) => x.trim()).filter(Boolean).forEach((seg) => {
            const r = seg.match(/^(\d+)-(\d+)$/);
            if (r) {
                const a = Number(r[1]);
                const b = Number(r[2]);
                for (let w = a; w <= b; w += 1) weeks.push(w);
                return;
            }
            const single = parseInt(seg, 10);
            if (Number.isFinite(single)) weeks.push(single);
        });
        let filtered = weeks;
        if (oddOnly && !evenOnly) filtered = weeks.filter((w) => w % 2 === 1);
        else if (evenOnly && !oddOnly) filtered = weeks.filter((w) => w % 2 === 0);
        return uniqueSortedNumbers(filtered);
    }

    function parseWeeksFromRow(row) {
        const zcstr = cleanText(row.zcstr);
        if (zcstr) {
            const w = parseWeekText(zcstr + "周");
            if (w.length) return w;
        }
        return parseWeekText(cleanText(row.zc) + "周");
    }

    function parseDay(item) {
        const d = parseNumber(item?.xingqi);
        return d !== null && d >= 1 && d <= 7 ? d : 1;
    }

    function parseStartSection(item) {
        const rqxl = cleanText(item?.rqxl);
        if (/^\d{3,4}$/.test(rqxl)) {
            const sec = Number(rqxl) % 100;
            if (sec >= 1 && sec <= 30) return sec;
        }
        const djc = parseNumber(item?.djc);
        return djc !== null && djc >= 1 && djc <= 30 ? djc : 1;
    }

    function buildCourseName(item) {
        const kcmc = cleanText(item?.kcmc);
        if (kcmc) return kcmc;
        const jxbmc = cleanText(item?.jxbmc);
        return jxbmc || "未命名课程";
    }

    function buildPosition(item) {
        const building = cleanText(item?.jxlmc);
        const room = cleanText(item?.croommc);
        const keepBuilding = window.WBU_KEEP_BUILDING === true;
        if (keepBuilding && building && room && !room.includes(building)) return `${building} ${room}`;
        return room || building || "";
    }

    // ===== 接口解析：按 天+节次 单元格分组并周次，合并连续节次/去重 =====
    function parseCoursesFromRows(rows) {
        const byCell = new Map();
        rows.forEach((row) => {
            const name = buildCourseName(row);
            const teacher = cleanText(row.tmc);
            const position = buildPosition(row);
            const day = parseDay(row);
            const djc = parseStartSection(row);
            const weeks = parseWeeksFromRow(row);
            if (!name || !weeks.length) return;
            const key = `${day}-${djc}`;
            if (!byCell.has(key)) byCell.set(key, new Map());
            const cell = byCell.get(key);
            const courseKey = `${name}\u0001${teacher}\u0001${position}`;
            if (!cell.has(courseKey)) cell.set(courseKey, new Set(weeks));
            else weeks.forEach((w) => cell.get(courseKey).add(w));
        });

        let maxDjc = 0;
        rows.forEach((row) => {
            const d = parseStartSection(row);
            if (Number.isFinite(d)) maxDjc = Math.max(maxDjc, d);
        });

        const out = [];
        for (let day = 1; day <= 7; day += 1) {
            let runStart = null;
            let runSig = "";
            const flushRun = (endSection) => {
                if (runStart === null || !runSig) { runStart = null; runSig = ""; return; }
                const cell = byCell.get(`${day}-${runStart}`);
                if (cell) {
                    cell.forEach((weeks, courseKey) => {
                        const parts = courseKey.split("\u0001");
                        if (parts.length === 3) {
                            out.push({
                                id: createId(),
                                name: parts[0],
                                teacher: parts[1],
                                position: parts[2],
                                day,
                                startSection: runStart,
                                endSection,
                                weeks: Array.from(weeks).sort((a, b) => a - b)
                            });
                        }
                    });
                }
                runStart = null;
                runSig = "";
            };
            for (let s = 1; s <= maxDjc + 1; s += 1) {
                const cell = byCell.get(`${day}-${s}`);
                const sig = cell
                    ? Array.from(cell.entries())
                        .map(([k, ws]) => `${k}|${Array.from(ws).sort((a, b) => a - b).join(",")}`)
                        .sort()
                        .join("\u0001")
                    : "";
                const ended = s === maxDjc + 1;
                if (runStart !== null && (ended || !sig || sig !== runSig)) flushRun(s - 1);
                if (!ended && sig && runStart === null) { runStart = s; runSig = sig; }
            }
        }
        return mergeAndDistinctCourses(out);
    }

    function mergeAndDistinctCourses(courses) {
        if (!Array.isArray(courses) || courses.length <= 1) return courses;
        const norm = courses.map((c) => ({
            ...c,
            name: c.name || "",
            teacher: c.teacher || "",
            position: c.position || "",
            weeks: (c.weeks || []).sort((a, b) => a - b)
        }));
        // 阶段1：连续节次合并 + 完全重复去重
        const s1 = norm.sort((a, b) =>
            a.name.localeCompare(b.name) ||
            a.teacher.localeCompare(b.teacher) ||
            a.position.localeCompare(b.position) ||
            a.day - b.day ||
            a.weeks.join(",").localeCompare(b.weeks.join(",")) ||
            (a.startSection || 0) - (b.startSection || 0));
        const step1 = [];
        let cur = s1[0];
        for (let i = 1; i < s1.length; i += 1) {
            const n = s1[i];
            const same = cur.name === n.name && cur.teacher === n.teacher &&
                cur.position === n.position && cur.day === n.day &&
                cur.weeks.join(",") === n.weeks.join(",");
            const continuous = cur.endSection + 1 === n.startSection;
            const duplicate = cur.startSection === n.startSection && cur.endSection === n.endSection;
            if (same && continuous) cur = { ...cur, endSection: n.endSection };
            else if (same && duplicate) { /* skip */ }
            else { step1.push(cur); cur = n; }
        }
        step1.push(cur);
        // 阶段2：同节次不同周次 -> 周次并集
        const s2 = step1.sort((a, b) =>
            a.name.localeCompare(b.name) ||
            a.teacher.localeCompare(b.teacher) ||
            a.position.localeCompare(b.position) ||
            a.day - b.day ||
            (a.startSection || 0) - (b.startSection || 0) ||
            (a.endSection || 0) - (b.endSection || 0));
        const step2 = [];
        let c = s2[0];
        for (let i = 1; i < s2.length; i += 1) {
            const n = s2[i];
            const sameSec = c.name === n.name && c.teacher === n.teacher &&
                c.position === n.position && c.day === n.day &&
                c.startSection === n.startSection && c.endSection === n.endSection;
            if (sameSec) c = { ...c, weeks: Array.from(new Set([...c.weeks, ...n.weeks])).sort((a, b) => a - b) };
            else { step2.push(c); c = n; }
        }
        step2.push(c);
        return step2;
    }

    // ===== DOM 回退解析（接口失败且在课表页时） =====
    function splitCourseBlocks(text) {
        return String(text).replace(/\r/g, "").split(/\n{2,}/).map((b) => b.trim()).filter(Boolean);
    }

    function extractWeeksFromLine(line) {
        const m = line.match(/(\d+(?:-\d+)?(?:,\d+(?:-\d+)?)*)\s*(?:\((单|双)\))?\s*周/);
        if (!m) return { weeks: "", rest: line };
        return { weeks: `${m[1]}${m[2] ? `(${m[2]})` : ""}周`, rest: line.replace(m[0], "") };
    }

    function parseCourseBlock(block) {
        const lines = block.split(/\n+/).map((l) => l.trim()).filter(Boolean);
        if (!lines.length) return null;
        let name = lines[0];
        let teacher = "";
        let weeksText = "";
        let position = "";
        const weekIdx = lines.findIndex((l) => /周/.test(l));
        if (weekIdx >= 0) {
            const r = extractWeeksFromLine(lines[weekIdx]);
            weeksText = r.weeks;
            if (weekIdx === 1) teacher = r.rest || lines[1];
        }
        if (!teacher && lines.length > 1) {
            teacher = lines[1];
            const r = extractWeeksFromLine(teacher);
            if (r.weeks) { weeksText = weeksText || r.weeks; teacher = r.rest; }
        }
        if (!weeksText) {
            for (const line of lines) {
                const r = extractWeeksFromLine(line);
                if (r.weeks) { weeksText = r.weeks; break; }
            }
        }
        if (!position) {
            if (weekIdx >= 0 && weekIdx + 1 < lines.length) position = lines[weekIdx + 1];
            if (!position) position = lines.find((l) => l !== name && l !== teacher && !/周/.test(l)) || "";
        }
        return { name: name || "未知课程", teacher: teacher || "", weeksText, position: position || "" };
    }

    function parseScheduleFromDocument(doc) {
        const cells = Array.from(doc.querySelectorAll("td.cell"));
        const fallback = cells.length ? [] : Array.from(doc.querySelectorAll("td[id^='Cell']"));
        const target = cells.length ? cells : fallback;
        const out = [];
        target.forEach((cell) => {
            const id = cell.getAttribute("id") || "";
            const m = id.match(/^Cell(\d)(\d{1,2})$/);
            if (!m) return;
            const day = parseInt(m[1], 10);
            const startSection = parseInt(m[2], 10);
            const rowspan = parseInt(cell.getAttribute("rowspan") || "1", 10);
            const endSection = startSection + Math.max(rowspan, 1) - 1;
            splitCourseBlocks(cell.innerText || "").forEach((block) => {
                const p = parseCourseBlock(block);
                if (!p) return;
                const weeks = parseWeekText(p.weeksText);
                if (!weeks.length) return;
                out.push({
                    id: createId(),
                    name: p.name,
                    teacher: p.teacher,
                    position: p.position,
                    day,
                    startSection,
                    endSection,
                    weeks
                });
            });
        });
        return mergeAndDistinctCourses(out);
    }

    // ===== 学期选择 =====
    function getCurrentXnxq(doc) {
        if (doc && !doc.querySelector && doc.xnxq) return doc.xnxq;
        const el = (doc || document).querySelector?.("#xnxq") || document.querySelector("#xnxq");
        return cleanText(el?.value || el?.textContent || "");
    }

    function readSemesterOptions(doc) {
        if (doc && !doc.querySelector && Array.isArray(doc.options)) return doc.options;
        const opts = [];
        (doc || document).querySelectorAll?.("#xnxq1 option").forEach((opt) => {
            const v = cleanText(opt.getAttribute("value"));
            const t = cleanText(opt.textContent);
            if (v && t) opts.push({ value: v, text: t });
        });
        return opts;
    }

    async function askChooseSemester(doc) {
        let options = [];
        for (let i = 0; i < 10 && !options.length; i += 1) {
            options = readSemesterOptions(doc);
            if (!options.length && document !== doc) options = readSemesterOptions(document);
            if (!options.length) await sleep(300);
        }
        if (!options.length || typeof window.AndroidBridgePromise.showSingleSelection !== "function") {
            return null;
        }
        const current = getCurrentXnxq(doc);
        const pages = [];
        for (let i = 0; i < options.length; i += SEMESTER_PAGE_SIZE) pages.push(options.slice(i, i + SEMESTER_PAGE_SIZE));
        let pageIndex = 0;
        while (pageIndex >= 0 && pageIndex < pages.length) {
            const page = pages[pageIndex];
            const isLast = pageIndex === pages.length - 1;
            const labels = page.map((o) => o.text);
            let moreIndex = -1;
            let backIndex = -1;
            if (!isLast) { moreIndex = labels.length; labels.push("更多…"); }
            if (pageIndex > 0) { backIndex = labels.length; labels.push("上一页"); }
            const defaultIndex = pageIndex === 0 ? Math.max(0, page.findIndex((o) => o.value === current)) : 0;
            let sel;
            try {
                sel = await window.AndroidBridgePromise.showSingleSelection(
                    pageIndex === 0 ? "请选择要导出的学年学期" : `更多学期（第 ${pageIndex + 1} 页 / 共 ${pages.length} 页）`,
                    JSON.stringify(labels),
                    defaultIndex
                );
            } catch (e) {
                console.error("学期选择失败，使用当前学期", e);
                return null;
            }
            if (sel === null) return CANCELED;
            if (sel === moreIndex) { pageIndex += 1; continue; }
            if (sel === backIndex) { pageIndex -= 1; continue; }
            const chosen = page[sel];
            return chosen ? chosen.value : null;
        }
        return null;
    }

    // ===== 校区 =====
    async function fetchCampusList() {
        try {
            const r = await fetchJson("/admin/api/jcsj/xqsj/getXqList", { method: "GET", headers: DEFAULT_HEADERS });
            const json = r || {};
            if (Number(json?.ret) !== 0) return [];
            return (json.data || []).map((c) => ({ id: c.id, name: c.xqmc })).filter((c) => c.id);
        } catch (e) {
            return [];
        }
    }

    async function fetchSemesterConfigRaw(xnxq, xqid) {
        const q = new URLSearchParams({ xnxq, role: "", userId: "", xqid: xqid || "" });
        const json = await fetchJson(`/admin/api/getZclistByXnxq?${q.toString()}`, { method: "GET", headers: DEFAULT_HEADERS });
        const data = (json && json.data) || {};
        const zclist = data.zclist || [];
        if (!zclist.length) return null;
        const sorted = zclist.slice().sort((a, b) => parseInt(a.zc, 10) - parseInt(b.zc, 10));
        const startDate = sorted[0]?.minrq ? String(sorted[0].minrq).slice(0, 10) : "";
        const totalWeeks = sorted.length ? Math.max(...sorted.map((w) => parseInt(w.zc, 10))) : sorted.length;
        const timeSlots = (data.jcsjszList || []).map((j) => ({
            number: parseInt(j.jc, 10),
            startTime: padTime(j.kssj),
            endTime: padTime(j.jssj)
        })).filter((s) => Number.isFinite(s.number) && s.startTime && s.endTime).sort((a, b) => a.number - b.number);
        return { semesterStartDate: startDate, semesterTotalWeeks: totalWeeks, timeSlots };
    }

    function sameConfig(a, b, maxSection) {
        if (!a || !b) return false;
        if (a.semesterStartDate !== b.semesterStartDate) return false;
        if (a.semesterTotalWeeks !== b.semesterTotalWeeks) return false;
        const limit = Number.isFinite(maxSection) && maxSection > 0 ? maxSection : Infinity;
        const t1 = (a.timeSlots || []).filter((s) => s.number <= limit);
        const t2 = (b.timeSlots || []).filter((s) => s.number <= limit);
        if (t1.length !== t2.length) return false;
        return t1.every((s, i) => s.number === t2[i].number && s.startTime === t2[i].startTime && s.endTime === t2[i].endTime);
    }

    async function resolveCampusId(doc, xnxq, defaultId, maxSection) {
        const campuses = await fetchCampusList();
        if (campuses.length <= 1) return defaultId;
        const configs = [];
        for (const c of campuses) {
            try {
                const cfg = await fetchSemesterConfigRaw(xnxq, c.id);
                configs.push({ campus: c, cfg });
            } catch (e) { /* ignore */ }
        }
        const first = configs[0]?.cfg;
        const allSame = configs.every((x) => sameConfig(x.cfg, first, maxSection));
        if (allSame) return defaultId;
        const bridge = window.AndroidBridgePromise || window.shiguangBridgePromise;
        if (!bridge || typeof bridge.showSingleSelection !== "function") return defaultId;
        const labels = campuses.map((c) => c.name);
        const defaultIndex = Math.max(0, campuses.findIndex((c) => c.id === defaultId));
        let sel;
        try {
            sel = await bridge.showSingleSelection(
                "检测到多个校区且作息不同，请选择要导出的校区",
                JSON.stringify(labels),
                defaultIndex
            );
        } catch (e) { return defaultId; }
        if (sel === null) return CANCELED;
        const chosen = campuses[Number(sel)];
        return chosen ? chosen.id : defaultId;
    }

    // ===== 网络工具 =====
    async function fetchJson(url, options = {}) {
        const r = await fetch(url, { credentials: "include", ...options });
        if (!r.ok) throw new Error(`HTTP ${r.status}: ${url}`);
        return await r.json();
    }

    async function fetchText(url, options = {}) {
        const r = await fetch(url, { credentials: "include", ...options });
        if (!r.ok) throw new Error(`HTTP ${r.status}: ${url}`);
        return await r.text();
    }

    function extractHiddenValue(html, fieldId) {
        const m = html.match(new RegExp(`id="${fieldId}"[^>]*value="([^"]+)"`, "i"));
        return m && m[1] ? m[1] : "";
    }

    function padTime(value) {
        const t = cleanText(value);
        const m = t.match(/^(\d{1,2}):(\d{1,2})/);
        if (!m) return t;
        return `${String(Number(m[1])).padStart(2, "0")}:${String(Number(m[2])).padStart(2, "0")}`;
    }

    function createId() {
        if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") return crypto.randomUUID();
        return `id-${Date.now()}-${Math.random().toString(16).slice(2, 10)}`;
    }

    function maxSectionFromCourses(courses) {
        return courses.reduce((m, c) => Math.max(m, c.endSection || 0), 0);
    }

    // 不在课表页时抓课表页参数
    async function fetchSchedulePageSession() {
        const base = "/admin/xsd/pkgl/xskb/queryKbForXsd";
        const candidates = [];
        const urlXnxq = new URLSearchParams(location.search).get("xnxq");
        if (urlXnxq) candidates.push(`${base}?xnxq=${encodeURIComponent(urlXnxq)}`);
        const fromApi = await resolveCurrentXnxqFromApi();
        if (fromApi) candidates.push(`${base}?xnxq=${encodeURIComponent(fromApi)}`);
        candidates.push(base);
        const seen = new Set();
        for (const url of candidates) {
            if (seen.has(url)) continue;
            seen.add(url);
            try {
                const html = await fetchText(url);
                const xhid = extractHiddenValue(html, "xhid");
                const xqdm = extractHiddenValue(html, "xqdm");
                const xnxq = extractHiddenValue(html, "xnxq");
                const options = [];
                const sel = html.match(/<select[^>]*id="xnxq1"[^>]*>([\s\S]*?)<\/select>/);
                if (sel) {
                    const re = /<option value="([^"]*)"[^>]*>([^<]*)<\/option>/g;
                    let m;
                    while ((m = re.exec(sel[1]))) {
                        const v = m[1].trim();
                        const t = m[2].trim();
                        if (v && t) options.push({ value: v, text: t });
                    }
                }
                if (xhid && xqdm && options.length) return { xhid, xqdm, xnxq, options };
            } catch (e) { console.warn("抓取课表页参数失败", url, e); }
        }
        return null;
    }

    async function resolveCurrentXnxqFromApi() {
        try {
            const r = await fetchJson("/admin/xsd/xsdcjcx/getCurrentXnxq", { method: "GET", headers: DEFAULT_HEADERS });
            return cleanText(r?.data) || "";
        } catch (e) {
            return "";
        }
    }

    // 获取真实的纯数字学号（绝非系统后台内部的流水号 xhid）
    function getActualStudentId(doc, rows) {
        // 1. 优先读取 Cookie 中的 username（超星教务登录后必定写入真实的学号）
        try {
            const cookieMatch = document.cookie
                .split(";")
                .map((s) => s.trim())
                .find((c) => c.startsWith("username="));
            if (cookieMatch) {
                const val = decodeURIComponent(cookieMatch.split("=")[1] || "").trim();
                if (val && /^\d{5,}$/.test(val)) return val;
            }
        } catch (e) {}

        // 2. 从页面顶栏或者信息展示区获取（<span class="admin_name">250594036</span>）
        try {
            const root = doc || document;
            const el = root.querySelector(".admin_name, #admin_name, span[class*='admin_name']");
            if (el) {
                const text = (el.textContent || "").trim();
                const m = text.match(/\b(\d{5,})\b/);
                if (m) return m[1];
            }
        } catch (e) {}

        // 3. 从接口返回行中提取（部分超星版本返回带 xh 字段）
        if (Array.isArray(rows) && rows.length > 0) {
            for (const r of rows) {
                const xh = String(r.xh || "").trim();
                if (xh && /^\d{5,}$/.test(xh)) return xh;
            }
        }

        return "";
    }

    async function main() {
        const bridgePromise = window.AndroidBridgePromise || window.shiguangBridgePromise;
        if (!bridgePromise) throw new Error("BridgePromise is missing in this WebView");
        showToast("WBU 导入已开始");

        // 1. API 优先：直接从后台请求课表页参数（获取权威的 xhid、xqdm、xnxq 及可选学期列表）
        let session = await fetchSchedulePageSession();
        let doc = null;

        // 若 API 请求失败或缺少关键字段，fallback 到从网页 DOM/iframe 获取
        if (!session || !session.xhid || !session.xqdm) {
            if (window.location.href.includes("queryKbForXsd")) {
                doc = document;
            } else {
                const iframe = document.querySelector("iframe[src*='queryKbForXsd']");
                if (iframe) {
                    for (let i = 0; i < 20; i += 1) {
                        try {
                            const d = iframe.contentDocument || iframe.contentWindow?.document;
                            if (d && d.readyState && d.readyState !== "loading") { doc = d; break; }
                        } catch (e) { /* ignore */ }
                        await sleep(500);
                    }
                }
            }
        }

        const semDoc = doc;
        const subjectDoc = session || semDoc;
        if (!subjectDoc) {
            showToast("未找到课表页面，请手动打开“我的课表”后重试");
            return;
        }

        const chosenXnxq = await askChooseSemester(subjectDoc);
        if (chosenXnxq === CANCELED) { showToast("已取消，终止导入"); return; }
        const currentXnxq = session?.xnxq || (semDoc ? getCurrentXnxq(semDoc) : "");
        const exportXnxq = chosenXnxq || currentXnxq;
        if (!exportXnxq) { showToast("无法识别当前学期参数"); return; }

        const xhid = session?.xhid || (semDoc && (semDoc.querySelector("#xhid")?.value || "")) || "";
        const xqdm = session?.xqdm || (semDoc && (semDoc.querySelector("#xqdm")?.value || "")) || "";

        let rows = [];
        try {
            const params = new URLSearchParams({ xnxq: exportXnxq, xhid, xqdm, zdzc: "", zxzc: "", xskbxslx: "0" });
            const json = await fetchJson(`/admin/xsd/pkgl/xskb/sdpkkbList?${params.toString()}`, { method: "GET", headers: DEFAULT_HEADERS });
            if (Number(json?.ret) === 0 && Array.isArray(json?.data)) rows = json.data;
        } catch (e) {
            console.warn("sdpkkbList 请求失败", e);
        }

        let courses = [];
        if (rows.length) {
            courses = parseCoursesFromRows(rows);
        } else if (semDoc) {
            // 接口失败且当前在课表页：回退解析 DOM
            const cells = Array.from(semDoc.querySelectorAll("td.cell")).filter((c) => /周/.test(c.innerText || ""));
            if (cells.length) courses = parseScheduleFromDocument(semDoc);
        }
        if (!courses.length) { showToast("未解析到课程，请确认课表已加载完成"); return; }

        const maxSection = maxSectionFromCourses(courses);
        const campusId = await resolveCampusId(semDoc, exportXnxq, xqdm, maxSection);
        if (campusId === CANCELED) { showToast("已取消，终止导入"); return; }

        const semesterConfig = await fetchSemesterConfigRaw(exportXnxq, campusId) || {};
        const timeSlots = semesterConfig.timeSlots || [];

        const startSection = courses[0]?.startSection;
        void startSection;

        await bridgePromise.saveImportedCourses(JSON.stringify(courses));
        if (timeSlots.length) await bridgePromise.savePresetTimeSlots(JSON.stringify(timeSlots));
        if (semesterConfig.semesterStartDate || semesterConfig.semesterTotalWeeks) {
            await bridgePromise.saveCourseConfig(JSON.stringify({
                semesterStartDate: semesterConfig.semesterStartDate || "",
                semesterTotalWeeks: semesterConfig.semesterTotalWeeks || 20,
                firstDayOfWeek: 1
            }));
        }

        // 回写元数据（学号与学期），打通后续主界面右上角一键自动同步与“我的课表”自动规范重命名
        try {
            const actualStudentId = getActualStudentId(semDoc, rows);
            if (actualStudentId) {
                console.log("解析到学生真实学号:", actualStudentId);
            }
            const metaPayload = JSON.stringify({
                studentId: actualStudentId || "",
                semesterCode: exportXnxq
            });
            if (typeof bridgePromise.saveTableMeta === "function") {
                await bridgePromise.saveTableMeta(metaPayload);
            } else if (window.AndroidBridge && typeof window.AndroidBridge.saveTableMeta === "function") {
                const promiseId = "meta_" + Date.now();
                window.AndroidBridge.saveTableMeta(metaPayload, promiseId);
            }
        } catch (e) {
            console.warn("保存课表元数据失败", e);
        }

        showToast(`导入完成：${courses.length} 门课程`);
        (window.AndroidBridge || window.shiguangBridge)?.notifyTaskCompletion();
    }

    main().catch((error) => {
        console.error("[WBU] 导入失败", error);
        showToast(`导入失败：${error.message || "未知错误"}`);
    });
})();
