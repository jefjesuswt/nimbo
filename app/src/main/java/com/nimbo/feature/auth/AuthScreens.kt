package com.nimbo.feature.auth

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nimbo.R
import com.nimbo.ui.common.RadioDialog

// Login TDLib: keys → teléfono → código → 2FA. El paso lo decide AuthViewModel.
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AuthFlow(vm: AuthViewModel) {
    val step by vm.step.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    when (step) {
        AuthStep.Loading -> LinearWavyProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
        AuthStep.Keys -> KeysView(onSave = vm::saveKeys)
        AuthStep.Phone -> LoginView(
            phone = vm.phone,
            country = vm.country,
            onPhone = { vm.phone = it },
            onCountry = { vm.country = it },
            onContinue = vm::sendPhone
        )
        AuthStep.Code -> CodeView(
            phone = vm.sentTo.ifEmpty { vm.phone },
            error = vm.codeError,
            verifying = false,
            onVerify = vm::sendCode,
            onWrongNumber = vm::wrongNumber
        )
        AuthStep.Password -> PasswordView(error = error, onConfirm = vm::sendPassword)
        AuthStep.Ready -> {}
    }
}

// Keys una sola vez: my.telegram.org → API development tools.
// Solo viven cifradas en este celu (SecretStore), nunca en repo.
@Composable
private fun KeysView(onSave: (String, String) -> Boolean) {
    val cs = MaterialTheme.colorScheme
    var apiId by remember { mutableStateOf("") }
    var apiHash by remember { mutableStateOf("") }
    var err by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.api_keys_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(R.string.api_keys_body),
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 8.dp)
        )
        OutlinedTextField(
            value = apiId,
            onValueChange = { apiId = it.filter { c -> c.isDigit() } },
            label = { Text("API ID") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            isError = err,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = apiHash,
            onValueChange = { apiHash = it.trim() },
            label = { Text("API hash") },
            singleLine = true,
            isError = err,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )
        if (err) Text(
            stringResource(R.string.check_both),
            color = cs.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp)
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { err = !onSave(apiId, apiHash) },
            modifier = Modifier.fillMaxWidth()
        ) { Text(stringResource(R.string.save_connect)) }
    }
}

@Composable
private fun LoginView(
    phone: String,
    country: TgCountry,
    onPhone: (String) -> Unit,
    onCountry: (TgCountry) -> Unit,
    onContinue: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    var countryOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.connect_tg), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(R.string.login_body),
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 8.dp)
        )
        Spacer(Modifier.height(8.dp))
        // Tap abre picker países; readOnly+disabled evita teclado.
        Box(
            Modifier.fillMaxWidth().clickable { countryOpen = true }
        ) {
            OutlinedTextField(
                value = "${country.name} (+${country.dial})",
                onValueChange = {},
                enabled = false,
                label = { Text(stringResource(R.string.country)) },
                trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null) },
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = phone,
            onValueChange = { onPhone(it.filter { c -> c.isDigit() || c == '+' || c == ' ' }) },
            label = { Text(stringResource(R.string.phone_number)) },
            prefix = { Text("+${country.dial} ") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            singleLine = true,
            supportingText = { Text(stringResource(R.string.phone_hint)) },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onContinue,
            enabled = isPhoneOk(phone),
            modifier = Modifier.fillMaxWidth()
        ) { Text(stringResource(R.string.continue_)) }
    }
    if (countryOpen) {
        RadioDialog(
            title = stringResource(R.string.country),
            options = TgCountries.sortedBy { it.name }.map { it.iso to "${it.name} (+${it.dial})" },
            initial = country.iso,
            onDismiss = { countryOpen = false },
            onConfirm = { iso ->
                countryOpen = false
                TgCountries.firstOrNull { it.iso == iso }?.let(onCountry)
            }
        )
    }
}

@Composable
private fun CodeView(
    phone: String,
    error: Boolean,
    verifying: Boolean,
    onVerify: (String) -> Unit,
    onWrongNumber: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val digits = remember { List(5) { mutableStateOf("") } }
    val focus = remember { List(5) { FocusRequester() } }
    val fm = LocalFocusManager.current
    val code = digits.joinToString("") { it.value }
    LaunchedEffect(code) {
        if (code.length == 5 && !verifying) {
            fm.clearFocus()
            onVerify(code)
        }
    }
    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.enter_code), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(R.string.code_sent, phone),
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 8.dp)
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            digits.forEachIndexed { i, st ->
                OutlinedTextField(
                    value = st.value,
                    onValueChange = { v ->
                        val d = v.filter { it.isDigit() }.takeLast(1)
                        st.value = d
                        if (d.isNotEmpty() && i < 4) focus[i + 1].requestFocus()
                        if (d.isEmpty() && i > 0) focus[i - 1].requestFocus()
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = error,
                    modifier = Modifier.width(56.dp).focusRequester(focus[i]),
                    textStyle = MaterialTheme.typography.titleLarge.copy(textAlign = TextAlign.Center)
                )
            }
        }
        if (error) Text(
            stringResource(R.string.code_wrong),
            color = cs.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp)
        )
        if (verifying) Text(stringResource(R.string.verifying), color = cs.primary, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(8.dp))
        Row {
            TextButton(onClick = {}) { Text(stringResource(R.string.resend_code)) }
            TextButton(onClick = onWrongNumber) { Text(stringResource(R.string.wrong_number)) }
        }
    }
}

@Composable
private fun PasswordView(error: String?, onConfirm: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var pw by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.two_step), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(R.string.two_step_body),
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 8.dp)
        )
        OutlinedTextField(
            value = pw,
            onValueChange = { pw = it },
            label = { Text(stringResource(R.string.password)) },
            singleLine = true,
            isError = error != null,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )
        if (error != null) Text(
            error,
            color = cs.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp)
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { onConfirm(pw) },
            enabled = pw.isNotEmpty(),
            modifier = Modifier.fillMaxWidth()
        ) { Text(stringResource(R.string.unlock)) }
    }
}
