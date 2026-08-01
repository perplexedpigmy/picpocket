#!/usr/bin/env python3
import subprocess, time, uiautomator2 as u2

d = u2.connect('emulator-5554')
d.app_stop('com.picpocket.app')
d.app_start('com.picpocket.app')
time.sleep(6)

g = d(text='Get started')
if g.exists: g.click(); time.sleep(3)
gear = d(description='Settings')
if gear.exists: gear.click(); time.sleep(3)

# Navigate to Sync
import xml.etree.ElementTree as ET
xml = d.dump_hierarchy()
tree = ET.fromstring(xml)
for node in tree.iter('node'):
    if node.get('text') == 'Sync':
        b = node.get('bounds','').replace('[','').replace(']',' ').split()
        cx = (int(b[0].split(',')[0]) + int(b[1].split(',')[0])) // 2
        cy = (int(b[0].split(',')[1]) + int(b[1].split(',')[1])) // 2
        subprocess.run(['adb','-s','emulator-5554','shell','input','tap',str(cx),str(cy)])
        break
time.sleep(3)

# Click Select Folder
sf = d(text='Select Folder')
if sf.exists:
    sf.click()
    time.sleep(4)

# Dump the SAF picker hierarchy
with open('/tmp/saf_picker.xml', 'w') as f:
    f.write(d.dump_hierarchy())
print("SAF picker dump written to /tmp/saf_picker.xml")

# Find all text in the SAF picker
for tv in d(className='android.widget.TextView'):
    t = tv.get_text()
    if t and t.strip():
        b = tv.info.get('bounds', {})
        print(f"  TextView: '{t}' at ({b.get('left')},{b.get('top')})-({b.get('right')},{b.get('bottom')})")

# Check if roots drawer is active
sr = d(description='Show roots')
if sr.exists:
    print(f"Show roots exists: {sr.exists}")
    sr.click()
    time.sleep(3)
    
    with open('/tmp/saf_roots.xml', 'w') as f:
        f.write(d.dump_hierarchy())
    
    print("After Show Roots click:")
    for tv in d(className='android.widget.TextView'):
        t = tv.get_text()
        if t and t.strip():
            b = tv.info.get('bounds', {})
            print(f"  TextView: '{t}' at ({b.get('left')},{b.get('top')})-({b.get('right')},{b.get('bottom')})")
    
    # Find Nextcloud
    for tv in d(className='android.widget.TextView'):
        if tv.get_text() == 'Nextcloud':
            b = tv.info.get('bounds', {})
            if b:
                cx = (b['left'] + b['right']) // 2
                cy = (b['top'] + b['bottom']) // 2
                print(f"Tapping Nextcloud at ({cx},{cy})")
                subprocess.run(['adb','-s','emulator-5554','shell','input','tap',str(cx),str(cy)])
                break
    time.sleep(5)
    
    with open('/tmp/saf_nextcloud.xml', 'w') as f:
        f.write(d.dump_hierarchy())
    
    print("After Nextcloud tap:")
    for tv in d(className='android.widget.TextView'):
        t = tv.get_text()
        if t and t.strip():
            b = tv.info.get('bounds', {})
            print(f"  TextView: '{t}' at ({b.get('left')},{b.get('top')})-({b.get('right')},{b.get('bottom')})")
