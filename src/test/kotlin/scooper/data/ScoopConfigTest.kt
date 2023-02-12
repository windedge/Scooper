package scooper.data

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import scooper.util.Scoop
import scooper.util.form_builder.ChoiceState
import scooper.util.form_builder.FormState
import scooper.util.form_builder.SwitchState
import scooper.util.form_builder.TextFieldState
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.full.memberProperties

class ScoopConfigTest {
    private val format = Json {
        prettyPrint = true
        isLenient = true
        ignoreUnknownKeys = true
    }

    private val jsonText = """
            {
                "aria2-enabled":  true,
                "alias":  {

                          },
                "proxy": "127.0.0.1:1081",
                "last_update":  "2023-02-11T15:21:55.9731897+08:00",
                "scoop_branch":  "master",
                "scoop_repo":  "https://github.com/lukesampson/scoop"
            }
        """.trimIndent()

    @Test
    fun testSerialize() {
        val config = ScoopConfig(proxy = "127.0.0.1:1080", aria2Enabled = true)
        val json = format.encodeToJsonElement(config).jsonObject

        println("json = ${json}")

    }

    @Test
    fun testDeserialize() {
        val config = format.decodeFromString<ScoopConfig>(jsonText)
        assertEquals(true, config.aria2Enabled)
        assertEquals("127.0.0.1:1081", config.proxy)
    }

    @Test
    fun testReadConfig() {
        val config = Scoop.readScoopConfig()
        println("config = ${config}")
    }

    @Test
    fun testWriteConfig() {
        val config = ScoopConfig(proxy = "localhost:2183", aria2Enabled = false)
        Scoop.writeScoopConfig(config, Scoop.configFile.parentFile.resolve("config.test.json"))
    }

    @Test
    fun testGetData() {
        // val config = ScoopConfig(proxy = "localhost:2183", aria2Enabled = false)
        // val fields = config::class.declaredMemberProperties
        // println("fields = ${fields}")

        val formState = FormState(
            fields = listOf(
                ChoiceState("proxyType", validators = listOf()),
                TextFieldState("proxy"),
                SwitchState("aria2Enabled")
            )
        )
        formState.getState<ChoiceState>("proxyType").value = "custom"
        formState.getState<TextFieldState>("proxy").value = "127.0.0.1:1081"
        formState.getState<SwitchState>("aria2Enabled").value = true
        val config = formState.getData(ScoopConfig::class)
        assertEquals("127.0.0.1:1081", config.proxy)
    }

    @Test
    fun testSetData() {
        val config = ScoopConfig(proxy = "localhost:2183", aria2Enabled = false)
        val formState = FormState(
            fields = listOf(
                ChoiceState("proxyType", validators = listOf()),
                TextFieldState("proxy"),
                SwitchState("aria2Enabled")
            )
        )
        formState.setData(config)
        assertEquals("custom", formState.getState<ChoiceState>("proxyType").value)
    }

    @Test
    fun testEnum() {
        val config = UIConfig(theme = Theme.Light)
        val formState = FormState(
            fields = listOf(
                SwitchState("refreshOnStartup"),
                ChoiceState("theme", validators = listOf(), choices = Theme.values().associate { it.name to it.name }),
            )
        )

        // println("config.theme.javaClass.isEnum = ${config.theme.javaClass.isEnum}")
        assertTrue(config.theme.javaClass.isEnum)

        assertDoesNotThrow {
            formState.setData(config)
        }
        val themeState = formState.getState<ChoiceState>("theme")
        assertEquals("Light", themeState.value)

        val theme = Theme.valueOf("Auto")
        assertEquals(Theme.Auto, theme)

        val xTheme: Any = theme.javaClass.enumConstants[2]
        assertEquals(Theme.Dark, xTheme)

        val uiconfig = formState.getData(UIConfig::class)
        assertEquals(Theme.Light, uiconfig.theme)
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun testRemoveKeys() {
        val format = Json {
            // encodeDefaults = true
            isLenient = true
            ignoreUnknownKeys = true
            prettyPrint = true
        }
        val config = ScoopConfig(proxy = "localhost:2183", aria2Enabled = false)
        val json = format.encodeToJsonElement(config).jsonObject
        val jsonFromFile = format.parseToJsonElement(jsonText).jsonObject
        /*
        @Suppress("SimplifiableCall")
        val keys = ScoopConfig::class
            .memberProperties
            .filter { it is KMutableProperty1 }
            .map { it.name }.toSet()
        */
        val keys = ScoopConfig.serializer().descriptor.elementNames.toSet()
        println("keys = ${keys}")
        val keysToRemove = keys - json.jsonObject.keys
        val xxx = jsonFromFile.filter { it.key !in keysToRemove }
        println("xxx = ${xxx}")
        assertTrue(!xxx.containsKey("aria2-enabled"))
    }


}