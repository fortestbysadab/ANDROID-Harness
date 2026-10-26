package com.androidharness.tools

import android.Manifest
import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import com.androidharness.permissions.Permissions
import org.json.JSONArray
import org.json.JSONObject

/**
 * Looking someone up in the address book.
 *
 * Searching rather than listing is the point. "Give me all my
 * contacts" would spend the model's context on a thousand names to
 * answer a question about one, and would hand an entire address book
 * to a provider over the network when a single row was wanted.
 */
class ContactsTool(private val context: Context) : NativeTool {

    override val name = "find_contact"
    override val description =
        "Look up a person's phone number in the owner's contacts by name or by " +
            "part of a number. Returns at most 25 matches."
    override val inputSchema = """
        {
          "type": "object",
          "properties": {
            "query": {"type": "string", "minLength": 1, "maxLength": 100}
          },
          "required": ["query"]
        }
    """.trimIndent()
    override val risk = "sensitive_read"

    override fun run(payloadJson: String): String {
        val query = NativeTool.argumentsOf(payloadJson).optString("query").trim()
        if (query.isEmpty()) {
            return NativeTool.error("say who to look for", "empty_query")
        }

        val outcome = Permissions.ensure(context, listOf(Manifest.permission.READ_CONTACTS))
        if (outcome != Permissions.Outcome.GRANTED) {
            return NativeTool.error(
                Permissions.explain(outcome, "contacts"),
                Permissions.code(outcome),
                retryable = outcome == Permissions.Outcome.CANNOT_ASK,
            )
        }

        val found = mutableListOf<ToolFormatting.Person>()
        val uri: Uri = Uri.withAppendedPath(
            ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI,
            Uri.encode(query),
        )
        try {
            context.contentResolver.query(
                uri,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                while (cursor.moveToNext() && found.size < 200) {
                    found += ToolFormatting.Person(
                        name = cursor.getString(0).orEmpty(),
                        number = cursor.getString(1).orEmpty(),
                    )
                }
            }
        } catch (error: SecurityException) {
            return NativeTool.error("the contacts store refused the read", "permission_refused")
        }

        val people = ToolFormatting.tidyContacts(found)
        val data = JSONArray()
        people.forEach { data.put(JSONObject().put("name", it.name).put("number", it.number)) }
        return NativeTool.ok(
            ToolFormatting.describeContacts(people, query),
            JSONObject().put("matches", data).put("count", people.size),
        )
    }
}
