#!/usr/bin/env python3
"""Test: inject PicPocketTest subfolder URI and run sync."""
import subprocess, time, json, uiautomator2 as u2

SERIAL = 'emulator-5554'
SUB_URI = "content://org.nextcloud.documents/tree/e7ad2a2c29786c42923be2a432f94cac%2F82"

def adb(cmd):
    return subprocess.run(['adb', '-s', SERIAL] + cmd.split(),
                          capture_output=True, text=True, timeout=10)

# 1. Update drive_index.json
index = adb("shell run-as com.picpocket.app cat files/drive_index.json")
data = json.loads(index.stdout)
data['rootTreeUri'] = SUB_URI
data['rootFolderName'] = '/PicPocketTest'
# Write back via temp file
adb(f"shell run-as com.picpocket.app sh -c 'rm -f files/drive_index.json'")
tmp = '/data/local/tmp/drive_index.json'
subprocess.run(['adb', '-s', SERIAL, 'shell', f'echo \'{json.dumps(data)}\' > {tmp}'])
adb(f"shell run-as com.picpocket.app sh -c 'cat {tmp} > files/drive_index.json'")

# Verify
result = adb("shell run-as com.picpocket.app cat files/drive_index.json")
print("drive_index.json:", result.stdout)

# 2. Clear logcat and restart app
adb("logcat -c")
d = u2.connect(SERIAL)
d.app_stop('com.picpocket.app')
d.app_start('com.picpocket.app')
time.sleep(6)

g = d(text='Get started')
if g.exists: g.click(); time.sleep(3)
gear = d(description='Settings')
if gear.exists: gear.click(); time.sleep(3)
sync = d(text='Sync')
if sync.exists: sync.click(); time.sleep(3)

# Ensure sync ON
sync_toggle = d(className='android.widget.Switch')
if sync_toggle.exists:
    si = sync_toggle.info
    if not si.get('checked', False):
        sync_toggle.click()
        time.sleep(1)

# What does the sync screen show?
print("Sync screen texts:", [t.get_text() for t in d(className='android.widget.TextView') if t.get_text()])

# Tap Sync Now
subprocess.run(['adb','-s',SERIAL,'shell','input','tap','258','733'])
time.sleep(10)

# Check logcat
result = subprocess.run(['adb','-s',SERIAL,'logcat','-d'], capture_output=True, text=True, timeout=10)
for line in result.stdout.splitlines():
    if any(t in line for t in ('SyncManager','DriveFileMan','DownloadEng','performSync')):
        print(line)
