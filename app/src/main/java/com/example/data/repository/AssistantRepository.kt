package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.data.model.AutomationRoutine
import com.example.data.model.CommandLog
import com.example.data.model.SmartItem
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

class AssistantRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        ),
        FirebaseAuth.getInstance()
    )

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("Пользователь должен войти через Google перед доступом к облаку.")
    }

    // --- Smart Items (Notes, Tasks, Reminders) ---

    fun observeSmartItems(userId: String = auth.currentUser?.uid ?: ""): Flow<List<SmartItem>> = flow {
        val targetUid = userId.ifBlank { auth.currentUser?.uid ?: "unauthenticated" }
        val path = "users/$targetUid/items"
        emitAll(
            db.collection("users").document(targetUid).collection("items")
                .whereEqualTo("userId", targetUid)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(SmartItem::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedByDescending { it.createdAt?.seconds ?: 0L }
                }
                .catch { error ->
                    val firestoreEx = (error as? com.google.firebase.firestore.FirebaseFirestoreException)
                        ?: (error.cause as? com.google.firebase.firestore.FirebaseFirestoreException)
                        ?: (error.cause?.cause as? com.google.firebase.firestore.FirebaseFirestoreException)
                    if (firestoreEx != null) {
                        handleFirestoreError(firestoreEx, OperationType.LIST, path)
                        throw firestoreEx
                    }
                    if (error is Exception) handleFirestoreError(error, OperationType.LIST, path)
                    throw error
                }
        )
    }

    suspend fun createSmartItem(
        title: String,
        content: String,
        category: String = "NOTE",
        scheduledTime: String = ""
    ): Result<String> {
        return try {
            val uid = requireUserId()
            val cleanTitle = title.trim().take(120).ifBlank { "Заметка" }
            val cleanContent = content.trim().take(1000)
            val cleanCategory = if (category in listOf("NOTE", "TASK", "REMINDER")) category else "NOTE"
            val cleanTime = scheduledTime.trim().take(64)

            val colRef = db.collection("users").document(uid).collection("items")
            val docRef = colRef.document()
            val payload = mapOf(
                "title" to cleanTitle,
                "content" to cleanContent,
                "category" to cleanCategory,
                "isCompleted" to false,
                "scheduledTime" to cleanTime,
                "userId" to uid,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            docRef.set(payload).await()
            Result.success(docRef.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, "users/${auth.currentUser?.uid}/items")
            Result.failure(e)
        }
    }

    suspend fun getSmartItemById(ownerUid: String, itemId: String): Result<SmartItem?> {
        val path = "users/$ownerUid/items/$itemId"
        return try {
            val snap = db.collection("users").document(ownerUid).collection("items").document(itemId)
                .get(com.google.firebase.firestore.Source.SERVER)
                .await()
            Result.success(snap.toObject(SmartItem::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, path)
            Result.failure(e)
        }
    }

    suspend fun toggleSmartItemCompleted(item: SmartItem): Result<Unit> {
        return try {
            val uid = requireUserId()
            require(item.id.isNotBlank()) { "Item ID must not be blank" }
            requireNotNull(item.createdAt) { "Item createdAt must not be null" }
            val docRef = db.collection("users").document(uid).collection("items").document(item.id)
            docRef.update(
                mapOf(
                    "isCompleted" to !item.isCompleted,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, "users/${auth.currentUser?.uid}/items/${item.id}")
            Result.failure(e)
        }
    }

    suspend fun deleteSmartItem(itemId: String): Result<Unit> {
        return try {
            val uid = requireUserId()
            val docRef = db.collection("users").document(uid).collection("items").document(itemId)
            docRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, "users/${auth.currentUser?.uid}/items/$itemId")
            Result.failure(e)
        }
    }

    // --- Automation Routines (Macros) ---

    fun observeRoutines(userId: String = auth.currentUser?.uid ?: ""): Flow<List<AutomationRoutine>> = flow {
        val targetUid = userId.ifBlank { auth.currentUser?.uid ?: "unauthenticated" }
        val path = "users/$targetUid/routines"
        emitAll(
            db.collection("users").document(targetUid).collection("routines")
                .whereEqualTo("userId", targetUid)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(AutomationRoutine::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedByDescending { it.createdAt?.seconds ?: 0L }
                }
                .catch { error ->
                    val firestoreEx = (error as? com.google.firebase.firestore.FirebaseFirestoreException)
                        ?: (error.cause as? com.google.firebase.firestore.FirebaseFirestoreException)
                        ?: (error.cause?.cause as? com.google.firebase.firestore.FirebaseFirestoreException)
                    if (firestoreEx != null) {
                        handleFirestoreError(firestoreEx, OperationType.LIST, path)
                        throw firestoreEx
                    }
                    if (error is Exception) handleFirestoreError(error, OperationType.LIST, path)
                    throw error
                }
        )
    }

    suspend fun createRoutine(
        title: String,
        triggerPhrase: String,
        iconName: String,
        actionSteps: List<String>
    ): Result<String> {
        return try {
            val uid = requireUserId()
            val cleanSteps = actionSteps.map { it.trim().take(200) }.filter { it.isNotEmpty() }.take(10)
                .ifEmpty { listOf("SPEAK:Сценарий запущен") }

            val colRef = db.collection("users").document(uid).collection("routines")
            val docRef = colRef.document()
            val payload = mapOf(
                "title" to title.trim().take(100).ifBlank { "Мой сценарий" },
                "triggerPhrase" to triggerPhrase.trim().take(120).ifBlank { "Запусти сценарий" },
                "iconName" to iconName.trim().take(40).ifBlank { "bolt" },
                "actionSteps" to cleanSteps,
                "userId" to uid,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            docRef.set(payload).await()
            Result.success(docRef.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, "users/${auth.currentUser?.uid}/routines")
            Result.failure(e)
        }
    }

    suspend fun deleteRoutine(routineId: String): Result<Unit> {
        return try {
            val uid = requireUserId()
            val docRef = db.collection("users").document(uid).collection("routines").document(routineId)
            docRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, "users/${auth.currentUser?.uid}/routines/$routineId")
            Result.failure(e)
        }
    }

    // --- Command History Logs ---

    fun observeHistory(userId: String = auth.currentUser?.uid ?: ""): Flow<List<CommandLog>> = flow {
        val targetUid = userId.ifBlank { auth.currentUser?.uid ?: "unauthenticated" }
        val path = "users/$targetUid/history"
        emitAll(
            db.collection("users").document(targetUid).collection("history")
                .whereEqualTo("userId", targetUid)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(CommandLog::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedByDescending { it.createdAt?.seconds ?: 0L }
                }
                .catch { error ->
                    val firestoreEx = (error as? com.google.firebase.firestore.FirebaseFirestoreException)
                        ?: (error.cause as? com.google.firebase.firestore.FirebaseFirestoreException)
                        ?: (error.cause?.cause as? com.google.firebase.firestore.FirebaseFirestoreException)
                    if (firestoreEx != null) {
                        handleFirestoreError(firestoreEx, OperationType.LIST, path)
                        throw firestoreEx
                    }
                    if (error is Exception) handleFirestoreError(error, OperationType.LIST, path)
                    throw error
                }
        )
    }

    suspend fun addCommandLog(
        userQuery: String,
        assistantReply: String,
        executedActions: List<String>
    ): Result<String> {
        return try {
            val uid = requireUserId()
            val colRef = db.collection("users").document(uid).collection("history")
            val docRef = colRef.document()
            val payload = mapOf(
                "userQuery" to userQuery.trim().take(500).ifBlank { "Голосовая команда" },
                "assistantReply" to assistantReply.trim().take(1000).ifBlank { "Выполнено" },
                "executedActions" to executedActions.map { it.take(200) }.take(10),
                "userId" to uid,
                "createdAt" to FieldValue.serverTimestamp()
            )
            docRef.set(payload).await()
            Result.success(docRef.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, "users/${auth.currentUser?.uid}/history")
            Result.failure(e)
        }
    }

    suspend fun deleteCommandLog(logId: String): Result<Unit> {
        return try {
            val uid = requireUserId()
            val docRef = db.collection("users").document(uid).collection("history").document(logId)
            docRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, "users/${auth.currentUser?.uid}/history/$logId")
            Result.failure(e)
        }
    }
}
