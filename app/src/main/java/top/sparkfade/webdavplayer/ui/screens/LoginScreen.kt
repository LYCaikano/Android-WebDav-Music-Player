package top.sparkfade.webdavplayer.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import top.sparkfade.webdavplayer.data.model.WebDavAccount
import kotlin.math.roundToInt

@Composable
fun LoginScreen(
    accountToEdit: WebDavAccount?,
    onSaveAccount: (Long, String, String, String, String, Boolean, Int) -> Unit,
    onTestConnection: suspend (String, String, String, Boolean) -> Boolean
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    var name by remember { mutableStateOf(accountToEdit?.name ?: "") }
    var url by remember { mutableStateOf(accountToEdit?.url ?: "https://") }
    var username by remember { mutableStateOf(accountToEdit?.username ?: "") }
    var password by remember { mutableStateOf(accountToEdit?.password ?: "") }
    var skipSsl by remember { mutableStateOf(accountToEdit?.skipSsl ?: false) }
    var scanDepth by remember { mutableStateOf(accountToEdit?.scanDepth?.toFloat() ?: 4f) }
    var isTesting by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }

    val performTestConnection = {
        if (url.isNotBlank()) {
            focusManager.clearFocus()
            isTesting = true
            scope.launch {
                val success = onTestConnection(url, username, password, skipSsl)
                isTesting = false
                Toast.makeText(context, if (success) "Success!" else "Failed!", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, "URL cannot be empty", Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        val title = if (accountToEdit == null) "Add Account" else "Edit Account"
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(32.dp))

        // 1. Account Name
        TextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Account Name") },
            modifier = Modifier.fillMaxWidth(),
            // 禁止换行
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) })
        )
        Spacer(Modifier.height(8.dp))

        // 2. Server URL
        TextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Server URL") },
            modifier = Modifier.fillMaxWidth(),
            // 禁止换行，键盘类型为URI
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Next
            ),
            keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) })
        )
        Spacer(Modifier.height(8.dp))

        // 3. Username
        TextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Username") },
            modifier = Modifier.fillMaxWidth(),
            // 禁止换行
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) })
        )
        Spacer(Modifier.height(8.dp))

        // 4. Password
        TextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            // 禁止换行，类型为密码
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Go
            ),
            keyboardActions = KeyboardActions(onGo = {
                performTestConnection()
            })
        )

        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = skipSsl, onCheckedChange = { skipSsl = it })
            Text("Skip SSL Validation")
        }

        Spacer(Modifier.height(16.dp))

        Text("Scan Depth: ${scanDepth.roundToInt()}", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = scanDepth,
            onValueChange = { scanDepth = it },
            valueRange = 1f..10f,
            steps = 8
        )
        Text("Higher depth means slower scanning.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)

        Spacer(Modifier.height(24.dp))

        OutlinedButton(
            onClick = { performTestConnection() }, // 复用逻辑
            enabled = !isTesting && !isSaving,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isTesting) CircularProgressIndicator(modifier = Modifier.size(20.dp)) else Text("Test Connection")
        }

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = {
                if (url.isNotBlank() && username.isNotBlank()) {
                    isTesting = true
                    scope.launch {
                        val success = onTestConnection(url, username, password, skipSsl)
                        isTesting = false
                        if(success) {
                            isSaving = true
                            onSaveAccount(
                                accountToEdit?.id ?: 0L,
                                name, url, username, password, skipSsl,
                                scanDepth.roundToInt()
                            )
                        } else {
                            Toast.makeText(context,"Connection failed! Check settings.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            },
            enabled = !isSaving && !isTesting,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isSaving) CircularProgressIndicator(modifier = Modifier.size(24.dp)) else Text("Save & Sync")
        }
    }
}