---
name: Use the correct provider URI for each mission; verify column linkage against real device data
description: Every query must target the right ContentProvider URI and join on the right column; validate both against real device-provider exports before coding.
type: feedback
---
When implementing any read/write path in simple-sms, pick the correct provider URI and join column for the mission, and confirm both against a real device-provider export before writing code. The user has flagged this explicitly: "be absolutely anal about making sure we're accurately linking all data together, and that we're using the correct provider for the respective mission."

**Why:** Past bugs in this codebase (AndroidSimpleConversation.threadId reading `_id` which is the latest-message-id on the simple conversations view; MmsPart `_data` vs `dataLocation` column mismatch; MmsPart `mid` not populating `parentId`) all stem from guessing at column semantics instead of verifying against real provider shape. Samsung Android 16 layers extra columns and sometimes changes types (e.g. `m_cls` as string not int), so AOSP docs alone are insufficient.

**How to apply:**
- Before writing a new query, answer: (1) what is the table's primary key column, (2) what column links it to the parent entity, (3) what does the URI variant do (e.g. `mms-sms/conversations?simple=true` has a completely different row shape than `mms-sms/conversations`), (4) are there Samsung OEM columns to preserve.
- Cross-check column names and types against a generated type-def for that table.
- Cross-check value shapes (int vs string, empty-string sentinels, date-in-ms vs date-in-sec) against a raw-data dump.
- Join keys to watch: `thread_id` (threads) vs `_id` (row PK), `mid` (mms_part/mms_addr → parent mms id) vs `_id`, `contact_id` (data row → contact), `msg_id` (mms_addr → mms `_id`).
- When uncertain, dump a row from the real export and walk the field mapping line by line rather than pattern-matching against a similar model.
