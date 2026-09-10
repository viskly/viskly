#!/usr/bin/env bash
# Copyright (C) 2026 Karol Krawczyk
# SPDX-License-Identifier: GPL-3.0-only
# Downloads the whisper.cpp model into ~/.viskly/models.
#
# Why this is not just "curl -o":
#  1) HuggingFace serves these files over HTTP/2 through a CDN. Some networks (VPNs,
#     proxies, antivirus) reset the connection right after the last byte — curl exits
#     with error 35 even though the file on disk is complete. So we force HTTP/1.1 and
#     do NOT trust curl's exit code; we check the file itself.
#  2) The download resumes (-C -), so a break halfway does not throw away the progress.

set -euo pipefail

MODEL="${1:-large-v3-turbo-q5_0}"
DIR="${HOME}/.viskly/models"
FILE="${DIR}/ggml-${MODEL}.bin"
ATTEMPTS=6

# The model with a known checksum comes from the commit that checksum belongs to, the same
# one the application uses, so a later upload under the same name cannot break it. Other
# models come from main and are only checked for the ggml header.
revision_for() {
  case "$1" in
    large-v3-turbo-q5_0) echo "98aa99a0a9db05ae2342309f5096248665f7cba3" ;;
    *) echo "main" ;;
  esac
}
URL="https://huggingface.co/ggerganov/whisper.cpp/resolve/$(revision_for "${MODEL}")/ggml-${MODEL}.bin"

# Checksums from HuggingFace. For models not listed here we only check the ggml header
# and that the file is not suspiciously small.
sha_for() {
  case "$1" in
    large-v3-turbo-q5_0) echo "394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2" ;;
    *) echo "" ;;
  esac
}

file_size() { [[ -f "$1" ]] && wc -c < "$1" | tr -d ' ' || echo 0; }

# Returns 0 when the file is a complete model.
verify() {
  local f="$1" want size magic got
  size="$(file_size "$f")"
  [[ "${size}" -lt 10000000 ]] && return 1

  # ggml files start with the magic number 0x67676d6c, which on disk (little endian)
  # reads as "lmgg". Anything else means an error page, not a model.
  magic="$(head -c 4 "$f" 2>/dev/null || true)"
  [[ "${magic}" != "lmgg" ]] && return 1

  want="$(sha_for "${MODEL}")"
  if [[ -n "${want}" ]]; then
    echo "Checking the checksum ($(( size / 1024 / 1024 )) MB, this takes a moment)..."
    got="$(shasum -a 256 "$f" | cut -d' ' -f1)"
    [[ "${got}" == "${want}" ]] || { echo "Checksum mismatch: ${got}"; return 1; }
  fi
  return 0
}

if [[ -f "${FILE}" ]] && verify "${FILE}"; then
  echo "Model already present and valid: ${FILE}"
  exit 0
fi

mkdir -p "${DIR}"

for attempt in $(seq 1 "${ATTEMPTS}"); do
  have="$(file_size "${FILE}")"
  if [[ "${have}" -gt 0 ]]; then
    echo "Attempt ${attempt}/${ATTEMPTS} — resuming from $(( have / 1024 / 1024 )) MB"
  else
    echo "Attempt ${attempt}/${ATTEMPTS} — downloading ${MODEL} (574 MB)"
  fi

  # --http1.1 is the heart of the fix: a connection reset at the end of a transfer is the
  # classic symptom of HTTP/2 passing through a middlebox that does not close the stream.
  # || true, because verify() decides whether this worked, not curl's exit code.
  curl -L --http1.1 --progress-bar \
       --retry 3 --retry-delay 2 --retry-all-errors \
       --speed-time 60 --speed-limit 1024 \
       -C - -o "${FILE}" "${URL}" || true

  echo
  if verify "${FILE}"; then
    echo "Done: ${FILE} ($(du -h "${FILE}" | cut -f1))"
    exit 0
  fi

  after="$(file_size "${FILE}")"
  if [[ "${after}" -le "${have}" ]]; then
    echo "No progress — waiting 5 s"
    sleep 5
  fi
done

echo
echo "Could not download the whole file in ${ATTEMPTS} attempts."
echo "The partial file stays at ${FILE} — run the script again to finish it."
echo "If this keeps happening, try it by hand:"
echo "  curl -L --http1.1 -C - -o '${FILE}' '${URL}'"
echo "  shasum -a 256 '${FILE}'   # expected: $(sha_for "${MODEL}")"
exit 1
