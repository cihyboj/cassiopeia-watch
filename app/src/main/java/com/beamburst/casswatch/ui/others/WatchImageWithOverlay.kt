// Modified from CasioGShockSmartSync (https://github.com/izivkov/CasioGShockSmartSync) for Cassiopeia Watch. See NOTICE.
package com.beamburst.casswatch.ui.others

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.beamburst.casswatch.R

@Composable
fun WatchImage(
    modifier: Modifier = Modifier,
    imageResId: Int = R.drawable.cassiopeia_watch
) {
    // The generic artwork does not identify model-specific buttons. Keep it decorative
    // rather than pointing to controls whose position and behavior vary by watch.
    Image(
        painter = painterResource(id = imageResId),
        contentDescription = null,
        modifier = modifier.fillMaxSize(),
        contentScale = ContentScale.Fit
    )
}
