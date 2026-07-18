#!/bin/bash
set -euo pipefail

APP_PACKAGE="com.docscanner"
APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
AVD_NAME="${1:-testPixel7}"
ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/.local/android-sdk}"
PASS=0
FAIL=0

red()   { printf "\033[31m%s\033[0m\n" "$1"; }
green() { printf "\033[32m%s\033[0m\n" "$1"; }

pass() { PASS=$((PASS+1)); green "  PASS: $1"; }
fail() { FAIL=$((FAIL+1)); red "   FAIL: $1"; }

cleanup() {
    echo ""
    echo "--- Summary: $PASS passed, $FAIL failed ---"
    adb emu kill 2>/dev/null || true
    exit $FAIL
}
trap cleanup EXIT INT TERM

echo "=== DocScanner Smoke Tests ==="
echo ""

# ── 1. Build ──────────────────────────────────────────────────
echo ">>> Building APK..."
./gradlew assembleDebug 2>&1 | tail -1

# ── 2. Start emulator ─────────────────────────────────────────
if ! adb get-state 2>/dev/null | grep -q device; then
    echo ">>> Starting emulator ($AVD_NAME)..."
    $ANDROID_SDK_ROOT/emulator/emulator -avd "$AVD_NAME" \
        -no-window -noaudio -gpu swiftshader_indirect -read-only &
    EMU_PID=$!
    echo ">>> Waiting for boot..."
    adb wait-for-device
    sleep 15
    while [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != "1" ]; do
        sleep 3
    done
    sleep 5
fi

# ── 3. Install APK ────────────────────────────────────────────
echo ">>> Installing APK..."
adb install -r "$APK_PATH" 2>&1 | tail -1

# ── 4. Generate & push test files ─────────────────────────────
echo ">>> Generating test PDFs..."
mkdir -p tmp
python3 -c "
from fpdf import FPDF
pdf = FPDF()
pdf.set_auto_page_break(auto=False)
for i in range(3):
    pdf.add_page()
    pdf.set_font('Helvetica', size=24)
    pdf.cell(0, 100, text=f'Page {i+1}', align='C')
pdf.output('tmp/test-3page.pdf')
" 2>/dev/null || {
    convert -size 100x100 xc:white \
        -gravity center -pointsize 20 -annotate 0 'Page 1' \
        tmp/p1.png 2>/dev/null
    convert -size 100x100 xc:white \
        -gravity center -pointsize 20 -annotate 0 'Page 2' \
        tmp/p2.png 2>/dev/null
    convert -size 100x100 xc:white \
        -gravity center -pointsize 20 -annotate 0 'Page 3' \
        tmp/p3.png 2>/dev/null
    convert tmp/p1.png tmp/p2.png tmp/p3.png tmp/test-3page.pdf 2>/dev/null || \
        echo "WARNING: could not generate 3-page PDF"
}
touch tmp/test-empty.pdf
echo "not a pdf" > tmp/test-not-a-pdf.txt

echo ">>> Pushing test files..."
adb push tmp/test-3page.pdf /sdcard/Download/ 2>&1 | tail -1
adb push tmp/test-empty.pdf /sdcard/Download/ 2>&1 | tail -1
adb push tmp/test-not-a-pdf.txt /sdcard/Download/ 2>&1 | tail -1

# ── Helper: tap center of bounds ──────────────────────────────
tap_bounds() {
    local bounds="$1"
    bounds="${bounds#*[}"
    bounds="${bounds%]*}"
    local x1="${bounds%,*}"
    local rest="${bounds#*,}"
    local y1="${rest%]*}"
    local x2="${rest#*,}"
    y1="${y1%%]*}"
    local x2="${rest#*,}"
    local y2="${x2#*,}"
    x1="${x1%%,*}"
    y1="${rest%%,*}"
    y1="${y1##*\[}"
    local rest2="${bounds#*,}"
    rest2="${rest2#*,}"
    local y2="${rest2#*,}"
    local x2="${bounds#*,}"
    x2="${x2#*,}"
    x2="${x2%%,*}"
    y2="${bounds##*,}"
    local cx=$(( (x1 + x2) / 2 ))
    local cy=$(( (y1 + y2) / 2 ))
    adb shell input tap $cx $cy
    sleep 1
}

find_text_bounds() {
    local text="$1"
    local xml="$2"
    grep -oP "text=\"$text\"[^>]*bounds=\"[^\"]*\"" "$xml" | grep -oP 'bounds="[^"]*"' | head -1
}

# ── 5.3: Import 3-page PDF ──────────────────────────────────
echo ""
echo "--- Test 5.3: Import 3-page PDF ---"

adb shell am start -n "$APP_PACKAGE/.MainActivity"
sleep 3

# Tap Import PDF button (top bar, content-desc "Import PDF")
adb shell uiautomator dump /sdcard/step_import.xml 2>/dev/null
IMPORT_BOUNDS=$(adb shell cat /sdcard/step_import.xml | grep -oP 'content-desc="Import PDF"[^>]*bounds="[^"]*"' | grep -oP 'bounds="[^"]*"')
if [ -z "$IMPORT_BOUNDS" ]; then
    # Try to find by the Add icon
    IMPORT_BOUNDS=$(adb shell cat /sdcard/step_import.xml | grep -oP 'bounds="[^"]*"' | head -30 | tail -1)
    fail "Could not find Import PDF button"
fi

# We know the Import PDF is at [818,158][944,284] on Pixel 7
adb shell input tap 881 221
sleep 4

# Navigate SAF picker: Show roots if needed
adb shell uiautomator dump /sdcard/step_saf.xml 2>/dev/null
ROOTS=$(adb shell cat /sdcard/step_saf.xml | grep 'Show roots')
if [ -n "$ROOTS" ]; then
    adb shell input tap 73 199
    sleep 2
    adb shell uiautomator dump /sdcard/step_roots.xml 2>/dev/null
    # Tap Downloads in roots drawer
    adb shell input tap 367 650
    sleep 2
fi

# Tap test-3page.pdf (first card)
adb shell uiautomator dump /sdcard/step_files.xml 2>/dev/null
THREE_PAGE_BOUNDS=$(adb shell cat /sdcard/step_files.xml | grep -A1 'test-3page.pdf' | grep item_root | grep -oP 'bounds="[^"]*"')
adb shell input tap 296 983
sleep 5

# Verify result
adb shell uiautomator dump /sdcard/step_result.xml 2>/dev/null
PAGES=$(adb shell cat /sdcard/step_result.xml | grep -oP 'text="Page [123]"' | sort -u | wc -l)
TITLE=$(adb shell cat /sdcard/step_result.xml | grep -oP 'text="test-3page"')

if [ "$PAGES" -eq 3 ] && [ -n "$TITLE" ]; then
    pass "Imported 3-page PDF with all pages visible"
else
    fail "Expected 3 pages + title, got $PAGES pages"
fi

# ── 5.4: Import empty PDF ────────────────────────────────────
echo ""
echo "--- Test 5.4: Import empty PDF ---"

# Go back to home
adb shell input tap 75 221
sleep 2

# Tap Import PDF
adb shell input tap 881 221
sleep 4

# Navigate to Downloads if needed
adb shell uiautomator dump /sdcard/step_import2.xml 2>/dev/null
ROOTS2=$(adb shell cat /sdcard/step_import2.xml | grep 'Show roots')
if [ -n "$ROOTS2" ]; then
    adb shell input tap 73 199
    sleep 2
    adb shell input tap 367 650
    sleep 2
fi

# Tap test-empty.pdf (second card)
adb shell input tap 783 983
sleep 5

# Verify error dialog
adb shell uiautomator dump /sdcard/step_empty_result.xml 2>/dev/null
ERR_TITLE=$(adb shell cat /sdcard/step_empty_result.xml | grep -oP 'text="Import Failed"')
ERR_MSG=$(adb shell cat /sdcard/step_empty_result.xml | grep -oP 'text="Selected PDF has no pages"')

if [ -n "$ERR_TITLE" ] && [ -n "$ERR_MSG" ]; then
    pass "Empty PDF shows Import Failed error"
else
    fail "Expected Import Failed error dialog"
fi

# Dismiss dialog
adb shell input tap 768 1358
sleep 2

# ── 5.6: Import non-PDF file ─────────────────────────────────
echo ""
echo "--- Test 5.6: Import non-PDF file ---"

# Tap Import PDF
adb shell input tap 881 221
sleep 4

# Navigate to Downloads if needed
adb shell uiautomator dump /sdcard/step_import3.xml 2>/dev/null
ROOTS3=$(adb shell cat /sdcard/step_import3.xml | grep 'Show roots')
if [ -n "$ROOTS3" ]; then
    adb shell input tap 73 199
    sleep 2
    adb shell input tap 367 650
    sleep 2
fi

# Tap test-not-a-pdf.txt (third card)
adb shell input tap 296 1606
sleep 5

adb shell uiautomator dump /sdcard/step_nonpdf_result.xml 2>/dev/null
ERR_TITLE2=$(adb shell cat /sdcard/step_nonpdf_result.xml | grep -oP 'text="Import Failed"')

if [ -n "$ERR_TITLE2" ]; then
    pass "Non-PDF file shows Import Failed error"
else
    fail "Expected Import Failed error for non-PDF"
fi

# Dismiss dialog
adb shell input tap 768 1358
sleep 2

# ── 5.5: Rescan page ─────────────────────────────────────────
echo ""
echo "--- Test 5.5: Rescan page (cancel gracefully) ---"

# Open the 3-page document
adb shell input tap 540 629
sleep 3

# Tap Rescan on Page 1
adb shell input tap 483 1101
sleep 3

# No camera app available — should return to app gracefully
adb shell dumpsys activity activities 2>/dev/null | grep -q "$APP_PACKAGE" && \
    pass "Rescan handles cancellation gracefully" || \
    fail "Rescan did not return to app"

# ── Done ─────────────────────────────────────────────────────
echo ""
echo "=== Smoke tests complete ==="
