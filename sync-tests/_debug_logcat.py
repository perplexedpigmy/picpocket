#!/usr/bin/env python3
import subprocess, time, uiautomator2 as u2

subprocess.run(['adb','-s','emulator-5554','logcat','-c','-b','all'],
               capture_output=True)

d = u2.connect('emulator-5554')
d.app_stop('com.picpocket.app')
d.app_start('com.picpocket.app')
time.sleep(6)

g = d(text='Get started')
if g.exists:
    g.click()
    time.sleep(3)

gear = d(description='Settings')
if gear.exists:
    gear.click()
    time.sleep(3)

sync = d(text='Sync')
if sync.exists:
    sync.click()
    time.sleep(3)

# Ensure sync ON
sync_toggle = d(className='android.widget.Switch')
if sync_toggle.exists:
    si = sync_toggle.info
    if not si.get('checked', False):
        sync_toggle.click()
        time.sleep(1)

subprocess.run(['adb','-s','emulator-5554','shell','input','tap','258','733'])
time.sleep(5)

result = subprocess.run(['adb','-s','emulator-5554','logcat','-d'],
                        capture_output=True, text=True, timeout=10)
for line in result.stdout.splitlines():
    if any(t in line for t in ('SyncManager','DriveFileMan','DownloadEng','performSync')):
        print(line)
