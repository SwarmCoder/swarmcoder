"""Spark concurrency benchmark: 1 sequential stream vs 10 concurrent streams.

Mimics the swarm shape: identical shared prefix (prefix-cache friendly), small
persona suffix per request, bounded completion. Reports per-request latency,
wall time, and aggregate throughput so the 'N concurrent ~ cost of 1' premise
can be checked with numbers.
"""
import json, time, urllib.request, threading

BASE = "http://192.168.0.10:8000/v1/chat/completions"
MODEL = "qwen36-27b"
MAX_TOKENS = 256
N = 10

SHARED_PREFIX = (
    "You are a software engineering worker agent. Implement exactly the task described "
    "below in the repository you have tools for. Work in small steps: inspect with "
    "read/exec; modify with write_file (full file content) or apply_diff (unified diff); "
    "verify by running builds/tests with exec; then call report_done when complete.\n\n"
    "Task: Add a public method multiply(int a, int b) returning a*b to the Calculator "
    "class in src/main/java/com/example/calc/Calculator.java. Do not change existing "
    "behavior. You may ONLY modify these paths: [src/main/java/com/example/calc]\n\n"
    + "Repository map:\n" + "\n".join(
        f"  src/main/java/com/example/pkg{i}/Class{i}.java — class Class{i} with methods m{i}a, m{i}b"
        for i in range(60))  # pad the prefix toward a realistic size
)

def call(persona_idx, out):
    body = json.dumps({
        "model": MODEL,
        "messages": [
            {"role": "system", "content": SHARED_PREFIX + f"\n\nPersona: worker-{persona_idx}."},
            {"role": "user", "content": "Write the new Calculator.java file content now."},
        ],
        "max_tokens": MAX_TOKENS,
        "temperature": 0.2 + 0.05 * persona_idx,
        "chat_template_kwargs": {"enable_thinking": False},
    }).encode()
    req = urllib.request.Request(BASE, data=body, headers={"Content-Type": "application/json"})
    t0 = time.time()
    with urllib.request.urlopen(req, timeout=300) as r:
        resp = json.load(r)
    dt = time.time() - t0
    usage = resp.get("usage", {})
    out.append((dt, usage.get("completion_tokens", 0), usage.get("prompt_tokens", 0)))

# Warmup (loads weights path + warms prefix cache)
w = []
call(0, w)
print(f"warmup: {w[0][0]:.1f}s ({w[0][1]} completion tokens, {w[0][2]} prompt tokens)")

# Phase A: 3 sequential single requests
seq = []
t0 = time.time()
for i in range(3):
    call(i, seq)
seq_wall = time.time() - t0
seq_avg = sum(d for d, _, _ in seq) / len(seq)
seq_tps = sum(c for _, c, _ in seq) / seq_wall
print(f"\nSEQUENTIAL x3: wall={seq_wall:.1f}s avg-latency={seq_avg:.1f}s "
      f"per-stream={seq[0][1]/seq[0][0]:.1f} tok/s")

# Phase B: 10 concurrent requests
conc = []
threads = [threading.Thread(target=call, args=(i, conc)) for i in range(N)]
t0 = time.time()
for t in threads:
    t.start()
for t in threads:
    t.join()
conc_wall = time.time() - t0
lat = sorted(d for d, _, _ in conc)
total_completion = sum(c for _, c, _ in conc)
print(f"\nCONCURRENT x{N}: wall={conc_wall:.1f}s "
      f"latency min/med/max={lat[0]:.1f}/{lat[len(lat)//2]:.1f}/{lat[-1]:.1f}s "
      f"aggregate={total_completion/conc_wall:.1f} tok/s")

print(f"\nVERDICT: {N} concurrent took {conc_wall:.1f}s vs {seq_avg:.1f}s for one "
      f"({conc_wall/seq_avg:.2f}x one request's latency, "
      f"vs {N}.0x if serialized) — aggregate throughput "
      f"{(total_completion/conc_wall)/(seq[0][1]/seq[0][0]):.1f}x the single stream.")
