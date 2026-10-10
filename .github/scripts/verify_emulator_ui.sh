#!/usr/bin/env bash
set -euo pipefail
PACKAGE="com.kadir.muzikindir.video27"
ACTIVITY="com.kadir.linkdnm.Mp3ConverterActivity"
ui_ok=0

# Fresh Google APIs emulator can temporarily show Pixel Launcher ANR on first boot.
for attempt in 1 2 3 4 5; do
  adb shell am force-stop com.google.android.apps.nexuslauncher || true
  adb shell am start -n "$PACKAGE/$ACTIVITY" || true
  sleep 5
  adb shell pidof "$PACKAGE" || true
  adb shell uiautomator dump /sdcard/window.xml || true
  adb pull /sdcard/window.xml window.xml >/dev/null || true
  if python3 -c "import xml.etree.ElementTree as ET; nodes=[n.get('text','') for n in ET.parse('window.xml').getroot().iter('node')]; joined=' '.join(nodes); assert 'BAĞLANTIYI YAPIŞTIR' in joined and 'MP3 İNDİR' in joined and 'VİDEO YAP' in joined, joined; assert 'Şarkıcının' not in joined and 'Video kaç dakika' not in joined and 'YouTube erişimi kısıtlıysa' not in joined, joined"; then
    ui_ok=1
    break
  fi
  echo "Transient emulator/launcher UI problem on attempt $attempt"
  adb shell input keyevent 4 || true
  sleep 3
done

if [ "$ui_ok" -ne 1 ]; then
  echo "App UI was not visible after 5 emulator retries"
  exit 1
fi

adb shell input tap 360 1130 || true
sleep 2
adb shell input swipe 530 1300 530 500 420
adb shell uiautomator dump /sdcard/video-window.xml
adb pull /sdcard/video-window.xml video-window.xml >/dev/null
python3 -c "import xml.etree.ElementTree as ET; nodes=[n.get('text','') for n in ET.parse('video-window.xml').getroot().iter('node')]; joined=' '.join(nodes); assert 'FOTOĞRAF SEÇ' in joined or 'MÜZİK SEÇ' in joined or 'VİDEO OLUŞTUR' in joined, joined"

adb shell dumpsys activity activities | grep -F Mp3ConverterActivity
echo "One-screen MP3 and video UI verified"
