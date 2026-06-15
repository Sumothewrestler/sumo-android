package `in`.rfidpro.sumo

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `in`.rfidpro.sumo.api.LoginReq
import `in`.rfidpro.sumo.api.PentadClient
import `in`.rfidpro.sumo.ui.SumoSlate900
import `in`.rfidpro.sumo.ui.SumoTeal600
import `in`.rfidpro.sumo.ui.SumoTheme
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Single-purpose login. Username + password → POST /api/auth/login/ →
 * store the access token → start MainActivity (auto-listen on).
 *
 * Auto-routes to MainActivity if already logged in (handles the case
 * where MainActivity bounces back here because of a lost session, then
 * the refresh succeeds before this activity gets to render).
 */
class LoginActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Already authenticated? Skip the screen.
        if (PentadClient.tokens.isLoggedIn) {
            goToMain()
            return
        }
        setContent {
            SumoTheme {
                LoginScreen(
                    onSuccess = {
                        goToMain()
                        finish()
                    }
                )
            }
        }
    }

    private fun goToMain() {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                putExtra("auto_listen", true)
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
        )
        finish()
    }
}


@Composable
private fun LoginScreen(onSuccess: () -> Unit) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun submit() {
        if (busy) return
        if (username.isBlank() || password.isBlank()) {
            error = "Enter your username and password."
            return
        }
        busy = true
        error = null
        scope.launch {
            try {
                val resp = PentadClient.api.login(LoginReq(username.trim(), password))
                if (resp.isSuccessful) {
                    val body = resp.body()
                    if (body?.accessToken.isNullOrBlank()) {
                        error = "Server returned no access token."
                    } else {
                        PentadClient.tokens.accessToken = body!!.accessToken
                        PentadClient.tokens.username = body.user?.username ?: username.trim()
                        onSuccess()
                    }
                } else {
                    error = if (resp.code() == 401 || resp.code() == 400)
                        "Couldn't sign in. Check your credentials and try again."
                    else
                        "Server error ${resp.code()}. Try again."
                }
            } catch (_: IOException) {
                error = "Couldn't reach Sumo. Check your network."
            } catch (t: Throwable) {
                error = "Unexpected: ${t.message}"
            } finally {
                busy = false
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = SumoSlate900,
    ) {
        Box(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 360.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Sumo",
                    style = MaterialTheme.typography.headlineLarge.copy(
                        fontSize = 36.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Voice assistant for your Pentad tasks.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Next,
                    ),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error != null) {
                    Text(
                        text = error!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = ::submit,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SumoTeal600,
                        contentColor = Color.White,
                    ),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            color = Color.White,
                            strokeWidth = 2.dp,
                            modifier = Modifier.height(18.dp).padding(end = 8.dp),
                        )
                        Text("Signing in…", fontWeight = FontWeight.SemiBold)
                    } else {
                        Text("Sign in", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
