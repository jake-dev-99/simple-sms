---
name: All Android permissions route through simple-permissions
description: Hard rule — simple-sms (and related packages) must not request, check, or handle runtime permissions or the default-SMS-app role directly; everything goes through the simple_permissions_native package.
type: feedback
---
All Android runtime-permission checks, requests, and default-SMS-app role (`RoleManager.ROLE_SMS`) handling in `simple-sms` (Dart and Kotlin sides) must route through the `simple_permissions_native` package. Never call `ContextCompat.checkSelfPermission`, `ActivityCompat.requestPermissions`, `RoleManager.createRequestRoleIntent`, or any bespoke permission gate.

**Why:** The user enforces a layering contract across the four sister packages (simple-sms, simple-permissions, simple-query, simple-telephony). simple_permissions_native owns the vocabulary (ReadSms, ReceiveSms, SendSms, DefaultSmsApp, ReadContacts, etc.), the request flow, the observable state stream, and the role lifecycle. Duplicating any of that in simple-sms splinters the contract and creates divergent truth for consumers. The user has flagged this explicitly: "don't go rogue with this." Parallel rule to the simple_query exclusivity rule.

**How to apply:**
- In Dart: consumers check access via `SimplePermissionsNative.instance.request(const DefaultSmsApp())` / `.requestAll([...])` / `.observe([...])`. simple-sms's public API documents what permission each method needs and surfaces `SecurityException` / empty results when missing — but simple-sms itself does NOT check. That is the CALLER'S job, via simple_permissions_native.
- In Kotlin: use `io.simplezen.simple_permissions_android.PermissionGuards` for any internal gate (see `Query.kt`'s `getDeviceInfo` for the existing pattern — `PermissionGuards.areAllPermissionsGranted` / `PermissionGuards.isPermissionGranted`). Never call `checkSelfPermission` / `requestPermissions` directly.
- Activity-result + request-permissions-result listeners on `SimpleSmsPlugin` should decline (return `false`) so simple_permissions_native handles them. The current code already does this at `SimpleSmsPlugin.kt:124-134`; keep it that way.
- When adding a new method, document the required permission vocabulary in the method's dartdoc using the simple_permissions_native class name (`ReadSms`, `SendSms`, etc.) and link to the plugin.
- Dead code check: audit remaining Kotlin for any direct `Manifest.permission.*` request flows and route them through PermissionGuards instead.
