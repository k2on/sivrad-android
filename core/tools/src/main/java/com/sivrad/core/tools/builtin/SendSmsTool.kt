package com.sivrad.core.tools.builtin

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.telephony.SmsManager
import com.sivrad.core.tools.Confirmation
import com.sivrad.core.tools.NameMatcher
import com.sivrad.core.tools.ObjectSchema
import com.sivrad.core.tools.Preparation
import com.sivrad.core.tools.StringParam
import com.sivrad.core.tools.Tool
import com.sivrad.core.tools.ToolEnvironment
import com.sivrad.core.tools.ToolResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject

/**
 * Sends a text to a contact. Needs the device unlocked (contacts are
 * personal, and sending acts as the user) and an explicit tap on the
 * confirmation card showing the resolved recipient and the exact message.
 */
class SendSmsTool : Tool {
    override val name = "send_sms"
    override val description =
        "Send a text message (SMS) to a person in the user's contacts. The user sees the recipient and message and must confirm before it is sent."
    override val requiresUnlock = true
    override val replyDirectly = true
    override val parameters = ObjectSchema.of(
        "contact_name" to StringParam("Name of the contact as saved in the phone's contacts.", maxLength = 100),
        "message" to StringParam("The exact text to send.", maxLength = 1000),
        required = listOf("contact_name", "message"),
    )

    override suspend fun prepare(args: JsonObject, env: ToolEnvironment): Preparation {
        val ctx = env.context
        for (perm in listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.SEND_SMS)) {
            if (ctx.checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
                return Preparation.Rejected("Permission $perm has not been granted to the assistant. Ask the user to grant it in the Sivrad setup screen.")
            }
        }
        val query = args.string("contact_name").trim()
        val message = args.string("message")
        if (message.isBlank()) return Preparation.Rejected("The message is empty.")

        val recipient = when (val r = resolveContact(ctx, query)) {
            is Resolution.Found -> r.contact
            is Resolution.NotFound -> return Preparation.Rejected("No contact with a phone number matches \"$query\".")
            is Resolution.Ambiguous -> return Preparation.Rejected(
                "Several contacts match \"$query\": ${r.names.joinToString()}. Ask the user which one they mean.",
            )
        }

        val confirmation = Confirmation(
            title = "Send text message?",
            fields = listOf(
                "To" to "${recipient.name} (${recipient.number})",
                "Message" to message,
            ),
            confirmLabel = "Send",
        )
        return Preparation.Ready(confirmation) { send(ctx, recipient, message) }
    }

    private suspend fun send(ctx: Context, to: Contact, message: String): ToolResult {
        val sms = ctx.getSystemService(SmsManager::class.java)
        val parts = sms.divideMessage(message)
        val action = "${ctx.packageName}.SMS_SENT.${System.nanoTime()}"
        val result = CompletableDeferred<Int>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                result.complete(resultCode)
            }
        }
        ctx.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
        try {
            val sent = PendingIntent.getBroadcast(
                ctx, 0, Intent(action).setPackage(ctx.packageName),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT,
            )
            // Only the last part reports; it is sent after the others.
            val sentIntents = ArrayList<PendingIntent?>(List(parts.size) { null })
            sentIntents[parts.size - 1] = sent
            sms.sendMultipartTextMessage(to.number, null, parts, sentIntents, null)

            val code = withTimeoutOrNull(30_000) { result.await() }
            return when (code) {
                Activity.RESULT_OK -> ToolResult.Success("Sent to ${to.name}.")
                null -> ToolResult.Success("Handed to the radio for ${to.name}; no delivery report yet.")
                else -> ToolResult.Error("The message to ${to.name} failed to send (error $code). Check signal and try again.")
            }
        } finally {
            ctx.unregisterReceiver(receiver)
        }
    }

    data class Contact(val name: String, val number: String)

    sealed interface Resolution {
        data class Found(val contact: Contact) : Resolution
        data object NotFound : Resolution
        data class Ambiguous(val names: List<String>) : Resolution
    }

    companion object {
        /**
         * Looks [query] up with the contacts provider's own name filter, then
         * narrows: an exact (case-insensitive) display name wins, then a single
         * matching contact. For a contact with several numbers the primary one,
         * else mobile, else the first, is used.
         */
        suspend fun resolveContact(ctx: Context, query: String): Resolution = withContext(Dispatchers.IO) {
            val filtered = queryPhones(ctx, Uri.withAppendedPath(Phone.CONTENT_FILTER_URI, Uri.encode(query)))
            val exact = pick(query, filtered)
            if (exact != Resolution.NotFound) return@withContext exact
            // The provider's filter is a prefix match, so a misheard name
            // ("Jon" for "John", "Aiden" for "Aidan") finds nothing; fall back
            // to fuzzy matching over every contact with a number.
            fuzzyPick(query, queryPhones(ctx, Phone.CONTENT_URI))
        }

        private fun queryPhones(ctx: Context, uri: Uri): List<Row> {
            val rows = mutableListOf<Row>()
            ctx.contentResolver.query(
                uri,
                arrayOf(Phone.CONTACT_ID, Phone.DISPLAY_NAME, Phone.NUMBER, Phone.TYPE, Phone.IS_SUPER_PRIMARY),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val number = c.getString(2) ?: continue
                    rows += Row(c.getLong(0), c.getString(1) ?: number, number, c.getInt(3), c.getInt(4) != 0)
                }
            }
            return rows
        }

        fun fuzzyPick(query: String, rows: List<Row>): Resolution {
            val byContact = rows.groupBy { it.contactId }.values.toList()
            val match = NameMatcher.best(query, byContact, { it.first().name }) ?: return Resolution.NotFound
            return pick(match.name, match.item)
        }

        data class Row(val contactId: Long, val name: String, val number: String, val type: Int, val primary: Boolean)

        fun pick(query: String, rows: List<Row>): Resolution {
            if (rows.isEmpty()) return Resolution.NotFound
            val byContact = rows.groupBy { it.contactId }
            val exact = byContact.filterValues { r -> r.first().name.equals(query, ignoreCase = true) }
            val candidates = when {
                exact.size == 1 -> exact
                byContact.size == 1 -> byContact
                else -> return Resolution.Ambiguous(
                    (exact.ifEmpty { byContact }).values.map { it.first().name }.distinct().take(5),
                )
            }
            val numbers = candidates.values.single()
            val best = numbers.firstOrNull { it.primary }
                ?: numbers.firstOrNull { it.type == Phone.TYPE_MOBILE }
                ?: numbers.first()
            return Resolution.Found(Contact(best.name, best.number))
        }
    }
}
