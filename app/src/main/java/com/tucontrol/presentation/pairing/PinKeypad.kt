package com.tucontrol.presentation.pairing

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.tucontrol.data.tv.androidtv.PairingSecret
import com.tucontrol.presentation.common.rememberHaptics

private val KEY_ROWS = listOf("0123", "4567", "89AB", "CDEF")
private val KEY_SIZE = 32.dp
private val KEY_GAP = 4.dp

/**
 * On-screen keypad for the Android TV pairing code. The TV always shows 6 hexadecimal
 * characters (0–9, A–F), so only those 16 keys are offered and the code is sent as soon
 * as the sixth one is typed — far quicker than the system keyboard before the TV gives up.
 */
@Composable
fun PinKeypad(
    invalid: Boolean,
    onSubmit: (String) -> Unit,
) {
    var code by rememberSaveable { mutableStateOf("") }
    val haptics = rememberHaptics(enabled = true)

    // A rejected code is cleared so the next attempt starts fresh.
    LaunchedEffect(invalid) { if (invalid) code = "" }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CodeSlots(code = code, invalid = invalid)
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .clickable(enabled = code.isNotEmpty(), role = Role.Button) {
                        haptics.tick()
                        code = code.dropLast(1)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.Backspace,
                    contentDescription = "Borrar",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Box(Modifier.size(KEY_GAP * 2))
        Column(verticalArrangement = Arrangement.spacedBy(KEY_GAP)) {
            KEY_ROWS.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(KEY_GAP)) {
                    row.forEach { key ->
                        Key(key.toString()) {
                            if (code.length < PairingSecret.CODE_LENGTH) {
                                haptics.tick()
                                code += key
                                if (code.length == PairingSecret.CODE_LENGTH) onSubmit(code)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CodeSlots(code: String, invalid: Boolean) {
    val colors = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(PairingSecret.CODE_LENGTH) { index ->
            val char = code.getOrNull(index)
            Box(
                modifier = Modifier
                    .size(width = 14.dp, height = 20.dp)
                    .background(
                        color = when {
                            invalid -> colors.error.copy(alpha = 0.25f)
                            index == code.length -> colors.primary.copy(alpha = 0.3f)
                            else -> colors.surfaceContainer
                        },
                        shape = RoundedCornerShape(4.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = char?.toString().orEmpty(),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurface,
                )
            }
        }
    }
}

@Composable
private fun Key(label: String, onClick: () -> Unit) {
    val isLetter = label[0].isLetter()
    Box(
        modifier = Modifier
            .size(KEY_SIZE)
            .clip(CircleShape)
            .background(
                if (isLetter) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainerHigh,
            )
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (isLetter) MaterialTheme.colorScheme.onPrimaryContainer else Color.Unspecified,
        )
    }
}
