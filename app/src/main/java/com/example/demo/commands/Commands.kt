package com.example.demo.commands

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.compose.ui.text.capitalize
import androidx.compose.ui.text.intl.Locale
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import com.example.demo.MANAGE_FILES_SETTINGS
import com.example.demo.managers.FlashManager.Result
import com.example.demo.data.notes.Note
import com.example.demo.models.LauncherApp
import com.example.demo.models.settingsIntent
import com.example.demo.models.uninstallIntent
import com.example.demo.shell.Action
import com.example.demo.shell.ShellContext
import com.example.demo.suggestions.Suggestion
import com.example.demo.suggestions.Suggestions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileReader
import java.io.IOException

private val NOTE_COMMAND_GROUP = object : Command.Group("notes") {

    private val ADD_OPTION = Leaf(
        Metadata.Builder("add")
            .addRequiredArg("note", Suggestions.Empty)
            .build()
    ) {
        repository.addNote(Note(content = it[0].value))
    }

    private val LIST_OPTION = Leaf(Metadata.Builder("list").build()) {
        repository.notesList().forEachIndexed { index, note ->
            sendAction(Action.Message("$index: ${note.content}"))
        }
    }

    private val REMOVE_OPTION = Leaf(
        Metadata.Builder("rm")
            .addRequiredArg("index", Suggestions.Empty)
            .build()
    ) { arguments ->
        arguments[0].value.toLongOrNull()?.takeIf { repository.removeNote(it) > 0 } ?: run {
            sendAction(Action.Message("Invalid index"))
        }
    }

    private val CLEAR_OPTION = Leaf(Metadata.Builder("clear").build()) {
        repository.clearNotes()
    }

    private val COPY_OPTION = Leaf(
        Metadata.Builder("copy")
            .addRequiredArg("index", Suggestions.Empty)
            .build()
    ) {

        val index = it[0].value.toLongOrNull() ?: run {
            sendAction(Action.Message("Invalid index"))
            return@Leaf
        }

        val note = repository.getNote(index) ?: run {
            sendAction(Action.Message("Invalid index"))
            return@Leaf
        }

        val manager = appContext.getSystemService<ClipboardManager>() ?: run {
            sendAction(Action.Message("Clipboard isn't supported on your device"))
            return@Leaf
        }

        manager.setPrimaryClip(ClipData.newPlainText("note", note.content))
    }

    override val commands: CommandList = CommandList.Builder()
        .putCommand(ADD_OPTION)
        .putCommand(LIST_OPTION)
        .putCommand(REMOVE_OPTION)
        .putCommand(CLEAR_OPTION)
        .putCommand(COPY_OPTION)
        .build()
}

private val APP_COMMAND_GROUP = object : Command.Group("apps") {

    private val LIST_OPTION = Leaf(Metadata.Builder("ls").build()) {
        repository.loadLauncherApps().forEach {
            sendAction(Action.Message(it.name) {
                appContext.startActivity(it.launchIntent)
            })
        }
    }

    private val REMOVE_OPTION = Leaf(
        Metadata.Builder("rm")
            .addRequiredArg("name", Suggestions.Applications)
            .build()
    ) { arguments ->
        val app = lookupApplication(arguments[0].value) ?: return@Leaf
        sendAction(Action.StartIntent(app.uninstallIntent))
    }

    private val OPEN_OPTION = Leaf(
        Metadata.Builder("open").addRequiredArg("name", Suggestions.Applications).build()
    ) { arguments ->
        val app = lookupApplication(arguments[0].value) ?: return@Leaf
        sendAction(Action.StartIntent(app.launchIntent))
    }

    private val SETTINGS_OPTION = Leaf(
        Metadata.Builder("st").addRequiredArg("name", Suggestions.Applications).build()
    ) { arguments ->
        val app = lookupApplication(arguments[0].value) ?: return@Leaf
        sendAction(Action.StartIntent(app.settingsIntent))
    }

    override val commands: CommandList = CommandList.Builder()
        .putCommand(SETTINGS_OPTION)
        .putCommand(REMOVE_OPTION)
        .putCommand(LIST_OPTION)
        .putCommand(OPEN_OPTION)
        .build()

    private suspend fun ShellContext.lookupApplication(appName: String): LauncherApp? {

        val apps = repository
            .loadLauncherApps { it.equals(appName, true) }
            .takeIf { it.isNotEmpty() }

        if (apps == null) {
            sendAction(Action.Message("'$appName' not found"))
            return null
        }

        if (apps.size == 1) {
            return apps.first()
        }

        sendAction(Action.Message("There are more than one app with this name. Which one did you meant?"))

        apps.forEachIndexed { index, appModel ->
            sendAction(Action.Message("$index: ${appModel.packageName}"))
        }

        val index = sendAction(Action.Prompt("Enter a valid index"))
            .toIntOrNull()?.takeIf { it in apps.indices }

        if (index == null) {
            sendAction(Action.Message("Invalid index"))
            return null
        }

        return apps[index]
    }
}

private val CONTACTS_COMMAND_GROUP = object : Command.Group("contacts") {

    private val LS_OPTION = Leaf(Metadata.Builder("ls").build()) {

        var permissions = arrayOf(Manifest.permission.READ_CONTACTS)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            permissions += Manifest.permission.READ_PHONE_NUMBERS
        }

        if (permissions.any { appContext.hasNotPermission(it) }) {
            if (!sendAction(Action.RequestPermissions(permissions))) {
                sendAction(Action.Message("Can't read contacts"))
                return@Leaf
            }
        }

        repository.loadContacts().forEach { contact ->

            sendAction(Action.Message("${contact.name}: ${contact.phone}") {

                if (appContext.hasNotPermission(Manifest.permission.CALL_PHONE)) {
                    if (!sendAction(Action.RequestPermissions(arrayOf(Manifest.permission.CALL_PHONE)))) {
                        sendAction(Action.Message("Can't perform phone calls"))
                        return@Message
                    }
                }

                sendAction(Action.StartIntent(Intent(Intent.ACTION_CALL, "tel:${contact.phone}".toUri())))
            })
        }
    }

    override val commands: CommandList = CommandList.Builder()
            .putCommand(LS_OPTION)
            .build()
}

private val LS_COMMAND = Command.Leaf(Metadata.Builder("ls").build()) {

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        if (!Environment.isExternalStorageManager()) {
            sendAction(Action.StartIntentForResult(MANAGE_FILES_SETTINGS))
            if (!Environment.isExternalStorageManager()) {
                sendAction(Action.Message("Can't read from the external storage"))
                return@Leaf
            }
        }
    } else {
        if (!sendAction(Action.RequestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)))) {
            sendAction(Action.Message("Can't read from the external storage"))
            return@Leaf
        }
    }

    repository.loadFiles(workingDir).forEach {
        sendAction(Action.Message(it.name))
    }
}

private val MAKE_DIR_COMMAND = Command.Leaf(
    Metadata.Builder("mkdir").addRequiredArg("name", Suggestions.Empty).build()
) { arguments ->

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        if (!Environment.isExternalStorageManager()) {
            sendAction(Action.StartIntentForResult(MANAGE_FILES_SETTINGS))
            if (!Environment.isExternalStorageManager()) {
                sendAction(Action.Message("Can't write to the external storage"))
                return@Leaf
            }
        }
    } else {
        if (!sendAction(Action.RequestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE)))) {
            sendAction(Action.Message("Can't write to the external storage"))
            return@Leaf
        }
    }

    val fileName = arguments[0].value
    val file = normalizePath(fileName)

    when {
        file.exists() -> sendAction(Action.Message("$fileName already exists"))
        !file.mkdirs() -> sendAction(Action.Message("failed to create folder"))
    }
}

private val ECHO_COMMAND = Command.Leaf(
    Metadata.Builder("echo")
        .addRequiredNArgs("args", Suggestions.Empty)
        .build()
) { arguments ->
    sendAction(Action.Message(arguments.joinToString(" ") { it.value }))
}

private val CLEAR_COMMAND = Command.Leaf(Metadata.Builder("clear").build()) {
    sendAction(Action.Clear)
}

private val PWD_COMMAND = Command.Leaf(Metadata.Builder("pwd").build()) {
    sendAction(Action.Message(workingDir.canonicalPath))
}

private val CD_COMMAND = Command.Leaf(
    Metadata.Builder("cd").addRequiredArg("directory", Suggestions.Directories).build()
) { arguments ->

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        if (!Environment.isExternalStorageManager()) {
            sendAction(Action.StartIntentForResult(MANAGE_FILES_SETTINGS))
            if (!Environment.isExternalStorageManager()) {
                sendAction(Action.Message("Can't read from the external storage"))
                return@Leaf
            }
        }
    } else {
        if (!sendAction(Action.RequestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)))) {
            sendAction(Action.Message("Can't read from the external storage"))
            return@Leaf
        }
    }

    val file = normalizePath(arguments[0].value)

    if (file.exists()) {
        workingDir = file
    } else {
        sendAction(Action.Message("${arguments[0]}: not found"))
    }
}

private val EXIT_COMMAND = Command.Leaf(Metadata.Builder("exit").build()) {
    sendAction(Action.Exit)
}

private val FLASH_COMMAND = Command.Leaf(
    Metadata.Builder("flash")
        .addRequiredArg("facing", Suggestions.Custom(listOf(Suggestion("front"), Suggestion("back"))))
        .addRequiredArg("state", Suggestions.Custom(listOf(Suggestion("on"), Suggestion("off"))))
        .build()
) { arguments ->

    val facing = arguments[0].value.takeIf { it == "front" || it == "back" } ?: run {
        sendAction(Action.Message("facing mode could be either 'back' or 'front'"))
        return@Leaf
    }

    val state = arguments[1].value.takeIf { it == "on" || it == "off" } ?: run {
        sendAction(Action.Message("state mode could be either 'on' or 'off'"))
        return@Leaf
    }

    if (flashManager.NeedsPermission && appContext.hasNotPermission(Manifest.permission.CAMERA)) {
        if (!sendAction(Action.RequestPermissions(arrayOf(Manifest.permission.CAMERA)))) {
            sendAction(Action.Message("Can't have access to the camera"))
            return@Leaf
        }
    }

    val facingMode = if (facing == "front") flashManager.FacingFront else flashManager.FacingBack

    val message = when (flashManager.setTorchMode(facingMode, state == "on")) {
        Result.CameraNotFound -> "The $facing camera isn't available"
        Result.FlashNotFound -> "${facing.capitalize(Locale.current)} camera doesn't have a flash unit"
        Result.Success -> "Flash is $state"
    }

    sendAction(Action.Message(message))
}

private val TOUCH_COMMAND = Command.Leaf(
    Metadata.Builder("touch").addRequiredNArgs("files", Suggestions.Empty).build()
) { arguments ->

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        if (!Environment.isExternalStorageManager()) {
            sendAction(Action.StartIntentForResult(MANAGE_FILES_SETTINGS))
            if (!Environment.isExternalStorageManager()) {
                sendAction(Action.Message("Can't write to the external storage"))
                return@Leaf
            }
        }
    } else {
        if (!sendAction(Action.RequestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE)))) {
            sendAction(Action.Message("Can't write to the external storage"))
            return@Leaf
        }
    }

    arguments.forEach { argument ->

        val file = normalizePath(argument.value).takeIf { file -> !file.exists() } ?: run {
            sendAction(Action.Message("'${argument.value}' already exists"))
            return@forEach
        }

        try {
            if (!file.createNewFile()) {
                sendAction(Action.Message("Cannot create '${argument.value}'"))
            }
        } catch (e: IOException) {
            sendAction(Action.Message("${e.message}"))
        }
    }
}

@Suppress("DEPRECATION")
@SuppressLint("MissingPermission")
private val BT_COMMAND = Command.Leaf(Metadata.Builder("bt").build()) {

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        if (appContext.hasNotPermission(Manifest.permission.BLUETOOTH_ADMIN)
            || appContext.hasNotPermission(Manifest.permission.BLUETOOTH_CONNECT)
            || appContext.hasNotPermission(Manifest.permission.BLUETOOTH)
        ) {
            if (!sendAction(
                    Action.RequestPermissions(
                        arrayOf(
                            Manifest.permission.BLUETOOTH_CONNECT,
                            Manifest.permission.BLUETOOTH_ADMIN,
                            Manifest.permission.BLUETOOTH
                        )
                    )
                )
            ) {
                sendAction(Action.Message("Bluetooth permission denied"))
                return@Leaf
            }
        }
    } else {
        if (appContext.hasNotPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            if (!sendAction(Action.RequestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT)))) {
                sendAction(Action.Message("Bluetooth permission denied"))
                return@Leaf
            }
        }
    }

    val adapter = appContext.getSystemService<BluetoothManager>()?.adapter ?: run {
        sendAction(Action.Message("Bluetooth isn't supported on this device"))
        return@Leaf
    }

    val shouldEnable = !adapter.isEnabled

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        if (shouldEnable) adapter.enable() else adapter.disable()
    } else if (shouldEnable) {
        val result =
            sendAction(Action.StartIntentForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)))
        if (result.resultCode != Activity.RESULT_OK) {
            sendAction(Action.Message("Bluetooth wasn't enabled"))
            return@Leaf
        }
    } else {
        sendAction(Action.Message("Bluetooth cannot be disabled. This action must be done manually"))
        return@Leaf
    }

    sendAction(Action.Message("Bluetooth is " + if (shouldEnable) "enabled" else "disabled"))
}

private val READ_COMMAND = Command.Leaf(
    Metadata.Builder("read").addRequiredArg("file", Suggestions.Files).build()
) { arguments ->

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        if (!Environment.isExternalStorageManager()) {
            sendAction(Action.StartIntentForResult(MANAGE_FILES_SETTINGS))
            if (!Environment.isExternalStorageManager()) {
                sendAction(Action.Message("Can't read from the external storage"))
                return@Leaf
            }
        }
    } else {
        if (!sendAction(Action.RequestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)))) {
            sendAction(Action.Message("Can't read from the external storage"))
            return@Leaf
        }
    }

    val fileName = arguments[0].value

    val file = normalizePath(fileName).takeIf { it.exists() } ?: run {
        sendAction(Action.Message("$fileName is not found"))
        return@Leaf
    }

    withContext(Dispatchers.IO) {
        FileReader(file).buffered().use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                sendAction(Action.Message(line))
            }
        }
    }
}

private val RM_COMMAND = Command.Leaf(
    Metadata.Builder("rm").addRequiredNArgs("files", Suggestions.Files).build()
) { arguments ->

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        if (!Environment.isExternalStorageManager()) {
            sendAction(Action.StartIntentForResult(MANAGE_FILES_SETTINGS))
            if (!Environment.isExternalStorageManager()) {
                sendAction(Action.Message("Can't write to the external storage"))
                return@Leaf
            }
        }
    } else {
        if (!sendAction(Action.RequestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE)))) {
            sendAction(Action.Message("Can't write to the external storage"))
            return@Leaf
        }
    }

    arguments.forEach {
        if (!normalizePath(it.value).deleteRecursively()) {
            sendAction(Action.Message("Can't delete ${it.value}"))
        }
    }
}

private fun Context.hasNotPermission(permission: String): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        checkSelfPermission(permission) == PackageManager.PERMISSION_DENIED
    } else {
        false
    }
}

internal val Commands = CommandList.Builder()
    .putCommand(CONTACTS_COMMAND_GROUP)
    .putCommand(NOTE_COMMAND_GROUP)
    .putCommand(APP_COMMAND_GROUP)
    .putCommand(MAKE_DIR_COMMAND)
    .putCommand(CLEAR_COMMAND)
    .putCommand(FLASH_COMMAND)
    .putCommand(TOUCH_COMMAND)
    .putCommand(ECHO_COMMAND)
    .putCommand(EXIT_COMMAND)
    .putCommand(READ_COMMAND)
    .putCommand(PWD_COMMAND)
    .putCommand(LS_COMMAND)
    .putCommand(RM_COMMAND)
    .putCommand(CD_COMMAND)
    .putCommand(BT_COMMAND)
    .build()
