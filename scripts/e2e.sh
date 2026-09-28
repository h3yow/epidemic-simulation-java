#!/usr/bin/env bash
set -u
BASE=${E2E_BASE:-http://localhost:8080}
PASS=0; FAIL=0
check() { # desc expected_substr actual
  if [[ "$3" == *"$2"* ]]; then echo "PASS: $1"; PASS=$((PASS+1));
  else echo "FAIL: $1"; echo "  expected substring: $2"; echo "  got: $3"; FAIL=$((FAIL+1)); fi
}
scenario() { # beta horizon
  printf '{"population":5000,"initialInfected":15,"beta":%s,"gamma":0.08,"horizonHours":%s,"interventions":[]}' "$1" "$2"
}

echo "=== 1. single simulation ==="
R=$(curl -s -w '\n%{http_code}' -X POST "$BASE/api/simulation/run" -H 'Content-Type: application/json' \
  -d "{\"scenario\":$(scenario 0.35 120),\"seed\":42}")
CODE=$(echo "$R" | tail -1); BODY=$(echo "$R" | sed '$d')
check "single run HTTP 200" "200" "$CODE"
check "single run seed echoed" '"seed":42' "$BODY"
check "single run has 120 hourly points" "$(echo "$BODY" | jq '.hourlyInfected|length')" "120"

echo "=== 2. batch serial vs parallel identical ==="
SER=$(curl -s -X POST "$BASE/api/simulation/batch" -H 'Content-Type: application/json' \
  -d "{\"scenario\":$(scenario 0.35 120),\"replications\":24,\"masterSeed\":20260928,\"parallelism\":1,\"timeoutMillis\":0}")
PAR=$(curl -s -X POST "$BASE/api/simulation/batch" -H 'Content-Type: application/json' \
  -d "{\"scenario\":$(scenario 0.35 120),\"replications\":24,\"masterSeed\":20260928,\"parallelism\":8,\"timeoutMillis\":0}")
echo "  serial   meanPeak=$(echo "$SER"|jq .meanPeakInfected) p2.5=$(echo "$SER"|jq .peakInfectedP2_5) p97.5=$(echo "$SER"|jq .peakInfectedP97_5)"
echo "  parallel meanPeak=$(echo "$PAR"|jq .meanPeakInfected) p2.5=$(echo "$PAR"|jq .peakInfectedP2_5) p97.5=$(echo "$PAR"|jq .peakInfectedP97_5)"
[ "$(echo "$SER"|jq .meanPeakInfected)" = "$(echo "$PAR"|jq .meanPeakInfected)" ] && { echo "PASS: meanPeak identical"; PASS=$((PASS+1)); } || { echo "FAIL: meanPeak differs"; FAIL=$((FAIL+1)); }
[ "$(echo "$SER"|jq .peakInfectedP2_5)" = "$(echo "$PAR"|jq .peakInfectedP2_5)" ] && { echo "PASS: p2.5 identical"; PASS=$((PASS+1)); } || { echo "FAIL: p2.5"; FAIL=$((FAIL+1)); }
[ "$(echo "$SER"|jq .peakInfectedP97_5)" = "$(echo "$PAR"|jq .peakInfectedP97_5)" ] && { echo "PASS: p97.5 identical"; PASS=$((PASS+1)); } || { echo "FAIL: p97.5"; FAIL=$((FAIL+1)); }

echo "=== 3. calibration on noisy observations ==="
python3 - <<'PY'
import json, subprocess, random, urllib.request, os, urllib.error
BASE=os.environ.get("E2E_BASE","http://localhost:8080")
def batch(beta, reps=200, seed=777, par=4):
    body=json.dumps({"scenario":{"population":5000,"initialInfected":15,"beta":beta,"gamma":0.08,"horizonHours":120,"interventions":[]},
                     "replications":reps,"masterSeed":seed,"parallelism":par,"timeoutMillis":0}).encode()
    return json.load(urllib.request.urlopen(urllib.request.Request(BASE+"/api/simulation/batch",body,{"Content-Type":"application/json"})))
b=batch(0.35)
h=120; mean=[0.0]*h
for r in b["results"]:
    for t in range(h): mean[t]+=r["hourlyInfected"][t]
random.seed(31337)
noisy=[max(0.0, mean[t]/200 + 30*random.gauss()) for t in range(h)]
def cal(par):
    body=json.dumps({"baseScenario":{"population":5000,"initialInfected":15,"beta":0.2,"gamma":0.08,"horizonHours":120,"interventions":[]},
                     "observedInfected":noisy,"betaLower":0.1,"betaUpper":0.8,
                     "replications":200,"masterSeed":777,"parallelism":par,"maxEvaluations":400}).encode()
    req=urllib.request.Request(BASE+"/api/calibration/beta",body,{"Content-Type":"application/json"})
    try:
        return 200, json.load(urllib.request.urlopen(req))
    except urllib.error.HTTPError as e:
        return e.code, json.load(e)
c1=cal(1); c4=cal(4); c1b=cal(1)
for name,(code,r) in [("serial",c1),("parallel4",c4),("serial-repeat",c1b)]:
    print(f"{name}: http={code} success={r.get('success')} beta={r.get('estimatedBeta'):.6f} "
          f"CI=[{r.get('betaLower95'):.6f},{r.get('betaUpper95'):.6f}] width={r.get('betaUpper95')-r.get('betaLower95'):.6f} "
          f"noiseSigma={r.get('noiseSigma'):.3f}")
assert c1[1]["estimatedBeta"]==c4[1]["estimatedBeta"]==c1b[1]["estimatedBeta"], "beta differs across parallelism/repeat"
assert c1[1]["betaLower95"]==c4[1]["betaLower95"]==c1b[1]["betaLower95"], "CI lo differs"
assert c1[1]["betaUpper95"]==c4[1]["betaUpper95"]==c1b[1]["betaUpper95"], "CI hi differs"
assert c1[1]["betaUpper95"]-c1[1]["betaLower95"] > 1e-4, "interval collapsed"
print("PARITY_OK NONDEGENERATE_OK")
PY

echo "=== 4. capacity rejections (422, distinguishable reason) ==="
r422() { curl -s -w '|%{http_code}' "$@"; }
R=$(r422 -X POST "$BASE/api/simulation/run" -H 'Content-Type: application/json' -d "{\"scenario\":$(scenario 0.35 100000),\"seed\":1}")
echo "  single oversize -> $R"
check "single oversize 422" "422" "$R"
check "single reason HORIZON_HOURS" '"reason":"HORIZON_HOURS"' "$R"

R=$(r422 -X POST "$BASE/api/simulation/batch" -H 'Content-Type: application/json' \
  -d "{\"scenario\":$(scenario 0.35 120),\"replications\":5000,\"masterSeed\":1,\"parallelism\":4,\"timeoutMillis\":0}")
echo "  batch oversize -> $R"
check "batch oversize 422" "422" "$R"
check "batch reason REPLICATIONS" '"reason":"REPLICATIONS"' "$R"

OBS=$(python3 -c "import json;print(json.dumps([100.0]*120))")
R=$(r422 -X POST "$BASE/api/calibration/beta" -H 'Content-Type: application/json' \
  -d "{\"baseScenario\":$(scenario 0.2 120),\"observedInfected\":$OBS,\"betaLower\":0.1,\"betaUpper\":0.8,\"replications\":1000,\"masterSeed\":1,\"parallelism\":4,\"maxEvaluations\":500}")
echo "  calibration oversize -> $R"
check "calibration oversize 422" "422" "$R"
check "calibration failureReason" '"failureReason":"RESOURCE_LIMIT_EXCEEDED"' "$R"
check "calibration detail TOTAL_SIMULATED_HOURS" '"detailReason":"TOTAL_SIMULATED_HOURS"' "$R"

echo "=== 5. invalid scenario still 400 ==="
R=$(r422 -X POST "$BASE/api/simulation/run" -H 'Content-Type: application/json' \
  -d "{\"scenario\":{\"population\":0,\"initialInfected\":15,\"beta\":0.35,\"gamma\":0.08,\"horizonHours\":120,\"interventions\":[]},\"seed\":1}")
echo "  invalid -> $R"
check "invalid scenario 400" "400" "$R"

echo; echo "RESULT pass=$PASS fail=$FAIL"
