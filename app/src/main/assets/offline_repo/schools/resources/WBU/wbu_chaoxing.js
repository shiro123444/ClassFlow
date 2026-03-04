(() => {
    const DEFAULT_HEADERS = {
        "X-Requested-With": "XMLHttpRequest",
        "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8"
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

    function showToast(message) {
        try {
            window.AndroidBridge?.showToast(message);
        } catch (error) {
            console.log("[WBU] toast failed", error);
        }
    }

    function cleanText(value) {
        if (!value) {
            return "";
        }

        const text = String(value)
            .replace(/<[^>]+>/g, "")
            .replace(/&nbsp;/g, " ")
            .replace(/&amp;/g, "&")
            .replace(/&lt;/g, "<")
            .replace(/&gt;/g, ">")
            .replace(/\s+/g, " ")
            .trim();

        return text;
    }

    function parseNumber(value) {
        if (value === null || value === undefined) {
            return null;
        }

        const number = Number(String(value).trim());
        if (!Number.isFinite(number)) {
            return null;
        }

        return number;
    }

    function uniqueSortedNumbers(list) {
        const result = Array.from(new Set(list.filter(n => Number.isInteger(n) && n > 0)));
        result.sort((a, b) => a - b);
        return result;
    }

    function expandRangeToken(token) {
        const normalized = token.trim();
        if (!normalized) {
            return [];
        }

        const single = parseNumber(normalized);
        if (single !== null) {
            return [single];
        }

        const rangeMatch = normalized.match(/^(\d+)\s*[-~]\s*(\d+)$/);
        if (!rangeMatch) {
            return [];
        }

        let start = Number(rangeMatch[1]);
        let end = Number(rangeMatch[2]);
        if (start > end) {
            const temp = start;
            start = end;
            end = temp;
        }

        const result = [];
        for (let week = start; week <= end; week += 1) {
            result.push(week);
        }
        return result;
    }

    function parseWeeks(item) {
        const weeksFromZcstr = cleanText(item?.zcstr)
            .split(",")
            .map(token => parseNumber(token))
            .filter(week => Number.isInteger(week) && week > 0);

        if (weeksFromZcstr.length > 0) {
            return uniqueSortedNumbers(weeksFromZcstr);
        }

        const zcText = cleanText(item?.zc);
        if (!zcText) {
            return [];
        }

        const oddOnly = zcText.includes("单");
        const evenOnly = zcText.includes("双");
        const normalized = zcText.replace(/[^\d,\-~]/g, "");
        const baseWeeks = normalized
            .split(",")
            .flatMap(token => expandRangeToken(token));

        const filtered = baseWeeks.filter(week => {
            if (oddOnly) {
                return week % 2 === 1;
            }
            if (evenOnly) {
                return week % 2 === 0;
            }
            return true;
        });

        return uniqueSortedNumbers(filtered);
    }

    function parseDay(item) {
        const day = parseNumber(item?.xingqi);
        if (day !== null && day >= 1 && day <= 7) {
            return day;
        }
        return 1;
    }

    function parseStartSection(item) {
        const rqxl = cleanText(item?.rqxl);
        if (/^\d{3,4}$/.test(rqxl)) {
            // rqxl 格式: 第一位是星期，后两位是节次
            // 例: 101 = 周一第1节, 305 = 周三第5节
            const number = Number(rqxl);
            const section = number % 100;  // 取后两位作为节次
            if (section >= 1 && section <= 30) {
                return section;
            }
        }

        const djc = parseNumber(item?.djc);
        if (djc !== null && djc >= 1 && djc <= 30) {
            return djc;
        }

        return 1;
    }

    function parseEndSection(item, startSection) {
        const djs = parseNumber(item?.djs);
        if (djs !== null && djs > 0 && djs <= 8) {
            return startSection + djs - 1;
        }
        return startSection;
    }

    function buildCourseName(item) {
        const fromKcmc = cleanText(item?.kcmc);
        if (fromKcmc) {
            return fromKcmc;
        }

        const fromJxbmc = cleanText(item?.jxbmc);
        if (fromJxbmc) {
            return fromJxbmc;
        }

        return "未命名课程";
    }

    function buildTeacher(item) {
        const teacher = cleanText(item?.tmc);
        return teacher || "";
    }

    function buildPosition(item) {
        const building = cleanText(item?.jxlmc);
        const room = cleanText(item?.croommc);

        if (building && room) {
            if (room.includes(building)) {
                return room;
            }
            return `${building} ${room}`;
        }

        return room || building || "";
    }

    function toImportCourse(item) {
        const startSection = parseStartSection(item);
        const endSection = parseEndSection(item, startSection);
        const weeks = parseWeeks(item);

        return {
            id: cleanText(item?.id) || "",  // 娣诲姞璇剧▼ID
            name: buildCourseName(item),
            teacher: buildTeacher(item),
            position: buildPosition(item),
            day: parseDay(item),
            startSection,
            endSection,
            weeks
        };
    }

    function pickSemesterFromLocation() {
        try {
            const currentUrl = new URL(window.location.href);
            const fromQuery = currentUrl.searchParams.get("xnxq");
            if (fromQuery) {
                return fromQuery;
            }
        } catch (error) {
            console.log("[WBU] parse location xnxq failed", error);
        }

        return null;
    }

    async function fetchJson(url, options = {}) {
        const response = await fetch(url, {
            credentials: "include",
            ...options
        });

        if (!response.ok) {
            throw new Error(`HTTP ${response.status}: ${url}`);
        }

        return await response.json();
    }

    async function fetchText(url, options = {}) {
        const response = await fetch(url, {
            credentials: "include",
            ...options
        });

        if (!response.ok) {
            throw new Error(`HTTP ${response.status}: ${url}`);
        }

        return await response.text();
    }

    async function resolveSemester() {
        const fromLocation = pickSemesterFromLocation();
        if (fromLocation) {
            return fromLocation;
        }

        const currentXnxq = await fetchJson("/admin/xsd/xsdcjcx/getCurrentXnxq", {
            method: "GET",
            headers: {
                "X-Requested-With": "XMLHttpRequest"
            }
        });

        const fromApi = cleanText(currentXnxq?.data);
        if (fromApi) {
            return fromApi;
        }

        throw new Error("鏃犳硶璇嗗埆褰撳墠瀛︽湡鍙傛暟 xnxq");
    }

    function extractHiddenValue(html, fieldId) {
        const pattern = new RegExp(`id="${fieldId}"[^>]*value="([^"]+)"`, "i");
        const match = html.match(pattern);
        return match && match[1] ? match[1] : "";
    }

    async function resolveXhidAndXqdm(xnxq) {
        const html = await fetchText(`/admin/xsd/pkgl/xskb/queryKbForXsd?xnxq=${encodeURIComponent(xnxq)}`, {
            method: "GET"
        });

        const xhid = extractHiddenValue(html, "xhid");
        const xqdm = extractHiddenValue(html, "xqdm");

        if (!xhid || !xqdm) {
            throw new Error("鏈兘浠?queryKbForXsd 椤甸潰鎻愬彇 xhid/xqdm");
        }

        return { xhid, xqdm };
    }

    async function fetchWbuCourses(xnxq, xhid, xqdm) {
        const params = new URLSearchParams({
            xnxq,
            xhid,
            xqdm,
            zdzc: "",
            zxzc: "",
            xskbxslx: "0"
        });

        const json = await fetchJson(`/admin/xsd/pkgl/xskb/sdpkkbList?${params.toString()}`, {
            method: "GET",
            headers: {
                "X-Requested-With": "XMLHttpRequest"
            }
        });

        if (Number(json?.ret) !== 0 || !Array.isArray(json?.data)) {
            throw new Error(`sdpkkbList 杩斿洖寮傚父: ${json?.msg || "鏈煡閿欒"}`);
        }

        return json.data;
    }

    async function fetchCurrentWeek() {
        const json = await fetchJson("/admin/getCurrentPkZc", {
            method: "GET",
            headers: {
                "X-Requested-With": "XMLHttpRequest"
            }
        });

        if (!Array.isArray(json?.data) || json.data.length === 0) {
            return 1;
        }

        const week = parseNumber(json.data[0]);
        return week !== null && week > 0 ? week : 1;
    }

    function normalizeTime(value) {
        const text = cleanText(value);
        const match = text.match(/^(\d{1,2}):(\d{1,2})$/);
        if (!match) {
            return "";
        }

        const hour = String(Number(match[1])).padStart(2, "0");
        const minute = String(Number(match[2])).padStart(2, "0");
        return `${hour}:${minute}`;
    }

    async function fetchWbuTimeSlots() {
        try {
            const currentWeek = await fetchCurrentWeek();
            const body = new URLSearchParams({ type: "1", zc: String(currentWeek) });

            const json = await fetchJson("/admin/getXsdSykb", {
                method: "POST",
                headers: DEFAULT_HEADERS,
                body: body.toString()
            });

            const list = Array.isArray(json?.data?.jcKcxx) ? json.data.jcKcxx : [];
            const parsed = list.map(item => {
                const number = parseNumber(item?.jcbm ?? item?.jc);
                const startTime = normalizeTime(item?.kssj);
                const endTime = normalizeTime(item?.jssj);

                return {
                    number,
                    startTime,
                    endTime
                };
            }).filter(item => Number.isInteger(item.number) && item.number > 0 && item.startTime && item.endTime);

            if (parsed.length > 0) {
                parsed.sort((a, b) => a.number - b.number);
                return parsed;
            }
        } catch (error) {
            console.log("[WBU] load time slots failed, fallback default", error);
        }

        return WBU_TIME_SLOTS_FALLBACK;
    }

    function mergeContinuousSections(courses) {
        if (!Array.isArray(courses) || courses.length === 0) {
            return [];
        }

        const sorted = [...courses].sort((a, b) =>
            a.day - b.day ||
            JSON.stringify(a.weeks).localeCompare(JSON.stringify(b.weeks)) ||
            a.name.localeCompare(b.name) ||
            a.teacher.localeCompare(b.teacher) ||
            a.position.localeCompare(b.position) ||
            a.startSection - b.startSection
        );

        const merged = [];
        let i = 0;

        while (i < sorted.length) {
            const current = sorted[i];
            let endSection = current.endSection;
            let j = i + 1;

            while (j < sorted.length) {
                const next = sorted[j];

                if (
                    next.day === current.day &&
                    next.name === current.name &&
                    next.teacher === current.teacher &&
                    next.position === current.position &&
                    JSON.stringify(next.weeks) === JSON.stringify(current.weeks) &&
                    next.startSection === endSection + 1
                ) {
                    endSection = next.endSection;
                    j += 1;
                } else {
                    break;
                }
            }

            merged.push({
                id: current.id,
                name: current.name,
                teacher: current.teacher,
                position: current.position,
                day: current.day,
                startSection: current.startSection,
                endSection,
                weeks: current.weeks
            });

            i = j;
        }

        console.log(`[WBU] merge done: ${courses.length} -> ${merged.length}`);
        return merged;
    }

    async function main() {
        if (!window.AndroidBridgePromise) {
            throw new Error("AndroidBridgePromise is missing in this WebView");
        }

        showToast("WBU import started");

        const xnxq = await resolveSemester();
        const { xhid, xqdm } = await resolveXhidAndXqdm(xnxq);

        const rawCourses = await fetchWbuCourses(xnxq, xhid, xqdm);
        console.log("[WBU] raw courses:", rawCourses.length);
        if (rawCourses.length > 0) {
            console.log("[WBU] first raw:", JSON.stringify(rawCourses[0]));
        }

        const mappedCourses = rawCourses
            .map(item => toImportCourse(item))
            .filter(item => item.name && item.weeks.length > 0);

        console.log("[WBU] mapped courses:", mappedCourses.length);
        if (mappedCourses.length > 0) {
            console.log("[WBU] first mapped:", JSON.stringify(mappedCourses[0]));
        }

        if (mappedCourses.length === 0) {
            throw new Error("No importable courses found on current page");
        }

        const mergedCourses = mergeContinuousSections(mappedCourses);
        const timeSlots = await fetchWbuTimeSlots();

        await window.AndroidBridgePromise.saveImportedCourses(JSON.stringify(mergedCourses));

        if (timeSlots.length > 0) {
            await window.AndroidBridgePromise.savePresetTimeSlots(JSON.stringify(timeSlots));
        }

        showToast(`Import completed: ${mergedCourses.length} courses`);
        window.AndroidBridge.notifyTaskCompletion();
    }

    main().catch(error => {
        console.error("[WBU] import failed", error);
        showToast(`Import failed: ${error.message || "unknown error"}`);
    });
})();
