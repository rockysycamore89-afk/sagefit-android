# SageFit for Android (all-day steps)

This small Android app shows your SageFit site and adds what the web version can't do:
**it counts your steps all day, even when SageFit is minimized, closed or the screen is off.**

How it works:
- The phone's built-in step-counter chip counts every step, all the time, using almost no battery.
- **Always-on counting:** SageFit shows a small, silent notification, "SageFit is counting your steps: 4,210 today."
  While it shows, Android and Samsung's battery manager won't pause SageFit. It turns itself back on after a restart.
- Backup: every 15 minutes a small background job also saves the new steps to today.
- When SageFit is open, steps refresh every 30 seconds, and instantly each time you come back to the app.
- Health Connect (Samsung Health, Google Fit, Fitbit) is an optional second source. SageFit keeps the
  higher number for each day, so steps are never counted twice.

## Build it for free (no Android Studio needed)
1. Make a free account at github.com.
2. Click **+** (top right), then **New repository**. Name it `sagefit-android`, choose **Private**, then **Create repository**.
3. On the new page, click **uploading an existing file**. Drag in everything inside this folder,
   including the hidden `.github` folder. (On Windows, turn on View > Show > Hidden items to see it.)
   Click **Commit changes**.
4. Open the **Actions** tab. A build named "Build SageFit app" starts by itself and takes about 5 minutes.
5. When it has a green check, click it and download **SageFit-app** at the bottom. Unzip it to get **SageFit.apk**.

If the build shows a red X, click it, open the failed step, and copy the error message back to Claude.

## Install on an Android phone
1. Send SageFit.apk to the phone (email, Google Drive or USB) and tap it.
2. Allow "install unknown apps" when asked.
3. Open SageFit. On the home screen, find **All-day steps** and tap **Sync steps now**.
   Allow **Physical activity**, then allow **notifications**. The "SageFit is counting your steps"
   notification should appear. Make sure **Always-on counting** is On.
4. Tap **Battery settings** and set SageFit to **Unrestricted**.
5. **Samsung Galaxy:** Settings > Battery > Background usage limits. Make sure SageFit is **not** in
   "Sleeping apps" or "Deep sleeping apps". If "Put unused apps to sleep" is on, add SageFit to "Never sleeping apps".
6. Optional: tap **Connect Health Connect** to also use steps from a watch or Samsung Health.

## Good to know
- Steps start counting from the moment you allow Physical activity. Earlier steps can come from Health Connect.
- You can swipe SageFit away from recent apps; the notification keeps counting. You can hide the notification's
  look in Settings > Notifications, but don't turn it off, or Samsung may pause counting again.
- If you **force stop** SageFit in Settings, Android pauses the 15-minute saves until you open it again.
  The chip keeps counting, so no steps are lost; they may just land on the next day if you cross midnight.
- Health Connect is built into Android 14 and newer. On older phones the app sends you to the Play Store to install it.
- Your steps come from whatever already counts them: Samsung Health, Google Fit, Fitbit, or the phone itself.
  Make sure that app is set to share steps with Health Connect (in Health Connect > App permissions).
- SageFit only *reads* steps and only keeps them on your phone.
- This version is for testing on your family's phones. Publishing it on Google Play takes one more step
  (a release signing key), which Claude can set up with you.

## Test signing key

`app/sagefit-debug.keystore` is a test-only key (password `android`). Every GitHub build is signed with it, so a new SageFit.apk installs as an update and keeps your step history. It is **not** your Google Play key, so keep the Play keystore off GitHub.
