#!/usr/bin/env bash
# Local end-to-end smoke check.
# Starts the service (default port 8080), walks every HTTP path, and verifies:
#   - single simulation, batch (serial vs parallel identical), calibration
#   - capacity rejection (every entry, every resource dimension)
#   - noisy-observation calibration: non-degenerate, stable, covers true beta
#   - unidentifiable data -> PARAMETER_UNIDENTIFIABLE
# Requires: a JDK, Maven, Python 3 on PATH.
set -euo pipefail
cd "$(dirname "$0")/.."

PORT="${PORT:-8080}"; BASE="http://localhost:${PORT}"
LOG=".runlogs/e2e-smoke.log"; mkdir -p .runlogs
echo "[smoke] starting service on port ${PORT} ..."
mvn -q spring-boot:run -Dspring-boot.run.arguments="--server.port=${PORT}" >"${LOG}" 2>&1 &
MVN_PID=$!
cleanup() { kill "${MVN_PID}" 2>/dev/null || true; pkill -P "${MVN_PID}" 2>/dev/null || true; }
trap cleanup EXIT
for _ in $(seq 1 90); do
  code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "${BASE}/api/simulation/single" \
      -H 'Content-Type: application/json' -d '{}' || true)
  [ "${code}" != "000" ] && break
  sleep 2
done

python3 - "$BASE" <<'PY'
import json, random, sys, urllib.request, urllib.error
BASE = sys.argv[1]

def post(path, payload):
    req = urllib.request.Request(BASE + path, data=json.dumps(payload).encode(),
                                 headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req) as r:
            return r.status, json.loads(r.read())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read())

SC = {"population": 5000, "initialInfected": 15, "beta": 0.35, "gamma": 0.08,
      "horizonHours": 120, "interventions": []}
checks = 0
def ok(cond, label):
    global checks
    assert cond, "FAILED: " + label
    checks += 1
    print("  ok:", label)

print("[1] single simulation")
st, r = post("/api/simulation/single", {"scenario": SC, "seed": 42})
ok(st == 200 and r["seed"] == 42 and r["peakInfected"] > 0, "single run, seed echoed")
st, r2 = post("/api/simulation/single", {"scenario": SC, "seed": 42})
ok(r2["peakInfected"] == r["peakInfected"], "same seed -> identical result")

print("[2] batch serial == parallel")
def batch(par):
    return post("/api/simulation/batch", {"scenario": SC, "replications": 30,
                "masterSeed": 909, "parallelism": par, "timeoutMillis": 0})
st, a = batch(1); st2, b = batch(8)
ok(st == 200 and st2 == 200, "both batches succeed")
for k in ("meanPeakInfected", "peakInfectedP2_5", "peakInfectedP97_5", "meanTotalRecovered"):
    ok(a[k] == b[k], f"aggregate {k} identical")
ok([x["seed"] for x in a["results"]] == [x["seed"] for x in b["results"]],
   "per-replication seeds identical")

print("[3] noisy-observation calibration")
st, batch300 = post("/api/simulation/batch", {"scenario": SC, "replications": 300,
        "masterSeed": 777, "parallelism": 4, "timeoutMillis": 0})
mean = [0.0] * SC["horizonHours"]
for x in batch300["results"]:
    for t, v in enumerate(x["hourlyInfected"]):
        mean[t] += v
mean = [v / 300 for v in mean]
random.seed(4242)
obs = [max(0.0, m + 60.0 * random.gauss(0, 1)) for m in mean]
def calibrate(par):
    return post("/api/calibration/beta", {
        "baseScenario": {**SC, "beta": 0.2}, "observedInfected": obs,
        "betaLower": 0.1, "betaUpper": 0.8, "replications": 300,
        "masterSeed": 777, "parallelism": par, "maxEvaluations": 200})
st, c1 = calibrate(8); _, c2 = calibrate(8); _, cs = calibrate(1)
ok(st == 200 and c1["success"], "calibration succeeds on noisy observations")
w = c1["betaUpper95"] - c1["betaLower95"]
ok(w > 0.001, f"interval non-degenerate (width={w:.5f})")
ok(c1["betaLower95"] < c1["estimatedBeta"] < c1["betaUpper95"], "interval brackets estimate")
ok(c1["betaLower95"] <= 0.35 <= c1["betaUpper95"], "95% interval covers true beta 0.35")
ok(c1["estimatedBeta"] == c2["estimatedBeta"] == cs["estimatedBeta"],
   "identical estimate across repeats and parallelism")
ok(c1["betaLower95"] == cs["betaLower95"] and c1["betaUpper95"] == cs["betaUpper95"],
   "identical interval across parallelism")
print(f"   beta={c1['estimatedBeta']:.4f} CI95=[{c1['betaLower95']:.4f}, "
      f"{c1['betaUpper95']:.4f}] width={w:.5f}")

print("[4] capacity rejection (before execution)")
def rejected(path, body, code, where):
    st, r = post(path, body)
    ok(st == 422, f"{where}: HTTP 422")
    if path == "/api/calibration/beta":
        ok(r.get("failureReason") == "RESOURCE_LIMIT_EXCEEDED"
           and r.get("capacityReason") == code, f"{where}: {code}")
    else:
        ok(r.get("error") == "CAPACITY_EXCEEDED" and r.get("reason") == code,
           f"{where}: {code}")
rejected("/api/simulation/single", {"scenario": {**SC, "population": 99_999_999}, "seed": 1},
         "POPULATION_TOO_LARGE", "single/population")
rejected("/api/simulation/single", {"scenario": {**SC, "horizonHours": 200_000}, "seed": 1},
         "HORIZON_TOO_LONG", "single/horizon")
rejected("/api/simulation/batch", {"scenario": SC, "replications": 5000,
         "masterSeed": 1, "parallelism": 1, "timeoutMillis": 0},
         "TOO_MANY_REPLICATIONS", "batch/replications")
rejected("/api/simulation/batch", {"scenario": SC, "replications": 10,
         "masterSeed": 1, "parallelism": 32, "timeoutMillis": 0},
         "PARALLELISM_TOO_HIGH", "batch/parallelism")
rejected("/api/simulation/batch", {"scenario": {**SC, "horizonHours": 20_010},
         "replications": 1000, "masterSeed": 1, "parallelism": 1, "timeoutMillis": 0},
         "TOTAL_WORK_TOO_LARGE", "batch/totalWork")
rejected("/api/calibration/beta", {
        "baseScenario": {"population": 1000, "initialInfected": 15, "beta": 0.3,
                        "gamma": 0.05, "horizonHours": 20_001, "interventions": []},
        "observedInfected": [10.0] * 100, "betaLower": 0.1, "betaUpper": 0.8,
        "replications": 100, "masterSeed": 1, "parallelism": 1, "maxEvaluations": 10},
        "TOTAL_WORK_TOO_LARGE", "calibration/totalWork")

print("[5] unidentifiable data")
st, r = post("/api/calibration/beta", {
    "baseScenario": {**SC, "beta": 0.3}, "observedInfected": [100.0] * 120,
    "betaLower": 0.05, "betaUpper": 0.9, "replications": 300,
    "masterSeed": 777, "parallelism": 4, "maxEvaluations": 200})
ok(st == 422 and r["failureReason"] == "PARAMETER_UNIDENTIFIABLE",
   "flat plateau -> PARAMETER_UNIDENTIFIABLE")

print(f"\nSMOKE PASSED: {checks} assertions")
PY
