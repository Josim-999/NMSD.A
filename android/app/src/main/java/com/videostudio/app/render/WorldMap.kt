package com.videostudio.app.render

import android.content.Context
import android.graphics.Path
import android.graphics.RectF
import java.text.Normalizer
import java.util.Locale

/** Country outlines (Natural Earth 1:110m, public domain) for the animated map scene. */
class WorldMap private constructor(val countries: List<Country>) {
    class Country(val iso: String, val name: String, val aliases: List<String>, val rings: List<FloatArray>) {
        /** Outline in map units: x = longitude + 180 (0..360), y = 90 - latitude (0..180). */
        val path: Path = Path().apply {
            for (r in rings) {
                if (r.size < 4) continue
                moveTo(r[0] + 180f, 90f - r[1])
                var i = 2
                while (i < r.size) { lineTo(r[i] + 180f, 90f - r[i + 1]); i += 2 }
                close()
            }
        }
        val bounds: RectF = RectF().also { b ->
            // The largest ring decides the box (so far-away islands don't zoom the map out).
            val main = rings.maxByOrNull { it.size } ?: FloatArray(0)
            var l = 999f; var t = 999f; var r = -999f; var btm = -999f
            var i = 0
            while (i < main.size) {
                val x = main[i] + 180f; val y = 90f - main[i + 1]
                if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > btm) btm = y
                i += 2
            }
            b.set(l, t, r, btm)
        }
    }

    fun find(place: String): Country? {
        val k = key(place)
        if (k.isEmpty()) return null
        val alias = ALIASES[k] ?: k
        // Own name or ISO code first; then aliases, preferring the largest country (the UK, not a territory it owns).
        return countries.firstOrNull { key(it.name) == alias || it.iso.lowercase(Locale.ROOT) == alias }
            ?: countries.filter { it.aliases.any { a -> key(a) == alias } }.maxByOrNull { it.bounds.width() * it.bounds.height() }
            ?: countries.firstOrNull { key(it.name).startsWith(alias) || alias.startsWith(key(it.name)) && key(it.name).length >= 5 }
    }

    companion object {
        private val ALIASES = mapOf(
            "usa" to "unitedstatesofamerica", "us" to "unitedstatesofamerica", "unitedstates" to "unitedstatesofamerica",
            "america" to "unitedstatesofamerica", "uk" to "unitedkingdom", "britain" to "unitedkingdom", "greatbritain" to "unitedkingdom",
            "england" to "unitedkingdom", "scotland" to "unitedkingdom", "southkorea" to "southkorea", "korea" to "southkorea",
            "uae" to "unitedarabemirates", "russianfederation" to "russia", "holland" to "netherlands", "czechia" to "czechrepublic",
        )

        fun key(s: String): String = Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(Regex("[^a-z0-9]"), "")

        @Volatile private var cached: WorldMap? = null

        fun parse(text: String): WorldMap = WorldMap(text.lineSequence().filter { it.isNotBlank() }.mapNotNull { line ->
            val parts = line.split('|')
            if (parts.size < 4) return@mapNotNull null
            val rings = parts[3].split('/').map { ring ->
                ring.split(' ').flatMap { pt -> pt.split(',').mapNotNull { it.toFloatOrNull() } }.toFloatArray()
            }
            Country(parts[0], parts[1], parts[2].split(';').filter { it.isNotBlank() }, rings)
        }.toList())

        fun load(context: Context): WorldMap? = cached ?: runCatching {
            parse(context.assets.open("world.txt").bufferedReader().readText()).also { cached = it }
        }.getOrNull()
    }
}
