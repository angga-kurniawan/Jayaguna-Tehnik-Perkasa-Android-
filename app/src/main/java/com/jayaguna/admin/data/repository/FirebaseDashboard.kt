package com.jayaguna.admin.data.repository

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import com.jayaguna.admin.data.model.Account
import com.jayaguna.admin.data.model.Dashboard
import com.jayaguna.admin.data.remote.audit
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.tasks.await

private const val DASHBOARD_BATCH_SIZE = 100

/** Reads only existing collections under the signed-in user's Firestore rules. */
internal suspend fun loadFirebaseDashboard(db: FirebaseFirestore, account: Account): Dashboard = coroutineScope {
    require(account.uid.isNotBlank() && account.role in setOf("OWNER", "ADMIN")) { "Akun tidak memiliki hak akses katalog." }
    val products = async {
        var active = 0
        var draft = 0
        scanDashboardQuery(db.collection("products").whereEqualTo("archived", false).orderBy(FieldPath.documentId())) { doc ->
            when (doc.get("status")) {
                "active" -> active++
                "draft", "inactive" -> draft++
            }
        }
        active to draft
    }
    val partners = async {
        var active = 0
        scanDashboardQuery(db.collection("partners").whereEqualTo("archived", false).orderBy(FieldPath.documentId())) { doc ->
            if (doc.get("active") == true) active++
        }
        active
    }
    val recent = async {
        var query: Query = db.collection("activityLogs")
        if (account.role == "ADMIN") query = query.whereEqualTo("actorUid", account.uid)
        query.orderBy("timestamp", Query.Direction.DESCENDING).limit(6).get(Source.SERVER).await().documents.map { it.audit() }
    }
    val counts = products.await()
    Dashboard(activeProducts = counts.first, draftProducts = counts.second, activePartners = partners.await(), recent = recent.await())
}

private suspend fun scanDashboardQuery(query: Query, consume: (DocumentSnapshot) -> Unit) {
    scanDashboardPages<DocumentSnapshot>(
        fetch = { cursor, limit ->
            (if (cursor == null) query else query.startAfter(cursor)).limit(limit.toLong()).get(Source.SERVER).await().documents
        },
        consume = consume
    )
}

/** The cursor is the last document, so every request stays within the rule limit. */
internal suspend fun <T : Any> scanDashboardPages(fetch: suspend (cursor: T?, limit: Int) -> List<T>, consume: (T) -> Unit) {
    var cursor: T? = null
    do {
        currentCoroutineContext().ensureActive()
        val page = fetch(cursor, DASHBOARD_BATCH_SIZE)
        currentCoroutineContext().ensureActive()
        page.forEach(consume)
        cursor = page.lastOrNull()
    } while (page.size == DASHBOARD_BATCH_SIZE)
}
