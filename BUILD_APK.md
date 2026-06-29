# Build APK - Quick Fix Guide

## There are XML syntax errors in the layout files. Let me fix them:

### Issues Found:
1. ✅ dialog_text_overlay.xml - duplicate style attribute (FIXED)
2. ✅ activity_stream.xml - extra closing tag (FIXED)  
3. ❌ activity_main.xml - missing closing tag at line 193

## To Build APK Successfully:

### Option 1: Open in Android Studio (EASIEST)
1. Open project in Android Studio
2. Let it auto-fix XML errors with "Fix" button
3. Build → Generate Signed Bundle / APK
4. Select APK → Debug → Finish
5. APK will be at: `app/build/outputs/apk/debug/app-debug.apk`

### Option 2: Command Line (After fixing XML)
```bash
cd e:\Youtube
.\gradlew.bat clean assembleDebug
```

### Where to find the APK:
```
e:\Youtube\app\build\outputs\apk\debug\app-debug.apk
```

## Install on Phone:

### Method 1: USB Cable
1. Enable Developer Options on phone
2. Enable USB Debugging
3. Connect phone to PC
4. Run: `.\gradlew.bat installDebug`

### Method 2: Transfer APK
1. Copy `app-debug.apk` to phone (via USB, email, Google Drive, etc.)
2. Open the APK file on phone
3. Allow "Install from Unknown Sources" if prompted
4. Tap "Install"

## Current Status:
- ❌ Build failing due to XML syntax errors in activity_main.xml line 193
- Need to fix missing closing `</LinearLayout>` tag

Let me fix the remaining error for you...
