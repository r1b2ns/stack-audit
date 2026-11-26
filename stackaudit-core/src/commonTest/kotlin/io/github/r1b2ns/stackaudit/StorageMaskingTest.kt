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
    fun masksValuesOfSecretHeaderNames() {
        assertEquals("••••1234", scrubber.storageValue("Authorization", "Bearer abcdefghijkl1234"))
        assertEquals("••••1234", scrubber.storageValue("cookie", "session=abcd1234"))
        assertEquals(
            listOf("••••1234", "7"),
            scrubber.sqlArguments("UPDATE session SET cookie = ? WHERE id = ?", listOf("session=abcd1234", "7")),
        )
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

    @Test
    fun masksLiteralsAssignedToSecretColumns() {
        assertEquals(
            "UPDATE user SET name = 'ana', password = '••••' WHERE id = 7",
            scrubber.sql("UPDATE user SET name = 'ana', password = 'hunter2' WHERE id = 7"),
        )
        assertEquals("SELECT * FROM s WHERE token='••••5678'", scrubber.sql("SELECT * FROM s WHERE token='abcdefgh12345678'"))
        assertEquals("SELECT * FROM u WHERE password = '••••' AND age > 3", scrubber.sql("SELECT * FROM u WHERE password = 1234 AND age > 3"))
        assertEquals("SELECT * FROM u WHERE password = ?", scrubber.sql("SELECT * FROM u WHERE password = ?"))
    }

    @Test
    fun masksLiteralsInsideInsertValues() {
        assertEquals(
            "INSERT INTO user (name, password, note) VALUES ('ana', '••••', 'a, b'), ('bia', '••••', 'it''s')",
            scrubber.sql("INSERT INTO user (name, password, note) VALUES ('ana', 'hun,ter2', 'a, b'), ('bia', 'x''y', 'it''s')"),
        )
        assertEquals(
            "insert or replace into `session` (`id`, `token`) values (1, '••••'), (2, ?)",
            scrubber.sql("insert or replace into `session` (`id`, `token`) values (1, 'abc'), (2, ?)"),
        )
        assertEquals(
            "INSERT INTO t (a, api_key) VALUES (lower('X'), '••••')",
            scrubber.sql("INSERT INTO t (a, api_key) VALUES (lower('X'), 'shh')"),
        )
    }

    @Test
    fun ignoresSecretLookingTextInsideOtherLiterals() {
        val sql = "INSERT INTO post (title, body) VALUES ('password = ''x''', 'token = 1'); SELECT * FROM post WHERE id = 3"
        assertEquals(sql, scrubber.sql(sql))
        assertEquals("SELECT 1", scrubber.sql("SELECT 1"))
    }
}
