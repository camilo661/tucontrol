package com.tucontrol.presentation.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.ConnectedTv
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.ui.graphics.vector.ImageVector
import com.tucontrol.domain.model.TvBrand

fun TvBrand.icon(): ImageVector = when (this) {
    TvBrand.ANDROID_TV -> Icons.Rounded.Cast
    TvBrand.ROKU -> Icons.Rounded.LiveTv
    TvBrand.SAMSUNG, TvBrand.LG, TvBrand.SONY -> Icons.Rounded.ConnectedTv
    TvBrand.UNKNOWN -> Icons.Rounded.Tv
}
