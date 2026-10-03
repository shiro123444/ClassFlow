#!/usr/bin/env python3
"""把「用 <mask> 做镂空」的 SVG 转成 Android VectorDrawable（纯矢量 XML）。

为什么不能直接用 <clip-path>：
    Android 的 VectorDrawableClipPath 里只有 name / pathData 两个属性，
    不读 android:fillType（fillType 只对 <path> 生效）。也就是说 <clip-path> 永远按
    nonzero 缠绕规则裁剪，像本图这种「圆盘挖掉编钟轮廓 + 字形」的镂空会失效，
    在设备上会渲染成一整个实心圆。所以这里把布尔运算烘焙进路径本身：

        fill = (圆盘 ∩ mask 白) − mask 黑

    用 nonzero 绕向表达：圆盘保留原始曲线与方向，被挖掉的部分外轮廓反向（挖空）、
    内轮廓保持同向（多轮廓形状里的“岛”要重新填上）。

    轮廓的并集/差集用 shapely 以多边形近似（贝塞尔按 tol 展平），圆盘本身仍是原始曲线，
    所以外圆在设备上依旧光滑。

依赖：pip install shapely
用法：python scripts/svg-to-android-vector.py in.svg out.xml [tol=0.02] [precision=3]
"""

import collections
import math
import re
import sys

from shapely.geometry import Polygon
from shapely.ops import unary_union
from shapely.validation import make_valid

TOKEN = re.compile(r"[MmLlHhVvCcSsQqTtAaZz]|-?\d*\.?\d+(?:e-?\d+)?")


# ---------------------------------------------------------------- 路径解析 / 展平
def parse_path(d):
    """-> [{'start': (x, y), 'items': [('L', p) | ('C', c1, c2, p)], 'closed': bool}]"""
    toks = TOKEN.findall(d)
    i = 0
    cmd = None
    subs = []
    start = cur = None
    items = []

    def take(n):
        nonlocal i
        v = [float(toks[i + k]) for k in range(n)]
        i += n
        return v

    while i < len(toks):
        t = toks[i]
        if t.isalpha():
            cmd = t
            i += 1
        elif cmd == "M":  # 省略后续命令时，M 的额外坐标对是隐式 lineto
            cmd = "L"
        elif cmd == "m":
            cmd = "l"
        rel, c = cmd.islower(), cmd.upper()

        if c == "Z":
            subs.append({"start": start, "items": items, "closed": True})
            start = cur = None
            items = []
            continue
        if c == "M":
            x, y = take(2)
            if rel and cur:
                x, y = x + cur[0], y + cur[1]
            if start is not None:
                subs.append({"start": start, "items": items, "closed": False})
            start = cur = (x, y)
            items = []
        elif c == "L":
            x, y = take(2)
            if rel:
                x, y = x + cur[0], y + cur[1]
            cur = (x, y)
            items.append(("L", cur))
        elif c == "C":
            p1, p2, p3 = take(2), take(2), take(2)
            if rel:
                p1 = (p1[0] + cur[0], p1[1] + cur[1])
                p2 = (p2[0] + cur[0], p2[1] + cur[1])
                p3 = (p3[0] + cur[0], p3[1] + cur[1])
            items.append(("C", p1, p2, p3))
            cur = p3
        else:
            raise SystemExit(f"暂不支持的路径命令: {cmd}")
    if start is not None and items:
        subs.append({"start": start, "items": items, "closed": False})
    return subs


def _flat_cubic(p0, p1, p2, p3, tol, out, depth=0):
    dx, dy = p3[0] - p0[0], p3[1] - p0[1]
    d = math.hypot(dx, dy)
    if d < 1e-9:
        dev = max(math.hypot(p1[0] - p0[0], p1[1] - p0[1]),
                  math.hypot(p2[0] - p0[0], p2[1] - p0[1]))
    else:
        dev = max(
            abs((p1[0] - p0[0]) * dy - (p1[1] - p0[1]) * dx),
            abs((p2[0] - p0[0]) * dy - (p2[1] - p0[1]) * dx),
        ) / d
    if dev <= tol or depth >= 18:
        out.append(p3)
        return

    def mid(a, b):
        return ((a[0] + b[0]) / 2, (a[1] + b[1]) / 2)

    a, b, c = mid(p0, p1), mid(p1, p2), mid(p2, p3)
    a1, b1 = mid(a, b), mid(b, c)
    m = mid(a1, b1)
    _flat_cubic(p0, a, a1, m, tol, out, depth + 1)
    _flat_cubic(m, b1, c, p3, tol, out, depth + 1)


def flatten(sub, tol):
    pts = [sub["start"]]
    cur = sub["start"]
    for it in sub["items"]:
        if it[0] == "L":
            cur = it[1]
            pts.append(cur)
        else:
            _flat_cubic(cur, it[1], it[2], it[3], tol, pts)
            cur = it[3]
    return pts


def shoelace(pts):
    return sum(
        pts[i][0] * pts[(i + 1) % len(pts)][1] - pts[(i + 1) % len(pts)][0] * pts[i][1]
        for i in range(len(pts))
    ) / 2


# ---------------------------------------------------------------- 坐标平移烘焙
def fmt(v, prec):
    s = f"{v:.{prec}f}".rstrip("0").rstrip(".")
    return "0" if s in ("-0", "") else s


def bake_translate(d, dx, dy, prec):
    """把 <g transform="translate()"> 烘焙进绝对坐标（只处理 M/L/C/Z）。"""
    toks = TOKEN.findall(d)
    i = 0
    cmd = None
    out = []
    while i < len(toks):
        t = toks[i]
        if t.isalpha():
            cmd = t
            i += 1
            out.append(t)
        elif cmd == "M":
            cmd = "L"
            out.append("L")
        elif cmd == "m":
            cmd = "l"
            out.append("l")
        rel, c = cmd.islower(), cmd.upper()
        if c == "Z":
            continue
        pairs = {"M": 1, "L": 1, "C": 3}[c]
        vals = [float(toks[i + k]) for k in range(pairs * 2)]
        i += pairs * 2
        if not rel:
            for k in range(pairs):
                vals[2 * k] += dx
                vals[2 * k + 1] += dy
        out.append(" " + " ".join(fmt(v, prec) for v in vals))
    s = re.sub(r"\s+", " ", "".join(out))
    return re.sub(r"([A-Za-z]) ", r"\1", s).strip()


# ---------------------------------------------------------------- 主流程
def main():
    if len(sys.argv) < 3:
        raise SystemExit(__doc__)
    src, dst = sys.argv[1], sys.argv[2]
    tol = float(sys.argv[3]) if len(sys.argv) > 3 else 0.02
    prec = int(sys.argv[4]) if len(sys.argv) > 4 else 3

    svg = open(src, encoding="utf-8").read()

    view = re.search(r'viewBox="([-\d.eE ]+)"', svg)
    if not view:
        raise SystemExit("SVG 缺少 viewBox")
    vx, vy, vw, vh = (float(v) for v in view.group(1).split())

    # 只支持纯 translate 的 <g>（这类导出文件基本都是）：取出现次数最多的那个平移量
    translates = collections.Counter(
        (float(m.group(1)), float(m.group(2)))
        for m in re.finditer(r'transform="translate\(([-\d.eE]+)[ ,]+([-\d.eE]+)\)"', svg)
    )
    if len(translates) > 1:
        raise SystemExit(f"存在多种 translate，需手动处理：{dict(translates)}")
    tx, ty = translates.most_common(1)[0][0] if translates else (0.0, 0.0)
    dx, dy = tx - vx, ty - vy

    def attr(chunk, name):
        m = re.search(name + r'="([^"]*)"', chunk)
        return m.group(1) if m else None

    mask_spans = [(m.start(), m.end()) for m in re.finditer(r"<mask[^>]*>.*?</mask>", svg, re.S)]
    if not mask_spans:
        raise SystemExit("没找到 <mask>，这个脚本只处理 mask 镂空的图")

    # 扫一遍标签栈，记录每个 <path> 继承到的 id / fill / mask（fill 常写在 <g> 上）
    stack = []
    paths = []
    for m in re.finditer(r"<g\b[^>]*>|</g\s*>|<path\b[^>]*?/?>|<mask\b[^>]*>|</mask\s*>", svg, re.S):
        tag = m.group(0)
        if tag.startswith("</"):
            if stack:
                stack.pop()
            continue
        if tag.startswith("<g") or tag.startswith("<mask"):
            stack.append({
                "id": attr(tag, "id"),
                "fill": attr(tag, "fill"),
                "mask": attr(tag, "mask"),
            })
            continue
        top = stack[-1] if stack else {}
        paths.append({
            "chunk": tag,
            "id": attr(tag, "id") or top.get("id"),
            "fill": attr(tag, "fill") or top.get("fill"),
            "in_mask": any(a <= m.start() < b for a, b in mask_spans),
            "in_masked_group": any(g.get("mask") for g in stack),
        })
    if not paths:
        raise SystemExit("没找到 <path>")

    d_of = lambda p: attr(p["chunk"], "d")

    mask_paths = [p for p in paths if p["in_mask"]]
    white = [d_of(p) for p in mask_paths
             if (p["fill"] or "#000000").upper() in ("#FFFFFF", "#FFF", "WHITE")]
    black = [d_of(p) for p in mask_paths if d_of(p) not in white]
    if not white:
        raise SystemExit("mask 里没有白色（保留）路径")

    body_paths = [p for p in paths if p["in_masked_group"] and not p["in_mask"]]
    if not body_paths:
        raise SystemExit("没找到使用 mask 的 <g> 里的圆盘路径")
    body_d = d_of(body_paths[0])
    body_fill = body_paths[0]["fill"] or "#000000"
    body_name = body_paths[0]["id"] or "disk"

    foreground = [(p["id"] or f"detail{i + 1}", p["fill"] or "#000000", d_of(p))
                  for i, p in enumerate(paths)
                  if not p["in_mask"] and not p["in_masked_group"]]

    def to_poly(d):
        # 一条 path 里的多个子路径按「并集」处理（镂空图上嵌套子路径只会多切掉本该
        # 也透明的内部区域，对最终显示无影响），这也和浏览器/系统按 nonzero 填充的结果一致。
        polys = []
        for sub in parse_path(d):
            poly = Polygon(flatten(sub, tol))
            polys.append(make_valid(poly) if not poly.is_valid else poly)
        return unary_union(polys)

    mask_shape = unary_union([to_poly(d) for d in white])
    black_shape = unary_union([to_poly(d) for d in black])

    # 可见几何 = (圆盘 ∩ mask 白) − mask 黑；被挖掉的部分 = 圆盘 − 可见几何
    circle_sub = parse_path(body_d)[0]
    circle_pts = flatten(circle_sub, tol)
    sign = shoelace(circle_pts)
    circle_poly = Polygon(circle_pts)
    visible = circle_poly.intersection(mask_shape).difference(black_shape)
    cut = make_valid(circle_poly.difference(visible)).buffer(0)

    def ring(pts, want_sign):
        pts = list(pts)
        if pts[0] != pts[-1]:
            pts.append(pts[0])
        if (shoelace(pts[:-1]) > 0) != (want_sign > 0):
            pts = pts[::-1]
        return "M" + "L".join(f"{fmt(x + dx, prec)} {fmt(y + dy, prec)}" for x, y in pts) + "Z"

    polys = [cut] if cut.geom_type == "Polygon" else list(cut.geoms)
    polys = [p for p in polys
             if p.geom_type == "Polygon" and not p.is_empty and len(p.exterior.coords) > 1]
    disk_d = bake_translate(body_d, dx, dy, prec) + "".join(
        [ring(p.exterior.coords, -sign) for p in polys]
        + [ring(r.coords, sign) for p in polys for r in p.interiors]
    )

    name = re.sub(r"\.svg$", "", re.split(r"[\\/]", src)[-1])
    lines = [
        '<?xml version="1.0" encoding="utf-8"?>',
        "<!--",
        f"    {name}（纯矢量，由官方 SVG 转换，非位图；脚本 scripts/svg-to-android-vector.py）。",
        "    注意：<clip-path> 不读 android:fillType（VectorDrawableClipPath 只有 name/pathData），",
        "    镂空只有写在 <path> 上才生效，所以这里把「圆盘 − 编钟轮廓 − 字形」的布尔结果",
        "    直接烘焙进路径，用 nonzero 绕向表达（被挖形状外轮廓反向 = 挖空，内轮廓同向 = 岛）。",
        "    单色 #005FAD，实际显示时由调用方按主题 ColorFilter.tint 着色。",
        "-->",
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
        '    android:width="140dp"',
        '    android:height="140dp"',
        f'    android:viewportWidth="{fmt(vw, 6)}"',
        f'    android:viewportHeight="{fmt(vh, 6)}">',
        "",
        f'    <path android:name="{body_name}"',
        f'        android:fillColor="{body_fill}"',
        f'        android:pathData="{disk_d}" />',
    ]
    for nm, fill, d in foreground:
        lines += ["", f'    <path android:name="{nm}"', f'        android:fillColor="{fill}"',
                  f'        android:pathData="{bake_translate(d, dx, dy, prec)}" />']
    lines.append("</vector>")
    xml = "\n".join(lines) + "\n"
    open(dst, "w", encoding="utf-8", newline="\n").write(xml)
    print(f"{dst}: {len(xml)} bytes, 镂空面积 {cut.area:.1f} / 圆盘 {circle_poly.area:.1f}, "
          f"前景 {len(foreground)} 条")


if __name__ == "__main__":
    main()
