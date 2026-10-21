#!/bin/bash
set -e

APP=/Applications/TabbyCat.app
ICON=/Users/ianblaylock/Code/TabbyCat/icon.png
JAR=/Users/ianblaylock/Code/TabbyCat/TabbyCat.jar

# Build .icns
ICONSET=/tmp/tabbycaticon.iconset
rm -rf "$ICONSET" && mkdir -p "$ICONSET"
sips -z 16 16   "$ICON" --out "$ICONSET/icon_16x16.png"
sips -z 32 32   "$ICON" --out "$ICONSET/icon_16x16@2x.png"
sips -z 32 32   "$ICON" --out "$ICONSET/icon_32x32.png"
sips -z 64 64   "$ICON" --out "$ICONSET/icon_32x32@2x.png"
sips -z 128 128 "$ICON" --out "$ICONSET/icon_128x128.png"
sips -z 256 256 "$ICON" --out "$ICONSET/icon_128x128@2x.png"
sips -z 256 256 "$ICON" --out "$ICONSET/icon_256x256.png"
sips -z 512 512 "$ICON" --out "$ICONSET/icon_256x256@2x.png"
sips -z 512 512 "$ICON" --out "$ICONSET/icon_512x512.png"
cp "$ICON"           "$ICONSET/icon_512x512@2x.png"
iconutil -c icns "$ICONSET" -o /tmp/TabbyCat.icns

# Build .app
sudo rm -rf "$APP"
sudo mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
sudo cp /tmp/TabbyCat.icns "$APP/Contents/Resources/TabbyCat.icns"

sudo tee "$APP/Contents/MacOS/TabbyCat" > /dev/null << EOF
#!/bin/bash
cd $(dirname "$JAR")
exec /usr/bin/java -Xdock:name=TabbyCat -Xdock:icon="$(dirname "$0")/../Resources/TabbyCat.icns" -jar TabbyCat.jar
EOF
sudo chmod +x "$APP/Contents/MacOS/TabbyCat"

sudo tee "$APP/Contents/Info.plist" > /dev/null << 'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleName</key><string>TabbyCat</string>
    <key>CFBundleDisplayName</key><string>TabbyCat</string>
    <key>CFBundleIdentifier</key><string>com.ianblaylock.tabbycat</string>
    <key>CFBundleVersion</key><string>1.0</string>
    <key>CFBundleExecutable</key><string>TabbyCat</string>
    <key>CFBundleIconFile</key><string>TabbyCat</string>
    <key>CFBundlePackageType</key><string>APPL</string>
    <key>NSHighResolutionCapable</key><true/>
</dict>
</plist>
EOF

sudo xattr -cr "$APP"
echo "Done — TabbyCat.app is in /Applications"
