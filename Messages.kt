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

fun db() = Firebase.firestore

fun timeAgo(ms: Long): String {
    val s = (System.currentTimeMillis() - ms) / 1000
    return when { s < 60 -> "${s}s ago"; s < 3600 -> "${s / 60}m ago"; s < 86400 -> "${s / 3600}h ago"; else -> "${s / 86400}d ago" }
}

/** Deducts [amount] from the user's Purse (in-app currency). */
fun spend(uid: String, amount: Long, onOk: () -> Unit, onFail: (String) -> Unit) {
    val ref = db().collection("users").document(uid)
    db().runTransaction { t ->
        val p = t.get(ref).getLong("purse") ?: 0L
        if (p < amount) throw Exception("Not enough in Purse")
        t.update(ref, "purse", p - amount)
    }.addOnSuccessListener { onOk() }.addOnFailureListener { onFail(it.message ?: "Error") }
}

fun uriToB64(ctx: Context, uri: Uri, max: Int): String? = try {
    val bmp = BitmapFactory.decodeStream(ctx.contentResolver.openInputStream(uri))
    val sc = max.toFloat() / maxOf(bmp.width, bmp.height)
    val b = if (sc < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * sc).toInt(), (bmp.height * sc).toInt(), true) else bmp
    val o = ByteArrayOutputStream()
    b.compress(Bitmap.CompressFormat.JPEG, 70, o)
    Base64.encodeToString(o.toByteArray(), Base64.NO_WRAP)
} catch (e: Exception) { null }

fun b64ToBitmap(s: String): androidx.compose.ui.graphics.ImageBitmap {
    val b = Base64.decode(s, Base64.NO_WRAP)
    return BitmapFactory.decodeByteArray(b, 0, b.size).asImageBitmap()
}

fun pmColor(s: String?) = when (s) {
    "white" -> Color.White; "black" -> Color.Black; "brown" -> Color(0xFF795548); else -> Color(0xFF333333)
}

// ---------- Voice messages (free, max 15s, stored as small base64 clips) ----------
fun startRec(f: File): MediaRecorder? {
    val r = MediaRecorder()
    return try {
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
        r.setMaxDuration(15000)
        r.setOutputFile(f.path)
        r.prepare(); r.start(); r
    } catch (e: Exception) { r.release(); null }
}

@Composable
fun VoiceButton(onSend: (String) -> Unit) {
    val ctx = LocalContext.current
    var rec by remember { mutableStateOf(false) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    Box(
        Modifier.padding(4.dp).size(44.dp)
            .background(if (rec) Color.Red else Color(0xFF1E88E5), CircleShape)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        perm.launch(Manifest.permission.RECORD_AUDIO)
                    } else {
                        val f = File(ctx.cacheDir, "rec.3gp")
                        val r = startRec(f)
                        if (r != null) {
                            rec = true
                            tryAwaitRelease()
                            rec = false
                            try { r.stop(); r.release(); onSend(Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)) }
                            catch (e: Exception) { r.release() }
                        }
                    }
                })
            },
        contentAlignment = Alignment.Center
    ) { Text("🎤") }
}

@Composable
fun VoicePill(b64: String) {
    val ctx = LocalContext.current
    Text("▶ voice", color = Color.White, fontSize = 12.sp,
        modifier = Modifier.background(Color(0xFF1E88E5), RoundedCornerShape(12.dp)).clickable {
            try {
                val f = File(ctx.cacheDir, "play.3gp")
                f.writeBytes(Base64.decode(b64, Base64.NO_WRAP))
                MediaPlayer().apply { setDataSource(f.path); prepare(); start(); setOnCompletionListener { release() } }
            } catch (e: Exception) { }
        }.padding(horizontal = 10.dp, vertical = 2.dp))
}

// ---------- Private messages ----------
data class Pm(val id: String, val from: String, val name: String, val rank: Rank, val text: String, val color: Color, val t: Long)

@Composable
fun PmHost(uid: String, onAbout: (String) -> Unit, onReply: (String) -> Unit) {
    var queue by remember { mutableStateOf(listOf<Pm>()) }
    var off by remember { mutableStateOf(Offset(40f, 200f)) }
    DisposableEffect(uid) {
        val reg = db().collection("pm").whereEqualTo("to", uid).whereEqualTo("shown", false)
            .addSnapshotListener { s, _ ->
                queue = s?.documents?.map { d ->
                    Pm(d.id, d.getString("from") ?: "", d.getString("fromName") ?: "", Rank.of(d.getString("fromRank")),
                        d.getString("text") ?: "", pmColor(d.getString("color")),
                        d.getTimestamp("t")?.toDate()?.time ?: System.currentTimeMillis())
                } ?: emptyList()
            }
        onDispose { reg.remove() }
    }
    val m = queue.firstOrNull()
    if (m != null) {
        // Header colors per rank: only Administrator, Top user, Supermoderator so far; others use the default.
        val head = when (m.rank) {
            Rank.ADMIN -> Color(0xFFFF6A00); Rank.TOP -> Color(0xFFD4A000); Rank.SUPERMOD -> Color(0xFFFFE600)
            else -> Color(0xFFE8472B)
        }
        val headText = if (m.rank == Rank.SUPERMOD) Color.Black else Color.White
        val close = { db().collection("pm").document(m.id).update("shown", true); Unit }
        Column(
            Modifier.offset { IntOffset(off.x.roundToInt(), off.y.roundToInt()) }.width(280.dp)
                .pointerInput(Unit) { detectDragGestures { c, d -> c.consume(); off += d } }
        ) {
            Row(Modifier.fillMaxWidth().background(head).padding(8.dp)) {
                Text("${m.name}  ${timeAgo(m.t)}", color = headText, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                Text("✕", color = headText, modifier = Modifier.clickable { close() })
            }
            Column(Modifier.fillMaxWidth().background(Color(0xFFFFA07A)).padding(8.dp)) {
                Text(m.text, color = m.color)
                Row {
                    Button(onClick = { close(); onReply(m.from) }) { Text("Reply") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { close(); onAbout(m.from) }) { Text("About user") }
                }
            }
        }
    }
}

@Composable
fun PmCompose(uid: String, to: String, back: () -> Unit) {
    var toName by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    LaunchedEffect(to) { db().collection("users").document(to).get().addOnSuccessListener { toName = it.getString("name") ?: "" } }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Private message to $toName", fontWeight = FontWeight.Bold)
        OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            db().collection("users").document(uid).get().addOnSuccessListener { me ->
                db().collection("pm").add(mapOf(
                    "to" to to, "from" to uid, "fromName" to (me.getString("name") ?: ""),
                    "fromRank" to (me.getString("rank") ?: "MALE"), "color" to (me.getString("pmColor") ?: ""),
                    "text" to text, "shown" to false, "t" to FieldValue.serverTimestamp()
                )).addOnSuccessListener { msg = "Sent"; text = "" }
            }
        }) { Text("Send") }
        Text(msg)
    }
}
