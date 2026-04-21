#!/usr/bin/env bash
# fetch-and-deploy-model.sh
# ---------------------------------------------------------------------------
# Download a LiteRT-LM Gemma model from Hugging Face and push it onto the
# connected S10 at /sdcard/Models/.
#
# Usage:
#   ./scripts/fetch-and-deploy-model.sh                # default: gemma-4-E4B-it
#   ./scripts/fetch-and-deploy-model.sh gemma-4-E2B-it # pick a different variant
#
# Environment:
#   Bash on Windows via Git Bash (same command set works on Linux/macOS).
#   Requires: hf (huggingface_hub CLI), adb, sha256sum, curl.
#
# Idempotent:
#   - If the local file already exists AND its SHA256 matches the expected
#     value in models/checksums.txt, the download step is skipped.
#   - If the file is already on the device AND the byte size matches the
#     local file, the push step is skipped.
# ---------------------------------------------------------------------------

set -euo pipefail

MODEL_NAME="${1:-gemma-4-E4B-it}"
HF_REPO="litert-community/${MODEL_NAME}-litert-lm"
# LiteRT-LM's own docs use both extensions. Try .litertlm first, .task second.
MODEL_FILE_PRIMARY="${MODEL_NAME}.litertlm"
MODEL_FILE_FALLBACK="${MODEL_NAME}-litert-lm.task"

# Download target — outside the project tree so huge files never get committed.
DOWNLOAD_DIR="${HOME}/Downloads/litertlm-models"
DEVICE_DIR="/sdcard/Models"

# Checksums live in the repo next to this script's caller.
SCRIPT_DIR="$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"
PROJECT_ROOT="$( cd -- "${SCRIPT_DIR}/.." &> /dev/null && pwd )"
CHECKSUMS_FILE="${PROJECT_ROOT}/models/checksums.txt"

# ---------- helpers --------------------------------------------------------

color() { printf '\033[%sm%s\033[0m\n' "$1" "$2"; }
info()  { color "1;34" "[info] $*"; }
warn()  { color "1;33" "[warn] $*" >&2; }
err()   { color "1;31" "[err]  $*" >&2; }

require() {
    local cmd="$1"; local hint="$2"
    if ! command -v "$cmd" >/dev/null 2>&1; then
        err "required command '${cmd}' not found in PATH"
        err "hint: ${hint}"
        exit 1
    fi
}

expected_sha256_for() {
    # Returns expected SHA256 for a file name, or empty string if unknown.
    local fname="$1"
    [[ -f "$CHECKSUMS_FILE" ]] || return 0
    awk -v fname="$fname" '
        /^[[:space:]]*#/ { next }
        /^[[:space:]]*$/ { next }
        $2 == fname { print $1; exit }
    ' "$CHECKSUMS_FILE"
}

# ---------- prerequisite checks --------------------------------------------

info "model: ${MODEL_NAME}"
info "source: huggingface.co/${HF_REPO}"

require adb         "install Android platform-tools (choco install adb / brew install --cask android-platform-tools)"
require sha256sum   "ships with Git Bash on Windows; install coreutils on macOS"
require curl        "ships with Git Bash and every modern Linux"

if ! command -v hf >/dev/null 2>&1; then
    err "Hugging Face CLI 'hf' not found."
    err "install it with:  pip install --user --upgrade huggingface_hub"
    err "then:             hf auth login"
    exit 1
fi

info "checking Hugging Face auth..."
if ! hf auth whoami >/dev/null 2>&1; then
    err "you are not logged in to Hugging Face."
    err "run:  hf auth login"
    err "(Gemma models are gated — you must accept the license on the HF page first.)"
    exit 1
fi
HF_USER="$(hf auth whoami 2>/dev/null | head -1)"
info "HF user: ${HF_USER}"

# ---------- resolve which file the repo actually ships --------------------

mkdir -p "$DOWNLOAD_DIR"
cd "$DOWNLOAD_DIR"

# Probe both filenames. `hf download --dry-run` isn't reliable, so we try the
# primary name first and fall back. `hf download` only re-downloads on a hash
# mismatch, so this is cheap on reruns.

resolve_remote_filename() {
    # Inspect the remote file tree once via the HF API so we don't speculate.
    local listing
    listing="$(curl -sSL \
        -H "Authorization: Bearer $(hf auth token 2>/dev/null || echo '')" \
        "https://huggingface.co/api/models/${HF_REPO}/tree/main" 2>/dev/null || true)"
    if [[ -z "$listing" ]]; then
        warn "could not list remote files; will try primary name '${MODEL_FILE_PRIMARY}'"
        echo "$MODEL_FILE_PRIMARY"
        return
    fi
    # Prefer .litertlm, then .task.
    local found
    found="$(printf '%s' "$listing" | grep -oE '"path":"[^"]+\.(litertlm|task)"' | head -1 | sed 's|^"path":"\(.*\)"$|\1|')"
    if [[ -z "$found" ]]; then
        warn "no .litertlm / .task file visible in repo ${HF_REPO}; trying primary name"
        echo "$MODEL_FILE_PRIMARY"
        return
    fi
    echo "$found"
}

REMOTE_FILENAME="$(resolve_remote_filename)"
LOCAL_FILE="${DOWNLOAD_DIR}/${REMOTE_FILENAME}"
info "remote file resolved: ${REMOTE_FILENAME}"

# ---------- download (idempotent via sha256) -------------------------------

EXPECTED_HASH="$(expected_sha256_for "$REMOTE_FILENAME")"
if [[ -z "$EXPECTED_HASH" ]]; then
    warn "no expected SHA256 for '${REMOTE_FILENAME}' in models/checksums.txt"
    warn "will download without a pre-verified hash, then record what we got"
fi

need_download=1
if [[ -f "$LOCAL_FILE" ]]; then
    if [[ -n "$EXPECTED_HASH" ]]; then
        info "found existing local file, verifying SHA256..."
        ACTUAL_HASH="$(sha256sum "$LOCAL_FILE" | awk '{print $1}')"
        if [[ "$ACTUAL_HASH" == "$EXPECTED_HASH" ]]; then
            info "local file hash matches expected — skipping download"
            need_download=0
        else
            warn "local file hash mismatch (got ${ACTUAL_HASH:0:12}..., expected ${EXPECTED_HASH:0:12}...); re-downloading"
        fi
    else
        info "local file present; no expected hash to compare against — skipping re-download"
        info "(delete ${LOCAL_FILE} manually to force a fresh pull)"
        need_download=0
    fi
fi

if (( need_download == 1 )); then
    info "downloading ${REMOTE_FILENAME} from ${HF_REPO} (this is ~3.65 GB for E4B)..."
    hf download "${HF_REPO}" "${REMOTE_FILENAME}" --local-dir "${DOWNLOAD_DIR}"
    info "download complete: ${LOCAL_FILE}"

    if [[ -n "$EXPECTED_HASH" ]]; then
        info "verifying downloaded SHA256..."
        ACTUAL_HASH="$(sha256sum "$LOCAL_FILE" | awk '{print $1}')"
        if [[ "$ACTUAL_HASH" != "$EXPECTED_HASH" ]]; then
            err "SHA256 mismatch after download!"
            err "  expected: $EXPECTED_HASH"
            err "  got:      $ACTUAL_HASH"
            err "refusing to push a corrupted file. Delete it and retry."
            exit 1
        fi
        info "SHA256 verified."
    else
        ACTUAL_HASH="$(sha256sum "$LOCAL_FILE" | awk '{print $1}')"
        warn "record this hash in models/checksums.txt so future runs verify:"
        warn "  ${ACTUAL_HASH}  ${REMOTE_FILENAME}"
    fi
fi

LOCAL_SIZE="$(stat -c%s "$LOCAL_FILE" 2>/dev/null || stat -f%z "$LOCAL_FILE")"
info "local file size: $((LOCAL_SIZE / 1024 / 1024)) MiB"

# ---------- ADB device check -----------------------------------------------

info "checking adb devices..."
# `adb devices` first line is a header, then one entry per device.
DEVICE_COUNT="$(adb devices | awk 'NR>1 && $2=="device" {count++} END {print count+0}')"
if (( DEVICE_COUNT == 0 )); then
    err "no ADB device connected. Plug in a cable or run 'adb connect <ip>:5555'."
    exit 1
elif (( DEVICE_COUNT > 1 )); then
    err "multiple ADB devices connected; refusing to guess which one."
    err "disconnect the others or pass -s <serial> manually after editing this script."
    adb devices
    exit 1
fi
info "adb device: $(adb devices | awk 'NR==2 {print $1}')"

# ---------- ensure remote dir exists ---------------------------------------

info "ensuring ${DEVICE_DIR} exists on device..."
adb shell "mkdir -p ${DEVICE_DIR}"

# ---------- push (idempotent via size) -------------------------------------

REMOTE_PATH="${DEVICE_DIR}/${REMOTE_FILENAME}"
REMOTE_SIZE="$(adb shell "[ -f '${REMOTE_PATH}' ] && stat -c '%s' '${REMOTE_PATH}' || echo 0" | tr -d '\r')"

if [[ "$REMOTE_SIZE" == "$LOCAL_SIZE" ]]; then
    info "remote file already present with matching byte size — skipping push"
else
    if [[ "$REMOTE_SIZE" != "0" ]]; then
        warn "remote file exists but size differs (remote=${REMOTE_SIZE}, local=${LOCAL_SIZE}) — re-pushing"
    fi
    info "pushing to device (this takes a few minutes over USB/ADB-WiFi)..."
    adb push "$LOCAL_FILE" "$REMOTE_PATH"

    # Verify
    REMOTE_SIZE="$(adb shell "stat -c '%s' '${REMOTE_PATH}'" | tr -d '\r')"
    if [[ "$REMOTE_SIZE" != "$LOCAL_SIZE" ]]; then
        err "post-push size mismatch (remote=${REMOTE_SIZE}, local=${LOCAL_SIZE})"
        exit 1
    fi
    info "push verified."
fi

# ---------- final report ---------------------------------------------------

info "-------------------------------------------------------------------"
info "model deployed to: ${REMOTE_PATH}"
info "size: $((LOCAL_SIZE / 1024 / 1024)) MiB"
info ""
info "next steps:"
info "  1. Open 'VicinoLLM' on the phone."
info "  2. Go to Models, tap 'Load' next to '${REMOTE_FILENAME}'."
info "  3. Go to Server, tap 'Start server' — note the IP:port shown."
info "  4. From this laptop, test:"
info ""
info "     curl http://<phone-ip>:8080/health"
info ""
info "     curl http://<phone-ip>:8080/v1/chat/completions \\"
info "       -H 'Content-Type: application/json' \\"
info "       -d '{\"model\":\"${REMOTE_FILENAME}\",\"messages\":[{\"role\":\"user\",\"content\":\"Hello\"}]}'"
info "-------------------------------------------------------------------"
