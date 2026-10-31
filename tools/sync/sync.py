#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""六端同步工具（Maid File Manager）。

为什么需要它
------------
本项目六个仓库（1.20.1 / 1.21.1 / 26.1.2 × Forge-or-NeoForge / Fabric）共用同一套
`common/` 源码树，67 个文件。改一次要同步五端 + 六次编译 + 四次 selfTest + 六次提交推送。
手工做这件事已经出过两次事故：正则改代码吞掉 1300 行、漏同步 MaidScreenDraw。

分类判据（全自动，无需维护清单）
--------------------------------
对每个文件，只看**内容哈希**（EOL 归一化），分两步：

第一步 · 五端互相是否一致？
  是  -> 该文件没有平台差异（copy 型）
  否  -> 进入第二步

第二步 · 用 git 历史区分「落后」与「API 差异」
  取母工程 HEAD 版本的哈希 prev
  对每个内容分组：
      组内容 == 母工程当前   -> 已同步
      组内容 == prev        -> **落后**（只是没跟本次改动）-> 可安全同步
      两个都不等于          -> **分叉**（平台 API 差异，例如 Path vs File、
                              GuiGraphics vs GuiGraphicsExtractor）-> 绝不能自动同步

这个判据的关键在于：**平台差异不会等于母工程的上一个版本**。一个因 API 不同的分支
会一直偏离主干，而「落后」只会短暂匹配 prev。所以它不需要任何人工白名单，也不会
把 API 差异误当成落后。

用法
----
    python sync.py check      # 报告各类与漂移
    python sync.py copy       # 同步 copy 型 + 落后型（不碰分叉型）
    python sync.py verify     # 编译六端 + 四端 selfTest
    python sync.py report     # 详细分类

设计原则
--------
- **只做文件级复制，绝不改写代码内容**。任何需要改代码的同步都必须人工——
  之前的教训是用正则改代码结构，一次吞掉 1300 行。
- EOL 归一化后再比较（母工程 LF，五端 CRLF）。
"""
import hashlib
import io
import os
from collections import defaultdict
import re
import subprocess
import sys

# ============================ 配置 ============================
#
# 本文件不包含任何本机路径。所有机器相关的配置（仓库根目录、各端 JDK 路径、
# GRADLE_USER_HOME）都从同目录下的 sync.local.json 读取，该文件已在 .gitignore 中。
# 首次使用：复制 sync.example.json 为 sync.local.json 并填入本机路径。

CONFIG_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "sync.local.json")


def _load_config():
    if not os.path.isfile(CONFIG_PATH):
        raise SystemExit(
            "缺少配置文件: %s\n"
            "请复制同目录下的 sync.example.json 为 sync.local.json 并填入本机路径。\n"
            "（sync.local.json 已在 .gitignore 中，不会被提交）" % CONFIG_PATH)
    import json
    with io.open(CONFIG_PATH, encoding="utf-8") as f:
        cfg = json.load(f)
    for key in ("base", "ports"):
        if key not in cfg:
            raise SystemExit("sync.local.json 缺少必需字段: %s" % key)
    return cfg


_CFG = _load_config()

# 六端仓库所在的父目录
BASE = _CFG["base"]

# 母工程（同步来源）
MASTER = _CFG.get("master", "forge-1.20.1")

# 各端配置：module 名、JDK 路径、是否有 selfTest
PORTS = _CFG["ports"]

OTHER = [p for p in PORTS if p != MASTER]

SCAN_ROOTS = ["common/src/main/java"]

# 真正的 Java 包根（相对仓库根）：包 = 这一层下面的第一级目录
PKG_ROOT_REL = "common/src/main/java/io/github/zgxhzhr/maidfm"

_prev_cache = {}


def port_root(port):
    return os.path.join(BASE, "maid_file_manager-" + port)


# ============================ 基础 ============================

def read_norm(path):
    if not os.path.isfile(path):
        return None
    with io.open(path, encoding="utf-8") as f:
        return f.read().replace("\r\n", "\n")


def dg(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()[:10] if text is not None else None


def prev_digest(rel):
    """母工程 HEAD 版本的内容哈希；文件在 HEAD 里不存在返回 None。"""
    if rel in _prev_cache:
        return _prev_cache[rel]
    r = subprocess.run(["git", "show", "HEAD:" + rel], cwd=port_root(MASTER),
                       capture_output=True, text=True, encoding="utf-8", errors="replace")
    val = dg(r.stdout.replace("\r\n", "\n")) if r.returncode == 0 else None
    _prev_cache[rel] = val
    return val



def semantic_digest(text):
    """语义哈希：去掉整行注释、空行与行首缩进后的内容哈希。

    用途：区分「注释漂移」与「真实代码差异」。历史手工同步留下过一批只改注释的
    漂移（例如 {@code MaidFilePackets.checkSize} vs {@code checkSize}），它们对功能
    零影响，却会让文件被判成平台差异。用语义哈希比较后，这类文件可以安全同步。
    """
    if text is None:
        return None
    out = []
    for line in text.split("\n"):
        s = line.strip()
        if not s:
            continue
        if s.startswith("//") or s.startswith("*") or s.startswith("/*") or s.startswith("*/"):
            continue
        out.append(s)
    return hashlib.sha256("\n".join(out).encode("utf-8")).hexdigest()[:10]



def read_norm_of_prev(rel):
    """母工程 HEAD 版本的归一化内容；不存在返回 None。"""
    r = subprocess.run(["git", "show", "HEAD:" + rel], cwd=port_root(MASTER),
                       capture_output=True, text=True, encoding="utf-8", errors="replace")
    return r.stdout.replace("\r\n", "\n") if r.returncode == 0 else None


def target_eol(path):
    if os.path.isfile(path):
        with open(path, "rb") as f:
            return "CRLF" if b"\r\n" in f.read(4000) else "LF"
    return "LF"


def write_with_eol(path, text, eol):
    t = text.replace("\r\n", "\n")
    if eol == "CRLF":
        t = t.replace("\n", "\r\n")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(t.encode("utf-8"))


def collect_files():
    rels = set()
    for port in PORTS:
        for root in SCAN_ROOTS:
            base = os.path.join(port_root(port), root.replace("/", os.sep))
            if not os.path.isdir(base):
                continue
            for dirpath, _, files in os.walk(base):
                for f in files:
                    if f.endswith(".java"):
                        rels.add(os.path.relpath(os.path.join(dirpath, f),
                                                 port_root(port)).replace(os.sep, "/"))
    return sorted(rels)


def classify(rel):
    """返回 {kind, behind:[ports], behind_all:bool, divergent:[ports], missing:[ports], groups}。

    kind:
      synced    全部端与母工程一致
      copy      五端互相一致且都落后 -> 可同步到全部
      behind    五端有分叉，但存在「只是落后」的组 -> 可同步到那些组
      divergent 五端有分叉，且所有偏离组都不等于母工程 prev -> 平台差异，不动
      master-new 五端有分叉，母工程与任何组都不同，且 prev 也对不上 -> 需人工确认
      missing   某端缺文件
    """
    master_text = read_norm(os.path.join(port_root(MASTER), rel.replace("/", os.sep)))
    m_d = dg(master_text)
    p_d = prev_digest(rel)

    others = {}
    for port in OTHER:
        others[port] = dg(read_norm(os.path.join(port_root(port), rel.replace("/", os.sep))))

    missing = [p for p, d in others.items() if d is None]
    present = {p: d for p, d in others.items() if d is not None}

    base = {"master_d": m_d, "prev_d": p_d, "groups": {}, "behind": [], "divergent": [],
            "missing": missing, "behind_all": False}

    if master_text is None or missing:
        base["kind"] = "missing"
        return base

    # 按内容分组
    groups = {}
    for p, d in present.items():
        groups.setdefault(d, []).append(p)
    base["groups"] = groups

    # 已同步的端：内容 == 母工程当前
    synced = [p for p, d in present.items() if d == m_d]

    # 语义哈希（忽略注释与空白）：用于识别「注释漂移」这类无害差异
    m_sem = semantic_digest(master_text)
    p_sem = semantic_digest(read_norm_of_prev(rel))
    sem_of = {}
    for p in present:
        sem_of[p] = semantic_digest(read_norm(os.path.join(port_root(p), rel.replace("/", os.sep))))

    behind, divergent = [], []
    for d, members in groups.items():
        if d == m_d:
            continue
        # 只是落后：内容等于母工程上一个提交
        if p_d is not None and d == p_d:
            behind.extend(members)
            continue
        # 注释漂移：语义与母工程当前一致 -> 可安全同步（只统一注释）
        if all(sem_of.get(p) == m_sem for p in members):
            behind.extend(members)
            continue
        divergent.extend(members)   # 真实平台差异 或 独立改动
    # 五端互相一致（1 组）但与母工程不同：说明它们没有 API 分化，只是「一致地落后」。
    # 也不能用 prev 判据识别（母工程的改动可能已经提交，prev 就等于当前）。
    # 这类可以同步，但单独标出来，方便人工扫一眼确认母工程的改动确实适用于所有端。
    if len(groups) == 1 and not synced:
        behind = list(present.keys())
        divergent = []
        base["kind"] = "all-behind"
        base["behind"] = behind
        base["divergent"] = divergent
        base["behind_all"] = True
        return base

    base["behind"] = behind
    base["divergent"] = divergent
    base["behind_all"] = (not divergent and len(behind) == len(OTHER))

    if not behind and not divergent:
        base["kind"] = "synced"
    elif not divergent:
        base["kind"] = "copy" if base["behind_all"] else "behind"
    elif not behind:
        base["kind"] = "divergent"
    else:
        base["kind"] = "behind"   # 部分落后 + 部分分叉 -> 只同步落后的
    return base


# ============================ 命令 ============================

def cmd_check(args):
    rels = collect_files()
    buckets = {k: [] for k in ["synced", "copy", "all-behind", "behind", "divergent", "master-new", "missing"]}
    for rel in rels:
        buckets[classify(rel)["kind"]].append(rel)

    print("扫描 %d 个 common 文件\n" % len(rels))

    if buckets["all-behind"]:
        print("【五端互相一致但都落后 · 可同步到全部五端（建议扫一眼改动内容）】%d 个" % len(buckets["all-behind"]))
        for r in buckets["all-behind"]:
            print("  " + r.split("/")[-1])
        print()

    if buckets["copy"]:
        print("【五端一致但落后 · 可同步到全部五端】%d 个" % len(buckets["copy"]))
        for r in buckets["copy"]:
            print("  " + r.split("/")[-1])
        print()

    if buckets["behind"]:
        print("【部分端落后 · 只同步落后的端】%d 个" % len(buckets["behind"]))
        for r in buckets["behind"]:
            info = classify(r)
            print("  %-32s 落后: %s" % (r.split("/")[-1], ",".join(info["behind"])))
            if info["divergent"]:
                print("  %-32s 分叉(不动): %s" % ("", ",".join(info["divergent"])))
        print()

    if buckets["divergent"]:
        print("【平台差异 · 不同步】（%d 个）" % len(buckets["divergent"]))
        for r in buckets["divergent"]:
            info = classify(r)
            sizes = "+".join("%d端" % len(m) for m in info["groups"].values())
            print("  %-32s %s" % (r.split("/")[-1], sizes))
        print()

    if buckets["missing"]:
        print("【某端缺文件】%d 个" % len(buckets["missing"]))
        for r in buckets["missing"]:
            print("  %-32s 缺: %s" % (r.split("/")[-1], ",".join(classify(r)["missing"])))
        print()

    print("=" * 78)
    print("待同步: copy=%d  all-behind=%d  behind=%d  平台差异(不动)=%d  缺文件=%d  已同步=%d"
          % (len(buckets["copy"]), len(buckets["all-behind"]), len(buckets["behind"]),
             len(buckets["divergent"]), len(buckets["missing"]), len(buckets["synced"])))
    return 0 if not buckets["copy"] and not buckets["behind"] and not buckets["missing"] else 1


def cmd_copy(args):
    rels = collect_files()
    done = []
    skipped = []
    for rel in rels:
        info = classify(rel)
        if info["kind"] in ("synced", "divergent", "master-new"):
            if info["kind"] == "divergent":
                skipped.append((rel, "平台差异"))
            continue
        if info["kind"] == "all-behind":
            master_text = read_norm(os.path.join(port_root(MASTER), rel.replace("/", os.sep)))
            for port in OTHER:
                dst = os.path.join(port_root(port), rel.replace("/", os.sep))
                write_with_eol(dst, master_text, target_eol(dst))
            done.append((rel, OTHER))
            continue
        if info["kind"] == "missing":
            skipped.append((rel, "缺文件"))
            continue
        master_text = read_norm(os.path.join(port_root(MASTER), rel.replace("/", os.sep)))
        targets = info["behind"] if info["kind"] == "behind" else OTHER
        for port in targets:
            dst = os.path.join(port_root(port), rel.replace("/", os.sep))
            write_with_eol(dst, master_text, target_eol(dst))
        done.append((rel, targets))

    print("已同步 %d 个文件：" % len(done))
    for r, ts in done:
        print("  %-40s -> %s" % (r.split("/")[-1], ",".join(ts)))
    if skipped:
        print()
        print("跳过 %d 个（平台差异 / 缺文件，需人工）：" % len(skipped))
        for r, why in skipped:
            print("  %-40s %s" % (r.split("/")[-1], why))
    return 0


def run_gradle(port, task):
    env = dict(os.environ)
    env["JAVA_HOME"] = PORTS[port]["jdk"]
    _gh = _CFG.get("gradleUserHome")
    if _gh:
        env["GRADLE_USER_HOME"] = _gh
    cmd = [os.path.join(port_root(port), "gradlew.bat"), task, "--console=plain", "--offline"]
    r = subprocess.run(cmd, cwd=port_root(port), env=env, capture_output=True, text=True,
                       encoding="utf-8", errors="replace")
    out = (r.stdout or "") + (r.stderr or "")
    return ("BUILD SUCCESSFUL" in out), out


# ============================ arch 子命令 ============================
#
# 背景：compileJava 和 selfTest 都发现不了「包依赖成环」。我在阶段 B 把
# ServerPacketHandlers（业务编排，依赖 service + config）放进 network 包，
# 产生三个环而毫无察觉，直到事后手动查图才发现。这个子命令把架构约束固化下来。
#
# 层级意图（左侧可以依赖右侧，反向就是违规）：
#
#   client   -> 所有
#   service  -> config, data, network, platform, spi
#   config   -> data, network
#   network  -> data
#   spi      -> platform
#   data     -> platform, spi
#   platform -> （谁都不依赖，最底层）
#
# 新增一条「没登记过但方向合理」的边只警告，不失败——架构是会演进的，
# 硬失败会逼人绕过检查；但方向反了或成环必须失败。

# 期望的依赖图：左侧包允许依赖右侧集合里的包
EXPECTED_EDGES = {
    "client":   {"config", "data", "network", "platform", "service", "spi", "util"},
    "service":  {"config", "data", "network", "platform", "spi", "util"},
    "config":   {"data", "network", "platform", "spi", "util"},
    "network":  {"data", "platform", "spi", "util"},
    "spi":      {"platform", "util"},
    "data":     {"platform", "spi", "util"},
    "platform": set(),
    "util":     set(),
}

# io.github.zgxhzhr.maidfm.<X> 里 X 是「类」而不是「包」的那些名字，
# 它们是 <top> 包的顶层类，不该被当成包名参与依赖分析。
IGNORED_FQN_SEGMENTS = {
    "Constants", "MaidFileModForge", "MaidFileModFabric",
    "MaidFileModNeoForge", "MaidFilePayloads", "C2SPacket",
    "S2CPacket", "ServerNetworkBridge",
}

# 绝对不允许的边（不管 EXPECTED_EDGES 怎么写，这些方向永远错）
FORBIDDEN_EDGES = {
    ("platform", "*"),      # 平台抽象层不能反向依赖任何人
    ("util", "*"),          # 工具层同理
}


def _scan_pkg_deps(java_root):
    """返回 (deps, files_by_pkg, pkgs)。

    deps: {pkg: set(pkg)}，只统计 io.github.zgxhzhr.maidfm.<pkg>. 形式的 import。
    """
    # 关键：既扫 import，也扫全限定名引用（io.github.zgxhzhr.maidfm.<pkg>.）。
    # 只扫 import 是不够的——本项目里大量地方直接用全限定名（例如
    # io.github.zgxhzhr.maidfm.config.MaidConfigManager.setServerConfig(...)），
    # 那种写法同样是依赖边，漏掉会让检查假通过。
    fqn_re = re.compile(r"io\.github\.zgxhzhr\.maidfm\.(\w+)\.")
    deps = defaultdict(set)
    files_by_pkg = defaultdict(list)
    for dirpath, _, files in os.walk(java_root):
        rel = os.path.relpath(dirpath, java_root)
        pkg = "<top>" if rel == "." else rel.split(os.sep)[0]
        for f in files:
            if not f.endswith(".java"):
                continue
            fp = os.path.join(dirpath, f)
            files_by_pkg[pkg].append(fp)
            with io.open(fp, encoding="utf-8") as fh:
                for line in fh:
                    s = line.strip()
                    # 跳过注释行：注释里写全限定名不构成依赖
                    if s.startswith("//") or s.startswith("*") or s.startswith("/*"):
                        continue
                    for m in fqn_re.finditer(line):
                        if m.group(1) != pkg and m.group(1) not in IGNORED_FQN_SEGMENTS:
                            deps[pkg].add(m.group(1))
    pkgs = sorted(p for p in files_by_pkg if files_by_pkg[p])
    return deps, files_by_pkg, pkgs


def _find_cycles(deps, pkgs):
    """DFS 三色标记找环，返回环列表。"""
    WHITE, GRAY, BLACK = 0, 1, 2
    color = {p: WHITE for p in pkgs}
    cycles = []

    def dfs(u, path):
        color[u] = GRAY
        path.append(u)
        for v in sorted(deps.get(u, [])):
            if v not in color:
                continue
            if color[v] == GRAY:
                cycles.append(path[path.index(v):] + [v])
            elif color[v] == WHITE:
                dfs(v, path)
        path.pop()
        color[u] = BLACK

    for p in pkgs:
        if color[p] == WHITE:
            dfs(p, [])
    return cycles


def _find_empty_pkg_dirs(pkg_root):
    """返回 pkg_root 下第 1 层的空包目录（相对 pkg_root）。

    只看第 1 层：pkg_root 本身就是包根，它下面的第 1 级目录才是「包」。
    更深层的空目录（如 client/gui）也报出来，因为它们同样会让包名骗人。
    """
    out = []
    for dirpath, _, files in os.walk(pkg_root):
        rel = os.path.relpath(dirpath, pkg_root)
        if rel == ".":
            continue
        if not any(f.endswith(".java") for f in files):
            out.append(rel)
    return sorted(out)


def cmd_arch(args):
    check_all = "--all" in args
    ports = list(PORTS.keys()) if check_all else [MASTER]

    failed = False
    print("=" * 72)
    print("包依赖架构检查（ports: %s）" % ("全部六端" if check_all else MASTER))
    print("=" * 72)

    pkg_sets = {}

    for port in ports:
        root = port_root(port)
        java_root = os.path.join(root, PKG_ROOT_REL.replace("/", os.sep))
        if not os.path.isdir(java_root):
            print("  %-18s 跳过（无 %s）" % (port, SCAN_ROOTS[0]))
            continue

        deps, files_by_pkg, pkgs = _scan_pkg_deps(java_root)
        pkg_sets[port] = set(pkgs)

        print()
        print("--- %s（%d 个包）" % (port, len(pkgs)))
        for p in pkgs:
            d = sorted(deps.get(p, []))
            print("    %-10s -> %s" % (p, ", ".join(d) if d else "（无）"))

        # 1) 环
        cycles = _find_cycles(deps, pkgs)
        if cycles:
            failed = True
            print("    [FAIL] 发现 %d 个依赖环：" % len(cycles))
            for c in cycles:
                print("           " + " -> ".join(c))
        else:
            print("    [ OK ] 无环（DAG）")

        # 2) 层级方向
        bad_edges = []
        new_edges = []
        for p in pkgs:
            for q in sorted(deps.get(p, [])):
                if (p, "*") in FORBIDDEN_EDGES or (p, q) in FORBIDDEN_EDGES:
                    bad_edges.append((p, q, "禁止的依赖方向"))
                    continue
                allowed = EXPECTED_EDGES.get(p)
                if allowed is None:
                    continue                       # 未知包：不判
                if q in allowed:
                    continue
                # 未登记但方向可能合理：警告
                new_edges.append((p, q))

        if bad_edges:
            failed = True
            print("    [FAIL] %d 条禁止的依赖边：" % len(bad_edges))
            for p, q, why in bad_edges:
                print("           %s -> %s（%s）" % (p, q, why))
        else:
            print("    [ OK ] 无禁止的依赖方向")

        if new_edges:
            print("    [WARN] %d 条新增依赖边（未在 EXPECTED_EDGES 登记，请确认方向合理后补登记）："
                  % len(new_edges))
            for p, q in new_edges:
                print("           %s -> %s" % (p, q))

        # 3) 空包
        empties = _find_empty_pkg_dirs(java_root)
        if empties:
            print("    [WARN] %d 个空包目录：" % len(empties))
            for e in empties:
                print("           " + e)
        else:
            print("    [ OK ] 无空包")

    # 4) 六端包结构一致
    if len(pkg_sets) > 1:
        print()
        print("--- 六端包结构一致性")
        ref_port = MASTER if MASTER in pkg_sets else sorted(pkg_sets)[0]
        ref = pkg_sets[ref_port]
        ok = True
        for port in sorted(pkg_sets):
            if port == ref_port:
                continue
            only_here = pkg_sets[port] - ref
            only_ref = ref - pkg_sets[port]
            if only_here or only_ref:
                ok = False
                failed = True
                print("    [FAIL] %s 与 %s 包结构不同：" % (port, ref_port))
                if only_here:
                    print("           仅本端有: " + ", ".join(sorted(only_here)))
                if only_ref:
                    print("           仅母工程有: " + ", ".join(sorted(only_ref)))
        if ok:
            print("    [ OK ] 六端包结构一致（%s）" % ", ".join(sorted(ref)))

    print()
    print("=" * 72)
    if failed:
        print("架构检查：失败（有环 / 禁止边 / 结构不一致）")
        return 1
    print("架构检查：通过")
    return 0


def cmd_verify(args):
    print("=" * 78)
    print("编译六端")
    print("=" * 78)
    compile_ok = {}
    for port in PORTS:
        ok, out = run_gradle(port, "compileJava")
        compile_ok[port] = ok
        print("  %-18s %s" % (port, "OK" if ok else "FAILED"))
        if not ok:
            shown = 0
            for line in out.split("\n"):
                if ".java:" in line and ("错误" in line or "error" in line):
                    print("      " + line.strip())
                    shown += 1
                    if shown >= 3:
                        break
    print()
    print("=" * 78)
    print("selfTest")
    print("=" * 78)
    test_ok = {}
    for port in PORTS:
        if not PORTS[port]["selfTest"]:
            print("  %-18s (无此任务)" % port)
            continue
        if not compile_ok.get(port):
            print("  %-18s 跳过（编译失败）" % port)
            continue
        ok, out = run_gradle(port, ":%s:selfTest" % PORTS[port]["module"])
        test_ok[port] = ok
        print("  %-18s %s  OK=%d FAIL=%d" % (port, "PASSED" if ok else "FAILED",
                                             out.count("[OK]"), out.count("[FAIL]")))
        if not ok:
            for line in out.split("\n"):
                if "[FAIL]" in line:
                    print("      " + line.strip())
    print()
    allc = all(compile_ok.values())
    allt = all(test_ok.get(p, True) for p in PORTS)
    print("=" * 78)
    print("编译: %s    selfTest: %s" % ("全部通过" if allc else "有失败",
                                        "全部通过" if allt else "有失败"))
    return 0 if allc and allt else 1


def cmd_report(args):
    rels = collect_files()
    rows = []
    for rel in rels:
        info = classify(rel)
        rows.append((info["kind"], rel, info))
    for kind in ["copy", "all-behind", "behind", "divergent", "synced", "missing", "master-new"]:
        sub = [(r, i) for k, r, i in rows if k == kind]
        if not sub:
            continue
        print("%s: %d 个" % (kind, len(sub)))
        for r, i in sub:
            extra = ""
            if i["behind"]:
                extra += " 落后:" + ",".join(x.replace("maid_file_manager-", "") for x in i["behind"])
            if i["divergent"]:
                extra += " 分叉:" + ",".join(x.replace("maid_file_manager-", "") for x in i["divergent"])
            print("  %-34s%s" % (r.split("/")[-1], extra))
        print()
    return 0


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    fn = {"check": cmd_check, "copy": cmd_copy, "verify": cmd_verify,
          "report": cmd_report, "arch": cmd_arch}.get(sys.argv[1])
    if not fn:
        print("未知命令: %s\n" % sys.argv[1])
        print(__doc__)
        return 2
    return fn(sys.argv[2:])


if __name__ == "__main__":
    sys.exit(main())
