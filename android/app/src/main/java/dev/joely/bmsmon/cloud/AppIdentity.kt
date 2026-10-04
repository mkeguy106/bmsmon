package dev.joely.bmsmon.cloud

import android.os.Build
import dev.joely.bmsmon.BuildConfig

/**
 * The User-Agent every upload and enrollment carries (DATA-28), so a server log can tell which build
 * sent what: `bmsmon-android/<versionName> (<git sha>; sdk <SDK_INT>)`. Characters a header can't
 * carry are dropped. versionCode is deliberately NOT the build identity — a lower code on a later
 * install would make `adb install -r` refuse the downgrade.
 */
internal fun userAgent(versionName: String, gitSha: String, sdkInt: Int): String =
    "bmsmon-android/$versionName ($gitSha; sdk $sdkInt)".filter { it in ' '..'~' }

internal fun appUserAgent(): String =
    userAgent(BuildConfig.VERSION_NAME, BuildConfig.GIT_SHA, Build.VERSION.SDK_INT)
