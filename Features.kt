package com.vicsa.avacs

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.util.Base64
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.roundToInt

@Composable
fun MenuScreen(uid: String, go: (String) -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        listOf("Profile" to "profile", "Purse and services" to "purse", "Market" to "market",
            "My gallery" to "gallery:$uid", "Help (ranks)" to "help").forEach { (l, s) ->
            Button(onClick = { go(s) }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) { Text(l) }
        }
    }
}

@Composable
fun PurseScreen(uid: String) {
    var purse by remember { mutableStateOf(0L) }
    var color by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    fun load() { db().collection("users").document(uid).get().addOnSuccessListener { purse = it.getLong("purse") ?: 0L; color = it.getString("pmColor") ?: "" } }
    LaunchedEffect(uid) { load() }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Purse: $purse", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text("Private messages color: $color (changing costs 2)")
        Row {
            listOf("white", "black", "brown").forEach { c ->
                Button(onClick = {
                    spend(uid, 2, { db().collection("users").document(uid).update("pmColor", c).addOnSuccessListener { load(); msg = "Changed" } }, { msg = it })
                }, modifier = Modifier.padding(end = 6.dp)) { Text(c) }
            }
        }
        Text(msg, color = Color.Red)
    }
}

@Composable
fun MarketScreen(uid: String) {
    val types = listOf("Buy ID", "Sell ID", "Buy room", "Sell room")
    var type by remember { mutableStateOf(types[0]) }
    var ads by remember { mutableStateOf(listOf<Map<String, Any>>()) }
    var text by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    DisposableEffect(type) {
        val reg = db().collection("market").whereEqualTo("type", type).addSnapshotListener { s, _ ->
            ads = s?.documents?.sortedByDescending { it.getTimestamp("t")?.seconds ?: 0L }?.map { it.data ?: emptyMap() } ?: emptyList()
        }
        onDispose { reg.remove() }
    }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            types.forEach { t ->
                TextButton(onClick = { type = t }) { Text(t, fontWeight = if (t == type) FontWeight.Bold else FontWeight.Normal) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(text, { text = it }, modifier = Modifier.weight(1f), label = { Text("Your ad") })
            Button(onClick = {
                spend(uid, 2, {
                    db().collection("users").document(uid).get().addOnSuccessListener {
                        db().collection("market").add(mapOf("type" to type, "text" to text, "uid" to uid,
                            "name" to (it.getString("name") ?: ""), "t" to FieldValue.serverTimestamp()))
                        text = ""; msg = "Posted"
                    }
                }, { msg = it })
            }) { Text("Post (2)") }
        }
        Text(msg, color = Color.Red)
        LazyColumn { items(ads) { a -> Text("${a["name"]}: ${a["text"]}", Modifier.padding(vertical = 4.dp)) } }
    }
}

@Composable
fun HelpScreen(go: (String) -> Unit) {
    @Composable fun chip(r: Rank) {
        Text(r.title, color = Color.White, fontSize = 12.sp,
            modifier = Modifier.padding(2.dp).background(r.color, RoundedCornerShape(6.dp))
                .clickable { go("users:${r.name}") }.padding(horizontal = 8.dp, vertical = 3.dp))
    }
    val tiers = listOf(listOf(Rank.ADMIN), listOf(Rank.TOP, Rank.SUPERMOD, Rank.MERCHANT),
        listOf(Rank.ROOM_OWNER, Rank.ROOM_MOD, Rank.HIRO, Rank.NEO, Rank.LONG_LIVER))
    Column(Modifier.fillMaxSize().padding(10.dp)) {
        Text("Ranks (tap a rank to see its users)", fontWeight = FontWeight.Bold)
        tiers.forEach { t ->
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 3.dp)) { t.forEach { chip(it) } }
        }
        Row(Modifier.padding(vertical = 3.dp)) {
            Column(Modifier.weight(1f)) { chip(Rank.ELDER_MALE); chip(Rank.MALE) }
            Column(Modifier.weight(1f)) { chip(Rank.ELDER_FEMALE); chip(Rank.FEMALE) }
        }
    }
}

@Composable
fun UsersScreen(rank: String, open: (String) -> Unit) {
    var list by remember { mutableStateOf(listOf<Pair<String, String>>()) }
    LaunchedEffect(rank) {
        db().collection("users").whereEqualTo("rank", rank).get().addOnSuccessListener { s ->
            list = s.documents.map { it.id to (it.getString("name") ?: "") }
        }
    }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text(Rank.of(rank).title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        LazyColumn { items(list) { (id, n) -> Text(n, Modifier.clickable { open(id) }.padding(vertical = 8.dp).fillMaxWidth()) } }
    }
}
