#!/usr/bin/env bash
#
# Turns a Gradle log into a Markdown snippet for the platform bump PR body.
#
# Usage: build-report.sh <gradle-log> <outcome: success|failure>
#        TASKS="buildPlugin check" build-report.sh ...   (label for the status line)
#
# Prints: status line, Kotlin compiler errors, Gradle failure blocks, Plugin
# Verifier verdicts, a count of deprecation warnings and the log tail.
# Output is capped so the PR body stays well below GitHub's 65536 char limit.

set -euo pipefail

log=${1:?log file required}
outcome=${2:?outcome required}
max_chars=${MAX_CHARS:-55000}
tasks=${TASKS:-buildPlugin check verifyPlugin}

section() {
  # $1 = title, stdin = content; emits a fenced block only when content is non-empty
  local content
  content=$(cat)
  [[ -z "$content" ]] && return 0
  # shellcheck disable=SC2016 # backticks are literal Markdown
  printf '\n### %s\n\n```text\n%s\n```\n' "$1" "$content"
}

{
  if [[ "$outcome" == "success" ]]; then
    echo "✅ \`${tasks}\` passed with the new platform versions."
  else
    echo "❌ \`${tasks}\` **failed** with the new platform versions."
  fi

  if [[ ! -s "$log" ]]; then
    printf '\n_No build log was produced._\n'
    exit 0
  fi

  # Kotlin compiler errors: "e: file:///...kt:12:5 Unresolved reference ..."
  grep -E '^e: ' "$log" | sed "s#file://${GITHUB_WORKSPACE:-$PWD}/##" | sort -u | head -n 150 \
    | section "Compilation errors" || true

  # Gradle's "What went wrong" blocks
  awk '/^\* What went wrong:/{p=1; n=0} p{print; n++} /^\* Try:/{p=0} n>40{p=0}' "$log" \
    | grep -v '^\* Try:' | head -n 120 | section "Gradle failures" || true

  # Plugin Verifier verdict lines, e.g. "Plugin ... against CL-262.x: Compatible. 2 usages of deprecated API"
  grep -E '^Plugin .* against .*: ' "$log" | sort -u | head -n 40 | section "Plugin Verifier" || true

  deprecations=$(grep -cE '^w: .*[Dd]eprecat' "$log" || true)
  if [[ "${deprecations:-0}" -gt 0 ]]; then
    printf '\n%s Kotlin deprecation warning(s) — see the full log artifact.\n' "$deprecations"
  fi

  if [[ "$outcome" != "success" ]]; then
    tail -n 60 "$log" | section "Log tail"
  fi
} | head -c "$max_chars"
