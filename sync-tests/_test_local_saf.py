#!/usr/bin/env python3
"""Select local PicPocketTest via SAF picker and run sync."""
import subprocess, time, json, uiautomator2 as u2

SERIAL = 'emulator-5554'
d = u2.connect(SERIAL)

# 1. Clear app data and start fresh
d.app_stop('com.picpocket.app')
subprocess.run(['adb','-s',SERIAL,'shell','pm','clear','com.picpocket.app'],
               capture_output=True)
d.app_start('com.picpocket.app')
time.sleep(6)

g = d(text='Get started')
if g.exists: g.click(); time.sleep(3)

# 2. Go to Settings > Sync
gear = d(description='Settings')
if gear.exists: gear.click(); time.sleep(3)

# Find Sync text and tap
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

# 3. Click Select Folder
sf = d(text='Select Folder')
print(f'Select Folder exists: {sf.exists}')
if sf.exists:
    sf.click()
    time.sleep(5)
    
    print("\nSAF picker view:")
    for tv in d(className='android.widget.TextView'):
        t = tv.get_text()
        if t and t.strip():
            b = tv.info.get('bounds', {})
            print(f"  '{t}' at ({b.get('left')},{b.get('top')})-({b.get('right')},{b.get('bottom')})")
    
    # Check for PicPocketTest entry
    ppt = d(text='PicPocketTest')
    print(f'\nPicPocketTest exists: {ppt.exists}')
    
    if ppt.exists:
        b = ppt.info.get('bounds', {})
        cx = (b['left'] + b['right']) // 2
        cy = (b['top'] + b['bottom']) // 2
        print(f'Tapping PicPocketTest at ({cx},{cy})')
        ppt.click()
        time.sleep(3)
        
        # Now USE THIS FOLDER should work
        uf = d(text='USE THIS FOLDER')
        print(f'USE THIS FOLDER exists: {uf.exists}')
        if uf.exists:
            uf.click()
            time.sleep(3)
            
            # Check for ALLOW
            al = d(text='ALLOW')
            if al.exists:
                al.click()
                time.sleep(2)
                print('ALLOW clicked')
            
            # 4. Verify the app received the URI
            result = subprocess.run(['adb','-s',SERIAL,'shell','run-as','com.picpocket.app','cat','files/drive_index.json'],
                                  capture_output=True, text=True, timeout=5)
            print(f'drive_index.json: {result.stdout}')
        else:
            print("USE THIS FOLDER not found - checking hierarchy")
            with open('/tmp/saf_after_tap.xml', 'w') as f:
                f.write(d.dump_hierarchy())
