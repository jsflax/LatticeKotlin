package com.example.notes

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lattice.Lattice
import com.lattice.LatticeConfiguration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

private val json = Json { ignoreUnknownKeys = true }

// Theme colors matching LatticePython and LatticeJS
object LatticeColors {
    val BgPrimary = Color(0xFF1a1a2e)
    val BgSecondary = Color(0xFF16213e)
    val BgCard = Color(0xFF2a2a4e)
    val BgInput = Color(0xFF0f0f1a)
    val Accent = Color(0xFF00d9ff)
    val AccentDim = Color(0xFF008899)
    val Text = Color(0xFFeeeeee)
    val TextMuted = Color(0xFF888888)
    val Error = Color(0xFFff4444)
    val Success = Color(0xFF44ff88)
}

@Serializable
data class AuthResponse(val token: String? = null, val error: String? = null, val userId: Int? = null)

@Serializable
data class AuthRequest(val email: String, val password: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NotesApp(context = this)
        }
    }
}

@Composable
fun NotesApp(context: Context) {
    var lattice by remember { mutableStateOf<Lattice?>(null) }
    var noteText by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf(listOf<Note>()) }

    // Sync state
    var syncExpanded by remember { mutableStateOf(false) }
    var serverUrl by remember { mutableStateOf("http://10.0.2.2:5050") } // Android emulator localhost
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var authToken by remember { mutableStateOf<String?>(null) }
    var syncStatus by remember { mutableStateOf("Not connected") }
    var isConnected by remember { mutableStateOf(false) }
    var authError by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val dbPath = context.getDatabasePath("notes2.sqlite").absolutePath

    // Initialize Lattice (local only initially)
    LaunchedEffect(Unit) {
        if (lattice == null) {
            lattice = Lattice(dbPath, Note::class)
        }
    }

    fun refreshNotes() {
        lattice?.let { db ->
            notes = db.objects(Note::class)
                .orderBy("createdAt", com.lattice.SortOrder.DESCENDING)
                .toList()
        }
    }

    // Load notes and set up observer when lattice changes
    LaunchedEffect(lattice) {
        refreshNotes()

        // Observe table changes to refresh notes when sync brings in new data
        lattice?.let { db ->
            db.objects(Note::class).observe { _ ->
                refreshNotes()
            }
        }
    }

    fun reconnectWithSync(token: String) {
        lattice?.close()
        val wsUrl = serverUrl
            .replace("https://", "wss://")
            .replace("http://", "ws://") + "/sync"
        val config = LatticeConfiguration(
            path = dbPath,
            syncEndpoint = wsUrl,
            authorizationToken = token
        )
        lattice = Lattice(config, Note::class)
        authToken = token
        isConnected = true
        syncStatus = "Connected"
        refreshNotes()
    }

    fun addNote() {
        if (noteText.isBlank()) return
        lattice?.let { db ->
            val note = Note().apply {
                text = noteText
                createdAt = Clock.System.now()
            }
            db.add(note)
            noteText = ""
            refreshNotes()
        }
    }

    fun deleteNote(note: Note) {
        lattice?.remove(note)
        refreshNotes()
    }

    suspend fun authenticate(isLogin: Boolean): Result<String> = withContext(Dispatchers.IO) {
        try {
            val endpoint = if (isLogin) "/login" else "/register"
            val url = URL("$serverUrl$endpoint")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true

            val body = json.encodeToString(AuthRequest.serializer(), AuthRequest(email, password))
            conn.outputStream.bufferedWriter().use { it.write(body) }

            val response = if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().readText()
            } else {
                conn.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
            }

            val authResponse = try {
                json.decodeFromString(AuthResponse.serializer(), response)
            } catch (e: Exception) {
                AuthResponse(error = response)
            }

            if (authResponse.token != null) {
                Result.success(authResponse.token)
            } else {
                Result.failure(Exception(authResponse.error ?: "Authentication failed"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = LatticeColors.BgPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp)
        ) {
            // Header
            Text(
                text = "Lattice Notes",
                color = LatticeColors.Accent,
                fontSize = 26.sp,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // Sync Panel
            SyncPanel(
                expanded = syncExpanded,
                onToggle = { syncExpanded = !syncExpanded },
                serverUrl = serverUrl,
                onServerUrlChange = { serverUrl = it },
                email = email,
                onEmailChange = { email = it },
                password = password,
                onPasswordChange = { password = it },
                isConnected = isConnected,
                syncStatus = syncStatus,
                authError = authError,
                isLoading = isLoading,
                onLogin = {
                    scope.launch {
                        isLoading = true
                        authError = null
                        authenticate(isLogin = true).fold(
                            onSuccess = { token ->
                                reconnectWithSync(token)
                            },
                            onFailure = { e ->
                                authError = e.message
                            }
                        )
                        isLoading = false
                    }
                },
                onRegister = {
                    scope.launch {
                        isLoading = true
                        authError = null
                        authenticate(isLogin = false).fold(
                            onSuccess = { token ->
                                reconnectWithSync(token)
                            },
                            onFailure = { e ->
                                authError = e.message
                            }
                        )
                        isLoading = false
                    }
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Input row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    placeholder = { Text("Type a note...", color = LatticeColors.TextMuted) },
                    modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = LatticeColors.BgInput,
                        unfocusedContainerColor = LatticeColors.BgInput,
                        focusedTextColor = LatticeColors.Text,
                        unfocusedTextColor = LatticeColors.Text,
                        focusedBorderColor = LatticeColors.Accent,
                        unfocusedBorderColor = Color.Transparent
                    ),
                    shape = RoundedCornerShape(8.dp),
                    singleLine = true
                )

                Button(
                    onClick = { addNote() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = LatticeColors.Accent,
                        contentColor = LatticeColors.BgPrimary
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Add", fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Notes list
            if (notes.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No notes yet. Add one!",
                        color = LatticeColors.TextMuted,
                        fontSize = 14.sp
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(notes, key = { it.hashCode() }) { note ->
                        NoteCard(note = note, onDelete = { deleteNote(note) })
                    }
                }
            }
        }
    }

    // Cleanup
    DisposableEffect(Unit) {
        onDispose {
            lattice?.close()
        }
    }
}

@Composable
fun SyncPanel(
    expanded: Boolean,
    onToggle: () -> Unit,
    serverUrl: String,
    onServerUrlChange: (String) -> Unit,
    email: String,
    onEmailChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    isConnected: Boolean,
    syncStatus: String,
    authError: String?,
    isLoading: Boolean,
    onLogin: () -> Unit,
    onRegister: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = LatticeColors.BgSecondary),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column {
            // Header (always visible)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggle() }
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Status dot
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .padding(0.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = if (isConnected) LatticeColors.Success else LatticeColors.TextMuted,
                            modifier = Modifier.size(8.dp)
                        ) {}
                    }
                    Text(
                        text = "Server Sync",
                        color = LatticeColors.Text,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "($syncStatus)",
                        color = LatticeColors.TextMuted,
                        fontSize = 12.sp
                    )
                }
                Text(
                    text = if (expanded) "−" else "+",
                    color = LatticeColors.Accent,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Expandable content
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (!isConnected) {
                        // Server URL
                        OutlinedTextField(
                            value = serverUrl,
                            onValueChange = onServerUrlChange,
                            label = { Text("Server URL", color = LatticeColors.TextMuted) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = syncTextFieldColors(),
                            shape = RoundedCornerShape(8.dp),
                            singleLine = true
                        )

                        // Email
                        OutlinedTextField(
                            value = email,
                            onValueChange = onEmailChange,
                            label = { Text("Email", color = LatticeColors.TextMuted) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = syncTextFieldColors(),
                            shape = RoundedCornerShape(8.dp),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
                        )

                        // Password
                        OutlinedTextField(
                            value = password,
                            onValueChange = onPasswordChange,
                            label = { Text("Password", color = LatticeColors.TextMuted) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = syncTextFieldColors(),
                            shape = RoundedCornerShape(8.dp),
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                        )

                        // Error message
                        if (authError != null) {
                            Text(
                                text = authError,
                                color = LatticeColors.Error,
                                fontSize = 12.sp
                            )
                        }

                        // Buttons
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = onLogin,
                                enabled = !isLoading && email.isNotBlank() && password.isNotBlank(),
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = LatticeColors.Accent,
                                    contentColor = LatticeColors.BgPrimary
                                ),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                if (isLoading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        color = LatticeColors.BgPrimary,
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Text("Login", fontWeight = FontWeight.Bold)
                                }
                            }

                            OutlinedButton(
                                onClick = onRegister,
                                enabled = !isLoading && email.isNotBlank() && password.isNotBlank(),
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = LatticeColors.Accent
                                ),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Register")
                            }
                        }
                    } else {
                        Text(
                            text = "Connected to $serverUrl",
                            color = LatticeColors.Success,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun syncTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = LatticeColors.BgInput,
    unfocusedContainerColor = LatticeColors.BgInput,
    focusedTextColor = LatticeColors.Text,
    unfocusedTextColor = LatticeColors.Text,
    focusedBorderColor = LatticeColors.Accent,
    unfocusedBorderColor = LatticeColors.BgCard,
    focusedLabelColor = LatticeColors.Accent,
    unfocusedLabelColor = LatticeColors.TextMuted
)

@Composable
fun NoteCard(note: Note, onDelete: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = LatticeColors.BgCard),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(15.dp)
        ) {
            Text(
                text = note.text,
                color = LatticeColors.Text,
                fontSize = 14.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = note.createdAt?.let {
                        val local = it.toLocalDateTime(TimeZone.currentSystemDefault())
                        "${local.date} ${local.hour}:${local.minute.toString().padStart(2, '0')}"
                    } ?: "",
                    color = LatticeColors.TextMuted,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )

                TextButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.textButtonColors(contentColor = LatticeColors.Error)
                ) {
                    Text("Delete", fontSize = 11.sp)
                }
            }
        }
    }
}
