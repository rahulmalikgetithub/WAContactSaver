package com.wabiz.contactsaver

import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.RawContacts

/**
 * Reads existing device contacts and creates new ones.
 */
object ContactManager {

    // ── Read all saved phone numbers ─────────────────────────────────────

    /**
     * Returns a set of normalised digit-only phone number strings
     * for every contact currently saved on the device.
     */
    fun loadSavedNumbers(context: Context): Set<String> {
        val result = mutableSetOf<String>()
        val resolver: ContentResolver = context.contentResolver

        val cursor: Cursor? = resolver.query(
            Phone.CONTENT_URI,
            arrayOf(Phone.NUMBER),
            null, null, null
        )
        cursor?.use {
            val col = it.getColumnIndex(Phone.NUMBER)
            while (it.moveToNext()) {
                val raw = it.getString(col) ?: continue
                val digits = raw.filter { c -> c.isDigit() }
                if (digits.length >= 7) {
                    result.add(digits)
                    // Also store last 10 digits for fuzzy matching
                    if (digits.length > 10) result.add(digits.takeLast(10))
                }
            }
        }
        return result
    }

    // ── Check if a number is already saved ───────────────────────────────

    fun isSaved(digits: String, savedSet: Set<String>): Boolean {
        val n = digits.filter { it.isDigit() }
        if (savedSet.contains(n)) return true
        if (n.length > 10 && savedSet.contains(n.takeLast(10))) return true
        // Try stripping country code (up to 3 digits)
        for (trim in 1..3) {
            if (n.length > trim && savedSet.contains(n.drop(trim))) return true
        }
        return false
    }

    // ── Save a new contact ───────────────────────────────────────────────

    /**
     * Creates a new device contact with [displayName] and [phoneNumber].
     * Returns true on success.
     */
    fun saveContact(context: Context, displayName: String, phoneNumber: String): Boolean {
        return try {
            val ops = ArrayList<ContentProviderOperation>()

            // 1. Insert raw contact
            ops.add(
                ContentProviderOperation.newInsert(RawContacts.CONTENT_URI)
                    .withValue(RawContacts.ACCOUNT_TYPE, null)
                    .withValue(RawContacts.ACCOUNT_NAME, null)
                    .build()
            )

            // 2. Insert display name
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(
                        ContactsContract.Data.MIMETYPE,
                        ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE
                    )
                    .withValue(
                        ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME,
                        displayName
                    )
                    .build()
            )

            // 3. Insert phone number
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(
                        ContactsContract.Data.MIMETYPE,
                        Phone.CONTENT_ITEM_TYPE
                    )
                    .withValue(Phone.NUMBER, phoneNumber)
                    .withValue(Phone.TYPE, Phone.TYPE_MOBILE)
                    .build()
            )

            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
