# WA Contact Saver — Android App

Scans your WhatsApp Business app directly (including archived chats) and saves
all unsaved numbers to your Contacts with a fully customisable naming series.

---

## How it works

Uses Android's **Accessibility Service** (same technique as the InTouch app):
- Opens WhatsApp Business automatically
- Scrolls through ALL chats, including archived ones
- Detects phone numbers (unsaved) vs contact names (already saved)
- Saves new contacts directly to your Android Contacts with your custom prefix + number series

No root required. No database extraction. Everything happens on your phone.

---

## Build Instructions (one-time, ~5 minutes)

### Requirements
- A PC/Mac with **Android Studio** installed (free from developer.android.com)
- Your Android phone with **USB Debugging** enabled

### Steps
1. Open Android Studio → File → Open → select this `WAContactSaver` folder
2. Wait for Gradle sync to complete (downloads dependencies automatically)
3. Connect your phone via USB and click the ▶ Run button
4. The app installs on your phone

---

## First-time setup on your phone

1. Open **WA Contact Saver** app
2. Tap **"Enable Accessibility Service"** → find "WA Contact Saver" in the list → turn it ON
3. Grant **Contacts permission** when prompted
4. Set your naming preferences (prefix, start number, padding)
5. Tap **🚀 Start Scan**

The app will open WhatsApp Business, scroll through all chats automatically,
and save unsaved numbers directly to your Contacts.

---

## Naming Examples

| Prefix     | Start | Padding | Result                            |
|------------|-------|---------|-----------------------------------|
| WA Contact | 1     | 3       | WA Contact 001, WA Contact 002… |
| Lead       | 1     | 3       | Lead 001, Lead 002…              |
| Customer   | 50    | 4       | Customer 0050, Customer 0051…    |
| Mumbai     | 1     | 2       | Mumbai 01, Mumbai 02…            |

---

## Permissions used

| Permission       | Why                                          |
|------------------|----------------------------------------------|
| READ_CONTACTS    | Check which numbers are already saved        |
| WRITE_CONTACTS   | Save the new contacts                        |
| Accessibility    | Read WhatsApp Business screen content only   |

No internet permission. No data ever leaves your device.
