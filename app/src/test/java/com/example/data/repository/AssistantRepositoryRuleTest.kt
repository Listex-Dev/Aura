package com.example.data.repository

import com.example.base.FirestoreEmulatorTestBase
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AssistantRepositoryRuleTest : FirestoreEmulatorTestBase() {

    @Test
    fun createSmartItem_validPayload_createsAndEmitsInFlow() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        val repository = AssistantRepository(firestore, auth)

        val createResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.createSmartItem(
                title = "Позвонить маме",
                content = "Спросить про выходные",
                category = "REMINDER",
                scheduledTime = "19:30"
            )
        }
        assertTrue(createResult.isSuccess)
        val itemId = createResult.getOrThrow()
        assertTrue(itemId.isNotEmpty())

        val emittedItems = withTimeout(FLOW_TIMEOUT_MS) {
            repository.observeSmartItems(aliceUid).first { list -> list.any { it.id == itemId } }
        }
        assertTrue(emittedItems.any { it.id == itemId && it.title == "Позвонить маме" })
    }

    @Test
    fun createRoutineAndHistory_authenticatedOwner_succeeds() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        val repository = AssistantRepository(firestore, auth)

        val routineResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.createRoutine(
                title = "Ночной режим",
                triggerPhrase = "Спокойной ночи",
                iconName = "bedtime",
                actionSteps = listOf("TORCH:OFF", "VOLUME:15", "ALARM:07:30")
            )
        }
        assertTrue(routineResult.isSuccess)
        val routineId = routineResult.getOrThrow()

        val routines = withTimeout(FLOW_TIMEOUT_MS) {
            repository.observeRoutines(aliceUid).first { list -> list.any { it.id == routineId } }
        }
        assertTrue(routines.any { it.id == routineId })

        val logResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.addCommandLog(
                userQuery = "Включи фонарик",
                assistantReply = "Фонарик включён",
                executedActions = listOf("TORCH_ON")
            )
        }
        assertTrue(logResult.isSuccess)
    }

    @Test
    fun getSmartItemById_crossUserAccess_failsWithPermissionDenied() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        val aliceRepo = AssistantRepository(firestore, auth)
        val itemId = withTimeout(DEFAULT_TIMEOUT_MS) {
            aliceRepo.createSmartItem("Секретная заметка", "Пин-код", "NOTE").getOrThrow()
        }

        signInTestUser(BOB_EMAIL)
        val bobRepo = AssistantRepository(firestore, auth)
        val result = withTimeout(DEFAULT_TIMEOUT_MS) {
            bobRepo.getSmartItemById(aliceUid, itemId)
        }
        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull() as? FirebaseFirestoreException
        assertNotNull(ex)
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, ex?.code)
    }

    @Test
    fun observeSmartItems_unauthenticatedUser_failsWithPermissionDenied() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        auth.signOut()
        val repository = AssistantRepository(firestore, auth)

        try {
            withTimeout(FLOW_TIMEOUT_MS) {
                repository.observeSmartItems(aliceUid).first()
            }
            fail("Expected FirebaseFirestoreException PERMISSION_DENIED")
        } catch (e: FirebaseFirestoreException) {
            assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, e.code)
        }
    }

    private companion object {
        const val ALICE_EMAIL = "alice_aura@test.com"
        const val BOB_EMAIL = "bob_aura@test.com"
        const val DEFAULT_TIMEOUT_MS = 5000L
        const val FLOW_TIMEOUT_MS = 4000L
    }
}
