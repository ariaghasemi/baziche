package com.baziche.scaffolder

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.random.Random

data class ScaffoldRequest(
    val gameType: String = "quiz",
    val theme: String = "space",
    val nameFa: String = "بازی من",
    val nameEn: String = "My Game",
    val sceneCount: Int = 2,
    val difficulty: Int = 1,
    val seed: Long = 0L,
)

/**
 * Deterministic offline game generator: same [ScaffoldRequest] always yields
 * byte-identical project JSON (formatVersion 1) that loads in :runtime and
 * passes backend build validation. No network, no AI — pure seeded
 * procedural assembly from the Tier-1 conventions.
 */
object Scaffolder {
    val GAME_TYPES = mapOf(
        "puzzle" to "portrait",
        "quiz" to "portrait",
        "word" to "portrait",
        "arcade" to "portrait",
        "runner" to "landscape",
        "platformer" to "landscape",
        "match3" to "portrait",
        "card" to "portrait",
        "board" to "landscape",
        "towerdefense" to "landscape",
        "racing" to "landscape",
        "shooter" to "landscape",
        "adventure" to "landscape",
        "idle" to "portrait",
        "tycoon" to "landscape",
        "merge" to "portrait",
        "survival" to "landscape",
        "detective" to "landscape",
        "zombie" to "landscape",
        "minigames" to "portrait",
    )

    val THEMES = mapOf(
        "space" to listOf("#0B1026", "#7C4DFF", "#00E5FF", "#FFD54F"),
        "forest" to listOf("#0D2818", "#2D6A4F", "#95D5B2", "#F4A259"),
        "candy" to listOf("#3E1F3D", "#FF4D8D", "#FFD166", "#7BDFF2"),
        "desert" to listOf("#2B2118", "#C97B3F", "#F2D398", "#7A9E7E"),
        "ocean" to listOf("#062A3A", "#0E7C7B", "#17C3B2", "#FFCB77"),
        "cyberpunk" to listOf("#0A0A12", "#00F0FF", "#FF0055", "#FFE600"),
        "sunset" to listOf("#1C0A28", "#E040FB", "#FF6E40", "#FFD740"),
    )

    private val TITLES = mapOf(
        "puzzle" to ("معما" to "Puzzle"),
        "quiz" to ("کوییز" to "Quiz"),
        "word" to ("کلمات" to "Word"),
        "arcade" to ("آرکید" to "Arcade"),
        "runner" to ("دونده" to "Runner"),
        "platformer" to ("سکوبازی" to "Platformer"),
        "match3" to ("سه‌تایی" to "Match-3"),
        "card" to ("کارت" to "Cards"),
        "board" to ("تخته" to "Board"),
        "towerdefense" to ("دفاع از قلعه" to "Tower Defense"),
        "racing" to ("مسابقه" to "Racing"),
        "shooter" to ("تیراندازی" to "Shooter"),
        "adventure" to ("ماجراجویی" to "Adventure"),
        "idle" to ("آیدل" to "Idle"),
        "tycoon" to ("مدیریت" to "Tycoon"),
        "merge" to ("ادغام" to "Merge"),
        "survival" to ("بقا" to "Survival"),
        "detective" to ("کارآگاهی" to "Detective"),
        "zombie" to ("زامبی" to "Zombie"),
        "minigames" to ("مینی‌گیم" to "Mini Games"),
    )

    fun generate(req: ScaffoldRequest): JsonObject {
        val rng = Random(req.seed)
        val type = req.gameType.takeIf { it in GAME_TYPES } ?: "quiz"
        val orientation = GAME_TYPES[type]!!
        val palette = THEMES[req.theme] ?: THEMES["space"]!!
        val (w, h) = if (orientation == "landscape") 800f to 480f else 480f to 800f
        val nScenes = req.sceneCount.coerceIn(1, 8)
        val diff = req.difficulty.coerceIn(1, 3)
        val (faTitle, enTitle) = TITLES[type]!!

        val sceneIds = List(nScenes) { i -> if (i == 0) "scene_main" else "scene_$i" }

        // ---- objects, grouped per scene ----
        val perScene = mutableMapOf<String, MutableList<JsonObject>>()
        sceneIds.forEach { perScene[it] = mutableListOf() }

        fun transform(x: Number, y: Number, ww: Number, hh: Number): JsonObject = buildJsonObject {
            put("x", JsonPrimitive(x)); put("y", JsonPrimitive(y))
            put("w", JsonPrimitive(ww)); put("h", JsonPrimitive(hh))
            put("rotation", JsonPrimitive(0)); put("opacity", JsonPrimitive(1))
        }
        fun textComp(text: String, size: Int, color: String): JsonObject = buildJsonObject {
            put("text", JsonPrimitive(text)); put("size", JsonPrimitive(size)); put("color", JsonPrimitive(color))
        }
        fun buttonComp(label: String, eventId: String): JsonObject = buildJsonObject {
            put("label", JsonPrimitive(label)); put("eventId", JsonPrimitive(eventId)); put("enabled", JsonPrimitive(true))
        }
        fun obj(id: String, scene: String, kind: String, comps: Map<String, JsonObject>): JsonObject = buildJsonObject {
            put("id", JsonPrimitive(id)); put("sceneId", JsonPrimitive(scene)); put("kind", JsonPrimitive(kind))
            put("visible", JsonPrimitive(true)); put("layer", JsonPrimitive(0))
            put("components", JsonObject(comps))
        }

        sceneIds.forEachIndexed { i, sid ->
            val list = perScene[sid]!!
            list.add(
                obj(
                    "title_$i", sid, "text",
                    mapOf("Transform" to transform(w / 2 - 200, 60, 400, 80), "Text" to textComp("$faTitle — ${i + 1}", 44, palette[3])),
                ),
            )
            if (i == 0) {
                list.add(
                    obj(
                        "btn_play", sid, "button",
                        mapOf("Transform" to transform(w / 2 - 110, h / 2 - 40, 220, 80), "Button" to buttonComp("شروع", "ev_play")),
                    ),
                )
                list.add(
                    obj(
                        "score_hud", sid, "text",
                        mapOf("Transform" to transform(16, 16, 200, 40), "Text" to textComp("0", 28, palette[2])),
                    ),
                )
            } else {
                list.add(
                    obj(
                        "btn_next_$i", sid, "button",
                        mapOf("Transform" to transform(w / 2 - 110, h - 160, 220, 80), "Button" to buttonComp("بعدی", "ev_next_$i")),
                    ),
                )
            }
            if (type == "runner" || type == "platformer" || type == "arcade") {
                list.add(
                    obj(
                        "hero_$i", sid, "rect",
                        mapOf(
                            "Transform" to transform(w * 0.2f, h * 0.6f, 56, 56),
                            "Health" to buildJsonObject {
                                put("max", JsonPrimitive(100)); put("current", JsonPrimitive(100)); put("invincibleMs", JsonPrimitive(500))
                            },
                        ),
                    ),
                )
            }
            repeat(1 + diff) { k ->
                val ow = 40 + rng.nextInt(80)
                val oh = 40 + rng.nextInt(80)
                val kind = if (rng.nextBoolean()) "rect" else "circle"
                list.add(
                    obj(
                        "ob_${i}_$k", sid, kind,
                        mapOf(
                            "Transform" to transform(rng.nextInt(w.toInt() - ow), h * 0.25f + rng.nextInt((h * 0.5f).toInt()), ow, oh),
                        ),
                    ),
                )
            }
        }
        val objects = perScene.values.flatten()

        fun act(cap: String, vararg params: Pair<String, JsonPrimitive>): JsonObject = buildJsonObject {
            put("capability", JsonPrimitive(cap))
            put("params", JsonObject(params.toMap()))
        }
        fun event(id: String, trigType: String, target: String?, actions: List<JsonObject>): JsonObject = buildJsonObject {
            put("id", JsonPrimitive(id))
            put(
                "trigger",
                buildJsonObject {
                    put("type", JsonPrimitive(trigType))
                    if (target != null) put("target", JsonPrimitive(target))
                },
            )
            put("actions", JsonArray(actions))
        }

        val events = mutableListOf<JsonObject>()
        events.add(event("boot", "start", null, listOf(act("CAP-0016", "screenId" to JsonPrimitive("hud")))))
        val second = if (nScenes > 1) sceneIds[1] else sceneIds[0]
        events.add(
            event(
                "ev_play", "tap", "btn_play",
                listOf(
                    act("CAP-0020", "variable" to JsonPrimitive("score"), "amount" to JsonPrimitive(10)),
                    act("CAP-0015", "sceneId" to JsonPrimitive(second)),
                ),
            ),
        )
        sceneIds.forEachIndexed { i, sid ->
            if (i == 0) return@forEachIndexed
            val next = sceneIds[(i + 1) % nScenes]
            events.add(
                event(
                    "ev_next_$i", "tap", "btn_next_$i",
                    listOf(
                        act("CAP-0020", "variable" to JsonPrimitive("score"), "amount" to JsonPrimitive(5 * diff)),
                        act("CAP-0015", "sceneId" to JsonPrimitive(next)),
                    ),
                ),
            )
            void(sid)
        }

        fun numVar(name: String, init: Number): JsonObject = buildJsonObject {
            put("name", JsonPrimitive(name)); put("type", JsonPrimitive("number")); put("initial", JsonPrimitive(init))
        }
        val variables = listOf(
            numVar("score", 0), numVar("coins", 0), numVar("quest_main", 0), numVar("level", 1),
            buildJsonObject {
                put("name", JsonPrimitive("inventory")); put("type", JsonPrimitive("string")); put("initial", JsonPrimitive("{}"))
            },
        )

        val scenes = sceneIds.mapIndexed { i, sid ->
            buildJsonObject {
                put("id", JsonPrimitive(sid))
                put("name", JsonPrimitive(if (i == 0) "Main" else "Scene $i"))
                put("entry", JsonPrimitive(i == 0))
                put("background", buildJsonObject { put("color", JsonPrimitive(palette[0])) })
                put("objectIds", JsonArray(perScene[sid]!!.map { JsonPrimitive((it["id"] as JsonPrimitive).content) }))
                put("transitions", JsonArray(emptyList()))
            }
        }

        val particles = type == "arcade" || type == "runner"
        void(enTitle)
        return buildJsonObject {
            put("formatVersion", JsonPrimitive(1))
            put(
                "meta",
                buildJsonObject {
                    put("name", JsonPrimitive(req.nameEn.ifBlank { enTitle }))
                    put("gameType", JsonPrimitive(type))
                    put("versionCode", JsonPrimitive(1)); put("versionName", JsonPrimitive("1.0.0"))
                    put("orientation", JsonPrimitive(orientation))
                    put("templateId", JsonPrimitive("scaffold:$type"))
                },
            )
            put("settings", buildJsonObject { put("locale", JsonPrimitive("fa")); put("fps", JsonPrimitive(60)) })
            put("scenes", JsonArray(scenes))
            put("objects", JsonArray(objects))
            put("components", JsonArray(emptyList()))
            put("events", JsonArray(events))
            put("variables", JsonArray(variables))
            put("assets", JsonArray(emptyList()))
            put("audio", JsonObject(emptyMap()))
            put("animations", JsonArray(emptyList()))
            put("levels", JsonArray(listOf(buildJsonObject { put("id", JsonPrimitive("level_1")); put("name", JsonPrimitive("Level 1")) })))
            put("ui", JsonObject(emptyMap()))
            put(
                "systems",
                buildJsonObject {
                    put(
                        "ambientParticles",
                        buildJsonObject {
                            put("enabled", JsonPrimitive(particles))
                            put("ratePerSec", JsonPrimitive(15 + 10 * diff))
                            put("max", JsonPrimitive(100))
                            put("speed", JsonPrimitive(50)); put("size", JsonPrimitive(4))
                            put("color", JsonPrimitive(palette[2])); put("lifeMs", JsonPrimitive(2500))
                        },
                    )
                },
            )
            put("monetization", JsonObject(emptyMap()))
            put(
                "build",
                buildJsonObject {
                    put("applicationId", JsonPrimitive(""))
                    put("appName", buildJsonObject { put("fa", JsonPrimitive(req.nameFa)); put("en", JsonPrimitive(req.nameEn)) })
                    put("versionCode", JsonPrimitive(1)); put("versionName", JsonPrimitive("1.0.0"))
                    put("targetApi", JsonPrimitive(36)); put("minApi", JsonPrimitive(26))
                    put("signing", JsonPrimitive("platform-managed"))
                },
            )
        }
    }

    /** Structural validation. Empty list = buildable. */
    fun validate(root: JsonObject): List<String> {
        val issues = mutableListOf<String>()
        if ((root["formatVersion"] as? JsonPrimitive)?.doubleOrNull?.toInt() != 1) issues.add("formatVersion must be 1")
        val scenes = root["scenes"] as? kotlinx.serialization.json.JsonArray
        if (scenes == null || scenes.isEmpty()) {
            issues.add("no scenes")
            return issues
        }
        val sceneObjs = scenes.mapNotNull { it as? JsonObject }
        if (sceneObjs.none { (it["entry"] as? JsonPrimitive)?.contentOrNull == "true" || it["entry"].toString() == "true" }) {
            issues.add("no entry scene")
        }
        val ids = sceneObjs.mapNotNull { (it["id"] as? JsonPrimitive)?.contentOrNull }.toSet()
        val objects = (root["objects"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
        for (o in objects) {
            val sid = (o["sceneId"] as? JsonPrimitive)?.contentOrNull
            if (sid !in ids) issues.add("object ${o["id"]} references unknown scene $sid")
        }
        val events = (root["events"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
        for (e in events) {
            val actions = (e["actions"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { it as? JsonObject } ?: continue
            for (a in actions) {
                val cap = (a["capability"] as? JsonPrimitive)?.contentOrNull ?: ""
                val m = Regex("^CAP-(\\d{4})$").matchEntire(cap)
                val n = m?.groupValues?.get(1)?.toIntOrNull()
                if (n == null || n !in 1..32) issues.add("unknown capability $cap")
            }
        }
        val vars = (root["variables"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
        for (v in vars) {
            if ((v["name"] as? JsonPrimitive)?.contentOrNull.isNullOrBlank()) issues.add("variable without name")
            if (!v.containsKey("initial")) issues.add("variable without initial")
        }
        return issues
    }

    private fun void(@Suppress("UNUSED_PARAMETER") vararg a: Any?) = Unit
}
