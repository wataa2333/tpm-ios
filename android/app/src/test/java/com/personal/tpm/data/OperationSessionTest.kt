package com.personal.tpm.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.lang.reflect.Proxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OperationSessionTest {
    @get:Rule val folder = TemporaryFolder()
    private fun context(): Context {
        val preferences = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getString" -> args!![1]
                "edit" -> error("An operation session must not change saved account preferences")
                else -> null
            }
        } as SharedPreferences
        return object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = folder.root
            override fun getSharedPreferences(name: String?, mode: Int) = preferences
        }
    }
    private fun authenticate(session: Session, name: String) {
        // Exercise the same update function as login and token renewal without a server request.
        Session::class.java.getDeclaredMethod("update", JSONObject::class.java).apply { isAccessible = true }
            .invoke(session, Jv.obj("name" to name, "id" to name, "token" to "offline-token", "factoryId" to "same-factory"))
    }
    @Test fun temporaryLoginRenewalAndLogoutLeavePersistedSessionUntouched() {
        val ctx = context()
        val saved = folder.newFile("session.bin").apply { writeText("original-encrypted-session") }
        val temporary = Session(ctx, transient = true)
        assertFalse(temporary.signedIn)
        authenticate(temporary, "temporary")
        assertTrue(temporary.signedIn)
        assertEquals("temporary", temporary.accountName)
        assertEquals("original-encrypted-session", saved.readText())
        temporary.signOut()
        assertTrue(saved.exists())
        assertEquals("original-encrypted-session", saved.readText())
    }
    @Test fun temporarySessionDoesNotChangeOwnerAndForwardsRefresh() {
        val ctx = context()
        val owner = Session(ctx, transient = true)
        authenticate(owner, "owner")
        val temporary = Session(ctx, transient = true).apply { bindOperationOwner(owner) }
        authenticate(temporary, "operator")
        val originalScope = owner.scope.value
        temporary.notifyChanged()
        assertEquals(1, owner.changed.value)
        assertEquals(originalScope, owner.scope.value)
        assertEquals("owner", owner.accountName)
        assertEquals("same-factory", owner.currentFactoryId)
    }
    @Test fun changedPrimaryAccountStopsOperationBeforeAnyNetworkRequest() = runTest {
        val ctx = context()
        val owner = Session(ctx, transient = true)
        authenticate(owner, "owner")
        val temporary = Session(ctx, transient = true).apply { bindOperationOwner(owner) }
        authenticate(temporary, "operator")
        owner.signOut()
        authenticate(owner, "new-owner")
        try {
            temporary.request("process/commitTask", body = JSONObject())
            fail("An old operation must not be sent after the main account changes")
        } catch (e: CancellationException) {
            assertTrue(e.message.orEmpty().contains("主账号或厂区已改变"))
        }
    }
}
