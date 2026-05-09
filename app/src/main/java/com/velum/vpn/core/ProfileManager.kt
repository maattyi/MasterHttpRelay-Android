package com.velum.vpn.core

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Goose-style relay profile manager. Profiles are persisted as JSON
 * files in the app's internal storage so users can import / export
 * configurations between devices.
 */
@Serializable
data class Profile(
    val name: String,
    val authKey: String = "",
    val tunnelKey: String = "",
    val scriptIds: List<String> = emptyList(),
    val frontDomain: String = "www.google.com",
    val googleIp: String = "216.239.38.120",
    val workerHost: String = "",
    val workerPath: String = "",
    val cloudflareChain: Boolean = false,
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    fun toRuntime(): RuntimeRelayConfig = RuntimeRelayConfig(
        authKey = authKey.ifEmpty { tunnelKey },
        scriptIds = scriptIds.filter { it.isNotBlank() },
        frontDomain = frontDomain,
        googleIp = googleIp,
        workerHost = workerHost,
        workerPath = workerPath,
        useCloudflareChain = cloudflareChain && workerHost.isNotEmpty(),
    )
}

data class RuntimeRelayConfig(
    val authKey: String,
    val scriptIds: List<String>,
    val frontDomain: String,
    val googleIp: String,
    val workerHost: String,
    val workerPath: String,
    val useCloudflareChain: Boolean,
)

class ProfileManager(private val context: Context) {

    private val dir: File = File(context.filesDir, "profiles").apply { mkdirs() }
    private val indexFile = File(dir, "index.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val profiles = mutableMapOf<String, Profile>()
    private var activeName: String = ""
    private var smartBalance: Boolean = false

    init { load() }

    private fun load() {
        if (indexFile.exists()) {
            try {
                val idx = json.decodeFromString(IndexFile.serializer(), indexFile.readText())
                activeName = idx.active
                smartBalance = idx.smartBalance
            } catch (_: Exception) {}
        }
        dir.listFiles { f -> f.extension == "json" && f.name != "index.json" }
            ?.forEach {
                try {
                    val p = json.decodeFromString(Profile.serializer(), it.readText())
                    profiles[p.name] = p
                } catch (_: Exception) {}
            }
    }

    private fun saveAll() {
        indexFile.writeText(
            json.encodeToString(IndexFile.serializer(), IndexFile(activeName, smartBalance))
        )
        profiles.values.forEach { p ->
            File(dir, safeName(p.name) + ".json")
                .writeText(json.encodeToString(Profile.serializer(), p))
        }
    }

    fun list(): List<Profile> = profiles.values.toList()
    fun get(name: String): Profile? = profiles[name]
    fun active(): Profile? = profiles[activeName]
    fun isSmartBalance(): Boolean = smartBalance

    fun upsert(p: Profile) {
        profiles[p.name] = p.copy(updatedAt = System.currentTimeMillis())
        saveAll()
    }

    fun remove(name: String) {
        if (profiles.remove(name) != null) {
            File(dir, safeName(name) + ".json").delete()
            if (activeName == name) activeName = ""
            saveAll()
        }
    }

    fun setActive(name: String) {
        if (name.isNotEmpty() && name !in profiles) throw NoSuchElementException(name)
        activeName = name
        saveAll()
    }

    fun setSmartBalance(on: Boolean) {
        smartBalance = on
        saveAll()
    }

    /**
     * Compute the runtime relay config for the current selection.
     * Smart-balance mode merges all profiles' script IDs together.
     */
    fun runtime(): RuntimeRelayConfig? {
        if (smartBalance && profiles.isNotEmpty()) {
            val merged = mutableListOf<String>()
            val seen = mutableSetOf<String>()
            profiles.values.forEach { p ->
                p.scriptIds.forEach { s ->
                    if (s.isNotBlank() && seen.add(s)) merged += s
                }
            }
            val base = profiles.values.first().toRuntime()
            return base.copy(scriptIds = merged)
        }
        return active()?.toRuntime()
    }

    fun exportToFile(target: File, names: Collection<String>? = null) {
        val items = (names?.mapNotNull { profiles[it] } ?: profiles.values).map { it }
        target.writeText(json.encodeToString(ExportFile.serializer(), ExportFile(items, 3)))
    }

    fun importFromFile(source: File): Int {
        val data = json.decodeFromString(ExportFile.serializer(), source.readText())
        var n = 0
        for (p in data.profiles) {
            var name = p.name
            var i = 1
            while (name in profiles) { name = "${p.name} ($i)"; i++ }
            profiles[name] = p.copy(name = name)
            n++
        }
        saveAll()
        return n
    }

    private fun safeName(s: String) =
        s.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }
         .joinToString("")
            .ifEmpty { UUID.randomUUID().toString() }

    @Serializable
    private data class IndexFile(val active: String = "", val smartBalance: Boolean = false)

    @Serializable
    private data class ExportFile(val profiles: List<Profile>, val version: Int)
}
