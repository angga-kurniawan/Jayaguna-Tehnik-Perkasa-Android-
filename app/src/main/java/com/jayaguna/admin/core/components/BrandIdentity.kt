package com.jayaguna.admin.core.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jayaguna.admin.R
import com.jayaguna.admin.core.theme.JayagunaBrand

@Composable
fun BrandIdentity(modifier: Modifier = Modifier, compact: Boolean = false) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Image(painterResource(R.drawable.jayaguna_logo), "Logo PT Jayaguna Tehnik Perkasa",
            Modifier.size(if (compact) 40.dp else 54.dp), contentScale = ContentScale.Fit)
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text("Jayaguna", style = MaterialTheme.typography.titleLarge, color = JayagunaBrand.Navy)
            Text("TEHNIK PERKASA", fontSize = if (compact) 8.sp else 10.sp,
                fontWeight = FontWeight.SemiBold, letterSpacing = 1.6.sp, color = JayagunaBrand.Navy)
        }
    }
}

@Composable
fun BrandEyebrow(text: String, modifier: Modifier = Modifier, color: Color = JayagunaBrand.Red) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Box(Modifier.width(20.dp).height(2.dp).background(color))
        Text(text, style = MaterialTheme.typography.labelMedium, color = color, letterSpacing = 1.8.sp)
    }
}
