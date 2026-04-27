package com.wabiz.contactsaver

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Accessibility Service that scans the WhatsApp Business chat list.
 *
 * HOW IT WORKS
 * ────────────
 * 1. MainActivity tells this service to start via [startScan].
 * 2. The service opens WhatsApp Business.
 * 3. As the WA chat list scrolls into view, [onAccessibilityEvent] fires.
 * 4. Each chat-list row has a TextView containing either:
 *      • A contact NAME  → already saved   (skip)
 *      • A PHONE NUMBER  → unsaved         (save it)
 *    We distinguish them using a phone-number regex.
 * 5. After the main list reaches the end, the service taps the
 *    "Archived" banner to scan archived chats too.
 * 6. Results are broadcast to ResultActivity via a local Intent.
 */
class ScanAccessibilityService : AccessibilityService() {

    companion object {
        const val WA_BUSINESS_PKG = "com.whatsapp.w4b"

        // Broadcast actions
        const val ACTION_PROGRESS   = "com.wabiz.contactsaver.PROGRESS"
        const val ACTION_DONE       = "com.wabiz.contactsaver.DONE"
        const val EXTRA_MSG         = "msg"
        const val EXTRA_SAVED       = "saved"
        const val EXTRA_SKIPPED     = "skipped"
        const val EXTRA_TOTAL_FOUND = "total_found"

        // Phone number pattern — matches most international formats
        // e.g.  +91 98765 43210  or  9876543210  or  +1-800-555-0199
        private val PHONE_REGEX = Regex("""^[\+]?[\d\s\-\(\)]{7,20}$""")

        // Singleton reference so MainActivity can call startScan / stopScan
        var instance: ScanAccessibilityService? = null
            private set

        var isRunning = false
            private set
    }

    // ── State ────────────────────────────────────────────────────────────

    private val handler = Handler(Looper.getMainLooper())
    private var savedNumbers   = mutableSetOf<String>()  // existing device contacts
    private val foundNumbers   = mutableSetOf<String>()  // all WA numbers seen
    private val unsavedNumbers = mutableListOf<String>() // numbers we will save

    private var savedCount   = 0
    private var skippedCount = 0

    private var scanningArchived   = false
    private var archivedBannerTapped = false
    private var lastScrollCount    = -1
    private var sameScrollStreak   = 0
    private val MAX_SAME_STREAK    = 4  // stop scrolling after N identical states

    // ── Lifecycle ────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        serviceInfo = serviceInfo.also { info ->
            info.flags = info.flags or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
    }

    override fun onDestroy() {
        instance = null
        isRunning = false
        super.onDestroy()
    }

    override fun onInterrupt() {}

    // ── Public API (called from MainActivity) ────────────────────────────

    fun startScan(context: android.content.Context) {
        if (isRunning) return
        isRunning          = true
        scanningArchived   = false
        archivedBannerTapped = false
        lastScrollCount    = -1
        sameScrollStreak   = 0
        foundNumbers.clear()
        unsavedNumbers.clear()
        savedCount   = 0
        skippedCount = 0

        PrefsHelper.load(context)

        // Load existing device contacts for comparison
        savedNumbers = ContactManager.loadSavedNumbers(context).toMutableSet()
        broadcast(ACTION_PROGRESS, "Opening WhatsApp Business…")

        // Open WhatsApp Business on the chat list
        val launchIntent = context.packageManager
            .getLaunchIntentForPackage(WA_BUSINESS_PKG)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        if (launchIntent == null) {
            broadcast(ACTION_PROGRESS, "❌ WhatsApp Business is not installed.")
            isRunning = false
            return
        }
        context.startActivity(launchIntent)
    }

    fun stopScan() {
        isRunning = false
        broadcast(ACTION_PROGRESS, "Scan stopped by user.")
    }

    // ── Accessibility callbacks ──────────────────────────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!isRunning) return
        if (event.packageName != WA_BUSINESS_PKG) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                handler.removeCallbacksAndMessages(null)
                // Small delay to let the UI settle after a scroll
                handler.postDelayed({ processCurrentScreen() }, 350)
            }
        }
    }

    // ── Screen processing ────────────────────────────────────────────────

    private fun processCurrentScreen() {
        val root = rootInActiveWindow ?: return
        if (root.packageName != WA_BUSINESS_PKG) return

        // Try to find and handle the "Archived" banner first
        if (!scanningArchived && !archivedBannerTapped) {
            if (tapArchivedBannerIfVisible(root)) {
                archivedBannerTapped = true
                scanningArchived = true
                broadcast(ACTION_PROGRESS, "Now scanning archived chats…")
                return
            }
        }

        // Scan all visible chat row nodes
        val chatNodes = findChatNameNodes(root)
        var newFound = 0

        for (node in chatNodes) {
            val text = node.text?.toString()?.trim() ?: continue
            if (text.isEmpty() || text.length < 5) continue

            // Skip if we've seen this entry before
            if (foundNumbers.contains(text)) continue

            if (looksLikePhoneNumber(text)) {
                val digits = text.filter { it.isDigit() }
                if (digits.length >= 7) {
                    foundNumbers.add(text)
                    if (!ContactManager.isSaved(digits, savedNumbers)) {
                        unsavedNumbers.add(digits)
                        newFound++
                    } else {
                        skippedCount++
                    }
                }
            } else {
                // Looks like a name → saved contact
                foundNumbers.add(text)
                skippedCount++
            }
        }

        if (newFound > 0) {
            broadcast(ACTION_PROGRESS,
                "Found ${unsavedNumbers.size} unsaved so far… (${foundNumbers.size} chats scanned)")
        }

        // Try scrolling down to load more chats
        val scrolled = scrollChatList(root)

        if (!scrolled || sameScrollStreak >= MAX_SAME_STREAK) {
            if (!scanningArchived && !archivedBannerTapped) {
                // Try archived once before finishing
                if (!tapArchivedBannerIfVisible(root)) {
                    finishScan()
                }
            } else {
                finishScan()
            }
        }
    }

    // ── Node detection helpers ───────────────────────────────────────────

    /**
     * Walk the accessibility tree and collect nodes that are likely to be
     * the "name/number" text of a WhatsApp chat row.
     *
     * Strategy: find all leaf TextViews inside the scrollable chat list
     * that look like contact names or phone numbers.
     */
    private fun findChatNameNodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val results = mutableListOf<AccessibilityNodeInfo>()

        // Try by view-id first (most reliable when IDs are available)
        val byId = root.findAccessibilityNodeInfosByViewId("$WA_BUSINESS_PKG:id/conversation_contact_name")
        if (byId.isNotEmpty()) {
            results.addAll(byId)
            return results
        }

        // Fallback: walk the tree and collect likely name nodes
        collectChatNameNodes(root, results, depth = 0)
        return results
    }

    private fun collectChatNameNodes(
        node: AccessibilityNodeInfo,
        out: MutableList<AccessibilityNodeInfo>,
        depth: Int
    ) {
        if (depth > 12) return

        val text = node.text?.toString() ?: ""
        val isLeafWithText = node.childCount == 0 && text.isNotEmpty()

        if (isLeafWithText) {
            // Heuristic: chat row names are usually in the top portion of a list item
            // They are NOT timestamps (short, digit-colon format) or message previews (long sentences)
            if (!looksLikeTimestamp(text) && !looksLikeMessagePreview(text)) {
                out.add(node)
            }
        }

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectChatNameNodes(it, out, depth + 1) }
        }
    }

    private fun looksLikePhoneNumber(text: String): Boolean {
        val digits = text.filter { it.isDigit() }
        if (digits.length < 7 || digits.length > 15) return false
        val cleaned = text.trim()
        return PHONE_REGEX.matches(cleaned)
    }

    private fun looksLikeTimestamp(text: String): Boolean {
        // e.g.  "10:30", "Yesterday", "Mon", "Jan 5"
        if (text.length > 12) return false
        return text.contains(":") || text.all { it.isLetter() || it.isWhitespace() || it == '.' }
    }

    private fun looksLikeMessagePreview(text: String): Boolean {
        // Message previews are usually longer sentences with spaces
        return text.length > 40 && text.contains(" ")
    }

    // ── Scroll helpers ───────────────────────────────────────────────────

    private fun scrollChatList(root: AccessibilityNodeInfo): Boolean {
        val scrollable = findScrollable(root) ?: return false
        val count = scrollable.childCount

        return if (count == lastScrollCount) {
            sameScrollStreak++
            scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            false
        } else {
            lastScrollCount = count
            sameScrollStreak = 0
            scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        }
    }

    private fun findScrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findScrollable(child)
            if (found != null) return found
        }
        return null
    }

    // ── Archived chats ───────────────────────────────────────────────────

    /**
     * Look for an "Archived" text node (the banner at the top of the list)
     * and tap it.
     */
    private fun tapArchivedBannerIfVisible(root: AccessibilityNodeInfo): Boolean {
        // Try by view-id
        val byId = root.findAccessibilityNodeInfosByViewId("$WA_BUSINESS_PKG:id/archived_chats_label")
        if (byId.isNotEmpty()) {
            byId[0].performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return true
        }
        // Try by text
        val byText = root.findAccessibilityNodeInfosByText("Archived")
        if (byText.isNotEmpty()) {
            byText[0].performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return true
        }
        return false
    }

    // ── Finish & save ────────────────────────────────────────────────────

    private fun finishScan() {
        isRunning = false
        broadcast(ACTION_PROGRESS, "Scan complete. Saving ${unsavedNumbers.size} contacts…")

        var index = PrefsHelper.currentIndex(
            applicationContext.also { PrefsHelper.load(it) }
        )
        var successCount = 0

        for (number in unsavedNumbers) {
            val name     = PrefsHelper.buildName(index)
            val fullNum  = PrefsHelper.formatNumber(number)
            val ok       = ContactManager.saveContact(applicationContext, name, fullNum)
            if (ok) {
                successCount++
                index++
                broadcast(ACTION_PROGRESS, "Saved: $name  →  $fullNum")
            }
        }

        PrefsHelper.setCurrentIndex(applicationContext, index)

        // Final broadcast
        val intent = Intent(ACTION_DONE).apply {
            putExtra(EXTRA_SAVED,       successCount)
            putExtra(EXTRA_SKIPPED,     skippedCount)
            putExtra(EXTRA_TOTAL_FOUND, foundNumbers.size)
        }
        sendBroadcast(intent)
    }

    // ── Broadcast helper ─────────────────────────────────────────────────

    private fun broadcast(action: String, msg: String = "") {
        val intent = Intent(action).apply { putExtra(EXTRA_MSG, msg) }
        sendBroadcast(intent)
    }
}
