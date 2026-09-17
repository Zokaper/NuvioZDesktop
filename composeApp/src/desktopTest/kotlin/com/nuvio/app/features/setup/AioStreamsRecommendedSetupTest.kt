package com.nuvio.app.features.setup

import com.nuvio.app.features.addons.RawHttpResponse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The native AIOStreams client against the **real** template file and a fake instance that speaks
 * the v2.34.0 wire format (`POST /api/v1/user` -> `{success, data: {uuid, encryptedPassword}}`).
 */
class AioStreamsRecommendedSetupTest {

    private val templateText = File("../nuvio-z-torbox-v1.json").readText()
    private val key = "tb_SECRET_key_0123456789abcdef"

    @Test
    fun createsConfigAndBuildsManifestUrlFromServerResponse() = runBlocking {
        val http = FakeInstance()
        val result = setup(http).createConfig(request())

        assertIs<AioStreamsConfigResult.Created>(result)
        assertEquals("$Base/stremio/uuid-1/ENC_pw/manifest.json", result.manifestUrl)
        assertEquals("Nuvio Z Recommended", result.addonName)
        assertEquals("uuid-1", result.recovery.uuid)
        assertEquals(64, result.recovery.password.length)
        assertEquals(listOf("GET $TemplateUrl", "GET $Base/api/v1/status", "POST $Base/api/v1/user"), http.calls)
    }

    @Test
    fun postedConfigIsTheResolvedTemplate() = runBlocking {
        val http = FakeInstance()
        setup(http).createConfig(request(source = "Japanese", subtitle = "Arabic"))

        val body = Json.parseToJsonElement(http.postBody!!).jsonObject
        val config = body["config"]!!.jsonObject
        assertTrue(body["password"]!!.jsonPrimitive.content.length >= 6, "AIOStreams rejects short passwords")

        val torbox = config["services"]!!.jsonArray.single().jsonObject
        assertEquals("torbox", torbox["id"]!!.jsonPrimitive.content)
        assertEquals(key, torbox["credentials"]!!.jsonObject["apiKey"]!!.jsonPrimitive.content)

        // `{{inputs.sourceLanguages}}` is spread into its array, not nested.
        assertEquals(
            listOf("Japanese", "Original", "Dual Audio", "Multi", "Dubbed", "Unknown"),
            config["preferredLanguages"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(listOf("Arabic"), config["preferredSubtitles"]!!.jsonArray.map { it.jsonPrimitive.content })

        // No template placeholder survives, and the browser's own defaults were layered underneath.
        assertFalse(http.postBody!!.contains("{{"))
        assertEquals("gdrive", config["formatter"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("zokaper.nuvio-z-recommended", config["appliedTemplates"]!!.jsonArray.single().jsonObject["id"]!!.jsonPrimitive.content)
        // The template's own values are untouched by that layer.
        assertEquals("Nuvio Z Recommended", config["addonName"]!!.jsonPrimitive.content)
        assertEquals(listOf("3D", "H-OU", "H-SBS"), config["excludedVisualTags"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun presetsTheInstanceDoesNotOfferAreDropped() = runBlocking {
        val http = FakeInstance(presets = """[{"ID":"comet"},{"ID":"meteor","DISABLED":{"disabled":true}}]""")
        setup(http).createConfig(request())

        val presets = Json.parseToJsonElement(http.postBody!!).jsonObject["config"]!!.jsonObject["presets"] as JsonArray
        assertEquals(listOf("comet"), presets.map { it.jsonObject["type"]!!.jsonPrimitive.content })
    }

    @Test
    fun blankKeyNeverTouchesTheNetwork() = runBlocking {
        val http = FakeInstance()
        val result = setup(http).createConfig(request(key = "   "))
        assertEquals(AioStreamsConfigResult.Failed(RecommendedSourceFailure.MissingKey), result)
        assertTrue(http.calls.isEmpty())
    }

    @Test
    fun rejectedConfigSurfacesARedactedServerMessage() = runBlocking {
        val http = FakeInstance(
            userStatus = 400,
            userBody = """{"success":false,"data":null,"error":{"code":"USER_INVALID_CONFIG","message":"Comet failed: https://comet.example/$key/manifest.json returned 401 for $key"}}""",
        )
        val result = setup(http).createConfig(request())

        assertIs<AioStreamsConfigResult.Failed>(result)
        assertEquals(RecommendedSourceFailure.Rejected, result.reason)
        assertFalse(result.detail!!.contains(key), "server detail leaked the key: ${result.detail}")
        assertFalse(result.detail!!.contains("comet.example"))
        assertTrue(result.detail!!.startsWith("Comet failed"))
    }

    @Test
    fun rateLimitAndOutageAreDistinguished() = runBlocking {
        val limited = setup(FakeInstance(userStatus = 429, userBody = "{}")).createConfig(request())
        assertEquals(RecommendedSourceFailure.RateLimited, (limited as AioStreamsConfigResult.Failed).reason)

        val down = setup(FakeInstance(userStatus = 502, userBody = "<html>bad gateway</html>")).createConfig(request())
        assertEquals(RecommendedSourceFailure.InstanceUnavailable, (down as AioStreamsConfigResult.Failed).reason)
        assertNull(down.detail)

        val noTemplate = setup(FakeInstance(templateStatus = 404)).createConfig(request())
        assertEquals(RecommendedSourceFailure.TemplateUnavailable, (noTemplate as AioStreamsConfigResult.Failed).reason)
    }

    @Test
    fun templateWithUnsupportedDirectiveFailsClosedWithoutPosting() = runBlocking {
        val conditional = templateText.replace(
            "\"enableSeadex\": true,",
            "\"enableSeadex\": { \"__if\": \"inputs.sourceLanguages\", \"__value\": true },",
        )
        val http = FakeInstance(template = conditional)
        val result = setup(http).createConfig(request())

        assertEquals(AioStreamsConfigResult.Failed(RecommendedSourceFailure.TemplateUnsupported), result)
        assertNull(http.postBody, "a template the resolver cannot fully honour must never be sent")
    }

    @Test
    fun languageNotOfferedByTheTemplateIsRefused() {
        val template = Json.parseToJsonElement(templateText).jsonObject
        val error = runCatching {
            AioStreamsTemplateResolver.resolve(
                template = template,
                inputs = mapOf("sourceLanguages" to listOf("Klingon"), "subtitleLanguages" to listOf("English")),
                credentials = mapOf("torbox.apiKey" to key),
                availablePresetTypes = setOf("comet"),
            )
        }.exceptionOrNull()
        assertIs<AioStreamsTemplateException>(error)
        assertFalse(error.message.orEmpty().contains(key))
    }

    @Test
    fun everyMappedLanguageIsATemplateOption() {
        val template = Json.parseToJsonElement(templateText).jsonObject
        val inputs = template["metadata"]!!.jsonObject["inputs"]!!.jsonArray.map { it.jsonObject }
        inputs.forEach { input ->
            val offered = input["options"]!!.jsonArray.map { it.jsonObject["value"]!!.jsonPrimitive.content }.toSet()
            assertEquals(offered, AioStreamsLanguageByCode.values.toSet(), "mapping drifted from ${input["id"]}")
        }
    }

    @Test
    fun keyAndPasswordNeverAppearInToString() {
        assertFalse(TorBoxApiKey(key).toString().contains(key))
        assertFalse(request().toString().contains(key))
        assertFalse(AioStreamsRecovery("u", "id", "hunter2hunter2").toString().contains("hunter2"))
    }

    private fun setup(http: FakeInstance) = AioStreamsRecommendedSetup(
        instanceBaseUrl = "$Base/",
        templateUrl = TemplateUrl,
        http = http,
        generatePassword = { "p".repeat(64) },
    )

    private fun request(key: String = this.key, source: String = "English", subtitle: String = "English") =
        RecommendedSourceRequest(TorBoxApiKey(key), source, subtitle)

    private inner class FakeInstance(
        private val template: String = templateText,
        private val templateStatus: Int = 200,
        private val presets: String = AllPresets,
        private val userStatus: Int = 201,
        private val userBody: String = """{"success":true,"detail":"User was successfully created","data":{"uuid":"uuid-1","encryptedPassword":"ENC_pw"},"error":null}""",
    ) : AioStreamsHttp {
        val calls = mutableListOf<String>()
        var postBody: String? = null

        override suspend fun request(method: String, url: String, headers: Map<String, String>, body: String): RawHttpResponse {
            calls += "$method $url"
            return when {
                url == TemplateUrl -> response(templateStatus, template, url)
                url.endsWith("/api/v1/status") ->
                    response(200, """{"success":true,"data":{"version":"2.34.0","settings":{"presets":$presets}}}""", url)
                method == "POST" && url.endsWith("/api/v1/user") -> {
                    postBody = body
                    assertEquals("application/json", headers["Content-Type"])
                    response(userStatus, userBody, url)
                }
                else -> error("unexpected request $method $url")
            }
        }

        private fun response(status: Int, body: String, url: String) =
            RawHttpResponse(status = status, statusText = "", url = url, body = body, headers = emptyMap())
    }

    private companion object {
        const val Base = "https://aio.example"
        const val TemplateUrl = "https://templates.example/nuvio-z-torbox-v1.json"
        val AllPresets = listOf(
            "seadex", "neko-bt", "torznab", "stremthruTorz", "meteor", "knaben", "mediafusion", "opensubtitles-v3-plus", "comet",
        ).joinToString(",", "[", "]") { """{"ID":"$it"}""" }
    }
}
