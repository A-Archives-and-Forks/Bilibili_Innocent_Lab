package com.Bilibili_Innocent_Lab.xposedmodule.telemetry

import android.os.Build
import android.os.Looper
import android.os.SystemClock
import java.util.Locale
import java.util.concurrent.TimeUnit

internal data class TelemetryDeviceProfile(
    val manufacturer: String,
    val model: String,
    val rom: String
)

/** Only product labels and a ROM family leave this boundary, never raw properties. */
internal object TelemetryDevicePolicy {
    private val labelPattern = Regex("^[A-Za-z0-9\\u3400-\\u9fff][A-Za-z0-9\\u3400-\\u9fff ._()+-]{0,63}$")
    val romCodes = setOf(
        "lineageos", "aosp", "hyperos", "miui", "realme_ui", "oxygenos", "coloros",
        "originos", "funtouchos", "one_ui", "emui", "harmonyos", "magicos", "flyme",
        "nubiaui", "myos", "zui", "nothingos", "hios", "xos", "unknown"
    )

    /**
     * 单台设备最多读多少条属性。`getprop` 每次都是 fork/exec，预算必须硬封顶——
     * 白名单可以长，实际探测**永远不超过这个数**，靠厂商定向选键而不是全表扫。
     */
    const val MAX_PROBE_KEYS = 8

    // 每组都是"某个家族自己写的版本属性"，没有厂商名、序列号、指纹、完整 build 转储。
    private val xiaomiKeys = listOf(
        "ro.mi.os.version.code", "ro.mi.os.version.name", "ro.miui.ui.version.name"
    )
    // ColorOS 12 / OxygenOS 12 / realme UI 3 起统一到 oplusrom，旧 opporom 仍需保留兜底。
    private val oplusKeys = listOf(
        "ro.build.version.oplusrom", "ro.build.version.realmeui", "ro.rom.version",
        "ro.oxygen.version", "ro.build.version.opporom"
    )
    private val vivoKeys = listOf("ro.vivo.os.name")
    // sep 是三星平台版本，One UI 属性缺失的老机型靠它才不掉进 unknown。
    private val samsungKeys = listOf("ro.build.version.oneui", "ro.build.version.sep")
    // 荣耀独立后仍继承华为属性面，magic 在前、emui 在后才能把 MagicOS 与 EMUI 分开。
    private val huaweiKeys = listOf(
        "ro.build.version.magic", "ro.magic.version",
        "hw_sc.build.platform.version", "ro.build.version.emui"
    )
    private val meizuKeys = listOf("ro.build.flyme.version", "ro.flyme.published")
    private val zteKeys = listOf("ro.build.rom.id", "ro.build.myos.version", "ro.build.MiFavor_version")
    private val lenovoKeys = listOf("ro.com.zui.version")
    private val nothingKeys = listOf("ro.nothing.version")
    private val transsionKeys = listOf("ro.tranos.version")
    /** 任何厂商的机器都可能被刷成 AOSP 派生，这两条对谁都探。 */
    private val portableKeys = listOf("ro.lineage.version", "ro.modversion")
    /** 认不出厂商时的通用组：按国内装机量挑最可能命中的家族指示器。 */
    private val fallbackKeys = listOf(
        "ro.miui.ui.version.name", "ro.build.version.oplusrom", "ro.build.version.emui",
        "ro.vivo.os.name", "ro.build.version.oneui", "ro.build.flyme.version"
    )

    /** 允许读取的全部键；实际单机探测量由 [probeKeys] 收敛到 [MAX_PROBE_KEYS] 以内。 */
    val propertyKeys: List<String> = (
        portableKeys + xiaomiKeys + oplusKeys + vivoKeys + samsungKeys + huaweiKeys +
            meizuKeys + zteKeys + lenovoKeys + nothingKeys + transsionKeys + fallbackKeys
        ).distinct()

    private val vendorKeys: Map<String, List<String>> = buildMap {
        for (vendor in listOf("xiaomi", "redmi", "poco")) put(vendor, xiaomiKeys)
        for (vendor in listOf("oppo", "oneplus", "realme")) put(vendor, oplusKeys)
        for (vendor in listOf("vivo", "iqoo")) put(vendor, vivoKeys)
        put("samsung", samsungKeys)
        for (vendor in listOf("huawei", "honor")) put(vendor, huaweiKeys)
        for (vendor in listOf("meizu", "blackshark")) put(vendor, meizuKeys)
        for (vendor in listOf("zte", "nubia")) put(vendor, zteKeys)
        for (vendor in listOf("lenovo", "motorola")) put(vendor, lenovoKeys)
        put("nothing", nothingKeys)
        for (vendor in listOf("tecno", "infinix", "itel", "transsion")) put(vendor, transsionKeys)
    }

    private fun vendorToken(raw: String?): String =
        raw?.takeIf { it.length <= 64 }?.trim()?.lowercase(Locale.ROOT)?.replace(" ", "").orEmpty()

    /**
     * 本机要探测的键。
     *
     * 厂商定向：先按 MANUFACTURER，再按 BRAND（荣耀拆分前 MANUFACTURER 仍是 HUAWEI），
     * 都认不出才用通用组。可移植键永远排在最前——被刷过的机器上厂商属性往往还在，
     * 先确认它是不是 AOSP 派生，比读一堆残留的厂商版本号更接近事实。
     */
    fun probeKeys(manufacturer: String?, brand: String? = null): List<String> {
        val selected = listOf(manufacturer, brand).asSequence()
            .map(::vendorToken).filter { it.isNotEmpty() }
            .firstNotNullOfOrNull { vendorKeys[it] } ?: fallbackKeys
        return (portableKeys + selected).distinct().take(MAX_PROBE_KEYS)
    }

    fun productLabel(raw: String?, lowercase: Boolean = false): String {
        if (raw == null || raw.length > 128) return "unknown"
        val normalized = raw.trim().replace(Regex(" +"), " ")
        if (!labelPattern.matches(normalized)) return "unknown"
        return if (lowercase) normalized.lowercase(Locale.ROOT) else normalized
    }

    /** 已知 AOSP 派生项目的显著标记。词根短到会误伤的一律不收（derp、spark、voltage）。 */
    private val aospProjects = Regex(
        "(^|[^a-z])(aosp|pixelexperience|crdroid|evolution[ _-]?x|arrowos|derpfest|" +
            "havoc[ _-]?os|paranoid[ _-]?android|omnirom|resurrection[ _-]?remix|" +
            "ricedroid|nameless[ _-]?aosp|superior[ _-]?os|project[ _-]?matrixx)"
    )

    fun romFamily(properties: Map<String, String>, display: String?, manufacturer: String? = null): String {
        fun present(key: String) = properties[key]?.trim()?.let {
            it.isNotEmpty() && it.length <= 128 && it != "0" &&
                !it.equals("unknown", ignoreCase = true)
        } == true
        fun value(key: String) = properties[key]?.takeIf { it.length <= 128 }.orEmpty().lowercase(Locale.ROOT)
        val vendor = vendorToken(manufacturer)

        // 刷机痕迹优先于厂商残留：定制包里常常还留着原厂的版本属性。
        if (present("ro.lineage.version")) return "lineageos"
        if (present("ro.modversion")) return "aosp"

        if (present("ro.mi.os.version.code") || present("ro.mi.os.version.name")) return "hyperos"
        if (present("ro.miui.ui.version.name")) return "miui"

        // 荣耀要在 EMUI 之前判：MagicOS 的机器上 emui 属性同样存在。
        if (present("ro.build.version.magic") || present("ro.magic.version")) return "magicos"
        if (value("ro.build.version.emui").contains("magic")) return "magicos"

        // OnePlus/realme 与 OPPO 现在共用同一套 oplus 属性，靠版本串和厂商分流。
        val oplusRom = value("ro.rom.version")
        if (oplusRom.contains("oxygen") || oplusRom.contains("h2os") || oplusRom.contains("hydrogen")) return "oxygenos"
        if (present("ro.oxygen.version")) return "oxygenos"
        if (present("ro.build.version.realmeui")) return "realme_ui"
        if (present("ro.build.version.oplusrom") || present("ro.build.version.opporom")) {
            return when (vendor) {
                "realme" -> "realme_ui"
                "oneplus" -> "oxygenos"
                else -> "coloros"
            }
        }

        val vivo = value("ro.vivo.os.name")
        if (vivo.contains("origin")) return "originos"
        if (vivo.contains("funtouch")) return "funtouchos"

        if (present("ro.build.version.oneui") || present("ro.build.version.sep")) return "one_ui"
        if (present("hw_sc.build.platform.version")) return "harmonyos"
        if (present("ro.build.version.emui")) return "emui"
        if (present("ro.build.flyme.version") || present("ro.flyme.published")) return "flyme"
        if (present("ro.build.rom.id")) return "nubiaui"
        if (present("ro.build.myos.version") || present("ro.build.MiFavor_version")) return "myos"
        if (present("ro.com.zui.version")) return "zui"
        if (present("ro.nothing.version")) return "nothingos"
        if (present("ro.tranos.version")) {
            return when (vendor) {
                "tecno" -> "hios"
                "infinix" -> "xos"
                else -> "unknown"
            }
        }

        // DISPLAY is inspected locally only and is never serialized.
        val text = display?.takeIf { it.length <= 128 }?.lowercase(Locale.ROOT).orEmpty()
        return when {
            Regex("(^|[^a-z])lineage").containsMatchIn(text) -> "lineageos"
            Regex("(^|[^a-z])flyme").containsMatchIn(text) -> "flyme"
            aospProjects.containsMatchIn(text) -> "aosp"
            else -> "unknown"
        }
    }
}

internal object TelemetryDeviceCollector {
    /** Called only by the existing telemetry worker after consent; no Hook or UI-thread work. */
    fun collect(): TelemetryDeviceProfile {
        check(Looper.myLooper() != Looper.getMainLooper())
        val deadline = SystemClock.elapsedRealtime() + 1_500L
        // BRAND 只参与选键和本机判定，不进上报字段（荣耀拆分前 MANUFACTURER 仍是 HUAWEI）。
        val properties = buildMap {
            for (key in TelemetryDevicePolicy.probeKeys(Build.MANUFACTURER, Build.BRAND)) {
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0L) break
                readProperty(key, minOf(remaining, 150L))?.let { put(key, it) }
            }
        }
        return TelemetryDeviceProfile(
            TelemetryDevicePolicy.productLabel(Build.MANUFACTURER, lowercase = true),
            TelemetryDevicePolicy.productLabel(Build.MODEL),
            TelemetryDevicePolicy.romFamily(properties, Build.DISPLAY, Build.MANUFACTURER)
        )
    }

    /** Fixed argv, no shell/root/reflection/property dump. Each child and output are bounded. */
    private fun readProperty(key: String, timeoutMs: Long): String? {
        val process = runCatching {
            ProcessBuilder("/system/bin/getprop", key).redirectErrorStream(true).start()
        }.getOrNull() ?: return null
        return try {
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS) || process.exitValue() != 0) null
            else process.inputStream.use { input ->
                val bytes = ByteArray(129)
                var count = 0
                while (count < bytes.size) {
                    val size = input.read(bytes, count, bytes.size - count)
                    if (size < 0) break
                    count += size
                }
                if (count >= bytes.size) null else String(bytes, 0, count, Charsets.UTF_8).trim()
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (_: Exception) {
            null
        } finally {
            if (process.isAlive) process.destroyForcibly()
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            runCatching { process.outputStream.close() }
        }
    }
}
