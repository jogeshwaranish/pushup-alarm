package com.pushupalarm

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek

/** An alarm. Empty [days] means it rings once and then turns itself off. */
data class Alarm(
    val id: Int,
    val hour: Int,
    val minute: Int,
    val days: Set<DayOfWeek> = emptySet(),
    val enabled: Boolean = true,
)

object AlarmStore {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("alarms", Context.MODE_PRIVATE)

    fun all(ctx: Context): List<Alarm> {
        val json = JSONArray(prefs(ctx).getString("list", "[]"))
        return (0 until json.length()).map { i ->
            val o = json.getJSONObject(i)
            val mask = o.getInt("days")
            Alarm(
                id = o.getInt("id"),
                hour = o.getInt("hour"),
                minute = o.getInt("minute"),
                days = DayOfWeek.entries.filter { mask and (1 shl it.ordinal) != 0 }.toSet(),
                enabled = o.getBoolean("enabled"),
            )
        }.sortedWith(compareBy({ it.hour }, { it.minute }))
    }

    fun get(ctx: Context, id: Int) = all(ctx).firstOrNull { it.id == id }

    fun nextId(ctx: Context) = (all(ctx).maxOfOrNull { it.id } ?: 0) + 1

    fun save(ctx: Context, alarm: Alarm) = write(ctx, all(ctx).filter { it.id != alarm.id } + alarm)

    fun delete(ctx: Context, id: Int) = write(ctx, all(ctx).filter { it.id != id })

    private fun write(ctx: Context, alarms: List<Alarm>) {
        val json = JSONArray()
        alarms.forEach { a ->
            json.put(
                JSONObject()
                    .put("id", a.id)
                    .put("hour", a.hour)
                    .put("minute", a.minute)
                    .put("days", a.days.fold(0) { mask, d -> mask or (1 shl d.ordinal) })
                    .put("enabled", a.enabled),
            )
        }
        prefs(ctx).edit().putString("list", json.toString()).commit()
    }
}
