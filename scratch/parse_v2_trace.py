"""
方案 3：解析 java 服务 stdout 中的 [V2-Trace] 每步耗时日志。

优点：无需重新编译/重新部署。SearchServiceV2.executeStep 已经在 System.out 打印：
    "  [V2-Trace] Step KeywordCoarseEvidenceStep finish in 42 ms."
以及管线边界：
    "====== [SearchServiceV2 Pipeline Start] ======"
    "====== [SearchServiceV2 Pipeline End] Total Time: 658 ms ======"
只要把 java 进程的 stdout 重定向到文件，即可采集。

用法：
    # 1) 重启 java 服务时重定向 stdout（任选其一）：
    #    java  -jar  app.jar  >  java_stdout.log  2>&1
    #    mvn spring-boot:run  >  java_stdout.log  2>&1
    # 2) 跑一段时间后解析：
    python scratch/parse_v2_trace.py java_stdout.log
    python scratch/parse_v2_trace.py java_stdout.log --keyword-only

注意：stdout 在多线程并发下会交错，无法可靠地按"单次请求"切分。
本脚本因此做"全局按步骤名聚合"——每行自带步骤名，对交错鲁棒；
并单独统计 End 行的 Total Time 端到端分布。
精确的"每请求每步"分解请用 search_audit_log 的新列（方案 2）。
"""
import argparse
import re
import statistics
import sys

STEP_RE = re.compile(r"\[V2-Trace\]\s+Step\s+(\w+)\s+finish in\s+(\d+)\s+ms")
END_RE = re.compile(r"\[SearchServiceV2 Pipeline End\]\s+Total Time:\s+(\d+)\s+ms")
# keyword 模式特征行（用于 --keyword-only 近似过滤）
KW_MARKER_RE = re.compile(r"keyword 模式|keyword mode|Delegating to KeywordRecallStrategy")


def percentile(values, pct):
    if not values:
        return 0.0
    ordered = sorted(values)
    idx = int(round((pct / 100.0) * (len(ordered) - 1)))
    return ordered[max(0, min(idx, len(ordered) - 1))]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("logfile", help="java stdout 重定向文件路径")
    ap.add_argument("--keyword-only", action="store_true", help="近似只统计 keyword 请求（按上下文标记过滤，不精确）")
    args = ap.parse_args()

    try:
        with open(args.logfile, "r", encoding="utf-8", errors="replace") as fh:
            lines = fh.readlines()
    except Exception as e:
        print(f"[FATAL] 无法读取 {args.logfile}: {e}", file=sys.stderr)
        sys.exit(1)

    # 简单的"keyword 上下文窗口"：遇到 KW_MARKER 行后，接下来若干行视为 keyword 请求。
    # 仅用于 --keyword-only 近似；并发交错下不保证精确。
    keyword_ctx = False
    ctx_decay = 0

    per_step = {}        # step_name -> [ms,...]
    totals = []          # end-to-end Total Time

    for ln in lines:
        if args.keyword_only:
            if KW_MARKER_RE.search(ln):
                keyword_ctx = True
                ctx_decay = 40  # 之后 40 行视为 keyword 上下文
            elif ctx_decay > 0:
                ctx_decay -= 1
                if ctx_decay == 0:
                    keyword_ctx = False

        m = STEP_RE.search(ln)
        if m:
            if args.keyword_only and not keyword_ctx:
                continue
            name, ms = m.group(1), int(m.group(2))
            per_step.setdefault(name, []).append(ms)
            continue

        e = END_RE.search(ln)
        if e:
            if args.keyword_only and not keyword_ctx:
                # End 行本身也算 keyword 上下文的一部分（前面通常有 marker）
                pass
            totals.append(int(e.group(1)))

    # ── 输出 ──
    mode_label = "keyword-only(近似)" if args.keyword_only else "全部"
    print(f"\n===== V2-Trace 每步耗时聚合（{mode_label}）=====")
    if not per_step:
        print("未解析到任何 [V2-Trace] Step 行。请确认 stdout 已重定向且服务在跑搜索流量。")
        return

    header = f"{'step':<34}{'n':>6}{'avg':>8}{'p50':>8}{'p90':>8}{'p95':>8}{'max':>8}"
    print(header)
    print("-" * len(header))
    rows = []
    for name, vals in per_step.items():
        avg = statistics.mean(vals)
        rows.append((name, len(vals), avg,
                     percentile(vals, 50), percentile(vals, 90),
                     percentile(vals, 95), max(vals)))
    # 按平均耗时降序排，方便看瓶颈
    rows.sort(key=lambda r: -r[2])
    grand_sum_avg = 0.0
    for name, n, avg, p50, p90, p95, mx in rows:
        print(f"{name:<34}{n:>6}{avg:>8.1f}{p50:>8.1f}{p90:>8.1f}{p95:>8.1f}{mx:>8.1f}")
        grand_sum_avg += avg

    print("-" * len(header))
    print(f"{'(各步 avg 之和)':<34}{'':>6}{grand_sum_avg:>8.1f}")

    if totals:
        print(f"\n===== 端到端 Total Time 分布（n={len(totals)}）=====")
        print(f"  avg={statistics.mean(totals):.1f}  "
              f"p50={percentile(totals,50):.1f}  "
              f"p90={percentile(totals,90):.1f}  "
              f"p95={percentile(totals,95):.1f}  "
              f"p99={percentile(totals,99):.1f}  "
              f"max={max(totals):.1f}")
    else:
        print("\n（未解析到 Pipeline End 行，端到端分布略）")


if __name__ == "__main__":
    main()
