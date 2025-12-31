package io.simplezen.simple_sms.device

import android.app.role.RoleManager
import android.content.Context
import android.content.Context.ROLE_SERVICE
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import io.flutter.embedding.android.FlutterFragmentActivity
import io.simplezen.simple_sms.SimpleSmsPlugin
import io.flutter.plugin.common.JSONMethodCodec
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall

class PermissionsHandler(val context: Context) : FlutterFragmentActivity(), MethodChannel.MethodCallHandler {

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "sendMessage" -> {
                result.notImplemented()
            }
            else ->  result.notImplemented()
        }
    }

    private var roleLauncher: ActivityResultLauncher<Intent>? = null
    private var permissionsLauncher: ActivityResultLauncher<Array<String>>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prepareIntentLauncher()
    }

    private fun prepareIntentLauncher() {
        roleLauncher =
                registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                    if (it.resultCode == RESULT_OK) {

                        Toast.makeText(context, "Success requesting ROLE_SMS!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Failed requesting ROLE_SMS", Toast.LENGTH_SHORT).show()
                    }
                }
    }

    fun checkRole(role: String): Boolean {
        val roleManager = context.getSystemService(ROLE_SERVICE) as RoleManager
        return roleManager.isRoleAvailable(role) && roleManager.isRoleHeld(role)
    }

    // Checks role, then allows requesting it if not granted
    fun requestRole(role: String): Boolean {
        SimpleSmsPlugin.Companion.requestRole(role)
        return false
        // val roleManager = context.getSystemService(Context.ROLE_SERVICE) as RoleManager

        // // Check if Role is available.
        // val isRoleAvailable = roleManager.isRoleAvailable(role)
        // if (isRoleAvailable) {
        //     Log.d("requestRole", " <<< Invalid Role:: $role")
        //     throw Exception("Invalid Role - $role")
        // }
        // val fragment = this.

        // // Check if Role already held.
        // var isRoleHeld = roleManager.isRoleHeld(role)
        // if (isRoleHeld) {
        //     Log.d("requestRole", " <<< Role is already held")
        //     return true
        // }

        // val roleRequestIntent: Intent = roleManager.createRequestRoleIntent(role)
        // val result: ActivityResult? =
        //         ActivityResultContracts.StartActivityForResult()
        //                 .getSynchronousResult(context, roleRequestIntent)
        //                 ?.value

        // Log.d("requestRole", " <<< Role $role Requested")
        // isRoleHeld = roleManager.isRoleHeld(role)
        // Log.d("requestRole", " <<< Role $role Held? $isRoleHeld")
        // // Log.d("requestRole", " <<< getData ${result?.getData()}")
        // // Log.d("requestRole", " <<< getResultCode ${result?.getResultCode()}")
        // Log.d("requestRole", " <<< resultCode ${result?.resultCode.toString()}")

        // // Request Role.
        // val engine = provideFlutterEngine(this)
        // Log.d("requestRole", " <<< Engine: $engine")
        // var requestCompleted = false
        // val contract: ActivityResultContracts.StartActivityForResult =
        //         ActivityResultContracts.StartActivityForResult()

        // val requestRole =
        //         registerForActivityResult(contract) { asdf ->
        //             Log.d("requestRole", " <<< result $asdf")
        //             Log.d("requestRole", " <<< Role $role Requested")
        //             isRoleHeld = roleManager.isRoleHeld(role)
        //             Log.d("requestRole", " <<< Role $role Held: $isRoleHeld")
        //             requestCompleted = true
        //         }
        // Log.d("requestRole", " <<< Requesting Role: $role")
        // requestRole.launch(roleRequestIntent)
        // Log.d("requestRole", " <<< requestCompleted : $requestCompleted")
        // return roleManager.isRoleHeld(role)
    }

    fun checkPermissions(permissions: List<String>): Map<String, Boolean> {
        val permissionResults = mutableMapOf<String, Boolean>()
        for (permission in permissions) {
            val permissionGranted = context.checkSelfPermission(permission)
            permissionResults[permission] = permissionGranted == 0
        }
        return permissionResults
    }

    // Checks permissions, then allows requesting them if not granted
    fun requestPermissions(permissions: List<String>): Map<String, Boolean> {

        // // var grantedCheck: Boolean = false
        // val permissionsArr = arrayOf("android.permission.READ_PHONE_STATE")

        // Log.d("requestPermission", " <<< permissionsLauncher: ${permissionsLauncher}")

        // if (permissionsArr.isEmpty()) {
        //     Log.d("requestPermission", " <<< No permissions requested")
        //     return mapOf()
        // }

        // prepareIntentLauncher()
        // permissionsLauncher!!.launch(permissionsArr)
        // Log.d("requestPermission", " <<< Result:: $permissionsArr")
        val permissionsResults = SimpleSmsPlugin.Companion.requestPermissions(permissions.toTypedArray())
        return permissionsResults
    }
}
