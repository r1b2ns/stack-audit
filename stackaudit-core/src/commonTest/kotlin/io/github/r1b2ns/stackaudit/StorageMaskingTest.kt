package io.github.r1b2ns.stackaudit

import kotlin.test.Test
import kotlin.test.assertEquals

class StorageMaskingTest {
    private val scrubber = SecretScrubber(SecretRules.Default)

    @Test
    fun masksValuesOfSecretKeys() {
        assertEquals("••••", scrubber.storageValue("password", "hunter2"))
        assertEquals("••••5678", scrubber.storageValue("access_token", "abcdefgh12345678"))
        assertEquals("ana", scrubber.storageValue("user", "ana"))
        assertEquals(null, scrubber.storageValue("token", null))
    }

    @Test
    fun masksSecretsInsideJsonValues() {
        assertEquals("""{"token":"••••","id":1}""", scrubber.storageValue("session", """{"token":"abc","id":1}"""))
    }

    @Test
    fun mapsPlaceholdersToColumns() {
        assertEquals(listOf("name", "password", null), SqlColumns.forPlaceholders("INSERT INTO user(name, password, age) VALUES (?, ?, 3) RETURNING ?"))
        assertEquals(listOf("token", "id"), SqlColumns.forPlaceholders("UPDATE session SET token = ? WHERE id=?"))
        assertEquals(listOf("email", null), SqlColumns.forPlaceholders("SELECT * FROM u WHERE email LIKE ? LIMIT ?"))
        assertEquals(listOf("a"), SqlColumns.forPlaceholders("SELECT * FROM t WHERE a == ?"))
        assertEquals(listOf("name"), SqlColumns.forPlaceholders("insert or replace into `user` (`name`) values (?)"))
    }

    @Test
    fun masksSqlArgumentsBoundToSecretColumns() {
        assertEquals(
            listOf("ana", "••••", "7"),
            scrubber.sqlArguments("UPDATE user SET name = ?, password = ? WHERE id = ?", listOf("ana", "hunter2", "7")),
        )
    }
}
