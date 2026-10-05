package com.luminaauth.plugin

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultPluginTest {

    private val yaml = File("src/main/assets/default_drcom.plugin.yaml").readText(Charsets.UTF_8)
    private val plugin by lazy { PluginLoader.load(yaml, "default_drcom.plugin.yaml", PluginSource.BUILTIN) }

    @Test
    fun modelMapped() {
        assertEquals("drcom-default", plugin.id)
        assertEquals(2, plugin.hosts.size)
        assertEquals(2, plugin.fields.size)
        assertEquals(FieldType.PASSWORD, plugin.fields[1].type)
        assertTrue(plugin.fields[0].required)
        assertEquals(1, plugin.isps.size)
        assertNotNull(plugin.check)
        assertEquals(1, plugin.loginSteps.size)
        assertEquals(6, plugin.ssidPatterns.size)
        assertEquals(
            listOf(
                "ChinaNet-JLZG", "ChinaNet-JLZG-5G", "ChinaNet-JLZG-2.4G",
                "JLZG", "JLZG-5G", "JLZG-2.4G",
            ),
            plugin.ssidPatterns,
        )
    }

    @Test
    fun loginUrlMatchesLegacyByteForByte() {
        val state = SessionState(plugin, HashMap(), "after")
        state.inputs[Plugin.FIELD_USERNAME] = "2024001"
        state.inputs[Plugin.FIELD_PASSWORD] = "pass123"
        state.ip = "10.10.10.10"
        state.rand = 4242
        val rendered = PluginRules.expand(plugin.loginSteps[0].url, resolve = { state.resolve(it) })
        val expected = "http://218.6.130.195:801/eportal/portal/login" +
            "?callback=dr1003&login_method=1&user_account=,1,2024001@after" +
            "&user_password=pass123&wlan_user_ip=10.10.10.10&wlan_user_ipv6=" +
            "&wlan_user_mac=000000000000&wlan_ac_ip=218.89.190.11&wlan_ac_name=" +
            "&jsVersion=4.1.3&terminal_type=2&lang=zh-cn&v=4242&lang=zh"
        assertEquals(expected, rendered)
    }

    @Test
    fun loginResponseRules() {
        val step = plugin.loginSteps[0]
        assertTrue(PluginRules.matches(step.success!!, "dr1003({\"result\":1,\"msg\":\"\"})"))
        assertFalse(PluginRules.matches(step.success!!, "dr1003({\"result\":0,\"msg\":\"x\"})"))
        assertTrue(
            PluginRules.matches(step.alreadyOnline!!, "dr1003({\"result\":0,\"msg\":\"您已经在线了\"})"),
        )
        assertEquals(
            "password error",
            PluginRules.jsonPath("dr1003({\"result\":0,\"msg\":\"password error\"})", "msg"),
        )
        assertEquals("1", PluginRules.jsonPath("dr1003({\"result\":1})", "result"))
        assertEquals(
            "password error",
            step.failMessageJsonPath?.let {
                PluginRules.jsonPath("dr1003({\"result\":0,\"msg\":\"password error\"})", it)
            },
        )
    }

    @Test
    fun checkResponseRules() {
        val check = plugin.check!!
        assertTrue("dr1002({\"result\":1})".contains(check.onlineContains!!))
        assertTrue("dr1002({\"result\":0})".contains(check.offlineContains!!))
    }

    @Test
    fun parserBasics() {
        val m = YamlParser.parse(
            "a: 1\nb:\n  c: hi\nd:\n  - x\n  - y\n# comment\n",
        ) as Map<*, *>
        assertEquals(1L, m["a"])
        assertEquals("hi", (m["b"] as Map<*, *>)["c"])
        assertEquals(listOf("x", "y"), m["d"])
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsHostOutsideAllowList() {
        val bad = """
            id: bad
            sandbox:
              hosts:
                - 218.6.130.195:801
            fields:
              - id: username
              - id: password
            login:
              steps:
                - url: "http://evil.example:801/x"
        """.trimIndent()
        PluginLoader.load(bad, "bad.plugin.yaml", PluginSource.EXTERNAL)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonHttpScheme() {
        val bad = """
            id: bad
            sandbox:
              hosts:
                - 218.6.130.195:801
            fields:
              - id: username
              - id: password
            login:
              steps:
                - url: "file:///sdcard/x"
        """.trimIndent()
        PluginLoader.load(bad, "bad.plugin.yaml", PluginSource.EXTERNAL)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPortMismatch() {
        val bad = """
            id: bad
            sandbox:
              hosts:
                - 218.6.130.195:801
            fields:
              - id: username
              - id: password
            login:
              steps:
                - url: "http://218.6.130.195:909/x"
        """.trimIndent()
        PluginLoader.load(bad, "bad.plugin.yaml", PluginSource.EXTERNAL)
    }
}
