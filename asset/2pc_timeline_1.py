import json
import argparse
import sys
from pathlib import Path

def load_and_filter_spans(file_map, target_cid):
    all_spans = []
    for service, path in file_map.items():
        try:
            with open(path, 'r', encoding='utf-8') as f:
                data = json.load(f)
                traces = data.get('data', []) if isinstance(data, dict) else data
                if not isinstance(traces, list):
                    traces = [traces]

                for trace in traces:
                    processes = {pid: p.get("serviceName", pid) for pid, p in trace.get("processes", {}).items()}
                    for span in trace.get('spans', []):
                        tags = {t['key']: t.get('value') for t in span.get('tags', [])}
                        if tags.get('correlationId') == target_cid:
                            parent_id = None
                            if span.get('references'):
                                for ref in span['references']:
                                    if ref.get('refType') == 'CHILD_OF':
                                        parent_id = ref.get('spanID')

                            all_spans.append({
                                'id': span['spanID'],
                                'parent_id': parent_id,
                                'service': processes.get(span.get("processID", ""), service),
                                'op': span['operationName'],
                                'start': span['startTime'],
                                'duration': span['duration']
                            })
        except FileNotFoundError:
            pass
    return all_spans

HTML_TEMPLATE = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<title>2PC Timeline — {cid}</title>
<link href="https://fonts.googleapis.com/css2?family=JetBrains+Mono:wght@400;600&family=Syne:wght@600;800&display=swap" rel="stylesheet">
<style>
  :root {{
    --bg:        #0d0f14;
    --surface:   #13161e;
    --border:    #1f2330;
    --muted:     #3a3f52;
    --text:      #c9d1e8;
    --text-dim:  #5a6380;
    --accent:    #4f8ef7;

    --c-coordinator:       #17B8BE;
    --c-order-service:     #F5A623;
    --c-inventory-service: #A855F7;
    --c-payment-service:   #34D399;
    --c-default:           #60a5fa;
  }}

  * {{ box-sizing: border-box; margin: 0; padding: 0; }}

  body {{
    font-family: 'JetBrains Mono', monospace;
    background: var(--bg);
    color: var(--text);
    height: 100vh;
    display: flex;
    flex-direction: column;
    overflow: hidden;
  }}

  /* ── HEADER ── */
  .header {{
    background: var(--surface);
    border-bottom: 1px solid var(--border);
    padding: 14px 24px;
    display: flex;
    align-items: center;
    gap: 20px;
    flex-shrink: 0;
  }}
  .header-title {{
    font-family: 'Syne', sans-serif;
    font-size: 17px;
    font-weight: 800;
    letter-spacing: 0.04em;
    color: #fff;
  }}
  .header-title span {{ color: var(--accent); }}
  .pills {{
    display: flex;
    gap: 10px;
    margin-left: auto;
  }}
  .pill {{
    font-size: 11px;
    padding: 3px 10px;
    border-radius: 99px;
    border: 1px solid var(--border);
    color: var(--text-dim);
    letter-spacing: 0.05em;
  }}
  .pill b {{ color: var(--text); }}

  /* ── LEGEND ── */
  .legend {{
    display: flex;
    gap: 18px;
    padding: 8px 24px;
    background: var(--bg);
    border-bottom: 1px solid var(--border);
    flex-shrink: 0;
  }}
  .legend-item {{
    display: flex;
    align-items: center;
    gap: 6px;
    font-size: 11px;
    color: var(--text-dim);
    letter-spacing: 0.03em;
  }}
  .legend-dot {{
    width: 10px; height: 10px;
    border-radius: 2px;
  }}

  /* ── MAIN GRID ── */
  .main {{
    display: flex;
    flex: 1;
    overflow: hidden;
  }}

  /* ── LEFT PANE ── */
  .left-pane {{
    width: 280px;
    flex-shrink: 0;
    border-right: 1px solid var(--border);
    overflow-y: auto;
    background: var(--surface);
    display: flex;
    flex-direction: column;
  }}
  .left-pane-header {{
    height: 36px;            /* matches ruler height */
    border-bottom: 1px solid var(--border);
    padding: 0 14px;
    display: flex;
    align-items: center;
    font-size: 10px;
    color: var(--text-dim);
    letter-spacing: 0.08em;
    text-transform: uppercase;
    flex-shrink: 0;
  }}
  .span-row {{
    height: 34px;
    display: flex;
    align-items: center;
    padding: 0 14px;
    border-bottom: 1px solid var(--border);
    gap: 8px;
    transition: background 0.15s;
  }}
  .span-row:hover {{ background: rgba(79,142,247,0.06); }}
  .svc-dot {{
    width: 8px; height: 8px;
    border-radius: 50%;
    flex-shrink: 0;
  }}
  .svc-name {{
    font-size: 11px;
    font-weight: 600;
    color: var(--text);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
    max-width: 90px;
  }}
  .op-name {{
    font-size: 10px;
    color: var(--text-dim);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }}

  /* ── RIGHT PANE ── */
  .right-pane {{
    flex: 1;
    overflow-x: auto;
    overflow-y: auto;
    position: relative;
    display: flex;
    flex-direction: column;
  }}

  /* ── RULER ── */
  .ruler {{
    height: 36px;
    background: var(--surface);
    border-bottom: 2px solid var(--border);
    position: sticky;
    top: 0;
    z-index: 10;
    flex-shrink: 0;
    position: relative;
  }}
  .ruler-tick {{
    position: absolute;
    top: 0;
    display: flex;
    flex-direction: column;
    align-items: center;
  }}
  .ruler-tick-line {{
    width: 1px;
    height: 8px;
    background: var(--muted);
    margin-top: 4px;
  }}
  .ruler-tick-label {{
    font-size: 9px;
    color: var(--text-dim);
    letter-spacing: 0.04em;
    margin-top: 3px;
    white-space: nowrap;
    transform: translateX(-50%);
  }}
  .ruler-tick.major .ruler-tick-line {{
    height: 14px;
    background: var(--accent);
    opacity: 0.5;
  }}
  .ruler-tick.major .ruler-tick-label {{
    color: var(--accent);
    font-weight: 600;
  }}
  /* vertical grid lines behind spans */
  .grid-line {{
    position: absolute;
    top: 0;
    bottom: 0;
    width: 1px;
    background: var(--border);
    opacity: 0.5;
    pointer-events: none;
    z-index: 0;
  }}

  /* ── BARS ── */
  .bars-area {{
    flex: 1;
    position: relative;
  }}
  .bar-row {{
    height: 34px;
    border-bottom: 1px solid var(--border);
    position: relative;
  }}
  .bar-row:hover {{ background: rgba(255,255,255,0.02); }}
  .span-bar {{
    position: absolute;
    height: 14px;
    top: 10px;
    border-radius: 3px;
    min-width: 3px;
    cursor: pointer;
    transition: filter 0.15s, transform 0.1s;
    box-shadow: 0 1px 4px rgba(0,0,0,0.4);
  }}
  .span-bar:hover {{
    filter: brightness(1.25);
    transform: scaleY(1.3);
  }}
  .span-bar-label {{
    position: absolute;
    top: 50%;
    transform: translateY(-50%);
    left: 5px;
    font-size: 9px;
    font-weight: 600;
    color: rgba(0,0,0,0.7);
    white-space: nowrap;
    overflow: hidden;
    pointer-events: none;
  }}

  /* ── TOOLTIP ── */
  .tooltip {{
    position: fixed;
    background: #1a1d28;
    border: 1px solid var(--border);
    color: var(--text);
    padding: 10px 14px;
    border-radius: 6px;
    display: none;
    pointer-events: none;
    z-index: 9999;
    font-size: 11px;
    line-height: 1.7;
    box-shadow: 0 8px 24px rgba(0,0,0,0.5);
    max-width: 260px;
  }}
  .tooltip-svc {{
    font-family: 'Syne', sans-serif;
    font-size: 13px;
    font-weight: 700;
    margin-bottom: 4px;
  }}
  .tooltip-row {{ display: flex; justify-content: space-between; gap: 20px; }}
  .tooltip-key {{ color: var(--text-dim); }}
  .tooltip-val {{ color: #fff; font-weight: 600; }}

  /* ── SCROLLBAR ── */
  ::-webkit-scrollbar {{ width: 6px; height: 6px; }}
  ::-webkit-scrollbar-track {{ background: var(--bg); }}
  ::-webkit-scrollbar-thumb {{ background: var(--muted); border-radius: 3px; }}
</style>
</head>
<body>

<div class="header">
  <div class="header-title">2PC <span>Timeline</span></div>
  <div style="font-size:11px;color:var(--text-dim);font-family:'JetBrains Mono'">cid: <span style="color:var(--accent)">{cid}</span></div>
  <div class="pills">
    <div class="pill">Duration <b>{total_duration_ms} ms</b></div>
    <div class="pill">Services <b>{service_count}</b></div>
    <div class="pill">Spans <b>{span_count}</b></div>
  </div>
</div>

<div class="legend" id="legend"></div>

<div class="main">
  <div class="left-pane">
    <div class="left-pane-header">Service / Operation</div>
    <div id="left-rows"></div>
  </div>

  <div class="right-pane" id="right-pane">
    <div class="ruler" id="ruler"></div>
    <div class="bars-area" id="bars-area"></div>
  </div>
</div>

<div class="tooltip" id="tooltip"></div>

<script>
const spans = {spans_json};
const totalUs = {total_duration_us};
const minStart = {min_start};

const SERVICE_COLORS = {{
  'coordinator':        '#17B8BE',
  'order-service':      '#F5A623',
  'inventory-service':  '#A855F7',
  'payment-service':    '#34D399',
}};

function colorFor(svc) {{
  return SERVICE_COLORS[svc] || '#60a5fa';
}}

// ── Legend
const seen = {{}};
const legend = document.getElementById('legend');
spans.forEach(s => {{
  if (!seen[s.service]) {{
    seen[s.service] = true;
    const item = document.createElement('div');
    item.className = 'legend-item';
    item.innerHTML = `<div class="legend-dot" style="background:${{colorFor(s.service)}}"></div>${{s.service}}`;
    legend.appendChild(item);
  }}
}});

// ── Ruler
const ruler = document.getElementById('ruler');
const barsArea = document.getElementById('bars-area');
const RULER_WIDTH = ruler.parentElement.clientWidth || 900;

const NUM_TICKS = 10;
// Decide tick interval so labels are nice round numbers
const totalMs = totalUs / 1000;
const rawStep = totalMs / NUM_TICKS;
// Round to a nice number
const magnitude = Math.pow(10, Math.floor(Math.log10(rawStep)));
const niceStep = Math.ceil(rawStep / magnitude) * magnitude;

function buildRuler(containerWidth) {{
  ruler.innerHTML = '';
  // Draw major ticks + labels
  let t = 0;
  while (t <= totalMs + niceStep) {{
    const pct = (t / totalMs) * 100;
    if (pct > 102) break;

    const tick = document.createElement('div');
    tick.className = 'ruler-tick major';
    tick.style.left = pct + '%';

    const line = document.createElement('div');
    line.className = 'ruler-tick-line';

    const label = document.createElement('div');
    label.className = 'ruler-tick-label';
    label.textContent = t.toFixed(t < 1 ? 2 : (t < 10 ? 1 : 0)) + 'ms';

    tick.appendChild(line);
    tick.appendChild(label);
    ruler.appendChild(tick);

    // Grid line behind bars
    const gl = document.createElement('div');
    gl.className = 'grid-line';
    gl.style.left = pct + '%';
    barsArea.appendChild(gl);

    t = Math.round((t + niceStep) * 1000) / 1000; // avoid float drift
  }}

  // Minor ticks between majors (4 per major)
  const minorStep = niceStep / 4;
  let mt = minorStep;
  while (mt <= totalMs) {{
    const isMajor = Math.abs((mt / niceStep) - Math.round(mt / niceStep)) < 0.001;
    if (!isMajor) {{
      const pct = (mt / totalMs) * 100;
      const tick = document.createElement('div');
      tick.className = 'ruler-tick';
      tick.style.left = pct + '%';
      const line = document.createElement('div');
      line.className = 'ruler-tick-line';
      tick.appendChild(line);
      ruler.appendChild(tick);
    }}
    mt = Math.round((mt + minorStep) * 1000) / 1000;
  }}
}}

buildRuler(RULER_WIDTH);

// ── Rows
const leftRows = document.getElementById('left-rows');
const tooltip = document.getElementById('tooltip');

spans.forEach(span => {{
  const color = colorFor(span.service);
  const relStartMs = (span.start - minStart) / 1000;
  const durMs = span.duration / 1000;

  // Left row
  const lr = document.createElement('div');
  lr.className = 'span-row';
  lr.innerHTML = `
    <div class="svc-dot" style="background:${{color}}"></div>
    <div class="svc-name" title="${{span.service}}">${{span.service}}</div>
    <div class="op-name" title="${{span.op}}">${{span.op}}</div>
  `;
  leftRows.appendChild(lr);

  // Bar row
  const row = document.createElement('div');
  row.className = 'bar-row';

  const leftPct = (relStartMs / totalMs) * 100;
  const widthPct = Math.max((durMs / totalMs) * 100, 0.3);

  const bar = document.createElement('div');
  bar.className = 'span-bar';
  bar.style.cssText = `left:${{leftPct}}%;width:${{widthPct}}%;background:${{color}};`;

  // inline label if bar is wide enough
  if (widthPct > 5) {{
    const lbl = document.createElement('div');
    lbl.className = 'span-bar-label';
    lbl.textContent = span.op;
    bar.appendChild(lbl);
  }}

  bar.addEventListener('mousemove', e => {{
    tooltip.style.display = 'block';
    tooltip.innerHTML = `
      <div class="tooltip-svc" style="color:${{color}}">${{span.service}}</div>
      <div style="color:var(--text-dim);font-size:10px;margin-bottom:6px">${{span.op}}</div>
      <div class="tooltip-row"><span class="tooltip-key">Start</span><span class="tooltip-val">${{relStartMs.toFixed(3)}} ms</span></div>
      <div class="tooltip-row"><span class="tooltip-key">Duration</span><span class="tooltip-val">${{durMs.toFixed(3)}} ms</span></div>
      <div class="tooltip-row"><span class="tooltip-key">Span ID</span><span class="tooltip-val" style="font-size:9px">${{span.id.slice(0,12)}}…</span></div>
    `;
    tooltip.style.left = (e.clientX + 14) + 'px';
    tooltip.style.top  = (e.clientY + 14) + 'px';
  }});
  bar.addEventListener('mouseout', () => {{ tooltip.style.display = 'none'; }});

  row.appendChild(bar);
  barsArea.appendChild(row);

  // Sync scroll between left-rows and bars-area
  const rightPane = document.getElementById('right-pane');
  const leftPane = document.querySelector('.left-pane');
  rightPane.addEventListener('scroll', () => {{
    leftPane.scrollTop = rightPane.scrollTop;
  }});
  leftPane.addEventListener('scroll', () => {{
    rightPane.scrollTop = leftPane.scrollTop;
  }});
}});
</script>
</body>
</html>
"""

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--cid", required=True)
    args = parser.parse_args()

    files = {
        "coordinator": "coordinator_trace.json",
        "order-service": "order_trace.json",
        "inventory-service": "inventory_trace.json",
        "payment-service": "payment_trace.json"
    }

    spans = load_and_filter_spans(files, args.cid)

    if not spans:
        print("No spans found for correlationId:", args.cid)
        sys.exit(1)

    spans.sort(key=lambda x: x['start'])

    min_start = spans[0]['start']
    max_end = max(s['start'] + s['duration'] for s in spans)

    padding_factor = 0.05
    total_duration_us = int((max_end - min_start) * (1 + padding_factor))

    html = HTML_TEMPLATE.format(
        cid=args.cid,
        total_duration_ms=round(total_duration_us / 1000, 2),
        service_count=len(set(s['service'] for s in spans)),
        span_count=len(spans),
        spans_json=json.dumps(spans),
        total_duration_us=total_duration_us,
        min_start=min_start
    )

    Path("jaeger_clone_ui.html").write_text(html, encoding="utf-8")
    print("✅ Generated: jaeger_clone_ui.html")

if __name__ == "__main__":
    main()