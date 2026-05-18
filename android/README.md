# Bluetooth Feed Sync Testing
Build tested on two Android phones (A and B) running the same APK

DBG = small label in the top-right corner
Tap - show/hide debug info

## Local post rendering
- Posted a message on A
- Message appeared immediately at the top of the feed
- Input cleared correctly afterward
Status:

## Feed auto-scroll after posting
- Scrolled down on A to older messages
- Posted a new message
- Feed automatically returned to the top
Status:

## Sync from A to B
- Both phones nearby with Bluetooth enabled
- Posted "T1" on A
- Message appeared on B within a few seconds
Status:

## Sync from B to A
- Posted "Q1" on B
- Message appeared correctly on A
Status:

## Simultaneous posting
- Posted "A1" from A and "B1" from B at nearly the same time
- Both devices eventually showed both messages
Status:

## Offline recovery (single message)
- Disabled Bluetooth on B
- Posted "T1" from A
- Re-enabled Bluetooth on B
- Missing message synced automatically without manual interaction
Status:

## Offline recovery (multiple messages)
- Disabled Bluetooth on B
- Posted "T1" and "T2" from A
- Re-enabled Bluetooth on B
- Both messages synced successfully and remained in correct order
Status:

## Long idle recovery
- Left both devices idle for 30+ minutes
- Posted a new message afterward
- Sync still completed within roughly 10 seconds
Note:
Android may stop BLE scans after extended idle/background time.
Current implementation restarts scanning every 5 minutes to recover automatically.
Status:

## Persistence after restart
- Posted "P1" on A
- Force-closed and reopened the app
- Message history remained intact
Status:


# Build APK
- Install Android Studio
- Open Android Studio - Open - select the android folder from the repo
- Wait for Gradle sync to finish (first time may take a few minutes)
- Select Menu - Build - Generate App Bundles or APKs - Generate APKs
- The APK will be at android/app/build/outputs/apk/debug/app-debug.apk

Here are the steps to build the APK