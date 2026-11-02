package com.vicsa.avacs

import androidx.compose.ui.graphics.Color

// Colors are placeholders; they will be matched to your screenshots.
enum class Rank(val title: String, val color: Color) {
    ADMIN("Administrator", Color(0xFFFF6A00)),
    TOP("Top user", Color(0xFFD4A000)),
    MERCHANT("Merchant", Color(0xFF2E7D32)),
    SUPERMOD("Supermoderator", Color(0xFFE6C800)),
    ROOM_OWNER("Room owner or creator", Color(0xFF8E24AA)),
    ROOM_MOD("Room moderator", Color(0xFF6A1B9A)),
    HIRO("Hiro", Color(0xFFC62828)),
    NEO("Neo", Color(0xFF00897B)),
    LONG_LIVER("Long liver", Color(0xFF5D4037)),
    ELDER_MALE("Elder/Veteran/Haunter - male", Color(0xFF1565C0)),
    MALE("Male", Color(0xFF1E88E5)),
    ELDER_FEMALE("Elder/Veteran/Haunter - female", Color(0xFFAD1457)),
    FEMALE("Female", Color(0xFFEC407A));

    val hasCrown get() = ordinal <= ROOM_MOD.ordinal

    companion object {
        fun of(s: String?) = values().firstOrNull { it.name == s } ?: MALE
    }
}
