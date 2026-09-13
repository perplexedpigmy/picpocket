#!/usr/bin/env python3
"""Test: sync with local PicPocketTest as SAF target."""
import subprocess, time, uiautomator2 as u2

SERIAL = 'emulator-5554'
d = u2.connect(SERIAL)

# Clear logcat
subprocess.run(['adb','-s',SERIAL,'logcat','-c','-b','all'], capture_output=True)

# Restart app
d.app_stop('com.picpocket.app')
d.app_start('com.picpocket.app')
time.sleep(6)

g = d(text='Get started')
if g.exists: g.click(); time.sleep(3)

# Go to settings > sync
gear = d(description='Settings')
if gear.exists: gear.click(); time.sleep(3)

import xml.etree.ElementTree as ET
xml = d.dump_hierarchy()
tree = ET.fromstring(xml)
for node in tree.iter('node'):
    if node.get('text') == 'Sync':
        b = node.get('bounds','').replace('[','').replace(']',' ').split()
        cx = (int(b[0].split(',')[0]) + int(b[1].split(',')[0])) // 2
        cy = (int(b[0].split(',')[1]) + int(b[1].split(',')[1])) // 2
        subprocess.run(['adb','-s',SERIAL,'shell','input','tap',str(cx),str(cy)])
        break
time.sleep(3)

# Ensure sync ON
sync_toggle = d(className='android.widget.Switch')
if sync_toggle.exists:
    si = sync_toggle.info
    if not si.get('checked', False):
        sync_toggle.click()
        time.sleep(1)

print("Sync screen:")
for tv in d(className='android.widget.TextView'):
    t = tv.get_text()
    if t and t.strip():
        print(f"  '{t}'")

# Import a PDF first
d.app_stop('com.picpocket.app')

# Reopen to main screen
d.app_start('com.picpocket.app')
time.sleep(6)
g = d(text='Get started')
if g.exists: g.click(); time.sleep(3)

# Use the UI to import a PDF
# First, create a test PDF on the device
subprocess.run(['adb','-s',SERIAL,'shell','mkdir','-p','/sdcard/Download'], capture_output=True)
subprocess.run(['adb','-s',SERIAL,'shell','echo','test pdf content','>','/sdcard/Download/sync_test.pdf'], capture_output=True)

# Open the import picker via the import button
fab = d(description='Import PDF')
if fab.exists:
    print("Import button found")
    fab.click()
    time.sleep(5)
    
    # Navigate to the file
    for tv in d(className='android.widget.TextView'):
        t = tv.get_text()
        if t and t.strip():
            print(f"  Picker: '{t}'")
    
    # Look for sync_test.pdf or Download
    sf = d(text='sync_test.pdf')
    if sf.exists:
        sf.click()
        time.sleep(5)
        print("Imported sync_test.pdf")
    else:
        # Try to navigate to Download
        dl = d(text='Download')
        if dl.exists:
            dl.click()
            time.sleep(3)
            sf2 = d(text='sync_test.pdf')
            if sf2.exists:
                sf2.click()
                time.sleep(5)
                print("Imported sync_test.pdf via Download")
else:
    # Alternative: use the + button
    plus = d(description='Add')
    if plus.exists:
        plus.click()
        time.sleep(3)
    print("Import button not found")

# Go back to sync screen
gear = d(description='Settings')
if gear.exists: gear.click(); time.sleep(3)
# Find Sync text
xml = d.dump_hierarchy()
tree = ET.fromstring(xml)
for node in tree.iter('node'):
    if node.get('text') == 'Sync':
        b = node.get('bounds','').replace('[','').replace(']',' ').split()
        cx = (int(b[0].split(',')[0]) + int(b[1].split(',')[0])) // 2
        cy = (int(b[0].split(',')[1]) + int(b[1].split(',')[1])) // 2
        subprocess.run(['adb','-s',SERIAL,'shell','input','tap',str(cx),str(cy)])
        break
time.sleep(3)

# Ensure sync ON
sync_toggle = d(className='android.widget.Switch')
if sync_toggle.exists:
    si = sync_toggle.info
    if not si.get('checked', False):
        sync_toggle.click()
        time.sleep(1)

# Tap Sync Now
subprocess.run(['adb','-s',SERIAL,'shell','input','tap','258','733'])
print("Sync Now tapped, waiting 15s...")
time.sleep(15)

# Check logcat
result = subprocess.run(['adb','-s',SERIAL,'logcat','-d'], capture_output=True, text=True, timeout=10)
for line in result.stdout.splitlines():
    if any(t in line for t in ('SyncManager','DriveFileMan','DownloadEng','performSync')):
        print(line)

# Check if files were written to PicPocketTest
result2 = subprocess.run(['adb','-s',SERIAL,'shell','ls','-la','/sdcard/PicPocketTest/'], capture_output=True, text=True, timeout=5)
print(f"\nPicPocketTest contents:\n{result2.stdout}")
