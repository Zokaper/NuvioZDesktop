#!/usr/bin/env bash
#
# run-desktop-tests-split.sh -- the full desktopTest suite, in four parts that each fit the
# 20-minute task timeout. See scripts/desktop-test-split.init.gradle for the parts.
#
# Usage: scripts/run-desktop-tests-split.sh [output-dir]
#
# Needs JAVA_HOME set to the JBR SDK (see AGENTS.md). Never run it alongside another Gradle
# invocation in this repo. Each part starts from a deleted results directory and --rerun, so a
# count can never include a previous run's XML; its results are copied to <output-dir>/results-<part>
# and the totals are printed at the end. A part whose Gradle run failed is reported as such.

set -uo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
out_dir="${1:-${repo_root}/composeApp/build/desktop-test-split}"
init_script="${repo_root}/scripts/desktop-test-split.init.gradle"
results_dir="${repo_root}/composeApp/build/test-results/desktopTest"
mkdir -p "${out_dir}"
cd "${repo_root}"

summary="${out_dir}/summary.txt"
echo "HEAD $(git rev-parse HEAD)" > "${summary}"
overall=0
for part in rest playback downloads e2e; do
    rm -rf "${results_dir}"
    start=$(date +%s)
    ./gradlew --init-script "${init_script}" -PnzPart="${part}" :composeApp:desktopTest --rerun \
        --console=plain > "${out_dir}/part-${part}.log" 2>&1
    rc=$?
    [[ ${rc} -eq 0 ]] || overall=1
    rm -rf "${out_dir}/results-${part}"
    cp -r "${results_dir}" "${out_dir}/results-${part}" 2>/dev/null
    echo "part ${part} rc=${rc} secs=$(( $(date +%s) - start ))" >> "${summary}"
done

# Windows has `python`, and a `python3` stub that only opens the Store.
py=python3
python3 -c "" 2>/dev/null || py=python
"${py}" - "${out_dir}" >> "${summary}" <<'PY'
import glob, os, sys
import xml.etree.ElementTree as ET
out = sys.argv[1]
seen, dup, tot = set(), 0, dict(tests=0, failures=0, errors=0, skipped=0)
for part in ("rest", "playback", "downloads", "e2e"):
    p = dict(tests=0, failures=0, errors=0, skipped=0)
    for f in glob.glob(os.path.join(out, "results-" + part, "*.xml")):
        r = ET.parse(f).getroot()
        for k in p:
            p[k] += int(r.get(k, 0))
        for c in r.iter("testcase"):
            key = (c.get("classname"), c.get("name"))
            dup += key in seen
            seen.add(key)
    for k in p:
        tot[k] += p[k]
    print(part, p)
print("total", tot, "duplicates", dup)
PY
cat "${summary}"
exit ${overall}
