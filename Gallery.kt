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
fun UserScreen(me: String, uid: String, go: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var rank by remember { mutableStateOf(Rank.MALE) }
    var photo by remember { mutableStateOf<String?>(null) }
    var likes by remember { mutableStateOf(0L) }
    var msg by remember { mutableStateOf("") }
    val ur = db().collection("users").document(uid)
    fun load() { ur.get().addOnSuccessListener { name = it.getString("name") ?: ""; rank = Rank.of(it.getString("rank")); photo = it.getString("photo"); likes = it.getLong("likes") ?: 0L } }
    LaunchedEffect(uid) { load() }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        val p = photo
        if (p != null) Image(remember(p) { b64ToBitmap(p) }, null, Modifier.size(160.dp))
        Text(name, color = rank.color, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Text("${rank.title}   ♥ $likes")
        Row {
            Button(onClick = {
                val lr = ur.collection("likes").document(me)
                db().runTransaction { t ->
                    if (!t.get(lr).exists()) { t.set(lr, mapOf("t" to 1)); t.update(ur, "likes", FieldValue.increment(1)) }
                    null
                }.addOnSuccessListener { load() }
            }) { Text("Like") }
            Spacer(Modifier.width(6.dp))
            Button(onClick = { go("pm:$uid") }) { Text("Message") }
        }
        Row {
            Button(onClick = { go("gallery:$uid") }) { Text("Gallery") }
            Spacer(Modifier.width(6.dp))
            OutlinedButton(onClick = {
                db().collection("users").document(me).collection("contacts").document(uid).set(mapOf("t" to 1))
                    .addOnSuccessListener { msg = "Added to contacts" }
            }) { Text("Add contact") }
        }
        Text(msg)
    }
}

@Composable
fun GalleryScreen(me: String, owner: String) {
    val ctx = LocalContext.current
    val own = me == owner
    val uref = db().collection("users").document(owner)
    var photo by remember { mutableStateOf<String?>(null) }
    var likes by remember { mutableStateOf(0L) }
    var albums by remember { mutableStateOf(listOf<DocumentSnapshot>()) }
    var open by remember { mutableStateOf<DocumentSnapshot?>(null) }
    var photos by remember { mutableStateOf(listOf<String>()) }
    var pending by remember { mutableStateOf<DocumentSnapshot?>(null) }
    var target by remember { mutableStateOf<DocumentSnapshot?>(null) }
    var msg by remember { mutableStateOf("") }
    var newName by remember { mutableStateOf("") }
    var newMode by remember { mutableStateOf("open") }
    var newPrice by remember { mutableStateOf("") }

    fun load() {
        uref.get().addOnSuccessListener { photo = it.getString("photo"); likes = it.getLong("likes") ?: 0L }
        uref.collection("albums").get().addOnSuccessListener { albums = it.documents }
    }
    LaunchedEffect(owner) { load() }

    // Screenshots are blocked while a paid album is open (owner excluded).
    val secure = !own && open?.getString("mode") == "paid"
    DisposableEffect(secure) {
        val w = (ctx as? Activity)?.window
        if (secure) w?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { w?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }

    val pickProfile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u ->
        if (u != null) uriToB64(ctx, u, 300)?.let { uref.update("photo", it).addOnSuccessListener { load() } }
    }
    val pickAlbum = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u ->
        val a = target
        if (u != null && a != null) uriToB64(ctx, u, 400)?.let {
            a.reference.collection("photos").add(mapOf("d" to it, "t" to FieldValue.serverTimestamp())).addOnSuccessListener { msg = "Photo added" }
        }
    }

    fun openAlbum(a: DocumentSnapshot) {
        val mode = a.getString("mode") ?: "open"
        val price = a.getLong("price") ?: 0L
        fun show() { open = a; pending = null; a.reference.collection("photos").get().addOnSuccessListener { s -> photos = s.documents.mapNotNull { it.getString("d") } } }
        if (own || mode == "open") show()
        else if (mode == "contacts") uref.collection("contacts").document(me).get().addOnSuccessListener { if (it.exists()) show() else msg = "Contacts only" }
        else db().collection("users").document(me).collection("unlocked").document(a.id).get().addOnSuccessListener {
            if (it.exists()) show() else { pending = a; msg = "Pay $price from your Purse to view" }
        }
    }
    fun pay(a: DocumentSnapshot) {
        val price = a.getLong("price") ?: 0L
        val mr = db().collection("users").document(me)
        val ur = mr.collection("unlocked").document(a.id)
        db().runTransaction { t ->
            val p = t.get(mr).getLong("purse") ?: 0L
            if (p < price) throw Exception("Not enough in Purse")
            t.update(mr, "purse", p - price)
            t.update(uref, "purse", FieldValue.increment(price))
            t.set(ur, mapOf("t" to 1))
            null
        }.addOnSuccessListener { openAlbum(a) }.addOnFailureListener { msg = it.message ?: "Error" }
    }

    LazyColumn(Modifier.fillMaxSize().padding(12.dp)) {
        item {
            val p = photo
            if (p != null) Image(remember(p) { b64ToBitmap(p) }, null, Modifier.size(160.dp))
            Text("♥ $likes")
            if (own) {
                Button(onClick = { pickProfile.launch("image/*") }) { Text("Set profile photo") }
                OutlinedTextField(newName, { newName = it }, label = { Text("New album name") })
                Row { listOf("open", "contacts", "paid").forEach { m ->
                    TextButton(onClick = { newMode = m }) { Text(m, fontWeight = if (m == newMode) FontWeight.Bold else FontWeight.Normal) } } }
                if (newMode == "paid") OutlinedTextField(newPrice, { newPrice = it }, label = { Text("Price (Purse)") })
                Button(onClick = {
                    uref.collection("albums").add(mapOf("name" to newName, "mode" to newMode, "price" to (newPrice.toLongOrNull() ?: 0L)))
                        .addOnSuccessListener { newName = ""; load() }
                }) { Text("Create album") }
            }
            Text(msg, color = Color.Red)
            val pd = pending
            if (pd != null) Button(onClick = { pay(pd) }) { Text("Pay and view") }
            Text("Albums", fontWeight = FontWeight.Bold)
        }
        items(albums) { a ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${a.getString("name")} [${a.getString("mode")}]", Modifier.weight(1f).clickable { openAlbum(a) })
                if (own) TextButton(onClick = { target = a; pickAlbum.launch("image/*") }) { Text("+ photo") }
            }
        }
        if (open != null) items(photos) { p -> Image(remember(p) { b64ToBitmap(p) }, null, Modifier.fillMaxWidth().padding(vertical = 4.dp)) }
    }
}
