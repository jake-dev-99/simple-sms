package io.simplezen.simple_sms.device

import android.app.role.RoleManager
import android.content.Context
import android.content.Context.ROLE_SERVICE
import android.content.pm.PackageManager
import io.simplezen.simple_sms.SimpleSmsPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

class PermissionsHandler(val context: Context) : MethodChannel.MethodCallHandler {

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "checkRole" -> {
                val role = call.arguments as? String ?: run {
                    result.error("INVALID_ARGUMENT", "Role string required", null)
                    return
                }
                result.success(checkRole(role))
            }
            "requestRole" -> {
                val role = call.arguments as? String ?: run {
                    result.error("INVALID_ARGUMENT", "Role string required", null)
                    return
                }
                val activity = SimpleSmsPlugin.activityBinding?.activity
                if (activity == null) {
                    result.error("NO_ACTIVITY", "Cannot request role without an attached Activity", null)
                    return
                }
                val roleManager = context.getSystemService(ROLE_SERVICE) as RoleManager
                if (!roleManager.isRoleAvailable(role)) {
                    result.error("ROLE_UNAVAILABLE", "Role '$role' is not available on this device", null)
                    return
                }
                if (roleManager.isRoleHeld(role)) {
                    result.success(true)
                    return
                }
                SimpleSmsPlugin.setPendingRoleResult(result)
                val intent = roleManager.createRequestRoleIntent(role)
                activity.startActivityForResult(intent, SimpleSmsPlugin.REQUEST_CODE_ROLE)
            }
            "checkPermissions" -> {
                @Suppress("UNCHECKED_CAST")
                val permissions = call.arguments as? List<String> ?: run {
                    result.error("INVALID_ARGUMENT", "Permission list required", null)
                    return
                }
                result.success(checkPermissions(permissions))
            }
            "requestPermission" -> {
                @Suppress("UNCHECKED_CAST")
                val permissions = call.arguments as? List<String> ?: run {
                    result.error("INVALID_ARGUMENT", "Permission list required", null)
                    return
                }
                val activity = SimpleSmsPlugin.activityBinding?.activity
                if (activity == null) {
                    result.error("NO_ACTIVITY", "Cannot request permissions without an attached Activity", null)
                    return
                }
                val ungranted = permissions.filter {
                    context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
                }.toTypedArray()
                if (ungranted.isEmpty()) {
                    val results = mutableMapOf<String, Boolean>()
                    for (p in permissions) results[p] = true
                    result.success(results)
                    return
                }
                SimpleSmsPlugin.setPendingPermissionsResult(result, permissions.toTypedArray())
                activity.requestPermissions(ungranted, SimpleSmsPlugin.REQUEST_CODE_PERMISSIONS)
            }
            else -> result.notImplemented()
        }
    }

    private fun checkRole(role: String): Boolean {
        val roleManager = context.getSystemService(ROLE_SERVICE) as RoleManager
        return roleManager.isRoleAvailable(role) && roleManager.isRoleHeld(role)
    }

    private fun checkPermissions(permissions: List<String>): Map<String, Boolean> {
        val results = mutableMapOf<String, Boolean>()
        for (permission in permissions) {
            results[permission] = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        }
        return results
    }
}
