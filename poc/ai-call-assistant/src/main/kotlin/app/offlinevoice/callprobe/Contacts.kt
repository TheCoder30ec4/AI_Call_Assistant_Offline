package app.offlinevoice.callprobe

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract

/** Reads your contacts, only on this phone, to know who's calling and to help spell names right. */
object Contacts {
    fun allowed(context: Context) =
        context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** The saved name for [number], or null if it isn't in your contacts (or access isn't granted). */
    fun nameFor(context: Context, number: String): String? {
        if (number.isBlank() || !allowed(context)) return null
        return runCatching {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0)?.trim()?.takeIf(String::isNotBlank) else null
            }
        }.getOrNull()
    }

    /**
     * Names from your contacts, for biasing the recognizer: full names plus each first name, most
     * contacted first. Capped, since a huge bias list stops helping.
     */
    // ponytail: top 150 by times contacted. Raise the cap if a less-frequent contact keeps being misheard.
    fun biasNames(context: Context, limit: Int = 150): List<String> {
        if (!allowed(context)) return emptyList()
        return runCatching {
            val names = LinkedHashSet<String>()
            context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
                "${ContactsContract.Contacts.HAS_PHONE_NUMBER} = 1",
                null,
                "${ContactsContract.Contacts.TIMES_CONTACTED} DESC",
            )?.use { cursor ->
                while (cursor.moveToNext() && names.size < limit) {
                    val name = cursor.getString(0)?.trim().orEmpty()
                    if (name.none(Char::isLetter)) continue
                    names += name
                    name.split(" ").firstOrNull()?.takeIf { it.length > 1 && it != name }?.let { names += it }
                }
            }
            names.toList()
        }.getOrDefault(emptyList())
    }
}
