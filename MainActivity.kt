package com.vicsa.avacs

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = lightColorScheme()) { App() } }
    }
}

data class Msg(val name: String, val text: String, val rank: Rank, val voice: String? = null)

@Composable
fun App() {
    var uid by remember { mutableStateOf(Firebase.auth.currentUser?.uid) }
    var stack by remember { mutableStateOf(listOf("room")) }
    val id = uid
    if (id == null) {
        AuthScreen { uid = it; stack = listOf("room") }
    } else {
        val s = stack.last()
        BackHandler(stack.size > 1) { stack = stack.dropLast(1) }
        val go: (String) -> Unit = { x -> stack = stack + x }
        val back: () -> Unit = { if (stack.size > 1) stack = stack.dropLast(1) }
        Box(Modifier.fillMaxSize()) {
            when {
                s == "room" -> RoomScreen(id) { go("menu") }
                s == "menu" -> MenuScreen(id, go)
                s == "profile" -> ProfileScreen(id, back) { Firebase.auth.signOut(); uid = null }
                s == "purse" -> PurseScreen(id)
                s == "market" -> MarketScreen(id)
                s == "help" -> HelpScreen(go)
                s.startsWith("users:") -> UsersScreen(s.removePrefix("users:")) { go("user:$it") }
                s.startsWith("user:") -> UserScreen(id, s.removePrefix("user:"), go)
                s.startsWith("gallery:") -> GalleryScreen(id, s.removePrefix("gallery:"))
                s.startsWith("pm:") -> PmCompose(id, s.removePrefix("pm:"), back)
            }
            PmHost(id, { go("user:$it") }, { go("pm:$it") })
        }
    }
}

@Composable
fun AuthScreen(onDone: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var err by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        OutlinedTextField(name, { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(email, { email = it }, label = { Text("Email") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(pass, { pass = it }, label = { Text("Password") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        Row {
            Button(onClick = {
                Firebase.auth.signInWithEmailAndPassword(email, pass)
                    .addOnSuccessListener { onDone(it.user!!.uid) }
                    .addOnFailureListener { err = it.message ?: "Error" }
            }) { Text("Login") }
            Spacer(Modifier.width(12.dp))
            Button(onClick = {
                Firebase.auth.createUserWithEmailAndPassword(email, pass)
                    .addOnSuccessListener { r ->
                        val id = r.user!!.uid
                        Firebase.firestore.collection("users").document(id)
                            .set(mapOf("name" to name, "verified" to false, "purse" to 0L, "rank" to Rank.MALE.name))
                            .addOnSuccessListener { onDone(id) }
                    }
                    .addOnFailureListener { err = it.message ?: "Error" }
            }) { Text("Sign up") }
        }
        Text(err, color = Color.Red)
    }
}

@Composable
fun RoomScreen(uid: String, openMenu: () -> Unit) {
    var msgs by remember { mutableStateOf(listOf<Msg>()) }
    var me by remember { mutableStateOf(Msg("", "", Rank.MALE)) }
    var text by remember { mutableStateOf("") }
    val col = Firebase.firestore.collection("rooms/main/messages")

    LaunchedEffect(uid) {
        Firebase.firestore.collection("users").document(uid).get().addOnSuccessListener {
            me = Msg(it.getString("name") ?: "", "", Rank.of(it.getString("rank")))
        }
    }
    DisposableEffect(Unit) {
        val reg = col.orderBy("t").limitToLast(100).addSnapshotListener { s, _ ->
            msgs = s?.documents?.map {
                Msg(it.getString("name") ?: "", it.getString("text") ?: "", Rank.of(it.getString("rank")), it.getString("voice"))
            } ?: emptyList()
        }
        onDispose { reg.remove() }
    }

    Column(Modifier.fillMaxSize().background(Color.White)) {
        Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("News: welcome to the room", color = Color.Red, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(" ${msgs.map { it.name }.distinct().size} ", color = Color.White,
                modifier = Modifier.background(Color(0xFF1E88E5)).padding(horizontal = 6.dp))
        }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            items(msgs) { m ->
                Row(Modifier.padding(vertical = 2.dp)) {
                    if (m.rank.hasCrown) Text("👑", fontSize = 12.sp)
                    Text("${m.name}: ", color = m.rank.color, fontWeight = FontWeight.Bold)
                    if (m.voice != null) VoicePill(m.voice) else Text(m.text, color = m.rank.color)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(text, { text = it }, modifier = Modifier.weight(1f))
            Button(onClick = {
                if (text.isNotBlank()) {
                    col.add(mapOf("name" to me.name, "text" to text, "rank" to me.rank.name, "t" to FieldValue.serverTimestamp()))
                    text = ""
                }
            }) { Text("Send") }
            VoiceButton { v -> col.add(mapOf("name" to me.name, "text" to "", "voice" to v, "rank" to me.rank.name, "t" to FieldValue.serverTimestamp())) }
            TextButton(onClick = openMenu) { Text("Menu") }
        }
    }
}

@Composable
fun ProfileScreen(uid: String, back: () -> Unit, logout: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var purse by remember { mutableStateOf(0L) }
    var verified by remember { mutableStateOf(false) }
    LaunchedEffect(uid) {
        Firebase.firestore.collection("users").document(uid).get().addOnSuccessListener {
            name = it.getString("name") ?: ""
            purse = it.getLong("purse") ?: 0L
            verified = it.getBoolean("verified") ?: false
        }
    }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Profile", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Row { Text("Name", Modifier.weight(1f)); Text(name) }
        Row { Text("Purse", Modifier.weight(1f)); Text("$purse") }
        Row { Text("Verify", Modifier.weight(1f)); if (verified) Text("✔", color = Color(0xFF1E88E5)) }
        Spacer(Modifier.height(16.dp))
        Row {
            Button(onClick = back) { Text("Back") }
            Spacer(Modifier.width(12.dp))
            OutlinedButton(onClick = logout) { Text("Logout") }
        }
    }
}
