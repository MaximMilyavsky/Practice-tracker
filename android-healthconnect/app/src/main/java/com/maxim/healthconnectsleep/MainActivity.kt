package com.maxim.healthconnectsleep

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var resultText: TextView
    private lateinit var permissionButton: Button
    private lateinit var readButton: Button
    private lateinit var copyButton: Button
    private var client: HealthConnectClient? = null

    private val permissions = setOf(
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(OxygenSaturationRecord::class)
    )

    private val permissionLauncher =
        registerForActivityResult(PermissionController.createRequestPermissionResultContract()) {
            lifecycleScope.launch { refreshPermissionState() }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        resultText = findViewById(R.id.resultText)
        permissionButton = findViewById(R.id.permissionButton)
        readButton = findViewById(R.id.readButton)
        copyButton = findViewById(R.id.copyButton)

        if (HealthConnectClient.getSdkStatus(this) == HealthConnectClient.SDK_AVAILABLE) {
            client = HealthConnectClient.getOrCreate(this)
            statusText.text = "Health Connect доступен."
        } else {
            statusText.text = "Health Connect недоступен или требует обновления."
            permissionButton.isEnabled = false
            readButton.isEnabled = false
        }

        permissionButton.setOnClickListener { permissionLauncher.launch(permissions) }
        readButton.setOnClickListener { lifecycleScope.launch { readLastNight() } }
        copyButton.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Health Connect report", resultText.text))
            statusText.text = "Отчёт скопирован."
        }

        lifecycleScope.launch { refreshPermissionState() }
    }

    private suspend fun refreshPermissionState() {
        val c = client ?: return
        val granted = c.permissionController.getGrantedPermissions()
        readButton.isEnabled = granted.containsAll(permissions)
        statusText.text = if (readButton.isEnabled) {
            "Доступ к Sleep + Heart Rate + SpO₂ выдан."
        } else {
            "Нажмите «Дать доступ к данным»."
        }
    }

    private suspend fun readLastNight() {
        val c = client ?: return
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val start = LocalDateTime.of(today.minusDays(1), LocalTime.of(18, 0)).atZone(zone).toInstant()
        val end = LocalDateTime.of(today, LocalTime.NOON).atZone(zone).toInstant()

        statusText.text = "Читаю последнюю ночь…"
        resultText.text = "Подождите…"

        try {
            val allSleep = c.readRecords(
                ReadRecordsRequest(
                    recordType = SleepSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                    pageSize = 1000
                )
            ).records

            if (allSleep.isEmpty()) {
                resultText.text = "Записи сна не найдены в окне ${fmt(start, zone)} → ${fmt(end, zone)}"
                statusText.text = "Готово: сна не найдено."
                return
            }

            val byOrigin = allSleep.groupBy { it.metadata.dataOrigin.packageName }
            val originCoverage = byOrigin.mapValues { (_, records) ->
                unionDuration(records.map { it.startTime to it.endTime })
            }
            val source = originCoverage.maxByOrNull { it.value }!!.key
            val sleep = byOrigin[source]!!.sortedBy { it.startTime }
            val sleepStart = sleep.minOf { it.startTime }
            val sleepEnd = sleep.maxOf { it.endTime }
            val coverage = unionDuration(sleep.map { it.startTime to it.endTime })
            val stages = sleep.flatMap { it.stages }

            val hr = c.readRecords(
                ReadRecordsRequest(
                    recordType = HeartRateRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(sleepStart, sleepEnd),
                    dataOriginFilter = setOf(DataOrigin(source)),
                    pageSize = 1000
                )
            ).records.flatMap { it.samples }

            val spo2 = c.readRecords(
                ReadRecordsRequest(
                    recordType = OxygenSaturationRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(sleepStart, sleepEnd),
                    dataOriginFilter = setOf(DataOrigin(source)),
                    pageSize = 1000
                )
            ).records.map { it.percentage.value }

            val report = buildString {
                appendLine("HEALTH CONNECT — LAST NIGHT")
                appendLine("Источник: $source")
                appendLine("Окно: ${fmt(sleepStart, zone)} → ${fmt(sleepEnd, zone)}")
                appendLine("Объединённое покрытие сна: ${fmtDuration(coverage)}")
                appendLine("SleepSessionRecord: ${sleep.size}")
                appendLine("Stage objects: ${stages.size}")
                appendLine()
                appendLine("СТАДИИ СНА")
                if (stages.isEmpty()) {
                    appendLine("⚠️ В SleepSessionRecord нет стадий Light/Deep/REM/Awake.")
                } else {
                    stageSummary(stages).forEach { (name, duration) ->
                        appendLine("$name: ${fmtDuration(duration)}")
                    }
                }
                appendLine()
                appendLine("ПУЛЬС")
                if (hr.isEmpty()) appendLine("Нет данных") else {
                    val values = hr.map { it.beatsPerMinute.toDouble() }
                    appendLine("Точек: ${values.size}")
                    appendLine("Средний: ${values.average().roundToInt()} bpm")
                    appendLine("Мин: ${values.minOrNull()!!.roundToInt()} bpm")
                    appendLine("Макс: ${values.maxOrNull()!!.roundToInt()} bpm")
                }
                appendLine()
                appendLine("SpO₂")
                if (spo2.isEmpty()) appendLine("Нет данных") else {
                    appendLine("Точек: ${spo2.size}")
                    appendLine("Средняя: ${"%.1f".format(spo2.average())}%")
                    appendLine("Мин: ${"%.1f".format(spo2.minOrNull()!!)}%")
                    appendLine("Макс: ${"%.1f".format(spo2.maxOrNull()!!)}%")
                }
                appendLine()
                appendLine("ИСТОЧНИКИ СНА")
                originCoverage.toList().sortedByDescending { it.second }.forEach { (pkg, dur) ->
                    appendLine("$pkg — ${fmtDuration(dur)}")
                }
                appendLine()
                appendLine("RAW SLEEP SESSIONS")
                sleep.forEachIndexed { i, s ->
                    appendLine("${i + 1}. ${fmt(s.startTime, zone)} → ${fmt(s.endTime, zone)} | stages=${s.stages.size}")
                }
            }

            resultText.text = report
            statusText.text = "Готово. Скопируйте отчёт и пришлите его в ChatGPT."
        } catch (e: Exception) {
            resultText.text = "Ошибка: ${e::class.simpleName}: ${e.message}"
            statusText.text = "Ошибка чтения Health Connect."
        }
    }

    private fun stageSummary(stages: List<SleepSessionRecord.Stage>): LinkedHashMap<String, Duration> {
        val mapping = linkedMapOf(
            "Awake" to setOf(
                SleepSessionRecord.STAGE_TYPE_AWAKE,
                SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED,
                SleepSessionRecord.STAGE_TYPE_OUT_OF_BED
            ),
            "Light" to setOf(SleepSessionRecord.STAGE_TYPE_LIGHT),
            "Deep" to setOf(SleepSessionRecord.STAGE_TYPE_DEEP),
            "REM" to setOf(SleepSessionRecord.STAGE_TYPE_REM),
            "Sleeping (unknown stage)" to setOf(SleepSessionRecord.STAGE_TYPE_SLEEPING),
            "Unknown" to setOf(SleepSessionRecord.STAGE_TYPE_UNKNOWN)
        )
        val result = linkedMapOf<String, Duration>()
        for ((name, accepted) in mapping) {
            val intervals = stages.filter { it.stage in accepted }.map { it.startTime to it.endTime }
            if (intervals.isNotEmpty()) result[name] = unionDuration(intervals)
        }
        return result
    }

    private fun unionDuration(intervals: List<Pair<Instant, Instant>>): Duration {
        val sorted = intervals.filter { it.second.isAfter(it.first) }.sortedBy { it.first }
        if (sorted.isEmpty()) return Duration.ZERO
        var start = sorted.first().first
        var end = sorted.first().second
        var total = Duration.ZERO
        for ((nextStart, nextEnd) in sorted.drop(1)) {
            if (!nextStart.isAfter(end)) {
                if (nextEnd.isAfter(end)) end = nextEnd
            } else {
                total = total.plus(Duration.between(start, end))
                start = nextStart
                end = nextEnd
            }
        }
        return total.plus(Duration.between(start, end))
    }

    private fun fmt(i: Instant, zone: ZoneId): String =
        DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(zone).format(i)

    private fun fmtDuration(d: Duration): String {
        val mins = d.toMinutes()
        return "${mins / 60}ч ${mins % 60}м"
    }
}
