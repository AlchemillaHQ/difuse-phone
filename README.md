# Difuse Phone

**Difuse Phone** is a fork of [linphone-android](https://github.com/BelledonneCommunications/linphone-android) by **Alchemilla Ventures** & **Difuse** (Iridia Solutions Private Limited), tailored difuse's and other SIP infrastructure. It is fully SIP-based, for all calling and presence features.

### About

**ML-Powered QR Scanner** — The upstream linphone QR scanner is replaced with a Google ML Kit + CameraX barcode scanner. QR codes encode JSON blobs containing PBX credentials (username, password, host, transport), allowing one-tap device provisioning.

**Sentry — Go-Based Push & B2BUA Backend** — The most significant architectural addition is Sentry, a production Go service that acts as a SIP Back-to-Back User Agent. Built with `sipgo`, Gin, and PostgreSQL (via SQLC), Sentry bridges upstream PBX systems (Difuse PBX, Asterisk, FreeSWITCH) to mobile devices via FCM/APNs push — **all push events are handled with sub-millisecond latency**. It handles SIP registration on behalf of sleeping devices, dispatches wake-up pushes, bridges calls, and exposes a REST API for interaction with the Difuse Phone. The Android app registers with Sentry on startup, maintains heartbeats, and manages dual SIP accounts — a direct PBX account for calls and a hidden "push-only" B2BUA account for incoming call wake-up.

**Optimized for Calling** — The experience is streamlined for voice and video calling with a custom dialer, a refined "difuse" blue UI theme, and pre-configured third-party SIP login that gets users connected faster.

### License

Copyright © Belledonne Communications / Alchemilla Ventures Private Limited

Difuse Phone is available under a [GNU/GPLv3 license](https://www.gnu.org/licenses/gpl-3.0.en.html) (see LICENSE file for details).

# Building the app

If you have Android Studio, simply open the project, wait for the gradle synchronization and then build/install the app.  
It will download the linphone library from our Maven repository as an AAR file so you don't have to build anything yourself.

If you don't have Android Studio, you can build and install the app using gradle:
```
./gradlew assembleDebug
```
will compile the APK file (assembleRelease to instead if you want to build a release package), and then
```
./gradlew installDebug
```
to install the generated APK in the previous step (use installRelease instead if you built a release package).

APK files are stored within ```./app/build/outputs/apk/debug/``` and ```./app/build/outputs/apk/release/``` directories.

When building a release AppBundle, use releaseAppBundle target instead of release.   
Also make sure you have a NDK installed and that you have an environment variable named ```ANDROID_NDK_HOME``` that contains the path to the NDK.  
This is to be able to include native libraries symbols into app bundle for the Play Store.

## Building a local SDK

1. Clone the linphone-sdk repository:
```
git clone https://gitlab.linphone.org/BC/public/linphone-sdk.git --recursive
```

2. Follow the instructions in the linphone-sdk/README file to build the SDK.

3. Create or edit the gradle.properties file in $GRADLE_USER_HOME (usually ~/.gradle/) and add the absolute path to your linphone-sdk build directory, for example:
```
LinphoneSdkBuildDir=/home/<username>/linphone-sdk/build/
```

4. Rebuild the app in Android Studio.

## Native debugging

1. Install LLDB from SDK Tools in Android-studio.

2. In Android-studio go to Run->Edit Configurations->Debugger.

3. Select 'Dual' or 'Native' and add the path to linphone-sdk debug libraries (build/libs-debug/ for example).

4. Open native file and put your breakpoint on it.

5. Make sure you are using the debug AAR in the app/build.gradle script and not the release one (to have faster builds by default the release AAR is used even for debug APK flavor).

6. Debug app.

## Known issues

- If you have the following build issue `AAPT: error: resource drawable/linphone_logo_tinted (aka org.linphone:drawable/linphone_logo_tinted) not found`, delete the `app/src/main/res/xml/contacts.xml` file (you can do it simply with `git clean -f` command) and start the build again.

- If you encounter the `couldn't find "libc++_shared.so"` crash when the app starts, simply clean the project in Android Studio (under Build menu) and build again.
Also check you have built the SDK for the right CPU architecture using the `-DLINPHONESDK_ANDROID_ARCHS=armv7,arm64,x86,x86_64` cmake parameter.

- Push notification might not work when app has been started by Android Studio consecutively to an install. Remove the app from the recent activity view and start it again using the launcher icon to resolve this.

## Troubleshooting

### Behavior issue

When submitting an issue, please include the matching library logs.  
Logs are always enabled and stored locally on the device, you can clear them/upload them by going into the Help → Troubleshooting page.

### Native crash

First of all, to be able to get a symbolized stack trace, you need the debug version of our libraries.

If you haven't built the SDK locally (see [building a local SDK](#building-a-local-sdk)), here's how to get them:

1. Go to the [linphone maven repository](https://download.linphone.org/maven_repository/org/linphone/linphone-sdk-android/) and find the directory that matches the version of the SDK.

2. Download the linphone-sdk-android-\<version\>-libs-debug.zip archive.

3. Extract the symbolized libraries somewhere on your computer, it will create a ```libs-debug``` directory.

Now you need the ```ndk-stack``` tool and possibly ```adb logcat```.
If your computer isn't used for Android development, you can download those tools from [Google website](https://developer.android.com/studio#downloads), in the ```Command line tools only``` section.

Once you have the debug libraries and the proper tools installed, you can use the ```ndk-stack``` tool to symbolize your stacktrace. Note that you also need to know the architecture (armv7, arm64, x86, etc...) of the libraries that were used.

If you know the CPU architecture of your device (most probably arm64 if it's a recent device) you can use the following to get the stacktrace:
```
adb logcat -d | ndk-stack -sym ./libs-debug/arm64-v8a/
```
If you don't know the CPU architecture, use the following instead:
```
adb logcat -d | ndk-stack -sym ./libs-debug/`adb shell getprop ro.product.cpu.abi | tr -d '\r'` 
```
Warning: This command won't print anything until you reproduce the crash!

Starting [NDK r29](https://github.com/android/ndk/wiki/Changelog-r29) you will be able to directly use the ```libs-debug.zip``` file in ```ndk-stack -sym``` argument.

## Firebase push notifications

Firebase push notifications require a ```app/google-services.json``` file that contains the configuration.  
If you have your own push server, replace this file with yours.

If you encounter
```
Execution failed for task ':app:processDebugGoogleServices'.
> No matching client found for package name 'your package name'
```
error when building, make sure you have replaced the ```app/google-services.json``` file by yours (containing your package name).

## Create an APK with a different package name

Simply edit the ```app/build.gradle.kts``` file and change the value of the ```packageName``` variable.
The next build will automatically use this value everywhere thanks to ```manifestPlaceholders``` feature of gradle and Android.
