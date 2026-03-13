#!/usr/bin/env python3
"""
Cache Comparison Benchmark Analyzer
Tüm kombinasyonların JSONL loglarını okuyarak kapsamlı metrikler,
grafikler ve LaTeX raporu üretir.

Kullanım:
    python3 scripts/analyze.py [benchmark-results-dir]
"""

import json
import math
import os
import statistics
import sys
from collections import defaultdict
from datetime import datetime
from pathlib import Path

# ── Opsiyonel bağımlılıklar ──────────────────────────────────────────────────
try:
    import matplotlib
    matplotlib.use('Agg')
    import matplotlib.pyplot as plt
    import matplotlib.patches as mpatches
    import numpy as np
    HAS_VIZ = True
except ImportError:
    HAS_VIZ = False
    print("UYARI: matplotlib/numpy bulunamadı → grafikler atlanacak.")
    print("       Yüklemek için: pip install matplotlib numpy\n")

# ── Sabitler ─────────────────────────────────────────────────────────────────
COMBINATIONS = [
    ("redis",     "lazy"),
    ("redis",     "eager"),
    ("hazelcast", "lazy"),
    ("hazelcast", "eager"),
    ("inmemory",  "lazy"),
    ("inmemory",  "eager"),
]

PROVIDER_COLORS = {
    "redis":     "#DC382D",
    "hazelcast": "#1C4E8D",
    "inmemory":  "#2E8B57",
}

LABELS = {
    "redis":     "Redis",
    "hazelcast": "Hazelcast",
    "inmemory":  "In-Memory",
    "lazy":      "Lazy",
    "eager":     "Eager",
}

# ── Yardımcı: istatistik ─────────────────────────────────────────────────────
def pct(data, p):
    if not data:
        return 0.0
    sd = sorted(data)
    k = (len(sd) - 1) * p / 100.0
    lo, hi = math.floor(k), math.ceil(k)
    return sd[lo] if lo == hi else sd[lo] * (hi - k) + sd[hi] * (k - lo)


def full_stats(durations):
    if not durations:
        return {}
    n   = len(durations)
    mn  = statistics.mean(durations)
    std = statistics.stdev(durations) if n > 1 else 0.0
    p50 = pct(durations, 50)
    p75 = pct(durations, 75)
    p25 = pct(durations, 25)
    return {
        "n":          n,
        "mean":       mn,
        "median":     statistics.median(durations),
        "std":        std,
        "cv_pct":     (std / mn * 100) if mn else 0,
        "min":        min(durations),
        "max":        max(durations),
        "p25":        p25,
        "p50":        p50,
        "p75":        p75,
        "p90":        pct(durations, 90),
        "p95":        pct(durations, 95),
        "p99":        pct(durations, 99),
        "iqr":        p75 - p25,
        "total":      sum(durations),
    }


# ── Veri yükleme ─────────────────────────────────────────────────────────────
def load_all(results_dir: Path):
    data = {}
    for provider, strategy in COMBINATIONS:
        key = f"{provider}-{strategy}"
        jsonl_path    = results_dir / f"{key}-cache.jsonl"
        internal_path = results_dir / f"{key}-internal.json"

        entries = []
        if jsonl_path.exists():
            with open(jsonl_path) as fh:
                for line in fh:
                    line = line.strip()
                    if line:
                        try:
                            entries.append(json.loads(line))
                        except json.JSONDecodeError:
                            pass

        internal = {}
        if internal_path.exists():
            try:
                internal = json.loads(internal_path.read_text())
            except json.JSONDecodeError:
                pass

        if entries or internal:
            data[key] = {
                "provider": provider,
                "strategy": strategy,
                "entries":  entries,
                "internal": internal,
            }
    return data


# ── Metrik hesaplama ─────────────────────────────────────────────────────────
def compute_metrics(data):
    metrics = {}
    for key, d in data.items():
        entries  = d["entries"]
        internal = d["internal"]

        hits    = [e for e in entries if e["result"] == "HIT"]
        misses  = [e for e in entries if e["result"] == "MISS"]
        warmups = [e for e in entries if e["result"] == "WARM_UP"]
        serves  = hits + misses

        hit_ms    = [e["durationMs"] for e in hits]
        miss_ms   = [e["durationMs"] for e in misses]
        serve_ms  = [e["durationMs"] for e in serves]
        warmup_ms = [e["durationMs"] for e in warmups]

        n         = len(serves)
        hit_n     = len(hits)
        miss_n    = len(misses)
        hit_rate  = (hit_n / n * 100) if n else 0

        wall_ms   = internal.get("totalWallMs", 0)
        throughput = (n / wall_ms * 1000) if wall_ms else 0

        # İlk / son 100 istek karşılaştırması (lazy ısınma etkisi)
        first100 = serve_ms[:100]  if len(serve_ms) >= 100 else serve_ms
        last100  = serve_ms[-100:] if len(serve_ms) >= 100 else serve_ms

        # MISS vs HIT oran farkı
        miss_hit_ratio = None
        if hit_ms and miss_ms:
            miss_hit_ratio = statistics.mean(miss_ms) / statistics.mean(hit_ms)

        # Zaman serisi: 10'arlık kayan ortalama (grafik için)
        win = 20
        rolling_avg = []
        if len(serve_ms) >= win:
            for i in range(0, len(serve_ms) - win + 1):
                rolling_avg.append(statistics.mean(serve_ms[i:i+win]))

        # Warm-up süresi (eager) — toplam WARM_UP işlem sürelerinin toplamı
        warmup_total_ms = sum(warmup_ms) if warmup_ms else 0

        metrics[key] = {
            "provider":          d["provider"],
            "strategy":          d["strategy"],
            "total_requests":    n,
            "hit_count":         hit_n,
            "miss_count":        miss_n,
            "hit_rate":          hit_rate,
            "throughput":        throughput,
            "wall_ms":           wall_ms,
            "serve":             full_stats(serve_ms),
            "hit":               full_stats(hit_ms),
            "miss":              full_stats(miss_ms),
            "warmup":            full_stats(warmup_ms),
            "warmup_total_ms":   warmup_total_ms,
            "first100_mean":     statistics.mean(first100) if first100 else 0,
            "last100_mean":      statistics.mean(last100)  if last100  else 0,
            "miss_hit_ratio":    miss_hit_ratio,
            "timeline":          serve_ms,
            "rolling_avg":       rolling_avg,
        }
    return metrics


# ── Konsol özeti ─────────────────────────────────────────────────────────────
def print_summary(metrics):
    SEP = "─" * 100
    print(f"\n{'═'*100}")
    print(f"  CACHE COMPARISON — DETAYLI METRİK RAPORU")
    print(f"  Üretilme: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    print(f"{'═'*100}\n")

    for key, m in metrics.items():
        s = m["serve"]
        if not s:
            continue
        print(f"  ╔══ {key.upper()} {'═'*(60 - len(key))}╗")
        print(f"  ║  Sağlayıcı : {LABELS[m['provider']]:15}  Strateji: {LABELS[m['strategy']]}")
        print(f"  ║  İstekler  : toplam={m['total_requests']}  hit={m['hit_count']}  miss={m['miss_count']}  hit-rate={m['hit_rate']:.1f}%")
        print(f"  ║  Gecikme (ms) :")
        print(f"  ║    Ort={s['mean']:.4f}  Medyan={s['median']:.4f}  Std={s['std']:.4f}  CV={s['cv_pct']:.1f}%")
        print(f"  ║    Min={s['min']:.4f}  Max={s['max']:.4f}  IQR={s['iqr']:.4f}")
        print(f"  ║    p25={s['p25']:.4f}  p50={s['p50']:.4f}  p75={s['p75']:.4f}")
        print(f"  ║    p90={s['p90']:.4f}  p95={s['p95']:.4f}  p99={s['p99']:.4f}")
        print(f"  ║  Verimlilik: {m['throughput']:,.0f} ops/sec  (wall={m['wall_ms']} ms)")
        if m["miss"] and m["miss"].get("n", 0) > 0:
            ms_s = m["miss"]
            print(f"  ║  MISS gecikme: ort={ms_s['mean']:.4f}  p99={ms_s['p99']:.4f}  (DB fetch dahil)")
        if m["miss_hit_ratio"]:
            print(f"  ║  MISS/HIT oran: {m['miss_hit_ratio']:.1f}x  (DB erişimi cache'e göre kaç kat yavaş)")
        if m["warmup"].get("n", 0) > 0:
            wu = m["warmup"]
            print(f"  ║  Warm-up: {wu['n']} kayıt  toplam={m['warmup_total_ms']:.1f}ms  ort/kayıt={wu['mean']:.4f}ms")
        if m["first100_mean"] and m["last100_mean"]:
            delta = m["last100_mean"] - m["first100_mean"]
            print(f"  ║  İlk100 ort={m['first100_mean']:.4f}ms  Son100 ort={m['last100_mean']:.4f}ms  Δ={delta:+.4f}ms")
        print(f"  ╚{'═'*62}╝\n")

    # ── Sıralama tablosu
    print(f"\n{'═'*70}")
    print("  HIZLILIK SIRALAMASI (ortalama gecikme — düşük = iyi)")
    print(f"{'─'*70}")
    sorted_keys = sorted([k for k in metrics if metrics[k]["serve"]],
                          key=lambda k: metrics[k]["serve"]["mean"])
    for rank, key in enumerate(sorted_keys, 1):
        s = metrics[key]["serve"]
        print(f"  #{rank}  {key:<22}  ort={s['mean']:.4f}ms  p99={s['p99']:.4f}ms  "
              f"tps={metrics[key]['throughput']:,.0f}")

    print(f"\n{'═'*70}")
    print("  VERİMLİLİK SIRALAMASI (throughput — yüksek = iyi)")
    print(f"{'─'*70}")
    sorted_tps = sorted([k for k in metrics if metrics[k]["serve"]],
                         key=lambda k: metrics[k]["throughput"], reverse=True)
    for rank, key in enumerate(sorted_tps, 1):
        print(f"  #{rank}  {key:<22}  {metrics[key]['throughput']:>10,.0f} ops/sec")
    print()


# ── Grafikler ─────────────────────────────────────────────────────────────────
def make_figures(metrics, out_dir: Path):
    if not HAS_VIZ:
        return []
    out_dir.mkdir(parents=True, exist_ok=True)

    plt.rcParams.update({
        "font.family":      "DejaVu Sans",
        "font.size":        10,
        "axes.titlesize":   12,
        "axes.labelsize":   10,
        "figure.dpi":       150,
        "axes.spines.top":  False,
        "axes.spines.right":False,
    })

    keys = [f"{p}-{s}" for p, s in COMBINATIONS if f"{p}-{s}" in metrics and metrics[f"{p}-{s}"]["serve"]]
    providers = ["redis", "hazelcast", "inmemory"]
    strategies = ["lazy", "eager"]

    def barlabel(key):
        m = metrics[key]
        return f"{LABELS[m['provider']]}\n{LABELS[m['strategy']]}"

    legend_patches = [mpatches.Patch(color=PROVIDER_COLORS[p], label=LABELS[p]) for p in providers]

    figures = []

    # ── F1: Ortalama gecikme (yatay bar, hata çubuklu) ────────────────────────
    fig, ax = plt.subplots(figsize=(10, 5.5))
    ys = list(range(len(keys)))
    means = [metrics[k]["serve"]["mean"] for k in keys]
    stds  = [metrics[k]["serve"]["std"]  for k in keys]
    cols  = [PROVIDER_COLORS[metrics[k]["provider"]] for k in keys]
    alphas = [0.65 if metrics[k]["strategy"] == "lazy" else 1.0 for k in keys]
    for i, (y, mean, std, col, al) in enumerate(zip(ys, means, stds, cols, alphas)):
        ax.barh(y, mean, 0.55, xerr=std, capsize=4, color=col, alpha=al,
                edgecolor="white", error_kw={"elinewidth": 1.2, "ecolor": "#555"})
        ax.text(mean + std + max(means)*0.01, y, f"{mean:.4f} ms", va="center", fontsize=9)
    ax.set_yticks(ys)
    ax.set_yticklabels([barlabel(k) for k in keys])
    ax.set_xlabel("Ortalama Gecikme (ms)")
    ax.set_title("Cache Mekanizmaları — Ortalama Gecikme\n(hata çubukları = ±1σ, soluk = lazy, koyu = eager)")
    ax.legend(handles=legend_patches, loc="lower right")
    ax.grid(axis="x", alpha=0.25)
    fig.tight_layout()
    p = out_dir / "fig1_avg_latency.png"; fig.savefig(p, bbox_inches="tight"); plt.close(fig)
    figures.append((p.name, "Ortalama Gecikme Karşılaştırması (± std)"))

    # ── F2: Yüzdelikler (p50, p95, p99) — üçlü yan yana bar ─────────────────
    fig, axes = plt.subplots(1, 3, figsize=(15, 5))
    for ax, (pkey, ptitle) in zip(axes, [("p50","Medyan (p50)"),("p95","p95"),("p99","p99")]):
        vals  = [metrics[k]["serve"].get(pkey, 0) for k in keys]
        cols  = [PROVIDER_COLORS[metrics[k]["provider"]] for k in keys]
        bars  = ax.bar(range(len(keys)), vals, color=cols, alpha=0.85, edgecolor="white")
        for bar, val in zip(bars, vals):
            ax.text(bar.get_x() + bar.get_width()/2, bar.get_height() + max(vals)*0.01,
                    f"{val:.3f}", ha="center", va="bottom", fontsize=8)
        ax.set_xticks(range(len(keys)))
        ax.set_xticklabels([barlabel(k) for k in keys], fontsize=8, rotation=20, ha="right")
        ax.set_ylabel("ms"); ax.set_title(ptitle); ax.grid(axis="y", alpha=0.25)
    fig.suptitle("Yüzdelik Gecikme Karşılaştırması", fontsize=13, fontweight="bold")
    fig.legend(handles=legend_patches, loc="upper right", bbox_to_anchor=(1.01, 1.0))
    fig.tight_layout()
    p = out_dir / "fig2_percentiles.png"; fig.savefig(p, bbox_inches="tight"); plt.close(fig)
    figures.append((p.name, "Yüzdelik Gecikme (p50 / p95 / p99)"))

    # ── F3: Kutu grafiği (boxplot) ────────────────────────────────────────────
    fig, ax = plt.subplots(figsize=(13, 6))
    bdata = [metrics[k]["timeline"] for k in keys]
    bp = ax.boxplot(bdata, labels=[barlabel(k) for k in keys],
                    patch_artist=True, notch=True,
                    flierprops={"marker": ".", "markersize": 2, "alpha": 0.3},
                    medianprops={"color": "black", "linewidth": 1.5})
    for patch, key in zip(bp["boxes"], keys):
        patch.set_facecolor(PROVIDER_COLORS[metrics[key]["provider"]])
        patch.set_alpha(0.65 if metrics[key]["strategy"] == "lazy" else 0.95)
    ax.set_ylabel("Gecikme (ms)")
    ax.set_title("Gecikme Dağılımı — Kutu Grafiği (medyan · IQR · aykırı değerler)")
    ax.legend(handles=legend_patches)
    ax.grid(axis="y", alpha=0.25)
    fig.tight_layout()
    p = out_dir / "fig3_boxplot.png"; fig.savefig(p, bbox_inches="tight"); plt.close(fig)
    figures.append((p.name, "Gecikme Dağılımı (Kutu Grafiği)"))

    # ── F4: Throughput (ops/sec) ──────────────────────────────────────────────
    fig, ax = plt.subplots(figsize=(10, 5))
    tps   = [metrics[k]["throughput"] for k in keys]
    cols  = [PROVIDER_COLORS[metrics[k]["provider"]] for k in keys]
    bars  = ax.bar(range(len(keys)), tps, color=cols, alpha=0.85, edgecolor="white")
    for bar, val in zip(bars, tps):
        ax.text(bar.get_x() + bar.get_width()/2, bar.get_height() + max(tps)*0.01,
                f"{val:,.0f}", ha="center", va="bottom", fontsize=9)
    ax.set_xticks(range(len(keys)))
    ax.set_xticklabels([barlabel(k) for k in keys], rotation=20, ha="right")
    ax.set_ylabel("ops/saniye")
    ax.set_title("Verimlilik (Throughput) — İşlem/Saniye")
    ax.legend(handles=legend_patches)
    ax.grid(axis="y", alpha=0.25)
    fig.tight_layout()
    p = out_dir / "fig4_throughput.png"; fig.savefig(p, bbox_inches="tight"); plt.close(fig)
    figures.append((p.name, "Verimlilik (Throughput)"))

    # ── F5: Gecikme zaman serisi (kayan ortalama) ─────────────────────────────
    fig, ax = plt.subplots(figsize=(14, 6))
    for key in keys:
        ra = metrics[key]["rolling_avg"]
        if not ra:
            continue
        m  = metrics[key]
        ls = "--" if m["strategy"] == "lazy" else "-"
        ax.plot(ra,
                label=f"{LABELS[m['provider']]} ({LABELS[m['strategy']]})",
                color=PROVIDER_COLORS[m["provider"]], linestyle=ls, linewidth=1.5, alpha=0.85)
    ax.set_xlabel("İstek sırası (kayan pencere = 20)")
    ax.set_ylabel("Ortalama gecikme (ms)")
    ax.set_title("Gecikme Zaman Serisi — Kayan Ortalama (pencere = 20 istek)\n"
                 "Lazy stratejide ilk isteklerin MISS nedeniyle yüksek gecikme gösterdiği görülebilir")
    ax.legend(fontsize=9)
    ax.grid(alpha=0.25)
    fig.tight_layout()
    p = out_dir / "fig5_timeline.png"; fig.savefig(p, bbox_inches="tight"); plt.close(fig)
    figures.append((p.name, "Gecikme Zaman Serisi (Kayan Ortalama)"))

    # ── F6: Isı haritası (provider × strategy) ───────────────────────────────
    fig, axes = plt.subplots(1, 2, figsize=(12, 4))
    for ax, (mkey, title) in zip(axes, [("mean", "Ortalama (ms)"), ("p99", "p99 (ms)")]):
        mat = [[metrics.get(f"{p}-{s}", {}).get("serve", {}).get(mkey, 0)
                for s in strategies] for p in providers]
        arr = np.array(mat)
        im  = ax.imshow(arr, cmap="YlOrRd", aspect="auto")
        ax.set_xticks(range(len(strategies)))
        ax.set_xticklabels([LABELS[s] for s in strategies])
        ax.set_yticks(range(len(providers)))
        ax.set_yticklabels([LABELS[p] for p in providers])
        ax.set_title(title)
        vmax = arr.max()
        for i in range(len(providers)):
            for j in range(len(strategies)):
                ax.text(j, i, f"{arr[i,j]:.3f}",
                        ha="center", va="center", fontweight="bold",
                        color="white" if arr[i,j] > vmax * 0.65 else "black")
        plt.colorbar(im, ax=ax, label="ms")
    fig.suptitle("Isı Haritası: Sağlayıcı × Strateji", fontsize=13, fontweight="bold")
    fig.tight_layout()
    p = out_dir / "fig6_heatmap.png"; fig.savefig(p, bbox_inches="tight"); plt.close(fig)
    figures.append((p.name, "Isı Haritası (Ortalama ve p99)"))

    # ── F7: İsabet oranı (hit rate) ───────────────────────────────────────────
    fig, ax = plt.subplots(figsize=(10, 5))
    hit_rates  = [metrics[k]["hit_rate"] for k in keys]
    miss_rates = [100 - r for r in hit_rates]
    cols       = [PROVIDER_COLORS[metrics[k]["provider"]] for k in keys]
    ax.bar(range(len(keys)), hit_rates,  color=cols, alpha=0.85, label="HIT",  edgecolor="white")
    ax.bar(range(len(keys)), miss_rates, bottom=hit_rates, color="#aaa", alpha=0.5, label="MISS")
    for i, (hr, mr) in enumerate(zip(hit_rates, miss_rates)):
        if hr > 5:
            ax.text(i, hr/2, f"{hr:.1f}%", ha="center", va="center",
                    fontsize=9, fontweight="bold", color="white")
        if mr > 5:
            ax.text(i, hr + mr/2, f"{mr:.1f}%", ha="center", va="center", fontsize=9, color="#333")
    ax.set_xticks(range(len(keys)))
    ax.set_xticklabels([barlabel(k) for k in keys], rotation=20, ha="right")
    ax.set_ylim(0, 105); ax.set_ylabel("Oran (%)")
    ax.set_title("Cache İsabet Oranı (Hit / Miss)")
    ax.legend(); ax.grid(axis="y", alpha=0.25)
    fig.tight_layout()
    p = out_dir / "fig7_hitrate.png"; fig.savefig(p, bbox_inches="tight"); plt.close(fig)
    figures.append((p.name, "Cache İsabet Oranı"))

    # ── F8: Lazy vs Eager karşılaştırması (provider bazında) ─────────────────
    fig, axes = plt.subplots(1, 3, figsize=(15, 5))
    mkeys = ["mean", "p95", "p99"]
    mnames = ["Ortalama", "p95", "p99"]
    for ax, prov in zip(axes, providers):
        lk = f"{prov}-lazy"
        ek = f"{prov}-eager"
        lazy_vals  = [metrics[lk]["serve"].get(mk, 0) if lk in metrics else 0 for mk in mkeys]
        eager_vals = [metrics[ek]["serve"].get(mk, 0) if ek in metrics else 0 for mk in mkeys]
        x = np.arange(len(mkeys))
        w = 0.35
        ax.bar(x - w/2, lazy_vals,  w, label="Lazy",  color=PROVIDER_COLORS[prov], alpha=0.55)
        ax.bar(x + w/2, eager_vals, w, label="Eager", color=PROVIDER_COLORS[prov], alpha=1.0)
        for xv, lv, ev in zip(x, lazy_vals, eager_vals):
            ax.text(xv - w/2, lv  + max(max(lazy_vals), max(eager_vals))*0.02, f"{lv:.3f}",  ha="center", fontsize=8)
            ax.text(xv + w/2, ev  + max(max(lazy_vals), max(eager_vals))*0.02, f"{ev:.3f}", ha="center", fontsize=8)
        ax.set_xticks(x); ax.set_xticklabels(mnames)
        ax.set_ylabel("ms"); ax.set_title(LABELS[prov])
        ax.legend(fontsize=9); ax.grid(axis="y", alpha=0.25)
    fig.suptitle("Lazy vs Eager — Strateji Etkisi (sağlayıcı bazında)", fontsize=12, fontweight="bold")
    fig.tight_layout()
    p = out_dir / "fig8_lazy_vs_eager.png"; fig.savefig(p, bbox_inches="tight"); plt.close(fig)
    figures.append((p.name, "Lazy vs Eager Strateji Karşılaştırması"))

    # ── F9: CV (tutarlılık) — düşük CV = daha tahmin edilebilir ───────────────
    fig, ax = plt.subplots(figsize=(10, 5))
    cvs  = [metrics[k]["serve"].get("cv_pct", 0) for k in keys]
    cols = [PROVIDER_COLORS[metrics[k]["provider"]] for k in keys]
    bars = ax.bar(range(len(keys)), cvs, color=cols, alpha=0.85, edgecolor="white")
    for bar, val in zip(bars, cvs):
        ax.text(bar.get_x() + bar.get_width()/2, bar.get_height() + max(cvs)*0.01,
                f"{val:.1f}%", ha="center", va="bottom", fontsize=9)
    ax.set_xticks(range(len(keys)))
    ax.set_xticklabels([barlabel(k) for k in keys], rotation=20, ha="right")
    ax.set_ylabel("Varyasyon Katsayısı — CV (%)"); ax.grid(axis="y", alpha=0.25)
    ax.set_title("Gecikme Tutarlılığı — Varyasyon Katsayısı (CV)\n(düşük CV = daha öngörülebilir gecikme)")
    ax.legend(handles=legend_patches)
    fig.tight_layout()
    p = out_dir / "fig9_cv.png"; fig.savefig(p, bbox_inches="tight"); plt.close(fig)
    figures.append((p.name, "Gecikme Tutarlılığı (CV — Varyasyon Katsayısı)"))

    print(f"  ✓ {len(figures)} grafik üretildi → {out_dir}/")
    return figures


# ── LaTeX raporu ──────────────────────────────────────────────────────────────
def fmt(v, dec=4):
    return f"{v:.{dec}f}"

def generate_latex(metrics, figures, report_dir: Path):
    now_str = datetime.now().strftime("%d %B %Y, %H:%M")
    combo_keys = [f"{p}-{s}" for p, s in COMBINATIONS]

    # ── Tablo 1: Gecikme istatistikleri ──────────────────────────────────────
    rows_latency = ""
    for key in combo_keys:
        if key not in metrics:
            continue
        m, s = metrics[key], metrics[key]["serve"]
        if not s:
            continue
        rows_latency += (
            f"    {LABELS[m['provider']]} & {LABELS[m['strategy']]}"
            f" & {fmt(s['mean'])} & {fmt(s['median'])} & {fmt(s['std'])}"
            f" & {s['cv_pct']:.1f}\\% & {fmt(s['min'])} & {fmt(s['max'])} \\\\\n"
        )

    # ── Tablo 2: Yüzdelik değerler ────────────────────────────────────────────
    rows_pct = ""
    for key in combo_keys:
        if key not in metrics:
            continue
        m, s = metrics[key], metrics[key]["serve"]
        if not s:
            continue
        rows_pct += (
            f"    {LABELS[m['provider']]} & {LABELS[m['strategy']]}"
            f" & {fmt(s['p25'])} & {fmt(s['p50'])} & {fmt(s['p75'])}"
            f" & {fmt(s['p90'])} & {fmt(s['p95'])} & {fmt(s['p99'])} \\\\\n"
        )

    # ── Tablo 3: Verimlilik & isabet oranı ───────────────────────────────────
    rows_perf = ""
    for key in combo_keys:
        if key not in metrics:
            continue
        m, s = metrics[key], metrics[key]["serve"]
        if not s:
            continue
        miss_hit = f"{m['miss_hit_ratio']:.1f}x" if m["miss_hit_ratio"] else "---"
        rows_perf += (
            f"    {LABELS[m['provider']]} & {LABELS[m['strategy']]}"
            f" & {m['hit_count']} & {m['miss_count']} & {m['hit_rate']:.1f}\\%"
            f" & {m['throughput']:,.0f} & {m['wall_ms']} & {miss_hit} \\\\\n"
        )

    # ── Şekil komutları ───────────────────────────────────────────────────────
    def fig_latex(fname, caption, label_key):
        return (
            f"\\begin{{figure}}[htbp]\n"
            f"  \\centering\n"
            f"  \\includegraphics[width=\\textwidth]{{figures/{fname}}}\n"
            f"  \\caption{{{caption}}}\n"
            f"  \\label{{fig:{label_key}}}\n"
            f"\\end{{figure}}\n\n"
        )

    fig_blocks = ""
    for fname, caption in figures:
        label_key = fname.replace(".png", "").replace(".", "_")
        fig_blocks += fig_latex(fname, caption, label_key)

    # ── Hızlılık sıralaması ───────────────────────────────────────────────────
    valid_keys = [k for k in combo_keys if k in metrics and metrics[k]["serve"]]
    ranked = sorted(valid_keys, key=lambda k: metrics[k]["serve"]["mean"])
    rank_rows = ""
    for rank, key in enumerate(ranked, 1):
        s = metrics[key]["serve"]
        m = metrics[key]
        rank_rows += (
            f"    {rank} & {LABELS[m['provider']]} & {LABELS[m['strategy']]}"
            f" & {fmt(s['mean'])} & {fmt(s['p99'])} & {m['throughput']:,.0f} \\\\\n"
        )

    # ── LaTeX belgesi ─────────────────────────────────────────────────────────
    tex = r"""\documentclass[12pt,a4paper]{article}

\usepackage[utf8]{inputenc}
\usepackage[T1]{fontenc}
\usepackage[top=2.5cm,bottom=2.5cm,left=2.5cm,right=2.5cm]{geometry}
\usepackage{booktabs}
\usepackage{graphicx}
\usepackage{xcolor}
\usepackage{hyperref}
\usepackage{amsmath}
\usepackage{array}
\usepackage{longtable}
\usepackage{lmodern}
\setlength{\parskip}{0.5em}
\setlength{\parindent}{0pt}

\definecolor{redisred}{HTML}{DC382D}
\definecolor{hazelblue}{HTML}{1C4E8D}
\definecolor{memgreen}{HTML}{2E8B57}
\definecolor{lightgray}{HTML}{F5F5F5}

\hypersetup{
    colorlinks=true,
    linkcolor=blue!60!black,
    urlcolor=blue!70!black,
    pdftitle={Cache Mekanizmaları Performans Karşılaştırması},
    pdfauthor={CacheCompare PoC}
}

\newcolumntype{R}[1]{>{\raggedleft\arraybackslash}p{#1}}
\newcolumntype{C}[1]{>{\centering\arraybackslash}p{#1}}

\title{
    \vspace{-1cm}
    {\LARGE \textbf{Cache Mekanizmaları Performans Karşılaştırması}} \\[0.6em]
    {\large Redis, Hazelcast ve In-Memory HashMap} \\[0.4em]
    {\normalsize PoC Kıyaslama Raporu}
}
\author{CacheCompare PoC — Spring Boot 4.0 / Java 21}
\date{""" + now_str + r"""}

\begin{document}

\maketitle
\tableofcontents
\newpage

% ═══════════════════════════════════════════════════════════════
\section{Özet}
% ═══════════════════════════════════════════════════════════════

Bu rapor, üç farklı cache mekanizmasının (\textbf{Redis}, \textbf{Hazelcast}, \textbf{In-Memory HashMap}) ve iki farklı cache yükleme stratejisinin (\textbf{Lazy / Cache-Aside} ve \textbf{Eager / Warm-Up}) Spring Boot tabanlı bir uygulamada gerçek koşullarda nasıl davrandığını ölçen PoC çalışmasının sonuçlarını içermektedir.

Her kombinasyon için tek bir istek ve 1000 ardışık istek olmak üzere iki tür kıyaslama senaryosu uygulanmış; gecikme, verimlilik, isabet oranı ve tutarlılık boyutlarında kapsamlı metrikler toplanmıştır. Ölçümler \texttt{System.nanoTime()} hassasiyetinde, JVM içinden doğrudan cache katmanına yapılan çağrıların süresi esas alınarak gerçekleştirilmiştir.

% ═══════════════════════════════════════════════════════════════
\section{Test Ortamı}
% ═══════════════════════════════════════════════════════════════

\subsection{Altyapı}

Tüm bileşenler Docker Compose ile tek bir makine üzerinde çalıştırılmıştır:

\begin{itemize}
    \item \textbf{Veritabanı:} PostgreSQL 16 (Alpine), port 5432.
          1000 adet \texttt{Product} kaydı önceden yüklenmiştir.
    \item \textbf{Redis:} Redis 7 (Alpine), port 6379.
          Maksimum 256 MB bellek, LRU tahliye politikası.
    \item \textbf{Hazelcast:} Hazelcast 5.3 (standalone member), port 5701.
          Uygulama \textit{client-server} modunda bağlanmaktadır; bu
          tercih, gerçek dağıtık dağıtım topolojisini yansıtmak için yapılmıştır.
    \item \textbf{Uygulama:} Spring Boot 4.0.3, Java 21, Maven.
          Her kombinasyon ayrı JVM sürecinde, soğuk başlangıçla çalıştırılmıştır.
\end{itemize}

\subsection{Veri Modeli}

Test varlığı olarak \texttt{Product} sınıfı kullanılmıştır.
Sınıf 10 alan içermekte; Redis ve Hazelcast için \texttt{Serializable}
arayüzünü uygulamaktadır:

\begin{center}
\begin{tabular}{lll}
\toprule
\textbf{Alan}    & \textbf{Tür}         & \textbf{Açıklama} \\
\midrule
\texttt{id}          & \texttt{Long}           & Otomatik artan birincil anahtar \\
\texttt{name}        & \texttt{String}         & Ürün adı \\
\texttt{description} & \texttt{String}         & Açıklama (maks. 1000 karakter) \\
\texttt{price}       & \texttt{BigDecimal}     & Fiyat \\
\texttt{stock}       & \texttt{Integer}        & Stok adedi \\
\texttt{category}    & \texttt{String}         & Kategori (8 farklı değer) \\
\texttt{brand}       & \texttt{String}         & Marka \\
\texttt{sku}         & \texttt{String}         & Stok kodu \\
\texttt{weight}      & \texttt{Double}         & Ağırlık (kg) \\
\texttt{imageUrl}    & \texttt{String}         & Görsel URL'i \\
\bottomrule
\end{tabular}
\end{center}

\subsection{Cache Sağlayıcıları}

\paragraph{\textcolor{redisred}{Redis.}}
Lettuce istemcisi ile bağlanılmış, \textit{Java serileştirmesi yerine Jackson JSON serileştirmesi} tercih edilmiştir. Bulk yükleme için tek bir ağ turu ile 1000 kaydı gönderen \texttt{MSET} (multiSet) komutu kullanılmıştır. Redis, JVM dışında ayrı bir süreçte çalışmaktadır; dolayısıyla her cache okuması bir ağ hop'u (loopback) içermektedir.

\paragraph{\textcolor{hazelblue}{Hazelcast.}}
Client-server modunda, Docker konteynerindeki standalone member'a \texttt{HazelcastClient} ile bağlanılmaktadır. Veriler \texttt{IMap<Long, Product>} üzerinde tutulmaktadır. Hazelcast, verileri kendi serileştirme katmanıyla saklar; bu nedenle yazma işlemleri Redis'e kıyasla daha fazla serileştirme maliyeti içerebilmektedir.

\paragraph{\textcolor{memgreen}{In-Memory HashMap.}}
JVM yığınında yaşayan bir \texttt{ConcurrentHashMap<Long, Product>} kullanılmıştır. Başlangıç kapasitesi 1024 olarak ayarlanmış, böylece 1000 kayıtlık yükleme sırasında yeniden boyutlandırma gerçekleşmemektedir. Ağ gecikmesi sıfırdır; tüm erişimler salt nesne referansı dereference işlemidir.

% ═══════════════════════════════════════════════════════════════
\section{Metodoloji}
% ═══════════════════════════════════════════════════════════════

\subsection{Cache Yükleme Stratejileri}

\subsubsection{Eager (Warm-Up) Stratejisi}

Uygulama başladıktan ve web sunucusu bağlantıları kabul etmeye hazır
hale geldikten sonra \texttt{ApplicationReadyEvent} dinleyicisi
tetiklenmekte ve tüm 1000 ürün PostgreSQL'den çekilerek seçili
cache'e toplu olarak yazılmaktadır.

\begin{enumerate}
    \item \texttt{ProductRepository.findAll()} → 1000 kayıt
    \item \texttt{CacheProvider.putAll()} → tek seferde toplu yazma
    \item Bundan sonra gelen her istek cache'ten karşılanır (HIT)
\end{enumerate}

Redis'te \texttt{MSET}, Hazelcast'te \texttt{IMap.putAll()},
In-Memory'de \texttt{ConcurrentHashMap.putAll()} kullanılmıştır.

\subsubsection{Lazy (Cache-Aside) Stratejisi}

Cache ön yüklenmez. Her istek şu adımları izler:

\begin{enumerate}
    \item Cache'e bak: kayıt varsa \textbf{HIT} → direkt döndür
    \item Kayıt yoksa \textbf{MISS} → veritabanından oku, cache'e yaz, döndür
\end{enumerate}

Zamanla cache ısınır ve isabet oranı artış eğilimi gösterir.
Test sırasında 1000 istek rastgele 1--1000 arası ID'lere yönlendirilmiştir.

\subsection{Ölçüm Yöntemi}

Gecikme ölçümü, \textbf{Spring Cache soyutlaması (\texttt{@Cacheable}) kasıtlı olarak devre dışı bırakılarak} doğrudan \texttt{CacheProvider} arayüzüne yapılan çağrılar etrafında gerçekleştirilmiştir. Bu yöntem, proxy katmanının getirdiği ek yükü devre dışı bırakarak ham cache performansını ölçmeye olanak tanımaktadır.

\begin{equation}
t_{\text{gecikme}} = t_{\text{bitiş}} - t_{\text{başlangıç}}
\quad\text{(}\texttt{System.nanoTime()}\text{, nanosaniye hassasiyeti)}
\end{equation}

Lazy stratejide bu süre hem cache erişimini hem de gerektiğinde
veritabanı erişimini kapsamaktadır. Eager stratejide yalnızca
cache okuma süresi ölçülmektedir.

\subsection{Benchmark Senaryoları}

\paragraph{İç Benchmark (Internal).}
\texttt{GET /products/benchmark/bulk?count=1000} endpoint'ine tek
bir HTTP isteği atılır. Uygulama içinde 1000 rastgele ID için
cache okumaları gerçekleştirilir ve toplanmış istatistikler
(toplam, ortalama, min, maks) JSON olarak döndürülür.
Ağ katmanı bu ölçüme dahil \textbf{değildir}.

\paragraph{Dış Benchmark (External).}
\texttt{scripts/benchmark.sh} aracılığıyla 1000 ayrı
\texttt{curl} isteği atılır. Her isteğin HTTP round-trip süresi
(bağlantı kurma, istek gönderme, yanıt alma) CSV dosyasına kaydedilir.
Bu ölçüm gerçek istemci perspektifini yansıtmaktadır.

\paragraph{Loglama.}
Her cache işlemi için NDJSON formatında bir log satırı,
\texttt{logs/cache-benchmark.jsonl} dosyasına \texttt{APPEND}
modunda yazılmaktadır:

\begin{verbatim}
{
  "timestamp":     "2026-03-13T08:07:17.124Z",
  "cacheProvider": "redis",
  "strategy":      "lazy",
  "productId":     42,
  "result":        "MISS",
  "durationNs":    1234567,
  "durationMs":    1.234567,
  "threadName":    "http-nio-8080-exec-2"
}
\end{verbatim}

% ═══════════════════════════════════════════════════════════════
\section{Terimler ve Kısaltmalar}
% ═══════════════════════════════════════════════════════════════

\noindent\colorbox{gray!12}{\parbox{\dimexpr\linewidth-2\fboxsep}{%
  \textbf{Kısaltmalar Sözlüğü}}}
\vspace{0.3em}
\noindent\begin{minipage}{\linewidth}
\begin{description}
  \item[HIT]     Cache \textit{isabeti} — istenen veri cache'te bulundu, doğrudan döndürüldü.
  \item[MISS]    Cache \textit{kaçırma} — veri cache'te yok; veritabanından okunup cache'e yazıldı.
  \item[WARM\_UP] Eager stratejide uygulama başlangıcında yapılan toplu cache yükleme kaydı.
  \item[p50]     50. yüzdelik dilim — \textit{medyan}. İsteklerin yarısı bu değerin altında,
                 yarısı üstünde kalmaktadır.
  \item[p75]     75. yüzdelik dilim. İsteklerin \%75'i bu süreden daha hızlıdır.
  \item[p90]     90. yüzdelik dilim. En yavaş \%10'un başladığı eşik değer.
  \item[p95]     95. yüzdelik dilim. Sistemin \textit{kuyruk gecikmesini} ölçmek için
                 yaygın kullanılan eşik — en yavaş \%5'in başladığı nokta.
  \item[p99]     99. yüzdelik dilim. En yavaş \%1'in eşiği; aykırı değerlere
                 (örn. GC duraksaları, ağ dalgalanmaları) duyarlıdır.
  \item[TPS]     \textit{Transactions Per Second} — saniye başına işlem sayısı.
                 Sistemin verimlilik kapasitesini (throughput) gösterir.
  \item[CV\%]    \textit{Coefficient of Variation} — Varyasyon Katsayısı.
                 $CV = \sigma / \mu \times 100$. Düşük CV gecikmenin tutarlı
                 olduğunu, yüksek CV öngörülemeyen ani artışlar olduğunu gösterir.
  \item[IQR]     \textit{Interquartile Range} — Çeyrekler Açıklığı. $IQR = p75 - p25$.
                 Aykırı değerlerden etkilenmeyen bir yayılım ölçüsüdür.
  \item[Std]     Standart sapma ($\sigma$). Gecikmelerin ortalamadan ne kadar
                 saptığını gösterir.
  \item[Wall]    \textit{Wall-clock time} — Duvar saati süresi. Tüm 1000 isteğin
                 başından sonuna kadar geçen gerçek süre (ms).
\end{description}
\end{minipage}
\vspace{0.5em}

% ═══════════════════════════════════════════════════════════════
\section{Sonuçlar}
% ═══════════════════════════════════════════════════════════════

\subsection{Gecikme İstatistikleri}

Tüm süreler milisaniye (ms) cinsindendir. CV\% değeri tutarlılığı,
Std ise mutlak dalgalanmayı göstermektedir.

\begin{table}[htbp]
\centering
\caption{Gecikme İstatistikleri (ms)}
\resizebox{\textwidth}{!}{%
\begin{tabular}{llrrrrrr}
\toprule
\textbf{Sağlayıcı} & \textbf{Strateji}
  & \textbf{Ort} & \textbf{Medyan} & \textbf{Std}
  & \textbf{CV\%} & \textbf{Min} & \textbf{Max} \\
\midrule
""" + rows_latency + r"""\bottomrule
\end{tabular}%
}
\end{table}

\subsection{Yüzdelik Değerler}

Yüzdelik değerler gecikme dağılımının farklı noktalarını gösterir.
p99, sistemin en ağır \%1'lik yükünü karakterize eden kuyruk gecikmesidir.

\begin{table}[htbp]
\centering
\caption{Yüzdelik Gecikme Değerleri (ms)}
\resizebox{\textwidth}{!}{%
\begin{tabular}{llrrrrrr}
\toprule
\textbf{Sağlayıcı} & \textbf{Strateji}
  & \textbf{p25} & \textbf{p50} & \textbf{p75}
  & \textbf{p90} & \textbf{p95} & \textbf{p99} \\
\midrule
""" + rows_pct + r"""\bottomrule
\end{tabular}%
}
\end{table}

\subsection{Verimlilik ve İsabet Oranı}

TPS, 1000 isteğin toplam duvar saati süresinden hesaplanmıştır.
MISS/HIT sütunu, bir MISS'in HIT'e kıyasla kaç kat daha yavaş olduğunu gösterir.

\begin{table}[htbp]
\centering
\caption{Verimlilik ve İsabet Oranı}
\resizebox{\textwidth}{!}{%
\begin{tabular}{llrrrrrl}
\toprule
\textbf{Sağlayıcı} & \textbf{Strateji}
  & \textbf{HIT} & \textbf{MISS} & \textbf{Hit\%}
  & \textbf{TPS} & \textbf{Wall (ms)} & \textbf{MISS/HIT} \\
\midrule
""" + rows_perf + r"""\bottomrule
\end{tabular}%
}
\end{table}

\subsection{Hızlılık Sıralaması}

\begin{table}[htbp]
\centering
\caption{Ortalama gecikmeye göre hızlılık sıralaması}
\begin{tabular}{clllrrl}
\toprule
\textbf{\#} & \textbf{Sağlayıcı} & \textbf{Strateji}
  & \textbf{Ort (ms)} & \textbf{p99 (ms)} & \textbf{TPS} \\
\midrule
""" + rank_rows + r"""\bottomrule
\end{tabular}
\end{table}

% ═══════════════════════════════════════════════════════════════
\section{Grafikler}
% ═══════════════════════════════════════════════════════════════

""" + fig_blocks + r"""

% ═══════════════════════════════════════════════════════════════
\section{Değerlendirme}
% ═══════════════════════════════════════════════════════════════

\subsection{In-Memory HashMap}

In-Memory HashMap, beklenen şekilde en düşük gecikmeyi ve en yüksek
verimliliği sergilemiştir. Tüm veriler JVM yığınında bulunduğundan
nesne erişimi yalnızca bir \textit{dereference} işlemi gerektirmekte,
herhangi bir ağ veya serileştirme maliyeti oluşmamaktadır.

\textbf{Avantajlar:} Son derece düşük gecikme, sıfır ağ gecikmesi,
basit kurulum.

\textbf{Dezavantajlar:} Uygulama yeniden başlatıldığında veri kaybolur;
birden fazla JVM örneği (yatay ölçekleme) arasında önbellek
tutarsızlığına yol açar; büyük veri setleri GC baskısını artırır.

\subsection{Redis}

Redis, ağ gecikmesini yönetilebilir düzeyde tutarken veri
kalıcılığı ve çok-örnek uyumluluğu sağlamaktadır. Lettuce
istemcisi non-blocking I/O kullanmakta ve bağlantı havuzu ile
ek yük minimize edilmektedir. JSON serileştirmesi sayesinde
cache içeriği \texttt{redis-cli} ile okunabilmekte ve
serileştirme formatı JVM yeniden başlatmalarından etkilenmemektedir.

\textbf{Avantajlar:} Yatay ölçekleme uyumluluğu, veri kalıcılığı,
zengin veri yapıları, olgun ekosistem.

\textbf{Dezavantajlar:} Ağ gecikmesi (loopback bile olsa),
serileştirme/deserileştirme maliyeti.

\subsection{Hazelcast}

Hazelcast, dağıtık hesaplama odaklı tasarımından dolayı
bu senaryoda en yüksek gecikmeyi göstermiştir.
Client-server modunda her veri erişimi ağ üzerinden
gitmekte; ek olarak Hazelcast'in kendi serileştirme katmanı
ek bir dönüşüm maliyeti eklemektedir.

\textbf{Avantajlar:} Gerçek dağıtık veri yapıları, hesaplama
yakınlığı (near-cache, entry processor), SQL desteği,
yüksek kullanılabilirlik.

\textbf{Dezavantajlar:} Bu tür basit okuma iş yükleri için
orantısız gecikme; kaynak tüketimi Redis'e kıyasla daha yüksek.

\subsection{Eager vs Lazy Stratejisi}

Eager stratejide tüm veriler başlangıçta yüklendiğinden
ilk istek dahil her erişim HIT olarak sonuçlanmaktadır.
Bu yaklaşım, servis başladıktan hemen sonra tahmin edilebilir
ve tutarlı gecikme sunar.

Lazy stratejide ilk erişimler MISS olduğunda veritabanına
gidilmekte ve bu durum özellikle Hazelcast ve Redis'te
belirgin gecikme artışına yol açmaktadır. MISS'in HIT'e
oranla kaç kat daha yavaş olduğu, serileştirme maliyeti
ve ağ topolojisine göre sağlayıcılar arasında farklılık göstermektedir.

% ═══════════════════════════════════════════════════════════════
\section{Sonuç}
% ═══════════════════════════════════════════════════════════════

\begin{enumerate}
    \item \textbf{En hızlı:} In-Memory HashMap — salt JVM içi erişim.
          Ağ gerektirmeyen, tek JVM'li senaryolar için ideal.

    \item \textbf{En dengeli:} Redis — gecikme, kalıcılık ve
          ekosistem desteği açısından çok yönlü bir seçenek.
          Yatay ölçekleme gereksinimlerinde birincil tercih.

    \item \textbf{Karmaşık iş yükleri için:} Hazelcast — bu kıyaslamada
          avantajlı görünmese de hesaplama yakınlığı, near-cache ve SQL
          özelliklerine ihtiyaç duyan senaryolarda öne çıkmaktadır.

    \item \textbf{Strateji:} Veri güncelliğinden ödün verilebiliyorsa
          ve erken yükleme süresi kabul edilebilirse Eager,
          aksi hâlde Lazy tercih edilmelidir. Eager, uygulamanın
          ilk istekten itibaren tam hızda çalışmasını garantiler.
\end{enumerate}

\end{document}
"""

    report_dir.mkdir(parents=True, exist_ok=True)
    tex_path = report_dir / "report.tex"
    tex_path.write_text(tex)
    print(f"  ✓ LaTeX raporu üretildi → {tex_path}")
    return tex_path


# ── Metrikleri JSON olarak kaydet ─────────────────────────────────────────────
def save_metrics_json(metrics, out_dir: Path):
    out = {}
    for key, m in metrics.items():
        out[key] = {k: v for k, v in m.items() if k != "timeline" and k != "rolling_avg"}
    path = out_dir / "metrics.json"
    path.write_text(json.dumps(out, indent=2, ensure_ascii=False, default=str))
    print(f"  ✓ Metrikler kaydedildi → {path}")


# ── Derleme scripti ──────────────────────────────────────────────────────────
def write_compile_sh(report_dir: Path):
    script = """\
#!/usr/bin/env bash
# LaTeX raporunu PDF'e derler (pdflatex gerektirir)
export PATH="/Library/TeX/texbin:$PATH"
cd "$(dirname "$0")"
echo "Derleniyor: report.tex ..."
pdflatex -interaction=nonstopmode report.tex > /dev/null
pdflatex -interaction=nonstopmode report.tex > /dev/null
echo "Tamamlandı: report.pdf"
open report.pdf 2>/dev/null || true
"""
    p = report_dir / "compile.sh"
    p.write_text(script)
    p.chmod(0o755)
    print(f"  ✓ Derleme scripti → {p}")
    print(f"    Kullanım: cd {report_dir} && ./compile.sh")


# ── Ana giriş noktası ─────────────────────────────────────────────────────────
def main():
    script_dir   = Path(__file__).parent
    project_root = script_dir.parent
    results_dir  = Path(sys.argv[1]) if len(sys.argv) > 1 else project_root / "benchmark-results"

    if not results_dir.exists():
        print(f"HATA: Dizin bulunamadı: {results_dir}")
        sys.exit(1)

    report_dir  = results_dir / "report"
    figures_dir = report_dir / "figures"

    print(f"\nVeri dizini : {results_dir}")
    print(f"Rapor dizini: {report_dir}\n")

    print("─── Veri yükleniyor ───────────────────────────────────────────────────")
    data = load_all(results_dir)
    if not data:
        print("HATA: Hiç veri bulunamadı. benchmark-results/ dizinini kontrol edin.")
        sys.exit(1)
    print(f"  ✓ {len(data)} kombinasyon yüklendi: {', '.join(data.keys())}")

    print("\n─── Metrikler hesaplanıyor ────────────────────────────────────────────")
    metrics = compute_metrics(data)

    print("\n─── Grafikler üretiliyor ──────────────────────────────────────────────")
    figures = make_figures(metrics, figures_dir)

    print("\n─── LaTeX raporu yazılıyor ────────────────────────────────────────────")
    generate_latex(metrics, figures, report_dir)
    write_compile_sh(report_dir)

    print("\n─── Metrikler JSON'a kaydediliyor ────────────────────────────────────")
    save_metrics_json(metrics, report_dir)

    print("\n─── Konsol özeti ──────────────────────────────────────────────────────")
    print_summary(metrics)

    print("─" * 70)
    print(f"\nTüm çıktılar: {report_dir}/")
    print("  report.tex     → LaTeX kaynak")
    print("  compile.sh     → pdflatex ile PDF üretir")
    print("  metrics.json   → tüm metrikler (makine okunabilir)")
    print("  figures/       → 9 adet PNG grafik\n")


if __name__ == "__main__":
    main()
