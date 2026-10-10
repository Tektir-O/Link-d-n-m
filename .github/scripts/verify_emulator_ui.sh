#!/usr/bin/env bash
set -euo pipefail
PACKAGE="com.kadir.muzikindir.video25"
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
  if python3 -c "import xml.etree.ElementTree as ET; nodes=[n.get('text','') for n in ET.parse('window.xml').getroot().iter('node')]; joined=' '.join(nodes); assert 'BAĞLANTIYI YAPIŞTIR' in joined and 'MP3 İNDİR' in joined and 'TELEFONDAN VİDEO / SES' in joined and 'İndirilen müziklerim' in joined"; then
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

video_ok=0
for attempt in 1 2 3; do
  adb shell input swipe 530 1490 530 400 450
  adb shell input swipe 530 1490 530 400 450
  adb shell uiautomator dump /sdcard/video-window.xml || true
  adb pull /sdcard/video-window.xml video-window.xml >/dev/null || true
  if python3 -c "import xml.etree.ElementTree as ET; nodes=[n.get('text','') for n in ET.parse('video-window.xml').getroot().iter('node')]; joined=' '.join(nodes); assert 'FOTOĞRAFTAN VİDEO' in joined or 'FOTOĞRAF SEÇ' in joined or 'Şarkıcının' in joined or 'Ses tam nereden' in joined"; then
    video_ok=1
    break
  fi
done
test "$video_ok" -eq 1
adb shell dumpsys activity activities | grep -F Mp3ConverterActivity
echo "One-screen MP3 and video UI verified"
