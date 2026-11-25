package io.github.r1b2ns.stackaudit.demo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DemoScenariosTest {
    @Test
    fun titlesAreUnique() {
        val titles = DemoScenarios.all.map { it.title }
        assertEquals(titles.size, titles.toSet().size)
    }

    @Test
    fun scenariosUseHttpsAndCoverBothClients() {
        assertTrue(DemoScenarios.all.all { it.url.startsWith("https://") || it.url.startsWith("wss://") })
        assertEquals(Client.entries.toSet(), DemoScenarios.all.map { it.client }.toSet())
    }

    @Test
    fun secretsScenarioCarriesSecretsToMask() {
        val login = DemoScenarios.all.single { it.method == "POST" }
        assertTrue("password" in login.body.orEmpty())
        assertTrue(login.headers.containsKey("Authorization"))
    }
}
